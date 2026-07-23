(ns ai.obney.grain.kv-store.core
  (:require [ai.obney.grain.kv-store.interface.protocol :as p :refer [start-kv-store]]))

(defmethod start-kv-store :default
  [{:keys [type]}]
  (throw (ex-info (str "Unsupported kv store type: " type) {:type type})))

(defn start
  "Starts a kv-store. Accepts either a config map with a :type key
   (dispatches to the matching backend's start-kv-store method), or an
   already-constructed record satisfying the KVStore protocol (backwards
   compatible with direct construction, e.g. (lmdb/->KV-Store-LMDB {...}))."
  [config]
  (if (satisfies? p/KVStore config)
    (p/start config)
    (start-kv-store config)))
