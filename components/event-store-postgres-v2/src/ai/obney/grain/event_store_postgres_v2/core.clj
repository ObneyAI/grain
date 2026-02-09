(ns ai.obney.grain.event-store-postgres-v2.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v2.interface.protocol :as p :refer [EventStore start-event-store]]
            [ai.obney.grain.event-store-v2.interface :refer [->event]]
            [next.jdbc :as jdbc]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [hikari-cp.core :as hikari]
            [cognitect.anomalies :as anom]
            [clojure.string :as string]
            [clojure.edn :as edn]))

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

                        ;; Table
                        "CREATE TABLE IF NOT EXISTS grain.events (
                          id   UUID         PRIMARY KEY,
                          time TIMESTAMPTZ NOT NULL,
                          type TEXT         NOT NULL,
                          tags TEXT[]       NOT NULL,
                          edn  TEXT         NOT NULL
                         );"
                        
                        "CREATE TABLE IF NOT EXISTS grain.global_lock (
                          id INTEGER PRIMARY KEY
                         );"
                        
                        "INSERT INTO grain.global_lock (id) VALUES (1) ON CONFLICT DO NOTHING;"

                        "CREATE INDEX IF NOT EXISTS idx_events_type ON grain.events(type);"

                        "CREATE INDEX IF NOT EXISTS idx_events_tags_gin ON grain.events USING GIN (tags);"]]

       (jdbc/execute! conn [statement])))))

;; --------------------------- ;;
;; Integrant / Lifecycle Setup ;;
;; --------------------------- ;;

(defn start
  [{::keys [_server-name _port-number _username _password _database-name] :as config}]
  (u/trace
   ::starting-event-store
   []
   (let [config* (assoc config :adapter "postgresql")
         system (ig/init
                 {::config config*
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
    (hikari/make-datasource config)
    (catch Throwable t
      (u/log ::error-creating-connection-pool :error t)
      (throw t))))

(defmethod ig/halt-key! ::connection-pool [_ connection-pool]
  (hikari/close-datasource connection-pool))

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

(defn transform-row
  "Transform PostgreSQL row to event schema format"
  [{:keys [id time type tags edn] :as row}]
  (try
    (let [body-data (when (seq edn) (edn/read-string edn))
          parsed-tags (parse-tags tags)]
      (merge
       {:event/id id
        :event/timestamp time
        :event/type (keyword (string/replace type #"^:" ""))
        :event/tags (or parsed-tags #{})}
       body-data))
    (catch Exception e
      (u/log ::error-transforming-row :error e :row row)
      (throw e))))


(defn- build-single-query-sql
  "Build WHERE clause and params for a single read query.
   Returns {:where-sql \"WHERE ...\" :params [...]}"
  [{:keys [tags types after as-of]}]
  (let [tag-clauses (when tags
                      [["tags @> ?::text[]"
                        (into-array String
                                    (map #(str (key-fn (first %)) ":" (second %)) tags))]])
        clauses  (->> (concat tag-clauses
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
  "Create a reducible that transforms rows via transform-row over a JDBC plan."
  [plan]
  (reify
    clojure.lang.IReduceInit
    (reduce [_ f init]
      (reduce
       (fn [acc row]
         (f acc (transform-row row)))
       init
       plan))
    clojure.lang.IReduce
    (reduce [_ f]
      (let [reduced-result
            (reduce
             (fn [acc row]
               (if (= acc ::none)
                 (transform-row row)
                 (f acc (transform-row row))))
             ::none
             plan)]
        (if (= reduced-result ::none)
          (f)
          reduced-result)))))

(defn- read-single
  [event-store query]
  (let [{:keys [where-sql params]} (build-single-query-sql query)
        sql  (str "SELECT id, time, type, tags, edn FROM grain.events "
                  where-sql " ORDER BY id")
        plan (jdbc/plan
              (get-in event-store [:state ::connection-pool])
              (into [sql] params)
              {:fetch-size 500})]
    (make-reducible plan)))

(defn- read-batch
  [event-store queries]
  (let [sub-queries (map-indexed
                     (fn [i query]
                       (let [{:keys [where-sql params]} (build-single-query-sql query)]
                         {:sql    (str "(SELECT id, time, type, tags, edn FROM grain.events "
                                       where-sql ")")
                          :params params}))
                     queries)
        union-sql   (str "SELECT DISTINCT ON (id) id, time, type, tags, edn FROM ("
                         (string/join " UNION ALL " (map :sql sub-queries))
                         ") AS combined ORDER BY id")
        all-params  (into [] (mapcat :params) sub-queries)
        plan        (jdbc/plan
                     (get-in event-store [:state ::connection-pool])
                     (into [union-sql] all-params)
                     {:fetch-size 500})]
    (make-reducible plan)))

(defn read
  [event-store args]
  (if (vector? args)
    (if (= 1 (count args))
      (read-single event-store (first args))
      (read-batch event-store args))
    (read-single event-store args)))

(defn insert-events
  [conn events]
  (jdbc/execute-batch!
   conn
   "INSERT INTO grain.events (id, time, type, tags, edn) VALUES (?, ?, ?, ?, ?)"
   (for [event events]
     [(:event/id event)
      (:event/timestamp event)
      (str (:event/type event))
      (into-array
       String
       (reduce
        (fn [acc [k v]]
          (conj acc (str (key-fn k) ":" v)))
        []
        (:event/tags event)))
      (pr-str
       (dissoc
        event
        :event/id
        :event/timestamp
        :event/type
        :event/tags))])
   {:batch-size 100}))


(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [events tx-metadata]}]
  (let [events* (conj
                 events
                 (->event
                  {:type :grain/tx
                   :body (cond-> {:event-ids (set (mapv :event/id events))}
                           tx-metadata (assoc :metadata tx-metadata))}))]
    (jdbc/with-transaction
      [conn (get-in event-store [:state ::connection-pool])]
      (jdbc/execute! conn ["SET LOCAL lock_timeout = '5000ms'"])
      (jdbc/execute! conn ["SELECT id FROM grain.global_lock FOR UPDATE"])
      (if cas
        (if (predicate-fn (read event-store cas))
          (insert-events conn events*)
          (let [anomaly  {::anom/category ::anom/conflict
                          ::anom/message "CAS failed"
                          ::cas cas}]
            (u/log ::cas-failed :anomaly anomaly)
            anomaly))
        (insert-events conn events*)))))

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

  (append [this args]
    (append this args))

  (read [this args]
    (read this args)))

(defmethod start-event-store :postgres
  [config]
  (p/start
   (->PostgresEventStore (dissoc (:conn config) :type))))

(comment

  (def es (p/start-event-store
           {:conn {:type :postgres
                   :server-name "localhost"
                   :port-number "5433"
                   :username "postgres"
                   :password "password"
                   :database-name "obneyai"}}))

  (stop es)


  (def user-id #uuid "eae56ad0-6575-418f-a5f5-bab2674ac2c9")

  (p/append
   es
   {:events [(->event
              {:type :hello/world
               :tags #{[:user user-id]}
               :body {:user-id user-id}})]})


  (reduce
   (fn [acc event]
     (conj acc event))
   []
   (read es {}))

  
  {:event/id #uuid "0197cbed-d1e3-70ac-8fca-a9c23706d550",
   :event/timestamp #inst "2025-07-02T16:17:30.083469000-00:00",
   :event/type :hello/world,
   :event/tags #{[:user #uuid "eae56ad0-6575-418f-a5f5-bab2674ac2c9"]},
   :user-id #uuid "eae56ad0-6575-418f-a5f5-bab2674ac2c9",
   :message "Testing transformation"}



  "")