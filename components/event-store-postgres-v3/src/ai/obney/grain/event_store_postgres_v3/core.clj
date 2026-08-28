(ns ai.obney.grain.event-store-postgres-v3.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v3.interface.protocol :as p :refer [EventStore start-event-store]]
            [ai.obney.grain.event-store-v3.interface.compaction :as compaction]
            [ai.obney.grain.event-store-v3.interface.backend :refer [prepare-append]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.event-store-postgres-v3.interface.datasource :as datasource]
            [ai.obney.grain.fressian-util.interface :as fressian-util]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [cognitect.anomalies :as anom]
            [clojure.string :as string]))

;; --------------------- ;;
;; Advisory Lock Mapping  ;;
;; --------------------- ;;

(defn tenant-lock-key
  "Maps a tenant UUID to a bigint for use with pg_advisory_xact_lock.
   Uses the most significant 64 bits of the UUID."
  [^java.util.UUID tenant-id]
  (.getMostSignificantBits tenant-id))

;; -------------------------- ;;
;; Event Store Initialization ;;
;; -------------------------- ;;

(defn init-idempotently
  [{::keys [connection-pool] :as _event-store}]
  (u/trace
   ::initializing-event-store-idempotently
   []
   (jdbc/with-transaction [conn connection-pool]
     (doseq [statement ["CREATE SCHEMA IF NOT EXISTS grain;"

                        "CREATE TABLE IF NOT EXISTS grain.tenants (
                          id UUID PRIMARY KEY
                         );"

                        "CREATE TABLE IF NOT EXISTS grain.events (
                          tenant_id UUID         NOT NULL REFERENCES grain.tenants(id),
                          id        UUID         NOT NULL,
                          time      TIMESTAMPTZ  NOT NULL,
                          type      TEXT         NOT NULL,
                          tags      TEXT[]       NOT NULL,
                          data      BYTEA        NOT NULL,
                          PRIMARY KEY (tenant_id, id)
                         );"

                        "CREATE INDEX IF NOT EXISTS idx_events_tenant_type ON grain.events(tenant_id, type);"

                        "CREATE INDEX IF NOT EXISTS idx_events_tenant_type_time_id ON grain.events(tenant_id, type, time, id);"

                        "CREATE INDEX IF NOT EXISTS idx_events_tenant_tags_gin ON grain.events USING GIN (tags);"

                        "CREATE INDEX IF NOT EXISTS idx_events_tenant_id_order ON grain.events(tenant_id, id);"

                        "ALTER TABLE grain.events ENABLE ROW LEVEL SECURITY;"

                        "DO $$ BEGIN
                          IF NOT EXISTS (
                            SELECT 1 FROM pg_policies
                            WHERE tablename = 'events' AND schemaname = 'grain' AND policyname = 'tenant_isolation'
                          ) THEN
                            CREATE POLICY tenant_isolation ON grain.events
                              USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
                              WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
                          END IF;
                         END $$;"

                        "ALTER TABLE grain.tenants ADD COLUMN IF NOT EXISTS last_event_id UUID;"]]

       (jdbc/execute! conn [statement])))))

;; --------------------------- ;;
;; Integrant / Lifecycle Setup ;;
;; --------------------------- ;;

(defn start
  [config]
  (u/trace
   ::starting-event-store
   []
   (let [system (ig/init
                 {::config config
                  ::connection-pool {::config (ig/ref ::config)}})]
     (init-idempotently system)
     system)))

(defn stop
  [event-store]
  (u/trace
   ::stopping-event-store
   []
   (ig/halt! event-store)))

;; ---------------;;
;; Integrant keys ;;
;; -------------- ;;

(defmethod ig/init-key ::config [_ config]
  config)

(defmethod ig/init-key ::connection-pool [_ {::keys [config]}]
  (try
    (datasource/make-datasource config)
    (catch Throwable t
      (u/log ::error-creating-connection-pool :error t)
      (throw t))))

(defmethod ig/halt-key! ::connection-pool [_ connection-pool]
  (hikari/close-datasource connection-pool))

;; -------------- ;;
;; Data Transform  ;;
;; -------------- ;;

(defn parse-tags
  "Parse tags from PostgreSQL string array format to set of tuples"
  [tags-array]
  (when tags-array
    (let [tags-vec (if (instance? org.postgresql.jdbc.PgArray tags-array)
                     (.getArray tags-array)
                     tags-array)]
      (when (seq tags-vec)
        (->> tags-vec
             (map #(let [[entity-type entity-id] (string/split % #":" 2)]
                     [(keyword entity-type) (java.util.UUID/fromString entity-id)]))
             (into #{}))))))

(defn key-fn
  [k]
  (if (qualified-keyword? k)
    (str (namespace k) "/" (name k))
    (str (name k))))

(defn- placeholders [n]
  (string/join "," (repeat n "?")))

(defn- ->offset-date-time
  "Convert a java.sql.Timestamp to java.time.OffsetDateTime (UTC)."
  [^java.sql.Timestamp ts]
  (.atOffset (.toInstant ts) java.time.ZoneOffset/UTC))

(defn transform-row
  "Transform PostgreSQL row to event schema format"
  [{:keys [id time type tags data] :as row}]
  (try
    (let [body-data (when data (fressian-util/decode data))
          parsed-tags (parse-tags tags)]
      (merge
       {:event/id id
        :event/timestamp (->offset-date-time time)
        :event/type (keyword (string/replace type #"^:" ""))
        :event/tags (or parsed-tags #{})}
       body-data))
    (catch Exception e
      (u/log ::error-transforming-row :error e :row row)
      (throw e))))

;; ------------ ;;
;; Read Queries  ;;
;; ------------ ;;

(defn- build-single-query-sql
  "Build WHERE clause and params for a single read query.
   Returns {:where-sql \"WHERE ...\" :params [...]}"
  [{:keys [tenant-id tags types after as-of]}]
  (let [tenant-clause [["tenant_id = ?" tenant-id]]
        tag-clauses (when tags
                      [["tags @> ?::text[]"
                        (into-array String
                                    (map #(str (key-fn (first %)) ":" (second %)) tags))]])
        clauses  (->> (concat tenant-clause
                              tag-clauses
                              [(when types
                                 ["type = ANY(?)"
                                  (into-array String (mapv #(str ":" (key-fn %)) types))])
                               (when after ["id > ?" after])
                               (when as-of  ["id <= ?" as-of])])
                      (remove nil?))
        where-sql (if (seq clauses)
                    (str "WHERE " (string/join " AND " (map first clauses)))
                    "")]
    {:where-sql where-sql
     :params    (mapv second clauses)}))

(defn- order-limit-clause
  "Build the ` ORDER BY id [DESC] [LIMIT ?]` tail + its trailing param for a
   single read query. :limit's value, when present, must be appended to the
   query params AFTER the where-clause params. Single-query reads only."
  [{:keys [reverse? limit]}]
  {:sql    (str " ORDER BY id" (when reverse? " DESC") (when limit " LIMIT ?"))
   :params (if limit [limit] [])})

(defn- make-reducible
  "Create a reducible that opens a transaction, sets tenant context, and streams
   rows via transform-row over a JDBC plan. The transaction stays open for the
   duration of reduction, ensuring the connection is alive."
  [conn tenant-id sql params]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ f init]
      (jdbc/with-transaction [tx conn {:read-only true}]
        (jdbc/execute! tx [(str "SET LOCAL app.tenant_id = '" (str tenant-id) "'")])
        (let [plan (jdbc/plan tx (into [sql] params) {:fetch-size 500})]
          (reduce
           (fn [acc row]
             (f acc (transform-row row)))
           init
           plan))))
    clojure.lang.IReduce
    (reduce [_ f]
      (jdbc/with-transaction [tx conn {:read-only true}]
        (jdbc/execute! tx [(str "SET LOCAL app.tenant_id = '" (str tenant-id) "'")])
        (let [plan (jdbc/plan tx (into [sql] params) {:fetch-size 500})
              reduced-result
              (reduce
               (fn [acc row]
                 (if (= acc ::none)
                   (transform-row row)
                   (f acc (transform-row row))))
               ::none
               plan)]
          (if (= reduced-result ::none)
            (f)
            reduced-result))))))

(defn- read-single
  [event-store tenant-id query]
  (let [{:keys [where-sql params]} (build-single-query-sql query)
        {ol-sql :sql ol-params :params} (order-limit-clause query)
        sql  (str "SELECT id, time, type, tags, data FROM grain.events "
                  where-sql ol-sql)
        conn (get-in event-store [:state ::connection-pool])]
    (make-reducible conn tenant-id sql (into params ol-params))))

(defn- read-batch
  [event-store tenant-id queries]
  (let [sub-queries (map
                     (fn [query]
                       (let [{:keys [where-sql params]} (build-single-query-sql query)]
                         {:sql    (str "(SELECT id, time, type, tags, data FROM grain.events "
                                       where-sql ")")
                          :params params}))
                     queries)
        union-sql   (str "SELECT DISTINCT ON (id) id, time, type, tags, data FROM ("
                         (string/join " UNION ALL " (map :sql sub-queries))
                         ") AS combined ORDER BY id")
        all-params  (into [] (mapcat :params) sub-queries)
        conn (get-in event-store [:state ::connection-pool])]
    (make-reducible conn tenant-id union-sql all-params)))

(defn read
  [event-store args]
  (if (vector? args)
    (let [tenant-id (:tenant-id (first args))]
      (if (= 1 (count args))
        (read-single event-store tenant-id (first args))
        (read-batch event-store tenant-id args)))
    (read-single event-store (:tenant-id args) args)))

;; ------------ ;;
;; Append        ;;
;; ------------ ;;

(defn insert-events
  [conn tenant-id events]
  (jdbc/execute-batch!
   conn
   "INSERT INTO grain.events (tenant_id, id, time, type, tags, data) VALUES (?, ?, ?, ?, ?, ?)"
   (for [event events]
     [tenant-id
      (:event/id event)
      (:event/timestamp event)
      (str (:event/type event))
      (into-array
       String
       (reduce
        (fn [acc [k v]]
          (conj acc (str (key-fn k) ":" v)))
        []
        (:event/tags event)))
      (fressian-util/encode
       (dissoc
        event
        :event/id
        :event/timestamp
        :event/type
        :event/tags))])
   {:batch-size 100}))

(defn- committed-last-event-id
  [conn tenant-id]
  (some-> (jdbc/execute-one! conn
                             ["SELECT last_event_id FROM grain.tenants WHERE id = ?"
                              tenant-id])
          :tenants/last_event_id))

(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [tenant-id events tx-metadata]}]
  (jdbc/with-transaction
    [conn (get-in event-store [:state ::connection-pool])]
      ;; Set tenant context for RLS
      (jdbc/execute! conn [(str "SET LOCAL app.tenant_id = '" (str tenant-id) "'")])
      ;; Per-tenant advisory lock
      (jdbc/execute! conn ["SET LOCAL lock_timeout = '5000ms'"])
      (jdbc/execute! conn ["SELECT pg_advisory_xact_lock(?)" (tenant-lock-key tenant-id)])
      (let [last-id (committed-last-event-id conn tenant-id)
            persist! (fn []
                       (let [{:keys [events events-with-tx last-event-id]}
                             (prepare-append last-id events tx-metadata)]
                         (jdbc/execute! conn ["INSERT INTO grain.tenants (id, last_event_id) VALUES (?, ?)
                                               ON CONFLICT (id) DO UPDATE SET last_event_id = ?"
                                              tenant-id last-event-id last-event-id])
                         (insert-events conn tenant-id events-with-tx)
                         events))]
        ;; CAS check + insert. Tenant upsert (including last_event_id bump) runs
        ;; only on successful-insert branches.
        (if cas
          (let [cas-query (assoc cas :tenant-id tenant-id)
              {:keys [where-sql params]} (build-single-query-sql cas-query)
              {ol-sql :sql ol-params :params} (order-limit-clause cas-query)
              sql (str "SELECT id, time, type, tags, data FROM grain.events "
                       where-sql ol-sql)
              plan (jdbc/plan conn (into [sql] (into params ol-params)) {:fetch-size 500})
              cas-events (reify
                           clojure.lang.IReduceInit
                           (reduce [_ f init]
                             (reduce (fn [acc row] (f acc (transform-row row))) init plan))
                           clojure.lang.IReduce
                           (reduce [_ f]
                             (let [r (reduce (fn [acc row]
                                              (if (= acc ::none)
                                                (transform-row row)
                                                (f acc (transform-row row))))
                                            ::none plan)]
                               (if (= r ::none) (f) r))))]
            (if (predicate-fn cas-events)
              (persist!)
              (let [anomaly  {::anom/category ::anom/conflict
                              ::anom/message "CAS failed"
                              ::cas cas}]
                (u/log ::cas-failed :anomaly anomaly)
                anomaly)))
          (persist!)))))

(defn tenants
  [event-store]
  (let [conn (get-in event-store [:state ::connection-pool])
        rows (jdbc/execute! conn ["SELECT id, last_event_id FROM grain.tenants"])]
    (into {}
          (map (fn [{:tenants/keys [id last_event_id]}]
                 [id {:tenant/last-event-id last_event_id}]))
          rows)))

(defn- set-tenant! [conn tenant-id]
  (jdbc/execute! conn [(str "SET LOCAL app.tenant_id = '" (str tenant-id) "'")]))

(defn- load-policy-lifecycle-events [conn event-type]
  (set-tenant! conn compaction/system-tenant-id)
  (mapv transform-row
        (jdbc/execute!
         conn
         ["SELECT id, time, type, tags, data FROM grain.events
           WHERE tenant_id = ? AND type = ANY(?) AND tags @> ?::text[]
           ORDER BY id"
          compaction/system-tenant-id
          (into-array String (map str [compaction/policy-activated-type
                                       compaction/policy-deactivated-type]))
          (into-array String [(str (key-fn (first (compaction/policy-tag event-type)))
                                   ":" (second (compaction/policy-tag event-type)))])]
         {:builder-fn rs/as-unqualified-lower-maps})))

(defn- metadata->event [{:keys [id time]}]
  {:event/id id
   :event/timestamp (->offset-date-time time)})

(defn- unkeyed-eligible-metadata
  [conn tenant-id event-type cutoff-time limit]
  (set-tenant! conn tenant-id)
  (mapv metadata->event
        (jdbc/execute!
         conn
         ["SELECT id, time FROM grain.events
           WHERE tenant_id = ? AND type = ? AND time < ?
           ORDER BY id LIMIT ?"
          tenant-id (str event-type) cutoff-time limit]
         {:builder-fn rs/as-unqualified-lower-maps})))

(defn- keyed-eligible-metadata
  [conn tenant-id event-type cutoff-time limit tag-names]
  (set-tenant! conn tenant-id)
  (let [tag-names (mapv key-fn (sort-by key-fn tag-names))
        exact-counts (mapv (fn [_]
                             "(SELECT COUNT(*) FROM unnest(e.tags) tag
                               WHERE split_part(tag, ':', 1) = ?) = 1")
                           tag-names)
        sql (str "WITH keyed AS ("
                 "SELECT e.id, e.time, "
                 "ROW_NUMBER() OVER (PARTITION BY "
                 "ARRAY(SELECT tag FROM unnest(e.tags) tag "
                 "WHERE split_part(tag, ':', 1) = ANY(?::text[]) ORDER BY tag) "
                 "ORDER BY e.id DESC) AS retention_rank "
                 "FROM grain.events e "
                 "WHERE e.tenant_id = ? AND e.type = ? AND "
                 (string/join " AND " exact-counts)
                 ") SELECT id, time FROM keyed "
                 "WHERE time < ? AND retention_rank > 1 "
                 "ORDER BY id LIMIT ?")
        params (into [sql
                      (into-array String tag-names)
                      tenant-id
                      (str event-type)]
                     (concat tag-names [cutoff-time limit]))]
    (mapv metadata->event
          (jdbc/execute! conn params
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- eligible-event-metadata
  [conn tenant-id event-type policy cutoff-time limit]
  (if-let [tag-names (seq (get-in policy [:keep-latest-per :tags]))]
    (keyed-eligible-metadata conn tenant-id event-type cutoff-time limit tag-names)
    (unkeyed-eligible-metadata conn tenant-id event-type cutoff-time limit)))

(defn- postgres-compaction-context [conn {:keys [activation tenant-id limit evaluated-at]}]
  (let [{:keys [event/type policy]} activation
        lifecycle (when (and (pos-int? limit)
                             (not (contains? compaction/protected-event-types type)))
                    (load-policy-lifecycle-events conn type))]
    (when (and (pos-int? limit)
               (not (contains? compaction/protected-event-types type))
               (compaction/activation-matches? lifecycle activation))
      (let [cutoff (compaction/cutoff (or evaluated-at (time/now))
                                      (:retain-at-least policy))]
        {:cutoff cutoff
         :eligible (eligible-event-metadata
                    conn tenant-id type policy cutoff limit)}))))

(defn estimate-compaction [event-store request]
  (jdbc/with-transaction [conn (get-in event-store [:state ::connection-pool])]
    (if-let [{:keys [cutoff eligible]} (postgres-compaction-context conn request)]
      {:eligible-count (count eligible)
       :eligible-event-ids (mapv :event/id eligible)
       :cutoff cutoff}
      {:eligible-count 0 :authorized? false})))

(defn compact-events! [event-store {:keys [activation tenant-id] :as request}]
  (jdbc/with-transaction [conn (get-in event-store [:state ::connection-pool])]
    (jdbc/execute! conn ["SET LOCAL lock_timeout = '5000ms'"])
    (doseq [lock-key (sort [(tenant-lock-key compaction/system-tenant-id)
                            (tenant-lock-key tenant-id)])]
      (jdbc/execute! conn ["SELECT pg_advisory_xact_lock(?)" lock-key]))
    (if-let [{:keys [cutoff eligible]} (postgres-compaction-context conn request)]
      (when (seq eligible)
        (let [deleted-ids (set (map :event/id eligible))
              _ (set-tenant! conn tenant-id)
              last-id (committed-last-event-id conn tenant-id)
              receipt {:event/type compaction/compaction-receipt-type
                       :event/tags #{(compaction/policy-tag (:event/type activation))}
                       :retention/activation-id (:activation/id activation)
                       :retention/event-type (:event/type activation)
                       :retention/policy (:policy activation)
                       :retention/tenant-id tenant-id
                       :retention/cutoff cutoff
                       :retention/deleted-event-ids deleted-ids}
              {:keys [events events-with-tx last-event-id]}
              (prepare-append last-id [receipt]
                              {:grain/operation :retention-compaction})]
          (jdbc/execute! conn
                         (into [(str "DELETE FROM grain.events WHERE tenant_id = ? AND id IN ("
                                     (placeholders (count deleted-ids)) ")")
                                tenant-id]
                               deleted-ids))
          (jdbc/execute! conn ["INSERT INTO grain.tenants (id, last_event_id) VALUES (?, ?)
                                ON CONFLICT (id) DO UPDATE SET last_event_id = ?"
                               tenant-id last-event-id last-event-id])
          (insert-events conn tenant-id events-with-tx)
          (first events)))
      (throw (ex-info "Retention compaction is not authorized by the active policy"
                      {:request request})))))

;; ----------------- ;;
;; Record Definition ;;
;; ----------------- ;;

(defrecord PostgresEventStore [config]
  EventStore

  (start [this]
    (assoc this :state (start config)))

  (stop [this]
    (stop (:state this))
    (dissoc this :state))

  (tenants [this]
    (tenants this))

  (append [this args]
    (append this args))

  (read [this args]
    (read this args))

  compaction/EventCompaction
  (estimate [this request] (estimate-compaction this request))
  (compact! [this request] (compact-events! this request)))

(defmethod start-event-store :postgres
  [config]
  (p/start
   (->PostgresEventStore (dissoc (:conn config) :type))))
