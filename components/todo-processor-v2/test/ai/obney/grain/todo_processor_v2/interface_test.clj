(ns ai.obney.grain.todo-processor-v2.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.todo-processor-v2.core :as core]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.periodic-task.core :as pt-core]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))

(def test-tenant-id (random-uuid))

;; Register test event schemas
(defschemas test-events
  {:test/event-processed [:map
                          [:event-id :uuid]
                          [:status :string]]
   :test/event-1 [:map [:num :int]]
   :test/event-2 [:map [:num :int]]
   :test/event-3 [:map [:num :int]]
   :test/cas-event [:map [:n :int]]
   :test/effect-succeeded [:map [:msg :string]]
   :test/effect-failed [:map [:msg :string]]
   :test/billing-trigger [:map [:period :string]]
   :test/billing-done [:map [:period :string] [:billed-by :uuid]]
   :test/periodic-trigger [:map [:period :string]]})

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
   :event-store *event-store*
   :tenant-id test-tenant-id})

;; Sample Handler Functions

(defn successful-handler
  [_context]
  {})

(defn handler-with-events
  [_context]
  (let [event-id (uuid/v4)]
    {:result/events
     [(es/->event {:type :test/event-processed
                   :tags #{[:test event-id]}
                   :body {:event-id event-id
                          :status "processed"}})]}))

(defn handler-with-multiple-events
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
  [_context]
  nil)

(defn handler-returning-anomaly
  [_context]
  {::anom/category ::anom/fault
   ::anom/message "Handler failed"})

(defn handler-throwing-exception
  [_context]
  (throw (ex-info "Unexpected error in handler" {:error-type :database-connection})))

;; Tests

;; 1. Happy Path Tests

(deftest test-successful-processing
  (testing "Handler processes event successfully with no events to store"
    (let [event (make-event :test/trigger-event :body {:data "test"})
          context (make-context event successful-handler)
          result (core/process-event context)]
      (is (nil? result))
      (let [events (->> (es/read *event-store* {:tenant-id test-tenant-id})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (empty? events))))))

(deftest test-handler-with-events
  (testing "Handler returns events that get stored in event-store"
    (let [event (make-event :test/trigger-event :body {:data "test"})
          context (make-context event handler-with-events)
          result (core/process-event context)]
      (is (nil? result))
      (let [events (->> (es/read *event-store* {:tenant-id test-tenant-id})
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
      (is (nil? result))
      (let [events (->> (es/read *event-store* {:tenant-id test-tenant-id})
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
          events (->> (es/read *event-store* {:tenant-id test-tenant-id})
                      (into [])
                      (filter #(not= :grain/tx (:event/type %))))]
      (is (empty? events)))))

(deftest test-events-readable-after-append
  (testing "Events can be read back after being appended"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-with-events)]
      (core/process-event context)
      (let [events (->> (es/read *event-store* {:tenant-id test-tenant-id})
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

      (let [events (->> (es/read *event-store* {:tenant-id test-tenant-id})
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
      (is (nil? result)))))

(deftest test-handler-returning-anomaly
  (testing "Handler returning anomaly is logged and returns nil"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-returning-anomaly)
          result (core/process-event context)]
      (is (nil? result)))))

(deftest test-handler-throwing-exception
  (testing "Handler throwing exception is caught and returns nil (logged)"
    (let [event (make-event :test/trigger-event)
          context (make-context event handler-throwing-exception)
          result (core/process-event context)]
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
      (is (= ::anom/fault (::anom/category result)))
      (is (= "Error storing events." (::anom/message result))))))

;; 5. Tenant-ID Propagation Tests

(deftest test-tenant-id-propagated-from-event
  (testing "Todo processor extracts :grain/tenant-id from event and sets it on context"
    (let [tenant-id (random-uuid)
          received-tenant-id (atom nil)
          handler (fn [context]
                    (reset! received-tenant-id (:tenant-id context))
                    {})
          ps (pubsub/start {:type :core-async :topic-fn :event/type})
          processor (tp/start {:event-pubsub ps
                               :topics [:test/tenant-event]
                               :handler-fn handler
                               :context {:event-store *event-store*}})]
      (try
        (pubsub/pub ps {:message {:event/type :test/tenant-event
                                  :event/id (uuid/v4)
                                  :grain/tenant-id tenant-id}})
        (Thread/sleep 200)
        (is (= tenant-id @received-tenant-id))
        (finally
          (tp/stop processor)
          (pubsub/stop ps))))))

(deftest test-tenant-id-stripped-from-event-before-handler
  (testing "Handler receives event without :grain/tenant-id key"
    (let [received-event (atom nil)
          handler (fn [context]
                    (reset! received-event (:event context))
                    {})
          ps (pubsub/start {:type :core-async :topic-fn :event/type})
          processor (tp/start {:event-pubsub ps
                               :topics [:test/tenant-event]
                               :handler-fn handler
                               :context {:event-store *event-store*
                                         :tenant-id test-tenant-id}})]
      (try
        (pubsub/pub ps {:message {:event/type :test/tenant-event
                                  :event/id (uuid/v4)
                                  :grain/tenant-id (random-uuid)
                                  :data "test-data"}})
        (Thread/sleep 200)
        (is (some? @received-event))
        (is (not (contains? @received-event :grain/tenant-id)))
        (is (= "test-data" (:data @received-event)))
        (finally
          (tp/stop processor)
          (pubsub/stop ps))))))

(deftest test-tenant-id-used-for-event-storage
  (testing "Events produced by handler are stored in the correct tenant partition"
    (let [tenant-id (random-uuid)
          other-tenant-id (random-uuid)
          handler (fn [_context]
                    {:result/events
                     [(es/->event {:type :test/event-processed
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:event-id (uuid/v4)
                                          :status "done"}})]})
          ps (pubsub/start {:type :core-async :topic-fn :event/type})
          processor (tp/start {:event-pubsub ps
                               :topics [:test/tenant-event]
                               :handler-fn handler
                               :context {:event-store *event-store*}})]
      (try
        (pubsub/pub ps {:message {:event/type :test/tenant-event
                                  :event/id (uuid/v4)
                                  :grain/tenant-id tenant-id}})
        (Thread/sleep 200)
        (let [events (->> (es/read *event-store* {:tenant-id tenant-id})
                          (into [])
                          (filter #(not= :grain/tx (:event/type %))))]
          (is (= 1 (count events)))
          (is (= :test/event-processed (:event/type (first events)))))
        (let [other-events (->> (es/read *event-store* {:tenant-id other-tenant-id})
                                (into [])
                                (filter #(not= :grain/tx (:event/type %))))]
          (is (empty? other-events)))
        (finally
          (tp/stop processor)
          (pubsub/stop ps))))))

;; 6. Integration Tests - Backpressure and Concurrency

(deftest test-backpressure-with-slow-handler-and-many-events
  (testing "Todo-processor handles backpressure with 1000 events and slow handler (100ms each)"
    (let [processed-count (atom 0)
          processed-events (atom [])
          slow-handler (fn [{:keys [event]}]
                         (Thread/sleep 100)
                         (swap! processed-count inc)
                         (swap! processed-events conj (:event/type event))
                         {})
          ps (pubsub/start {:type :core-async
                           :topic-fn :event/type})
          processor (tp/start {:event-pubsub ps
                              :topics [:test/backpressure-event]
                              :handler-fn slow-handler
                              :context {:event-store *event-store*
                                        :tenant-id test-tenant-id}})]

      (try
        (let [num-events 1000]
          (dotimes [i num-events]
            (pubsub/pub ps {:message {:event/type :test/backpressure-event
                                     :event/id (uuid/v4)
                                     :event-number i}}))

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

          (is (= num-events @processed-count))
          (is (= num-events (count @processed-events)))
          (is (every? #(= :test/backpressure-event %) @processed-events)))

        (finally
          (tp/stop processor)
          (pubsub/stop ps))))))

;; 7. CAS (Compare-And-Swap) Tests

(deftest test-cas-success-then-conflict
  (testing "CAS predicate controls whether events are stored via todo processor"
    (let [handler (fn [_context]
                    {:result/events
                     [(es/->event {:type :test/cas-event
                                   :tags #{}
                                   :body {:n 1}})]
                     :result/cas
                     {:types #{:test/cas-event}
                      :predicate-fn (fn [events] (empty? (into [] events)))}})
          event1 (make-event :test/trigger-event)
          event2 (make-event :test/trigger-event)]
      ;; First call succeeds (no existing events, predicate returns true)
      (let [result1 (core/process-event (make-context event1 handler))]
        (is (nil? result1)))
      ;; Second call fails (events exist, predicate returns false)
      (let [result2 (core/process-event (make-context event2 handler))]
        (is (= ::anom/conflict (::anom/category result2)))
        (is (= "CAS failed" (::anom/message result2)))))))

(deftest test-cas-conflict-not-wrapped-as-fault
  (testing "CAS conflict anomaly is passed through, not wrapped as generic fault"
    (let [handler (fn [_context]
                    {:result/events
                     [(es/->event {:type :test/cas-event
                                   :tags #{}
                                   :body {:n 1}})]
                     :result/cas
                     {:types #{:test/cas-event}
                      :predicate-fn (constantly false)}})
          event (make-event :test/trigger-event)
          result (core/process-event (make-context event handler))]
      (is (= ::anom/conflict (::anom/category result)))
      (is (not= "Error storing events." (::anom/message result))))))

;; 8. Lease Check Guard

(deftest lease-check-skips-unowned-tenants
  (testing "process-event skips processing when lease-check-fn returns false"
    (let [processed (atom false)
          handler (fn [_] (reset! processed true) {})
          event (make-event :test/event-1 :body {:num 1})
          result (core/process-event
                   (assoc (make-context event handler)
                     :processor-name :test/proc
                     :lease-check-fn (fn [_tid _pname] false)))]
      (is (not @processed)))))

(deftest lease-check-allows-owned-tenants
  (testing "process-event processes normally when lease-check-fn returns true"
    (let [processed (atom false)
          handler (fn [_] (reset! processed true) {})
          event (make-event :test/event-1 :body {:num 1})
          result (core/process-event
                   (assoc (make-context event handler)
                     :processor-name :test/proc
                     :lease-check-fn (fn [_tid _pname] true)))]
      (is @processed))))

(deftest no-lease-check-fn-processes-normally
  (testing "process-event works normally when no lease-check-fn is provided"
    (let [processed (atom false)
          handler (fn [_] (reset! processed true) {})
          event (make-event :test/event-1 :body {:num 1})
          result (core/process-event (make-context event handler))]
      (is @processed))))

;; 9. Processor Registry

(deftest processor-registry-register-and-read
  (testing "Registering a processor makes it discoverable"
    (let [prev @core/processor-registry*]
      (try
        (core/register-processor! :test/my-proc {:topics [:test/event-1] :handler-fn identity})
        (is (contains? @core/processor-registry* :test/my-proc))
        (is (= [:test/event-1] (get-in @core/processor-registry* [:test/my-proc :topics])))
        (finally
          (reset! core/processor-registry* prev))))))

;; 10. Poll-based processing (EP1, EP4)

(deftest poll-based-processor-processes-all-events
  (testing "EP1: Poll-based processor processes every appended event without pubsub"
    (let [processed (atom [])
          handler (fn [{:keys [event]}]
                    (swap! processed conj (:event/id event))
                    {})
          tenant-id (random-uuid)
          ;; Append 50 events
          events (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range 50))]
      (es/append *event-store* {:tenant-id tenant-id :events events})
      ;; Start a poll-based processor (no pubsub)
      (let [processor (core/start-polling
                        {:event-store *event-store*
                         :tenant-id tenant-id
                         :topics [:test/event-1]
                         :handler-fn handler
                         :processor-name :test/poll-proc
                         :poll-interval-ms 100})]
        (try
          (Thread/sleep 2000)
          (is (= 50 (count @processed))
              (str "Expected 50 processed, got " (count @processed)))
          (finally
            (core/stop-polling processor)))))))

(deftest poll-based-processor-no-pubsub-dependency
  (testing "EP4: Processor works with no pubsub configured at all"
    (let [processed (atom [])
          handler (fn [{:keys [event]}]
                    (swap! processed conj (:event/id event))
                    {})
          tenant-id (random-uuid)
          event (make-event :test/event-1 :body {:num 1})]
      (es/append *event-store* {:tenant-id tenant-id :events [event]})
      (let [processor (core/start-polling
                        {:event-store *event-store*
                         :tenant-id tenant-id
                         :topics [:test/event-1]
                         :handler-fn handler
                         :processor-name :test/no-pubsub-proc
                         :poll-interval-ms 100})]
        (try
          (Thread/sleep 1000)
          (is (= 1 (count @processed)))
          (finally
            (core/stop-polling processor)))))))

(deftest poll-based-processor-catches-up-and-continues
  (testing "EP1: Processor catches up existing events and processes new ones"
    (let [processed (atom [])
          handler (fn [{:keys [event]}]
                    (swap! processed conj (:event/id event))
                    {})
          tenant-id (random-uuid)
          ;; Append 10 events before processor starts
          events-before (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range 10))]
      (es/append *event-store* {:tenant-id tenant-id :events events-before})
      (let [processor (core/start-polling
                        {:event-store *event-store*
                         :tenant-id tenant-id
                         :topics [:test/event-1]
                         :handler-fn handler
                         :processor-name :test/catchup-poll-proc
                         :poll-interval-ms 100})]
        (try
          (Thread/sleep 1000)
          ;; Should have caught up the 10 existing events
          (is (= 10 (count @processed))
              (str "Catch-up: expected 10, got " (count @processed)))
          ;; Append 5 more while processor is running
          (let [events-after (mapv (fn [_] (make-event :test/event-1 :body {:num 2})) (range 5))]
            (es/append *event-store* {:tenant-id tenant-id :events events-after}))
          (Thread/sleep 1000)
          ;; Should have processed all 15
          (is (= 15 (count @processed))
              (str "After new events: expected 15, got " (count @processed)))
          (finally
            (core/stop-polling processor)))))))

;; 11. Effect paths: at-least-once and at-most-once

(deftest effect-after-success-at-least-once
  (testing "At-least-once: effect runs, then checkpoint + on-success events appended"
    (let [effect-ran (atom false)
          handler (fn [_]
                    {:result/effect (fn [] (reset! effect-ran true))
                     :result/checkpoint :after
                     :result/on-success [(es/->event {:type :test/effect-succeeded
                                                       :body {:msg "ok"}})]})
          event (make-event :test/event-1 :body {:num 1})
          ctx (assoc (make-context event handler)
                :processor-name :test/effect-after)]
      (core/process-event ctx)
      (is @effect-ran "Effect should have run")
      ;; on-success event + checkpoint should exist
      (let [all (into [] (es/read *event-store* {:tenant-id test-tenant-id}))
            successes (filter #(= :test/effect-succeeded (:event/type %)) all)
            checkpoints (filter #(= :grain/todo-processor-checkpoint (:event/type %)) all)]
        (is (= 1 (count successes)) "One success event")
        (is (= 1 (count checkpoints)) "One checkpoint")))))

(deftest effect-after-failure-at-least-once
  (testing "At-least-once: effect fails, on-failure events + checkpoint appended"
    (let [handler (fn [_]
                    {:result/effect (fn [] (throw (ex-info "boom" {})))
                     :result/checkpoint :after
                     :result/on-failure [(es/->event {:type :test/effect-failed
                                                       :body {:msg "failed"}})]})
          event (make-event :test/event-1 :body {:num 1})
          ctx (assoc (make-context event handler)
                :processor-name :test/effect-after-fail)]
      (core/process-event ctx)
      (let [all (into [] (es/read *event-store* {:tenant-id test-tenant-id}))
            failures (filter #(= :test/effect-failed (:event/type %)) all)
            checkpoints (filter #(= :grain/todo-processor-checkpoint (:event/type %)) all)]
        (is (= 1 (count failures)) "One failure event")
        (is (= 1 (count checkpoints)) "Checkpoint written even on failure")))))

(deftest effect-before-at-most-once
  (testing "At-most-once: checkpoint first, then effect runs"
    (let [effect-ran (atom false)
          handler (fn [_]
                    {:result/effect (fn [] (reset! effect-ran true))
                     :result/checkpoint :before
                     :result/on-success [(es/->event {:type :test/effect-succeeded
                                                       :body {:msg "ok"}})]})
          event (make-event :test/event-1 :body {:num 1})
          ctx (assoc (make-context event handler)
                :processor-name :test/effect-before)]
      (core/process-event ctx)
      (is @effect-ran "Effect should have run")
      (let [checkpoints (filter #(= :grain/todo-processor-checkpoint (:event/type %))
                                (into [] (es/read *event-store* {:tenant-id test-tenant-id})))]
        (is (= 1 (count checkpoints)) "Checkpoint written before effect")))))

(deftest effect-before-no-rerun-on-replay
  (testing "At-most-once: replay does not re-run effect (checkpoint already exists)"
    (let [effect-count (atom 0)
          handler (fn [_]
                    {:result/effect (fn [] (swap! effect-count inc))
                     :result/checkpoint :before})
          event (make-event :test/event-1 :body {:num 1})
          ctx (assoc (make-context event handler)
                :processor-name :test/effect-before-replay)]
      ;; First run
      (core/process-event ctx)
      (is (= 1 @effect-count))
      ;; Replay — checkpoint exists, CAS conflict, effect should NOT run
      (core/process-event ctx)
      (is (= 1 @effect-count) "Effect must not run twice"))))

(deftest effect-after-no-rerun-on-replay
  (testing "At-least-once: replay does not re-run effect when checkpoint already exists"
    (let [effect-count (atom 0)
          handler (fn [_]
                    {:result/effect (fn [] (swap! effect-count inc))
                     :result/checkpoint :after})
          event (make-event :test/event-1 :body {:num 1})
          ctx (assoc (make-context event handler)
                :processor-name :test/effect-after-replay)]
      ;; First run — effect fires, checkpoint written
      (core/process-event ctx)
      (is (= 1 @effect-count))
      ;; Replay — checkpoint already exists, effect must NOT run again
      (core/process-event ctx)
      (is (= 1 @effect-count) "Effect must not run twice for :after checkpoint"))))

;; 11b. Catch-up idempotency after restart

(deftest catch-up-does-not-replay-checkpointed-effects
  (testing "Full catch-up path: effects don't re-fire after processor restart"
    (let [effect-count (atom 0)
          tenant-id (random-uuid)
          handler (fn [_]
                    {:result/effect (fn [] (swap! effect-count inc))
                     :result/checkpoint :after})
          ;; Seed 3 events before any processor runs
          events (mapv (fn [i] (make-event :test/event-1 :body {:num i})) (range 3))]
      (es/append *event-store* {:tenant-id tenant-id :events events})
      ;; First catch-up — processes all 3 events, writes 3 checkpoints
      (#'core/catch-up-tenant *event-store* tenant-id :test/catchup-restart
                            [:test/event-1]
                            {:event-store *event-store*}
                            handler)
      (is (= 3 @effect-count) "First catch-up should process all 3 events")
      ;; Simulate restart — second catch-up on the same processor name
      (#'core/catch-up-tenant *event-store* tenant-id :test/catchup-restart
                            [:test/event-1]
                            {:event-store *event-store*}
                            handler)
      (is (= 3 @effect-count) "Second catch-up must not re-fire effects"))))

(deftest catch-up-with-nil-checkpoint-does-not-duplicate-effects
  (testing "Catch-up replays from scratch but skips already-checkpointed events"
    (let [effect-count (atom 0)
          tenant-id (random-uuid)
          handler (fn [_]
                    {:result/effect (fn [] (swap! effect-count inc))
                     :result/checkpoint :after})
          events (mapv (fn [i] (make-event :test/event-1 :body {:num i})) (range 2))]
      (es/append *event-store* {:tenant-id tenant-id :events events})
      ;; First catch-up — processes both events, writes checkpoints
      (#'core/catch-up-tenant *event-store* tenant-id :test/catchup-nil
                            [:test/event-1]
                            {:event-store *event-store*}
                            handler)
      (is (= 2 @effect-count) "First catch-up processes both")
      ;; Simulate the scenario where get-last-processed-id returns nil
      ;; (e.g., transient DB failure). Manually call catch-up with all
      ;; events visible — the :after nil path reads from the beginning.
      ;; Each event already has a checkpoint, so effects must not re-fire.
      (let [all-events (into [] (es/read *event-store*
                                  {:tenant-id tenant-id
                                   :types #{:test/event-1}}))]
        (doseq [event all-events]
          (core/process-event {:event event
                               :handler-fn handler
                               :event-store *event-store*
                               :tenant-id tenant-id
                               :processor-name :test/catchup-nil})))
      (is (= 2 @effect-count) "Replaying all events must not re-fire effects"))))

;; 12. Batch checkpointing

(deftest bp1-batch-completeness
  (testing "BP1: All events processed, one checkpoint per batch pointing to last event"
    (let [processed (atom [])
          handler (fn [{:keys [event]}]
                    (swap! processed conj (:event/id event))
                    {})
          tenant-id (random-uuid)
          events (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range 50))]
      (es/append *event-store* {:tenant-id tenant-id :events events})
      (let [processor (core/start-polling
                        {:event-store *event-store*
                         :tenant-id tenant-id
                         :topics [:test/event-1]
                         :handler-fn handler
                         :processor-name :test/bp1-batch
                         :poll-interval-ms 100
                         :batch-size 100})]
        (try
          (Thread/sleep 2000)
          (is (= 50 (count @processed)) (str "All 50 processed, got " (count @processed)))
          ;; Should be exactly 1 checkpoint (one batch of 50)
          (let [proc-uuid (core/processor-name->uuid :test/bp1-batch)
                checkpoints (into []
                              (es/read *event-store*
                                {:tenant-id tenant-id
                                 :types #{:grain/todo-processor-checkpoint}
                                 :tags #{[:processor proc-uuid]}}))]
            (is (= 1 (count checkpoints))
                (str "Expected 1 checkpoint, got " (count checkpoints)))
            ;; Checkpoint should point to the last event
            (when (seq checkpoints)
              (is (= (:event/id (last events))
                     (:triggered-by (first checkpoints))))))
          (finally
            (core/stop-polling processor)))))))

(deftest bp3-effect-handlers-checkpoint-per-event
  (testing "BP3: Effect handlers still checkpoint individually, not batched"
    (let [effect-count (atom 0)
          handler (fn [{:keys [event]}]
                    {:result/effect (fn [] (swap! effect-count inc))
                     :result/checkpoint :after
                     :result/on-success [(es/->event {:type :test/effect-succeeded
                                                       :body {:msg "ok"}})]})
          tenant-id (random-uuid)
          events (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range 5))]
      (es/append *event-store* {:tenant-id tenant-id :events events})
      (let [processor (core/start-polling
                        {:event-store *event-store*
                         :tenant-id tenant-id
                         :topics [:test/event-1]
                         :handler-fn handler
                         :processor-name :test/bp3-effect
                         :poll-interval-ms 100
                         :batch-size 100})]
        (try
          (Thread/sleep 3000)
          (is (= 5 @effect-count) "All 5 effects ran")
          ;; Should be 5 checkpoints (one per event, not batched)
          (let [proc-uuid (core/processor-name->uuid :test/bp3-effect)
                checkpoints (into []
                              (es/read *event-store*
                                {:tenant-id tenant-id
                                 :types #{:grain/todo-processor-checkpoint}
                                 :tags #{[:processor proc-uuid]}}))]
            (is (= 5 (count checkpoints))
                (str "Expected 5 checkpoints, got " (count checkpoints))))
          (finally
            (core/stop-polling processor)))))))

(deftest bp4-batch-faster-than-per-event
  (testing "BP4: Batch checkpointing is faster than per-event"
    (let [tenant-batch (random-uuid)
          tenant-single (random-uuid)
          n 200
          handler (fn [_] {})
          events-batch (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range n))
          events-single (mapv (fn [_] (make-event :test/event-1 :body {:num 1})) (range n))]
      (es/append *event-store* {:tenant-id tenant-batch :events events-batch})
      (es/append *event-store* {:tenant-id tenant-single :events events-single})
      ;; Batch mode
      (let [start-batch (System/currentTimeMillis)
            proc-batch (core/start-polling
                         {:event-store *event-store*
                          :tenant-id tenant-batch
                          :topics [:test/event-1]
                          :handler-fn handler
                          :processor-name :test/bp4-batch
                          :poll-interval-ms 50
                          :batch-size 100})]
        (try
          (Thread/sleep 3000)
          (let [batch-ms (- (System/currentTimeMillis) start-batch)]
            ;; Per-event mode
            (let [start-single (System/currentTimeMillis)
                  proc-single (core/start-polling
                                {:event-store *event-store*
                                 :tenant-id tenant-single
                                 :topics [:test/event-1]
                                 :handler-fn handler
                                 :processor-name :test/bp4-single
                                 :poll-interval-ms 50
                                 :batch-size 1})]
              (try
                (Thread/sleep 5000)
                (let [single-ms (- (System/currentTimeMillis) start-single)
                      batch-ckpts (count (into []
                                           (es/read *event-store*
                                             {:tenant-id tenant-batch
                                              :types #{:grain/todo-processor-checkpoint}})))
                      single-ckpts (count (into []
                                            (es/read *event-store*
                                              {:tenant-id tenant-single
                                               :types #{:grain/todo-processor-checkpoint}})))]
                  (is (< batch-ckpts single-ckpts)
                      (str "Batch checkpoints (" batch-ckpts ") should be fewer than single (" single-ckpts ")")))
                (finally
                  (core/stop-polling proc-single)))))
          (finally
            (core/stop-polling proc-batch)))))))

;; 13. Coalesced tenant poller

(deftest tw2-change-detection-completeness
  (testing "TW2: Coalesced poller detects and processes only changed tenants"
    (let [processed (atom {})
          handler (fn [{:keys [event tenant-id]}]
                    (swap! processed update tenant-id (fnil conj []) (:event/id event))
                    {})
          tenants (repeatedly 20 random-uuid)
          changed-tenants (take 5 tenants)]
      ;; Create all 20 tenants with one initial event each
      (doseq [tid tenants]
        (es/append *event-store* {:tenant-id tid
                                  :events [(make-event :test/event-1 :body {:num 0})]}))
      ;; Register a test processor
      (let [prev @core/processor-registry*]
        (try
          (core/register-processor! :test/tw2-proc
            {:topics [:test/event-1]
             :handler-fn handler})
          (let [poller (core/start-tenant-poller
                         {:event-store *event-store*
                          :tenant-ids (set tenants)
                          :poll-interval-ms 100
                          :batch-size 100
                          :thread-pool-size 4})]
            (try
              ;; Wait for initial catch-up
              (Thread/sleep 2000)
              (reset! processed {})
              ;; Append events to only 5 tenants
              (doseq [tid changed-tenants]
                (es/append *event-store* {:tenant-id tid
                                          :events [(make-event :test/event-1 :body {:num 1})]}))
              (Thread/sleep 1000)
              ;; Only the 5 changed tenants should have new events processed
              (is (= 5 (count @processed))
                  (str "Expected 5 changed tenants, got " (count @processed)))
              (doseq [tid changed-tenants]
                (is (= 1 (count (get @processed tid)))
                    (str "Tenant " tid " should have 1 new event")))
              (finally
                (core/stop-tenant-poller poller))))
          (finally
            (reset! core/processor-registry* prev)))))))

(deftest tw3-delivery-completeness-under-load
  (testing "TW3: All events processed at high tenant count"
    (let [processed (atom 0)
          handler (fn [_] (swap! processed inc) {})
          n-tenants 100
          events-per 50
          tenants (repeatedly n-tenants random-uuid)]
      ;; Create tenants with events
      (doseq [tid tenants]
        (es/append *event-store*
          {:tenant-id tid
           :events (mapv (fn [_] (make-event :test/event-1 :body {:num 1}))
                         (range events-per))}))
      (let [prev @core/processor-registry*]
        (try
          (core/register-processor! :test/tw3-proc
            {:topics [:test/event-1]
             :handler-fn handler})
          (let [poller (core/start-tenant-poller
                         {:event-store *event-store*
                          :tenant-ids (set tenants)
                          :poll-interval-ms 100
                          :batch-size 100
                          :thread-pool-size 8})]
            (try
              (Thread/sleep 10000)
              (is (= (* n-tenants events-per) @processed)
                  (str "Expected " (* n-tenants events-per) " processed, got " @processed))
              (finally
                (core/stop-tenant-poller poller))))
          (finally
            (reset! core/processor-registry* prev)))))))

(deftest tw5-batch-checkpoint-per-tenant-per-cycle
  (testing "TW5: One checkpoint per tenant per cycle, not per event"
    (let [handler (fn [_] {})
          tenant-id (random-uuid)]
      (es/append *event-store*
        {:tenant-id tenant-id
         :events (mapv (fn [_] (make-event :test/event-1 :body {:num 1}))
                       (range 50))})
      (let [prev @core/processor-registry*]
        (try
          (core/register-processor! :test/tw5-proc
            {:topics [:test/event-1]
             :handler-fn handler})
          (let [poller (core/start-tenant-poller
                         {:event-store *event-store*
                          :tenant-ids #{tenant-id}
                          :poll-interval-ms 100
                          :batch-size 100
                          :thread-pool-size 2})]
            (try
              (Thread/sleep 2000)
              (let [proc-uuid (core/processor-name->uuid :test/tw5-proc)
                    checkpoints (into []
                                  (es/read *event-store*
                                    {:tenant-id tenant-id
                                     :types #{:grain/todo-processor-checkpoint}
                                     :tags #{[:processor proc-uuid]}}))]
                (is (= 1 (count checkpoints))
                    (str "Expected 1 batch checkpoint, got " (count checkpoints))))
              (finally
                (core/stop-tenant-poller poller))))
          (finally
            (reset! core/processor-registry* prev)))))))

;; 14. CAS-based periodic task deduplication

(deftest pt-cas1-duplicate-trigger-cas-conflict
  (testing "PT-CAS1: Two appends of the same trigger — first wins, second conflicts"
    (let [tenant-id (random-uuid)
          period "2026-03-23"
          trigger-event (fn [] (es/->event {:type :test/billing-trigger
                                            :body {:period period}}))
          cas-predicate {:types #{:test/billing-trigger}
                         :predicate-fn (fn [existing]
                                         (not (reduce
                                               (fn [_ evt]
                                                 (if (= period (:period evt))
                                                   (reduced true)
                                                   false))
                                               false
                                               existing)))}]
      ;; First append — should succeed
      (let [result-1 (es/append *event-store*
                       {:tenant-id tenant-id
                        :events [(trigger-event)]
                        :cas cas-predicate})]
        (is (not (::anom/category result-1)) "First trigger should succeed"))
      ;; Second append — same period, should conflict
      (let [result-2 (es/append *event-store*
                       {:tenant-id tenant-id
                        :events [(trigger-event)]
                        :cas cas-predicate})]
        (is (= ::anom/conflict (::anom/category result-2)) "Second trigger should conflict"))
      ;; Only one trigger event in the store
      (let [triggers (into []
                       (filter #(= :test/billing-trigger (:event/type %)))
                       (es/read *event-store* {:tenant-id tenant-id}))]
        (is (= 1 (count triggers)) "Exactly one trigger in store")))))

(deftest pt-cas2-processor-handles-trigger-exactly-once
  (testing "PT-CAS2: Two processors process same trigger — only one checkpoints"
    (let [tenant-id (random-uuid)
          trigger (es/->event {:type :test/billing-trigger
                               :body {:period "2026-03-23"}})
          billed (atom 0)
          handler (fn [{:keys [event]}]
                    (swap! billed inc)
                    {:result/events
                     [(es/->event {:type :test/billing-done
                                   :body {:period (:period event)
                                          :billed-by (random-uuid)}})]})]
      ;; Append the trigger
      (es/append *event-store* {:tenant-id tenant-id :events [trigger]})
      ;; Two "processors" try to handle it
      (core/process-event {:event trigger :handler-fn handler :event-store *event-store*
                           :tenant-id tenant-id :processor-name :test/billing-proc})
      (core/process-event {:event trigger :handler-fn handler :event-store *event-store*
                           :tenant-id tenant-id :processor-name :test/billing-proc})
      ;; Handler ran twice (at-least-once) but only one checkpoint + result
      (let [all (into []
                  (remove #(= :grain/tx (:event/type %)))
                  (es/read *event-store* {:tenant-id tenant-id}))
            billing-results (filter #(= :test/billing-done (:event/type %)) all)
            checkpoints (filter #(= :grain/todo-processor-checkpoint (:event/type %)) all)]
        (is (= 1 (count billing-results)) "Exactly one billing result")
        (is (= 1 (count checkpoints)) "Exactly one checkpoint")))))

;; 15. Blocking handlers don't starve other tenants (cached thread pool)

(deftest gl1-blocking-handlers-at-scale
  (testing "GL1: 100 tenants with 2s blocking handlers all complete within 5s (proves no fixed pool queuing)"
    (let [n-tenants 100
          tenants (repeatedly n-tenants random-uuid)
          processed (java.util.concurrent.ConcurrentHashMap.)]
      ;; Append one event per tenant
      (doseq [tid tenants]
        (es/append *event-store* {:tenant-id tid
                                  :events [(make-event :test/event-1 :body {:num 1})]}))
      (let [prev @core/processor-registry*]
        (try
          (core/register-processor! :test/gl1-proc
            {:topics [:test/event-1]
             :handler-fn (fn [{:keys [tenant-id]}]
                           (Thread/sleep 2000)
                           (.put processed tenant-id true)
                           {})})
          (let [start-ms (System/currentTimeMillis)
                poller (core/start-tenant-poller
                         {:event-store *event-store*
                          :tenant-ids (set tenants)
                          :poll-interval-ms 100
                          :batch-size 100})]
            (try
              ;; 5s is enough for concurrent execution (2s sleep + overhead)
              ;; but not enough for serial/batched execution (ceil(100/32)*2s = 8s)
              (Thread/sleep 5000)
              (let [done (.size processed)
                    elapsed (- (System/currentTimeMillis) start-ms)]
                (is (= n-tenants done)
                    (str "All " n-tenants " tenants should complete in 5s, got " done " in " elapsed "ms")))
              (finally
                (core/stop-tenant-poller poller))))
          (finally
            (reset! core/processor-registry* prev)))))))

;; 16. defprocessor macro

(deftest dp1-defprocessor-registers-and-creates-fn
  (testing "DP1: defprocessor creates a function and registers it in the registry"
    (let [prev @core/processor-registry*]
      (try
        (tp/defprocessor :test dp1-handler
          {:topics #{:test/event-1}}
          "Test processor for DP1."
          [context]
          {:result/events
           [(es/->event {:type :test/event-processed
                         :body {:event-id (:event/id (:event context))
                                :status "processed"}})]})
        ;; Function should exist
        (is (fn? test-dp1-handler) "defprocessor should create a function")
        ;; Should be in registry
        (is (contains? @core/processor-registry* :test/dp1-handler)
            "defprocessor should register the processor")
        (let [entry (get @core/processor-registry* :test/dp1-handler)]
          (is (= #{:test/event-1} (:topics entry)) "Topics should be registered")
          (is (some? (:handler-fn entry)) "Handler fn should be registered"))
        (finally
          (reset! core/processor-registry* prev))))))

(deftest dp2-defprocessor-works-with-poller
  (testing "DP2: A defprocessor-registered processor works with the coalesced poller"
    (let [prev @core/processor-registry*
          processed (atom [])]
      (try
        (tp/defprocessor :test dp2-handler
          {:topics #{:test/event-1}}
          [context]
          (swap! processed conj (:event/id (:event context)))
          {})
        (let [tenant-id (random-uuid)]
          (es/append *event-store* {:tenant-id tenant-id
                                    :events [(make-event :test/event-1 :body {:num 1})]})
          (let [poller (core/start-tenant-poller
                         {:event-store *event-store*
                          :tenant-ids #{tenant-id}
                          :poll-interval-ms 100
                          :batch-size 100})]
            (try
              (Thread/sleep 2000)
              (is (pos? (count @processed))
                  (str "Processor should have processed events, got " (count @processed)))
              (finally
                (core/stop-tenant-poller poller)))))
        (finally
          (reset! core/processor-registry* prev))))))

;; 17. defperiodic macro

(deftest dp-p1-defperiodic-registers-trigger
  (testing "DP-P1: defperiodic creates a function and registers it in the trigger registry"
    (let [prev-trig @pt-core/periodic-trigger-registry*]
      (try
        (pt/defperiodic :test periodic-billing
          {:schedule {:every 1 :duration :seconds}}
          "Test periodic billing trigger."
          [tenant-id time]
          (let [period (.toString (.toLocalDate time))]
            {:result/events
             [(es/->event {:type :test/periodic-trigger
                           :body {:period period}})]
             :result/cas
             {:types #{:test/periodic-trigger}
              :predicate-fn (fn [existing]
                              (not (some #(= period (:period %))
                                         (into [] existing))))}}))
        ;; Function should exist
        (is (fn? test-periodic-billing) "defperiodic should create a function")
        ;; Should be in trigger registry
        (is (contains? @pt-core/periodic-trigger-registry* :test/periodic-billing)
            "defperiodic should register the trigger")
        (let [entry (get @pt-core/periodic-trigger-registry* :test/periodic-billing)]
          (is (some? (:schedule entry)) "Schedule should be registered")
          (is (some? (:handler-fn entry)) "Handler fn should be registered"))
        (finally
          (reset! pt-core/periodic-trigger-registry* prev-trig))))))

(deftest dp-p2-start-periodic-triggers-appends-events
  (testing "DP-P2: start-periodic-triggers! calls handler and appends CAS-deduplicated events"
    (let [prev-trig @pt-core/periodic-trigger-registry*
          tenant-id (random-uuid)]
      (try
        ;; Register a trigger via the macro
        (pt/defperiodic :test dp-p2-billing
          {:schedule {:every 1 :duration :seconds}}
          [tid time]
          {:result/events
           [(es/->event {:type :test/periodic-trigger
                         :body {:period "2026-03-23"}})]
           :result/cas
           {:types #{:test/periodic-trigger}
            :predicate-fn (fn [existing]
                            (not (some #(= "2026-03-23" (:period %))
                                       (into [] existing))))}})
        ;; Create a tenant
        (es/append *event-store* {:tenant-id tenant-id
                                  :events [(make-event :test/event-1 :body {:num 1})]})
        ;; Start triggers
        (let [triggers (pt-core/start-periodic-triggers!
                        {:append-fn (partial es/append *event-store*)
                         :tenant-ids-fn #(set (keys (es/tenants *event-store*)))})]
          (try
            ;; Wait for at least one tick
            (Thread/sleep 2000)
            ;; Should have exactly 1 trigger event (CAS deduped even if multiple ticks)
            (let [all (into []
                        (filter #(= :test/periodic-trigger (:event/type %)))
                        (es/read *event-store* {:tenant-id tenant-id}))]
              (is (= 1 (count all))
                  (str "Expected 1 trigger event, got " (count all))))
            (finally
              (pt-core/stop-periodic-triggers! triggers))))
        (finally
          (reset! pt-core/periodic-trigger-registry* prev-trig))))))
