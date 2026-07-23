(ns ai.obney.grain.kv-store.interface
  (:require [ai.obney.grain.kv-store.interface.protocol :as p]
            [ai.obney.grain.kv-store.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop
  [kv-store]
  (p/stop kv-store))

(defn get!
  [kv-store args]
  (p/get! kv-store args))

(defn put!
  [kv-store args]
  (p/put! kv-store args))

(defn put-batch!
  [kv-store args]
  (p/put-batch! kv-store args))

