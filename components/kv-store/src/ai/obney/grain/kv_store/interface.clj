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

