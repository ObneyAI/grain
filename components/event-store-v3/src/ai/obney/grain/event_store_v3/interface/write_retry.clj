(ns ai.obney.grain.event-store-v3.interface.write-retry
  "Shared implementation of the Allium WriteContention contract."
  (:require [cognitect.anomalies :as anom]
            [com.brunobonacci.mulog :as u])
  (:import [java.sql SQLException]))

(def defaults
  {:write-lock-timeout-ms 5000
   :write-max-retries 3
   :write-retry-backoff-ms 100
   :write-retry-max-backoff-ms 400})

(def legacy-options
  {:write-lock-timeout-ms :busy-timeout-ms
   :write-max-retries :busy-max-retries
   :write-retry-backoff-ms :busy-retry-backoff-ms
   :write-retry-max-backoff-ms :busy-retry-max-backoff-ms})

(defn policy
  ([config] (policy config nil))
  ([config backend]
   (let [config (merge (:conn config) (dissoc config :conn))
         legacy-immediate? (and (= :sqlite backend)
                                (not (contains? config :write-lock-timeout-ms))
                                (= 0 (:busy-timeout-ms config)))]
     (into {}
           (for [[key default] defaults
                 :let [value (get config key (get config (legacy-options key) default))]]
             (do
               (when-not (and (integer? value) (<= 0 value Long/MAX_VALUE)
                              (or (not= key :write-lock-timeout-ms)
                                  (pos? value) legacy-immediate?))
                 (throw (IllegalArgumentException. (str "Invalid " key ": " value))))
               [key value]))))))

(defn datasource-options [config]
  (apply dissoc config (concat (keys defaults) (vals legacy-options))))

(defn- exception-chain [throwable]
  (loop [pending [throwable] seen #{} result []]
    (if-let [error (peek pending)]
      (if (seen error)
        (recur (pop pending) seen result)
        (recur (into (pop pending)
                     (remove nil? [(.getCause ^Throwable error)
                                   (when (instance? SQLException error)
                                     (.getNextException ^SQLException error))]))
               (conj seen error) (conj result error)))
      result)))

(defn sql-contention? [backend throwable]
  (let [errors (exception-chain throwable)]
    (and
     (not-any? #(or (::unsafe (ex-data %))
                    (instance? InterruptedException %)
                    (and (instance? SQLException %)
                         (some-> (.getSQLState ^SQLException %) (.startsWith "08"))))
               errors)
     (boolean
      (some #(and (instance? SQLException %)
                  (case backend
                    :postgres (= "55P03" (.getSQLState ^SQLException %))
                    :sqlite (contains? #{5 6} (bit-and 255 (.getErrorCode ^SQLException %)))
                    false))
            errors)))))

(defn- backoff-ms [policy retry-number]
  (loop [remaining (dec retry-number)
         delay (min (:write-retry-backoff-ms policy)
                    (:write-retry-max-backoff-ms policy))]
    (let [maximum (:write-retry-max-backoff-ms policy)]
      (if (or (zero? remaining) (= delay maximum) (zero? delay))
        (+ delay (if (pos? delay) (long (rand delay)) 0))
        (recur (dec remaining)
               (if (> delay (quot maximum 2)) maximum (* 2 delay)))))))

(defn pause! [delay-ms]
  (Thread/sleep (long delay-ms)))

(defn- emit! [backend operation metric attempt delay]
  (u/log :metric/metric
         :metric/name metric :metric/value 1 :metric/resolution :low
         :metric/dimensions {:backend (name backend) :operation (name operation)}
         :attempt attempt :backoff-ms delay)
  (when (= :sqlite backend)
    (u/log :metric/metric
           :metric/name (if (= "EventStoreWriteRetry" metric)
                          "SQLiteBusyRetry" "SQLiteBusyExhausted")
           :metric/value 1 :metric/resolution :high
           :retry attempt :attempts attempt :backoff-ms delay)))

(defn transaction
  "f opens/closes one atomic attempt. Only rolled-back contention is retryable.
   Exhaustion returns the same busy anomaly for every backend; other errors throw."
  [config backend operation contention? f]
  (let [policy (policy config backend)]
    (loop [attempt 1]
      (when (.isInterrupted (Thread/currentThread))
        (throw (InterruptedException. "Event-store write interrupted")))
      (let [[result error] (try [(f) nil] (catch Exception error [nil error]))]
        (if-not error
          result
          (if-not (contention? error)
            (throw error)
            (if (<= attempt (:write-max-retries policy))
              (let [delay (backoff-ms policy attempt)]
                (emit! backend operation "EventStoreWriteRetry" attempt delay)
                (pause! delay)
                (recur (inc attempt)))
              (do
                (emit! backend operation "EventStoreWriteContentionExhausted" attempt nil)
                {::anom/category ::anom/busy
                 ::anom/message "Event-store write contention limit exhausted"
                 :event-store/attempts attempt}))))))))
