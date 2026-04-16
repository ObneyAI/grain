#!/usr/bin/env clojure -M:dev

;; Scale test: 5 nodes, 100 tenants, multi-node failure/recovery.
;; Usage: clojure -M:dev scripts/scale-test.clj

(load-file "scripts/test-helpers.clj")

(def cf "docker-compose.scale-test.yml")
(def ports [7890 7891 7892 7893 7894])
(def container-names ["grain-node-1-1" "grain-node-2-1" "grain-node-3-1"
                      "grain-node-4-1" "grain-node-5-1"])

;; -------------------------------- ;;
;; Cluster lifecycle                ;;
;; -------------------------------- ;;

(defn start-cluster! []
  (info "Starting 5-node cluster...")
  (compose cf "down" "-v")
  (compose cf "build")
  ;; Start postgres first, then node-1 to create schema, then the rest
  (compose cf "up" "-d" "postgres")
  (Thread/sleep 5000)
  (compose cf "up" "-d" "node-1")
  (info "Waiting for node-1 to initialize schema...")
  (wait-for-port (first ports) 60000)
  (Thread/sleep 5000)
  ;; Now start the rest
  (compose cf "up" "-d")
  (info "Waiting for all nodes (up to 90s)...")
  (let [ok (every? true? (map #(wait-for-port % 90000) ports))]
    (when-not ok (fail "Not all nodes started"))
    (Thread/sleep 15000)
    (doseq [p ports] (setup-node! p))
    ok))

(defn stop-cluster! []
  (info "Stopping cluster...")
  (compose cf "down" "-v"))

;; -------------------------------- ;;
;; Helpers                          ;;
;; -------------------------------- ;;

(defn all-nodes-lease-count [port]
  (:leases (node-status port)))

(defn running-per-node []
  (mapv (fn [p]
          (try (:running (node-status p))
               (catch Exception _ 0)))
        ports))

(defn leases-per-owner [port]
  (let [leases (lease-details port)]
    (frequencies (map :owner leases))))

;; -------------------------------- ;;
;; Scenarios                        ;;
;; -------------------------------- ;;

(defn scenario-1 []
  (header "Scale 1: Even distribution across 5 nodes, 100 tenants")
  (let [sa (node-status (first ports))]
    (check "All 5 nodes active" (= 5 (:active-nodes sa))))
  (info "Creating 100 tenants...")
  (create-tenants! (first ports) 100)
  (info "Waiting for assignment (10s)...")
  (Thread/sleep 10000)
  (let [total (all-nodes-lease-count (first ports))
        per-owner (leases-per-owner (first ports))
        counts (vals per-owner)
        running (running-per-node)]
    (check (str "100 leases assigned (got " total ")") (= 100 total))
    (check (str "5 distinct owners (got " (count per-owner) ")") (= 5 (count per-owner)))
    (when (seq counts)
      (let [min-c (apply min counts)
            max-c (apply max counts)]
        (check (str "Even split: min=" min-c " max=" max-c " (diff <= 1)")
               (<= (- max-c min-c) 1))))
    (metric (str "Processors per node: " (pr-str running)))
    (check "All nodes running processors" (every? pos? running))))

(defn scenario-2 []
  (header "Scale 2: Kill 2 nodes, verify survivors absorb work")
  (info "Killing node-4 and node-5...")
  (docker "kill" (nth container-names 3))
  (docker "kill" (nth container-names 4))
  (info "Waiting for staleness + reassignment (15s)...")
  (Thread/sleep 15000)
  (let [sa (node-status (first ports))
        total (all-nodes-lease-count (first ports))
        per-owner (leases-per-owner (first ports))
        running (running-per-node)]
    (check (str "3 active nodes (got " (:active-nodes sa) ")") (= 3 (:active-nodes sa)))
    (check (str "100 leases still assigned (got " total ")") (= 100 total))
    (check (str "3 owners (got " (count per-owner) ")") (= 3 (count per-owner)))
    (when (seq (vals per-owner))
      (let [counts (vals per-owner)
            min-c (apply min counts)
            max-c (apply max counts)]
        (check (str "Even split among survivors: min=" min-c " max=" max-c " (diff <= 1)")
               (<= (- max-c min-c) 1))))
    (metric (str "Processors per node: " (pr-str running)))))

(defn scenario-3 []
  (header "Scale 3: Bring 2 nodes back, verify rebalance")
  (info "Restarting node-4...")
  (compose cf "restart" "node-4")
  (wait-for-port (nth ports 3) 60000)
  (setup-node! (nth ports 3))
  (info "Restarting node-5...")
  (compose cf "restart" "node-5")
  (wait-for-port (nth ports 4) 60000)
  (setup-node! (nth ports 4))
  (Thread/sleep 6000)
  (let [sa (node-status (first ports))
        total (all-nodes-lease-count (first ports))
        per-owner (leases-per-owner (first ports))
        running (running-per-node)]
    (check (str "5 active nodes (got " (:active-nodes sa) ")") (= 5 (:active-nodes sa)))
    (check (str "100 leases still assigned (got " total ")") (= 100 total))
    (check (str "5 owners (got " (count per-owner) ")") (= 5 (count per-owner)))
    (let [counts (vals per-owner)
          min-c (apply min counts)
          max-c (apply max counts)]
      (check (str "Even split: min=" min-c " max=" max-c " (diff <= 1)")
             (<= (- max-c min-c) 1)))
    (metric (str "Processors per node: " (pr-str running)))))

(defn scenario-4 []
  (header "Scale 4: All nodes agree on lease-ownership")
  (let [leases-from-each (mapv (fn [p]
                                  (try (set (lease-details p))
                                       (catch Exception _ nil)))
                                ports)
        non-nil (remove nil? leases-from-each)
        all-same (apply = non-nil)]
    (check (str "All " (count non-nil) " reachable nodes agree on leases") all-same)
    (when-not all-same
      (info "Disagreement detected — dumping per-node lease counts:")
      (doseq [[p leases] (map vector ports leases-from-each)]
        (info (str "  Port " p ": " (if leases (count leases) "unreachable")))))))

;; -------------------------------- ;;
;; Main                             ;;
;; -------------------------------- ;;

(println "\n╔══════════════════════════════════════════╗")
(println "║  Grain Control Plane Scale Test          ║")
(println "╚══════════════════════════════════════════╝")

(when-not (start-cluster!)
  (stop-cluster!)
  (System/exit 1))

(try
  (scenario-1)
  (scenario-2)
  (scenario-3)
  (scenario-4)
  (catch Throwable t
    (swap! results update :error inc)
    (println (str "\n  !! Error: " (.getMessage t)))
    (.printStackTrace t)))

(stop-cluster!)
(report-and-exit!)
