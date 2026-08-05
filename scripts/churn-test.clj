#!/usr/bin/env clojure -M:dev

;; Churn test: 2 nodes, 20 tenants, continuous node cycling + event production for 2 minutes.
;; Proves: zero duplicates, zero gaps under sustained instability.
;; Usage: clojure -M:dev scripts/churn-test.clj

(load-file "scripts/test-helpers.clj")

(def cf "docker-compose.cp-test.yml")
(def port-a 7890)
(def port-b 7891)
(def n-tenants 20)
(def test-duration-ms 120000) ;; 2 minutes
(def event-interval-ms 100)   ;; 10 events/sec
(def kill-interval-ms 10000)  ;; kill every 10s
(def restart-delay-ms 5000)   ;; restart 5s after kill

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
;; Event producer                   ;;
;; -------------------------------- ;;

(defn start-producer!
  "Start a background thread that appends events to random tenants.
   Returns an atom — set to false to stop."
  [tenant-ids]
  (let [running (atom true)
        produced (atom 0)
        t (Thread.
           (fn []
             (while @running
               (try
                 (let [tid (nth tenant-ids (rand-int (count tenant-ids)))]
                   (increment! port-a tid)
                   (swap! produced inc))
                 (catch Exception e
                   ;; node-a might be briefly unreachable during Postgres hiccups
                   nil))
               (Thread/sleep event-interval-ms))))]
    (.setDaemon t true)
    (.start t)
    {:running running :produced produced :thread t}))

(defn stop-producer! [{:keys [running thread]}]
  (reset! running false)
  (.join thread 5000))

(defn percentile [values p]
  (let [values (vec (sort values))
        index (max 0 (dec (long (Math/ceil (* p (count values))))))]
    (nth values (min index (dec (count values))))))

(defn run-contention-load!
  "Run writers inside the target JVM and return aggregate diagnostics only."
  [tenant-ids writers appends-per-writer]
  (eval-read port-a
    (format
      "(let [tenant-ids (mapv java.util.UUID/fromString %s)
             writers %d
             appends %d
             ready (java.util.concurrent.CountDownLatch. writers)
             start (java.util.concurrent.CountDownLatch. 1)
             outcomes (atom [])
             tasks (mapv
                    (fn [writer]
                      (future
                        (.countDown ready)
                        (.await start)
                        (dotimes [n appends]
                          (let [tenant-id (nth tenant-ids
                                               (mod (+ writer n) (count tenant-ids)))
                                before (System/nanoTime)]
                            (try
                              (let [returned (app/increment! @app/app tenant-id)]
                                (swap! outcomes conj
                                       {:ok (= 1 (count returned))
                                        :latency-ns (- (System/nanoTime) before)}))
                              (catch Throwable t
                                (swap! outcomes conj
                                       {:ok false :error (.getMessage t)
                                        :latency-ns (- (System/nanoTime) before)})))))))
                    (range writers))]
         (.await ready 10 java.util.concurrent.TimeUnit/SECONDS)
         (let [began (System/nanoTime)]
           (.countDown start)
           (doseq [task tasks] @task)
           {:elapsed-ns (- (System/nanoTime) began)
            :successes (count (filter :ok @outcomes))
            :errors (count (remove :ok @outcomes))
            :latencies-ns (mapv :latency-ns @outcomes)}))"
      (pr-str (vec tenant-ids)) writers appends-per-writer)
    120000))

(defn report-load! [label result]
  (let [latencies (:latencies-ns result)
        elapsed-seconds (/ (:elapsed-ns result) 1.0e9)
        ms #(/ % 1.0e6)]
    (metric (format "%s: %.1f writes/s, p50=%.2fms p95=%.2fms p99=%.2fms"
                    label
                    (/ (:successes result) elapsed-seconds)
                    (ms (percentile latencies 0.50))
                    (ms (percentile latencies 0.95))
                    (ms (percentile latencies 0.99))))))

;; -------------------------------- ;;
;; Node cycler                      ;;
;; -------------------------------- ;;

(defn start-cycler!
  "Start a background thread that kills and restarts node-b on a schedule.
   Returns an atom — set to false to stop."
  []
  (let [running (atom true)
        cycles (atom 0)
        t (Thread.
           (fn []
             (while @running
               (try
                 ;; Kill
                 (docker "kill" "grain-node-b-1")
                 (swap! cycles inc)
                 ;; Wait before restart
                 (Thread/sleep restart-delay-ms)
                 (when @running
                   ;; Restart
                   (compose cf "restart" "node-b")
                   (wait-for-port port-b 30000)
                   (Thread/sleep 3000)
                   (try (setup-node! port-b) (catch Exception _))
                   ;; Wait before next kill
                   (Thread/sleep (- kill-interval-ms restart-delay-ms)))
                 (catch Exception _ nil)))))]
    (.setDaemon t true)
    (.start t)
    {:running running :cycles cycles :thread t}))

(defn stop-cycler! [{:keys [running thread]}]
  (reset! running false)
  (.join thread 20000))

;; -------------------------------- ;;
;; Scenarios                        ;;
;; -------------------------------- ;;

(defn scenario-setup []
  (header "Churn: Setup")
  (info (str "Creating " n-tenants " tenants..."))
  (let [tids (create-tenants! port-a n-tenants)]
    (info "Waiting for assignment (6s)...")
    (Thread/sleep 6000)
    (let [sa (node-status port-a)]
      (check (str n-tenants " leases assigned (got " (:leases sa) ")")
             (= n-tenants (:leases sa))))
    tids))

(defn scenario-contention [tenant-ids]
  (header "Churn: Sustained append contention")
  (let [hot (run-contention-load! [(first tenant-ids)] 16 100)
        distributed (run-contention-load! tenant-ids 16 100)]
    (report-load! "Hot tenant (16x100)" hot)
    (report-load! "Distributed across 20 tenants (1,600)" distributed)
    (check "Hot tenant: all 1,600 appends succeeded"
           (and (= 1600 (:successes hot)) (zero? (:errors hot))))
    (check "Distributed: all 1,600 appends succeeded"
           (and (= 1600 (:successes distributed)) (zero? (:errors distributed))))))

(defn scenario-churn [tenant-ids]
  (header (str "Churn: " (/ test-duration-ms 1000) "s of events + node cycling"))
  (info (str "Event rate: " (/ 1000 event-interval-ms) " events/sec"))
  (info (str "Kill cycle: every " (/ kill-interval-ms 1000) "s, restart after " (/ restart-delay-ms 1000) "s"))

  (let [producer (start-producer! tenant-ids)
        cycler (start-cycler!)]
    ;; Run for the test duration
    (info (str "Running for " (/ test-duration-ms 1000) " seconds..."))
    (Thread/sleep test-duration-ms)

    ;; Stop cycling first, then stop producing
    (info "Stopping node cycler...")
    (stop-cycler! cycler)
    (info "Stopping event producer...")
    (stop-producer! producer)

    (metric (str "Events produced: " @(:produced producer)))
    (metric (str "Kill cycles: " @(:cycles cycler)))

    ;; Make sure node-b is running for final verification
    (info "Ensuring node-b is up for verification...")
    (compose cf "restart" "node-b")
    (wait-for-port port-b 60000)
    (Thread/sleep 8000)
    (try (setup-node! port-b) (catch Exception _))

    ;; Drain: wait for all processing to catch up
    (info "Draining: waiting 30s for processing to complete...")
    (Thread/sleep 30000)

    tenant-ids))

(defn scenario-verify [tenant-ids]
  (header "Churn: Verification")
  (let [all-ok (atom true)
        total-inc (atom 0)
        total-proc (atom 0)]
    (doseq [tid tenant-ids]
      (let [inc-c (increment-count port-a tid)
            proc-c (processed-count port-a tid)]
        (swap! total-inc + inc-c)
        (swap! total-proc + proc-c)
        (when (not= inc-c proc-c)
          (reset! all-ok false)
          (fail (str "Tenant " tid ": increments=" inc-c " processed=" proc-c)))))
    (metric (str "Total increments: " @total-inc))
    (metric (str "Total processed:  " @total-proc))
    (check "All tenants: increments == processed (zero duplicates, zero gaps)"
           @all-ok)
    (check (str "Grand total match: " @total-inc " == " @total-proc)
           (= @total-inc @total-proc))))

;; -------------------------------- ;;
;; Main                             ;;
;; -------------------------------- ;;

(println "\n╔══════════════════════════════════════════╗")
(println "║  Grain Control Plane Churn Test          ║")
(println "╚══════════════════════════════════════════╝")

(when-not (start-cluster!)
  (stop-cluster!)
  (System/exit 1))

(try
  (let [tids (scenario-setup)
        _ (scenario-contention tids)
        tids (scenario-churn tids)]
    (scenario-verify tids))
  (catch Throwable t
    (swap! results update :error inc)
    (println (str "\n  !! Error: " (.getMessage t)))
    (.printStackTrace t)))

(stop-cluster!)
(report-and-exit!)
