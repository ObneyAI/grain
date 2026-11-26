;; Profile either build against a disposable clone of the same million-event seed.
;; Args: fixture.edn result.edn [frozen-postgres-core.clj]
;; PG_* selects the empty-or-prepared disposable database, as in retention-scale.
(require '[clojure.edn :as edn])
(def profile-args (vec *command-line-args*))
(binding [*command-line-args* ["--load-only"]]
  (load-file "scripts/benchmarks/retention-scale.clj"))
(when-let [frozen-core (nth profile-args 2 nil)] (load-file frozen-core))

(def ^:dynamic *compaction-sample* nil)
(defn stage [key f]
  (if-not *compaction-sample* (f)
    (let [start (now-ns)]
      (try (f) (finally (swap! *compaction-sample* update key (fnil + 0.0) (elapsed-ms start)))))))

(defn profile-work [work]
  (let [samples (atom [])
        compact pg/compact-events!
        select @#'pg/eligible-event-metadata
        markers @#'pg/removable-transaction-ids
        execute jdbc/execute!
        execute-one jdbc/execute-one!
        execute-batch jdbc/execute-batch!
        transact jdbc/transact
        sql-call (fn [original conn sql opts]
                   (if-not *compaction-sample* (apply original conn sql opts)
                     (let [statement (first sql)
                           start (now-ns)
                           result (apply original conn sql opts)]
                       (swap! *compaction-sample* update :jdbc-calls (fnil inc 0))
                       (when (clojure.string/starts-with? statement "DELETE FROM grain.events")
                         (swap! *compaction-sample* assoc :delete-ms (elapsed-ms start)))
                       (when (and (clojure.string/includes? statement "pg_advisory_xact_lock")
                                  (= (second sql) (:tenant-lock-key @*compaction-sample*)))
                         (swap! *compaction-sample* assoc :tenant-lock-acquired-ns (now-ns)
                                :tenant-lock-wait-ms (elapsed-ms start)))
                       result)))]
    (with-redefs-fn
      {#'pg/compact-events!
       (fn [store request]
         (binding [*compaction-sample* (atom {:type (get-in request [:activation :event/type])
                                            :tenant-lock-key (pg/tenant-lock-key (:tenant-id request))})]
           (let [start (now-ns) receipt (compact store request)]
             (when receipt
               (swap! samples conj (-> @*compaction-sample*
                                       (dissoc :tenant-lock-key :tenant-lock-acquired-ns)
                                       (assoc :batch-ms (elapsed-ms start)))))
             receipt)))
       #'pg/eligible-event-metadata (fn [& args] (stage :selection-ms #(apply select args)))
       #'pg/removable-transaction-ids (fn [& args] (stage :marker-check-ms #(apply markers args)))
       #'jdbc/execute! (fn [conn sql & opts] (sql-call execute conn sql opts))
       #'jdbc/execute-one! (fn [conn sql & opts] (sql-call execute-one conn sql opts))
       #'jdbc/execute-batch!
       (fn [& args]
         (when *compaction-sample* (swap! *compaction-sample* update :jdbc-calls (fnil inc 0)))
         (stage :receipt-insert-ms #(apply execute-batch args)))
       #'jdbc/transact
       (fn [source f & opts]
         (if-not *compaction-sample* (apply transact source f opts)
           (let [body-end (volatile! nil)
                 result (apply transact source
                               (fn [conn] (let [r (f conn)] (vreset! body-end (now-ns)) r)) opts)]
             (when-let [acquired (:tenant-lock-acquired-ns @*compaction-sample*)]
               ;; Client-observed acquisition acknowledgement through commit
               ;; return (including connection release), not server-side tracing.
               (swap! *compaction-sample* assoc
                      :tenant-lock-held-ms (elapsed-ms acquired)
                      :commit-return-ms (elapsed-ms @body-end)))
             result)))}
      (fn []
        (let [result (work)
              metrics [:batch-ms :selection-ms :marker-check-ms :delete-ms :receipt-insert-ms
                       :tenant-lock-wait-ms :tenant-lock-held-ms :commit-return-ms]]
          (assoc result :profile
                 (into {}
                       (for [[type rows] (group-by :type @samples)]
                         [type {:batches (count rows)
                                :jdbc-calls {:min (apply min (map :jdbc-calls rows))
                                             :max (apply max (map :jdbc-calls rows))
                                             :total (reduce + (map :jdbc-calls rows))}
                                :timings (into {} (map (fn [key] [key (latency-summary (keep key rows))])) metrics)}]))))))))

(try
  (let [[fixture-path result-path] profile-args
        fixture (edn/read-string (slurp fixture-path))
        config {:conn {:type :postgres :server-name (or (System/getenv "PG_HOST") "127.0.0.1")
                       :port-number (or (System/getenv "PG_PORT") "5432")
                       :database-name (or (System/getenv "PG_DATABASE") "grain_retention_profile")
                       :username (or (System/getenv "PG_USER") "postgres")
                       :password (or (System/getenv "PG_PASSWORD") "password")}}
        result (binding [*prepared-fixture* fixture *store-config* config]
                 (profile-work #(run-scale! :postgres (get-in fixture [:load :domain-events]) 1000 result-path)))]
    (spit result-path (with-out-str (pprint/pprint result)))
    (progress! {:phase :profile-complete :path result-path}))
  (shutdown-agents)
  (catch Throwable t (.printStackTrace t) (shutdown-agents) (System/exit 1)))
