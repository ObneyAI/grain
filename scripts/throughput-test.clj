#!/usr/bin/env clojure -M:dev

;; Throughput test: 3 nodes, 1000 tenants, 100k events, measure processing rate.
;; Usage: clojure -M:dev scripts/throughput-test.clj

(load-file "scripts/test-helpers.clj")

(def cf "docker-compose.cp-test.yml")
(def port-a 7890)
(def port-b 7891)
(def port-c 7892)
(def all-ports [port-a port-b port-c])
(def n-tenants 1000)
(def events-per-tenant 100)
(def total-events (* n-tenants events-per-tenant))

;; -------------------------------- ;;
;; Cluster lifecycle                ;;
;; -------------------------------- ;;

(defn start-cluster! []
  (info "Starting 3-node cluster...")
  (compose cf "down" "-v")
  (compose cf "build")
  ;; Start postgres first, then node-a to create schema, then the rest
  (compose cf "up" "-d" "postgres")
  (Thread/sleep 5000)
  (compose cf "up" "-d" "node-a")
  (wait-for-port port-a 60000)
  (Thread/sleep 3000)
  (compose cf "up" "-d")
  (info "Waiting for all nodes...")
  (let [ok (every? true? (map #(wait-for-port % 90000) all-ports))]
    (when-not ok (fail "Not all nodes started"))
    (Thread/sleep 12000)
    (doseq [p all-ports] (setup-node! p))
    ok))

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
    (info "Waiting for assignment (15s)...")
    (Thread/sleep 15000)
    (let [sa (node-status port-a)]
      (check (str n-tenants " leases assigned (got " (:leases sa) ")")
             (= n-tenants (:leases sa)))
      (let [statuses (mapv #(try (node-status %) (catch Exception _ nil)) all-ports)
            running-total (reduce + (map #(or (:running %) 0) statuses))]
        (metric (str "Nodes active: " (count (filter some? statuses))))
        (metric (str "Tenants distributed: " running-total " across " (count all-ports) " nodes"))))
    tids))

(defn- tenants-per-node
  "Query which tenants each node owns via lease-ownership."
  []
  (let [leases (eval-read port-a
                 "(let [s @app/app]
                    (->> (app/leases s)
                         (group-by val)
                         (map (fn [[owner entries]]
                                [(str owner) (mapv #(str (key %)) entries)]))
                         (into {})))")]
    ;; Map node-id -> [tenant-id-strings], then match to ports
    (let [node-ids (mapv #(:node-id (node-status %)) all-ports)]
      (mapv (fn [port nid]
              {:port port
               :node-id nid
               :tenants (get leases nid [])})
            all-ports node-ids))))

(defn scenario-blast [tenant-ids]
  (header (str "Throughput: Blast " total-events " events (concurrent, 3 nodes)"))
  (info (str "Appending " events-per-tenant " events to each of " n-tenants " tenants..."))
  (info "Each node appends to its own assigned tenants in parallel.")
  (let [node-tenants (tenants-per-node)]
    (doseq [{:keys [port tenants]} node-tenants]
      (metric (str "Port " port ": " (count tenants) " tenants")))
    (let [start-ms (System/currentTimeMillis)
          ;; Launch concurrent appends on all 3 nodes
          futures (mapv
                    (fn [{:keys [port tenants]}]
                      (future
                        (when (seq tenants)
                          (eval-on port
                            (format "(let [s @app/app
                                           tids %s]
                                      (doseq [tid-str tids
                                              _ (range %d)]
                                        (app/increment! s (java.util.UUID/fromString tid-str)))
                                      :done)"
                                    (pr-str tenants) events-per-tenant)))))
                    node-tenants)]
      ;; Wait for all appends to finish
      (doseq [f futures] @f)
      (let [append-ms (- (System/currentTimeMillis) start-ms)]
        (metric (str "Append time: " append-ms "ms ("
                     (format "%.0f" (double (/ (* total-events 1000) append-ms)))
                     " events/sec)"))
        ;; Wait for processing
        (info "Waiting for processing to complete...")
        (let [poll-start (System/currentTimeMillis)
              timeout-ms 300000]
          (loop [i 0]
            (let [all-done (every?
                            (fn [tid]
                              (let [inc-c (increment-count port-a tid)
                                    proc-c (processed-count port-a tid)]
                                (= inc-c proc-c)))
                            tenant-ids)]
              (if all-done
                (let [total-ms (- (System/currentTimeMillis) start-ms)]
                  (metric (str "Total time (append + process): " total-ms "ms"))
                  (metric (str "End-to-end throughput: "
                               (format "%.0f" (double (/ (* total-events 1000) total-ms)))
                               " events/sec")))
                (if (> (- (System/currentTimeMillis) poll-start) timeout-ms)
                  (fail "Processing did not complete within timeout")
                  (do (Thread/sleep 2000) (recur (inc i))))))))
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
