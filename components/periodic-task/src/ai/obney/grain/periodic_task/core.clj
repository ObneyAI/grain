(ns ai.obney.grain.periodic-task.core
  (:require [chime.core :as chime]
            [com.brunobonacci.mulog :as u])
  (:import [java.time Instant Duration ZonedDateTime ZoneId]
           [com.cronutils.model CronType]
           [com.cronutils.model.definition CronDefinitionBuilder]
           [com.cronutils.model.time ExecutionTime]
           [com.cronutils.parser CronParser]))

(defn- cron-seq
  "Given a cron expression string and a ZoneId, returns a lazy sequence
   of java.time.Instant representing future execution times."
  [cron-expr zone-id]
  (let [cron-def  (CronDefinitionBuilder/instanceDefinitionFor CronType/UNIX)
        parser    (CronParser. cron-def)
        cron      (.parse parser cron-expr)
        exec-time (ExecutionTime/forCron cron)]
    (letfn [(next-times [zdt]
              (let [opt-next (.nextExecution exec-time zdt)]
                (when (.isPresent opt-next)
                  (let [next-zdt (.get opt-next)]
                    (lazy-seq
                      (cons (.toInstant next-zdt)
                            (next-times next-zdt)))))))]
      (next-times (ZonedDateTime/now zone-id)))))

(defn- periodic-seq-from-config
  [{:keys [every duration]}]
  (chime/periodic-seq
    (Instant/now)
    (case duration
      :seconds (Duration/ofSeconds every)
      :minutes (Duration/ofMinutes every)
      :hours   (Duration/ofHours every))))

(defn- schedule-seq
  "Dispatches on schedule config to produce a lazy seq of Instants."
  [{:keys [cron timezone every] :as schedule}]
  (cond
    cron  (cron-seq cron (if timezone
                           (ZoneId/of timezone)
                           (ZoneId/systemDefault)))
    every (periodic-seq-from-config schedule)
    :else (throw (ex-info "Invalid schedule config: must contain :cron or :every/:duration"
                          {:schedule schedule}))))

(defn start [{:keys [handler-fn _task-name schedule] :as args}]
  (u/log ::starting-periodic-task ::args args)
  (let [sseq (schedule-seq schedule)]
    {::task (chime/chime-at sseq handler-fn)
     ::args args}))

(defn stop [{::keys [task args]}]
  (u/log ::stopping-periodic-task ::args args)
  (.close task))

;; --------------------------------- ;;
;; Periodic trigger registry         ;;
;; --------------------------------- ;;

(def periodic-trigger-registry*
  "Global registry of periodic triggers. Maps trigger-name keyword to its
   declaration config and, while started, node-local runtime state."
  (atom {}))

(defn- update-trigger-runtime!
  [trigger-name runtime-state]
  (swap! periodic-trigger-registry*
         (fn [registry]
           (if (contains? registry trigger-name)
             (update registry trigger-name merge runtime-state)
             registry))))

(defn- running-trigger?
  [trigger-name]
  (true? (get-in @periodic-trigger-registry* [trigger-name :running?])))

(defn next-fire-at
  "Return the node-local Instant currently armed for a running trigger.
   Returns nil when the trigger is unknown, unstarted, stopped, or firing."
  [trigger-name]
  (let [{:keys [running? next-fire-at]} (get @periodic-trigger-registry* trigger-name)]
    (when running? next-fire-at)))

(defn- tracked-schedule
  [trigger-name times]
  (map (fn [time]
         (when (running-trigger? trigger-name)
           (update-trigger-runtime! trigger-name {:next-fire-at time}))
         time)
       times))

(defn register-periodic-trigger!
  "Register a periodic trigger."
  [trigger-name handler-fn opts]
  (swap! periodic-trigger-registry* assoc trigger-name
         (merge {:handler-fn handler-fn} opts)))

(defn- periodic-dimensions [trigger-name & [outcome failure-class]]
  (cond-> {:service (or (namespace trigger-name) "unqualified")
           :periodic (str trigger-name)}
    outcome (assoc :outcome outcome)
    failure-class (assoc :failure-class failure-class)))

(defn- emit-periodic-counter! [metric-name trigger-name outcome & [failure-class]]
  (u/log :metric/metric
         :metric/name metric-name
         :metric/value 1
         :metric/resolution :low
         :metric/dimensions (periodic-dimensions trigger-name outcome failure-class)))

(defn start-periodic-triggers!
  "Start all registered periodic triggers. Each trigger runs on a chime schedule.
   On each tick, the handler is called for each tenant with (tenant-id, time).
   The handler returns {:result/events [...] :result/cas {...}}.
   The framework appends the events with the CAS predicate.

   event-store-fns: {:append-fn (fn [args] ...)
                     :tenant-ids-fn (fn [] ...)}

   Returns a map of {trigger-name -> chime-task} that can be stopped."
  [{:keys [append-fn tenant-ids-fn]}]
  (let [registry @periodic-trigger-registry*
        control-plane-tid #uuid "00000000-0000-0000-0000-000000000001"]
    (into {}
      (for [[trigger-name config] registry]
        (let [sseq (schedule-seq (:schedule config))
              handler-fn (:handler-fn config)]
          (update-trigger-runtime! trigger-name {:running? true :next-fire-at nil})
          (try
            (let [task (chime/chime-at (tracked-schedule trigger-name sseq)
                         (fn [time]
                           (update-trigger-runtime! trigger-name {:next-fire-at nil})
                           (let [started-at (System/nanoTime)
                                 last-success-at (:last-success-at
                                                  (get @periodic-trigger-registry* trigger-name))]
                             (emit-periodic-counter! "PeriodicTriggered" trigger-name "triggered")
                             (when last-success-at
                               (u/log :metric/metric
                                      :metric/name "PeriodicLastSuccessAge"
                                      :metric/value (/ (double (- (System/currentTimeMillis)
                                                                  last-success-at))
                                                       1000.0)
                                      :metric/resolution :low
                                      :metric/dimensions (periodic-dimensions trigger-name)))
                           (try
                             (let [domain-tenants (disj (tenant-ids-fn) control-plane-tid)]
                               (u/log :metric/metric
                                      :metric/name "PeriodicTenantCount"
                                      :metric/value (count domain-tenants)
                                      :metric/resolution :low
                                      :metric/dimensions (periodic-dimensions trigger-name))
                               (doseq [tid domain-tenants]
                                 (let [result (handler-fn tid time)]
                                   (when-let [events (:result/events result)]
                                     (append-fn
                                       (cond-> {:tenant-id tid :events events}
                                         (:result/cas result) (assoc :cas (:result/cas result)))))))
                               (update-trigger-runtime! trigger-name
                                                        {:last-success-at (System/currentTimeMillis)})
                               (u/log :metric/metric
                                      :metric/name "PeriodicDuration"
                                      :mulog/duration (- (System/nanoTime) started-at)
                                      :metric/resolution :high
                                      :metric/dimensions
                                      (periodic-dimensions trigger-name "succeeded"))
                               (emit-periodic-counter! "PeriodicSucceeded" trigger-name "succeeded"))
                             (catch Throwable t
                               (u/log ::periodic-trigger-error :trigger trigger-name :exception t)
                               (u/log :metric/metric
                                      :metric/name "PeriodicDuration"
                                      :mulog/duration (- (System/nanoTime) started-at)
                                      :metric/resolution :high
                                      :metric/dimensions
                                      (periodic-dimensions trigger-name "failed"))
                               (emit-periodic-counter! "PeriodicFailed" trigger-name
                                                       "failed" "exception"))))))]
              [trigger-name task])
            (catch Throwable t
              (update-trigger-runtime! trigger-name {:running? false :next-fire-at nil})
              (throw t))))))))

(defn stop-periodic-triggers!
  "Stop all running periodic triggers."
  [triggers]
  (doseq [[trigger-name task] triggers]
    (u/log ::stopping-periodic-trigger :trigger trigger-name)
    (update-trigger-runtime! trigger-name {:running? false :next-fire-at nil})
    (.close task)))


(comment

  (def task
    (start
     {:schedule {:every 1 :duration :seconds}
      :handler-fn (fn [_time] (println "HELLO"))
      :task-name ::hello-world-task}))

  (def cron-task
    (start
     {:schedule {:cron "* * * * *"}
      :handler-fn (fn [_time] (println "CRON HELLO"))
      :task-name ::cron-hello-world-task}))

  (stop task)
  (stop cron-task)

  ""
  )
