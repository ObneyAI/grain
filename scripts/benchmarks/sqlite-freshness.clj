;; From the repository root:
;; clojure -Sdeps '{:deps {org.xerial/sqlite-jdbc {:mvn/version "3.46.1.3"}}}' \
;;   -M scripts/benchmarks/sqlite-freshness.clj SNAPSHOT > results.edn
;; The snapshot is opened read-only; schema/statistics changes use temporary copies.
;; Select the largest tenant's two least/most frequent non-transaction types.
;; Requires at least four such types and 101 events in that tenant. Use skewed
;; histories with old rare-type events to exercise sparse freshness checks.
;; To include the actual backend query builder, run from the SQLite project:
;; GRAIN_FRESHNESS_PRODUCTION=true clojure -M ../../scripts/benchmarks/sqlite-freshness.clj \
;;   SNAPSHOT composite-bounded-analyze
(require '[clojure.string :as str])
(import '[java.sql DriverManager Connection PreparedStatement ResultSet]
        '[java.nio.file Files Path CopyOption]
        '[java.nio.file.attribute FileAttribute])

(defn connect ^Connection [url] (DriverManager/getConnection url))
(defn prepare ^PreparedStatement [^Connection conn sql params]
  (let [stmt (.prepareStatement conn sql)]
    (doseq [[i value] (map-indexed vector params)] (.setObject stmt (inc i) value))
    stmt))
(defn execute! [^Connection conn sql]
  (with-open [stmt (.createStatement conn)] (.execute stmt sql)))
(defn scalar [conn sql & params]
  (with-open [stmt (prepare conn sql params) rs (.executeQuery stmt)]
    (when (.next rs) (.getString rs 1))))
(defn rows [^PreparedStatement stmt]
  (with-open [rs (.executeQuery stmt)]
    (loop [out (transient [])]
      (if (.next rs)
        (recur (conj! out [(.getString rs 1) (.getString rs 2) (.getString rs 3)
                          (vec (.getBytes rs 4))]))
        (persistent! out)))))
(defn consume! [^PreparedStatement stmt]
  ;; Fetch all selected columns without decoding Fressian or running projections.
  (with-open [rs (.executeQuery stmt)]
    (loop [n 0]
      (if (.next rs)
        (do (.getString rs 1) (.getString rs 2) (.getString rs 3) (.getBytes rs 4)
            (recur (inc n)))
        n))))

(defn type-frequencies [conn tenant]
  (with-open [stmt (prepare conn
                           (str "SELECT type, count(*) AS n FROM events "
                                "WHERE tenant_id=? AND type<>? GROUP BY type "
                                "ORDER BY n, max(id), type")
                           [tenant ":grain/tx"])
              rs (.executeQuery stmt)]
    (loop [out []]
      (if (.next rs)
        (recur (conj out {:type (.getString rs 1) :count (.getLong rs 2)}))
        out))))
(defn placeholders [xs] (str/join "," (repeat (count xs) "?")))
(defn query [tenant {:keys [types after as-of reverse? limit]} variant]
  (let [bounds (str (when after " AND id>?") (when as-of " AND id<=?"))
        bound-params (cond-> [] after (conj after) as-of (conj as-of))
        suffix (str " ORDER BY id" (when reverse? " DESC") (when limit " LIMIT ?"))
        hint (case variant
               :type " INDEXED BY idx_events_tenant_type"
               :type-id " INDEXED BY idx_events_tenant_type_id"
               "")
        types (when types (vec (distinct types)))
        [sql params]
        (if (= variant :union)
          [(str/join " UNION ALL "
                     (repeat (count types)
                             (str "SELECT id,time,type,data FROM events WHERE tenant_id=? AND type=?" bounds)))
           (vec (mapcat #(into [tenant %] bound-params) types))]
          [(str "SELECT id,time,type,data FROM events" hint
                " WHERE tenant_id=?"
                (when types (str " AND type IN (" (placeholders types) ")")) bounds)
           (into (into [tenant] types) bound-params)])]
    [(str sql suffix) (cond-> params limit (conj limit))]))
(defn production-query [tenant work]
  (require 'ai.obney.grain.event-store-sqlite-v3.core)
  (let [build (ns-resolve 'ai.obney.grain.event-store-sqlite-v3.core 'build-single-query)
        {:keys [sql params]} (build (-> work
                                       (assoc :tenant-id tenant)
                                       (update :types #(when % (set (map (fn [s] (keyword (subs s 1))) %))))))]
    [sql params]))
(defn explain [conn sql params]
  (with-open [stmt (prepare conn (str "EXPLAIN QUERY PLAN " sql) params)
              rs (.executeQuery stmt)]
    (loop [out []]
      (if (.next rs) (recur (conj out (.getString rs 4))) out))))
(defn measure [conn tenant work variant expected]
  (let [[sql params] (if (= variant :production)
                       (production-query tenant work)
                       (query tenant work variant))]
    (with-open [stmt (prepare conn sql params)]
      (assert (= expected (rows stmt)) (str "Result/order mismatch: " (:name work) " " variant))
      (dotimes [_ 10] (consume! stmt))
      (let [reps (if (> (count expected) 1000) 5 100)
            times (repeatedly 3 #(let [start (System/nanoTime)]
                                   (dotimes [_ reps] (consume! stmt))
                                   (* (/ (- (System/nanoTime) start) 1e6) (/ 100.0 reps))))]
        {:work (:name work) :variant variant :rows (count expected)
         :median-ms-per-100 (nth (sort times) 1) :reps reps
         :plan (explain conn sql params)}))))

(Class/forName "org.sqlite.JDBC")
(let [source (first *command-line-args*)
      _ (assert source "Supply a saved Grain SQLite snapshot path")
      dir (Files/createTempDirectory "grain-freshness-" (make-array FileAttribute 0))
      base (.resolve dir "base.db")]
  (binding [*out* *err*] (println "Disposable copies:" (str dir)))
  (with-open [conn (connect (str "jdbc:sqlite:" (.toUri (Path/of source (make-array String 0))) "?mode=ro"))]
    (execute! conn (str "VACUUM INTO '" (str/replace (str base) "'" "''") "'")))
  (doseq [schema (if (next *command-line-args*)
                  (map keyword (next *command-line-args*))
                  [:base :bounded-analyze :analyze :composite :composite-bounded-analyze :composite-analyze])]
    (let [db (.resolve dir (str (name schema) "-run.db"))]
      (Files/copy base db (make-array CopyOption 0))
      (with-open [conn (connect (str "jdbc:sqlite:" db))]
        (when (str/includes? (name schema) "composite")
          (execute! conn "CREATE INDEX idx_events_tenant_type_id ON events(tenant_id,type,id)"))
        (when (str/includes? (name schema) "pruned")
          (execute! conn "DROP INDEX IF EXISTS idx_events_tenant_type")
          (execute! conn "DROP INDEX IF EXISTS idx_events_tenant_id_order"))
        (when (str/includes? (name schema) "bounded")
          (execute! conn "PRAGMA analysis_limit=400"))
        (when (str/includes? (name schema) "analyze") (execute! conn "ANALYZE"))
        (prn {:schema schema :sqlite (scalar conn "SELECT sqlite_version()")
              :pages (scalar conn "PRAGMA page_count") :page-size (scalar conn "PRAGMA page_size")
              :free-pages (scalar conn "PRAGMA freelist_count")
              :events (scalar conn "SELECT count(*) FROM events")
              :stat4 (scalar conn "SELECT sqlite_compileoption_used('ENABLE_STAT4')")})
        (let [tenant (scalar conn "SELECT tenant_id FROM events GROUP BY tenant_id ORDER BY count(*) DESC LIMIT 1")
              frequencies (type-frequencies conn tenant)
              _ (assert (>= (count frequencies) 4)
                        "Largest tenant needs at least four non-transaction event types")
              sparse-types (mapv :type (take 2 frequencies))
              dense-types (mapv :type (take 2 (reverse frequencies)))
              sparse (apply scalar conn
                            (str "SELECT max(id) FROM events WHERE tenant_id=? AND type IN (" (placeholders sparse-types) ")")
                            tenant sparse-types)
              recent (scalar conn "SELECT id FROM events WHERE tenant_id=? ORDER BY id DESC LIMIT 1 OFFSET 100" tenant)
              end (scalar conn "SELECT max(id) FROM events WHERE tenant_id=?" tenant)
              works [{:name :sparse-zero :types sparse-types :after sparse}
                     {:name :sparse-cold :types sparse-types}
                     {:name :dense-cold :types dense-types}
                     {:name :dense-old :types dense-types :after sparse}
                     {:name :dense-recent :types dense-types :after recent}
                     {:name :dense-zero :types dense-types :after end}
                     {:name :dense-limit1 :types dense-types :after sparse :limit 1}
                     {:name :single-dense-zero :types (take 1 dense-types) :after end}
                     {:name :bounded-reverse :types dense-types :after sparse :as-of recent :reverse? true :limit 10}
                     {:name :unfiltered-recent :after recent}
                     {:name :unfiltered-latest :reverse? true :limit 1}]
              variants (cond-> (if (str/includes? (name schema) "composite")
                                 [:default :type :type-id :union] [:default :type])
                         (str/includes? (name schema) "pruned") (->> (remove #{:type}) vec)
                         (= "true" (System/getenv "GRAIN_FRESHNESS_PRODUCTION")) (conj :production))]
          (assert (and sparse recent end) "Largest tenant needs at least 101 events")
          (prn {:schema schema :sparse-counts (mapv :count (take 2 frequencies))
                :dense-counts (mapv :count (take 2 (reverse frequencies)))})
          (doseq [work works]
            (let [[sql params] (query tenant work :default)
                  expected (with-open [stmt (prepare conn sql params)] (rows stmt))]
              (doseq [variant (if (:types work) variants (filter #{:default :production} variants))]
                (prn (assoc (measure conn tenant work variant expected) :schema schema))
                (flush)))))))))
