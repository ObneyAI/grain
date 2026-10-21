(ns ai.obney.grain.read-model-processor-v2.telemetry
  "Production metrics, independent of the diagnostic observer and its execution paths."
  (:require [com.brunobonacci.mulog :as u]))

(def ^:dynamic *read-attempt* nil)

(defn- current-attempt? []
  (and *read-attempt* (identical? (Thread/currentThread) (:thread @*read-attempt*))))

(defn emit! [event]
  (u/with-context event (u/log ::metric)))

(defn- record! [event]
  ;; Telemetry must never change a read's result or replace its original error.
  (try (emit! event) (catch Throwable _ nil)))

(defn dimensions [ctx model kind]
  (cond-> {:read-model-kind kind
           :read-model-scope (or (::scope ctx) (if (:read-model/scope ctx) "scoped" "tenant"))}
    model (assoc :read-model-name (str model))))

(defn counter! [metric value dimensions]
  (record! (merge dimensions {:metric/name metric :metric/value value
                             :metric/resolution :standard})))

(defn duration! [metric started dimensions]
  (record! (merge dimensions {:metric/name metric :mulog/duration (- (System/nanoTime) started)
                             :metric/resolution :high})))

(defn cancelled? [error]
  (loop [error error]
    (cond
      (nil? error) false
      (or (instance? InterruptedException error)
          (instance? java.util.concurrent.CancellationException error)
          (instance? java.io.InterruptedIOException error)
          (instance? java.nio.channels.ClosedByInterruptException error)) true
      :else (recur (.getCause ^Throwable error)))))

(defn consumer-call
  "Tag an error thrown by a caller callback without wrapping or replacing it."
  [f & args]
  (try (apply f args)
       (catch Throwable error
         (when (current-attempt?) (swap! *read-attempt* update :consumer conj error))
         (throw error))))

(defn- read-started! [ctx model kind operation started f]
  (if (current-attempt?)
    (try (f)
         (catch Throwable error
           (swap! *read-attempt*
                  (fn [origins]
                    (if (contains? (:consumer origins) error) origins
                        (update origins :processor conj error))))
           (throw error)))
    (let [dims (assoc (dimensions ctx model kind) :read-model-operation operation)
          outcome (volatile! "success")]
      (binding [*read-attempt* (atom {:thread (Thread/currentThread) :consumer #{} :processor #{}})]
        (try (f)
             (catch Throwable error
               (let [{:keys [consumer processor]} @*read-attempt*
                     result (cond
                              (cancelled? error) "cancelled"
                              (and (contains? consumer error) (not (contains? processor error))) "consumer-error"
                              :else "failure")]
                 (vreset! outcome result)
                 (when (= "failure" result)
                   (counter! "ReadModelReadFailed" 1 (assoc dims :read-model-outcome result))))
               (throw error))
             (finally
               (duration! "ReadModelReadDuration" started (assoc dims :read-model-outcome @outcome))))))))

(defn read! [ctx model kind operation f]
  (read-started! ctx model kind operation (System/nanoTime) f))

(defn ordinary-context [ctx args]
  (assoc ctx ::scope (cond (:partition-key args) "partition" (:scope args) "scoped" :else "tenant")))

(defn ordinary-kind [args]
  (if (and (:partition-fn args) (:entity-id-fn args)) "partitioned" "ordinary"))

(defn reducible-read [ctx model kind operation construct]
  ;; Construct under the attempt only when construction fails; successful lazy
  ;; construction is not a completed read. Each reduction owns its own sample.
  (let [started (System/nanoTime)
        source (try (construct)
                    (catch Throwable error (read-started! ctx model kind operation started #(throw error))))]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf init]
        (read! ctx model kind operation
               #(reduce (fn [acc row] (consumer-call rf acc row)) init source))))))

(defn owned! [duration-metric failure-metric dims f]
  (let [started (System/nanoTime) outcome (volatile! "success")]
    (try (f)
         (catch Throwable error
           (vreset! outcome (if (cancelled? error) "cancelled" "failure"))
           (when (and failure-metric (= "failure" @outcome))
             (counter! failure-metric 1 (assoc dims :read-model-outcome @outcome)))
           (throw error))
         (finally (duration! duration-metric started (assoc dims :read-model-outcome @outcome))))))

(defn callback-options [options]
  (reduce (fn [options key]
            (if (ifn? (get options key))
              (assoc options key (fn [& args] (apply consumer-call (get options key) args)))
              options))
          options [:sort-value :include?]))

(def ^:dynamic *cold-build* nil)

(defn cold-start! []
  (when *cold-build*
    (swap! *cold-build* #(if (:started %) % (assoc % :started (System/nanoTime))))))

(defn cold-result! [outcome]
  (when *cold-build* (swap! *cold-build* assoc :outcome outcome)))

(defn cold-owned! [dims initially-owned? f]
  (binding [*cold-build* (atom {:outcome "success"})]
    (when initially-owned? (cold-start!))
    (try (f)
         (catch Throwable error
           (cold-result! (if (cancelled? error) "cancelled" "failure"))
           (when (and (:started @*cold-build*) (= "failure" (:outcome @*cold-build*)))
             (counter! "ReadModelColdBuildFailed" 1 (assoc dims :read-model-outcome "failure")))
           (throw error))
         (finally
           (let [{:keys [started outcome]} @*cold-build*]
             (when started
               (duration! "ReadModelColdBuildDuration" started (assoc dims :read-model-outcome outcome))))))))
