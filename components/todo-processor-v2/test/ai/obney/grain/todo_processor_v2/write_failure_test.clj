(ns ai.obney.grain.todo-processor-v2.write-failure-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as todo]
            [cognitect.anomalies :as anom]))

(defschemas events
  {:write-failure/trigger [:map [:n :int]]
   :write-failure/result [:map [:n :int]]})

(def busy {::anom/category ::anom/busy
           ::anom/message "Write retries exhausted"
           :event-store/attempts 4})

(defn- result-event [n]
  (es/->event {:type :write-failure/result :body {:n n}}))

(defn- checkpoint [store tenant processor]
  (#'todo/get-last-processed-id store tenant processor))

;; Allium: EventLog.StorageFailureIsNotCheckpointSuccess,
;; Checkpointing.MonotonicGapFreeWatermark / BatchCheckpointCompleteness.
;; Gate the next checkpoint attempt, rather than racing a sleep against a poll.
(deftest pollers-retry-failed-prefix-before-later-events
  (doseq [mode [:coalesced :per-tenant]
          mixed? [false true]]
    (testing (str mode " mixed pure/effect batch=" mixed?)
      (let [store (es/start {:conn {:type :in-memory}})
            tenant (random-uuid)
            processor :write-failure/poller
            stored (es/append store {:tenant-id tenant
                                    :events (mapv #(es/->event {:type :write-failure/trigger :body {:n %}}) [1 2])})
            first-id (:event/id (first stored))
            last-id (:event/id (last stored))
            attempts (atom 0)
            next-attempt (promise)
            resume (promise)
            done (promise)
            effects (atom [])
            handler (fn [{:keys [event]}]
                      (let [n (:n event)]
                        (if (and mixed? (= 1 n))
                          {:result/events [(result-event n)]}
                          {:result/checkpoint :before
                           :result/on-success [(result-event n)]
                           :result/effect #(do (swap! effects conj n)
                                               (when (= 2 n) (deliver done true)))})))
            original-append es/append
            registry @todo/processor-registry*]
        (try
          (reset! todo/processor-registry* {processor {:topics #{:write-failure/trigger} :handler-fn handler}})
          (with-redefs [es/append
                        (fn [s args]
                          (if-let [cp (some #(when (= :grain/todo-processor-checkpoint (:event/type %)) %) (:events args))]
                            (if (= 1 (swap! attempts inc))
                              busy
                              (do
                                (deliver next-attempt (:triggered-by cp))
                                (when (= ::timeout (deref resume 5000 ::timeout))
                                  (throw (ex-info "Test did not release checkpoint" {})))
                                (original-append s args)))
                            (original-append s args)))]
            (let [poller (if (= mode :coalesced)
                           (todo/start-tenant-poller {:event-store store :tenant-ids #{tenant} :poll-interval-ms 10})
                           (todo/start-polling {:event-store store :tenant-id tenant :processor-name processor
                                               :topics #{:write-failure/trigger} :handler-fn handler :poll-interval-ms 10}))]
              (try
                (is (= first-id (deref next-attempt 5000 ::timeout))
                    "The next cycle must retry the failed prefix, not checkpoint a later event")
                (is (nil? (checkpoint store tenant processor)))
                (is (empty? @effects))
                (deliver resume true)
                (is (= true (deref done 5000 ::timeout)))
                (is (= last-id (checkpoint store tenant processor)))
                (is (= (if mixed? [2] [1 2]) @effects))
                (is (= [1 2] (mapv :n (into [] (es/read store {:tenant-id tenant :types #{:write-failure/result}})))))
                (finally
                  (deliver resume true)
                  (if (= mode :coalesced) (todo/stop-tenant-poller poller) (todo/stop-polling poller))))))
          (finally (reset! todo/processor-registry* registry) (es/stop store)))))))

;; Allium: EventLog.StorageFailureIsNotCheckpointSuccess / CatchUpCompleteness.
(deftest startup-catch-up-fails-before-skipping-an-event
  (let [store (es/start {:conn {:type :in-memory}})
        tenant (random-uuid)
        processor :write-failure/catch-up
        effects (atom [])
        handler (fn [{:keys [event]}]
                  {:result/checkpoint :before :result/effect #(swap! effects conj (:n event))})]
    (try
      (es/append store {:tenant-id tenant :events (mapv #(es/->event {:type :write-failure/trigger :body {:n %}}) [1 2])})
      (let [caught (with-redefs [es/append (constantly busy)]
                     (try
                       (#'todo/catch-up-tenant store tenant processor #{:write-failure/trigger} {:event-store store} handler)
                       nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (is (= ::anom/busy (::anom/category caught)))
        (is (nil? (checkpoint store tenant processor)))
        (is (empty? @effects)))
      (#'todo/catch-up-tenant store tenant processor #{:write-failure/trigger} {:event-store store} handler)
      (is (= [1 2] @effects) "A later successful catch-up resumes at the failed event")
      (finally (es/stop store)))))

;; Allium: EventLog.StorageFailureIsNotCheckpointSuccess, including command writers.
(deftest busy-handler-result-is-never-checkpointed-as-a-rejection
  (let [store (es/start {:conn {:type :in-memory}})
        tenant (random-uuid)
        processor :write-failure/command-consumer
        event (first (es/append store {:tenant-id tenant :events [(es/->event {:type :write-failure/trigger :body {:n 1}})]}))]
    (try
      (doseq [retry-on-error? [false true]]
        (is (= busy (todo/process-event {:event-store store :tenant-id tenant :processor-name processor
                                        :event event :handler-fn (constantly busy) :retry-on-error? retry-on-error?})))
        (is (nil? (checkpoint store tenant processor))))
      (finally (es/stop store)))))

(deftest pollers-preserve-busy-from-a-handler
  (doseq [mode [:coalesced :per-tenant]]
    (let [store (es/start {:conn {:type :in-memory}})
          tenant (random-uuid)
          processor :write-failure/busy-handler
          calls (atom [])
          outputs (promise)
          release (promise)
          original-append es/append
          registry @todo/processor-registry*
          handler (fn [{:keys [event]}]
                    (swap! calls conj (:n event))
                    (if (= [1] @calls) busy {:result/events [(result-event (:n event))]}))]
      (try
        (es/append store {:tenant-id tenant :events (mapv #(es/->event {:type :write-failure/trigger :body {:n %}}) [1 2])})
        (reset! todo/processor-registry* {processor {:topics #{:write-failure/trigger} :handler-fn handler}})
        (with-redefs [es/append (fn [s args]
                                (deliver outputs (mapv :n (filter #(= :write-failure/result (:event/type %)) (:events args))))
                                (deref release 5000 nil)
                                (original-append s args))]
          (let [poller (if (= mode :coalesced)
                         (todo/start-tenant-poller {:event-store store :tenant-ids #{tenant} :poll-interval-ms 10})
                         (todo/start-polling {:event-store store :tenant-id tenant :processor-name processor
                                             :topics #{:write-failure/trigger} :handler-fn handler :poll-interval-ms 10}))]
            (try
              (is (= [1 2] (deref outputs 5000 ::timeout)))
              (is (= [1 1 2] @calls) "Busy must stop the cycle before invoking the next handler")
              (finally
                (deliver release true)
                (if (= mode :coalesced) (todo/stop-tenant-poller poller) (todo/stop-polling poller))))))
        (finally (reset! todo/processor-registry* registry) (es/stop store))))))
