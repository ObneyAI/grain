(ns ai.obney.grain.event-store-sqlite-v3.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v3.interface.protocol :as p :refer [EventStore start-event-store]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [ai.obney.grain.event-store-sqlite-v3.interface.datasource :as datasource]
            [ai.obney.grain.fressian-util.interface :as fressian-util]
            [next.jdbc :as jdbc]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [cognitect.anomalies :as anom]
            [clojure.string :as string])
  (:import [java.time OffsetDateTime]
           [java.util UUID]
           [java.sql Connection]))

;; -------------------------- ;;
;; Event Store Initialization ;;
;; -------------------------- ;;

(def ^:private schema-statements
  ["CREATE TABLE IF NOT EXISTS tenants (
     id            TEXT PRIMARY KEY,
     last_event_id TEXT
    );"

   "CREATE TABLE IF NOT EXISTS events (
     tenant_id TEXT NOT NULL REFERENCES tenants(id),
     id        TEXT NOT NULL,
     time      TEXT NOT NULL,
     type      TEXT NOT NULL,
     data      BLOB NOT NULL,
     PRIMARY KEY (tenant_id, id)
    );"

   "CREATE INDEX IF NOT EXISTS idx_events_tenant_type     ON events(tenant_id, type);"
   "CREATE INDEX IF NOT EXISTS idx_events_tenant_id_order ON events(tenant_id, id);"

   "CREATE TABLE IF NOT EXISTS event_tags (
     tenant_id TEXT NOT NULL,
     event_id  TEXT NOT NULL,
     tag       TEXT NOT NULL,
     PRIMARY KEY (tenant_id, tag, event_id),
     FOREIGN KEY (tenant_id, event_id) REFERENCES events(tenant_id, id) ON DELETE CASCADE
    );"

   "CREATE INDEX IF NOT EXISTS idx_event_tags_event ON event_tags(tenant_id, event_id);"])

(defn init-idempotently
  [{::keys [connection-pool] :as _event-store}]
  (u/trace
   ::initializing-event-store-idempotently
   []
   ;; journal_mode = WAL is a one-shot DB-level setting that persists across
   ;; connections; set it outside the schema migration transaction so it takes
   ;; effect on the same connection used to run the DDL.
   (with-open [conn (jdbc/get-connection connection-pool)]
     (jdbc/execute! conn ["PRAGMA journal_mode = WAL"])
     (jdbc/execute! conn ["PRAGMA foreign_keys = ON"]))
   (jdbc/with-transaction [conn connection-pool]
     (doseq [statement schema-statements]
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
;; Encoding       ;;
;; -------------- ;;

(defn- key-fn
  [k]
  (if (qualified-keyword? k)
    (str (namespace k) "/" (name k))
    (str (name k))))

(defn- tag->str
  [[entity-type entity-id]]
  (str (key-fn entity-type) ":" entity-id))

(defn- str->tag
  [s]
  (let [[entity-type entity-id] (string/split s #":" 2)
        slash-idx (.indexOf entity-type "/")]
    [(if (neg? slash-idx)
       (keyword entity-type)
       (keyword (subs entity-type 0 slash-idx) (subs entity-type (inc slash-idx))))
     (UUID/fromString entity-id)]))

(defn- transform-row
  "Reconstruct an event map from a SQLite row. Columns: id, time, type, data.
   :event/tags lives inside the Fressian-encoded body blob."
  [{:keys [id time type data]}]
  (try
    (merge
     {:event/id (UUID/fromString id)
      :event/timestamp (OffsetDateTime/parse ^CharSequence time)
      :event/type (keyword (subs type 1))}        ; strip leading ":"
     (when data (fressian-util/decode data)))
    (catch Exception e
      (u/log ::error-transforming-row :error e :id id :type type)
      (throw e))))

;; ------------ ;;
;; Read Queries  ;;
;; ------------ ;;

(defn- placeholders
  "Comma-separated `?` placeholders for an `IN (...)` clause."
  [n]
  (string/join "," (repeat n "?")))

(defn- build-single-query
  "Build SQL + params for a single read query.

   No-tags case:  SELECT ... FROM events WHERE tenant_id = ? [AND ...] ORDER BY id
   Tags case:     SELECT ... FROM events e
                  JOIN event_tags t ON t.tenant_id = e.tenant_id AND t.event_id = e.id
                  WHERE e.tenant_id = ? AND t.tag IN (...) [AND ...]
                  GROUP BY e.id HAVING COUNT(DISTINCT t.tag) = ?
                  ORDER BY e.id

   Returns {:sql \"...\" :params [...]}."
  [{:keys [tenant-id tags types after as-of]}]
  (let [tenant-str (str tenant-id)]
    (if (seq tags)
      (let [tag-strs (mapv tag->str tags)
            type-strs (when types (mapv #(str ":" (key-fn %)) types))
            where-parts (cond-> ["e.tenant_id = ?"
                                 (str "t.tag IN (" (placeholders (count tag-strs)) ")")]
                          types  (conj (str "e.type IN (" (placeholders (count type-strs)) ")"))
                          after  (conj "e.id > ?")
                          as-of  (conj "e.id <= ?"))
            params (cond-> (into [tenant-str] tag-strs)
                     types  (into type-strs)
                     after  (conj (str after))
                     as-of  (conj (str as-of))
                     true   (conj (count tag-strs)))
            sql (str "SELECT e.id AS id, e.time AS time, e.type AS type, e.data AS data "
                     "FROM events e "
                     "JOIN event_tags t ON t.tenant_id = e.tenant_id AND t.event_id = e.id "
                     "WHERE " (string/join " AND " where-parts) " "
                     "GROUP BY e.id "
                     "HAVING COUNT(DISTINCT t.tag) = ? "
                     "ORDER BY e.id")]
        {:sql sql :params params})
      (let [type-strs (when types (mapv #(str ":" (key-fn %)) types))
            where-parts (cond-> ["tenant_id = ?"]
                          types (conj (str "type IN (" (placeholders (count type-strs)) ")"))
                          after (conj "id > ?")
                          as-of (conj "id <= ?"))
            params (cond-> [tenant-str]
                     types (into type-strs)
                     after (conj (str after))
                     as-of (conj (str as-of)))
            sql (str "SELECT id, time, type, data FROM events "
                     "WHERE " (string/join " AND " where-parts) " "
                     "ORDER BY id")]
        {:sql sql :params params}))))

(defn- make-reducible
  "Create a reducible that opens a read-only transaction, runs `sql` with `params`,
   and streams rows via transform-row over a JDBC plan. The transaction stays open
   for the duration of reduction."
  [pool sql params]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ f init]
      (jdbc/with-transaction [tx pool]
        (reduce
         (fn [acc row] (f acc (transform-row row)))
         init
         (jdbc/plan tx (into [sql] params)))))
    clojure.lang.IReduce
    (reduce [_ f]
      (jdbc/with-transaction [tx pool]
        (let [result (reduce
                      (fn [acc row]
                        (if (= acc ::none)
                          (transform-row row)
                          (f acc (transform-row row))))
                      ::none
                      (jdbc/plan tx (into [sql] params)))]
          (if (= result ::none) (f) result))))))

(defn- read-single
  [event-store query]
  (let [{:keys [sql params]} (build-single-query query)
        pool (get-in event-store [:state ::connection-pool])]
    (make-reducible pool sql params)))

(defn- read-batch
  "Build a UNION ALL of sub-queries and dedupe by id. Each sub-query produces
   rows with byte-identical (id, time, type, data) columns for the same event,
   so picking any representative per id (via GROUP BY id) is correct."
  [event-store queries]
  (let [sub (mapv build-single-query queries)
        union-sql (str "SELECT id, time, type, data FROM ("
                       (string/join " UNION ALL "
                                    (map #(str "SELECT id, time, type, data FROM ("
                                               (:sql %)
                                               ")")
                                         sub))
                       ") GROUP BY id ORDER BY id")
        all-params (into [] (mapcat :params) sub)
        pool (get-in event-store [:state ::connection-pool])]
    (make-reducible pool union-sql all-params)))

(defn read
  [event-store args]
  (if (vector? args)
    (if (= 1 (count args))
      (read-single event-store (first args))
      (read-batch event-store args))
    (read-single event-store args)))

;; ------------ ;;
;; Append        ;;
;; ------------ ;;

(defn- insert-events!
  [conn tenant-id events]
  (let [tenant-str (str tenant-id)]
    (jdbc/execute-batch!
     conn
     "INSERT INTO events (tenant_id, id, time, type, data) VALUES (?, ?, ?, ?, ?)"
     (for [event events]
       [tenant-str
        (str (:event/id event))
        (str (:event/timestamp event))
        (str (:event/type event))
        (fressian-util/encode
         (dissoc event :event/id :event/timestamp :event/type))])
     {:batch-size 100})
    (let [tag-rows (for [event events
                         tag (:event/tags event)]
                     [tenant-str (str (:event/id event)) (tag->str tag)])]
      (when (seq tag-rows)
        (jdbc/execute-batch!
         conn
         "INSERT INTO event_tags (tenant_id, event_id, tag) VALUES (?, ?, ?)"
         tag-rows
         {:batch-size 100})))))

(defn- upsert-tenant!
  [conn tenant-id last-event-id]
  (jdbc/execute! conn
                 ["INSERT INTO tenants (id, last_event_id) VALUES (?, ?)
                   ON CONFLICT(id) DO UPDATE SET last_event_id = excluded.last_event_id"
                  (str tenant-id) (str last-event-id)]))

(defn- with-immediate-tx
  "Run body-fn inside a BEGIN IMMEDIATE transaction on a fresh connection.
   body-fn receives the connection and returns its result.
   Rolls back if body-fn returns an anomaly map or throws; otherwise commits.

   We must keep auto-commit = true so that JDBC does not issue an implicit
   DEFERRED BEGIN before our explicit BEGIN IMMEDIATE statement runs."
  [pool body-fn]
  (with-open [conn (jdbc/get-connection pool)]
    (.setAutoCommit ^Connection conn true)
    (jdbc/execute! conn ["BEGIN IMMEDIATE"])
    (let [completed? (volatile! false)]
      (try
        (let [result (body-fn conn)]
          (if (and (map? result) (::anom/category result))
            (do (jdbc/execute! conn ["ROLLBACK"])
                (vreset! completed? true)
                result)
            (do (jdbc/execute! conn ["COMMIT"])
                (vreset! completed? true)
                result)))
        (catch Throwable t
          (when-not @completed?
            (try (jdbc/execute! conn ["ROLLBACK"]) (catch Throwable _ nil)))
          (throw t))))))

(defn- conn-reducible
  "Reducible that reads from an already-open connection (no nested transaction)."
  [conn sql params]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ f init]
      (reduce (fn [acc row] (f acc (transform-row row)))
              init
              (jdbc/plan conn (into [sql] params))))
    clojure.lang.IReduce
    (reduce [_ f]
      (let [result (reduce
                    (fn [acc row]
                      (if (= acc ::none)
                        (transform-row row)
                        (f acc (transform-row row))))
                    ::none
                    (jdbc/plan conn (into [sql] params)))]
        (if (= result ::none) (f) result)))))

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
        pool (get-in event-store [:state ::connection-pool])]
    (with-immediate-tx
      pool
      (fn [conn]
        (if cas
          (let [{:keys [sql params]} (build-single-query (assoc cas :tenant-id tenant-id))
                cas-events (conn-reducible conn sql params)]
            (if (predicate-fn cas-events)
              (do
                (upsert-tenant! conn tenant-id max-event-id)
                (insert-events! conn tenant-id events*))
              (let [anomaly {::anom/category ::anom/conflict
                             ::anom/message "CAS failed"
                             ::cas cas}]
                (u/log ::cas-failed :anomaly anomaly)
                anomaly)))
          (do
            (upsert-tenant! conn tenant-id max-event-id)
            (insert-events! conn tenant-id events*)))))))

;; ----------- ;;
;; Tenants     ;;
;; ----------- ;;

(defn tenants
  [event-store]
  (let [pool (get-in event-store [:state ::connection-pool])
        rows (jdbc/execute! pool ["SELECT id, last_event_id FROM tenants"])]
    (into {}
          (map (fn [{:tenants/keys [id last_event_id]}]
                 [(UUID/fromString id)
                  {:tenant/last-event-id (some-> last_event_id UUID/fromString)}]))
          rows)))

;; ----------------- ;;
;; Record Definition ;;
;; ----------------- ;;

(defrecord SqliteEventStore [config]
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

(defmethod start-event-store :sqlite
  [config]
  (p/start
   (->SqliteEventStore (dissoc (:conn config) :type))))
