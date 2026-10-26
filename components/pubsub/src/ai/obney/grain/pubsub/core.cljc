(ns ai.obney.grain.pubsub.core
  (:require [ai.obney.grain.pubsub.core.core-async :as core-async]
            [ai.obney.grain.pubsub.core.protocol :as p]))

(defmulti start-pubsub :type)

(defmethod start-pubsub :core-async
  [config]
  (p/start (core-async/->CoreAsyncPubSub config)))

(defmethod start-pubsub :default 
  [config]
  (throw (ex-info "Unknown PubSub type" {:type (:type config)})))

(defn start
  [config]
  (start-pubsub config))

(defn stop
  [pubsub]
  (p/stop pubsub))

(defn pub
  [pubsub args]
  (p/pub pubsub args))

(defn sub
  [pubsub args]
  (p/sub pubsub args))

(defn unsub
  [pubsub args]
  (p/unsub pubsub args))
