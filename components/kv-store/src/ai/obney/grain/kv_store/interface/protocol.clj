(ns ai.obney.grain.kv-store.interface.protocol)

(defprotocol KVStore
  "A minimal protocol for a Key/Value store."
  (start [this])
  (stop [this])
  (get! [this args])
  (read-snapshot [this f])
  (put! [this args])
  (put-batch! [this args]))

(defprotocol TransactionalStore
  (transact [this mode f]
    "Call f with scoped :get/:put/:delete/:reduce operations in :read or :write mode."))
