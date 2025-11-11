(ns ai.obney.grain.pubsub.core.core-async
  (:require [clojure.core.async :as async]
            [ai.obney.grain.pubsub.core.protocol :as protocol]))

(defn start
  [{{:keys [topic-fn]} :config}]
  (let [chan (async/chan 1024)
        pub (async/pub chan topic-fn)]
    {:chan chan
     :pub pub}))

(defn stop
  [{{:keys [chan]} :state}]
  (async/close! chan))

(defn pub
  [{{:keys [chan]} :state :as _pubsub}
   {:keys [message] :as _args}]
  (when message
    (async/put! chan message)))

(defn sub
  [{{:keys [pub]} :state :as _pubsub}
   {:keys [topic sub-chan] :as _args}]
  (async/sub pub topic sub-chan))


(defrecord CoreAsyncPubSub [config]
  protocol/PubSub
  (start [this]
    (assoc this :state (start this)))
  (stop [this]
    (stop this))
  (pub [this args]
    (pub this args))
  (sub [this args]
    (sub this args)))