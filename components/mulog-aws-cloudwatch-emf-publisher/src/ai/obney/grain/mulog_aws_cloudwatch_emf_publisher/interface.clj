(ns ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [com.brunobonacci.mulog.buffer :as mb]
            [com.brunobonacci.mulog.publisher :as publisher]))

(def ^:private default-config
  {:namespace "ObneyAI/InfoSystem"
   :buffer-capacity 10000
   :flush-interval 200
   :overflow-policy :drop-newest
   :max-delivery-attempts 3
   :final-drain-timeout 5000})

(def ^:private units
  {:count "Count"
   :milliseconds "Milliseconds"
   :seconds "Seconds"
   :bytes "Bytes"
   :percent "Percent"
   :none "None"})

(def ^:private existing-grain-metrics
  {"CommandProcessed" [:timer :milliseconds :high]
   "CommandDuration" [:timer :milliseconds :high]
   "CommandSucceeded" [:counter :count :standard]
   "CommandRejected" [:counter :count :standard]
   "CommandAnomaly" [:counter :count :standard]
   "CommandHandlerException" [:counter :count :standard]
   "CommandEventAppendFailed" [:counter :count :standard]
   "CommandCasConflict" [:counter :count :standard]
   "QueryDuration" [:timer :milliseconds :high]
   "QuerySucceeded" [:counter :count :standard]
   "QueryRejected" [:counter :count :standard]
   "QueryAnomaly" [:counter :count :standard]
   "QueryHandlerException" [:counter :count :standard]
   "QueryAuthorizationDenied" [:counter :count :standard]
   "TodoDuration" [:timer :milliseconds :high]
   "TodoSucceeded" [:counter :count :standard]
   "TodoFailed" [:counter :count :standard]
   "TodoHandlerException" [:counter :count :standard]
   "TodoRetry" [:counter :count :standard]
   "TodoRetryExhausted" [:counter :count :standard]
   "TodoCheckpointFailed" [:counter :count :standard]
   "TodoLeaseCheckSkipped" [:counter :count :standard]
   "TodoBacklogDepth" [:gauge :count :standard]
   "TodoOldestPendingAge" [:gauge :seconds :standard]
   "TodoBatchSize" [:gauge :count :standard]
   "TodoPollDuration" [:timer :milliseconds :high]
   "TodoPollError" [:counter :count :standard]
   "TodoInFlight" [:gauge :count :standard]
   "TodoDrainDuration" [:timer :milliseconds :high]
   "TodoDrainTimedOut" [:counter :count :standard]
   "PeriodicTriggered" [:counter :count :standard]
   "PeriodicSucceeded" [:counter :count :standard]
   "PeriodicFailed" [:counter :count :standard]
   "PeriodicDuration" [:timer :milliseconds :high]
   "PeriodicLate" [:counter :count :standard]
   "PeriodicSkipped" [:counter :count :standard]
   "PeriodicTenantCount" [:gauge :count :standard]
   "PeriodicLastSuccessAge" [:gauge :seconds :standard]
   "EventAppendDuration" [:timer :milliseconds :high]
   "EventAppendSucceeded" [:counter :count :standard]
   "EventAppendFailed" [:counter :count :standard]
   "EventAppendEventCount" [:gauge :count :standard]
   "EventReadDuration" [:timer :milliseconds :high]
   "EventReadFailed" [:counter :count :standard]
   "EventReadEventCount" [:gauge :count :standard]
   "EventCasConflict" [:counter :count :standard]
   "EventStoreConnectionFailure" [:counter :count :standard]
   "PostgresPoolActive" [:gauge :count :standard]
   "PostgresPoolIdle" [:gauge :count :standard]
   "PostgresPoolPending" [:gauge :count :standard]
   "PostgresPoolSaturated" [:counter :count :standard]
   "PostgresTransactionFailed" [:counter :count :standard]
   "PostgresNotificationFailed" [:counter :count :standard]
   "TodoProcessed" [:timer :milliseconds :high]
   "ReadModelL1Hit" [:timer :milliseconds :high]
   "ReadModelL1Revalidated" [:timer :milliseconds :high]
   "ReadModelL1Stale" [:timer :milliseconds :high]
   "ReadModelL1Miss" [:timer :milliseconds :high]
   "ReadModelL2Hit" [:timer :milliseconds :high]
   "ReadModelL2Miss" [:timer :milliseconds :high]
   "GrainAppendEvents" [:timer :milliseconds :high]
   "AssignmentCycle" [:timer :milliseconds :standard]
   "HeartbeatEmitted" [:counter :count :standard]
   "LeaseAcquired" [:counter :count :standard]
   "LeaseReleased" [:counter :count :standard]
   "RoutingLocal" [:counter :count :standard]
   "RoutingRemote" [:counter :count :standard]
   "RoutingDegradation" [:counter :count :standard]
   "SQLiteWriteTransaction" [:timer :milliseconds :high]
   "SQLiteAppend" [:timer :milliseconds :high]
   "SQLiteBusyRetry" [:counter :count :high]
   "SQLiteBusyExhausted" [:counter :count :high]
   "SQLiteWriteQueueDepth" [:gauge :count :standard]
   "SQLiteWriteQueueSaturated" [:counter :count :high]
   "SQLiteWriteQueueWait" [:timer :milliseconds :high]
   ;; Legacy names remain registered while older Grain components are supported.
   "ReadModelProcessed" [:timer :milliseconds :high]
   "ReadModelCacheHit" [:timer :milliseconds :high]
   "ReadModelCacheMiss" [:timer :milliseconds :high]
   "TodoStarted" [:counter :count :high]
   "TodoFinished" [:counter :count :high]})

(def ^:private metric-specific-dimensions
  {"CommandDuration" #{:service :command :outcome}
   "CommandSucceeded" #{:service :command :outcome}
   "CommandRejected" #{:service :command :outcome :anomaly-category}
   "CommandAnomaly" #{:service :command :outcome :anomaly-category}
   "CommandHandlerException" #{:service :command :outcome :anomaly-category}
   "CommandEventAppendFailed" #{:service :command :outcome :anomaly-category}
   "CommandCasConflict" #{:service :command :outcome :anomaly-category}
   "QueryDuration" #{:service :query :outcome}
   "QuerySucceeded" #{:service :query :outcome}
   "QueryRejected" #{:service :query :outcome :anomaly-category}
   "QueryAnomaly" #{:service :query :outcome :anomaly-category}
   "QueryHandlerException" #{:service :query :outcome :anomaly-category}
   "QueryAuthorizationDenied" #{:service :query :outcome :anomaly-category}
   "TodoDuration" #{:service :processor :outcome}
   "TodoSucceeded" #{:service :processor :outcome}
   "TodoFailed" #{:service :processor :outcome :failure-class}
   "TodoHandlerException" #{:service :processor :outcome :failure-class}
   "TodoRetry" #{:service :processor :outcome :failure-class}
   "TodoRetryExhausted" #{:service :processor :outcome :failure-class}
   "TodoCheckpointFailed" #{:service :processor :outcome :failure-class}
   "TodoLeaseCheckSkipped" #{:service :processor :outcome}
   "TodoBacklogDepth" #{:service :processor}
   "TodoOldestPendingAge" #{:service :processor}
   "TodoBatchSize" #{:service :processor}
   "TodoPollDuration" #{:service :processor :outcome}
   "TodoPollError" #{:service :processor :outcome :failure-class}
   "TodoInFlight" #{:service :processor}
   "TodoDrainDuration" #{:outcome}
   "TodoDrainTimedOut" #{:outcome}
   "PeriodicTriggered" #{:service :periodic :outcome}
   "PeriodicSucceeded" #{:service :periodic :outcome}
   "PeriodicFailed" #{:service :periodic :outcome :failure-class}
   "PeriodicDuration" #{:service :periodic :outcome}
   "PeriodicLate" #{:service :periodic}
   "PeriodicSkipped" #{:service :periodic :outcome}
   "PeriodicTenantCount" #{:service :periodic}
   "PeriodicLastSuccessAge" #{:service :periodic}
   "EventAppendDuration" #{:backend :operation :outcome}
   "EventAppendSucceeded" #{:backend :operation :outcome}
   "EventAppendFailed" #{:backend :operation :outcome}
   "EventAppendEventCount" #{:backend :operation :outcome}
   "EventReadDuration" #{:backend :operation :outcome}
   "EventReadFailed" #{:backend :operation :outcome}
   "EventReadEventCount" #{:backend :operation :outcome}
   "EventCasConflict" #{:backend :operation :outcome}
   "EventStoreConnectionFailure" #{:backend :operation :outcome}
   "PostgresPoolActive" #{:backend :operation :outcome}
   "PostgresPoolIdle" #{:backend :operation :outcome}
   "PostgresPoolPending" #{:backend :operation :outcome}
   "PostgresPoolSaturated" #{:backend :operation :outcome}
   "PostgresTransactionFailed" #{:backend :operation :outcome}
   "PostgresNotificationFailed" #{:backend :operation :outcome}})

(def ^:private common-dimensions #{:app-name :env})

(defn grain-metric-registry
  "Returns definitions for metrics currently emitted by Grain.

   `dimension-values` maps each permitted dimension keyword to its bounded set
   of deployment values, for example `{:app-name #{\"academy\"} :env #{\"prod\"}}`."
  ([] (grain-metric-registry {}))
  ([dimension-values]
   (into {}
         (map (fn [[metric-name [metric-type unit resolution]]]
                (let [allowed-names (into common-dimensions
                                          (get metric-specific-dimensions metric-name #{}))]
                  [metric-name {:metric/type metric-type
                                :metric/unit unit
                                :metric/resolution resolution
                                :metric/dimensions (select-keys dimension-values
                                                                allowed-names)}])))
         existing-grain-metrics)))

(defn extend-grain-metric-registry
  "Builds a publisher registry containing Grain metrics and consumer-owned metrics.

   Consumer definitions use the same shape as publisher `:metric-registry`
   entries. Their names must be disjoint from Grain's names so an application
   cannot accidentally change a framework metric's stable semantics."
  ([consumer-metrics]
   (extend-grain-metric-registry {} consumer-metrics))
  ([dimension-values consumer-metrics]
   (let [grain-metrics (grain-metric-registry dimension-values)
         conflicts (set (filter #(contains? grain-metrics %) (keys consumer-metrics)))]
     (when (seq conflicts)
       (throw (ex-info "Consumer metrics must not override Grain metric definitions"
                       {:conflicting-metric-names conflicts})))
     (merge grain-metrics consumer-metrics))))

(defn- finite-number? [value]
  (and (number? value)
       (Double/isFinite (double value))))

(defn- valid-cloudwatch-number? [value]
  ;; Double's finite range is narrower than CloudWatch's documented ±2^360
  ;; range, so finite doubles need no additional magnitude check here.
  (finite-number? value))

(defn- valid-timestamp? [timestamp now-ms]
  (and (integer? timestamp)
       (<= (- now-ms (* 14 24 60 60 1000))
           timestamp
           (+ now-ms (* 2 60 60 1000)))))

(defn- valid-dimension-name? [name]
  (let [value (clojure.core/name name)]
    (and (<= 1 (count value) 255)
         (not (str/starts-with? value ":"))
         (some #(not (Character/isWhitespace ^char %)) value)
         (every? #(and (<= (int %) 127)
                       (not (Character/isISOControl ^char %)))
                 value))))

(defn- valid-dimension-value? [value]
  (and (string? value)
       (<= 1 (count value) 1024)
       (some #(not (Character/isWhitespace ^char %)) value)
       (every? #(and (<= (int %) 127)
                     (not (Character/isISOControl ^char %)))
               value)))

(defn- valid-name? [value]
  (and (string? value)
       (<= 1 (count value) 255)
       (some #(not (Character/isWhitespace ^char %)) value)))

(defn- valid-namespace? [value]
  (and (valid-name? value)
       (every? #(contains? (set "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-_/#: ") %)
               value)))

(defn- definition-for [config event]
  (get-in config [:metric-registry (:metric/name event)]))

(defn- normalize-resolution [definition event]
  (let [event-resolution (:metric/resolution event)
        definition-resolution (:metric/resolution definition)
        requested (or event-resolution definition-resolution)]
    (if definition
      (when (or (nil? event-resolution)
                (= event-resolution definition-resolution)
                (and (= event-resolution :low)
                     (= definition-resolution :standard)))
        (case requested
          :high 1
          (:standard :low nil) 60
          nil))
      (case requested
        :low 60
        (:high nil) 1
        :standard 60
        nil))))

(defn- normalize-dimensions [definition event]
  (if definition
    (let [declared (or (:metric/dimensions definition) {})
          explicit (or (:metric/dimensions event) {})
          supplied (merge (select-keys event (keys declared)) explicit)]
      (when (and (<= (count supplied) 30)
                 (every? #(contains? declared %) (keys explicit))
                 (every? (fn [[dimension value]]
                           (and (valid-dimension-name? dimension)
                                (valid-dimension-value? value)
                                (contains? (get declared dimension #{}) value)))
                         supplied))
        (->> supplied
             (map (fn [[dimension value]] [(name dimension) value]))
             (sort-by first)
             vec)))
    (let [legacy (select-keys event [:app-name :env])]
      (when (every? (fn [[dimension value]]
                      (and (valid-dimension-name? dimension)
                           (valid-dimension-value? value)))
                    legacy)
        (->> legacy
             (map (fn [[dimension value]] [(name dimension) value]))
             (sort-by first)
             vec)))))

(defn- measurement [config now-ms offset event]
  (try
    (let [metric-name (:metric/name event)
          definition (definition-for config event)
          direct-value (:metric/value event)
          duration (:mulog/duration event)
          metric-type (or (:metric/type definition)
                          (when (some? direct-value) :counter)
                          (when (some? duration) :timer))
          unit (or (:metric/unit definition)
                   (when (some? direct-value) :count)
                   (when (some? duration) :milliseconds))
          value (if (= metric-type :timer)
                  (when (finite-number? duration)
                    (case unit
                      :milliseconds (/ (double duration) 1e6)
                      :seconds (/ (double duration) 1e9)
                      nil))
                  direct-value)
          timestamp (or (:mulog/timestamp event) now-ms)
          resolution (normalize-resolution definition event)
          dimensions (normalize-dimensions definition event)]
      (when (and (valid-name? metric-name)
                 (contains? #{:counter :timer :gauge} metric-type)
                 (contains? units unit)
                 (or (nil? (:metric/type event))
                     (= metric-type (:metric/type event)))
                 (or (nil? (:metric/unit event))
                     (= unit (:metric/unit event)))
                 resolution
                 dimensions
                 (valid-cloudwatch-number? value)
                 (or (= metric-type :gauge) (not (neg? value)))
                 (valid-timestamp? timestamp now-ms))
        {:offset offset
         :event event
         :name metric-name
         :value value
         :metric-type metric-type
         :unit unit
         :resolution resolution
         :dimensions dimensions
         :timestamp timestamp}))
    (catch Throwable _ nil)))

(defn- aggregate-measurements [measurements]
  (->> measurements
       (group-by (juxt :name :dimensions :unit :resolution))
       (mapcat
        (fn [[_ samples]]
          (let [sample (last samples)
                timestamp (apply max (map :timestamp samples))
                offsets (set (map :offset samples))
                events (mapv :event samples)]
            (case (:metric-type sample)
              :counter [(assoc sample
                               :value (reduce + (map :value samples))
                               :timestamp timestamp
                               :offsets offsets
                               :events events)]
              :gauge [(assoc sample
                             :timestamp timestamp
                             :offsets offsets
                             :events events)]
              :timer (mapv (fn [values]
                             (assoc sample
                                    :value (mapv :value values)
                                    :timestamp (apply max (map :timestamp values))
                                    :offsets (set (map :offset values))
                                    :events (mapv :event values)))
                           (partition-all 100 samples))))))
       (sort-by (juxt :timestamp :name))))

(defn- document-map [namespace measurements]
  (let [sample (first measurements)
        dimensions (:dimensions sample)
        metric-definitions
        (mapv (fn [{:keys [name unit resolution]}]
                {:Name name
                 :Unit (units unit)
                 :StorageResolution resolution})
              measurements)
        base {:_aws {:Timestamp (:timestamp sample)
                     :CloudWatchMetrics
                     [{:Namespace namespace
                       :Dimensions [(mapv first dimensions)]
                       :Metrics metric-definitions}]}}
        with-dimensions (reduce (fn [result [dimension value]]
                                  (assoc result dimension value))
                                base
                                dimensions)]
    (reduce (fn [result {:keys [name value]}]
              (assoc result name value))
            with-dimensions
            measurements)))

(defn- encode-documents [config measurements]
  (->> measurements
       (group-by (juxt :timestamp :dimensions))
       (mapcat
        (fn [[_ compatible]]
          ;; Entries with the same name cannot share one JSON root target.
          (let [by-name (group-by :name compatible)
                rounds (apply max 0 (map count (vals by-name)))]
            (mapcat
             (fn [round]
               (->> by-name
                    vals
                    (keep #(nth % round nil))
                    (partition-all 100)
                    (map (fn [entries]
                           {:json (json/write-str
                                  (document-map (:namespace config) entries))
                            :measurements (vec entries)}))))
             (range rounds)))))))

(deftype ConfigurableBuffer [counter entries capacity overflow-policy accepting on-drop]
  clojure.lang.Counted
  (count [_] (count entries))

  mb/PRingBuffer
  (enqueue [this event]
    (let [next-counter (inc counter)]
      (if-not @accepting
        (do (on-drop) this)
        (if (< (count entries) capacity)
        (ConfigurableBuffer. next-counter (conj entries [next-counter event])
                             capacity overflow-policy accepting on-drop)
        (do
          (on-drop)
          (case overflow-policy
            :drop-oldest
            (ConfigurableBuffer. next-counter
                                 (conj (vec (rest entries)) [next-counter event])
                                 capacity overflow-policy accepting on-drop)
            this))))))
  (dequeue [_ offset]
    (ConfigurableBuffer. counter
                         (vec (remove #(<= (first %) (or offset 0)) entries))
                         capacity overflow-policy accepting on-drop))
  (clear [_]
    (ConfigurableBuffer. counter [] capacity overflow-policy accepting on-drop))
  (items [_] entries))

(defn- configurable-agent-buffer [config health accepting]
  (agent
   (ConfigurableBuffer. 0 [] (:buffer-capacity config)
                        (:overflow-policy config) accepting
                        #(swap! health update :dropped inc))
   :error-mode :continue))

(defn- retain-events [batch events]
  (reduce mb/enqueue (mb/clear batch) events))

(defn- default-output-sink [document]
  (println document)
  :accepted)

(deftype CloudWatchEMFPublisher [config buffer output-sink health attempts accepting]
  publisher/PPublisher
  (agent-buffer [_] buffer)
  (publish-delay [_] (:flush-interval config))
  (publish [this batch]
    (let [now-ms (System/currentTimeMillis)
          items (mb/items batch)
          classified (mapv (fn [[offset event]]
                             [offset event (measurement config now-ms offset event)])
                           items)
          invalid (filterv (comp nil? #(nth % 2)) classified)
          valid (vec (keep #(nth % 2) classified))
          documents (encode-documents config (aggregate-measurements valid))]
      (swap! health update :invalid + (count invalid))
      (loop [remaining documents
             retained []]
        (if-let [{:keys [json measurements]} (first remaining)]
          (let [events (mapcat :events measurements)
                accepted? (try
                            (output-sink json)
                            true
                            (catch Throwable _ false))]
            (if accepted?
              (do
              (doseq [event events]
                (locking attempts (.remove ^java.util.IdentityHashMap attempts event)))
              (swap! health update :published + (count events))
                (recur (rest remaining) retained))
              (do
                (swap! health update :flush-failed inc)
                (let [retryable
                      (filterv
                       (fn [event]
                         (let [attempt (locking attempts
                                         (let [next-attempt
                                               (inc (or (.get ^java.util.IdentityHashMap attempts event) 0))]
                                           (.put ^java.util.IdentityHashMap attempts event next-attempt)
                                           next-attempt))]
                           (if (< attempt (:max-delivery-attempts config))
                             true
                             (do
                               (locking attempts
                                 (.remove ^java.util.IdentityHashMap attempts event))
                               (swap! health update :dropped inc)
                               false))))
                       events)
                      later-events (mapcat (fn [document]
                                             (mapcat :events (:measurements document)))
                                           (rest remaining))]
                  (retain-events batch (concat retained retryable later-events))))))
          (do
            (swap! health assoc :last-successful-flush-at now-ms)
            (retain-events batch retained)))))))

(defn cloudwatch-emf-publisher
  "Creates a configurable CloudWatch EMF publisher.

   `:output-sink` receives one EMF JSON string and returns normally when the
   local write is accepted. Throwing retains the affected measurements for a
   bounded retry."
  [provided-config]
  (let [config (merge default-config provided-config)
        health (atom {:published 0 :dropped 0 :invalid 0 :flush-failed 0
                      :last-successful-flush-at nil})
        accepting (atom true)
        buffer (configurable-agent-buffer config health accepting)]
    (when-not (valid-namespace? (:namespace config))
      (throw (ex-info "Invalid CloudWatch metric namespace"
                      {:namespace (:namespace config)})))
    (CloudWatchEMFPublisher. config buffer
                             (or (:output-sink config) default-output-sink)
                             health (java.util.IdentityHashMap.) accepting)))

(defn publisher-health
  "Returns health independently of the configured metric output sink."
  [publisher-instance]
  (let [buffer-agent (publisher/agent-buffer publisher-instance)
        buffered (count @buffer-agent)
        config (.-config ^CloudWatchEMFPublisher publisher-instance)
        totals @(.-health ^CloudWatchEMFPublisher publisher-instance)]
    {:buffered buffered
     :published (:published totals)
     :dropped (:dropped totals)
     :invalid (:invalid totals)
     :flush-failed (:flush-failed totals)
     :buffer-utilization (/ (double buffered) (:buffer-capacity config))
     :last-successful-flush-at (:last-successful-flush-at totals)}))

(defn final-drain
  "Stops accepting no external source itself, but performs a deadline-bounded
   drain of the publisher's current μ/log buffer and accounts for leftovers."
  [publisher-instance]
  (let [buffer-agent (publisher/agent-buffer publisher-instance)
        config (.-config ^CloudWatchEMFPublisher publisher-instance)
        health (.-health ^CloudWatchEMFPublisher publisher-instance)
        accepting (.-accepting ^CloudWatchEMFPublisher publisher-instance)
        before @health
        timeout (:final-drain-timeout config)
        deadline (+ (System/currentTimeMillis) timeout)]
    (reset! accepting false)
    (loop []
      (when (and (pos? (count @buffer-agent))
                 (< (System/currentTimeMillis) deadline))
        (send buffer-agent #(publisher/publish publisher-instance %))
        (await-for (max 1 (- deadline (System/currentTimeMillis))) buffer-agent)
        (recur)))
    (when (pos? (count @buffer-agent))
      (let [remaining (count @buffer-agent)]
        (swap! health update :dropped + remaining)
        (send buffer-agent mb/clear)))
    (let [after @health]
      {:published-count (- (:published after) (:published before))
       :invalid-count (- (:invalid after) (:invalid before))
       :failed-count (- (:flush-failed after) (:flush-failed before))
       :retained-count 0})))

(defn start-cloudwatch-emf-publisher!
  "Starts the Grain EMF publisher through μ/log and returns a lifecycle-safe
   stop function. Stopping first performs the bounded final drain, then
   deregisters and stops μ/log's recurring publisher task."
  [config]
  (let [publisher-instance (cloudwatch-emf-publisher config)
        stop-publisher (u/start-publisher! {:type :inline
                                            :publisher publisher-instance})]
    (fn stop-cloudwatch-emf-publisher []
      (let [drain-result (final-drain publisher-instance)]
        (stop-publisher)
        drain-result))))
