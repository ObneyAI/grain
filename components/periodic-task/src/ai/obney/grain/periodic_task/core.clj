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
