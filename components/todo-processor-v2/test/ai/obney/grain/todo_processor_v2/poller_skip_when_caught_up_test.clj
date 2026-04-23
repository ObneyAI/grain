(ns ai.obney.grain.todo-processor-v2.poller-skip-when-caught-up-test
  "Verifies the high-watermark probe skips per-pair event-store reads
   when the (tenant, processor) pair is caught up to the tenant's
   durable watermark, and that the optimization fails closed when the
   probe throws."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.event-store-v3.interface.protocol :as p]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as tp-core]))

(defschemas events
  {:test/skip-trigger [:map [:n :int]]})

;; A wrapper event-store that counts `read` calls per tenant and
;; optionally overrides `tenants` (to simulate probe failure).
(defrecord CountingEventStore [inner config read-calls probe-calls probe-impl]
  p/EventStore
  (start [this] this)
  (stop [_] (p/stop inner))
  (append [_ args] (p/append inner args))
  (read [_ args]
    (swap! read-calls conj args)
    (p/read inner args))
  (tenants [_]
    (swap! probe-calls inc)
    (if probe-impl
      (probe-impl inner)
      (p/tenants inner))))

(defn- make-infra
  ([] (make-infra nil))
  ([probe-impl]
   (let [inner (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})]
     {:event-store (->CountingEventStore inner
                                         (:config inner)
                                         (atom [])
                                         (atom 0)
                                         probe-impl)})))

(defn- stop-infra [{:keys [event-store]}]
  (p/stop event-store))

(defn- reads-for-tenant
  [event-store tid]
  (count (filter #(= tid (:tenant-id %)) @(:read-calls event-store))))

(defn- trigger [n]
  (->event {:type :test/skip-trigger :tags #{} :body {:n n}}))

(defn- register-noop-proc! [proc-name]
  (swap! tp-core/processor-registry* assoc proc-name
         {:handler-fn (fn [_ctx] {:result/events []})
          :topics #{:test/skip-trigger}}))

(defn- unregister-proc! [proc-name]
  (swap! tp-core/processor-registry* dissoc proc-name))

(deftest skip-when-caught-up
  (testing "once a pair is caught up, subsequent ticks issue zero reads for it"
    (let [{:keys [event-store] :as infra} (make-infra)
          tid (random-uuid)
          proc-name :test/skip-proc-1]
      (register-noop-proc! proc-name)
      (es/append event-store {:tenant-id tid :events [(trigger 1)]})
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          ;; Let the pair seed, process the event, then advance-on-empty
          ;; catches pair_wm up to tenant_wm so the skip becomes eligible.
          (Thread/sleep 1500)
          (let [baseline (reads-for-tenant event-store tid)]
            ;; 40 additional ticks with zero new events — skip must fire.
            (Thread/sleep 2000)
            (is (= baseline (reads-for-tenant event-store tid))
                "No additional reads should occur for a caught-up pair"))
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))

(deftest new-event-triggers-a-read
  (testing "appending a new matching event causes the pair to read again"
    (let [{:keys [event-store] :as infra} (make-infra)
          tid (random-uuid)
          proc-name :test/skip-proc-2]
      (register-noop-proc! proc-name)
      (es/append event-store {:tenant-id tid :events [(trigger 1)]})
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          (Thread/sleep 1500)
          (let [before (reads-for-tenant event-store tid)]
            (es/append event-store {:tenant-id tid :events [(trigger 2)]})
            (Thread/sleep 1500)
            (let [after (reads-for-tenant event-store tid)]
              (is (>= (- after before) 1)
                  "At least one additional read should occur after a new event")))
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))

(deftest mixed-tenants-only-active-reads
  (testing "an idle tenant does not cause reads while an active tenant does"
    (let [{:keys [event-store] :as infra} (make-infra)
          idle-tid (random-uuid)
          active-tid (random-uuid)
          proc-name :test/skip-proc-3]
      (register-noop-proc! proc-name)
      ;; Seed both tenants so they exist in grain.tenants with watermarks.
      (es/append event-store {:tenant-id idle-tid :events [(trigger 1)]})
      (es/append event-store {:tenant-id active-tid :events [(trigger 1)]})
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{idle-tid active-tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          ;; Let both pairs catch up.
          (Thread/sleep 1500)
          (let [idle-baseline   (reads-for-tenant event-store idle-tid)
                active-baseline (reads-for-tenant event-store active-tid)]
            (es/append event-store {:tenant-id active-tid :events [(trigger 2)]})
            (Thread/sleep 1500)
            (is (= idle-baseline (reads-for-tenant event-store idle-tid))
                "Idle tenant should issue zero additional reads")
            (is (> (reads-for-tenant event-store active-tid) active-baseline)
                "Active tenant should issue at least one additional read"))
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))

(deftest seeding-tick-still-reads
  (testing "a newly-observed pair still reads on first tick even if tenant is populated"
    (let [{:keys [event-store] :as infra} (make-infra)
          tid (random-uuid)
          proc-name :test/skip-proc-4]
      ;; Populate the tenant BEFORE registering the processor or starting
      ;; the poller, so tenant_wm is already set when the pair's first
      ;; tick runs. The skip must not fire on this tick — pair_wm is
      ;; ::uninit and must be seeded via a read.
      (es/append event-store {:tenant-id tid :events [(trigger 1)]})
      (register-noop-proc! proc-name)
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          (Thread/sleep 500)
          (is (>= (reads-for-tenant event-store tid) 1)
              "First tick must issue at least one read to seed and process")
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))

(deftest probe-failure-fails-closed
  (testing "when the tenants probe throws, the poller falls back to per-pair reads"
    (let [{:keys [event-store] :as infra} (make-infra
                                           (fn [_inner]
                                             (throw (ex-info "probe boom" {}))))
          tid (random-uuid)
          proc-name :test/skip-proc-5]
      (register-noop-proc! proc-name)
      (es/append event-store {:tenant-id tid :events [(trigger 1)]})
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          ;; Without the skip, every tick issues a per-pair read.
          (Thread/sleep 1000)
          ;; At 50ms interval over ~1s we expect ~20 ticks of reads.
          ;; Allow slop, but the count should be well above what the
          ;; caught-up case produces (which is a small constant).
          (is (> (reads-for-tenant event-store tid) 5)
              "Probe failure must not starve the pair — reads continue")
          (is (> @(:probe-calls event-store) 1)
              "The probe itself was still invoked each tick")
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))

(deftest checkpoint-advances-the-skip
  (testing "after a batch is processed + advance-on-empty, further ticks skip"
    (let [{:keys [event-store] :as infra} (make-infra)
          tid (random-uuid)
          proc-name :test/skip-proc-6]
      (register-noop-proc! proc-name)
      (es/append event-store {:tenant-id tid :events [(trigger 1) (trigger 2)]})
      (let [poller (tp-core/start-tenant-poller
                     {:event-store event-store
                      :tenant-ids #{tid}
                      :context {}
                      :poll-interval-ms 50
                      :batch-size 10})]
        (try
          ;; Let the pair process both events, then settle via
          ;; advance-on-empty.
          (Thread/sleep 1500)
          (let [baseline (reads-for-tenant event-store tid)]
            (Thread/sleep 1500)
            (is (= baseline (reads-for-tenant event-store tid))
                "Once caught up past the checkpoint + tx bump, no further reads"))
          (finally
            (tp-core/stop-tenant-poller poller)
            (unregister-proc! proc-name)
            (stop-infra infra)))))))
