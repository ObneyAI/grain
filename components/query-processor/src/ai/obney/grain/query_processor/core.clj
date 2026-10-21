(ns ai.obney.grain.query-processor.core
  (:require
   [ai.obney.grain.query-schema.interface :as query-schema]
   [com.brunobonacci.mulog :as u]
   [cognitect.anomalies :as anom]
   [malli.core :as mc]
   [malli.error :as me]))

(def ^:private expected-rejections
  #{::anom/incorrect ::anom/not-found ::anom/forbidden ::anom/conflict})

(defn- query-dimensions [query-name outcome include-query? & [category]]
  (cond-> {:outcome outcome}
    (and include-query? (keyword? query-name))
    (assoc :service (or (namespace query-name) "unqualified")
           :query (str query-name))
    category (assoc :anomaly-category (name category))))

(defn- query-outcome [result]
  (let [category (::anom/category result)]
    (cond
      (nil? category) "succeeded"
      (contains? expected-rejections category) "rejected"
      :else "anomaly")))

(defn- emit-query-counter! [metric-name query-name outcome include-query? category]
  (u/log :metric/metric
         :metric/name metric-name
         :metric/value 1
         :metric/resolution :low
         :metric/dimensions (query-dimensions query-name outcome include-query? category)))

(defn process-query [{:keys [query query-registry] :as context}]
  (u/trace
   ::process-query
   [::query query]
   (let [started-at (System/nanoTime)
         query-name (:query/name query)
         handler (get-in query-registry [query-name :handler-fn])]
     (let [result
           (if handler
             (if-let [_ (and (mc/validate query-name query)
                             (mc/validate ::query-schema/query query))]
               (let [handler-result
                     (try
                       (handler context)
                       (catch Exception e
                         (u/log ::query-handler-exception
                                :error e
                                :query (get-in context [:query :query/name]))
                         (emit-query-counter! "QueryHandlerException" query-name
                                              "anomaly" true ::anom/fault)
                         {::anom/category ::anom/fault
                          ::anom/message (format "Error executing query handler: %s" (.getMessage e))}))]
                 (if (nil? handler-result)
                   {::anom/category ::anom/fault
                    ::anom/message (format "Query handler returned nil: %s" query-name)}
                   handler-result))
               {::anom/category ::anom/incorrect
                ::anom/message "Invalid Query: Failed Schema Validation"
                :error/explain (me/humanize (or (mc/explain query-name query)
                                                (mc/explain ::query-schema/query query)))})
             {::anom/category ::anom/not-found
              ::anom/message "Unknown Query"})
           category (::anom/category result)
           outcome (query-outcome result)
           dimensions (query-dimensions query-name outcome (some? handler))]
       (u/log :metric/metric
              :metric/name "QueryDuration"
              :mulog/duration (- (System/nanoTime) started-at)
              :metric/resolution :high
              :metric/dimensions dimensions)
       (emit-query-counter! (case outcome
                              "succeeded" "QuerySucceeded"
                              "rejected" "QueryRejected"
                              "QueryAnomaly")
                            query-name outcome (some? handler) category)
       result))))
