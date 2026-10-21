(ns ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [com.brunobonacci.mulog.core :as mulog-core]
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

(def ^:private read-model-metrics
  {"ReadModelReadDuration" [:timer :milliseconds :high]
   "ReadModelReadFailed" [:counter :count :standard]
   "ReadModelCatchUpDuration" [:timer :milliseconds :high]
   "ReadModelCatchUpFailed" [:counter :count :standard]
   "ReadModelCommittedEvents" [:counter :count :standard]
   "ReadModelCommittedBatches" [:counter :count :standard]
   "ReadModelEncodedBytesWritten" [:counter :bytes :standard]
   "ReadModelWaitDuration" [:timer :milliseconds :high]
   "ReadModelBatchBackoff" [:counter :count :standard]
   "ReadModelColdBuildDuration" [:timer :milliseconds :high]
   "ReadModelColdBuildFailed" [:counter :count :standard]})

(def ^:private read-model-dimensions
  #{:read-model-operation :read-model-kind :read-model-scope :read-model-outcome
    :read-model-wait-kind :read-model-target :read-model-name})

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
                                          (if (contains? read-model-metrics metric-name)
                                            read-model-dimensions
                                            (get metric-specific-dimensions metric-name #{})))]
                  [metric-name {:metric/type metric-type
                                :metric/unit unit
                                :metric/resolution resolution
                                :metric/dimensions (select-keys dimension-values
                                                                allowed-names)}])))
         (merge existing-grain-metrics read-model-metrics))))

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

(def ^:private max-cloudwatch-number
  (bigint (.shiftLeft java.math.BigInteger/ONE 360)))

(defn- valid-cloudwatch-number? [value]
  (and (finite-number? value)
       (<= (- max-cloudwatch-number) value max-cloudwatch-number)))

(defn- valid-timestamp? [timestamp now-ms]
  (and (integer? timestamp)
       (<= (- now-ms (* 14 24 60 60 1000))
           timestamp
           (+ now-ms (* 2 60 60 1000)))))

(defn- valid-dimension-name? [name]
  (let [value (clojure.core/name name)]
    (and (<= 1 (count value) 250)
         (not= "_aws" value)
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
       (some #(not (Character/isWhitespace ^char %)) value)
       (every? #(<= 32 (int %) 126) value)))

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

(defn- unique-dimension-targets? [dimensions]
  (let [names (map name (keys dimensions))]
    (= (count names) (count (set names)))))

(defn- normalize-dimensions [definition event]
  (if definition
    (let [declared (or (:metric/dimensions definition) {})
          explicit (or (:metric/dimensions event) {})
          top-level (select-keys event (keys declared))
          top-level (if (and (contains? read-model-metrics (:metric/name event))
                             (contains? top-level :read-model-name)
                             (not (contains? (get declared :read-model-name #{}) (:read-model-name top-level))))
                      (dissoc top-level :read-model-name)
                      top-level)
          supplied (merge top-level explicit)]
      (when (and (<= (count supplied) 30)
                 (unique-dimension-targets? supplied)
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
          ;; Fallback supplies semantics only. An actual configured definition
          ;; remains authoritative, including its dimension permissions.
          semantics (or definition
                        (when-let [[type unit resolution] (get read-model-metrics metric-name)]
                          {:metric/type type :metric/unit unit :metric/resolution resolution}))
          direct-value (:metric/value event)
          duration (:mulog/duration event)
          metric-type (or (:metric/type semantics)
                          (when (some? direct-value) :counter)
                          (when (some? duration) :timer))
          unit (or (:metric/unit semantics)
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
          resolution (normalize-resolution semantics event)
          dimensions (normalize-dimensions definition event)]
      (when (and (valid-name? metric-name)
                 (not= "_aws" metric-name)
                 (not-any? #(= metric-name (first %)) dimensions)
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
         :source-offset (or (::source-offset (meta event)) offset)
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
       (group-by (juxt :name :dimensions :unit :resolution :metric-type))
       (mapcat
        (fn [[_ samples]]
          (let [samples (sort-by :source-offset samples)
                sample (last samples)
                timestamp (apply max (map :timestamp samples))
                offsets (set (map :offset samples))
                events (mapv :event samples)]
            (case (:metric-type sample)
              :counter [(assoc sample
                               :value (try (reduce +' (map :value samples))
                                           (catch Exception _ nil))
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

(def ^:private max-document-bytes (* 1024 1024))

(defn- encode-document [config entries]
  (try
    (let [encoded (json/write-str (document-map (:namespace config) entries))]
      (when (<= (alength (.getBytes ^String encoded java.nio.charset.StandardCharsets/UTF_8))
                max-document-bytes)
        {:json encoded :measurements (vec entries)}))
    (catch Exception _ nil)))

(defn- pack-documents [config measurements]
  ;; Check the encoded bytes, including JSON escapes, before accepting an entry.
  ;; A singleton that cannot be encoded is isolated from all its companions.
  (loop [remaining (seq measurements) entries [] encoded nil documents [] invalid 0]
    (if-let [measurement (first remaining)]
      (let [candidate (when (< (count entries) 100)
                        (encode-document config (conj entries measurement)))]
        (cond
          candidate
          (recur (next remaining) (conj entries measurement) candidate documents invalid)

          (seq entries)
          (recur remaining [] nil (conj documents encoded) invalid)

          :else
          (recur (next remaining) [] nil documents
                 (+ invalid (count (:events measurement))))))
      {:documents (cond-> documents encoded (conj encoded)) :invalid-count invalid})))

(defn- encode-documents [config measurements]
  (let [groups (group-by (juxt :timestamp :dimensions) measurements)]
    (reduce
     (fn [result [_ compatible]]
       ;; Entries with the same name cannot share one JSON root target.
       (let [by-name (group-by :name compatible)
             rounds (apply max 0 (map count (vals by-name)))]
         (reduce
          (fn [result round]
            (let [packed (pack-documents config
                                         (keep #(nth % round nil) (vals by-name)))]
              (-> result
                  (update :documents into (:documents packed))
                  (update :invalid-count + (:invalid-count packed)))))
          result (range rounds))))
     {:documents [] :invalid-count 0} groups)))

(defprotocol ^:private RetainBuffer
  (retain-buffer-events [batch events]))

(deftype ConfigurableBuffer [counter entries capacity overflow-policy accepting on-drop on-remove]
  clojure.lang.Counted
  (count [_] (count entries))

  mb/PRingBuffer
  (enqueue [this event]
    (let [next-counter (inc counter)]
      (cond
        (not @accepting) (do (on-drop) this)
        (< (count entries) capacity)
        (ConfigurableBuffer. next-counter (conj entries [next-counter event])
                             capacity overflow-policy accepting on-drop on-remove)
        :else
        (do
          (on-drop)
          (case overflow-policy
            :drop-oldest
            (do
              (on-remove [(second (first entries))])
              (ConfigurableBuffer. next-counter
                                   (conj (vec (rest entries)) [next-counter event])
                                   capacity overflow-policy accepting on-drop on-remove))
            this)))))
  (dequeue [_ offset]
    (let [[removed retained] (split-with #(<= (first %) (or offset 0)) entries)]
      (on-remove (map second removed))
      (ConfigurableBuffer. counter (vec retained) capacity overflow-policy accepting on-drop on-remove)))
  (clear [_]
    (on-remove (map second entries))
    (ConfigurableBuffer. counter [] capacity overflow-policy accepting on-drop on-remove))
  (items [_] entries)

  RetainBuffer
  (retain-buffer-events [_ events]
    (let [retained (java.util.IdentityHashMap.)]
      (doseq [event events] (.put retained event true))
      (on-remove (keep (fn [[_ event]] (when-not (.containsKey retained event) event)) entries))
      ;; Keep original offsets and bypass acceptance: these events are retries,
      ;; not new arrivals after shutdown.
      (ConfigurableBuffer. counter
                           (filterv #(.containsKey retained (second %)) entries)
                           capacity overflow-policy accepting on-drop on-remove))))

(defn- forget-attempts! [attempts events]
  (locking attempts
    (doseq [event events] (.remove ^java.util.IdentityHashMap attempts event))))

(defn- configurable-agent-buffer [config health accepting attempts]
  (agent
   (ConfigurableBuffer. 0 [] (:buffer-capacity config)
                        (:overflow-policy config) accepting
                        #(swap! health update :dropped inc)
                        #(forget-attempts! attempts %))
   :error-mode :continue))

(defn- retain-events [batch events]
  (if (satisfies? RetainBuffer batch)
    (retain-buffer-events batch events)
    (reduce mb/enqueue (mb/clear batch) events)))

(defn- default-output-sink [document]
  (println document)
  :accepted)

(deftype CloudWatchEMFPublisher [config buffer output-sink health attempts accepting drain-deadline]
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
          aggregates (aggregate-measurements valid)
          valid-aggregate? (fn [{:keys [value metric-type]}]
                             (if (= metric-type :timer)
                               (every? valid-cloudwatch-number? value)
                               (valid-cloudwatch-number? value)))
          invalid-aggregates (remove valid-aggregate? aggregates)
          {:keys [documents invalid-count]}
          (encode-documents config (filter valid-aggregate? aggregates))]
      (forget-attempts! attempts (map second invalid))
      (forget-attempts! attempts (mapcat :events invalid-aggregates))
      (swap! health update :invalid + (count invalid) invalid-count
             (reduce + 0 (map #(count (:events %)) invalid-aggregates)))
      (loop [remaining documents]
        (cond
          (empty? remaining)
          (do
            (swap! health assoc :last-successful-flush-at now-ms)
            (retain-events batch []))

          (and @drain-deadline (>= (System/nanoTime) @drain-deadline))
          (retain-events batch (mapcat #(mapcat :events (:measurements %)) remaining))

          :else
          (let [{:keys [json measurements]} (first remaining)
                events (mapcat :events measurements)
                accepted? (try
                            (output-sink json)
                            true
                            (catch Throwable _ false))]
            (if accepted?
              (do
                (forget-attempts! attempts events)
                (swap! health update :published + (count events))
                (recur (rest remaining)))
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
                               (forget-attempts! attempts [event])
                               (swap! health update :dropped inc)
                               false))))
                       events)
                      later-events (mapcat #(mapcat :events (:measurements %)) (rest remaining))]
                  (retain-events batch (concat retryable later-events)))))))))))

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
        attempts (java.util.IdentityHashMap.)
        buffer (configurable-agent-buffer config health accepting attempts)]
    (when-not (valid-namespace? (:namespace config))
      (throw (ex-info "Invalid CloudWatch metric namespace"
                      {:namespace (:namespace config)})))
    (CloudWatchEMFPublisher. config buffer
                             (or (:output-sink config) default-output-sink)
                             health attempts accepting (atom nil))))

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

(defn- drain-publisher
  [publisher-instance before]
  (let [buffer-agent (publisher/agent-buffer publisher-instance)
        config (.-config ^CloudWatchEMFPublisher publisher-instance)
        health (.-health ^CloudWatchEMFPublisher publisher-instance)
        accepting (.-accepting ^CloudWatchEMFPublisher publisher-instance)
        deadline (+ (System/nanoTime) (* 1000000 (:final-drain-timeout config)))
        completed (promise)]
    (reset! (.-drain-deadline ^CloudWatchEMFPublisher publisher-instance) deadline)
    (send-off buffer-agent
              (fn [batch]
                (reset! accepting false)
                (let [remaining
                      (loop [batch batch]
                        (if (and (pos? (count batch)) (< (System/nanoTime) deadline))
                          (recur (publisher/publish publisher-instance batch))
                          batch))]
                  remaining)))
    ;; Deliver completion after the prior action's returned buffer is committed.
    (send-off buffer-agent
              (fn [batch] (deliver completed (count batch)) batch))
    ;; Round up so returning a timeout cannot release an in-flight sink while
    ;; the publisher still considers the deadline open for another document.
    (let [wait-ms (max 0 (long (Math/ceil (/ (- deadline (System/nanoTime)) 1000000.0))))
          finished (deref completed wait-ms ::timeout)
          timed-out? (or (= finished ::timeout) (pos? finished))]
      (when timed-out?
        ;; Never inspect stale agent state and drop an in-flight write here.
        (send-off buffer-agent
                  (fn [batch]
                    (reset! accepting false)
                    (swap! health update :dropped + (count batch))
                    (mb/clear batch))))
      (let [after @health]
        {:published-count (- (:published after) (:published before))
         :invalid-count (- (:invalid after) (:invalid before))
         :failed-count (- (:flush-failed after) (:flush-failed before))
         :dropped-count (- (:dropped after) (:dropped before))
         :timed-out? timed-out?
         :retained-count (when-not timed-out? finished)}))))

(defn final-drain
  "Closes input after already queued enqueues, then drains until the deadline.

   A blocked sink cannot be cancelled safely. On timeout, :retained-count is nil
   because queued or in-flight work has no authoritative remaining count.
   Cleanup runs on the same agent after that write resolves, so each event is
   counted published or dropped, never both. Producers must stop before draining."
  [publisher-instance]
  (drain-publisher publisher-instance
                   @(.-health ^CloudWatchEMFPublisher publisher-instance)))

(defn- merge-log-pairs [pairs]
  (into {} (mapcat (fn [part]
                    (if (sequential? part) (map vec (partition 2 part)) part))
                  pairs)))

(defn start-cloudwatch-emf-publisher!
  "Starts publishing events from the currently bound μ/log logger.

   A source watch forwards new events before μ/log's asynchronous dispatch can
   remove them. μ/log schedules publication using a separate empty source, so
   other publishers sharing the application's logger are unaffected. Stop the
   application's producers first; the returned idempotent stop function removes
   the watch, stops scheduling, then performs a bounded final drain."
  [config]
  (let [publisher-instance (cloudwatch-emf-publisher config)
        source mulog-core/*default-logger*
        buffer-agent (publisher/agent-buffer publisher-instance)
        lifecycle-lock (Object.)
        active? (atom true)
        startup-offsets (atom #{})
        watch-key (Object.)
        forward!
        (fn [items]
          (when (seq items)
            (let [events (keep (fn [[offset pairs]]
                                 (try (with-meta (merge-log-pairs pairs)
                                        {::source-offset offset})
                                      (catch Exception _ nil))) items)]
              (if @active?
                (send buffer-agent (partial reduce mb/enqueue) events)
                ;; A callback from an append concurrent with stop may already be
                ;; waiting for the lifecycle lock. Account for its events without
                ;; queueing work behind the final drain.
                (swap! (.-health ^CloudWatchEMFPublisher publisher-instance)
                       update :dropped + (count events))))))]
    (locking lifecycle-lock
      (add-watch source watch-key
                 (fn [_ _ old-buffer new-buffer]
                   ;; Watches can execute out of source order. Each callback
                   ;; owns only additions in its old/new transition; a shared
                   ;; high-water mark would lose delayed callbacks after μ/log
                   ;; dequeues the source. rseq examines only the new suffix.
                   (try
                     (let [previous-offset (or (ffirst (rseq (mb/items old-buffer))) 0)
                           additions (->> (rseq (mb/items new-buffer))
                                          (take-while #(> (first %) previous-offset))
                                          reverse vec)]
                       (locking lifecycle-lock
                         (forward! (remove #(contains? @startup-offsets (first %)) additions))))
                     (catch Exception _ nil))))
      ;; A callback waiting on this lock may refer to events in the initial
      ;; snapshot. Remember just those bounded source offsets to avoid replay.
      (let [initial-items (vec (mb/items @source))]
        (reset! startup-offsets (set (map first initial-items)))
        (forward! initial-items)))
    (try
      (let [stop-publisher
            (binding [mulog-core/*default-logger* (atom (mb/ring-buffer 1))]
              (u/start-publisher! {:type :inline :publisher publisher-instance}))
            stop-result
            (delay
              (let [before @(.-health ^CloudWatchEMFPublisher publisher-instance)]
                (locking lifecycle-lock
                  (reset! active? false)
                  (remove-watch source watch-key)
                  (stop-publisher))
                (drain-publisher publisher-instance before)))]
        (fn stop-cloudwatch-emf-publisher [] @stop-result))
      (catch Throwable error
        (locking lifecycle-lock
          (reset! active? false)
          (remove-watch source watch-key))
        (throw error)))))
