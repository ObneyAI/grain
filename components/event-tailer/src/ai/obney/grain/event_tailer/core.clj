(ns ai.obney.grain.event-tailer.core
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [com.brunobonacci.mulog :as u]))

(defn- active-subscriptions
  [subscriber-counts]
  (into {}
        (keep (fn [[tenant-id type-counts]]
                (let [active-types (->> type-counts
                                        (keep (fn [[event-type n]]
                                                (when (pos? n) event-type)))
                                        set)]
                  (when (seq active-types)
                    [tenant-id active-types]))))
        subscriber-counts))

(defn- tenant-watermark
  [event-store tenant-id]
  (get-in (es/tenants event-store) [tenant-id :tenant/last-event-id]))

(defn- ensure-watermark!
  [{:keys [event-store watermarks]} tenant-id]
  (when-not (contains? @watermarks tenant-id)
    (swap! watermarks
           (fn [wms]
             (if (contains? wms tenant-id)
               wms
               (assoc wms tenant-id (tenant-watermark event-store tenant-id)))))))

(defn- inc-type-counts
  [type-counts event-types]
  (reduce (fn [m event-type]
            (update m event-type (fnil inc 0)))
          (or type-counts {})
          event-types))

(defn- dec-type-counts
  [type-counts event-types]
  (let [updated (reduce (fn [m event-type]
                          (let [n (dec (get m event-type 0))]
                            (if (pos? n)
                              (assoc m event-type n)
                              (dissoc m event-type))))
                        (or type-counts {})
                        event-types)]
    (when (seq updated) updated)))

(defn subscribe!
  [tailer tenant-id event-types]
  (when (and tailer tenant-id (seq event-types))
    (ensure-watermark! tailer tenant-id)
    (swap! (:subscriber-counts tailer)
           update tenant-id inc-type-counts (set event-types)))
  tailer)

(defn unsubscribe!
  [tailer tenant-id event-types]
  (when (and tailer tenant-id (seq event-types))
    (swap! (:subscriber-counts tailer)
           (fn [counts]
             (let [type-counts (dec-type-counts (get counts tenant-id) (set event-types))]
               (if type-counts
                 (assoc counts tenant-id type-counts)
                 (dissoc counts tenant-id))))))
  tailer)

(defn- read-batch
  [{:keys [event-store batch-size watermarks] :as _tailer} tenant-id active-types]
  (let [watermark (get @watermarks tenant-id)
        read-args (cond-> {:tenant-id tenant-id
                           :types active-types}
                    watermark (assoc :after watermark))]
    (into [] (take batch-size) (es/read event-store read-args))))

(defn- publish-batch!
  [{:keys [event-pubsub watermarks]} tenant-id events]
  (when (seq events)
    (run! #(pubsub/pub event-pubsub {:message (assoc % :grain/tenant-id tenant-id)})
          events)
    (swap! watermarks assoc tenant-id (:event/id (last events)))))

(defn- tick!
  [tailer]
  (doseq [[tenant-id active-types] (active-subscriptions @(:subscriber-counts tailer))]
    (when @(:running tailer)
      (try
        (ensure-watermark! tailer tenant-id)
        (publish-batch! tailer tenant-id (read-batch tailer tenant-id active-types))
        (catch Throwable t
          (u/log ::tailer-tick-error
                 :tenant-id tenant-id
                 :event-types active-types
                 :exception t))))))

(defn start
  [{:keys [event-store event-pubsub poll-interval-ms batch-size]
    :or {poll-interval-ms 250
         batch-size 100}}]
  (when-not event-store
    (throw (ex-info "event-tailer requires :event-store" {})))
  (when-not event-pubsub
    (throw (ex-info "event-tailer requires :event-pubsub" {})))
  (let [tailer {:event-store event-store
                :event-pubsub event-pubsub
                :poll-interval-ms poll-interval-ms
                :batch-size batch-size
                :running (atom true)
                :watermarks (atom {})
                :subscriber-counts (atom {})}
        thread (Thread.
                (fn []
                  (u/log ::tailer-started
                         :poll-interval-ms poll-interval-ms
                         :batch-size batch-size)
                  (while @(:running tailer)
                    (tick! tailer)
                    (try
                      (Thread/sleep poll-interval-ms)
                      (catch InterruptedException _)))
                  (u/log ::tailer-stopped)))]
    (.setDaemon thread true)
    (.setName thread "grain-event-tailer")
    (.start thread)
    (assoc tailer :thread thread)))

(defn stop
  [{:keys [running thread]}]
  (when running
    (reset! running false))
  (when thread
    (.interrupt thread)
    (.join thread 2000)))
