(ns ai.obney.grain.event-store-postgres-v3.interface
  (:require [ai.obney.grain.event-store-postgres-v3.core]
            [ai.obney.grain.event-store-postgres-v3.interface.datasource :as datasource]))

(def make-datasource
  "Multimethod for creating a HikariCP datasource.
   Dispatches on (get-in config [:auth :type] :password).

   To add a custom auth strategy, define a defmethod on this var:

     (defmethod pg/make-datasource :aws-iam
       [{:keys [auth] :as config}]
       ...)"
  datasource/make-datasource)
