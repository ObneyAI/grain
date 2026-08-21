(ns ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface-test
  (:require [com.brunobonacci.mulog.publisher :as publisher]
            [com.brunobonacci.mulog :as u]
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
          (is @mulog-stopped))))))

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
