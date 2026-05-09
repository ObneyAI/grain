(ns ai.obney.grain.event-store-sqlite-v3.interface.datasource
  "Pluggable datasource creation for the SQLite event store.

   Dispatches on (:type config), defaulting to :file when no type key
   is present.

   Built-in strategies:
     :file   — file-backed SQLite at :database-file (or \":memory:\")

   Extension point: register additional defmethods (e.g. for custom
   SQLiteConfig setups) in your own namespace."
  (:require [hikari-cp.core :as hikari]))

(defmulti make-datasource
  "Create a HikariCP datasource from a SQLite config map.
   Dispatches on (:type config), defaulting to :file."
  (fn [config] (get config :type :file)))

(defmethod make-datasource :file
  [{:keys [database-file] :as config}]
  (when-not database-file
    (throw (IllegalArgumentException.
            "SQLite datasource requires :database-file (path or \":memory:\")")))
  (hikari/make-datasource
   (-> config
       (dissoc :database-file :type)
       (assoc :jdbc-url (str "jdbc:sqlite:" database-file)
              :driver-class-name "org.sqlite.JDBC"
              :maximum-pool-size (or (:maximum-pool-size config) 4)
              ;; Per-connection PRAGMA. journal_mode=WAL persists at the
              ;; database level (set once in init-idempotently); foreign_keys
              ;; is per-connection and must be re-asserted here.
              :connection-init-sql "PRAGMA foreign_keys = ON"))))
