#!/usr/bin/env clojure -M:dev

;; Throughput test: 2 nodes, 10 tenants, 1000 events, measure processing rate.
;; Usage: clojure -M:dev scripts/throughput-test.clj

(load-file "scripts/test-helpers.clj")

(def cf "docker-compose.cp-test.yml")
(def port-a 7890)
(def port-b 7891)
(def n-tenants 500)
(def events-per-tenant 100)
(def total-events (* n-tenants events-per-tenant))

;; -------------------------------- ;;
;; Cluster lifecycle                ;;
;; -------------------------------- ;;

(defn start-cluster! []
  (info "Starting 2-node cluster...")
  (compose cf "down" "-v")
  (compose cf "build")
  (compose cf "up" "-d")
  (info "Waiting for nodes...")
  (let [a-ok (wait-for-port port-a 60000)
        b-ok (wait-for-port port-b 60000)]
    (when-not a-ok (fail "Node A did not start"))
    (when-not b-ok (fail "Node B did not start"))
    (Thread/sleep 8000)
    (setup-node! port-a)
    (setup-node! port-b)
    (and a-ok b-ok)))

(defn stop-cluster! []
  (info "Stopping cluster...")
  (compose cf "down" "-v"))

;; -------------------------------- ;;
;; Scenarios                        ;;
;; -------------------------------- ;;

(defn scenario-setup []
  (header "Throughput: Setup")
  (info (str "Creating " n-tenants " tenants..."))
  (let [tids (create-tenants! port-a n-tenants)]
    (info "Waiting for assignment (6s)...")
    (Thread/sleep 6000)
    (let [sa (node-status port-a)]
      (check (str n-tenants " leases assigned (got " (:leases sa) ")")
             (= n-tenants (:leases sa))))
    tids))

(defn scenario-blast [tenant-ids]
  (header (str "Throughput: Blast " total-events " events"))
  (let [tids-str (pr-str tenant-ids)]
    (info (str "Appending " events-per-tenant " events to each of " n-tenants " tenants..."))
    (let [start-ms (System/currentTimeMillis)]
      ;; Bulk append via nREPL — all events in one call to minimize round-trip overhead
      (eval-on port-a
        (format "(let [s @app/app
                       tids %s]
                  (doseq [tid-str tids
                          _ (range %d)]
                    (app/increment! s (java.util.UUID/fromString tid-str)))
                  :done)"
                tids-str events-per-tenant))
      (let [append-ms (- (System/currentTimeMillis) start-ms)]
        (metric (str "Append time: " append-ms "ms ("
                     (format "%.0f" (double (/ (* total-events 1000) append-ms)))
                     " events/sec)"))
        ;; Wait for processing
        (info "Waiting for processing to complete...")
        (let [poll-start (System/currentTimeMillis)
              timeout-ms 120000]
          (loop [i 0]
            (let [all-done (every?
                            (fn [tid]
                              (let [inc-c (increment-count port-a tid)
                                    proc-c (processed-count port-a tid)]
                                (= inc-c proc-c)))
                            tenant-ids)]
              (if all-done
                (let [total-ms (- (System/currentTimeMillis) start-ms)
                      process-ms (- (System/currentTimeMillis) poll-start)]
                  (metric (str "Total time (append + process): " total-ms "ms"))
                  (metric (str "End-to-end throughput: "
                               (format "%.0f" (double (/ (* total-events 1000) total-ms)))
                               " events/sec")))
                (if (> (- (System/currentTimeMillis) poll-start) timeout-ms)
                  (fail "Processing did not complete within timeout")
                  (do (Thread/sleep 1000) (recur (inc i))))))))
        tenant-ids))))

(defn diagnose-tenant [port tid]
  (eval-read port
    (format "(app/diagnose-tenant @app/app (java.util.UUID/fromString \"%s\"))" tid)))

(defn scenario-verify [tenant-ids]
  (header "Throughput: Verification")
  (let [all-ok (atom true)
        total-missing (atom 0)]
    (doseq [tid tenant-ids]
      (let [diag (diagnose-tenant port-a tid)]
        (when (pos? (:missing-count diag))
          (reset! all-ok false)
          (swap! total-missing + (:missing-count diag))
          (fail (str "Tenant " tid ":"
                     " inc=" (:increments diag)
                     " proc=" (:processed diag)
                     " ckpt=" (:checkpoints diag)
                     " missing=" (:missing-count diag)
                     " dupes=" (count (:duplicate-processed diag)))))
        (when (pos? (count (:duplicate-processed diag)))
          (reset! all-ok false)
          (fail (str "Tenant " tid ": DUPLICATES " (:duplicate-processed diag))))))
    ;; Aggregate
    (let [total-inc (reduce + (map #(increment-count port-a %) tenant-ids))
          total-proc (reduce + (map #(processed-count port-a %) tenant-ids))]
      (metric (str "Total increments: " total-inc))
      (metric (str "Total processed:  " total-proc))
      (metric (str "Total missing:    " @total-missing))
      (check (str "All tenants: zero missing events") (zero? @total-missing))
      (check (str "Grand total: " total-inc " == " total-proc)
             (= total-inc total-proc)))
    ;; Detailed diagnosis for first failing tenant
    (when-not @all-ok
      (header "Throughput: Detailed diagnosis (first failing tenant)")
      (let [failing-tid (first (filter (fn [tid]
                                         (let [d (diagnose-tenant port-a tid)]
                                           (pos? (:missing-count d))))
                                       tenant-ids))]
        (when failing-tid
          (let [diag (diagnose-tenant port-a failing-tid)]
            (info (str "Tenant: " failing-tid))
            (info (str "Increments: " (:increments diag)))
            (info (str "Processed:  " (:processed diag)))
            (info (str "Checkpoints: " (:checkpoints diag)))
            (info (str "Missing count: " (:missing-count diag)))
            (info (str "Missing event IDs: " (pr-str (:missing-event-ids diag))))
            (info (str "Duplicates: " (pr-str (:duplicate-processed diag))))
            (info (str "Uncheckpointed: " (:uncheckpointed-count diag)))
            ;; Check which node owns this tenant
            (let [leases (lease-details port-a)
                  owner (->> leases (filter #(= failing-tid (:tenant %))) first :owner)
                  node-a-id (:node-id (node-status port-a))
                  owner-label (if (= owner node-a-id) "node-a" "node-b")]
              (info (str "Lease owner: " owner-label " (" owner ")"))
              ;; Get bridge trace for this tenant from the owning node
              (let [owner-port (if (= owner node-a-id) port-a port-b)
                    traces (eval-read owner-port
                             (format "(vec (filter (fn [t] (= \"%s\" (:tenant t)))
                                                   (app/bridge-trace @app/app)))"
                                     failing-tid))]
                (info (str "Bridge traces for this tenant: " (count traces)))
                (doseq [t (take 5 traces)]
                  (info (str "  wm-before=" (:watermark-before t)
                             " wm-after=" (:watermark-after t)
                             " read=" (:events-read t))))))))))))

;; -------------------------------- ;;
;; Main                             ;;
;; -------------------------------- ;;

(println "\n╔══════════════════════════════════════════╗")
(println "║  Grain Control Plane Throughput Test     ║")
(println "╚══════════════════════════════════════════╝")

(when-not (start-cluster!)
  (stop-cluster!)
  (System/exit 1))

(try
  (let [tids (scenario-setup)
        tids (scenario-blast tids)]
    (scenario-verify tids))
  (catch Throwable t
    (swap! results update :error inc)
    (println (str "\n  !! Error: " (.getMessage t)))
    (.printStackTrace t)))

(stop-cluster!)
(report-and-exit!)
