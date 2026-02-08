(ns ai.obney.grain.command-request-handler.core
  (:require [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.command-processor.interface.schemas :as command-schema]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.time.interface :as time]
            [clojure.core.async :as async]
            [com.brunobonacci.mulog :as u]
            [cognitect.anomalies :as anom]
            [malli.core :as mc]
            [malli.error :as me]
            [io.pedestal.http.body-params :as body-params]
            [cognitect.transit :as transit]))

(defn process-command-result-dispatch
  [result]
  (::anom/category result))

(defmulti process-command-result process-command-result-dispatch)

(defmethod process-command-result ::anom/incorrect
  [{:keys [::anom/message error/explain]}]
  {:status 400
   :body {:message message
          :explain explain}})

(defmethod process-command-result ::anom/not-found
  [{:keys [::anom/message]}]
  {:status 404
   :body {:message message}})

(defmethod process-command-result ::anom/forbidden
  [{:keys [::anom/message]}]
  {:status 403
   :body {:message message}})

(defmethod process-command-result ::anom/conflict
  [{:keys [::anom/message]}]
  {:status 409
   :body {:message message}})

(defmethod process-command-result :default
  [{:keys [::anom/message]}]
  {:status 500
   :body {:message message}})

(defmethod process-command-result nil
  [result]
  {:status 200
   :body (or (:command/result result) "OK")})

(defn decode-command
  [command]
  (-> command
      (assoc :command/id (random-uuid))
      (assoc :command/timestamp (time/now))))

(defn prep-response
  [response]
  (-> response
      (assoc-in [:headers "Content-Type"] "application/transit+json")
      (update :body (fn [data]
                      (let [out (java.io.ByteArrayOutputStream.)]
                        (transit/write (transit/writer out :json) data)
                        (.toString out))))))

(defn handle-command [grain-context {:keys [request] :as http-context}]
  (async/go
    (u/trace
     ::handle-command
     [::request request]
     (try
       (let [command (decode-command (get-in request [:transit-params :command]))]
         (if-let [error (me/humanize (mc/explain ::command-schema/command command))]
           (assoc http-context :response
                  (prep-response
                   (process-command-result
                    {::anom/category ::anom/incorrect
                     ::anom/message "Invalid Command"
                     :error/explain error})))
           (let [context (assoc (merge grain-context
                                       (:grain/additional-context http-context))
                                :command command)
                 command-registry (or (:command-registry context)
                                      @cp/command-registry*)
                 authorized? (get-in command-registry [(:command/name command) :authorized?])]
             (if (and authorized? (true? (authorized? context)))
               (let [result (async/<! (async/thread (cp/process-command context)))]
                 (when (anomaly? result)
                   (u/log ::anomaly ::anom/anomaly result))
                 (assoc http-context
                        :response (-> result process-command-result prep-response)
                        :grain/command command
                        :grain/command-result result))
               (assoc http-context :response
                      (prep-response
                       (process-command-result
                        {::anom/category ::anom/forbidden
                         ::anom/message "Unauthorized"})))))))
       (catch Exception e (u/log ::error :error e))))))

(defn interceptor
  [config]
  {:name ::command-request-handler
   :enter (partial #'handle-command config)})

(defn routes
  [{:keys [_event-store] :as config}]
  #{["/command" :post [(body-params/body-params) (interceptor config)] :route-name :command]})
