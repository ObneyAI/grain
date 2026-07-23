(ns ai.obney.grain.kv-store-sqlite.core
  (:refer-clojure :exclude [get])
  (:require [ai.obney.grain.kv-store.interface.protocol :as p :refer [start-kv-store]]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [hikari-cp.core :as hikari]))

(def default-table-name
  "kv_store")

(def default-max-pool-size
  4)

(defn- valid-table-name?
  [table-name]
  (boolean (re-matches #"[a-zA-Z_][a-zA-Z0-9_]*" table-name)))

(defn- make-datasource
  [{:keys [database-file maximum-pool-size]}]
  (when-not database-file
    (throw (IllegalArgumentException.
            "kv-store-sqlite requires :database-file (path or \":memory:\")")))
  (hikari/make-datasource
   {:jdbc-url (str "jdbc:sqlite:" database-file)
    :driver-class-name "org.sqlite.JDBC"
    :maximum-pool-size (or maximum-pool-size default-max-pool-size)}))

(defn start
  [{{:keys [table-name] :or {table-name default-table-name}} :config
    :as kv-store}]
  (when-not (valid-table-name? table-name)
    (throw (IllegalArgumentException. (str "Invalid :table-name " (pr-str table-name)))))
  (let [datasource (make-datasource (:config kv-store))]
    (with-open [conn (jdbc/get-connection datasource)]
      (jdbc/execute! conn ["PRAGMA journal_mode = WAL"])
      (jdbc/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                                " (key BLOB PRIMARY KEY, value BLOB NOT NULL)")]))
    (assoc kv-store :datasource datasource :table-name table-name)))

(defn stop
  [{:keys [datasource] :as _kv-store}]
  (hikari/close-datasource datasource))

(defn get!
  [{:keys [datasource table-name] :as _kv-store} {:keys [k]}]
  (:value (jdbc/execute-one! datasource
                             [(str "SELECT value FROM " table-name " WHERE key = ?") k]
                             {:builder-fn rs/as-unqualified-maps})))

(defn put!
  [{:keys [datasource table-name] :as _kv-store} {:keys [k v]}]
  (jdbc/execute! datasource
                 [(str "INSERT INTO " table-name " (key, value) VALUES (?, ?) "
                       "ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                  k v]))

(defn put-batch!
  [{:keys [datasource table-name] :as _kv-store} {:keys [entries]}]
  (jdbc/with-transaction [tx datasource]
    (doseq [{:keys [k v]} entries]
      (jdbc/execute! tx
                     [(str "INSERT INTO " table-name " (key, value) VALUES (?, ?) "
                           "ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                      k v]))))

(defrecord KV-Store-Sqlite [config]
  p/KVStore
  (start [this] (start this))
  (stop [this] (stop this))
  (get! [this args] (get! this args))
  (put! [this args] (put! this args))
  (put-batch! [this args] (put-batch! this args)))

(defmethod start-kv-store :sqlite
  [config]
  (p/start (->KV-Store-Sqlite (dissoc config :type))))
