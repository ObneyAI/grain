(ns ai.obney.grain.kv-store.core
  (:require [ai.obney.grain.kv-store.interface.protocol :refer [start-kv-store]]))

(defmethod start-kv-store :default
  [{:keys [type]}]
  (throw (ex-info (str "Unsupported kv store type: " type) {:type type})))

(defn start
  [config]
  (start-kv-store config))
