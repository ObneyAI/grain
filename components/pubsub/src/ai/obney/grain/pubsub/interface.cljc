(ns ai.obney.grain.pubsub.interface
  (:require [ai.obney.grain.pubsub.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop 
  [pubsub]
  (core/stop pubsub))

(defn pub
  [pubsub args]
  (core/pub pubsub args))

(defn sub
  [pubsub args]
  (core/sub pubsub args))

(defn unsub
  "Immediately removes sub-chan from topic, without closing the channel."
  [pubsub args]
  (core/unsub pubsub args))
