(ns ai.obney.grain.event-store-v3.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v3.interface.schemas :as schemas]
            [ai.obney.grain.event-store-v3.interface.protocol :as p :refer [start-event-store]]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [malli.core :as mc]
            [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as u])
  (:import [clojure.lang ExceptionInfo]
           [java.sql SQLException]))

(defn- backend-name
  [event-store]
  (or (get-in event-store [:config :event-store/backend]) "unknown"))

(defn- dimensions
  [event-store operation outcome]
  {:backend (backend-name event-store)
   :operation operation
   :outcome outcome})

(defn- emit-counter!
  [metric-name event-store operation outcome]
  (u/log :metric/metric
         :metric/name metric-name
         :metric/value 1
         :metric/resolution :low
         :metric/dimensions (dimensions event-store operation outcome)))

(defn- emit-gauge!
  [metric-name value event-store operation outcome]
  (u/log :metric/metric
         :metric/name metric-name
         :metric/value value
         :metric/resolution :low
         :metric/dimensions (dimensions event-store operation outcome)))

(defn- emit-duration!
  [metric-name started-at event-store operation outcome]
  (u/log :metric/metric
         :metric/name metric-name
         :mulog/duration (- (System/nanoTime) started-at)
         :metric/resolution :high
         :metric/dimensions (dimensions event-store operation outcome)))

(defn- connection-failure?
  [throwable]
  (loop [cause throwable]
    (cond
      (nil? cause) false
      (instance? SQLException cause)
      (let [sql-state (.getSQLState ^SQLException cause)]
        (or (and sql-state (.startsWith sql-state "08"))
            (recur (.getCause ^Throwable cause))))
      :else (recur (.getCause ^Throwable cause)))))

(defn- emit-operation-exception!
  [failure-metric event-store operation throwable]
  (emit-counter! failure-metric event-store operation "failed")
  (when (connection-failure? throwable)
    (emit-counter! "EventStoreConnectionFailure" event-store operation "failed")))

(defmethod start-event-store :default
  [{{:keys [type]} :conn}]
  (throw (ex-info (str "Unsupported event store type: " type) {:type type})))

(defn start
  [config]
  (let [backend (some-> (get-in config [:conn :type]) name)]
    (try
      (-> (start-event-store config)
          (assoc-in [:config :event-store/backend] backend)
          (assoc-in [:config :event-pubsub] (:event-pubsub config)))
      (catch Throwable throwable
        (when (connection-failure? throwable)
          (emit-counter! "EventStoreConnectionFailure"
                         {:config {:event-store/backend (or backend "unknown")}}
                         "start"
                         "failed"))
        (throw throwable)))))

(defn stop
  [event-store]
  (p/stop event-store))

(defn append
  [{{:keys [event-pubsub]} :config
    :as event-store}
   {:keys [events] :as args}]
  (let [started-at (System/nanoTime)
        validation-errors
        (or

         ;; Invalid arguments
         (when-let [validation-error (mc/explain ::schemas/append-args args)]
           {::anom/category ::anom/incorrect
            ::anom/message "Invalid arguments"
            :explain/data validation-error})

         ;; Schema validation issues
         (try (->> events
                   (mapv #(mc/explain [:and ::schemas/appendable-event (:event/type %)] %))
                   (filterv (complement nil?)))
              (catch ExceptionInfo _
                {::anom/category ::anom/fault
                 ::anom/message "One or more event schemas are not defined for :event/type"
                 ::event-names (set (map :event/name events))})))]
    (try
      (let [result
            (cond
              (anomaly? validation-errors)
              validation-errors

              (seq validation-errors)
              (do
                (u/log ::validation-errors :validation-errors validation-errors)
                {::anom/category ::anom/fault
                 ::anom/message "Invalid Event(s): Failed Schema Validation"
                 :error/explain validation-errors})

              :else
              (let [result (p/append event-store args)]
                (if (anomaly? result)
                  result
                  (let [{:keys [tenant-id]} args
                        persisted-events (if (sequential? result) result events)]
                    (when event-pubsub
                      (run! #(pubsub/pub event-pubsub {:message (assoc % :grain/tenant-id tenant-id)})
                            persisted-events))
                    result))))
            failed? (anomaly? result)
            outcome (if failed? "failed" "succeeded")]
        (emit-duration! "EventAppendDuration" started-at event-store "append" outcome)
        (if failed?
          (do
            (emit-counter! "EventAppendFailed" event-store "append" outcome)
            (when (= ::anom/conflict (::anom/category result))
              (emit-counter! "EventCasConflict" event-store "append" outcome)))
          (do
            (emit-counter! "EventAppendSucceeded" event-store "append" outcome)
            (emit-gauge! "EventAppendEventCount" (count events)
                         event-store "append" outcome)))
        result)
      (catch Throwable throwable
        (emit-duration! "EventAppendDuration" started-at event-store "append" "failed")
        (emit-operation-exception! "EventAppendFailed" event-store "append" throwable)
        (throw throwable)))))

(defn tenants
  [event-store]
  (p/tenants event-store))

(defn- observed-read
  [event-store reducible]
  (letfn [(observe [reduce-fn]
            (let [started-at (System/nanoTime)
                  event-count (volatile! 0)]
              (try
                (let [result (reduce-fn (fn [rf]
                                          (fn
                                            ([] (rf))
                                            ([acc] (rf acc))
                                            ([acc event]
                                             (vswap! event-count inc)
                                             (rf acc event)))))]
                  (emit-duration! "EventReadDuration" started-at event-store "read" "succeeded")
                  (emit-gauge! "EventReadEventCount" @event-count
                               event-store "read" "succeeded")
                  result)
                (catch Throwable throwable
                  (emit-duration! "EventReadDuration" started-at event-store "read" "failed")
                  (emit-operation-exception! "EventReadFailed" event-store "read" throwable)
                  (throw throwable)))))]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ rf init]
        (observe #(reduce (% rf) init reducible)))
      clojure.lang.IReduce
      (reduce [_ rf]
        (observe (fn [wrap]
                   (let [none (Object.)
                         result (reduce (wrap (fn [acc event]
                                                (if (identical? acc none)
                                                  event
                                                  (rf acc event))))
                                        none
                                        reducible)]
                     (if (identical? result none)
                       (rf)
                       result))))))))

(defn read
  [event-store args]
  (let [started-at (System/nanoTime)]
    (if-let [validation-error (mc/explain ::schemas/read-args args)]
      (let [result {::anom/category ::anom/incorrect
                    ::anom/message "Invalid arguments"
                    :explain/data validation-error}]
        (emit-duration! "EventReadDuration" started-at event-store "read" "failed")
        (emit-counter! "EventReadFailed" event-store "read" "failed")
        result)
      (try
        (let [result (p/read event-store args)]
          (if (anomaly? result)
            (do
              (emit-duration! "EventReadDuration" started-at event-store "read" "failed")
              (emit-counter! "EventReadFailed" event-store "read" "failed")
              result)
            (observed-read event-store result)))
        (catch Throwable throwable
          (emit-duration! "EventReadDuration" started-at event-store "read" "failed")
          (emit-operation-exception! "EventReadFailed" event-store "read" throwable)
          (throw throwable))))))

(defn ->event
  [{:keys [type body tags] :or {tags #{}} :as args}]
  (if-let [validation-error (mc/explain ::schemas/->event-args args)]
    {::anom/category ::anom/incorrect
     ::anom/message "Invalid arguments"
     :explain/data validation-error}
    (merge
     {:event/type type
      :event/tags tags}
     body)))
