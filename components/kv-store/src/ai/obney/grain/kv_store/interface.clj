(ns ai.obney.grain.kv-store.interface
  (:require [ai.obney.grain.kv-store.interface.protocol :as p]))

(defn start 
  [kv-store]
  (p/start kv-store))

(defn stop
  [kv-store]
  (p/stop kv-store))

(defn get!
  [kv-store args]
  (p/get! kv-store args))

(defn put!
  [kv-store args]
  (p/put! kv-store args))

(defn read-snapshot
  "Call f with a get function accepting {:k bytes}. All reads observe one
   consistent snapshot and return copied byte arrays (or nil for missing keys).
   Complete reads eagerly on the calling thread; do not retain the get function
   or use it after f returns. Returns f's result."
  [kv-store f]
  (p/read-snapshot kv-store f))

(defn put-batch!
  [kv-store args]
  (p/put-batch! kv-store args))
