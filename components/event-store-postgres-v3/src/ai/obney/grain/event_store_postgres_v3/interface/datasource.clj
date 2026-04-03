(ns ai.obney.grain.event-store-postgres-v3.interface.datasource
  "Pluggable datasource creation for the Postgres event store.

   Dispatches on (get-in config [:auth :type]), defaulting to :password
   when no :auth key is present (backward compat with flat config).

   Built-in strategies:
     :password — password auth, supports both flat config and {:auth ...} style

   Extension point: define a defmethod for :aws-iam, :gcp-iam, etc.
   in your own namespace. Require that namespace at app startup.

   Example (in your project, NOT in this component):

     (ns my.app.auth.aws-iam
       (:require [ai.obney.grain.event-store-postgres-v3.interface.datasource :as ds]
                 [hikari-cp.core :as hikari]))

     (defmethod ds/make-datasource :aws-iam
       [{:keys [auth] :as config}]
       (hikari/make-datasource
         (-> (dissoc config :auth)
             (assoc :adapter \"postgresql\"
                    :username (:username auth)
                    :password (generate-iam-token config auth)))))"
  (:require [hikari-cp.core :as hikari]))

(defmulti make-datasource
  "Create a HikariCP datasource from a config map.
   Dispatches on (get-in config [:auth :type] :password)."
  (fn [config] (get-in config [:auth :type] :password)))

(defmethod make-datasource :password
  [{:keys [auth] :as config}]
  (let [username (or (:username auth) (:username config))
        password (or (:password auth) (:password config))]
    (hikari/make-datasource
      (-> (dissoc config :auth)
          (assoc :adapter "postgresql"
                 :username username
                 :password password)))))
