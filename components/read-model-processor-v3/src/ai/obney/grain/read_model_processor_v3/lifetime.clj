(ns ai.obney.grain.read-model-processor-v3.lifetime
  "Committed-result retention through Datahike's native durable roots."
  (:require [datahike.api :as d]
            [datahike.gc-roots :as roots]
            [clojure.java.io :as io]))

(import '[java.lang.ref WeakReference Reference]
        '[java.util.concurrent Executors ThreadFactory TimeUnit ScheduledFuture]
        '[java.nio.channels FileChannel]
        '[java.nio.file StandardOpenOption])

(defonce snapshot-maintainer
  (Executors/newSingleThreadScheduledExecutor
   (reify ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "grain-projection-maintenance")
         (.setDaemon true))))))

(defn acquire-owner!
  [path]
  (.mkdirs (io/file path))
  (let [channel (FileChannel/open (.toPath (io/file path "grain-owner.lock"))
                                  (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (try
      (if-let [lock (.tryLock channel)]
        {:channel channel :lock lock}
        (throw (ex-info "Projection storage already owned by another process" {:path path})))
      (catch Throwable e
        (.close channel)
        (throw e)))))

(defn release-owner!
  [{:keys [lock channel]}]
  (try
    (.release ^java.nio.channels.FileLock lock)
    (finally
      (.close ^FileChannel channel))))

(defn new-runtime
  [conn context owner on-release]
  {:conn conn
   :context context
   :owner owner
   :on-release on-release
   :lifecycle-gate (Object.)
   :active-requests (atom 0)
   :locks (atom {})
   :maintenance-gate (Object.)
   :state (atom nil)
   :pins (atom {})
   :collected-pins (atom nil)
   :closing? (atom false)
   :released? (atom false)
   :task (atom nil)
   :last-error (atom nil)
   :lease-error (atom nil)
   :failure (atom nil)
   :pin-ttl-ms (atom roots/DEFAULT_TTL_MS)
   :gc-interval-ms (atom 300000)
   :last-gc (atom (System/currentTimeMillis))
   :stats (atom {:sweeps 0 :deleted 0})})

(defn close!
  [rt]
  (locking (:lifecycle-gate rt)
    (reset! (:closing? rt) true)
    (when (zero? @(:active-requests rt))
      (reset! (:state rt) nil))))

(defn with-request
  "Admit work before close. No lifecycle monitor is held while f runs."
  [rt f]
  (locking (:lifecycle-gate rt)
    (when @(:closing? rt)
      (throw (ex-info "Projection store is closed" {:error :invalid-projection-store})))
    (swap! (:active-requests rt) inc))
  (try
    (f)
    (finally
      (locking (:lifecycle-gate rt)
        (when (and (zero? (swap! (:active-requests rt) dec)) @(:closing? rt))
          (reset! (:state rt) nil))))))

(defn with-key-lock
  "Serialize one identity. Count waiters so its monitor can be removed safely."
  [rt key f]
  (let [entry (locking (:lifecycle-gate rt)
                (get (swap! (:locks rt) update key
                            (fn [entry]
                              (update (or entry {:monitor (Object.) :users 0}) :users inc)))
                     key))]
    (try
      (locking (:monitor entry)
        (f))
      (finally
        (locking (:lifecycle-gate rt)
          (swap! (:locks rt)
                 (fn [locks]
                   (if (= 1 (get-in locks [key :users]))
                     (dissoc locks key)
                     (update-in locks [key :users] dec)))))))))

(defn pin-state!
  [s]
  ;; Commit callers keep the native GC guard open until this pin is published.
  (let [rt (:runtime s)
        db (:db s)
        basis (Object.)
        ref (WeakReference. basis)
        id (roots/pin! db {:ttl-ms @(:pin-ttl-ms rt) :note "Grain committed result"} {:sync? true})
        stop (roots/start-renewal! db id
                                   {:on-lost (fn [e]
                                               (when (.get ref)
                                                 (reset! (:lease-error rt) e)))})]
    (swap! (:pins rt) assoc ref {:id id :stop stop})
    (assoc s :basis basis)))

(defn check-scope!
  [s]
  (when-let [scope (:scope s)]
    (when-not (and @(:active scope) (identical? (:thread scope) (Thread/currentThread)))
      (throw (ex-info "Intermediate map is only valid on its reducer thread during reduction"
                      {:error :expired-reduction-map})))))

(defn ensure-reducing!
  [s]
  (check-scope! s)
  (when-not (:scope s)
    (throw (ex-info "Committed projection is read-only; modify maps inside the reducer"
                    {:error :committed-projection-read-only}))))

(defn with-snapshot-state
  "Keep the snapshot's retention token reachable throughout the operation.
   Maintenance cannot release its pin or close storage while this token is live."
  [s f]
  (check-scope! s)
  (if-let [rt (:runtime s)]
    (do
      (when @(:released? rt)
        (throw (ex-info "Snapshot storage has been released" {})))
      (when-let [e @(:lease-error rt)]
        (throw e))
      (try
        (f)
        (finally
          (Reference/reachabilityFence s))))
    (f)))

(defn- maintain-snapshots*
  [rt force?]
  (when-not @(:released? rt)
    (doseq [[ref {:keys [id stop]}] @(:pins rt)
            :when (nil? (.get ^WeakReference ref))]
      (stop)
      (roots/release! (:context rt) id {:sync? true})
      (swap! (:pins rt) dissoc ref))
    (let [ids (set (map :id (vals @(:pins rt))))]
      (when (and (nil? @(:lease-error rt))
                 (or force? (and (not= ids @(:collected-pins rt))
                                 (>= (- (System/currentTimeMillis) @(:last-gc rt)) @(:gc-interval-ms rt)))))
        (doseq [id ids]
          (roots/assert-live! (:context rt) id @(:pin-ttl-ms rt) {:sync? true}))
        (let [deleted @(d/gc-storage (:conn rt) (java.util.Date.) {:min-age-ms 0})]
          (swap! (:stats rt) #(-> % (update :sweeps inc) (update :deleted + (count deleted))
                                  (assoc :roots (count @(:pins rt))))))
        (reset! (:last-error rt) nil)
        (reset! (:collected-pins rt) ids)
        (reset! (:last-gc rt) (System/currentTimeMillis))))
    (locking (:lifecycle-gate rt)
      (when (and @(:closing? rt) (zero? @(:active-requests rt)) (empty? @(:pins rt)))
        (d/release (:conn rt))
        (release-owner! (:owner rt))
        ((:on-release rt))
        (reset! (:released? rt) true)
        (when-let [task @(:task rt)]
          (.cancel ^ScheduledFuture task false))))
    (when-let [e @(:lease-error rt)]
      (reset! (:last-error rt) e))
    @(:stats rt)))

(defn maintain-snapshots!
  ([rt]
   (maintain-snapshots! rt true))
  ([rt force?]
   ;; Serialize maintenance and its error reporting without excluding requests.
   (locking (:maintenance-gate rt)
     (try
       (maintain-snapshots* rt force?)
       (catch Throwable e
         (reset! (:last-error rt) e)
         (throw e))))))

(defn start-maintenance!
  [rt interval-ms]
  ;; Orphan pin expiry and renewal are Datahike's responsibility.
  (let [task (.scheduleWithFixedDelay snapshot-maintainer
                                      ^Runnable (fn []
                                                  (try
                                                    (maintain-snapshots! rt false)
                                                    (catch Throwable _
                                                      nil)))
                                      (long interval-ms) (long interval-ms) TimeUnit/MILLISECONDS)]
    (reset! (:task rt) task)))
