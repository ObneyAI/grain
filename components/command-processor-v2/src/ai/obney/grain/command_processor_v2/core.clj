(ns ai.obney.grain.command-processor-v2.core
  (:require
   [ai.obney.grain.event-store-v3.interface :as event-store]
   [ai.obney.grain.command-processor-v2.interface.schemas :as command-schema]
   [ai.obney.grain.anomalies.interface :refer [anomaly?]]
   [com.brunobonacci.mulog :as u]
   [cognitect.anomalies :as anom]
   [malli.core :as mc]
   [malli.error :as me]))

(def ^:private expected-rejections
  #{::anom/incorrect ::anom/not-found ::anom/forbidden ::anom/conflict})

(defn- command-dimensions [command-name outcome include-command? & [category]]
  (cond-> {:outcome outcome}
    (and include-command? (keyword? command-name))
    (assoc :service (or (namespace command-name) "unqualified")
           :command (str command-name))
    category (assoc :anomaly-category (name category))))

(defn- command-outcome [result]
  (let [category (::anom/category result)]
    (cond
      (nil? category) "succeeded"
      (contains? expected-rejections category) "rejected"
      :else "anomaly")))

(defn- emit-command-counter! [metric-name command-name outcome include-command? category]
  (u/log :metric/metric
         :metric/name metric-name
         :metric/value 1
         :metric/resolution :low
         :metric/dimensions (command-dimensions command-name outcome include-command? category)))

(defn execute-command
  [handler {:keys [event-store tenant-id] :as context}]
  (let [result (try
                 (or (handler context)
                     {::anom/category ::anom/fault
                      ::anom/message (format "Command handler returned nil: %s"
                                             (get-in context [:command :command/name]))})
                 (catch Exception e
                   (u/log ::command-handler-exception
                          :error e
                          :command (get-in context [:command :command/name]))
                   (emit-command-counter! "CommandHandlerException"
                                          (get-in context [:command :command/name])
                                          "anomaly"
                                          true
                                          ::anom/fault)
                   {::anom/category ::anom/fault
                    ::anom/message (format "Error executing command handler: %s" (.getMessage e))}))]
    (when (anomaly? result)
      (u/log ::error-executing-command ::anomaly result))
    (if-let [events (:command-result/events result)]
      (if (:command-processor/skip-event-storage context)
        result
        (let [cas (:command-result/cas result)
              event-store-result (event-store/append event-store (cond-> {:tenant-id tenant-id :events events}
                                                                   cas (assoc :cas cas)))]
          (if-not (anomaly? event-store-result)
            result
            (do
              (u/log ::error-storing-events :anomaly event-store-result)
              (emit-command-counter! "CommandEventAppendFailed"
                                     (get-in context [:command :command/name])
                                     (command-outcome event-store-result)
                                     true
                                     (::anom/category event-store-result))
              (when (= ::anom/conflict (::anom/category event-store-result))
                (emit-command-counter! "CommandCasConflict"
                                       (get-in context [:command :command/name])
                                       "rejected"
                                       true
                                       ::anom/conflict))
              ;; Pass through the event-store anomaly — includes :error/explain
              ;; with Malli validation details when events fail schema checks.
              event-store-result))))
      result)))

(defn process-command [{:keys [command command-registry] :as context}]
  (u/trace
   ::process-command
   [::command command :metric/name "CommandProcessed" :metric/resolution :high]
   (let [started-at (System/nanoTime)
         command-name (:command/name command)
         handler (get-in command-registry [command-name :handler-fn])]
     (let [result (if handler
                    (if-let [_ (and (mc/validate command-name command)
                                    (mc/validate ::command-schema/command command))]
                      (execute-command handler context)
                      {::anom/category ::anom/incorrect
                       ::anom/message "Invalid Command: Failed Schema Validation"
                       :error/explain (me/humanize (or (mc/explain command-name command)
                                                       (mc/explain ::command-schema/command command)))})
                    {::anom/category ::anom/not-found
                     ::anom/message "Unknown Command"})
           category (::anom/category result)
           outcome (command-outcome result)
           dimensions (command-dimensions command-name outcome (some? handler))]
       (u/log :metric/metric
              :metric/name "CommandDuration"
              :mulog/duration (- (System/nanoTime) started-at)
              :metric/resolution :high
              :metric/dimensions dimensions)
       (emit-command-counter! (case outcome
                                "succeeded" "CommandSucceeded"
                                "rejected" "CommandRejected"
                                "CommandAnomaly")
                              command-name outcome (some? handler) category)
       result))))
