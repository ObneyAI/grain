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
             (process-pure-result context result)))
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
