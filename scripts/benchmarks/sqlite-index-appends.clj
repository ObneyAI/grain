;; Run from projects/grain-event-store-sqlite-v3:
;; clojure -Sdeps '{:paths ["../../components/event-store-sqlite-v3/test"]}' \
;;   -M ../../scripts/benchmarks/sqlite-index-appends.clj
;; Compares original, additive, and pruned index layouts on disposable stores.
;; Rotate trial order; first round warms the JVM. Timings are diagnostic.
(require '[ai.obney.grain.event-store-sqlite-v3.append-throughput-test :as load-test]
         '[ai.obney.grain.event-store-sqlite-v3.core :as sqlite]
         '[next.jdbc :as jdbc])

(doseq [tenant-count [1 20]
        round (range 4)
        layout (take 3 (drop (mod round 3) (cycle [:original :additive :pruned])))]
  (#'load-test/with-store
   (fn [store]
     (let [pool (get-in store [:state ::sqlite/connection-pool])]
       (when (#{:original :additive} layout)
         (jdbc/execute! pool ["CREATE INDEX idx_events_tenant_type ON events(tenant_id,type)"])
         (jdbc/execute! pool ["CREATE INDEX idx_events_tenant_id_order ON events(tenant_id,id)"]))
       (when (= :original layout)
         (jdbc/execute! pool ["DROP INDEX idx_events_tenant_type_id"])))
     (let [result (#'load-test/run-load!
                   store {:writers 8 :appends-per-writer 1000
                          :tenants (vec (repeatedly tenant-count random-uuid))})]
       (assert (:completed? result))
       (assert (= 8000 (count (:results result))))
       (assert (every? #(vector? (:returned %)) (:results result)))
       (prn {:tenants tenant-count :round round :layout layout
             :appends-per-second (/ 8000.0 (/ (:elapsed-ns result) 1e9))})
       (flush)))))
(shutdown-agents)
