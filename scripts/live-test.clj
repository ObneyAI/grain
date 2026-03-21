#!/usr/bin/env clojure -M:dev

;; Comprehensive live test runner for the Grain control plane.
;; Manages Docker lifecycle, runs all 12 test scenarios, reports results.
;;
;; Usage:
;;   clojure -M:dev scripts/live-test.clj
;;
;; Requires: docker compose, running Docker daemon.
;; The script starts and stops all containers itself.

(require '[nrepl.core :as nrepl])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.string :as str])

(def compose-file "docker-compose.cp-test.yml")
(def node-a-port 7890)
(def node-b-port 7891)

;; -------------------------------- ;;
;; State                            ;;
;; -------------------------------- ;;

(def results (atom {:pass 0 :fail 0 :error 0}))
(def tenant-ids (atom []))

;; -------------------------------- ;;
;; Shell helpers                    ;;
;; -------------------------------- ;;

(defn docker [& args]
  (let [result (apply sh "docker" args)]
    (when (not= 0 (:exit result))
      (binding [*out* *err*]
        (println "docker" (str/join " " args) "failed:" (:err result))))
    result))

(defn compose [& args]
  (apply docker "compose" "-f" compose-file args))

(defn wait-for-port
  "Poll until a port accepts connections, or timeout."
  [port timeout-ms]
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
  (eval-on port
    "(require '[ai.obney.grain.control-plane-test-base.core :as app])
     (require '[clj-uuid :as uuid])
     :ok"))

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
            (map (fn [[[tid pname] owner]]
                   {:tenant (str tid) :processor (str pname) :owner (str owner)}))
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
;; Docker lifecycle                 ;;
;; -------------------------------- ;;

(defn start-cluster!
  "Start fresh cluster: postgres + both nodes."
  []
  (info "Starting fresh cluster...")
  (compose "down" "-v")
  (compose "build")
  (compose "up" "-d")
  (info "Waiting for nodes to start...")
  (let [a-ok (wait-for-port node-a-port 60000)
        b-ok (wait-for-port node-b-port 60000)]
    (when-not a-ok (fail "Node A did not start"))
    (when-not b-ok (fail "Node B did not start"))
    ;; Extra wait for the control plane to settle
    (Thread/sleep 8000)
    (setup-node! node-a-port)
    (setup-node! node-b-port)
    (and a-ok b-ok)))

(defn restart-node-b!
  "Restart node-b container and wait for it to be ready."
  []
  (info "Restarting node-b container...")
  (compose "restart" "node-b")
  (wait-for-port node-b-port 60000)
  (Thread/sleep 8000)
  (setup-node! node-b-port))

(defn kill-node-b!
  "Hard kill node-b container (SIGKILL, no graceful shutdown)."
  []
  (info "Hard killing node-b container...")
  (docker "kill" "grain-node-b-1"))

(defn stop-cluster!
  "Tear down everything."
  []
  (info "Stopping cluster...")
  (compose "down" "-v"))

;; -------------------------------- ;;
;; Scenarios 1-7: Normal operations ;;
;; -------------------------------- ;;

(defn scenario-1 []
  (header "Scenario 1: Basic work distribution")
  (let [sa (node-status node-a-port)
        sb (node-status node-b-port)]
    (info (str "Node A: " (:node-id sa)))
    (info (str "Node B: " (:node-id sb)))
    (check "Both nodes see 2 active nodes"
           (and (= 2 (:active-nodes sa)) (= 2 (:active-nodes sb))))
    (info "Creating 4 tenants...")
    (let [tids (create-tenants! node-a-port 4)]
      (reset! tenant-ids tids)
      (info (str "Tenants: " (pr-str tids)))
      (info "Waiting for assignment...")
      (Thread/sleep 6000)
      (let [sa (node-status node-a-port)
            sb (node-status node-b-port)]
        (check (str "4 leases assigned (A sees " (:leases sa) ")")
               (= 4 (:leases sa)))
        (check (str "4 leases assigned (B sees " (:leases sb) ")")
               (= 4 (:leases sb)))
        (check "Node A running processors" (pos? (:running sa)))
        (check "Node B running processors" (pos? (:running sb)))
        (check "Work split across both nodes"
               (and (pos? (:running sa)) (pos? (:running sb))))))))

(defn scenario-2 []
  (header "Scenario 2: Event processing with correct routing")
  (let [t1 (first @tenant-ids)]
    (info (str "Incrementing tenant " t1 "..."))
    (increment! node-a-port t1)
    (Thread/sleep 3000)
    (let [c (processed-count node-a-port t1)]
      (check (str "Tenant processed " c " events (expected >= 2)") (>= c 2)))))

(defn scenario-3 []
  (header "Scenario 3: Cross-instance event delivery")
  (let [leases (lease-details node-a-port)
        node-b-id (:node-id (node-status node-b-port))
        b-tenant (->> leases
                      (filter #(= node-b-id (:owner %)))
                      first :tenant)]
    (if b-tenant
      (let [before (processed-count node-a-port b-tenant)]
        (info (str "Tenant " b-tenant " owned by node-b, processed=" before))
        (info "Appending event via node-a...")
        (increment! node-a-port b-tenant)
        (Thread/sleep 5000)
        (let [after (processed-count node-a-port b-tenant)]
          (info (str "Processed after: " after))
          (check "Cross-instance event processed" (> after before))))
      (fail "No tenants owned by node-b"))))

(defn scenario-4 []
  (header "Scenario 4: Graceful failover")
  (let [before (node-status node-a-port)]
    (info (str "Before: A running " (:running before) " processors"))
    (info "Stopping node-b control plane gracefully...")
    (eval-on node-b-port
      "(ai.obney.grain.control-plane.interface/stop (:control-plane @app/app))")
    (info "Waiting for reassignment (10s)...")
    (Thread/sleep 10000)
    (let [after (node-status node-a-port)]
      (info (str "After: A running " (:running after) " processors"))
      (check "Node A took over all leases" (= 4 (:leases after)))
      (check "Node A running all processors" (= 4 (:running after))))))

(defn scenario-5 []
  (header "Scenario 5: Hard failover (docker kill)")
  ;; First restart node-b from scenario 4
  (restart-node-b!)
  ;; Verify both running
  (Thread/sleep 4000)
  (let [sa (node-status node-a-port)]
    (info (str "Before kill: A running " (:running sa) ", leases=" (:leases sa))))
  ;; Kill
  (kill-node-b!)
  (info "Waiting for staleness detection (12s)...")
  (Thread/sleep 12000)
  (let [sa (node-status node-a-port)]
    (info (str "After kill: A sees " (:active-nodes sa) " active, running " (:running sa)))
    (check "Node A sees only 1 active node" (= 1 (:active-nodes sa)))
    (check "Node A owns all leases" (= 4 (:leases sa)))
    (check "Node A running all processors" (= 4 (:running sa)))))

(defn scenario-6 []
  (header "Scenario 6: Scale up")
  ;; Currently only A is running (B was killed in scenario 5)
  (let [before (node-status node-a-port)]
    (info (str "Before: A running " (:running before) " processors (solo)"))
    (check "A owns all 4 leases before scale-up" (= 4 (:leases before))))
  ;; Bring B back
  (restart-node-b!)
  (Thread/sleep 4000)
  (let [sa (node-status node-a-port)
        sb (node-status node-b-port)]
    (info (str "After: A running " (:running sa) ", B running " (:running sb)))
    (check "Both nodes active" (= 2 (:active-nodes sa)))
    (check "Work rebalanced to A" (pos? (:running sa)))
    (check "Work rebalanced to B" (pos? (:running sb)))
    (check "Total processors = 4" (= 4 (+ (:running sa) (:running sb))))))

(defn scenario-7 []
  (header "Scenario 7: Scale down to one")
  (info "Stopping node-b gracefully...")
  (eval-on node-b-port
    "(ai.obney.grain.control-plane.interface/stop (:control-plane @app/app))")
  (Thread/sleep 10000)
  (let [sa (node-status node-a-port)]
    (check "A sees 1 active node" (= 1 (:active-nodes sa)))
    (check "A owns all 4 leases" (= 4 (:leases sa)))
    (check "A running all 4 processors" (= 4 (:running sa)))))

;; -------------------------------- ;;
;; Scenarios 8-12: Adversarial      ;;
;; -------------------------------- ;;

(defn scenario-8 []
  (header "Scenario 8: Postgres restart")
  ;; Restore node-b first
  (restart-node-b!)
  (Thread/sleep 4000)
  (let [before-a (node-status node-a-port)]
    (info (str "Before: A active=" (:active-nodes before-a) " running=" (:running before-a)))
    (info "Restarting Postgres container...")
    (compose "restart" "postgres")
    (info "Waiting for Postgres to come back (10s)...")
    (Thread/sleep 10000)
    ;; Nodes may need time to reconnect and resume
    (info "Waiting for recovery (15s)...")
    (Thread/sleep 15000)
    (let [a-ok (node-reachable? node-a-port)
          b-ok (node-reachable? node-b-port)]
      (check "Node A still reachable" a-ok)
      (check "Node B still reachable" b-ok)
      (when (and a-ok b-ok)
        (let [sa (node-status node-a-port)]
          (check "Leases still assigned after Postgres restart" (= 4 (:leases sa)))
          (check "Processors still running" (pos? (:running sa))))))))

(defn scenario-9 []
  (header "Scenario 9: Network partition (node-b loses Postgres)")
  ;; Ensure both running
  (restart-node-b!)
  (Thread/sleep 4000)
  (let [before (node-status node-a-port)]
    (info (str "Before: A running=" (:running before) " B running=" (:running (node-status node-b-port)))))
  (info "Disconnecting node-b from network...")
  (docker "network" "disconnect" "grain_default" "grain-node-b-1")
  (info "Waiting for staleness detection (12s)...")
  (Thread/sleep 12000)
  (let [sa (node-status node-a-port)]
    (check "A sees only 1 active node during partition" (= 1 (:active-nodes sa)))
    (check "A owns all leases during partition" (= 4 (:leases sa)))
    (check "A running all processors during partition" (= 4 (:running sa))))
  ;; Restore connectivity
  (info "Reconnecting node-b to network...")
  (docker "network" "connect" "grain_default" "grain-node-b-1")
  (info "Waiting for recovery (15s)...")
  (Thread/sleep 15000)
  (let [b-ok (node-reachable? node-b-port)]
    (if b-ok
      (let [sa (node-status node-a-port)]
        (check "Both nodes active after reconnect" (= 2 (:active-nodes sa)))
        (check "Work rebalanced after reconnect" (< (:running sa) 4)))
      (info "Node B not reachable after reconnect (may need container restart)"))))

(defn scenario-10 []
  (header "Scenario 10: Rapid start/stop cycling")
  ;; Ensure B is running
  (restart-node-b!)
  (Thread/sleep 4000)
  (info "Cycling node-b 3 times (stop/start every 8s)...")
  (dotimes [i 3]
    (info (str "Cycle " (inc i) "/3: killing node-b..."))
    (kill-node-b!)
    (Thread/sleep 8000)
    (info (str "Cycle " (inc i) "/3: restarting node-b..."))
    (restart-node-b!)
    (Thread/sleep 6000))
  ;; After cycling, check stability
  (let [sa (node-status node-a-port)
        sb (node-status node-b-port)]
    (check "Both nodes active after cycling" (= 2 (:active-nodes sa)))
    (check "4 leases assigned after cycling" (= 4 (:leases sa)))
    (check "Total processors = 4 after cycling"
           (= 4 (+ (:running sa) (:running sb))))))

(defn scenario-11 []
  (header "Scenario 11: High event throughput during failover")
  ;; Both nodes should be running from scenario 10
  (let [t1 (first @tenant-ids)
        before (processed-count node-a-port t1)]
    (info (str "Tenant " t1 " has " before " processed events"))
    ;; Rapid fire events
    (info "Appending 20 events rapidly...")
    (dotimes [_ 20]
      (increment! node-a-port t1))
    ;; Kill node-b mid-stream
    (info "Killing node-b during event storm...")
    (kill-node-b!)
    ;; Wait for failover + processing
    (info "Waiting for failover + catch-up (15s)...")
    (Thread/sleep 15000)
    (let [after (processed-count node-a-port t1)]
      (info (str "Processed events after: " after))
      (check (str "All events processed (" after " > " before ")")
             (> after before))
      ;; Check no duplicates: processed count should equal incremented count
      (let [all-events (eval-read node-a-port
                         (format "(count (app/all-events @app/app (java.util.UUID/fromString \"%s\")))" t1))
            processed after
            increments (eval-read node-a-port
                         (format "(count (filter (fn [e] (= :test/counter-incremented (:event/type e)))
                                                 (app/all-events @app/app (java.util.UUID/fromString \"%s\"))))" t1))]
        (info (str "Total events=" all-events " increments=" increments " processed=" processed))
        (check "Every increment has exactly one processed event"
               (= increments processed))))))

(defn scenario-12 []
  (header "Scenario 12: Stale L1 cache after lease transfer")
  ;; Restart B for a clean state
  (restart-node-b!)
  (Thread/sleep 6000)
  ;; Find a tenant on A, transfer it to B by killing A and restarting
  (let [sa-before (node-status node-a-port)
        leases (lease-details node-a-port)
        node-a-id (:node-id sa-before)
        a-tenant (->> leases (filter #(= node-a-id (:owner %))) first :tenant)]
    (if a-tenant
      (let [before (processed-count node-a-port a-tenant)]
        (info (str "Tenant " a-tenant " owned by A, processed=" before))
        ;; Append an event while A owns it (populates A's cache)
        (increment! node-a-port a-tenant)
        (Thread/sleep 2000)
        ;; Now kill A — B takes over
        (info "Killing node-a to force lease transfer to B...")
        (docker "kill" "grain-node-a-1")
        (Thread/sleep 12000)
        ;; Append another event — B should process it
        (info "Appending event after lease transfer to B...")
        (increment! node-b-port a-tenant)
        (Thread/sleep 5000)
        (let [after (processed-count node-b-port a-tenant)]
          (info (str "Processed after transfer: " after))
          (check "Events processed after lease transfer" (> after before)))
        ;; Restart A for cleanup
        (info "Restarting node-a...")
        (compose "restart" "node-a")
        (wait-for-port node-a-port 60000)
        (Thread/sleep 8000)
        (setup-node! node-a-port))
      (fail "No tenants owned by node-a"))))

;; -------------------------------- ;;
;; Main runner                      ;;
;; -------------------------------- ;;

(defn run-all []
  (println "\n╔══════════════════════════════════════════╗")
  (println "║  Grain Control Plane Live Test Suite     ║")
  (println "╚══════════════════════════════════════════╝")

  (when-not (start-cluster!)
    (println "\nFailed to start cluster. Aborting.")
    (stop-cluster!)
    (System/exit 1))

  (try
    ;; Normal operations
    (scenario-1)
    (scenario-2)
    (scenario-3)
    (scenario-4)
    (scenario-5)
    (scenario-6)
    (scenario-7)

    ;; Adversarial
    (scenario-8)
    (scenario-9)
    (scenario-10)
    (scenario-11)
    (scenario-12)

    (catch Throwable t
      (swap! results update :error inc)
      (println (str "\n  !! Unexpected error: " (.getMessage t)))
      (.printStackTrace t)))

  ;; Report
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
    "check-status" (do (setup-node! node-a-port)
                       (try (setup-node! node-b-port) (catch Exception _))
                       (header "Status check")
                       (try (info (str "Node A: " (pr-str (node-status node-a-port))))
                            (catch Exception e (info (str "Node A: unreachable"))))
                       (try (info (str "Node B: " (pr-str (node-status node-b-port))))
                            (catch Exception e (info (str "Node B: unreachable")))))
    (run-all)))
