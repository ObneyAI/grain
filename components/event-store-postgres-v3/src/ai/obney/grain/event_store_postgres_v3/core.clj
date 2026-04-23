(ns ai.obney.grain.event-store-postgres-v3.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v3.interface.protocol :as p :refer [EventStore start-event-store]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.event-store-postgres-v3.interface.datasource :as datasource]
            [next.jdbc :as jdbc]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [cognitect.anomalies :as anom]
            [clojure.string :as string]
            [clojure.data.fressian :as fressian])
  (:import [java.io ByteArrayInputStream]))

;; --------------------- ;;
;; Fressian Serialization ;;
;; --------------------- ;;

(defn fressian-encode [data]
  (let [^java.nio.ByteBuffer buf (fressian/write data)
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn- deep-clojurize
  "Recursively convert Java collections to Clojure equivalents.
   clojure.walk/postwalk doesn't recurse into Java collections (they fail coll?),
   so nested structures like sets-of-vectors round-trip as sets-of-ArrayLists."
  [x]
  (cond
    (and (instance? java.util.Map x) (not (map? x)))
    (persistent!
     (reduce (fn [m e] (assoc! m (deep-clojurize (.getKey e)) (deep-clojurize (.getValue e))))
             (transient {}) x))

    (and (instance? java.util.Set x) (not (set? x)))
    (persistent!
     (reduce (fn [s v] (conj! s (deep-clojurize v)))
             (transient #{}) x))

    (and (instance? java.util.List x) (not (vector? x)))
    (mapv deep-clojurize x)

    (map? x)
    (persistent!
     (reduce-kv (fn [m k v] (assoc! m (deep-clojurize k) (deep-clojurize v)))
                (transient {}) x))

    (set? x)
    (persistent!
     (reduce (fn [s v] (conj! s (deep-clojurize v)))
             (transient #{}) x))

    (vector? x)
    (mapv deep-clojurize x)

    :else x))

(defn fressian-decode [bytes]
  (deep-clojurize (fressian/read (ByteArrayInputStream. bytes))))

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

(defn- ->offset-date-time
  "Convert a java.sql.Timestamp to java.time.OffsetDateTime (UTC)."
  [^java.sql.Timestamp ts]
  (.atOffset (.toInstant ts) java.time.ZoneOffset/UTC))

(defn transform-row
  "Transform PostgreSQL row to event schema format"
  [{:keys [id time type tags data] :as row}]
  (try
    (let [body-data (when data (fressian-decode data))
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
        sql  (str "SELECT id, time, type, tags, data FROM grain.events "
                  where-sql " ORDER BY id")
        conn (get-in event-store [:state ::connection-pool])]
    (make-reducible conn tenant-id sql params)))

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
      (fressian-encode
       (dissoc
        event
        :event/id
        :event/timestamp
        :event/type
        :event/tags))])
   {:batch-size 100}))

(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [tenant-id events tx-metadata]}]
  (let [events* (conj
                 events
                 (->event
                  {:type :grain/tx
                   :body (cond-> {:event-ids (set (mapv :event/id events))}
                           tx-metadata (assoc :metadata tx-metadata))}))
        max-event-id (:event/id (last events*))
        upsert-tenant!
        (fn [conn]
          (jdbc/execute! conn ["INSERT INTO grain.tenants (id, last_event_id) VALUES (?, ?)
                                ON CONFLICT (id) DO UPDATE SET last_event_id = ?"
                               tenant-id max-event-id max-event-id]))]
    (jdbc/with-transaction
      [conn (get-in event-store [:state ::connection-pool])]
      ;; Set tenant context for RLS
      (jdbc/execute! conn [(str "SET LOCAL app.tenant_id = '" (str tenant-id) "'")])
      ;; Per-tenant advisory lock
      (jdbc/execute! conn ["SET LOCAL lock_timeout = '5000ms'"])
      (jdbc/execute! conn ["SELECT pg_advisory_xact_lock(?)" (tenant-lock-key tenant-id)])
      ;; CAS check + insert. Tenant upsert (including last_event_id bump) runs
      ;; only on the successful-insert branches so last_event_id strictly
      ;; reflects events actually inserted; a CAS failure must not leave a
      ;; phantom watermark.
      (if cas
        (let [{:keys [where-sql params]} (build-single-query-sql
                                          (assoc cas :tenant-id tenant-id))
              sql (str "SELECT id, time, type, tags, data FROM grain.events "
                       where-sql " ORDER BY id")
              plan (jdbc/plan conn (into [sql] params) {:fetch-size 500})
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
            (do
              (upsert-tenant! conn)
              (insert-events conn tenant-id events*))
            (let [anomaly  {::anom/category ::anom/conflict
                            ::anom/message "CAS failed"
                            ::cas cas}]
              (u/log ::cas-failed :anomaly anomaly)
              anomaly)))
        (do
          (upsert-tenant! conn)
          (insert-events conn tenant-id events*))))))

(defn tenants
  [event-store]
  (let [conn (get-in event-store [:state ::connection-pool])
        rows (jdbc/execute! conn ["SELECT id, last_event_id FROM grain.tenants"])]
    (into {}
          (map (fn [{:tenants/keys [id last_event_id]}]
                 [id {:tenant/last-event-id last_event_id}]))
          rows)))

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
    (read this args)))

(defmethod start-event-store :postgres
  [config]
  (p/start
   (->PostgresEventStore (dissoc (:conn config) :type))))
