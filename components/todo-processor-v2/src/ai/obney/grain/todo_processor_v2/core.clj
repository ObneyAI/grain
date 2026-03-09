(ns ai.obney.grain.todo-processor-v2.core
  (:require [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as u]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [integrant.core :as ig]
            [clojure.core.async :as async]
            [ai.obney.grain.core-async-thread-pool.interface :as thread-pool]))

(defn process-event
  [{:keys [handler-fn event event-store tenant-id] :as context}]
  (u/log ::process-event :event event)
  (u/trace
   ::processing-event
   [:event event :metric/name "TodoProcessed" :metric/resolution :high]
   (try
     (let [_ (u/log :metric/metric :metric/name "TodoStarted" :metric/value 1 :metric/resolution :high)
           result (or (handler-fn context)
                      {::anom/category ::anom/fault
                       ::anom/message  "Todo Processor returned nil: %s"})
           _ (u/log :metric/metric :metric/name "TodoFinished" :metric/value 1 :metric/resolution :high)]
       (if (anomaly? result)
         (u/log ::anomaly-in-todo-processor :anomaly result)
         (when-let [events (:result/events result)]
           (let [cas (:result/cas result)
                 event-store-result (event-store/append event-store (cond-> {:tenant-id tenant-id :events events}
                                                                     cas (assoc :cas cas)))]
             (when (anomaly? event-store-result)
               (u/log ::error-storing-events :anomaly event-store-result)
               (if (= ::anom/conflict (::anom/category event-store-result))
                 event-store-result
                 {::anom/category ::anom/fault
                  ::anom/message "Error storing events."}))))))
     (catch Throwable t
       (u/log ::uncaught-exception-in-todo-processor :exception t)))))

(def ^:private system
  {::handler-fn {}
   ::topics {}
   ::event-sub {:event-pubsub (ig/ref ::event-pubsub)
                :in-chan (ig/ref ::in-chan)
                :topics (ig/ref ::topics)}
   ::event-pubsub {}
   ::context {}
   ::execution-fn {:context (ig/ref ::context)
                   :handler-fn (ig/ref ::handler-fn)}
   ::in-chan {:size 1024}
   ::thread-pool {:thread-count 1
                  :error-fn (fn [e] (u/log ::error ::error e))
                  :in-chan (ig/ref ::in-chan)
                  :execution-fn (ig/ref ::execution-fn)}})

(defmethod ig/init-key ::context [_ config]
  config)

(defmethod ig/init-key ::in-chan [_ config]
  (u/log ::starting-in-chan config)
  (async/chan (:size config)))

(defmethod ig/halt-key! ::in-chan [_ in-chan]
  (u/log ::stopping-in-chan in-chan)
  (async/close! in-chan))

(defmethod ig/init-key ::execution-fn [_ {:keys [context handler-fn]}]
  (u/log ::starting-execution-fn)
  (fn [event]
    (async/thread
      (try (let [tenant-id (:grain/tenant-id event)
                 event (dissoc event :grain/tenant-id)]
             (process-event (assoc context
                              :event event
                              :handler-fn handler-fn
                              :tenant-id tenant-id)))
           (catch Throwable t
             {::anom/category ::anom/fault
              ::anom/message "Error processing message"
              :exception t})))))

(defmethod ig/init-key ::thread-pool [_ config]
  (u/log ::starting-thread-pool config)
  (thread-pool/start config))

(defmethod ig/halt-key! ::thread-pool [_ thread-pool]
  (u/log ::stopping-thread-pool thread-pool)
  (thread-pool/stop thread-pool))

(defmethod ig/init-key ::event-pubsub [_ event-pubsub]
  event-pubsub)

(defmethod ig/init-key ::event-sub [_ {:keys [event-pubsub in-chan topics]}]
  (run! #(pubsub/sub
          event-pubsub
          {:sub-chan in-chan
           :topic %})
        topics))

(defmethod ig/init-key ::handler-fn [_ config]
  config)

(defmethod ig/init-key ::topics [_ config]
  config)

(defn start
  [config]
  (ig/init (merge system
                  {::context (:context config)
                   ::event-pubsub (:event-pubsub config)
                   ::handler-fn (:handler-fn config)
                   ::topics (:topics config)})))

(defn stop
  [todo-processor]
  (ig/halt! todo-processor))
