(ns ai.obney.grain.todo-processor.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.todo-processor.core :as core]
            [ai.obney.grain.todo-processor.interface :as tp]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))

;; Register test event schemas
(defschemas test-events
  {:test/event-processed [:map
                          [:event-id :uuid]
                          [:status :string]]
   :test/event-1 [:map [:num :int]]
   :test/event-2 [:map [:num :int]]
   :test/event-3 [:map [:num :int]]})

;; Test Fixtures

(def ^:dynamic *event-store* nil)

(defn event-store-fixture [f]
  (let [store (es/start {:conn {:type :in-memory}})]
    (binding [*event-store* store]
      (try
        (f)
        (finally
          (es/stop store))))))

(use-fixtures :each event-store-fixture)

;; Test Helpers

(defn make-event
  [event-type & {:keys [body tags]
                 :or {body {}
                      tags #{}}}]
  (es/->event {:type event-type
               :tags tags
               :body body}))

(defn make-context
  [event handler-fn]
  {:event event
   :handler-fn handler-fn
   :event-store *event-store*})

;; Sample Handler Functions

(defn successful-handler
  "Handler that processes successfully with no events to store"
  [_context]
  {})

(defn handler-with-events
  "Handler that returns events to be stored"
  [_context]
  (let [event-id (uuid/v4)]
    {:result/events
     [(es/->event {:type :test/event-processed
                   :tags #{[:test event-id]}
                   :body {:event-id event-id
                          :status "processed"}})]}))

(defn handler-with-multiple-events
  "Handler that returns multiple events"
  [_context]
  {:result/events
   [(es/->event {:type :test/event-1
                 :tags #{[:test (uuid/v4)]}
                 :body {:num 1}})
    (es/->event {:type :test/event-2
                 :tags #{[:test (uuid/v4)]}
                 :body {:num 2}})
    (es/->event {:type :test/event-3
                 :tags #{[:test (uuid/v4)]}
                 :body {:num 3}})]})

(defn handler-returning-nil
  "Handler that returns nil"
  [_context]
  nil)

(defn handler-returning-anomaly
  "Handler that returns an anomaly"
  [_context]
  {::anom/category ::anom/fault
   ::anom/message "Handler failed"})


(defn handler-throwing-exception
  "Handler that throws an uncaught exception"
  [_context]
  (throw (ex-info "Unexpected error in handler" {:error-type :database-connection})))

;; Tests

;; 1. Happy Path Tests

(deftest test-successful-processing
  (testing "Handler processes event successfully with no events to store"
    (let [event (make-event :test/trigger-event :body {:data "test"})
          context (make-context event successful-handler)
          result (core/process-event context)]
      ;; process-event returns nil for successful processing
      (is (nil? result))

      ;; Verify no events were stored (except :grain/tx)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (empty? events))))))

(deftest test-handler-with-events
  (testing "Handler returns events that get stored in event-store"
    (let [event (make-event :test/trigger-event :body {:data "test"})
          context (make-context event handler-with-events)
          result (core/process-event context)]
      ;; process-event returns nil on success
      (is (nil? result))

      ;; Verify event was stored (filter out :grain/tx)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))
        (is (= :test/event-processed (:event/type (first events))))
        (is (uuid? (:event-id (first events))))
        (is (= "processed" (:status (first events))))))))

(deftest test-handler-with-multiple-events
  (testing "Handler returns multiple events that all get stored"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-with-multiple-events)
          result (core/process-event context)]
      ;; process-event returns nil on success
      (is (nil? result))

      ;; Verify all events were stored (filter out :grain/tx)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 3 (count events)))
        (is (= #{:test/event-1 :test/event-2 :test/event-3}
               (set (map :event/type events))))))))

(deftest test-context-passed-to-handler
  (testing "Context is properly passed to handler with event, event-store, handler-fn"
    (let [event (make-event :test/trigger-event)
          received-context (atom nil)
          handler (fn [context]
                    (reset! received-context context)
                    {})
          context (make-context event handler)]
      (core/process-event context)
      ;; Verify context was passed correctly
      (is (not (nil? @received-context)))
      (is (contains? @received-context :event))
      (is (contains? @received-context :event-store))
      (is (contains? @received-context :handler-fn)))))

;; 2. Event Store Integration Tests

(deftest test-no-events-when-empty-result
  (testing "No events appended when handler returns empty map"
    (let [event (make-event :test/trigger-event)
          context (make-context event successful-handler)
          _ (core/process-event context)
          events (->> (es/read *event-store* {})
                      (into [])
                      (filter #(not= :grain/tx (:event/type %))))]
      (is (empty? events)))))

(deftest test-events-readable-after-append
  (testing "Events can be read back after being appended"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-with-events)]
      (core/process-event context)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))
        (is (uuid? (:event-id (first events))))
        (is (string? (:status (first events))))))))

(deftest test-multiple-todo-processor-invocations
  (testing "Multiple invocations append events independently"
    (let [event1 (make-event :test/trigger-1)
          event2 (make-event :test/trigger-2)
          context1 (make-context event1 handler-with-events)
          context2 (make-context event2 handler-with-events)]
      (core/process-event context1)
      (core/process-event context2)

      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 2 (count events)))
        (is (every? #(= :test/event-processed (:event/type %)) events))))))

;; 3. Handler Execution Tests

(deftest test-handler-returning-nil
  (testing "Handler returning nil produces fault anomaly but returns nil (logged)"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-returning-nil)
          result (core/process-event context)]
      ;; Anomaly is created and logged, but process-event returns nil
      (is (nil? result)))))

(deftest test-handler-returning-anomaly
  (testing "Handler returning anomaly is logged and returns nil"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-returning-anomaly)
          result (core/process-event context)]
      ;; Anomaly is logged, process-event returns nil
      (is (nil? result)))))

(deftest test-handler-throwing-exception
  (testing "Handler throwing exception is caught and returns nil (logged)"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-throwing-exception)
          result (core/process-event context)]
      ;; Exception is caught and logged, returns nil
      (is (nil? result)))))

(deftest test-context-contains-original-event
  (testing "Context contains the original triggering event"
    (let [test-body {:data "test-data" :count 42}
          event (make-event :test/trigger-event :body test-body)
          received-event (atom nil)
          handler (fn [context]
                    (reset! received-event (:event context))
                    {})
          context (make-context event handler)]
      (core/process-event context)
      (is (some? @received-event))
      (is (= :test/trigger-event (:event/type @received-event)))
      ;; Event body fields are flattened into the event map
      (is (= "test-data" (:data @received-event)))
      (is (= 42 (:count @received-event))))))

;; 4. Error Handling Tests

(deftest test-different-anomaly-categories
  (testing "Different anomaly categories from handler are logged (returns nil)"
    (doseq [category [::anom/fault
                      ::anom/forbidden
                      ::anom/incorrect
                      ::anom/not-found
                      ::anom/conflict]]
      (let [handler (fn [_context]
                      {::anom/category category
                       ::anom/message (str "Test " category)})
            event (make-event :test/trigger-event)
            context (make-context event handler)
            result (core/process-event context)]
        ;; Anomalies are logged but process-event returns nil
        (is (nil? result))))))

(deftest test-anomaly-context-preserved
  (testing "Anomalies from handler are logged (returns nil)"
    (let [handler (fn [_context]
                    {::anom/category ::anom/conflict
                     ::anom/message "Resource conflict"
                     :resource-id 123
                     :extra-info "Additional data"})
          event (make-event :test/trigger-event)
          context (make-context event handler)
          result (core/process-event context)]
      ;; Anomaly is logged, process-event returns nil
      (is (nil? result)))))

(deftest test-handler-with-invalid-events
  (testing "Handler returning invalid events causes event-store error"
    (let [handler (fn [_context]
                    {:result/events
                     [(es/->event {:type :test/invalid-event
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:missing-field "value"}})]})
          event (make-event :test/trigger-event)
          context (make-context event handler)
          result (core/process-event context)]
      ;; Event store will reject invalid events
      ;; Result should be a fault anomaly
      (is (= ::anom/fault (::anom/category result)))
      (is (= "Error storing events." (::anom/message result))))))

;; 5. Integration Tests - Backpressure and Concurrency

(deftest test-backpressure-with-slow-handler-and-many-events
  (testing "Todo-processor handles backpressure with 1000 events and slow handler (100ms each)"
    (let [processed-count (atom 0)
          processed-events (atom [])
          slow-handler (fn [{:keys [event]}]
                         ;; Simulate slow processing
                         (Thread/sleep 100)
                         (swap! processed-count inc)
                         (swap! processed-events conj (:event/type event))
                         {})
          ;; Create pubsub with :event/type as topic-fn
          ps (pubsub/start {:type :core-async
                           :topic-fn :event/type})
          ;; Start todo-processor with slow handler
          processor (tp/start {:event-pubsub ps
                              :topics [:test/backpressure-event]
                              :handler-fn slow-handler
                              :context {:event-store *event-store*}})]

      (try
        ;; Rapidly publish 1000 events to test backpressure handling
        ;; Note: >1024 events would exceed core.async's pending puts limit on the pubsub channel
        (let [num-events 1000]
          (dotimes [i num-events]
            (pubsub/pub ps {:message {:event/type :test/backpressure-event
                                     :event/id (uuid/v4)
                                     :event-number i}}))

          ;; Wait for all events to be processed
          ;; With 100ms per event and parallel processing, should complete in reasonable time
          ;; Use generous timeout to account for backpressure
          (let [timeout-ms 5000
                start-time (System/currentTimeMillis)]
            (loop []
              (when (< @processed-count num-events)
                (when (> (- (System/currentTimeMillis) start-time) timeout-ms)
                  (throw (ex-info "Timeout waiting for events to process"
                                 {:processed @processed-count
                                  :expected num-events
                                  :elapsed-ms (- (System/currentTimeMillis) start-time)})))
                (Thread/sleep 50)
                (recur))))

          ;; Verify all events were processed
          (is (= num-events @processed-count))
          (is (= num-events (count @processed-events)))

          ;; Verify no events were dropped
          (is (every? #(= :test/backpressure-event %) @processed-events)))

        (finally
          (tp/stop processor)
          (pubsub/stop ps))))))
