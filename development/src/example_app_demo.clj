(ns example-app-demo
  (:require [ai.obney.grain.example-base.core :as service]
            [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.example-service.interface.read-models :as rm]
            [ai.obney.grain.time.interface :as time]
            [clj-http.client :as http]))

(comment

  ;;
  ;; Start Service
  ;;
  ;; The service context already carries :event-store, :cache, and
  ;; :tenant-id — everything the macro handlers / read models need.
  (do
    (def service (service/start))
    (def context (::service/context service))
    (def event-store (:event-store context))
    (def tenant-id (:tenant-id context)))


  ;;
  ;; Stop Service ;;
  ;;
  (service/stop service)

  ""
  )


(comment

  ;; Interact internally in the REPL without HTTP.
  ;; cp/process-command and qp/process-query fall back to the global
  ;; registries populated by the defcommand / defquery macros.

  (try
    (cp/process-command
     (assoc context
            :command (cp/->command {:command/name :example/create-counter
                                    :name "Counter A"})))
    (catch Exception e (ex-data e)))

  (into [] (es/read event-store {:tenant-id tenant-id}))

  (def counters
    (->> (qp/process-query
          (assoc context
                 :query {:query/name :example/counters
                         :query/timestamp (time/now)
                         :query/id (random-uuid)}))
         :query/result))


  (def counter
    (->> (qp/process-query
          (assoc context
                 :query {:query/name :example/counter
                         :query/timestamp (time/now)
                         :query/id (random-uuid)
                         :counter-id (:counter/id (first counters))}))
         :query/result))



  (cp/process-command
   (assoc context
          :command (cp/->command {:command/name :example/increment-counter
                                  :counter-id (:counter/id counter)})))


  ;; Projects the :example/counters read model (read-model-processor-v2).
  (rm/root context)

  ;; The :example/calculate-average-counter-value processor reacts to the
  ;; increment above and appends an :example/average-calculated event.
  (into [] (es/read event-store {:tenant-id tenant-id}))


  ""
  )

(comment
  ;; Interact with the service via HTTP

  ;; Create a counter
  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :transit+json
       :as :transit+json
       :form-params {:command {:command/name :example/create-counter
                               :name "Counter C"}}}))
    (catch Exception e (ex-data e)))

  ;; Get all counters
  (def counters
    (try
      (:body
       (http/post
        "http://localhost:8080/query"
        {:content-type :transit+json
         :as :transit+json
         :form-params {:query {:query/name :example/counters}}}))
      (catch Exception e (ex-data e))))



  ;; Increment first counter

  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :transit+json
       :as :transit+json
       :form-params {:command {:command/name :example/increment-counter
                               :counter-id (-> counters first :counter/id)}}}))
    (catch Exception e (ex-data e)))

  ;; Decrement a counter by ID

  (try
    (:body
     (http/post
      "http://localhost:8080/command"
      {:content-type :transit+json
       :as :transit+json
       :form-params {:command {:command/name :example/decrement-counter
                               :counter-id (-> counters first :counter/id)}}}))
    (catch Exception e (ex-data e)))







  ""
  )
