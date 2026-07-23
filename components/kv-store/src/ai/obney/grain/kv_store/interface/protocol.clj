(ns ai.obney.grain.kv-store.interface.protocol)

(defmulti start-kv-store :type)

(defprotocol KVStore
  "A minimal protocol for a Key/Value store."
  (start [this])
  (stop [this])
  (get! [this args])
  (put! [this args])
  (put-batch! [this args]))