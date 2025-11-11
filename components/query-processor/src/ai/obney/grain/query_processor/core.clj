(ns ai.obney.grain.query-processor.core
  (:require
   [ai.obney.grain.query-schema.interface :as query-schema]
   [com.brunobonacci.mulog :as u]
   [cognitect.anomalies :as anom]
   [malli.core :as mc]
   [malli.error :as me]))

(defn process-query [{:keys [query query-registry] :as context}]
  (u/trace
   ::process-query
   [::query query]
   (let [query-name (:query/name query)
         handler (get-in query-registry [query-name :handler-fn])]
     (if handler
       (if-let [_ (and (mc/validate query-name query)
                       (mc/validate ::query-schema/query query))]
         (let [result (try
                        (handler context)
                        (catch Exception e
                          (u/log ::query-handler-exception
                                 :error e
                                 :query (get-in context [:query :query/name]))
                          {::anom/category ::anom/fault
                           ::anom/message (format "Error executing query handler: %s" (.getMessage e))}))]
           (if (nil? result)
             {::anom/category ::anom/fault
              ::anom/message (format "Query handler returned nil: %s"
                                     (get-in query [:query :query/name]))}
             result))
         {::anom/category ::anom/incorrect
          ::anom/message "Invalid Query: Failed Schema Validation"
          :error/explain (me/humanize (or (mc/explain query-name query)
                                          (mc/explain ::query-schema/query query)))})
       {::anom/category ::anom/not-found
        ::anom/message "Unknown Query"}))))

