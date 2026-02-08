(ns ai.obney.grain.command-processor.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid])
  (:import [java.time OffsetDateTime]))

;; Register test command schemas
(defschemas test-commands
  {:test/create-resource [:map]
   :test/create-with-events [:map]
   :test/create-with-both [:map]
   :test/unknown-command [:map]
   :test/some-command [:map]
   :test/nil-handler [:map]
   :test/forbidden-handler [:map]
   :test/context-handler [:map [:foo :string]]
   :test/multi-events [:map]
   :test/no-events [:map]
   :test/filtered-events [:map]
   :test/anomaly-handler [:map]
   :test/context-anomaly [:map]
   :test/throwing-handler [:map]
   :test/skip-storage [:map]
   :test/skip-storage-child [:map]})

;; Register test event schemas
(defschemas test-events
  {:test/event-created [:map
                        [:event-id :uuid]
                        [:description :string]]
   :test/event-1 [:map [:num :int]]
   :test/event-2 [:map [:num :int]]
   :test/event-3 [:map [:num :int]]
   :test/type-a [:map [:category :string]]
   :test/type-b [:map [:category :string]]})

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

(defn make-command
  [command-name & {:keys [id timestamp extra-data]
                   :or {id (uuid/v4)
                        timestamp (OffsetDateTime/now)}}]
  (merge {:command/name command-name
          :command/id id
          :command/timestamp timestamp}
         extra-data))

(defn make-context
  [command command-registry]
  {:command command
   :command-registry command-registry
   :event-store *event-store*})

(defn make-registry
  [command-handlers]
  (into {} (map (fn [[k v]] [k {:handler-fn v}]) command-handlers)))

;; Sample Command Handlers

(defn successful-handler
  [_context]
  {:command-result/success true
   :command/result {:status "completed"}})

(defn handler-with-events
  [_context]
  (let [event-id (uuid/v4)]
    {:command-result/events
     [(es/->event {:type :test/event-created
                   :tags #{[:test event-id]}
                   :body {:event-id event-id
                          :description "Test event"}})]}))

(defn handler-with-events-and-result
  [_context]
  (let [event-id (uuid/v4)]
    {:command-result/events
     [(es/->event {:type :test/event-created
                   :tags #{[:test event-id]}
                   :body {:event-id event-id
                          :description "Test event with result"}})]
     :command/result {:event-id event-id}}))

(defn handler-returning-nil
  [_context]
  nil)

(defn handler-returning-anomaly
  [_context]
  {::anom/category ::anom/forbidden
   ::anom/message "Access denied"})

(defn handler-receiving-context
  [context]
  {:command/result {:received-command (:command context)}})

(defn handler-throwing-exception
  [_context]
  (throw (ex-info "Unexpected error in handler" {:error-type :database-connection})))

;; Tests

;; 1. Happy Path Tests

(deftest test-successful-command-processing
  (testing "Valid command with registered handler executes successfully"
    (let [command (make-command :test/create-resource)
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))
      (is (= {:status "completed"} (:command/result result)))
      (is (true? (:command-result/success result))))))

(deftest test-command-with-events
  (testing "Events are persisted to event store when present in result"
    (let [command (make-command :test/create-with-events)
          registry (make-registry {:test/create-with-events handler-with-events})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))

      ;; Verify events were stored (filter out :grain/tx events)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))
        (is (= :test/event-created (:event/type (first events))))))))

(deftest test-command-with-events-and-result
  (testing "Handler can return both events and a result"
    (let [command (make-command :test/create-with-both)
          registry (make-registry {:test/create-with-both handler-with-events-and-result})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))
      (is (contains? (:command/result result) :event-id))

      ;; Verify events were stored (filter out :grain/tx events)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))))))

;; 2. Command Registry Tests

(deftest test-unregistered-command
  (testing "Unregistered command returns ::anom/not-found anomaly"
    (let [command (make-command :test/unknown-command)
          registry (make-registry {})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/not-found (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (= "Unknown Command" (::anom/message result))))))

(deftest test-nil-registry
  (testing "Command lookup with nil registry returns not-found"
    (let [command (make-command :test/some-command)
          registry nil
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/not-found (::anom/category result))))))

;; 3. Schema Validation Tests

(deftest test-command-missing-name
  (testing "Command missing :command/name returns ::anom/not-found (fails registry lookup)"
    (let [command {:command/id (uuid/v4)
                   :command/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/not-found (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-command-missing-id
  (testing "Command missing :command/id returns ::anom/incorrect"
    (let [command {:command/name :test/create-resource
                   :command/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-command-missing-timestamp
  (testing "Command missing :command/timestamp returns ::anom/incorrect"
    (let [command {:command/name :test/create-resource
                   :command/id (uuid/v4)}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result))))))

(deftest test-command-invalid-name-type
  (testing "Command with invalid :command/name type returns ::anom/not-found (fails registry lookup)"
    (let [command {:command/name "not-a-keyword"
                   :command/id (uuid/v4)
                   :command/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/not-found (::anom/category result))))))

(deftest test-command-invalid-id-type
  (testing "Command with invalid :command/id type returns ::anom/incorrect"
    (let [command {:command/name :test/create-resource
                   :command/id "not-a-uuid"
                   :command/timestamp (OffsetDateTime/now)}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/incorrect (::anom/category result))))))

(deftest test-validation-error-message-is-human-readable
  (testing "Schema validation errors include human-readable explanations"
    (let [command {:command/name :test/create-resource}
          registry (make-registry {:test/create-resource successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/incorrect (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (pos? (count (::anom/message result)))))))

;; 4. Handler Execution Tests

(deftest test-handler-returning-nil
  (testing "Handler returning nil produces ::anom/fault"
    (let [command (make-command :test/nil-handler)
          registry (make-registry {:test/nil-handler handler-returning-nil})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/fault (::anom/category result)))
      (is (re-find #"returned nil" (::anom/message result))))))

(deftest test-handler-returning-anomaly
  (testing "Handler returning anomaly passes it through"
    (let [command (make-command :test/forbidden-handler)
          registry (make-registry {:test/forbidden-handler handler-returning-anomaly})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/forbidden (::anom/category result)))
      (is (= "Access denied" (::anom/message result))))))

(deftest test-context-passed-to-handler
  (testing "Context is properly passed to handler"
    (let [command (make-command :test/context-handler :extra-data {:foo "bar"})
          registry (make-registry {:test/context-handler handler-receiving-context})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))
      (is (= command (get-in result [:command/result :received-command]))))))

(deftest test-handler-throwing-exception
  (testing "Handler throwing exception returns ::anom/fault anomaly"
    (let [command (make-command :test/throwing-handler)
          registry (make-registry {:test/throwing-handler handler-throwing-exception})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/fault (::anom/category result)))
      (is (string? (::anom/message result)))
      (is (re-find #"Error executing command handler" (::anom/message result)))
      (is (re-find #"Unexpected error in handler" (::anom/message result))))))

;; 5. Event Store Integration Tests

(deftest test-multiple-events-appended
  (testing "Multiple events are appended correctly"
    (let [handler (fn [_context]
                    {:command-result/events
                     [(es/->event {:type :test/event-1
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:num 1}})
                      (es/->event {:type :test/event-2
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:num 2}})
                      (es/->event {:type :test/event-3
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:num 3}})]})
          command (make-command :test/multi-events)
          registry (make-registry {:test/multi-events handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))

      ;; Verify all events were stored (filter out :grain/tx events)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 3 (count events)))
        (is (= #{:test/event-1 :test/event-2 :test/event-3}
               (set (map :event/type events))))))))

(deftest test-command-without-events
  (testing "Commands without events skip event store operations"
    (let [command (make-command :test/no-events)
          registry (make-registry {:test/no-events successful-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (not (contains? result ::anom/category)))

      ;; Verify no non-tx events were stored
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (empty? events))))))

(deftest test-event-store-read-filters-by-type
  (testing "Events can be read back and filtered by type"
    (let [handler (fn [_context]
                    {:command-result/events
                     [(es/->event {:type :test/type-a
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:category "a"}})
                      (es/->event {:type :test/type-b
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:category "b"}})]})
          command (make-command :test/filtered-events)
          registry (make-registry {:test/filtered-events handler})
          context (make-context command registry)]
      (cp/process-command context)

      ;; Read only type-a events
      (let [events (into [] (es/read *event-store* {:types #{:test/type-a}}))]
        (is (= 1 (count events)))
        (is (= :test/type-a (:event/type (first events))))))))

;; 6. Error Handling Tests

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
            command (make-command :test/anomaly-handler)
            registry (make-registry {:test/anomaly-handler handler})
            context (make-context command registry)
            result (cp/process-command context)]
        (is (= category (::anom/category result)))
        (is (= (str "Test " category) (::anom/message result)))))))

(deftest test-anomaly-context-preserved
  (testing "Error context is preserved in anomalies"
    (let [handler (fn [_context]
                    {::anom/category ::anom/conflict
                     ::anom/message "Resource already exists"
                     :resource-id 123
                     :extra-info "Additional context"})
          command (make-command :test/context-anomaly)
          registry (make-registry {:test/context-anomaly handler})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (= ::anom/conflict (::anom/category result)))
      (is (= "Resource already exists" (::anom/message result)))
      (is (= 123 (:resource-id result)))
      (is (= "Additional context" (:extra-info result))))))

;; 7. Global Registry and defcommand Macro Tests

(defschemas global-registry-test-commands
  {:test/global-registry-command [:map]
   :test/global-fallback [:map]
   :test/precedence-test [:map]
   :test/with-opts [:map]})

(deftest test-register-command-adds-to-global-registry
  (testing "register-command! adds handler to global registry"
    (let [initial-registry @cp/command-registry*]
      (reset! cp/command-registry* {})
      (try
        (cp/register-command! :test/global-registry-command
                              (fn [_] {:command/result {:success true}})
                              {})
        (is (contains? @cp/command-registry* :test/global-registry-command))
        (is (fn? (get-in @cp/command-registry* [:test/global-registry-command :handler-fn])))
        (finally
          (reset! cp/command-registry* initial-registry))))))

(deftest test-register-command-merges-opts
  (testing "register-command! merges opts into registry entry"
    (let [initial-registry @cp/command-registry*]
      (reset! cp/command-registry* {})
      (try
        (cp/register-command! :test/with-opts
                              (fn [_] {:command/result {:success true}})
                              {:auth :admin-required :rate-limit 10})
        (let [entry (get @cp/command-registry* :test/with-opts)]
          (is (fn? (:handler-fn entry)))
          (is (= :admin-required (:auth entry)))
          (is (= 10 (:rate-limit entry))))
        (finally
          (reset! cp/command-registry* initial-registry))))))

(deftest test-process-command-uses-global-registry-as-fallback
  (testing "process-command uses global registry when no context registry"
    (let [initial-registry @cp/command-registry*]
      (reset! cp/command-registry* {})
      (try
        (cp/register-command! :test/global-fallback
                              (fn [_] {:command/result {:source :global}})
                              {})
        (let [command (make-command :test/global-fallback)
              ;; Context WITHOUT command-registry
              context {:command command
                       :event-store *event-store*}
              result (cp/process-command context)]
          (is (not (contains? result ::anom/category)))
          (is (= {:source :global} (:command/result result))))
        (finally
          (reset! cp/command-registry* initial-registry))))))

(deftest test-context-registry-takes-precedence-over-global
  (testing "context command-registry takes precedence over global"
    (let [initial-registry @cp/command-registry*]
      (reset! cp/command-registry* {})
      (try
        ;; Register in global with one behavior
        (cp/register-command! :test/precedence-test
                              (fn [_] {:command/result {:source :global}})
                              {})
        ;; Use context registry with different behavior
        (let [command (make-command :test/precedence-test)
              context-handler (fn [_] {:command/result {:source :context}})
              context {:command command
                       :command-registry {:test/precedence-test {:handler-fn context-handler}}
                       :event-store *event-store*}
              result (cp/process-command context)]
          (is (= {:source :context} (:command/result result))))
        (finally
          (reset! cp/command-registry* initial-registry))))))

(deftest test-global-command-registry-returns-current-registry
  (testing "global-command-registry returns the current state of the registry"
    (let [initial-registry @cp/command-registry*]
      (reset! cp/command-registry* {})
      (try
        (is (= {} (cp/global-command-registry)))
        (cp/register-command! :test/some-command (fn [_] {}) {})
        (is (contains? (cp/global-command-registry) :test/some-command))
        (finally
          (reset! cp/command-registry* initial-registry))))))

;; 8. Skip Event Storage Tests

(deftest test-skip-event-storage
  (testing "Events are returned but not stored when :command-processor/skip-event-storage is true"
    (let [handler (fn [_context]
                    {:command-result/events
                     [(es/->event {:type :test/event-created
                                   :tags #{[:test (uuid/v4)]}
                                   :body {:event-id (uuid/v4)
                                          :description "Should not be stored"}})]
                     :command/result {:status "ok"}})
          command (make-command :test/skip-storage)
          registry (make-registry {:test/skip-storage handler})
          context (assoc (make-context command registry)
                         :command-processor/skip-event-storage true)
          result (cp/process-command context)]
      ;; Result should contain events
      (is (= 1 (count (:command-result/events result))))
      (is (= {:status "ok"} (:command/result result)))

      ;; But no events should be stored (filter out :grain/tx events)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (empty? events))))))

(deftest test-command-composition-with-skip-storage
  (testing "Parent handler can aggregate events from child commands"
    (let [child-handler (fn [_context]
                          {:command-result/events
                           [(es/->event {:type :test/event-created
                                         :tags #{[:test (uuid/v4)]}
                                         :body {:event-id (uuid/v4)
                                                :description "Child event"}})]})
          parent-handler (fn [context]
                           (let [child-ctx (assoc context :command-processor/skip-event-storage true)
                                 child-result (cp/process-command
                                                (assoc child-ctx
                                                       :command (make-command :test/skip-storage-child)))]
                             {:command-result/events (:command-result/events child-result)
                              :command/result {:aggregated true}}))
          command (make-command :test/skip-storage)
          registry (make-registry {:test/skip-storage parent-handler
                                   :test/skip-storage-child child-handler})
          context (make-context command registry)
          result (cp/process-command context)]
      ;; Parent should store the aggregated events
      (is (= 1 (count (:command-result/events result))))
      (is (= {:aggregated true} (:command/result result)))

      ;; Events should be stored (only once, by parent)
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))))))

(deftest test-default-behavior-stores-events
  (testing "Events are stored when :command-processor/skip-event-storage is not set"
    (let [command (make-command :test/create-with-events)
          registry (make-registry {:test/create-with-events handler-with-events})
          context (make-context command registry)
          result (cp/process-command context)]
      (is (seq (:command-result/events result)))

      ;; Events should be stored
      (let [events (->> (es/read *event-store* {})
                        (into [])
                        (filter #(not= :grain/tx (:event/type %))))]
        (is (= 1 (count events)))))))
