(ns ai.obney.grain.todo-processor-v2.poller-duplicate-test
  "Proves that slow effect handlers execute exactly once per event,
   even when the effect takes longer than the poll interval."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.todo-processor-v2.core :as tp-core]))

(defschemas events
  {:test/slow-trigger [:map [:data :string]]})

(defn- make-test-infra []
  (let [dir (str "/tmp/poller-dup-test-" (random-uuid))]
    {:event-store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
     :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
     :dir dir}))

(defn- stop-test-infra [{:keys [event-store cache dir]}]
  (kv/stop cache)
  (es/stop event-store)
  (let [f (java.io.File. dir)]
    (when (.exists f)
      (doseq [child (reverse (file-seq f))] (.delete child)))))

(deftest slow-effect-executes-exactly-once
  (testing "a 2-second effect runs exactly once despite 100ms poll interval"
    (let [{:keys [event-store] :as infra} (make-test-infra)
          tid (random-uuid)
          effect-count (atom 0)
          proc-name :test/slow-effect-proc]

      ;; Register processor with a slow effect
      (swap! tp-core/processor-registry* assoc proc-name
             {:handler-fn (fn [_context]
                            {:result/effect
                             (fn []
                               (swap! effect-count inc)
                               (Thread/sleep 2000)
                               nil)
                             :result/checkpoint :after})
              :topics #{:test/slow-trigger}})

      ;; Store one event
      (es/append event-store
                 {:tenant-id tid
                  :events [(->event {:type :test/slow-trigger
                                     :tags #{}
                                     :body {:data "test"}})]})

      ;; Start poller with fast interval (20x faster than the effect)
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 100
                      :batch-size 10})]
        (try
          ;; Wait for effect to complete plus buffer
          (Thread/sleep 5000)

          (println "Effect executions:" @effect-count "(expected: 1)")
          (is (= 1 @effect-count)
              "Each event should trigger exactly one effect execution")

          (finally
            (tp-core/stop-tenant-poller poller)
            (swap! tp-core/processor-registry* dissoc proc-name)
            (stop-test-infra infra)))))))
