(ns ai.obney.grain.command-processor-v2.core
  (:require
   [ai.obney.grain.event-store-v3.interface :as event-store]
   [ai.obney.grain.command-processor-v2.interface.schemas :as command-schema]
   [ai.obney.grain.anomalies.interface :refer [anomaly?]]
   [com.brunobonacci.mulog :as u]
   [cognitect.anomalies :as anom]
   [malli.core :as mc]
   [malli.error :as me]))

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
              ;; Pass through the event-store anomaly — includes :error/explain
              ;; with Malli validation details when events fail schema checks.
              event-store-result))))
      result)))

(defn process-command [{:keys [command command-registry] :as context}]
  (u/trace
   ::process-command
   [::command command :metric/name "CommandProcessed" :metric/resolution :high]
   (let [command-name (:command/name command)
         handler (get-in command-registry [command-name :handler-fn])]
     (if handler
       (if-let [_ (and (mc/validate command-name command)
                       (mc/validate ::command-schema/command command))]
         (execute-command handler context)
         {::anom/category ::anom/incorrect
          ::anom/message "Invalid Command: Failed Schema Validation"
          :error/explain (me/humanize (or (mc/explain command-name command)
                                          (mc/explain ::command-schema/command command)))})
       {::anom/category ::anom/not-found
        ::anom/message "Unknown Command"}))))
