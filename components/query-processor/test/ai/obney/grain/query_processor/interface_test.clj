(ns ai.obney.grain.query-processor.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid])
  (:import [java.time OffsetDateTime]))

;; Register test query schemas
(defschemas test-queries
  {:test/simple-query [:map]
   :test/query-with-id [:map [:resource-id :uuid]]
   :test/query-with-name [:map [:name :string]]
   :test/unknown-query [:map]
   :test/some-query [:map]
   :test/nil-handler [:map]
   :test/forbidden-handler [:map]
   :test/not-found-handler [:map]
   :test/context-handler [:map [:foo :string]]
   :test/empty-result [:map]
   :test/anomaly-handler [:map]
   :test/context-anomaly [:map]
   :test/throwing-handler [:map]})

;; Test Helpers

(defn make-query
  [query-name & {:keys [id timestamp extra-data]
                 :or {id (uuid/v4)
                      timestamp (OffsetDateTime/now)}}]
  (merge {:query/name query-name
          :query/id id
          :query/timestamp timestamp}
         extra-data))

(defn make-context
  [query query-registry]
  {:query query
   :query-registry query-registry})

(defn make-registry
  [query-handlers]
  (into {} (map (fn [[k v]] [k {:handler-fn v}]) query-handlers)))

;; Sample Query Handlers

(defn successful-handler
  [_context]
  {:query/result {:status "completed" :data "test-data"}})

(defn empty-result-handler
  [_context]
  {})

(defn handler-with-id
  [{{:keys [resource-id]} :query}]
  {:query/result {:resource-id resource-id :found true}})

(defn handler-returning-nil
  [_context]
  nil)

(defn handler-returning-not-found
  [_context]
  {::anom/category ::anom/not-found
   ::anom/message "Resource not found"})

(defn handler-returning-forbidden
  [_context]
  {::anom/category ::anom/forbidden
   ::anom/message "Access denied"})

(defn handler-receiving-context
  [context]
  {:query/result {:received-query (:query context)
                  :has-registry (contains? context :query-registry)}})

(defn handler-throwing-exception
  [_context]
  (throw (ex-info "Database query failed" {:error-type :database-connection})))

;; Tests

;; 1. Happy Path Tests

(deftest test-successful-query-processing
  (testing "Valid query with registered handler executes successfully"
    (let [query (make-query :test/simple-query)
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (not (contains? result ::anom/category)))
      (is (= {:status "completed" :data "test-data"} (:query/result result))))))

(deftest test-query-with-parameters
  (testing "Query with parameters are passed to handler"
    (let [test-id (uuid/v4)
          query (make-query :test/query-with-id :extra-data {:resource-id test-id})
          registry (make-registry {:test/query-with-id handler-with-id})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (not (contains? result ::anom/category)))
      (is (= test-id (get-in result [:query/result :resource-id])))
      (is (true? (get-in result [:query/result :found]))))))

(deftest test-query-with-empty-result
  (testing "Query returning empty map (no explicit result) is successful"
    (let [query (make-query :test/empty-result)
          registry (make-registry {:test/empty-result empty-result-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (not (contains? result ::anom/category)))
      (is (= {} result)))))

;; 2. Query Registry Tests

(deftest test-unregistered-query
  (testing "Unregistered query returns ::anom/not-found anomaly"
    (let [query (make-query :test/unknown-query)
          registry (make-registry {})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/not-found (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (= "Unknown Query" (::anom/message result))))))

(deftest test-nil-registry
  (testing "Query lookup with nil registry returns not-found"
    (let [query (make-query :test/some-query)
          registry nil
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/not-found (::anom/category result))))))

;; 3. Schema Validation Tests

(deftest test-query-missing-name
  (testing "Query missing :query/name returns ::anom/not-found (fails registry lookup)"
    (let [query {:query/id (uuid/v4)
                 :query/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/not-found (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-query-missing-id
  (testing "Query missing :query/id returns ::anom/incorrect"
    (let [query {:query/name :test/simple-query
                 :query/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-query-missing-timestamp
  (testing "Query missing :query/timestamp returns ::anom/incorrect"
    (let [query {:query/name :test/simple-query
                 :query/id (uuid/v4)}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-query-invalid-name-type
  (testing "Query with invalid :query/name type returns ::anom/not-found (fails registry lookup)"
    (let [query {:query/name "not-a-keyword"
                 :query/id (uuid/v4)
                 :query/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/not-found (::anom/category result))))))

(deftest test-query-invalid-id-type
  (testing "Query with invalid :query/id type returns ::anom/incorrect"
    (let [query {:query/name :test/simple-query
                 :query/id "not-a-uuid"
                 :query/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/incorrect (::anom/category result))))))

(deftest test-validation-error-message-is-human-readable
  (testing "Schema validation errors include human-readable explanations"
    (let [query {:query/name :test/simple-query}
          registry (make-registry {:test/simple-query successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (pos? (count (::anom/message result)))))))

(deftest test-query-specific-schema-validation
  (testing "Query-specific schema validation catches missing required fields"
    (let [query (make-query :test/query-with-name)
          ;; Missing required :name field
          registry (make-registry {:test/query-with-name successful-handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (contains? result :error/explain)))))

;; 4. Handler Execution Tests

(deftest test-handler-returning-nil
  (testing "Handler returning nil produces ::anom/fault"
    (let [query (make-query :test/nil-handler)
          registry (make-registry {:test/nil-handler handler-returning-nil})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/fault (::anom/category result)))
      (is (re-find #"returned nil" (::anom/message result))))))

(deftest test-handler-returning-not-found-anomaly
  (testing "Handler returning not-found anomaly passes it through"
    (let [query (make-query :test/not-found-handler)
          registry (make-registry {:test/not-found-handler handler-returning-not-found})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/not-found (::anom/category result)))
      (is (= "Resource not found" (::anom/message result))))))

(deftest test-handler-returning-forbidden-anomaly
  (testing "Handler returning forbidden anomaly passes it through"
    (let [query (make-query :test/forbidden-handler)
          registry (make-registry {:test/forbidden-handler handler-returning-forbidden})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/forbidden (::anom/category result)))
      (is (= "Access denied" (::anom/message result))))))

(deftest test-context-passed-to-handler
  (testing "Context is properly passed to handler"
    (let [query (make-query :test/context-handler :extra-data {:foo "bar"})
          registry (make-registry {:test/context-handler handler-receiving-context})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (not (contains? result ::anom/category)))
      (is (= query (get-in result [:query/result :received-query])))
      (is (true? (get-in result [:query/result :has-registry]))))))

(deftest test-handler-throwing-exception
  (testing "Handler throwing exception returns ::anom/fault anomaly"
    (let [query (make-query :test/throwing-handler)
          registry (make-registry {:test/throwing-handler handler-throwing-exception})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/fault (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (re-find #"Error executing query handler" (::anom/message result)))
      (is (re-find #"Database query failed" (::anom/message result))))))

;; 5. Error Handling Tests

(deftest test-different-anomaly-categories-preserved
  (testing "Different anomaly categories are handled properly"
    (doseq [category [::anom/forbidden
                      ::anom/incorrect
                      ::anom/not-found
                      ::anom/conflict
                      ::anom/fault]]
      (let [handler (fn [_context]
                      {::anom/category category
                       ::anom/message (str "Test " category)})
            query (make-query :test/anomaly-handler)
            registry (make-registry {:test/anomaly-handler handler})
            context (make-context query registry)
            result (qp/process-query context)]
        (is (= category (::anom/category result)))
        (is (= (str "Test " category) (::anom/message result)))))))

(deftest test-anomaly-context-preserved
  (testing "Error context is preserved in anomalies"
    (let [handler (fn [_context]
                    {::anom/category ::anom/conflict
                     ::anom/message "Resource already exists"
                     :resource-id 123
                     :extra-info "Additional context"})
          query (make-query :test/context-anomaly)
          registry (make-registry {:test/context-anomaly handler})
          context (make-context query registry)
          result (qp/process-query context)]
      (is (= ::anom/conflict (::anom/category result)))
      (is (= "Resource already exists" (::anom/message result)))
      (is (= 123 (:resource-id result)))
      (is (= "Additional context" (:extra-info result))))))
