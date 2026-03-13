(ns ai.obney.grain.periodic-task.core-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.periodic-task.core :as core]
            [ai.obney.grain.periodic-task.interface :as pt])
  (:import [java.time Instant Duration ZoneId]))

;; 1. Schedule Sequence Tests

(deftest test-periodic-seq-produces-instants-with-correct-interval
  (testing "Periodic schedule with seconds produces Instants spaced correctly"
    (let [times (take 3 (#'core/schedule-seq {:every 10 :duration :seconds}))]
      (is (= 3 (count times)))
      (is (every? #(instance? Instant %) times))
      (let [[t1 t2 t3] times
            gap1 (Duration/between t1 t2)
            gap2 (Duration/between t2 t3)]
        (is (= 10 (.getSeconds gap1)))
        (is (= 10 (.getSeconds gap2)))))))

(deftest test-periodic-seq-supports-minutes
  (testing "Periodic schedule with minutes produces correct intervals"
    (let [[t1 t2] (take 2 (#'core/schedule-seq {:every 5 :duration :minutes}))]
      (is (= 300 (.getSeconds (Duration/between t1 t2)))))))

(deftest test-periodic-seq-supports-hours
  (testing "Periodic schedule with hours produces correct intervals"
    (let [[t1 t2] (take 2 (#'core/schedule-seq {:every 2 :duration :hours}))]
      (is (= 7200 (.getSeconds (Duration/between t1 t2)))))))

(deftest test-cron-seq-produces-instants
  (testing "Cron schedule with every-minute expression produces Instants ~60s apart"
    (let [times (take 3 (#'core/schedule-seq {:cron "* * * * *"}))]
      (is (= 3 (count times)))
      (is (every? #(instance? Instant %) times))
      (let [[t1 t2 t3] times
            gap1 (.getSeconds (Duration/between t1 t2))
            gap2 (.getSeconds (Duration/between t2 t3))]
        (is (= 60 gap1))
        (is (= 60 gap2))))))

(deftest test-cron-seq-with-timezone
  (testing "Cron schedule respects explicit timezone"
    (let [times (take 2 (#'core/schedule-seq {:cron "0 9 * * *" :timezone "UTC"}))]
      (is (= 2 (count times)))
      (is (every? #(instance? Instant %) times))
      ;; Daily at 9am → 24 hours apart
      (is (= 86400 (.getSeconds (Duration/between (first times) (second times))))))))

(deftest test-invalid-schedule-throws
  (testing "Schedule config with neither :cron nor :every throws ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                (#'core/schedule-seq {})))))

(deftest test-invalid-cron-expression-throws
  (testing "Invalid cron expression throws exception"
    (is (thrown? Exception
                (#'core/schedule-seq {:cron "not-a-cron"})))))

;; 2. Lifecycle Tests

(deftest test-start-and-stop-periodic-task
  (testing "Start with periodic config executes handler, stop prevents further execution"
    (let [counter (atom 0)
          task (pt/start {:handler-fn (fn [_] (swap! counter inc))
                          :schedule {:every 1 :duration :seconds}
                          :task-name ::test-periodic})]
      (Thread/sleep 2500)
      (pt/stop task)
      (let [count-at-stop @counter]
        (is (>= count-at-stop 2))
        (Thread/sleep 1500)
        (is (= count-at-stop @counter) "No further increments after stop")))))

(deftest test-start-and-stop-cron-task
  (testing "Start with cron config returns correct structure and stops cleanly"
    (let [task (pt/start {:handler-fn (fn [_] nil)
                          :schedule {:cron "* * * * *"}
                          :task-name ::test-cron})]
      (is (contains? task ::core/task))
      (is (contains? task ::core/args))
      (pt/stop task))))

;; 3. Backward Compatibility

(deftest test-existing-periodic-config-works
  (testing "Exact config shape from example-base still works"
    (let [counter (atom 0)
          task (pt/start {:handler-fn (fn [_] (swap! counter inc))
                          :schedule {:every 30 :duration :seconds}
                          :task-name ::example-periodic-task})]
      (is (contains? task ::core/task))
      (is (contains? task ::core/args))
      (pt/stop task))))
