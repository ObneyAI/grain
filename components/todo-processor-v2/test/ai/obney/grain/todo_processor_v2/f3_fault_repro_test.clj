(ns ai.obney.grain.todo-processor-v2.f3-fault-repro-test
  "F3 repro: does process-effect-before run the effect when the
   append-with-checkpoint returns a NON-conflict anomaly (::anom/fault)?"
  (:require [clojure.test :refer :all]
            [ai.obney.grain.todo-processor-v2.core :as core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]))

(def test-tenant-id (random-uuid))

(defschemas f3-events
  {:f3/trigger [:map [:n :int]]
   ;; :f3/bad-event is deliberately NOT declared -> schema validation fault
   :f3/good [:map [:n :int]]})

(def ^:dynamic *event-store* nil)

(defn event-store-fixture [f]
  (let [store (es/start {:conn {:type :in-memory}})]
    (binding [*event-store* store]
      (try (f) (finally (es/stop store))))))

(use-fixtures :each event-store-fixture)

(deftest append-with-checkpoint-return-values
  (testing "success -> nil (the (when (anomaly? result) ...) wrapper)"
    (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 1}})
          r (#'core/append-with-checkpoint
             *event-store* test-tenant-id ::proc-a (:event/id trigger) [])]
      (is (nil? r) "append-with-checkpoint returns nil on success")))

  (testing "replay of the same triggering id -> ::anom/conflict returned verbatim"
    (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 2}})]
      (#'core/append-with-checkpoint *event-store* test-tenant-id ::proc-b
                                     (:event/id trigger) [])
      (let [r (#'core/append-with-checkpoint *event-store* test-tenant-id ::proc-b
                                             (:event/id trigger) [])]
        (is (= ::anom/conflict (::anom/category r))))))

  (testing "unregistered event type in the payload -> ::anom/fault returned"
    (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 3}})
          bad     (es/->event {:type :f3/bad-event :tags #{} :body {:whatever 1}})
          r (#'core/append-with-checkpoint *event-store* test-tenant-id ::proc-c
                                           (:event/id trigger) [bad])]
      (is (= ::anom/fault (::anom/category r))
          "non-conflict anomalies are normalised to ::anom/fault"))))

(deftest process-effect-before-runs-effect-on-fault
  (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 42}})
        bad     (es/->event {:type :f3/bad-event :tags #{} :body {:whatever 1}})
        spend   (atom 0)
        ctx {:event-store *event-store*
             :tenant-id test-tenant-id
             :processor-name ::leaf
             :event trigger}
        result {:result/checkpoint :before
                :result/on-success [bad]        ;; forces the fault branch
                :result/effect (fn [] (swap! spend inc))}]

    (testing "the effect RUNS even though the checkpoint append faulted"
      (#'core/process-effect-before ctx result)
      (is (= 1 @spend) "effect ran on ::anom/fault"))

    (testing "no checkpoint was written -> the event will be redelivered"
      (is (nil? (#'core/get-last-processed-id *event-store* test-tenant-id ::leaf))
          "no checkpoint exists for this processor"))

    (testing "redelivery spends again, unbounded"
      (#'core/process-effect-before ctx result)
      (#'core/process-effect-before ctx result)
      (is (= 3 @spend) "every redelivery re-runs the paid effect"))))

(deftest process-effect-before-suppresses-effect-on-conflict
  (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 7}})
        spend   (atom 0)
        ctx {:event-store *event-store*
             :tenant-id test-tenant-id
             :processor-name ::leaf2
             :event trigger}
        result {:result/checkpoint :before
                :result/on-success []
                :result/effect (fn [] (swap! spend inc))}]
    (#'core/process-effect-before ctx result)
    (is (= 1 @spend) "first delivery runs the effect")
    (#'core/process-effect-before ctx result)
    (#'core/process-effect-before ctx result)
    (is (= 1 @spend) "redeliveries are suppressed by the checkpoint CAS conflict")))

(deftest process-event-missing-checkpoint-key-swallows-the-effect
  (testing "an effect result with no :result/checkpoint hits `case` with no default"
    (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 9}})
          spend (atom 0)]
      (core/process-event
       {:event-store *event-store*
        :tenant-id test-tenant-id
        :processor-name ::leaf3
        :event trigger
        :handler-fn (fn [_] {:result/effect (fn [] (swap! spend inc))})})
      (is (= 0 @spend) "effect never ran")
      (is (some? (#'core/get-last-processed-id *event-store* test-tenant-id ::leaf3))
          "and the event was checkpointed as 'done' -> silently dropped forever"))))

(deftest poller-path-invokes-handler-twice-for-effect-results
  (testing "process-event calls handler-fn a second time"
    (let [trigger (es/->event {:type :f3/trigger :tags #{} :body {:n 11}})
          body-runs (atom 0)
          effect-runs (atom 0)
          handler (fn [_ctx]
                    (swap! body-runs inc)
                    {:result/checkpoint :before
                     :result/on-success []
                     :result/effect (fn [] (swap! effect-runs inc))})
          ctx {:event-store *event-store*
               :tenant-id test-tenant-id
               :event trigger
               :handler-fn handler}]
      ;; This mirrors core.clj:611 -> :612 -> :616 in start-tenant-poller
      (let [result (or (handler ctx) {})]
        (is (some? (:result/effect result)))
        (core/process-event (assoc ctx :processor-name ::leaf4)))
      (is (= 2 @body-runs) "handler body ran twice (poller :611 + process-event :218)")
      (is (= 1 @effect-runs) "but only the SECOND closure was invoked"))))
