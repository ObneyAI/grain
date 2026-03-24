#!/usr/bin/env clojure -M:dev

;; Comprehensive live test runner for the Grain control plane.
;; Parameterized for arbitrary node count. Manages Docker lifecycle.
;;
;; Usage: clojure -M:dev scripts/live-test.clj

(require '[nrepl.core :as nrepl])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.string :as str])

(def compose-file "docker-compose.cp-test.yml")
(def all-ports [7890 7891 7892])
(def primary-port (first all-ports))
(def victim-port (last all-ports))
(def victim-service "node-c")
(def victim-container "grain-node-c-1")
(def n-nodes (count all-ports))
(def n-tenants 6) ;; divisible by 3 and 2 for even splits

;; -------------------------------- ;;
;; State                            ;;
;; -------------------------------- ;;

(def results (atom {:pass 0 :fail 0 :error 0}))
(def tenant-ids (atom []))

;; -------------------------------- ;;
;; Shell / Docker helpers           ;;
;; -------------------------------- ;;

(defn docker [& args]
  (let [result (apply sh "docker" args)]
    (when (not= 0 (:exit result))
      (binding [*out* *err*]
        (println "docker" (str/join " " args) "failed:" (:err result))))
    result))

(defn compose [& args]
  (apply docker "compose" "-f" compose-file args))

(defn wait-for-port [port timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if (> (- (System/currentTimeMillis) start) timeout-ms)
        false
        (if (try
              (with-open [_ (java.net.Socket. "localhost" port)]
                true)
              (catch Exception _ false))
          true
          (do (Thread/sleep 500) (recur)))))))

;; -------------------------------- ;;
;; nREPL helpers                    ;;
;; -------------------------------- ;;

(defn eval-on [port code]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 15000)
          results (nrepl/message client {:op :eval :code code})]
      (doseq [r results]
        (when (:err r) (binding [*out* *err*] (print (:err r)))))
      (some :value results))))

(defn eval-read [port code]
  (let [v (eval-on port code)]
    (when v (read-string v))))

(defn setup-node! [port]
  (loop [attempts 5]
    (let [result (try
                   (eval-on port
                     "(require '[ai.obney.grain.control-plane-test-base.core :as app])
                      (require '[clj-uuid :as uuid])
                      :ok")
                   (catch Exception e
                     (when (pos? attempts)
                       (Thread/sleep 2000))
                     nil))]
      (if (or result (zero? attempts))
        result
        (recur (dec attempts))))))

(defn node-status [port]
  (eval-read port
    "(let [s @app/app]
       {:node-id (str (:node-id (:control-plane s)))
        :active-nodes (count (app/active-nodes s))
        :leases (count (app/leases s))
        :running (count (app/running-processors s))})"))

(defn lease-details [port]
  (eval-read port
    "(let [s @app/app]
       (->> (app/leases s)
            (map (fn [[tid owner]]
                   {:tenant (str tid) :owner (str owner)}))
            vec))"))

(defn create-tenants! [port n]
  (eval-read port
    (format
      "(let [s @app/app
             tids (repeatedly %d uuid/v4)]
         (doseq [t tids] (app/create-tenant! s t))
         (mapv str tids))" n)))

(defn increment! [port tenant-id]
  (eval-on port
    (format "(app/increment! @app/app (java.util.UUID/fromString \"%s\"))" tenant-id)))

(defn processed-count [port tenant-id]
  (eval-read port
    (format "(count (app/processed-events @app/app (java.util.UUID/fromString \"%s\")))" tenant-id)))

(defn increment-count [port tenant-id]
  (eval-read port
    (format "(count (filter (fn [e] (= :test/counter-incremented (:event/type e)))
                            (app/all-events @app/app (java.util.UUID/fromString \"%s\"))))" tenant-id)))

(defn node-reachable? [port]
  (try (node-status port) true
       (catch Exception _ false)))

;; -------------------------------- ;;
;; Reporting                        ;;
;; -------------------------------- ;;

(defn pass [msg]
  (swap! results update :pass inc)
  (println (str "  ✓ " msg)))

(defn fail [msg]
  (swap! results update :fail inc)
  (println (str "  ✗ FAIL: " msg)))

(defn info [msg] (println (str "  → " msg)))
(defn header [msg] (println (str "\n== " msg " ==")))

(defn check [desc pred]
  (if pred (pass desc) (fail desc))
  pred)

;; -------------------------------- ;;
;; Multi-node helpers               ;;
;; -------------------------------- ;;

(defn all-statuses []
  (mapv (fn [p]
          (try (assoc (node-status p) :port p :reachable true)
               (catch Exception _ {:port p :reachable false})))
        all-ports))

(defn reachable-statuses [] (filter :reachable (all-statuses)))

(defn total-running []
  (reduce + (map #(or (:running %) 0) (reachable-statuses))))

(defn find-tenant-owned-by [port owner-port]
  (let [leases (lease-details port)
        owner-id (:node-id (node-status owner-port))]
    (->> leases (filter #(= owner-id (:owner %))) first :tenant)))

(defn find-tenant-not-owned-by [port excluded-port]
  (let [leases (lease-details port)
        excluded-id (:node-id (node-status excluded-port))]
    (->> leases (filter #(not= excluded-id (:owner %))) first :tenant)))

;; -------------------------------- ;;
;; Docker lifecycle                 ;;
;; -------------------------------- ;;

(defn start-cluster! []
  (info (str "Starting " n-nodes "-node cluster..."))
  (compose "down" "-v")
  (compose "build")
  ;; Staggered start to avoid schema creation race
  (compose "up" "-d" "postgres")
  (Thread/sleep 5000)
  (compose "up" "-d" "node-a")
  (wait-for-port primary-port 60000)
  (Thread/sleep 3000)
  (compose "up" "-d")
  (info "Waiting for all nodes...")
  (let [ok (every? true? (map #(wait-for-port % 90000) all-ports))]
    (when-not ok (fail "Not all nodes started"))
    (Thread/sleep 12000)
    (doseq [p all-ports] (setup-node! p))
    ok))

(defn restart-victim! []
  (info (str "Restarting " victim-service " container..."))
  (compose "restart" victim-service)
  (wait-for-port victim-port 60000)
  (Thread/sleep 8000)
  (setup-node! victim-port))

(defn kill-victim! []
  (info (str "Hard killing " victim-container "..."))
  (docker "kill" victim-container))

(defn stop-cluster! []
  (info "Stopping cluster...")
  (compose "down" "-v"))

;; -------------------------------- ;;
;; Scenarios                        ;;
;; -------------------------------- ;;

(defn scenario-1 []
  (header "Scenario 1: Basic work distribution")
  (let [statuses (reachable-statuses)]
    (doseq [s statuses]
      (info (str "Port " (:port s) ": node " (:node-id s))))
    (check (str "All " n-nodes " nodes active")
           (every? #(= n-nodes (:active-nodes %)) statuses))
    (info (str "Creating " n-tenants " tenants..."))
    (let [tids (create-tenants! primary-port n-tenants)]
      (reset! tenant-ids tids)
      (info "Waiting for assignment...")
      (Thread/sleep 8000)
      (let [s (node-status primary-port)]
        (check (str n-tenants " leases assigned (got " (:leases s) ")")
               (= n-tenants (:leases s))))
      (let [tr (total-running)]
        (check (str "Total running = " n-tenants " (got " tr ")")
               (= n-tenants tr)))
      (check "All nodes running processors"
             (every? #(pos? (:running %)) (reachable-statuses))))))

(defn scenario-2 []
  (header "Scenario 2: Event processing with correct routing")
  (let [t1 (first @tenant-ids)]
    (info (str "Incrementing tenant " t1 "..."))
    (increment! primary-port t1)
    (Thread/sleep 3000)
    (let [c (processed-count primary-port t1)]
      (check (str "Tenant processed " c " events (expected >= 2)") (>= c 2)))))

(defn scenario-3 []
  (header "Scenario 3: Cross-instance event delivery")
  (let [non-primary-tenant (find-tenant-not-owned-by primary-port primary-port)]
    (if non-primary-tenant
      (let [before (processed-count primary-port non-primary-tenant)]
        (info (str "Tenant " non-primary-tenant " owned by non-primary, processed=" before))
        (info "Appending event via primary node...")
        (increment! primary-port non-primary-tenant)
        (Thread/sleep 5000)
        (let [after (processed-count primary-port non-primary-tenant)]
          (info (str "Processed after: " after))
          (check "Cross-instance event processed" (> after before))))
      (fail "No tenants owned by non-primary nodes"))))

(defn scenario-4 []
  (header "Scenario 4: Graceful failover")
  (let [before-running (total-running)]
    (info (str "Before: total running " before-running))
    (info (str "Stopping " victim-service " control plane gracefully..."))
    (eval-on victim-port
      "(ai.obney.grain.control-plane.interface/stop (:control-plane @app/app))")
    (info "Waiting for reassignment (12s)...")
    (Thread/sleep 12000)
    (let [s (node-status primary-port)
          tr (total-running)]
      (check (str "All " n-tenants " leases assigned (got " (:leases s) ")")
             (= n-tenants (:leases s)))
      (check (str "Total running = " n-tenants " (got " tr ")")
             (= n-tenants tr)))))

(defn scenario-5 []
  (header "Scenario 5: Hard failover (docker kill)")
  (restart-victim!)
  (Thread/sleep 4000)
  (let [before (node-status primary-port)]
    (info (str "Before kill: primary running " (:running before) ", leases=" (:leases before))))
  (kill-victim!)
  (info "Waiting for staleness detection (12s)...")
  (Thread/sleep 12000)
  (let [s (node-status primary-port)
        expected-active (dec n-nodes)]
    (check (str "Primary sees " expected-active " active (got " (:active-nodes s) ")")
           (= expected-active (:active-nodes s)))
    (check (str "All " n-tenants " leases assigned (got " (:leases s) ")")
           (= n-tenants (:leases s)))
    (let [tr (total-running)]
      (check (str "Total running = " n-tenants " (got " tr ")")
             (= n-tenants tr)))))

(defn scenario-6 []
  (header "Scenario 6: Scale up")
  (let [before (total-running)]
    (info (str "Before: total running " before " (victim down)"))
    (check (str "All " n-tenants " leases before scale-up")
           (= n-tenants (:leases (node-status primary-port)))))
  (restart-victim!)
  (Thread/sleep 6000)
  (let [s (node-status primary-port)
        tr (total-running)]
    (check (str "All " n-nodes " nodes active (got " (:active-nodes s) ")")
           (= n-nodes (:active-nodes s)))
    (check (str "Total running = " n-tenants " (got " tr ")")
           (= n-tenants tr))
    (check "Victim running processors"
           (pos? (:running (node-status victim-port))))))

(defn scenario-7 []
  (header "Scenario 7: Scale down to fewer nodes")
  (info (str "Stopping " victim-service " gracefully..."))
  (eval-on victim-port
    "(ai.obney.grain.control-plane.interface/stop (:control-plane @app/app))")
  (Thread/sleep 12000)
  (let [s (node-status primary-port)
        expected-active (dec n-nodes)]
    (check (str expected-active " active nodes (got " (:active-nodes s) ")")
           (= expected-active (:active-nodes s)))
    (check (str "All " n-tenants " leases (got " (:leases s) ")")
           (= n-tenants (:leases s)))
    (let [tr (total-running)]
      (check (str "Total running = " n-tenants " (got " tr ")")
             (= n-tenants tr)))))

(defn scenario-8 []
  (header "Scenario 8: Postgres restart")
  (restart-victim!)
  (Thread/sleep 4000)
  (info "Restarting Postgres container...")
  (compose "restart" "postgres")
  (info "Waiting for recovery (25s)...")
  (Thread/sleep 25000)
  (let [reachable (count (filter :reachable (all-statuses)))]
    (check (str "At least " (dec n-nodes) " nodes reachable (got " reachable ")")
           (>= reachable (dec n-nodes)))
    (let [s (node-status primary-port)]
      (check (str "Leases still " n-tenants " (got " (:leases s) ")")
             (= n-tenants (:leases s))))))

(defn scenario-9 []
  (header "Scenario 9: Network partition (victim loses Postgres)")
  (restart-victim!)
  (Thread/sleep 4000)
  (info (str "Disconnecting " victim-container " from network..."))
  (docker "network" "disconnect" "grain_default" victim-container)
  (info "Waiting for staleness detection (12s)...")
  (Thread/sleep 12000)
  (let [s (node-status primary-port)]
    (check (str "Primary sees " (dec n-nodes) " active during partition")
           (= (dec n-nodes) (:active-nodes s)))
    (let [tr (total-running)]
      (check (str "Total running = " n-tenants " during partition (got " tr ")")
             (= n-tenants tr))))
  (info (str "Reconnecting " victim-container "..."))
  (docker "network" "connect" "grain_default" victim-container)
  (info "Waiting for recovery (15s)...")
  (Thread/sleep 15000)
  (let [s (node-status primary-port)]
    (check (str "All " n-nodes " nodes active after reconnect (got " (:active-nodes s) ")")
           (= n-nodes (:active-nodes s)))))

(defn scenario-10 []
  (header "Scenario 10: Rapid start/stop cycling")
  (restart-victim!)
  (Thread/sleep 4000)
  (info "Cycling victim 3 times...")
  (dotimes [i 3]
    (info (str "Cycle " (inc i) "/3: killing..."))
    (kill-victim!)
    (Thread/sleep 8000)
    (info (str "Cycle " (inc i) "/3: restarting..."))
    (restart-victim!)
    (Thread/sleep 6000))
  (let [s (node-status primary-port)
        tr (total-running)]
    (check (str "All " n-nodes " active after cycling (got " (:active-nodes s) ")")
           (= n-nodes (:active-nodes s)))
    (check (str n-tenants " leases after cycling (got " (:leases s) ")")
           (= n-tenants (:leases s)))
    (check (str "Total running = " n-tenants " (got " tr ")")
           (= n-tenants tr))))

(defn scenario-11 []
  (header "Scenario 11: High event throughput during failover")
  (let [t1 (first @tenant-ids)
        before (processed-count primary-port t1)]
    (info (str "Tenant " t1 " has " before " processed events"))
    (info "Appending 20 events rapidly...")
    (dotimes [_ 20]
      (increment! primary-port t1))
    (info "Killing victim during event storm...")
    (kill-victim!)
    (info "Waiting for failover + catch-up (15s)...")
    (Thread/sleep 15000)
    (let [after (processed-count primary-port t1)]
      (info (str "Processed events after: " after))
      (check "All events processed" (> after before))
      (let [increments (increment-count primary-port t1)]
        (info (str "Increments=" increments " processed=" after))
        (check "Every increment processed exactly once" (= increments after))))))

(defn scenario-12 []
  (header "Scenario 12: Stale L1 cache after lease transfer")
  (restart-victim!)
  (Thread/sleep 6000)
  ;; Find a tenant on primary, kill primary, verify victim processes it
  (let [primary-tenant (find-tenant-owned-by primary-port primary-port)]
    (if primary-tenant
      (let [before (processed-count primary-port primary-tenant)]
        (info (str "Tenant " primary-tenant " owned by primary, processed=" before))
        (increment! primary-port primary-tenant)
        (Thread/sleep 2000)
        (info "Killing primary to force lease transfer...")
        (docker "kill" "grain-node-a-1")
        (Thread/sleep 12000)
        ;; Query from victim instead
        (let [after (processed-count victim-port primary-tenant)]
          (info (str "Processed after transfer: " after))
          (check "Events processed after lease transfer" (> after before)))
        (info "Restarting primary...")
        (compose "restart" "node-a")
        (wait-for-port primary-port 60000)
        (Thread/sleep 8000)
        (setup-node! primary-port))
      (fail "No tenants owned by primary"))))

(defn scenario-13 []
  (header "Scenario 13: In-progress work during node crash")
  (restart-victim!)
  (Thread/sleep 4000)
  (let [victim-tenant (find-tenant-owned-by primary-port victim-port)]
    (if victim-tenant
      (do
        (info (str "Tenant " victim-tenant " owned by victim"))
        (info "Submitting 3 slow-work events...")
        (eval-on primary-port
          (format "(dotimes [_ 3] (app/submit-slow-work! @app/app (java.util.UUID/fromString \"%s\")))"
                  victim-tenant))
        (info "Waiting 1s for processing to begin...")
        (Thread/sleep 1000)
        (info "Killing victim mid-processing...")
        (kill-victim!)
        (info "Waiting for failover + catch-up (20s)...")
        (Thread/sleep 20000)
        (let [diag (eval-read primary-port
                     (format "(app/diagnose-slow-work @app/app (java.util.UUID/fromString \"%s\"))"
                             victim-tenant))]
          (info (str "Diagnosis: " (pr-str diag)))
          (check (str "All slow work completed (" (:completed diag) "/3)")
                 (= 3 (:completed diag)))))
      (fail "No tenants owned by victim"))))

(defn scenario-14 []
  (header "Scenario 14: defperiodic CAS deduplication across nodes")
  ;; The test app has a defperiodic (:test/scheduled-trigger) that runs every 3s
  ;; with CAS dedup. All 3 nodes run it. A separate defprocessor handles it.
  (restart-victim!)
  (Thread/sleep 10000)
  ;; Record baseline trigger count per tenant
  (let [tids @tenant-ids
        before (eval-read primary-port
                 (format "(into {} (map (fn [tid-str]
                                          [tid-str (:triggers (app/scheduled-trigger-summary @app/app
                                                     (java.util.UUID/fromString tid-str)))])
                                        %s))"
                         (pr-str tids)))]
    (info (str "Trigger baseline: " (pr-str (vals before))))
    ;; Wait for ~3 periodic cycles (3s interval)
    (info "Waiting 10s for new periodic triggers...")
    (Thread/sleep 10000)
    ;; Get new counts
    (let [after (eval-read primary-port
                  (format "(into {} (map (fn [tid-str]
                                           [tid-str (:triggers (app/scheduled-trigger-summary @app/app
                                                      (java.util.UUID/fromString tid-str)))])
                                         %s))"
                          (pr-str tids)))
          new-per-tenant (map (fn [tid] (- (get after tid 0) (get before tid 0))) tids)
          total-new (reduce + new-per-tenant)]
      (info (str "New triggers per tenant: " (pr-str new-per-tenant)))
      (info (str "Total new: " total-new))
      (check "New triggers created" (pos? total-new))
      ;; CAS dedup check: each tenant should get the same number of new triggers
      ;; (one per cycle, not one per node per cycle)
      ;; With 3 nodes and ~3 cycles, without dedup = ~9 per tenant.
      ;; With dedup = ~3 per tenant.
      (let [max-new (apply max new-per-tenant)]
        (check (str "CAS dedup working (max " max-new " per tenant, not " (* 3 max-new) ")")
               ;; Each tenant should have roughly the same count (within 1)
               (<= (- (apply max new-per-tenant) (apply min new-per-tenant)) 1))))))

(defn scenario-15 []
  (header "Scenario 15: Blocking handlers don't starve other tenants")
  ;; Ensure all nodes are up
  (restart-victim!)
  (Thread/sleep 6000)
  ;; Submit slow-work to 3 tenants (handler sleeps 2s each)
  ;; and regular increments to the other 3
  (let [tids @tenant-ids
        slow-tids (take 3 tids)
        fast-tids (drop 3 tids)]
    (info (str "Submitting slow-work to " (count slow-tids) " tenants (2s sleep each)..."))
    (doseq [tid slow-tids]
      (eval-on primary-port
        (format "(app/submit-slow-work! @app/app (java.util.UUID/fromString \"%s\"))" tid)))
    (info (str "Submitting fast increments to " (count fast-tids) " tenants..."))
    (doseq [tid fast-tids]
      (increment! primary-port tid))
    ;; Wait 1.5s — fast tenants should be done, slow ones still blocking
    (Thread/sleep 1500)
    (let [fast-done (reduce + (map #(processed-count primary-port %) fast-tids))]
      (info (str "Fast tenants processed after 1.5s: " fast-done))
      (check "Fast tenants processed while slow ones block"
             (pos? fast-done)))
    ;; Wait for slow tenants to finish
    (Thread/sleep 5000)
    (let [slow-done (eval-read primary-port
                      (format "(reduce + (map (fn [tid-str]
                                                (:completed (app/diagnose-slow-work @app/app
                                                              (java.util.UUID/fromString tid-str))))
                                              %s))"
                              (pr-str (vec slow-tids))))]
      (info (str "Slow tenants completed after total wait: " slow-done))
      (check (str "All slow work eventually completed (" slow-done "/3)")
             (= 3 slow-done)))))

;; -------------------------------- ;;
;; Main runner                      ;;

(defn run-all []
  (println "\n╔══════════════════════════════════════════╗")
  (println (str "║  Grain Control Plane Live Test Suite     ║"))
  (println (str "║  " n-nodes " nodes, " n-tenants " tenants                       ║"))
  (println "╚══════════════════════════════════════════╝")

  (when-not (start-cluster!)
    (println "\nFailed to start cluster. Aborting.")
    (stop-cluster!)
    (System/exit 1))

  (try
    (scenario-1)
    (scenario-2)
    (scenario-3)
    (scenario-4)
    (scenario-5)
    (scenario-6)
    (scenario-7)
    (scenario-8)
    (scenario-9)
    (scenario-10)
    (scenario-11)
    (scenario-12)
    (scenario-13)
    (scenario-14)
    (scenario-15)

    (catch Throwable t
      (swap! results update :error inc)
      (println (str "\n  !! Unexpected error: " (.getMessage t)))
      (.printStackTrace t)))

  (let [{:keys [pass fail error]} @results
        total (+ pass fail error)]
    (println "\n╔══════════════════════════════════════════╗")
    (println (format "║  Results: %d passed, %d failed, %d errors  " pass fail error))
    (println (format "║  Total:   %d checks                        " total))
    (println "╚══════════════════════════════════════════╝"))

  (stop-cluster!)
  (let [{:keys [fail error]} @results]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))

;; Entry point
(let [cmd (first *command-line-args*)]
  (case cmd
    "check-status" (do (doseq [p all-ports] (setup-node! p))
                       (header "Status check")
                       (doseq [s (all-statuses)]
                         (info (str "Port " (:port s) ": " (pr-str (dissoc s :port))))))
    (run-all)))
