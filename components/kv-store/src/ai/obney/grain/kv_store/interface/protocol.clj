(ns ai.obney.grain.kv-store.interface.protocol)

(defprotocol KVStore
  "A minimal protocol for a Key/Value store."
  (start [this])
  (stop [this])
  (get! [this args])
  (put! [this args]))