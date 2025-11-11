(ns ai.obney.grain.command-request-handler.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.command-request-handler.interface :as crh]
            [ai.obney.grain.command-request-handler.core :as core]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.command-processor.interface :as cp]
            [cognitect.anomalies :as anom]
            [cognitect.transit :as transit]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.test :refer [response-for]]
            [clj-uuid :as uuid])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.time OffsetDateTime]))

;; Register test command schemas
(defschemas test-commands
  {:test/create-resource [:map [:name :string]]
   :test/return-result [:map]
   :test/with-events [:map]
   :test/forbidden [:map]
   :test/not-found [:map]
   :test/conflict [:map]
   :test/server-error [:map]
   :test/echo-context [:map]
   :test/throwing-handler [:map]})

;; Register test event schemas
(defschemas test-events
  {:test/resource-created [:map
                           [:resource-id :uuid]
                           [:name :string]]})

;; Test Fixtures

(def ^:dynamic *event-store* nil)
(def ^:dynamic *service* nil)

(defn event-store-fixture [f]
  (let [store (es/start {:conn {:type :in-memory}})]
    (binding [*event-store* store]
      (try
        (f)
        (finally
          (es/stop store))))))

;; Test Helpers

(defn transit-write
  "Serialize a value to Transit JSON string"
  [value]
  (let [out (ByteArrayOutputStream.)
        writer (transit/writer out :json)]
    (transit/write writer value)
    (.toString out "UTF-8")))

(defn transit-read
  "Deserialize Transit JSON string to value"
  [^String s]
  (let [in (ByteArrayInputStream. (.getBytes s "UTF-8"))
        reader (transit/reader in :json)]
    (transit/read reader)))

;; Sample Command Handlers

(defn create-resource-handler
  [_context]
  {:command/result {:resource-id (uuid/v4)
                    :status "created"}})

(defn return-result-handler
  [_context]
  {:command/result {:message "Success"}})

(defn handler-with-events
  [{{:keys [name]} :command :as _context}]
  (let [resource-id (uuid/v4)]
    {:command-result/events
     [(es/->event {:type :test/resource-created
                   :tags #{[:resource resource-id]}
                   :body {:resource-id resource-id
                          :name name}})]
     :command/result {:resource-id resource-id}}))

(defn forbidden-handler
  [_context]
  {::anom/category ::anom/forbidden
   ::anom/message "Access denied"})

(defn not-found-handler
  [_context]
  {::anom/category ::anom/not-found
   ::anom/message "Resource not found"})

(defn conflict-handler
  [_context]
  {::anom/category ::anom/conflict
   ::anom/message "Resource already exists"})

(defn server-error-handler
  [_context]
  {::anom/category ::anom/fault
   ::anom/message "Internal server error"})

(defn echo-context-handler
  "Returns the context keys to verify context merging"
  [context]
  {:command/result {:context-keys (keys context)
                    :user-id (:user-id context)
                    :request-id (:request-id context)}})

(defn throwing-handler
  "Throws an uncaught exception to test error handling"
  [_context]
  (throw (ex-info "Database connection failed" {:error-type :database-error})))

(def test-command-registry
  {:test/create-resource {:handler-fn create-resource-handler}
   :test/return-result {:handler-fn return-result-handler}
   :test/with-events {:handler-fn handler-with-events}
   :test/forbidden {:handler-fn forbidden-handler}
   :test/not-found {:handler-fn not-found-handler}
   :test/conflict {:handler-fn conflict-handler}
   :test/server-error {:handler-fn server-error-handler}
   :test/echo-context {:handler-fn echo-context-handler}
   :test/throwing-handler {:handler-fn throwing-handler}})

(defn service-fixture [f]
  (binding [*event-store* (es/start {:conn {:type :in-memory}})]
    (let [grain-context {:command-registry test-command-registry
                         :event-store *event-store*}
          service-map {::http/routes (crh/routes grain-context)
                       ::http/type :jetty
                       ::http/join? false}
          service (::http/service-fn (http/create-servlet service-map))]
      (binding [*service* service]
        (try
          (f)
          (finally
            (es/stop *event-store*)))))))

(use-fixtures :each service-fixture)

;; Unit Tests

;; 1. decode-command Tests

(deftest test-decode-command-adds-id
  (testing "decode-command adds :command/id as UUID"
    (let [command {:command/name :test/create-resource
                   :name "Test"}
          decoded (core/decode-command command)]
      (is (contains? decoded :command/id))
      (is (instance? java.util.UUID (:command/id decoded))))))

(deftest test-decode-command-adds-timestamp
  (testing "decode-command adds :command/timestamp as OffsetDateTime"
    (let [command {:command/name :test/create-resource
                   :name "Test"}
          decoded (core/decode-command command)]
      (is (contains? decoded :command/timestamp))
      (is (instance? OffsetDateTime (:command/timestamp decoded))))))

(deftest test-decode-command-preserves-fields
  (testing "decode-command preserves original command fields"
    (let [command {:command/name :test/create-resource
                   :name "Test Resource"
                   :extra-field "extra-value"}
          decoded (core/decode-command command)]
      (is (= :test/create-resource (:command/name decoded)))
      (is (= "Test Resource" (:name decoded)))
      (is (= "extra-value" (:extra-field decoded))))))

;; 2. process-command-result Tests

(deftest test-process-command-result-incorrect
  (testing "::anom/incorrect returns 400 with explain"
    (let [result {::anom/category ::anom/incorrect
                  ::anom/message "Invalid command"
                  :error/explain {:name ["should be a string"]}}
          response (core/process-command-result result)]
      (is (= 400 (:status response)))
      (is (= "Invalid command" (get-in response [:body :message])))
      (is (= {:name ["should be a string"]} (get-in response [:body :explain]))))))

(deftest test-process-command-result-not-found
  (testing "::anom/not-found returns 404"
    (let [result {::anom/category ::anom/not-found
                  ::anom/message "Resource not found"}
          response (core/process-command-result result)]
      (is (= 404 (:status response)))
      (is (= "Resource not found" (get-in response [:body :message]))))))

(deftest test-process-command-result-forbidden
  (testing "::anom/forbidden returns 403"
    (let [result {::anom/category ::anom/forbidden
                  ::anom/message "Access denied"}
          response (core/process-command-result result)]
      (is (= 403 (:status response)))
      (is (= "Access denied" (get-in response [:body :message]))))))

(deftest test-process-command-result-conflict
  (testing "::anom/conflict returns 409"
    (let [result {::anom/category ::anom/conflict
                  ::anom/message "Resource already exists"}
          response (core/process-command-result result)]
      (is (= 409 (:status response)))
      (is (= "Resource already exists" (get-in response [:body :message]))))))

(deftest test-process-command-result-default-anomaly
  (testing "Other anomalies return 500"
    (let [result {::anom/category ::anom/fault
                  ::anom/message "Internal error"}
          response (core/process-command-result result)]
      (is (= 500 (:status response)))
      (is (= "Internal error" (get-in response [:body :message]))))))

(deftest test-process-command-result-success-with-result
  (testing "Success with :command/result returns 200"
    (let [result {:command/result {:status "completed" :id 123}}
          response (core/process-command-result result)]
      (is (= 200 (:status response)))
      (is (= {:status "completed" :id 123} (:body response))))))

(deftest test-process-command-result-success-without-result
  (testing "Success without :command/result returns 200 with OK"
    (let [result {:some-other-key "value"}
          response (core/process-command-result result)]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))

;; 3. prep-response Tests

(deftest test-prep-response-sets-content-type
  (testing "prep-response sets Content-Type to application/transit+json"
    (let [response {:status 200 :body {:message "test"}}
          prepped (core/prep-response response)]
      (is (= "application/transit+json" (get-in prepped [:headers "Content-Type"]))))))

(deftest test-prep-response-serializes-body
  (testing "prep-response serializes body to Transit JSON"
    (let [response {:status 200 :body {:message "test" :id 123}}
          prepped (core/prep-response response)
          deserialized (transit-read (:body prepped))]
      (is (string? (:body prepped)))
      (is (= "test" (:message deserialized)))
      (is (= 123 (:id deserialized))))))

;; End-to-End Tests

(deftest test-e2e-valid-command-returns-200
  (testing "Valid command returns 200 with result"
    (let [command {:command/name :test/create-resource
                   :name "Test Resource"}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (contains? body :resource-id))
      (is (= "created" (:status body))))))

(deftest test-e2e-invalid-command-schema-returns-400
  (testing "Invalid command schema returns 400 with explanation"
    (let [command {:command/name :test/create-resource
                   ;; Missing required :name field
                   }
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 400 (:status response)))
      (is (string? (:message body)))
      (is (contains? body :explain)))))

(deftest test-e2e-missing-command-name-returns-400
  (testing "Command missing :command/name returns 400"
    (let [command {:name "Test"
                   ;; Missing :command/name
                   }
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))]
      (is (= 400 (:status response))))))

(deftest test-e2e-forbidden-returns-403
  (testing "Command handler returning ::anom/forbidden returns 403"
    (let [command {:command/name :test/forbidden}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 403 (:status response)))
      (is (= "Access denied" (:message body))))))

(deftest test-e2e-not-found-returns-404
  (testing "Command handler returning ::anom/not-found returns 404"
    (let [command {:command/name :test/not-found}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 404 (:status response)))
      (is (= "Resource not found" (:message body))))))

(deftest test-e2e-conflict-returns-409
  (testing "Command handler returning ::anom/conflict returns 409"
    (let [command {:command/name :test/conflict}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 409 (:status response)))
      (is (= "Resource already exists" (:message body))))))

(deftest test-e2e-server-error-returns-500
  (testing "Command handler returning ::anom/fault returns 500"
    (let [command {:command/name :test/server-error}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 500 (:status response)))
      (is (= "Internal server error" (:message body))))))

(deftest test-e2e-events-persisted
  (testing "Commands with events persist to event store"
    (let [command {:command/name :test/with-events
                   :name "Test Resource"}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))
          events (->> (es/read *event-store* {})
                     (into [])
                     (filter #(not= :grain/tx (:event/type %))))]
      (is (= 200 (:status response)))
      (is (contains? body :resource-id))
      (is (= 1 (count events)))
      (is (= :test/resource-created (:event/type (first events)))))))

(deftest test-e2e-transit-content-type-header
  (testing "Response has application/transit+json Content-Type"
    (let [command {:command/name :test/return-result}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))]
      (is (= "application/transit+json" (get-in response [:headers "Content-Type"]))))))

;; Context Merging Tests

(deftest test-context-includes-grain-context
  (testing "Command handler receives grain-context (command-registry, event-store, command)"
    (let [command {:command/name :test/echo-context}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))
          context-keys (set (:context-keys body))]
      (is (= 200 (:status response)))
      (is (contains? context-keys :command-registry))
      (is (contains? context-keys :event-store))
      (is (contains? context-keys :command)))))

(deftest test-additional-context-merging
  (testing "Command handler receives merged grain/additional-context from interceptors"
    (let [;; Create an interceptor that adds additional context
          additional-context-interceptor
          {:name ::add-additional-context
           :enter (fn [context]
                    (assoc context :grain/additional-context
                           {:user-id "test-user-123"
                            :request-id "req-456"}))}

          ;; Create a service with the additional context interceptor
          grain-context {:command-registry test-command-registry
                        :event-store *event-store*}
          service-map {::http/routes
                      #{["/command" :post
                         [(body-params/body-params)
                          additional-context-interceptor
                          (core/interceptor grain-context)]
                         :route-name :command]}
                      ::http/type :jetty
                      ::http/join? false}
          test-service (::http/service-fn (http/create-servlet service-map))

          command {:command/name :test/echo-context}
          response (response-for test-service :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (= "test-user-123" (:user-id body))
          "Additional context :user-id should be accessible to handler")
      (is (= "req-456" (:request-id body))
          "Additional context :request-id should be accessible to handler"))))

;; Exception Handling Tests

(deftest test-e2e-handler-throwing-exception
  (testing "Command handler throwing exception returns 500 with error message"
    (let [command {:command/name :test/throwing-handler}
          response (response-for *service* :post "/command"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:command command}))
          body (transit-read (:body response))]
      (is (= 500 (:status response)))
      (is (string? (:message body)))
      (is (re-find #"Error executing command" (:message body)))
      (is (re-find #"Database connection failed" (:message body))))))
