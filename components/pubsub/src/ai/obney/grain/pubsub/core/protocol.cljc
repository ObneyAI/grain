(ns ai.obney.grain.pubsub.core.protocol)

(defprotocol PubSub
  (start [this])
  (stop [this])
  (pub [this args])
  (sub [this args])
  (unsub [this args]))
