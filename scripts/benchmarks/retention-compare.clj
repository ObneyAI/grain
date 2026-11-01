;; Read-only comparison of exact receipt batches in two completed profile runs.
;; Args: fixture.edn before-database after-database result.edn; other connection
;; settings come from PG_*. Run after both profile processes have completed.
(require '[clojure.edn :as edn])
(def compare-args (vec *command-line-args*))
(binding [*command-line-args* ["--load-only"]]
  (load-file "scripts/benchmarks/retention-scale.clj"))
(defn receipt-fingerprint [database tenant]
  (let [store (es/start {:conn {:type :postgres
                                :server-name (or (System/getenv "PG_HOST") "127.0.0.1")
                                :port-number (or (System/getenv "PG_PORT") "5432")
                                :database-name database
                                :username (or (System/getenv "PG_USER") "postgres")
                                :password (or (System/getenv "PG_PASSWORD") "password")}})
        digest (sha)]
    (try
      (let [counts (reduce
                    (fn [counts receipt]
                      (digest! digest (select-keys receipt
                                                  [:retention/event-type :retention/cutoff
                                                   :retention/deleted-event-ids
                                                   :retention/deleted-transaction-ids]))
                      (-> counts (update :batches inc)
                          (update :deleted-events + (count (:retention/deleted-event-ids receipt)))
                          (update :deleted-transactions + (count (:retention/deleted-transaction-ids receipt)))))
                    {:batches 0 :deleted-events 0 :deleted-transactions 0}
                    (es/read store {:tenant-id tenant :types #{compaction/compaction-receipt-type}}))]
        (assoc counts :ordered-batches-sha256 (digest-result digest)))
      (finally (es/stop store)))))
(try
  (let [[fixture-path before-db after-db result-path] compare-args
        fixture (edn/read-string (slurp fixture-path))
        before (receipt-fingerprint before-db (:tenant fixture))
        after (receipt-fingerprint after-db (:tenant fixture))]
    (check! (pos? (:batches before)) "No compaction receipts to compare" {})
    (check! (= before after) "Deletion batches differ" {:before before :after after})
    (io/make-parents result-path)
    (spit result-path (with-out-str (pprint/pprint {:before before :after after :passed? true})))
    (progress! {:phase :batch-parity :passed? true :batches (:batches before)}))
  (shutdown-agents)
  (catch Throwable t (.printStackTrace t) (shutdown-agents) (System/exit 1)))
