(ns ai.obney.grain.query-request-handler.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.query-request-handler.interface :as qrh]
            [ai.obney.grain.query-request-handler.core :as core]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.query-processor.interface :as qp]
            [cognitect.anomalies :as anom]
            [cognitect.transit :as transit]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.test :refer [response-for]]
            [clj-uuid :as uuid])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.time OffsetDateTime]))

;; Register test query schemas
(defschemas test-queries
  {:test/simple-query [:map]
   :test/query-with-param [:map [:param :string]]
   :test/return-result [:map]
   :test/forbidden [:map]
   :test/not-found [:map]
   :test/conflict [:map]
   :test/server-error [:map]
   :test/echo-context [:map]
   :test/throwing-handler [:map]})

;; Test Fixtures

(def ^:dynamic *service* nil)

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

;; Sample Query Handlers

(defn simple-query-handler
  [_context]
  {:query/result {:status "completed" :data "test-data"}})

(defn query-with-param-handler
  [{{:keys [param]} :query}]
  {:query/result {:param param :found true}})

(defn return-result-handler
  [_context]
  {:query/result {:message "Success"}})

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
  "Returns the context keys to verify context passing"
  [context]
  {:query/result {:context-keys (keys context)
                  :has-query-registry (contains? context :query-registry)
                  :has-query (contains? context :query)
                  :user-id (:user-id context)
                  :request-id (:request-id context)}})

(defn throwing-handler
  "Throws an uncaught exception to test error handling"
  [_context]
  (throw (ex-info "Database query failed" {:error-type :database-error})))

(def test-query-registry
  {:test/simple-query {:handler-fn simple-query-handler}
   :test/query-with-param {:handler-fn query-with-param-handler}
   :test/return-result {:handler-fn return-result-handler}
   :test/forbidden {:handler-fn forbidden-handler}
   :test/not-found {:handler-fn not-found-handler}
   :test/conflict {:handler-fn conflict-handler}
   :test/server-error {:handler-fn server-error-handler}
   :test/echo-context {:handler-fn echo-context-handler}
   :test/throwing-handler {:handler-fn throwing-handler}})

(defn service-fixture [f]
  (let [config {:query-registry test-query-registry}
        service-map {::http/routes (qrh/routes config)
                     ::http/type :jetty
                     ::http/join? false}
        service (::http/service-fn (http/create-servlet service-map))]
    (binding [*service* service]
      (f))))

(use-fixtures :each service-fixture)

;; Unit Tests

;; 1. decode-query Tests

(deftest test-decode-query-adds-id
  (testing "decode-query adds :query/id as UUID"
    (let [query {:query/name :test/simple-query
                 :param "test"}
          decoded (core/decode-query query)]
      (is (contains? decoded :query/id))
      (is (instance? java.util.UUID (:query/id decoded))))))

(deftest test-decode-query-adds-timestamp
  (testing "decode-query adds :query/timestamp as OffsetDateTime"
    (let [query {:query/name :test/simple-query
                 :param "test"}
          decoded (core/decode-query query)]
      (is (contains? decoded :query/timestamp))
      (is (instance? OffsetDateTime (:query/timestamp decoded))))))

(deftest test-decode-query-preserves-fields
  (testing "decode-query preserves original query fields"
    (let [query {:query/name :test/simple-query
                 :param "test-param"
                 :extra-field "extra-value"}
          decoded (core/decode-query query)]
      (is (= :test/simple-query (:query/name decoded)))
      (is (= "test-param" (:param decoded)))
      (is (= "extra-value" (:extra-field decoded))))))

;; 2. process-query-result Tests

(deftest test-process-query-result-incorrect
  (testing "::anom/incorrect returns 400 with explain"
    (let [result {::anom/category ::anom/incorrect
                  ::anom/message "Invalid query"
                  :error/explain {:param ["should be a string"]}}
          response (core/process-query-result result)]
      (is (= 400 (:status response)))
      (is (= "Invalid query" (get-in response [:body :message])))
      (is (= {:param ["should be a string"]} (get-in response [:body :explain]))))))

(deftest test-process-query-result-not-found
  (testing "::anom/not-found returns 404"
    (let [result {::anom/category ::anom/not-found
                  ::anom/message "Resource not found"}
          response (core/process-query-result result)]
      (is (= 404 (:status response)))
      (is (= "Resource not found" (get-in response [:body :message]))))))

(deftest test-process-query-result-forbidden
  (testing "::anom/forbidden returns 403"
    (let [result {::anom/category ::anom/forbidden
                  ::anom/message "Access denied"}
          response (core/process-query-result result)]
      (is (= 403 (:status response)))
      (is (= "Access denied" (get-in response [:body :message]))))))

(deftest test-process-query-result-conflict
  (testing "::anom/conflict returns 409"
    (let [result {::anom/category ::anom/conflict
                  ::anom/message "Resource already exists"}
          response (core/process-query-result result)]
      (is (= 409 (:status response)))
      (is (= "Resource already exists" (get-in response [:body :message]))))))

(deftest test-process-query-result-default-anomaly
  (testing "Other anomalies return 500"
    (let [result {::anom/category ::anom/fault
                  ::anom/message "Internal error"}
          response (core/process-query-result result)]
      (is (= 500 (:status response)))
      (is (= "Internal error" (get-in response [:body :message]))))))

(deftest test-process-query-result-success-with-result
  (testing "Success with :query/result returns 200"
    (let [result {:query/result {:status "completed" :id 123}}
          response (core/process-query-result result)]
      (is (= 200 (:status response)))
      (is (= {:status "completed" :id 123} (:body response))))))

(deftest test-process-query-result-success-without-result
  (testing "Success without :query/result returns 200 with OK"
    (let [result {:some-other-key "value"}
          response (core/process-query-result result)]
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

(deftest test-e2e-valid-query-returns-200
  (testing "Valid query returns 200 with result"
    (let [query {:query/name :test/simple-query}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (= "completed" (:status body)))
      (is (= "test-data" (:data body))))))

(deftest test-e2e-query-with-parameters
  (testing "Query with parameters passes them to handler"
    (let [query {:query/name :test/query-with-param
                 :param "test-value"}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (= "test-value" (:param body)))
      (is (true? (:found body))))))

(deftest test-e2e-invalid-query-schema-returns-400
  (testing "Invalid query schema returns 400 with explanation"
    (let [query {:query/name :test/query-with-param
                 ;; Missing required :param field
                 }
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 400 (:status response)))
      (is (string? (:message body)))
      (is (contains? body :explain)))))

(deftest test-e2e-missing-query-name-returns-400
  (testing "Query missing :query/name returns 400"
    (let [query {:param "test"
                 ;; Missing :query/name
                 }
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))]
      (is (= 400 (:status response))))))

(deftest test-e2e-forbidden-returns-403
  (testing "Query handler returning ::anom/forbidden returns 403"
    (let [query {:query/name :test/forbidden}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 403 (:status response)))
      (is (= "Access denied" (:message body))))))

(deftest test-e2e-not-found-returns-404
  (testing "Query handler returning ::anom/not-found returns 404"
    (let [query {:query/name :test/not-found}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 404 (:status response)))
      (is (= "Resource not found" (:message body))))))

(deftest test-e2e-conflict-returns-409
  (testing "Query handler returning ::anom/conflict returns 409"
    (let [query {:query/name :test/conflict}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 409 (:status response)))
      (is (= "Resource already exists" (:message body))))))

(deftest test-e2e-server-error-returns-500
  (testing "Query handler returning ::anom/fault returns 500"
    (let [query {:query/name :test/server-error}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 500 (:status response)))
      (is (= "Internal server error" (:message body))))))

(deftest test-e2e-transit-content-type-header
  (testing "Response has application/transit+json Content-Type"
    (let [query {:query/name :test/return-result}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))]
      (is (= "application/transit+json" (get-in response [:headers "Content-Type"]))))))

;; Context Tests

(deftest test-context-includes-query-registry-and-query
  (testing "Query handler receives context with query-registry and query"
    (let [query {:query/name :test/echo-context}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (true? (:has-query-registry body)))
      (is (true? (:has-query body)))
      (is (contains? (set (:context-keys body)) :query-registry))
      (is (contains? (set (:context-keys body)) :query)))))

(deftest test-additional-context-merging
  (testing "Query handler receives merged grain/additional-context from interceptors"
    (let [;; Create an interceptor that adds additional context
          additional-context-interceptor
          {:name ::add-additional-context
           :enter (fn [context]
                    (assoc context :grain/additional-context
                           {:user-id "test-user-789"
                            :request-id "req-abc"}))}

          ;; Create a service with the additional context interceptor
          config {:query-registry test-query-registry}
          service-map {::http/routes
                      #{["/query" :post
                         [(body-params/body-params)
                          additional-context-interceptor
                          (core/interceptor config)]
                         :route-name :query]}
                      ::http/type :jetty
                      ::http/join? false}
          test-service (::http/service-fn (http/create-servlet service-map))

          query {:query/name :test/echo-context}
          response (response-for test-service :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 200 (:status response)))
      (is (= "test-user-789" (:user-id body))
          "Additional context :user-id should be accessible to handler")
      (is (= "req-abc" (:request-id body))
          "Additional context :request-id should be accessible to handler"))))

;; Exception Handling Tests

(deftest test-e2e-handler-throwing-exception
  (testing "Query handler throwing exception returns 500 with error message"
    (let [query {:query/name :test/throwing-handler}
          response (response-for *service* :post "/query"
                                :headers {"Content-Type" "application/transit+json"}
                                :body (transit-write {:query query}))
          body (transit-read (:body response))]
      (is (= 500 (:status response)))
      (is (string? (:message body)))
      (is (re-find #"Error executing query handler" (:message body)))
      (is (re-find #"Database query failed" (:message body))))))
