(ns ai.obney.grain.todo-processor-v2.dynamic-tenant-replay-test
  "Reproduces a bug where a tenant added to a running poller's
   owned-set AFTER the poller has started causes pre-checkpointed
   events to be re-read and re-run through handler-fn.

   Root cause: start-tenant-poller initializes watermarks from
   checkpoints exactly once, at poll-thread start, iterating
   (owned-tenants) x registry. When control-plane.reconcile-tenants!
   later grows the owned set via (reset! tenant-ids-atom ...), the
   newly-added tenant's watermarks are never initialized, so the next
   poll cycle reads `:after nil`, returning every historical event
   of the subscribed topics.

   This is the production path that runs whenever a lease is acquired
   after node startup (the first assignment cycle, lease reassignment,
   failover, etc.)."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as tp-core]))

(defschemas events
  {:test/dynamic-replay-trigger [:map [:n :int]]})

(defn- make-checkpoint-event
  [processor-name triggering-event-id]
  (let [proc-uuid (tp-core/processor-name->uuid processor-name)]
    (->event
     {:type :grain/todo-processor-checkpoint
      :tags #{[:processor proc-uuid]}
      :body {:processor/name processor-name
             :triggered-by triggering-event-id}})))

(deftest tenant-added-after-poller-start-does-not-reprocess-checkpointed-events
  (testing "a tenant added to a running poller's owned-set must NOT
            re-run handler-fn on events that already have checkpoints"
    (let [event-store (es/start {:conn {:type :in-memory}
                                 :event-pubsub nil
                                 :logger nil})
          proc-name :test/dynamic-replay-proc
          tid (random-uuid)
          handler-calls (atom 0)]
      (try
        (swap! tp-core/processor-registry* assoc proc-name
               {:handler-fn (fn [_ctx]
                              (swap! handler-calls inc)
                              {})
                :topics #{:test/dynamic-replay-trigger}})

        (let [evts (mapv (fn [i]
                           (->event {:type :test/dynamic-replay-trigger
                                     :tags #{}
                                     :body {:n i}}))
                         (range 3))
              cp (make-checkpoint-event proc-name
                                        (:event/id (last evts)))]
          (es/append event-store {:tenant-id tid :events evts})
          (es/append event-store {:tenant-id tid :events [cp]}))

        (let [ts-atom (atom #{})
              poller (tp-core/start-tenant-poller
                      {:event-store event-store
                       :tenant-ids ts-atom
                       :context {}
                       :poll-interval-ms 100
                       :batch-size 100})]
          (try
            (Thread/sleep 400)

            (reset! ts-atom #{tid})

            (Thread/sleep 1500)

            (is (zero? @handler-calls)
                (str "handler-fn must not be called for events that are "
                     "already checkpointed when a tenant is added to the "
                     "poller's owned-set after poller start; got "
                     @handler-calls " calls"))
            (finally
              (tp-core/stop-tenant-poller poller))))
        (finally
          (swap! tp-core/processor-registry* dissoc proc-name)
          (es/stop event-store))))))

(deftest watermark-lookup-memoized-for-uninitialized-pairs
  (testing "get-last-processed-id must run at most once per (tenant,
            processor) pair even when the tenant has no checkpoint yet,
            otherwise every poll cycle re-queries the event store for
            every uninitialized pair"
    (let [event-store (es/start {:conn {:type :in-memory}
                                 :event-pubsub nil
                                 :logger nil})
          proc-name :test/no-checkpoint-proc
          tid (random-uuid)
          lookup-calls (atom 0)
          real-lookup @#'tp-core/get-last-processed-id]
      (with-redefs [tp-core/get-last-processed-id
                    (fn [& args]
                      (swap! lookup-calls inc)
                      (apply real-lookup args))]
        (try
          (swap! tp-core/processor-registry* assoc proc-name
                 {:handler-fn (fn [_ctx] {})
                  :topics #{:test/dynamic-replay-trigger}})

          (let [poller (tp-core/start-tenant-poller
                        {:event-store event-store
                         :tenant-ids #{tid}
                         :context {}
                         :poll-interval-ms 50
                         :batch-size 100})]
            (try
              (Thread/sleep 1000)
              (is (= 1 @lookup-calls)
                  (str "get-last-processed-id must be called exactly once "
                       "per (tenant, processor) pair across many poll "
                       "cycles, including when the tenant has no "
                       "checkpoint yet; got " @lookup-calls " calls "
                       "across ~20 poll cycles"))
              (finally
                (tp-core/stop-tenant-poller poller))))
          (finally
            (swap! tp-core/processor-registry* dissoc proc-name)
            (es/stop event-store)))))))
