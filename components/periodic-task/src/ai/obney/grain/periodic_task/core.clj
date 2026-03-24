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
  "Global registry of periodic triggers. Maps trigger-name keyword to
   {:schedule config, :handler-fn fn}."
  (atom {}))

(defn register-periodic-trigger!
  "Register a periodic trigger."
  [trigger-name handler-fn opts]
  (swap! periodic-trigger-registry* assoc trigger-name
         (merge {:handler-fn handler-fn} opts)))

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
              handler-fn (:handler-fn config)
              task (chime/chime-at sseq
                     (fn [time]
                       (try
                         (let [domain-tenants (disj (tenant-ids-fn) control-plane-tid)]
                           (doseq [tid domain-tenants]
                             (let [result (handler-fn tid time)]
                               (when-let [events (:result/events result)]
                                 (append-fn
                                   (cond-> {:tenant-id tid :events events}
                                     (:result/cas result) (assoc :cas (:result/cas result))))))))
                         (catch Throwable t
                           (u/log ::periodic-trigger-error :trigger trigger-name :exception t)))))]
          [trigger-name task])))))

(defn stop-periodic-triggers!
  "Stop all running periodic triggers."
  [triggers]
  (doseq [[trigger-name task] triggers]
    (u/log ::stopping-periodic-trigger :trigger trigger-name)
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
