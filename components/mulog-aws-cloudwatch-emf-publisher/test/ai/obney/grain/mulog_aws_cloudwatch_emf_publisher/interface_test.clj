(ns ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface-test
  (:require [com.brunobonacci.mulog.publisher :as publisher]
            [com.brunobonacci.mulog :as u]
            [com.brunobonacci.mulog.core :as mulog-core]
            [ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface :as sut]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.brunobonacci.mulog.buffer :as buffer]))

(def ^:private timestamp (System/currentTimeMillis))

(defn- publish-events
  ([events]
   (publish-events {} events))
  ([config events]
   (let [instance (sut/cloudwatch-emf-publisher config)
         batch (reduce buffer/enqueue (buffer/ring-buffer (max 1 (count events))) events)
         output (with-out-str (publisher/publish instance batch))]
     {:instance instance
      :records (if (str/blank? output)
                 []
                 (mapv #(json/read-str % :key-fn keyword)
                       (str/split-lines output)))})))

(defn- metric-definition [record]
  (get-in record [:_aws :CloudWatchMetrics 0 :Metrics 0]))

(deftest legacy-counter-and-timer-classification
  (testing "legacy direct values remain Count measurements"
    (let [{:keys [records]}
          (publish-events [{:metric/name "Requests"
                            :metric/value 0
                            :mulog/timestamp timestamp}])]
      (is (= 1 (count records)))
      (is (= "Count" (:Unit (metric-definition (first records)))))
      (is (zero? (:Requests (first records))))))

  (testing "legacy durations are converted from nanoseconds to milliseconds"
    (let [{:keys [records]}
          (publish-events [{:metric/name "Latency"
                            :mulog/duration 2500000
                            :mulog/timestamp timestamp}])]
      (is (= "Milliseconds" (:Unit (metric-definition (first records)))))
      (is (= [2.5] (:Latency (first records)))))))

(deftest configurable-namespace-and-standard-resolution
  (let [{:keys [records]}
        (publish-events
         {:namespace "Grain/Test"
          :metric-registry
          {"QueueDepth" {:metric/type :gauge
                          :metric/unit :count
                          :metric/resolution :standard}}}
         [{:metric/name "QueueDepth"
           :metric/value 4
           :mulog/timestamp timestamp}])
        record (first records)]
    (is (= "Grain/Test"
           (get-in record [:_aws :CloudWatchMetrics 0 :Namespace])))
    (is (= 60 (:StorageResolution (metric-definition record))))))

(deftest registered-units-and-safe-dimensions
  (let [{:keys [records]}
        (publish-events
         {:metric-registry
          {"PayloadSize" {:metric/type :gauge
                           :metric/unit :bytes
                           :metric/resolution :standard
                           :metric/dimensions
                           {:service #{"academy"}
                            :outcome #{"success" "failure"}}}}}
         [{:metric/name "PayloadSize"
           :metric/value 512
           :mulog/timestamp timestamp
           :metric/dimensions {:service "academy"
                               :outcome "success"}}])
        record (first records)]
    (is (= "Bytes" (:Unit (metric-definition record))))
    (is (= #{"service" "outcome"}
           (set (first (get-in record [:_aws :CloudWatchMetrics 0 :Dimensions])))))
    (is (= "academy" (:service record)))
    (is (= "success" (:outcome record)))))

(deftest grain-registry-covers-existing-framework-metrics
  (let [registry (sut/grain-metric-registry
                  {:app-name #{"grain-test"} :env #{"test"}
                   :service #{"example"}
                   :command #{":example/create"}
                   :query #{":example/read"}
                   :processor #{":example/project"}
                   :periodic #{":example/tick"}
                   :backend #{"in-memory" "sqlite" "postgres"}
                   :operation #{"append" "read"}
                   :outcome #{"succeeded" "rejected" "anomaly"}
                   :anomaly-category #{"fault" "forbidden"}
                   :failure-class #{"fault" "exception"}})]
    (is (= {:metric/type :timer
            :metric/unit :milliseconds
            :metric/resolution :high
            :metric/dimensions {:app-name #{"grain-test"} :env #{"test"}}}
           (get registry "CommandProcessed")))
    (is (= :standard (get-in registry ["RoutingLocal" :metric/resolution])))
    (is (= :gauge (get-in registry ["SQLiteWriteQueueDepth" :metric/type])))
    (is (= :timer (get-in registry ["SQLiteWriteQueueWait" :metric/type])))
    (is (= :timer (get-in registry ["CommandDuration" :metric/type])))
    (is (= :counter (get-in registry ["QueryAuthorizationDenied" :metric/type])))
    (is (= :timer (get-in registry ["TodoDuration" :metric/type])))
    (is (= :gauge (get-in registry ["TodoBacklogDepth" :metric/type])))
    (is (= :counter (get-in registry ["PeriodicTriggered" :metric/type])))
    (is (= :seconds (get-in registry ["PeriodicLastSuccessAge" :metric/unit])))
    (is (= :timer (get-in registry ["EventAppendDuration" :metric/type])))
    (is (= :gauge (get-in registry ["EventReadEventCount" :metric/type])))
    (is (= :counter (get-in registry ["PostgresTransactionFailed" :metric/type])))
    (is (= #{:app-name :env :backend :operation :outcome}
           (set (keys (get-in registry ["EventAppendFailed" :metric/dimensions])))))
    (is (= #{:app-name :env :service :command :outcome :anomaly-category}
           (set (keys (get-in registry ["CommandRejected" :metric/dimensions])))))
    (is (not (contains? (get-in registry ["CommandRejected" :metric/dimensions])
                        :query)))))

(deftest consumers-can-extend-the-grain-registry
  (let [custom-definition {:metric/type :counter
                           :metric/unit :count
                           :metric/resolution :standard
                           :metric/dimensions {:service #{"academy"}
                                               :outcome #{"succeeded" "failed"}}}
        registry (sut/extend-grain-metric-registry
                  {:app-name #{"academy"} :env #{"test"}}
                  {"EnrollmentCompleted" custom-definition})
        {:keys [records]}
        (publish-events
         {:metric-registry registry}
         [{:metric/name "EnrollmentCompleted"
           :metric/value 1
           :mulog/timestamp timestamp
           :app-name "academy"
           :env "test"
           :metric/dimensions {:service "academy"
                               :outcome "succeeded"}}])
        record (first records)]
    (is (= custom-definition (get registry "EnrollmentCompleted")))
    (is (contains? registry "CommandDuration"))
    (is (= "Count" (:Unit (metric-definition record))))
    (is (= 60 (:StorageResolution (metric-definition record))))
    (is (= "academy" (:service record)))
    (is (= "succeeded" (:outcome record)))))

(deftest consumers-cannot-override-grain-metric-semantics
  (let [exception (try
                    (sut/extend-grain-metric-registry
                     {"CommandDuration" {:metric/type :counter
                                         :metric/unit :bytes
                                         :metric/resolution :standard}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
    (is (some? exception))
    (is (= #{"CommandDuration"}
           (:conflicting-metric-names (ex-data exception))))))

(deftest registered-global-dimensions-are-published
  (let [{:keys [records]}
        (publish-events
         {:metric-registry
          (sut/grain-metric-registry
           {:app-name #{"grain-test"} :env #{"test"}})}
         [{:metric/name "CommandProcessed"
           :mulog/duration 1000000
           :mulog/timestamp timestamp
           :app-name "grain-test"
           :env "test"}])
        record (first records)]
    (is (= "grain-test" (:app-name record)))
    (is (= "test" (:env record)))))

(deftest registered-timer-unit-controls-duration-normalization
  (let [{:keys [records]}
        (publish-events
         {:metric-registry
          {"Runtime" {:metric/type :timer
                       :metric/unit :seconds
                       :metric/resolution :high}}}
         [{:metric/name "Runtime"
           :mulog/duration 1500000000
           :mulog/timestamp timestamp}])]
    (is (= "Seconds" (:Unit (metric-definition (first records)))))
    (is (= [1.5] (:Runtime (first records))))))

(deftest registered-resolution-cannot-be-overridden
  (is (empty?
       (:records
        (publish-events
         {:metric-registry
          {"QueueDepth" {:metric/type :gauge
                          :metric/unit :count
                          :metric/resolution :standard}}}
         [{:metric/name "QueueDepth"
           :metric/value 1
           :metric/resolution :high
           :mulog/timestamp timestamp}])))))

(deftest invalid-namespace-is-rejected-at-construction
  (is (try
        (sut/cloudwatch-emf-publisher {:namespace "bad\nnamespace"})
        false
        (catch clojure.lang.ExceptionInfo error
          (boolean (re-find #"namespace" (.getMessage error)))))))

(deftest invalid-event-is-isolated-from-valid-events
  (let [result
        (try
          {:publication
           (publish-events
            [{:metric/name "Invalid"
              :metric/value Double/NaN
              :mulog/timestamp timestamp}
             {:metric/name "Valid"
              :metric/value 1
              :mulog/timestamp timestamp}])}
          (catch Throwable error
            {:error error}))]
    (is (nil? (:error result)) "one malformed event must not abort its flush")
    (is (= ["Valid"]
           (mapv #(get-in % [:_aws :CloudWatchMetrics 0 :Metrics 0 :Name])
                 (get-in result [:publication :records]))))))

(deftest compatible-counter-measurements-aggregate
  (let [{:keys [records]}
        (publish-events
         {:metric-registry
          {"Requests" {:metric/type :counter
                        :metric/unit :count
                        :metric/resolution :standard}}}
         [{:metric/name "Requests" :metric/value 2 :mulog/timestamp timestamp}
          {:metric/name "Requests" :metric/value 3 :mulog/timestamp timestamp}])]
    (is (= 1 (count records)))
    (is (= 5 (:Requests (first records))))))

(deftest timer-samples-retain-raw-values
  (let [{:keys [records]}
        (publish-events
         {:metric-registry
          {"Latency" {:metric/type :timer
                       :metric/unit :milliseconds
                       :metric/resolution :high}}}
         [{:metric/name "Latency" :mulog/duration 1000000 :mulog/timestamp timestamp}
          {:metric/name "Latency" :mulog/duration 3000000 :mulog/timestamp timestamp}])]
    (is (= 1 (count records)))
    (is (= [1.0 3.0] (:Latency (first records))))))

(deftest configured-buffer-capacity-and-overflow-policy
  (let [instance (sut/cloudwatch-emf-publisher
                  {:buffer-capacity 2 :overflow-policy :drop-newest})
        agent-buffer (publisher/agent-buffer instance)]
    (doseq [value [1 2 3]]
      (send agent-buffer buffer/enqueue
            {:metric/name "Requests"
             :metric/value value
             :mulog/timestamp timestamp}))
    (await agent-buffer)
    (let [events (mapv second (buffer/items @agent-buffer))]
      (is (= 2 (count events)))
      (is (= [1 2] (mapv :metric/value events))))))

(deftest emf-records-obey-aws-shape
  (let [{:keys [records]}
        (publish-events [{:metric/name "Requests"
                          :metric/value 1
                          :mulog/timestamp timestamp}])
        record (first records)
        directive (get-in record [:_aws :CloudWatchMetrics 0])]
    (is (= timestamp (get-in record [:_aws :Timestamp])))
    (is (string? (:Namespace directive)))
    (is (<= (count (:Dimensions directive)) 1))
    (is (<= (count (:Metrics directive)) 100))
    (is (number? (:Requests record)))))

(deftest registered-value-and-dimension-validation
  (let [config {:metric-registry
                {"Attempts" {:metric/type :counter
                              :metric/unit :count
                              :metric/resolution :standard
                              :metric/dimensions {:outcome #{"success" "failure"}}}}}]
    (testing "negative counter increments are invalid"
      (is (empty?
           (:records
            (publish-events config
                            [{:metric/name "Attempts"
                              :metric/value -1
                              :mulog/timestamp timestamp
                              :metric/dimensions {:outcome "failure"}}])))))
    (testing "dimension values outside the registered vocabulary are invalid"
      (is (empty?
           (:records
            (publish-events config
                            [{:metric/name "Attempts"
                              :metric/value 1
                              :mulog/timestamp timestamp
                              :metric/dimensions {:outcome "student-123"}}])))))
    (testing "unregistered dimensions are not silently attached or ignored"
      (is (empty?
           (:records
            (publish-events config
                            [{:metric/name "Attempts"
                              :metric/value 1
                              :mulog/timestamp timestamp
                              :metric/dimensions {:tenant-id "unbounded"}}])))))))

(deftest injectable-output-sink-is-used
  (let [accepted (atom [])
        sink (fn [document]
               (swap! accepted conj document)
               :accepted)
        {:keys [records]}
        (publish-events {:output-sink sink}
                        [{:metric/name "Requests"
                          :metric/value 1
                          :mulog/timestamp timestamp}])]
    (is (empty? records) "an injected sink replaces stdout")
    (is (= 1 (count @accepted)))))

(deftest public-health-snapshot-covers-independent-accounting
  (let [health-fn (ns-resolve
                   'ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
                   'publisher-health)
        instance (sut/cloudwatch-emf-publisher {})]
    (is (ifn? health-fn) "the fulfilled health contract has a public operation")
    (when health-fn
      (is (= {:buffered 0
              :published 0
              :dropped 0
              :invalid 0
              :flush-failed 0
              :buffer-utilization 0.0
              :last-successful-flush-at nil}
             (health-fn instance))))))

(deftest public-final-drain-publishes-buffered-events
  (let [drain-fn (ns-resolve
                  'ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
                  'final-drain)
        accepted (atom [])
        instance (sut/cloudwatch-emf-publisher
                  {:output-sink #(do (swap! accepted conj %) :accepted)})
        agent-buffer (publisher/agent-buffer instance)]
    (send agent-buffer buffer/enqueue
          {:metric/name "Requests"
           :metric/value 1
           :mulog/timestamp timestamp})
    (await agent-buffer)
    (is (ifn? drain-fn) "the fulfilled final_drain contract has a public operation")
    (when drain-fn
      (let [result (drain-fn instance)]
        (is (= 1 (:published-count result)))
        (is (empty? (buffer/items @(publisher/agent-buffer instance))))
        (is (= 1 (count @accepted)))))))

(deftest lifecycle-wrapper-drains-before-stopping-mulog
  (binding [mulog-core/*default-logger* (atom (buffer/ring-buffer 16))]
   (let [started-publisher (atom nil)
        mulog-stopped (atom false)
        accepted (atom [])]
    (with-redefs [u/start-publisher!
                  (fn [{:keys [type publisher]}]
                    (is (= :inline type))
                    (reset! started-publisher publisher)
                    #(reset! mulog-stopped true))]
      (let [stop (sut/start-cloudwatch-emf-publisher!
                  {:output-sink #(swap! accepted conj %)})
            agent-buffer (publisher/agent-buffer @started-publisher)]
        (send agent-buffer buffer/enqueue
              {:metric/name "Requests"
               :metric/value 1
               :mulog/timestamp timestamp})
        (await agent-buffer)
        (let [result (stop)]
          (is (= 1 (:published-count result)))
          (is (= 1 (count @accepted)))
          (is @mulog-stopped)))))))

(deftest output-failure-retains-only-unaccepted-measurements-for-retry
  (let [attempts (atom 0)
        sink (fn [_]
               (if (= 1 (swap! attempts inc))
                 (throw (ex-info "first write fails" {}))
                 :accepted))
        instance (sut/cloudwatch-emf-publisher
                  {:output-sink sink :max-delivery-attempts 3})
        initial (buffer/enqueue
                 (buffer/ring-buffer 1)
                 {:metric/name "Requests"
                  :metric/value 1
                  :mulog/timestamp timestamp})
        after-failure (binding [*out* (java.io.StringWriter.)]
                        (publisher/publish instance initial))]
    (is (= 1 (count after-failure))
        "a failed local write remains available to retry")
    (let [after-retry (binding [*out* (java.io.StringWriter.)]
                        (publisher/publish instance after-failure))]
      (is (empty? (buffer/items after-retry)))
      (is (= 2 @attempts)))))

(deftest equal-events-have-independent-retry-budgets
  (let [instance (sut/cloudwatch-emf-publisher
                  {:output-sink #(throw (ex-info "write fails" {}))
                   :max-delivery-attempts 2})
        event {:metric/name "Requests"
               :metric/value 1
               :mulog/timestamp timestamp}
        initial (-> (buffer/ring-buffer 2)
                    (buffer/enqueue (into {} event))
                    (buffer/enqueue (into {} event)))
        after-first (publisher/publish instance initial)
        after-second (publisher/publish instance after-first)]
    (is (= 2 (count after-first)))
    (is (zero? (count after-second)))
    (is (= 2 (:dropped (sut/publisher-health instance))))))

(deftest aws-metric-definition-limit-splits-documents
  (let [metric-names (mapv #(str "Metric" %) (range 101))
        registry (into {}
                       (map (fn [name]
                              [name {:metric/type :gauge
                                     :metric/unit :count
                                     :metric/resolution :standard}])
                            metric-names))
        events (mapv (fn [name]
                       {:metric/name name
                        :metric/value 1
                        :mulog/timestamp timestamp})
                     metric-names)
        {:keys [records]} (publish-events {:metric-registry registry} events)]
    (is (= 2 (count records)))
    (is (every? #(<= (count (get-in % [:_aws :CloudWatchMetrics 0 :Metrics])) 100)
                records))))

(deftest aws-numeric-array-limit-splits-timer-samples
  (let [events (mapv (fn [duration]
                       {:metric/name "Latency"
                        :mulog/duration duration
                        :mulog/timestamp timestamp})
                     (range 1000000 102000000 1000000))
        {:keys [records]}
        (publish-events
         {:metric-registry
          {"Latency" {:metric/type :timer
                       :metric/unit :milliseconds
                       :metric/resolution :high}}}
         events)]
    (is (= 2 (count records)))
    (is (every? (fn [record]
                  (let [values (:Latency record)]
                    (and (sequential? values)
                         (<= (count values) 100))))
                records))))


(deftest counter-boundaries-and-invalid-aggregates-are-isolated
  (let [{:keys [instance records]}
        (publish-events
         [{:metric/name "Large" :metric/value Long/MAX_VALUE}
          {:metric/name "Large" :metric/value 1}
          {:metric/name "TooLarge" :metric/value (Math/pow 2 360)}
          {:metric/name "TooLarge" :metric/value (Math/pow 2 360)}
          {:metric/name "Good" :metric/value 1}])
        values (apply merge records)]
    (is (= (inc (bigint Long/MAX_VALUE)) (:Large values)))
    (is (= 1 (:Good values)))
    (is (not (contains? values :TooLarge)))
    (is (= 2 (:invalid (sut/publisher-health instance))))))

(deftest emf-invalid-numbers-and-root-target-collisions-are-isolated
  (doseq [bad [{:metric/name "TooLarge" :metric/value (Math/pow 2 361)}
               {:metric/name "_aws" :metric/value 1}
               {:metric/name "app-name" :metric/value 1 :app-name "app"}
               {:metric/name "Bad\nName" :metric/value 1}
               {:metric/name "BadéName" :metric/value 1}]]
    (let [{:keys [instance records]} (publish-events [bad {:metric/name "Good" :metric/value 1}])]
      (is (= 1 (count records)))
      (is (= 1 (:Good (first records))))
      (is (= 1 (:invalid (sut/publisher-health instance))))))
  (let [{:keys [instance records]}
        (publish-events
         {:metric-registry {"Duplicate" {:metric/type :counter :metric/unit :count
                                         :metric/dimensions {:a/x #{"one"} :b/x #{"two"}}}}}
         [{:metric/name "Duplicate" :metric/value 1
           :metric/dimensions {:a/x "one" :b/x "two"}}])]
    (is (empty? records))
    (is (= 1 (:invalid (sut/publisher-health instance))))))

(deftest retry-state-is-bounded-by-buffered-events
  (let [instance (sut/cloudwatch-emf-publisher
                  {:buffer-capacity 1 :overflow-policy :drop-oldest
                   :output-sink (fn [_] (throw (Exception. "offline")))})
        ab (publisher/agent-buffer instance)]
    (dotimes [i 20]
      (send ab buffer/enqueue {:metric/name "Requests" :metric/value i})
      (send ab #(publisher/publish instance %))
      (is (await-for 2000 ab)))
    (is (<= (.size ^java.util.IdentityHashMap (.-attempts instance)) 1))
    (send ab buffer/clear)
    (is (await-for 2000 ab))
    (is (zero? (.size ^java.util.IdentityHashMap (.-attempts instance))))))

(deftest final-drain-retries-after-input-closes
  (let [attempts (atom 0)
        instance (sut/cloudwatch-emf-publisher
                  {:output-sink (fn [_] (when (= 1 (swap! attempts inc))
                                         (throw (Exception. "temporary"))))})
        ab (publisher/agent-buffer instance)]
    (send ab buffer/enqueue {:metric/name "Requests" :metric/value 1})
    (is (await-for 2000 ab))
    (let [result (sut/final-drain instance)]
      (is (= 2 @attempts))
      (is (= 1 (:published-count result)))
      (is (= 0 (:retained-count result)))
      (is (zero? (:dropped (sut/publisher-health instance)))))))

(deftest final-drain-waits-for-pending-enqueues
  (let [entered (promise) release (promise)
        accepted (atom [])
        instance (sut/cloudwatch-emf-publisher {:output-sink #(swap! accepted conj %)})
        ab (publisher/agent-buffer instance)]
    (try
      (send-off ab (fn [batch] (deliver entered true) (deref release 2000 nil) batch))
      (is (true? (deref entered 1000 nil)))
      (send ab buffer/enqueue {:metric/name "Pending" :metric/value 1})
      (let [draining (future (sut/final-drain instance))]
        (is (= ::waiting (deref draining 30 ::waiting))
            "drain must not return while accepted enqueues are still queued")
        (deliver release true)
        (is (= 1 (:published-count (deref draining 2000 nil))))
        (is (= 1 (count @accepted))))
      (finally (deliver release true)))))

(deftest drain-timeout-does-not-double-account-inflight-delivery
  (let [entered (promise) release (promise)
        instance (sut/cloudwatch-emf-publisher
                  {:final-drain-timeout 30
                   :output-sink (fn [_] (deliver entered true) (deref release 2000 nil))})
        ab (publisher/agent-buffer instance)]
    (try
      (send ab buffer/enqueue {:metric/name "Slow" :metric/value 1})
      ;; Start the blocked write before the short shutdown deadline, so agent
      ;; startup/JIT scheduling cannot skip the write under test.
      (send-off ab #(publisher/publish instance %))
      (is (true? (deref entered 1000 nil)))
      (let [result (sut/final-drain instance)]
        (is (true? (:timed-out? result)))
        (is (nil? (:retained-count result))
            "the input barrier is queued behind the already in-flight sink")
        (is (= 0 (:dropped (sut/publisher-health instance)))))
      (finally (deliver release true)))
    (is (await-for 2000 ab))
    (is (= 1 (:published (sut/publisher-health instance))))
    (is (= 0 (:dropped (sut/publisher-health instance))))
    (is (zero? (:buffered (sut/publisher-health instance))))))

(deftest timeout-during-final-drain-reports-unresolved-inflight-count
  (let [entered (promise) release (promise)
        instance (sut/cloudwatch-emf-publisher
                  {:final-drain-timeout 200
                   :output-sink (fn [_] (deliver entered true) (deref release 2000 nil))})
        ab (publisher/agent-buffer instance)]
    (try
      (send ab buffer/enqueue {:metric/name "Slow" :metric/value 1})
      (is (await-for 1000 ab))
      (let [draining (future (sut/final-drain instance))]
        (is (true? (deref entered 1000 nil)))
        (let [result (deref draining 1000 nil)]
          (is (true? (:timed-out? result)))
          (is (nil? (:retained-count result)))
          (is (zero? (:dropped (sut/publisher-health instance))))))
      (finally (deliver release true)))
    (is (await-for 2000 ab))
    (is (= 1 (:published (sut/publisher-health instance))))
    (is (zero? (:dropped (sut/publisher-health instance))))))

(deftest lifecycle-stop-includes-source-events-before-dispatch
  (let [logger (atom (buffer/ring-buffer 1000))
        accepted (atom [])]
    (binding [mulog-core/*default-logger* logger]
      (let [stop (sut/start-cloudwatch-emf-publisher!
                  {:output-sink #(swap! accepted conj %)})]
        (u/log ::before-stop :metric/name "BeforeStop" :metric/value 1)
        (let [result (stop)]
          (is (= 1 (:published-count result)))
          (is (= 1 (count @accepted)))
          (is (= 1 (:BeforeStop (json/read-str (first @accepted) :key-fn keyword)))))))))

;; This checker reads only emitted JSON and independently checks the relevant
;; AWS EMF constraints; it does not call publisher validation/encoding helpers.
(defn- assert-valid-emf-document [document]
  (let [record (json/read-str document :bigdec true)
        directives (get-in record ["_aws" "CloudWatchMetrics"])
        limit (bigint (.shiftLeft java.math.BigInteger/ONE 360))
        printable? #(and (string? %) (every? (fn [c] (<= 32 (int c) 126)) %))]
    (is (<= (alength (.getBytes ^String document "UTF-8")) 1048576))
    (is (integer? (get-in record ["_aws" "Timestamp"])))
    (is (= 1 (count (re-seq #"\"_aws\":" document))))
    (doseq [directive directives]
      (is (and (printable? (get directive "Namespace"))
               (<= 1 (count (get directive "Namespace")) 255)))
      (is (<= 1 (count (get directive "Metrics")) 100))
      (let [metric-names (map #(get % "Name") (get directive "Metrics"))
            dimension-names (mapcat identity (get directive "Dimensions"))]
        (is (= (count metric-names) (count (set metric-names))))
        (is (empty? (filter (set metric-names) dimension-names)))
        (is (not (contains? (set (concat metric-names dimension-names)) "_aws"))))
      (doseq [dimensions (get directive "Dimensions")]
        (is (<= (count dimensions) 30))
        (is (= (count dimensions) (count (set dimensions))))
        (doseq [dimension dimensions]
          (is (and (printable? dimension) (<= 1 (count dimension) 250)))
          (is (not (str/starts-with? dimension ":")))
          (is (and (printable? (get record dimension))
                   (<= 1 (count (get record dimension)) 1024)))))
      (doseq [metric (get directive "Metrics")]
        (let [name (get metric "Name")
              target (get record name)
              values (if (vector? target) target [target])]
          (is (and (printable? name) (<= 1 (count name) 255)))
          (is (contains? #{1 60} (get metric "StorageResolution")))
          (is (<= 1 (count values) 100))
          (is (every? #(and (number? %) (<= (- limit) % limit)) values)))))))

(deftest exact-numeric-boundaries-remain-valid-after-aggregation
  (let [limit (bigint (.shiftLeft java.math.BigInteger/ONE 360))
        docs (atom [])
        {:keys [instance]}
        (publish-events
         {:output-sink #(swap! docs conj %)
          :metric-registry {"Negative" {:metric/type :gauge :metric/unit :count}}}
         [{:metric/name "Boundary" :metric/value limit}
          {:metric/name "Above" :metric/value (inc limit)}
          {:metric/name "Sum" :metric/value limit}
          {:metric/name "Sum" :metric/value 1}
          {:metric/name "Negative" :metric/value (- (inc limit))}
          {:metric/name "Good" :metric/value 1}])]
    (doseq [document @docs] (assert-valid-emf-document document))
    (is (= 4 (:invalid (sut/publisher-health instance))))
    (is (= #{"Boundary" "Good"}
           (set (mapcat #(map :Name (get-in (json/read-str % :key-fn keyword)
                                           [:_aws :CloudWatchMetrics 0 :Metrics])) @docs))))))

(deftest encoded-size-splits-documents-and-isolates-oversized-singletons
  (let [large-number (bigdec (str "1." (apply str (repeat 20000 "1"))))
        documents (atom [])
        {:keys [instance]}
        (publish-events
         {:output-sink #(swap! documents conj %)}
         (conj (mapv #(hash-map :metric/name (str "Large" %) :metric/value large-number)
                     (range 60))
               {:metric/name "Good" :metric/value 1}))]
    (is (>= (count @documents) 2))
    (doseq [document @documents] (assert-valid-emf-document document))
    (is (= 0 (:invalid (sut/publisher-health instance))))
    (is (= 61 (:published (sut/publisher-health instance))))))

(deftest oversized-singleton-is-isolated-from-valid-companions
  (with-redefs-fn
    {(ns-resolve 'ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
                 'max-document-bytes) 1024}
    (fn []
      (let [documents (atom [])
            {:keys [instance]}
            (publish-events {:output-sink #(swap! documents conj %)}
                            [{:metric/name "Oversized"
                              :metric/value (bigdec (str "1." (apply str (repeat 1500 "1"))))}
                             {:metric/name "Good" :metric/value 1}])]
        (is (= 1 (count @documents)))
        (doseq [document @documents] (assert-valid-emf-document document))
        (is (= 1 (:invalid (sut/publisher-health instance))))
        (is (= 1 (:published (sut/publisher-health instance))))))))

(deftest dimension-boundaries-and-reserved-targets
  (doseq [dimension [(keyword "_aws") (keyword (apply str (repeat 251 "d")))]]
    (let [{:keys [instance records]}
          (publish-events
           {:metric-registry {"Invalid" {:metric/type :counter :metric/unit :count
                                         :metric/dimensions {dimension #{"value"}}}}}
           [{:metric/name "Invalid" :metric/value 1 :metric/dimensions {dimension "value"}}
            {:metric/name "Good" :metric/value 1}])]
      (is (= 1 (count records)))
      (is (= 1 (:Good (first records))))
      (is (= 1 (:invalid (sut/publisher-health instance)))))))

(deftest deadline-reports-unknown-pending-enqueues-honestly
  (let [entered (promise) release (promise)
        instance (sut/cloudwatch-emf-publisher {:final-drain-timeout 30})
        ab (publisher/agent-buffer instance)]
    (try
      (send-off ab (fn [batch] (deliver entered true) (deref release 2000 nil) batch))
      (is (true? (deref entered 1000 nil)))
      (send ab buffer/enqueue {:metric/name "Queued" :metric/value 1})
      (let [result (sut/final-drain instance)]
        (is (true? (:timed-out? result)))
        (is (nil? (:retained-count result)))
        (is (= 0 (:dropped (sut/publisher-health instance)))))
      (finally (deliver release true)))
    (is (await-for 2000 ab))
    (is (= 1 (:dropped (sut/publisher-health instance))))
    (is (= 0 (:published (sut/publisher-health instance))))))

(deftest delayed-source-watch-callbacks-preserve-events-and-source-order
  (let [logger (atom (buffer/ring-buffer 1000))
        entered (promise) release (promise) accepted (atom [])]
    (binding [mulog-core/*default-logger* logger]
      (let [stop (sut/start-cloudwatch-emf-publisher!
                  {:flush-interval 60000
                   :metric-registry {"Depth" {:metric/type :gauge :metric/unit :count}}
                   :output-sink #(swap! accepted conj %)})
            [watch-key original-watch] (first (.getWatches ^clojure.lang.Atom logger))]
        (try
          (add-watch logger watch-key
                     (fn [k a old new]
                       (when (= 1 (ffirst (buffer/items new)))
                         (deliver entered true)
                         (deref release 2000 nil))
                       (original-watch k a old new)))
          (let [first-log (future (u/log ::first :metric/name "Depth" :metric/value 1))]
            (is (true? (deref entered 1000 nil)))
            ;; Model ordinary μ/log dispatch dequeuing the first event while
            ;; that event's watch callback is delayed before its handoff.
            (swap! logger buffer/clear)
            (u/log ::second :metric/name "Depth" :metric/value 2)
            (deliver release true)
            (is (nil? (deref first-log 1000 ::timeout)))
            (let [result (stop)]
              (is (= 2 (:published-count result)))
              (is (= 2 (:Depth (json/read-str (first @accepted) :key-fn keyword))))))
          (finally (deliver release true) (stop)))))))

(deftest shared-source-lifecycle-publishers-and-ordinary-mulog-are-independent
  (let [logger (atom (buffer/ring-buffer 1000))
        first-docs (atom []) second-docs (atom []) ordinary-docs (atom [])
        ordinary (sut/cloudwatch-emf-publisher {:output-sink #(swap! ordinary-docs conj %)})]
    (binding [mulog-core/*default-logger* logger]
      (let [ordinary-stop (u/start-publisher! {:type :inline :publisher ordinary})
            first-stop (sut/start-cloudwatch-emf-publisher! {:output-sink #(swap! first-docs conj %)})
            second-stop (sut/start-cloudwatch-emf-publisher! {:output-sink #(swap! second-docs conj %)})]
        (try
          (u/log ::shared :metric/name "Shared" :metric/value 1)
          (let [first-result (first-stop)]
            (is (= 1 (:published-count first-result)))
            (is (= first-result (first-stop)))
            (is (= 1 (count (.getWatches ^clojure.lang.Atom logger)))))
          (is (= 1 (:published-count (second-stop))))
          (is (empty? (.getWatches ^clojure.lang.Atom logger)))
          ;; The ordinary μ/log publisher must still receive the source event.
          (let [deadline (+ (System/nanoTime) 2000000000)]
            (loop []
              (when (and (empty? @ordinary-docs) (< (System/nanoTime) deadline))
                (Thread/sleep 10)
                (recur))))
          (is (= 1 (count @ordinary-docs)))
          (is (= 1 (count @first-docs)))
          (is (= 1 (count @second-docs)))
          (is (= 1 (:Shared (json/read-str (first @ordinary-docs) :key-fn keyword))))
          (finally (ordinary-stop) (first-stop) (second-stop)))))))

(deftest unchanged-custom-factory-configuration-starts-publishes-and-stops
  (let [logger (atom (buffer/ring-buffer 1000))
        writer (java.io.StringWriter.)
        sink-var (ns-resolve 'ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface
                             'default-output-sink)
        original-sink @sink-var]
    ;; Scheduled μ/log tasks do not inherit test thread bindings. Bind stdout
    ;; at the output boundary while invoking the actual default sink.
    (with-redefs-fn {sink-var (fn [document]
                               (binding [*out* writer] (original-sink document)))}
      (fn []
        (binding [mulog-core/*default-logger* logger]
          ;; The pre-enhancement deployment configuration, unchanged.
          (let [stop (u/start-publisher!
                      {:type :custom :fqn-function #'sut/cloudwatch-emf-publisher})]
            (try
              (u/log ::legacy-counter :metric/name "OldCounter" :metric/value 1)
              (u/log ::legacy-timer :metric/name "OldTimer" :mulog/duration 2500000)
              (u/log ::read-model-bytes :metric/name "ReadModelEncodedBytesWritten" :metric/value 42)
              (u/log ::read-model-read :metric/name "ReadModelReadDuration" :mulog/duration 3000000)
              (let [deadline (+ (System/nanoTime) 2000000000)
                    result
                    (loop []
                      (let [values (try
                                     (apply merge
                                            (map #(json/read-str % :key-fn keyword)
                                                 (str/split-lines (str writer))))
                                     (catch Exception _ nil))]
                        (cond
                          (and (:OldCounter values) (:OldTimer values)
                               (:ReadModelEncodedBytesWritten values) (:ReadModelReadDuration values)) values
                          (< (System/nanoTime) deadline) (do (Thread/sleep 10) (recur))
                          :else values)))]
                (is (= 1 (:OldCounter result)))
                (is (= [2.5] (:OldTimer result)))
                (is (= 42 (:ReadModelEncodedBytesWritten result)))
                (is (= [3.0] (:ReadModelReadDuration result)))
                (let [definitions (into {} (map (juxt :Name identity))
                                        (mapcat #(get-in (json/read-str % :key-fn keyword)
                                                        [:_aws :CloudWatchMetrics 0 :Metrics])
                                                (str/split-lines (str writer))))]
                  (is (= "Bytes" (get-in definitions ["ReadModelEncodedBytesWritten" :Unit])))
                  (is (= 60 (get-in definitions ["ReadModelEncodedBytesWritten" :StorageResolution])))))
              (is (= :stopped (stop)))
              (finally (stop)))))))))

(deftest documented-registry-configuration-publishes-framework-and-custom-events
  (let [readme (slurp (if (.isFile (java.io.File. "components/mulog-aws-cloudwatch-emf-publisher/README.md"))
                       "components/mulog-aws-cloudwatch-emf-publisher/README.md"
                       "README.md"))
        code (second (re-find #"(?s)```clojure\n(.*?)```" readme))
        sandbox (create-ns (gensym "publisher-readme-"))]
    (try
      (let [config (binding [*ns* sandbox]
                     (clojure.core/refer 'clojure.core)
                     (eval (read-string (str "(do " code ")")))
                     (var-get (ns-resolve sandbox 'publisher-config)))
            events [{:metric/name "EnrollmentCompleted" :metric/value 1
                     :metric/dimensions {:service "academy" :outcome "succeeded"}}
                    {:metric/name "CommandDuration" :mulog/duration 1000000
                     :metric/dimensions {:service "academy" :command ":academy/enroll"
                                         :outcome "succeeded"}}
                    {:metric/name "CommandRejected" :metric/value 1
                     :metric/dimensions {:service "academy" :command ":academy/enroll"
                                         :outcome "rejected" :anomaly-category "forbidden"}}
                    {:metric/name "TodoRetry" :metric/value 1
                     :metric/dimensions {:service "academy" :processor ":academy/project"
                                         :outcome "retrying" :failure-class "exception"}}
                    {:metric/name "PeriodicFailed" :metric/value 1
                     :metric/dimensions {:service "academy" :periodic ":academy/remind"
                                         :outcome "failed" :failure-class "exception"}}
                    {:metric/name "PostgresPoolActive" :metric/value 2
                     :metric/dimensions {:backend "postgres" :operation "pool" :outcome "sampled"}}
                    {:metric/name "PostgresPoolSaturated" :metric/value 1
                     :metric/dimensions {:backend "postgres" :operation "pool" :outcome "saturated"}}]
            documents (atom [])
            {:keys [instance]}
            (publish-events (assoc config :output-sink #(swap! documents conj %))
                            (mapv #(assoc % :app-name "academy" :env "production") events))]
        (is (zero? (:invalid (sut/publisher-health instance))))
        (is (= (count events) (:published (sut/publisher-health instance))))
        (doseq [document @documents]
          (assert-valid-emf-document document)
          (let [record (json/read-str document :key-fn keyword)]
            (is (= "academy" (:app-name record)))
            (is (= "production" (:env record))))))
      (finally (remove-ns (ns-name sandbox))))))

(deftest deadline-stops-new-writes-and-accounts-late-outcomes-once
  (doseq [fail-late? [false true]]
    (let [entered (promise) release (promise) writes (atom 0)
          now (System/currentTimeMillis)
          instance (sut/cloudwatch-emf-publisher
                    {:final-drain-timeout 200
                     :output-sink (fn [_]
                                    (swap! writes inc)
                                    (deliver entered true)
                                    (deref release 2000 nil)
                                    (when fail-late? (throw (Exception. "late failure"))))})
          ab (publisher/agent-buffer instance)]
      (try
        (send ab buffer/enqueue {:metric/name "InFlight" :metric/value 1 :mulog/timestamp now})
        (send ab buffer/enqueue {:metric/name "Untouched" :metric/value 1 :mulog/timestamp (inc now)})
        (is (await-for 1000 ab))
        (let [draining (future (sut/final-drain instance))]
          (is (true? (deref entered 1000 nil)))
          (let [result (deref draining 1000 nil)]
            (is (true? (:timed-out? result)))
            (is (nil? (:retained-count result)))
            (is (<= @(.-drain-deadline instance) (System/nanoTime))
                "timeout must not return while another document may still start")))
        (finally (deliver release true)))
      (is (await-for 2000 ab))
      (is (= 1 @writes) "untouched documents must not start a write after expiry")
      (is (= (if fail-late? 0 1) (:published (sut/publisher-health instance))))
      (is (= (if fail-late? 2 1) (:dropped (sut/publisher-health instance))))
      (is (zero? (:buffered (sut/publisher-health instance))))
      (is (zero? (.size ^java.util.IdentityHashMap (.-attempts instance)))))))

(deftest partial-publication-timeout-never-reports-stale-retained-count
  (let [entered (promise) release (promise) writes (atom 0)
        now (System/currentTimeMillis)
        instance (sut/cloudwatch-emf-publisher
                  {:final-drain-timeout 200
                   :output-sink (fn [_]
                                  (when (= 2 (swap! writes inc))
                                    (deliver entered true)
                                    (deref release 2000 nil)))})
        ab (publisher/agent-buffer instance)]
    (try
      (send ab buffer/enqueue {:metric/name "Accepted" :metric/value 1 :mulog/timestamp now})
      (send ab buffer/enqueue {:metric/name "InFlight" :metric/value 1 :mulog/timestamp (inc now)})
      (is (await-for 1000 ab))
      (let [draining (future (sut/final-drain instance))]
        (is (true? (deref entered 1000 nil)))
        (let [result (deref draining 1000 nil)]
          (is (true? (:timed-out? result)))
          (is (= 1 (:published-count result)))
          (is (nil? (:retained-count result))
              "the stale agent batch still contains the already accepted event")
          (is (zero? (:dropped (sut/publisher-health instance))))))
      (finally (deliver release true)))
    (is (await-for 2000 ab))
    (is (= 2 (:published (sut/publisher-health instance))))
    (is (zero? (:dropped (sut/publisher-health instance))))))

(deftest lifecycle-includes-buffered-pre-start-events-once
  (let [logger (atom (buffer/ring-buffer 1000)) accepted (atom [])]
    (binding [mulog-core/*default-logger* logger]
      (u/log ::before-start :metric/name "BeforeStart" :metric/value 1)
      (let [stop (sut/start-cloudwatch-emf-publisher!
                  {:flush-interval 60000 :output-sink #(swap! accepted conj %)})]
        (try
          (is (= 1 (:published-count (stop))))
          (is (= 1 (count @accepted)))
          (is (empty? (.getWatches ^clojure.lang.Atom logger)))
          (finally (stop)))))))

(deftest lifecycle-accounts-for-source-callback-already-running-at-stop
  (let [logger (atom (buffer/ring-buffer 1000))
        entered (promise) release (promise) instance (atom nil)]
    (binding [mulog-core/*default-logger* logger]
      (with-redefs [u/start-publisher! (fn [config]
                                       (reset! instance (:publisher config))
                                       (mulog-core/start-publisher! mulog-core/*default-logger* config))]
        (let [stop (sut/start-cloudwatch-emf-publisher! {:flush-interval 60000})
              [watch-key original-watch] (first (.getWatches ^clojure.lang.Atom logger))]
          (try
            (add-watch logger watch-key
                       (fn [k a old new]
                         (when (seq (buffer/items new))
                           (deliver entered true)
                           (deref release 2000 nil))
                         (original-watch k a old new)))
            (let [logging (future (u/log ::concurrent-stop :metric/name "Concurrent" :metric/value 1))]
              (is (true? (deref entered 1000 nil)))
              (is (false? (:timed-out? (stop))))
              (deliver release true)
              (is (nil? (deref logging 1000 ::timeout)))
              (is (= 1 (:dropped (sut/publisher-health @instance))))
              (is (zero? (:published (sut/publisher-health @instance))))
              (is (empty? (.getWatches ^clojure.lang.Atom logger))))
            (finally (deliver release true) (stop))))))))

(deftest read-model-fallback-preserves-legacy-configuration-and-units
  (doseq [config [{} {:metric-registry {"Custom" {:metric/type :counter :metric/unit :count}}}]]
    (let [{:keys [records]} (publish-events config
                            [{:metric/name "ReadModelEncodedBytesWritten" :metric/value 1024
                              :app-name "old-app" :env "prod" :read-model-name ":unbounded/model"}])]
      (is (= 1 (count records)))
      (is (= "Bytes" (:Unit (metric-definition (first records)))))
      (is (= 60 (:StorageResolution (metric-definition (first records)))))
      (is (= "old-app" (:app-name (first records))))
      (is (nil? (:read-model-name (first records)))))))

(deftest read-model-dimension-registry-is-optional-and-authoritative
  (let [dims {:app-name #{"app"} :read-model-name #{":known/model"}
              :read-model-operation #{"values"} :read-model-kind #{"keyed"}}
        event {:metric/name "ReadModelReadDuration" :mulog/duration 1000000
               :app-name "app" :read-model-name ":unknown/model"
               :read-model-operation "values" :read-model-kind "keyed"}]
    (doseq [allowed [{} {:app-name #{"app"}} dims]]
      (let [{:keys [records]} (publish-events {:metric-registry (sut/grain-metric-registry allowed)} [event])]
        (is (= 1 (count records)))
        (is (nil? (:read-model-name (first records))))))
    (let [config {:metric-registry (sut/grain-metric-registry dims)}]
      (is (= ":known/model" (:read-model-name (first (:records (publish-events config [(assoc event :read-model-name ":known/model")]))))))
      (is (empty? (:records (publish-events config [(assoc event :metric/dimensions {:read-model-name ":unknown/model"})]))))
      (is (= 1 (count (:records (publish-events config [(assoc event :metric/dimensions {:read-model-name ":known/model"})]))))))))

(deftest read-model-fallback-does-not-override-configured-or-arbitrary-semantics
  (let [{:keys [records]} (publish-events
                          {:metric-registry {"ReadModelEncodedBytesWritten"
                                             {:metric/type :gauge :metric/unit :count :metric/resolution :high}}}
                          [{:metric/name "ReadModelEncodedBytesWritten" :metric/value 17}])]
    (is (= "Count" (:Unit (metric-definition (first records)))))
    (is (= 1 (:StorageResolution (metric-definition (first records))))))
  (is (empty? (:records (publish-events [{:metric/name "ArbitraryBytes" :metric/value 3 :metric/unit :bytes}]))))
  (is (= "Count" (:Unit (metric-definition (first (:records (publish-events [{:metric/name "Arbitrary" :metric/value 3}]))))))))
