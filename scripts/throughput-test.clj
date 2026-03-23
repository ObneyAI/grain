#!/usr/bin/env clojure -M:dev

;; Throughput test: 3 nodes, 1000 tenants, 100k events, measure processing rate.
;; Usage: clojure -M:dev scripts/throughput-test.clj

(load-file "scripts/test-helpers.clj")

(def cf "docker-compose.cp-test.yml")
(def port-a 7890)
(def port-b 7891)
(def port-c 7892)
(def all-ports [port-a port-b port-c])
(def n-tenants 10000)
(def events-per-tenant 10)
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
  (info (str "Creating " n-tenants " tenants in batches of 1000..."))
  (let [tids (loop [remaining n-tenants
                     all-tids []]
               (if (<= remaining 0)
                 all-tids
                 (let [batch (min 1000 remaining)
                       batch-tids (create-tenants! port-a batch)]
                   (info (str "  Created " (+ (count all-tids) (count batch-tids)) "/" n-tenants))
                   (recur (- remaining batch) (into all-tids batch-tids)))))]
    (let [wait-s (max 15 (/ n-tenants 200))]
      (info (str "Waiting for assignment (" wait-s "s)..."))
      (Thread/sleep (* wait-s 1000)))
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
                                    (pr-str tenants) events-per-tenant)
                            (max 60000 (* (count tenants) events-per-tenant 10))))))
                    node-tenants)]
      ;; Wait for all appends to finish
      (doseq [f futures] @f)
      (let [append-ms (- (System/currentTimeMillis) start-ms)]
        (metric (str "Append time: " append-ms "ms ("
                     (format "%.0f" (double (/ (* total-events 1000) append-ms)))
                     " events/sec)"))
        ;; Wait for processing — single server-side check per poll
        (info "Waiting for processing to complete...")
        (let [poll-start (System/currentTimeMillis)
              timeout-ms 300000
              tids-str (pr-str tenant-ids)]
          (loop [i 0]
            (let [check-result (eval-read port-a
                                 (format
                                   "(let [s @app/app
                                          tids %s
                                          results (mapv (fn [tid-str]
                                                          (let [tid (java.util.UUID/fromString tid-str)
                                                                all (app/all-events s tid)
                                                                incs (count (filter (fn [e] (= :test/counter-incremented (:event/type e))) all))
                                                                procs (count (filter (fn [e] (= :test/counter-processed (:event/type e))) all))]
                                                            {:tid tid-str :incs incs :procs procs}))
                                                        tids)
                                          total-incs (reduce + (map :incs results))
                                          total-procs (reduce + (map :procs results))
                                          all-done (every? (fn [r] (= (:incs r) (:procs r))) results)]
                                      {:all-done all-done :total-incs total-incs :total-procs total-procs})"
                                   tids-str)
                                 300000)]
              (if (:all-done check-result)
                (let [total-ms (- (System/currentTimeMillis) start-ms)]
                  (metric (str "Total time (append + process): " total-ms "ms"))
                  (metric (str "End-to-end throughput: "
                               (format "%.0f" (double (/ (* total-events 1000) total-ms)))
                               " events/sec")))
                (if (> (- (System/currentTimeMillis) poll-start) timeout-ms)
                  (fail (str "Processing did not complete within timeout. "
                             "incs=" (:total-incs check-result) " procs=" (:total-procs check-result)))
                  (do (Thread/sleep 3000) (recur (inc i))))))))
        tenant-ids))))

(defn diagnose-tenant [port tid]
  (eval-read port
    (format "(app/diagnose-tenant @app/app (java.util.UUID/fromString \"%s\"))" tid)))

(defn verify-on-node [port]
  "Run verification on a single node for its owned tenants. Returns totals."
  (eval-read port
    "(let [s @app/app
           tenants (or (app/running-processors s) #{})
           results (pmap (fn [tid]
                           (let [all (app/all-events s tid)
                                 incs (count (filter #(= :test/counter-incremented (:event/type %)) all))
                                 procs (count (filter #(= :test/counter-processed (:event/type %)) all))]
                             {:incs incs :procs procs}))
                         tenants)]
       {:total-incs (reduce + (map :incs results))
        :total-procs (reduce + (map :procs results))
        :tenant-count (count tenants)})"
    120000))

(defn scenario-verify [tenant-ids]
  (header "Throughput: Verification")
  ;; Each node verifies only its own tenants in parallel
  (info "Verifying across all nodes (each checks its own tenants)...")
  (let [futures (mapv (fn [p] (future (verify-on-node p))) all-ports)
        node-results (mapv deref futures)
        total-incs (reduce + (map #(or (:total-incs %) 0) node-results))
        total-procs (reduce + (map #(or (:total-procs %) 0) node-results))
        total-missing (- total-incs total-procs)]
    (doseq [[p r] (map vector all-ports node-results)]
      (info (str "Port " p ": " (:tenant-count r) " tenants, "
                 (:total-incs r) " incs, " (:total-procs r) " procs")))
    (metric (str "Total increments: " total-incs))
    (metric (str "Total processed:  " total-procs))
    (metric (str "Total missing:    " total-missing))
    (check "All tenants: zero missing events" (zero? total-missing))
    (check (str "Grand total: " total-incs " == " total-procs)
           (= total-incs total-procs))))
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
