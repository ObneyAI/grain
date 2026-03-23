(ns ai.obney.grain.todo-processor-v2.core
  (:require [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as u]
            [ai.obney.grain.event-store-v3.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [integrant.core :as ig]
            [clojure.core.async :as async]
            [ai.obney.grain.core-async-thread-pool.interface :as thread-pool]
            [clj-uuid :as uuid]))

;; ------------------- ;;
;; Processor Registry  ;;
;; ------------------- ;;

(def processor-registry*
  "Global registry of todo processors. Maps processor-name keyword to config map."
  (atom {}))

(defn register-processor!
  "Register a processor so the control plane can discover it."
  [processor-name config]
  (swap! processor-registry* assoc processor-name config))

;; ------------------- ;;
;; Checkpoint Support  ;;
;; ------------------- ;;

(defn processor-name->uuid
  "Derives a stable UUID v5 from a processor-name keyword.
   Used to create valid event store tags (which require UUID entity-ids)."
  [processor-name]
  (uuid/v5 uuid/+namespace-url+ (str processor-name)))

(defn- make-checkpoint-event
  [processor-name triggering-event-id]
  (let [proc-uuid (processor-name->uuid processor-name)]
    (event-store/->event
     {:type :grain/todo-processor-checkpoint
      :tags #{[:processor proc-uuid]}
      :body {:processor/name processor-name
             :triggered-by triggering-event-id}})))

(defn- append-with-checkpoint
  "Appends events + checkpoint atomically with CAS to prevent duplicates."
  [event-store tenant-id processor-name triggering-event-id events]
  (let [proc-uuid (processor-name->uuid processor-name)
        checkpoint (make-checkpoint-event processor-name triggering-event-id)
        all-events (conj (vec events) checkpoint)
        result (event-store/append event-store
                 {:tenant-id tenant-id
                  :events all-events
                  :cas {:types #{:grain/todo-processor-checkpoint}
                        :tags  #{[:processor proc-uuid]}
                        :predicate-fn
                        (fn [evts]
                          (not (reduce
                                (fn [_ evt]
                                  (if (= triggering-event-id (:triggered-by evt))
                                    (reduced true)
                                    false))
                                false
                                evts)))}})]
    (when (anomaly? result)
      (if (= ::anom/conflict (::anom/category result))
        (do (u/log ::already-processed :processor-name processor-name
                   :triggered-by triggering-event-id)
            result)
        (do (u/log ::error-storing-events :anomaly result)
            {::anom/category ::anom/fault
             ::anom/message "Error storing events."})))))

;; ------------------- ;;
;; Event Processing    ;;
;; ------------------- ;;

(defn- make-effect-failure-event
  [processor-name triggering-event-id error-message]
  (let [proc-uuid (processor-name->uuid processor-name)]
    (event-store/->event
     {:type :grain/todo-processor-effect-failure
      :tags #{[:processor proc-uuid]}
      :body {:processor/name processor-name
             :triggered-by triggering-event-id
             :error/message error-message}})))

(defn- process-effect-after
  "At-least-once: run effect first, then append events + checkpoint."
  [{:keys [event-store tenant-id processor-name event]} result]
  (let [triggering-id (:event/id event)
        effect-fn (:result/effect result)]
    (try
      (effect-fn)
      (append-with-checkpoint event-store tenant-id processor-name
                              triggering-id (or (:result/on-success result) []))
      (catch Throwable effect-ex
        (u/log ::effect-failed :processor-name processor-name
               :triggered-by triggering-id :exception effect-ex)
        (try
          (append-with-checkpoint event-store tenant-id processor-name
                                  triggering-id (or (:result/on-failure result) []))
          (catch Throwable failure-ex
            (u/log ::effect-failure-handler-failed :exception failure-ex)
            (let [failure-event (make-effect-failure-event
                                 processor-name triggering-id
                                 (str (ex-message effect-ex)))]
              (append-with-checkpoint event-store tenant-id processor-name
                                      triggering-id [failure-event]))))))))

(defn- process-effect-before
  "At-most-once: append events + checkpoint first, then run effect."
  [{:keys [event-store tenant-id processor-name event]} result]
  (let [triggering-id (:event/id event)
        append-result (append-with-checkpoint
                        event-store tenant-id processor-name
                        triggering-id (or (:result/on-success result) []))]
    (when-not (and (anomaly? append-result)
                   (= ::anom/conflict (::anom/category append-result)))
      ((:result/effect result)))))

(defn- process-pure-result
  "Handles the pure (no side effect) path for a handler result."
  [{:keys [event-store tenant-id processor-name event]} result]
  (let [handler-cas (:result/cas result)
        events (:result/events result)]
    (cond
      handler-cas
      (let [event-store-result (event-store/append event-store
                                 (cond-> {:tenant-id tenant-id :events events}
                                   handler-cas (assoc :cas handler-cas)))]
        (when (anomaly? event-store-result)
          (u/log ::error-storing-events :anomaly event-store-result)
          (if (= ::anom/conflict (::anom/category event-store-result))
            event-store-result
            {::anom/category ::anom/fault
             ::anom/message "Error storing events."})))

      processor-name
      (append-with-checkpoint event-store tenant-id processor-name
                              (:event/id event) (or events []))

      events
      (let [event-store-result (event-store/append event-store
                                 {:tenant-id tenant-id :events events})]
        (when (anomaly? event-store-result)
          (u/log ::error-storing-events :anomaly event-store-result)
          {::anom/category ::anom/fault
           ::anom/message "Error storing events."})))))

(defn process-event
  [{:keys [handler-fn event event-store tenant-id lease-check-fn processor-name
           retry-on-error?] :as context}]
  ;; Lease-check guard: skip events for tenants this node doesn't own
  (if (and lease-check-fn
           (not (lease-check-fn tenant-id processor-name)))
    (u/log ::lease-check-skipped :tenant-id tenant-id :processor-name processor-name)
    (do
      (u/log ::process-event :event event)
      (u/trace
       ::processing-event
       [:event event :metric/name "TodoProcessed" :metric/resolution :high]
       (try
         (let [_ (u/log :metric/metric :metric/name "TodoStarted" :metric/value 1 :metric/resolution :high)
               result (or (handler-fn context)
                          {::anom/category ::anom/fault
                           ::anom/message  "Todo Processor returned nil: %s"})
               _ (u/log :metric/metric :metric/name "TodoFinished" :metric/value 1 :metric/resolution :high)]
           (if (anomaly? result)
             (do (u/log ::anomaly-in-todo-processor :anomaly result)
                 (when (and processor-name (not retry-on-error?))
                   (append-with-checkpoint event-store tenant-id processor-name
                                           (:event/id event) [])))
             (if (:result/effect result)
               (case (:result/checkpoint result)
                 :after  (process-effect-after context result)
                 :before (process-effect-before context result))
               (process-pure-result context result))))
         (catch Throwable t
           (u/log ::uncaught-exception-in-todo-processor :exception t)
           (when (and processor-name (not retry-on-error?))
             (append-with-checkpoint event-store tenant-id processor-name
                                     (:event/id event) []))))))))

;; ------------------- ;;
;; Catch-up            ;;
;; ------------------- ;;

(defn- get-last-processed-id
  "Queries the event store for the most recent checkpoint for this processor+tenant."
  [event-store tenant-id processor-name]
  (let [proc-uuid (processor-name->uuid processor-name)]
    (->> (event-store/read event-store
           {:tenant-id tenant-id
            :types #{:grain/todo-processor-checkpoint}
            :tags #{[:processor proc-uuid]}})
         (reduce (fn [_ event] (:triggered-by event)) nil))))

(defn- catch-up-tenant
  "Catches up a single tenant by processing all missed events since the last checkpoint."
  [event-store tenant-id processor-name topics context handler-fn]
  (let [last-id (get-last-processed-id event-store tenant-id processor-name)
        missed (event-store/read event-store
                 (cond-> {:tenant-id tenant-id
                          :types (set topics)}
                   last-id (assoc :after last-id)))]
    (reduce
     (fn [_ event]
       (when-not (= :grain/tx (:event/type event))
         (process-event (assoc context
                          :event event
                          :handler-fn handler-fn
                          :tenant-id tenant-id
                          :processor-name processor-name))))
     nil
     missed)))

(defn- catch-up-all-tenants
  "Catches up all known tenants in parallel."
  [event-store processor-name topics context handler-fn]
  (let [tenants (event-store/tenant-ids event-store)]
    (when (seq tenants)
      (u/log ::catch-up-starting :processor-name processor-name
             :tenant-count (count tenants))
      (->> tenants
           (pmap (fn [tenant-id]
                   (catch-up-tenant event-store tenant-id processor-name
                                    topics context handler-fn)))
           doall)
      (u/log ::catch-up-complete :processor-name processor-name))))

;; ------------------- ;;
;; Integrant System    ;;
;; ------------------- ;;

(def ^:private system
  {::handler-fn {}
   ::topics {}
   ::processor-name {}
   ::event-sub {:event-pubsub (ig/ref ::event-pubsub)
                :in-chan (ig/ref ::in-chan)
                :topics (ig/ref ::topics)
                :processor-name (ig/ref ::processor-name)
                :context (ig/ref ::context)
                :handler-fn (ig/ref ::handler-fn)}
   ::event-pubsub {}
   ::context {}
   ::execution-fn {:context (ig/ref ::context)
                   :handler-fn (ig/ref ::handler-fn)
                   :processor-name (ig/ref ::processor-name)}
   ::in-chan {:size 1024}
   ::thread-pool {:thread-count 1
                  :error-fn (fn [e] (u/log ::error ::error e))
                  :in-chan (ig/ref ::in-chan)
                  :execution-fn (ig/ref ::execution-fn)}})

(defmethod ig/init-key ::context [_ config]
  config)

(defmethod ig/init-key ::in-chan [_ config]
  (u/log ::starting-in-chan config)
  (async/chan (:size config)))

(defmethod ig/halt-key! ::in-chan [_ in-chan]
  (u/log ::stopping-in-chan in-chan)
  (async/close! in-chan))

(defmethod ig/init-key ::execution-fn [_ {:keys [context handler-fn processor-name]}]
  (u/log ::starting-execution-fn)
  (fn [event]
    (async/thread
      (try (let [tenant-id (:grain/tenant-id event)
                 event (dissoc event :grain/tenant-id)]
             (process-event (cond-> (assoc context
                                     :event event
                                     :handler-fn handler-fn
                                     :tenant-id tenant-id)
                              processor-name (assoc :processor-name processor-name))))
           (catch Throwable t
             {::anom/category ::anom/fault
              ::anom/message "Error processing message"
              :exception t})))))

(defmethod ig/init-key ::thread-pool [_ config]
  (u/log ::starting-thread-pool config)
  (thread-pool/start config))

(defmethod ig/halt-key! ::thread-pool [_ thread-pool]
  (u/log ::stopping-thread-pool thread-pool)
  (thread-pool/stop thread-pool))

(defmethod ig/init-key ::event-pubsub [_ event-pubsub]
  event-pubsub)

(defmethod ig/init-key ::processor-name [_ config]
  config)

(defmethod ig/init-key ::event-sub [_ {:keys [event-pubsub in-chan topics
                                               processor-name context handler-fn]}]
  ;; Catch up missed events before subscribing to live events
  (when processor-name
    (let [event-store (:event-store context)]
      (when event-store
        (catch-up-all-tenants event-store processor-name topics context handler-fn))))
  ;; Subscribe to pubsub for live events
  (run! #(pubsub/sub
          event-pubsub
          {:sub-chan in-chan
           :topic %})
        topics))

(defmethod ig/init-key ::handler-fn [_ config]
  config)

(defmethod ig/init-key ::topics [_ config]
  config)

(defn start
  [config]
  (ig/init (merge system
                  {::context (:context config)
                   ::event-pubsub (:event-pubsub config)
                   ::handler-fn (:handler-fn config)
                   ::topics (:topics config)
                   ::processor-name (:processor-name config)})))

(defn stop
  [todo-processor]
  (ig/halt! todo-processor))

;; ----------------------------- ;;
;; Poll-based processor          ;;
;; ----------------------------- ;;

(defn start-polling
  "Start a pull-based processor that polls the event store directly.
   No pubsub, no channels, no NOTIFY. The event store is the delivery mechanism.

   Processes events in batches. Pure handlers (no :result/effect) are batched —
   one checkpoint per batch. Effect handlers checkpoint per-event.

   config keys:
     :event-store      - the event store instance
     :tenant-id        - the tenant to poll for
     :topics           - set/vector of event types to process
     :handler-fn       - the handler function
     :processor-name   - keyword name (used for checkpoints)
     :poll-interval-ms - poll frequency (default 250)
     :batch-size       - max events per poll cycle (default 100)
     :lease-check-fn   - optional lease check function"
  [{:keys [event-store tenant-id topics handler-fn processor-name
           poll-interval-ms batch-size lease-check-fn]
    :or {poll-interval-ms 250
         batch-size 100}}]
  (let [running (atom true)
        thread (Thread.
                (fn []
                  (u/log ::poll-processor-started :processor-name processor-name
                         :tenant-id tenant-id :topics topics :batch-size batch-size)
                  (let [last-id (atom (get-last-processed-id event-store tenant-id processor-name))]
                    (while @running
                      (try
                        (let [read-args (cond-> {:tenant-id tenant-id
                                                 :types (set topics)}
                                          @last-id (assoc :after @last-id))
                              events (into []
                                       (comp (remove #(= :grain/tx (:event/type %)))
                                             (take batch-size))
                                       (event-store/read event-store read-args))
                              batch-result-events (atom [])
                              last-batch-event-id (atom nil)]
                          (doseq [event events]
                            (when @running
                              (if (and lease-check-fn
                                       (not (lease-check-fn tenant-id processor-name)))
                                (u/log ::lease-check-skipped :tenant-id tenant-id)
                                (let [context {:event event
                                               :handler-fn handler-fn
                                               :event-store event-store
                                               :tenant-id tenant-id}
                                      result (or (handler-fn context)
                                                 {})]
                                  (if (:result/effect result)
                                    ;; Effect handler: checkpoint per-event (delegating to process-event)
                                    (process-event (cond-> (assoc context
                                                             :processor-name processor-name)
                                                     lease-check-fn (assoc :lease-check-fn lease-check-fn)))
                                    ;; Pure handler: collect result events for batch checkpoint
                                    (when-let [revents (:result/events result)]
                                      (swap! batch-result-events into revents)))))
                              (reset! last-batch-event-id (:event/id event))))
                          ;; After batch: one checkpoint for all pure results
                          (when @last-batch-event-id
                            (append-with-checkpoint event-store tenant-id processor-name
                                                    @last-batch-event-id
                                                    @batch-result-events)
                            (reset! last-id @last-batch-event-id)))
                        (catch Throwable t
                          (u/log ::poll-processor-error :exception t)))
                      (Thread/sleep poll-interval-ms)))))]
    (.setDaemon thread true)
    (.setName thread (str "grain-poll-" (name processor-name)))
    (.start thread)
    {:running running :thread thread}))

(defn stop-polling
  "Stop a poll-based processor."
  [{:keys [running thread]}]
  (when running
    (reset! running false))
  (when thread
    (.join thread 2000)))

;; --------------------------------- ;;
;; Coalesced tenant poller           ;;
;; --------------------------------- ;;

(defn start-tenant-poller
  "Start a single poll loop that services all owned tenants.
   Checks which tenants have new events, reads only those, dispatches
   handlers via a shared thread pool, batch checkpoints per tenant.

   config keys:
     :event-store      - the event store instance
     :tenant-ids       - set of tenant UUIDs this poller is responsible for (atom or set)
     :poll-interval-ms - poll frequency (default 250)
     :batch-size       - max events per tenant per cycle (default 100)
     :thread-pool-size - handler dispatch pool size (default 32)"
  [{:keys [event-store tenant-ids poll-interval-ms batch-size thread-pool-size]
    :or {poll-interval-ms 250
         batch-size 100
         thread-pool-size 32}}]
  (let [running (atom true)
        ;; Local watermarks: {tenant-id -> last-processed-event-id}
        watermarks (atom {})
        ;; Thread pool for handler dispatch
        pool (java.util.concurrent.Executors/newFixedThreadPool thread-pool-size)
        registry-snapshot (fn [] @processor-registry*)
        ;; Resolve tenant-ids (can be atom or set)
        owned-tenants (fn [] (if (instance? clojure.lang.Atom tenant-ids)
                               @tenant-ids
                               tenant-ids))
        poll-thread
        (Thread.
         (fn []
           (u/log ::tenant-poller-started :tenant-count (count (owned-tenants))
                  :poll-interval-ms poll-interval-ms :batch-size batch-size
                  :thread-pool-size thread-pool-size)
           ;; Initialize watermarks from checkpoints
           (let [registry (registry-snapshot)]
             (doseq [tid (owned-tenants)
                     [proc-name _] registry]
               (when-let [last-id (get-last-processed-id event-store tid proc-name)]
                 (swap! watermarks assoc-in [tid proc-name] last-id))))
           (while @running
             (try
               (let [tids (owned-tenants)
                     registry (registry-snapshot)]
                 (doseq [tid tids]
                   (when @running
                     (doseq [[proc-name proc-config] registry]
                       (when @running
                         (let [wm (get-in @watermarks [tid proc-name])
                               read-args (cond-> {:tenant-id tid
                                                   :types (set (:topics proc-config))}
                                           wm (assoc :after wm))
                               events (into []
                                        (comp (remove #(= :grain/tx (:event/type %)))
                                              (take batch-size))
                                        (event-store/read event-store read-args))]
                           (when (seq events)
                             ;; Process events — handlers run on the thread pool
                             (let [batch-result-events (atom [])
                                   handler-fn (:handler-fn proc-config)
                                   latch (java.util.concurrent.CountDownLatch. (count events))]
                               ;; Submit each event to the pool
                               (doseq [event events]
                                 (.submit pool
                                   ^Runnable
                                   (fn []
                                     (try
                                       (let [ctx {:event event
                                                  :handler-fn handler-fn
                                                  :event-store event-store
                                                  :tenant-id tid}
                                             result (or (handler-fn ctx) {})]
                                         (if (:result/effect result)
                                           ;; Effect handler: per-event checkpoint
                                           (process-event (assoc ctx :processor-name proc-name))
                                           ;; Pure handler: collect results
                                           (when-let [revents (:result/events result)]
                                             (swap! batch-result-events into revents))))
                                       (catch Throwable t
                                         (u/log ::poller-handler-error :exception t))
                                       (finally
                                         (.countDown latch))))))
                               ;; Wait for all handlers in this batch to complete
                               (.await latch 30 java.util.concurrent.TimeUnit/SECONDS)
                               ;; Batch checkpoint for this tenant+processor
                               (let [last-event-id (:event/id (last events))]
                                 (append-with-checkpoint event-store tid proc-name
                                                         last-event-id
                                                         @batch-result-events)
                                 (swap! watermarks assoc-in [tid proc-name] last-event-id))))))))))
               (catch Throwable t
                 (u/log ::tenant-poller-error :exception t)))
             (Thread/sleep poll-interval-ms))))]
    (.setDaemon poll-thread true)
    (.setName poll-thread "grain-tenant-poller")
    (.start poll-thread)
    {:running running
     :thread poll-thread
     :pool pool
     :watermarks watermarks}))

(defn stop-tenant-poller
  "Stop the coalesced tenant poller."
  [{:keys [running thread pool]}]
  (when running
    (reset! running false))
  (when thread
    (.join thread 5000))
  (when pool
    (.shutdown pool)
    (.awaitTermination pool 5 java.util.concurrent.TimeUnit/SECONDS)))
