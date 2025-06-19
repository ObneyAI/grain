(ns ai.obney.grain.query-request-handler.core
  (:require [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.query-schema.interface :as query-schema]
            [ai.obney.grain.time.interface :as time]
            [clojure.core.async :as async]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]
            [malli.core :as mc]
            [malli.transform :as mt]
            [malli.error :as me]
            [io.pedestal.http.body-params :as body-params]
            [clojure.data.json :as json]
            [ai.obney.grain.query-processor.interface :as qp]))

(defn process-query-result-dispatch
  [result]
  (::anom/category result))

(defmulti process-query-result process-query-result-dispatch)

(defmethod process-query-result ::anom/incorrect
  [{:keys [::anom/message error/explain]}]
  {:status 400
   :body {:message message
          :explain explain}})

(defmethod process-query-result ::anom/not-found
  [{:keys [::anom/message]}]
  {:status 404
   :body {:message message}})

(defmethod process-query-result ::anom/forbidden
  [{:keys [::anom/message]}]
  {:status 409
   :body {:message message}})

(defmethod process-query-result ::anom/conflict
  [{:keys [::anom/message]}]
  {:status 403
   :body {:message message}})

(defmethod process-query-result :default
  [{:keys [::anom/message]}]
  {:status 500
   :body {:message message}})

(defmethod process-query-result nil
  [result]
  {:status 200
   :body (or (:query/result result) "OK")})

(def json-transformer
  (mt/transformer
   mt/json-transformer))

(defn decode-query
  [query]
  (as-> query x
    (clojure.walk/keywordize-keys x)
    (assoc x :query/id (random-uuid))
    (assoc x :query/timestamp (time/now))
    (mc/decode ::query-schema/query x json-transformer)
    (mc/decode (:query/name x) x json-transformer)))

(defn prep-response
  [response]
  (-> response
      (assoc-in [:headers "Content-Type"] "application/json")
      (update :body json/write-str)))

(defn handle-query [config {:keys [request] :as context}]
  (async/go
    (u/trace
     ::handle-query
     [::request request]
     (try
       (let [query (decode-query (get-in request [:json-params :query]))]
         (if-let [error (me/humanize (mc/explain ::query-schema/query query))]
           (assoc context :response
                  (prep-response
                   (process-query-result
                    {::anom/category ::anom/incorrect
                     ::anom/message "Invalid Query"
                     :error/explain error})))
           (let [result (async/<! (async/thread (qp/process-query (assoc config :query query))))]
             (when (anomaly? result)
               (u/log ::anomaly ::anom/anomaly result))
             (assoc context :response (prep-response (process-query-result result))))))
       (catch Exception e (u/log ::error :error e))))))

(defn interceptor
  [{:keys [_query-chan _return-chan] :as config}]
  {:name ::query-request-handler
   :enter (partial #'handle-query config)})

(defn routes
  [config]
  #{["/query" :post [(body-params/body-params) (interceptor config)] :route-name :query]})
