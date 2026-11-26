(ns ai.obney.grain.event-store-v3.write-retry-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface.write-retry :as retry]
            [cognitect.anomalies :as anom])
  (:import [java.sql SQLException]))

;; Allium: WriteContention.SharedConfiguration
(deftest shared-policy-defaults-aliases-and-validation
  (is (= {:write-lock-timeout-ms 5000 :write-max-retries 3
          :write-retry-backoff-ms 100 :write-retry-max-backoff-ms 400}
         (retry/policy {})))
  (is (= 20 (:write-lock-timeout-ms (retry/policy {:busy-timeout-ms 20}))))
  (is (= 30 (:write-lock-timeout-ms (retry/policy {:busy-timeout-ms 20
                                                 :write-lock-timeout-ms 30}))))
  (is (= 30 (:write-lock-timeout-ms (retry/policy {:conn {:write-lock-timeout-ms 30}}))))
  (is (= 0 (:write-lock-timeout-ms (retry/policy {:conn {:busy-timeout-ms 0}} :sqlite))))
  (is (= 30 (:write-lock-timeout-ms (retry/policy {:busy-timeout-ms 0
                                                :write-lock-timeout-ms 30} :sqlite))))
  (doseq [backend [:postgres :in-memory]]
    (is (thrown? IllegalArgumentException (retry/policy {:busy-timeout-ms 0} backend))))
  (is (thrown? IllegalArgumentException
               (retry/policy {:busy-timeout-ms 0 :write-lock-timeout-ms 0} :sqlite)))
  (doseq [config [{:write-lock-timeout-ms 0} {:write-lock-timeout-ms -1}
                  {:write-max-retries -1} {:write-max-retries 1.5}
                  {:write-retry-backoff-ms nil} {:write-retry-max-backoff-ms -2}]]
    (is (thrown? IllegalArgumentException (retry/policy config))))
  (is (= {:database-file "db"} (retry/datasource-options
                                (merge retry/defaults
                                       {:database-file "db" :busy-timeout-ms 20})))))

;; Allium: WriteContention.BoundedRecovery / BackoffAndCancellation
(deftest every-backend-uses-the-same-retry-budget-and-backoff
  (doseq [backend [:postgres :sqlite :in-memory]]
    (let [attempts (atom 0) delays (atom [])]
      (with-redefs [retry/pause! #(swap! delays conj %)]
        (let [result (retry/transaction {} backend :append (constantly true)
                        #(do (swap! attempts inc) (throw (ex-info "contention" {}))))]
          (is (= ::anom/busy (::anom/category result)))
          (is (= 4 (:event-store/attempts result)))
          (is (= 4 @attempts))
          (is (every? true? (map (fn [delay base] (<= base delay (dec (* 2 base))))
                                 @delays [100 200 400])))))))
  (let [attempts (atom 0)]
    (retry/transaction {:write-max-retries 0} :in-memory :compact (constantly true)
                       #(do (swap! attempts inc) (throw (ex-info "contention" {}))))
    (is (= 1 @attempts))))

;; Allium: WriteContention.RetryScope
(deftest sql-contention-recognition-rejects-uncertain-outcomes
  (is (retry/sql-contention? :postgres (SQLException. "lock timeout" "55P03")))
  (is (retry/sql-contention? :sqlite (SQLException. "busy snapshot" "" 517)))
  (is (retry/sql-contention? :sqlite (SQLException. "locked shared cache" "" 262)))
  (doseq [backend [:postgres :sqlite]]
    (let [lock-error (if (= backend :postgres)
                       (SQLException. "lock timeout" "55P03")
                       (SQLException. "busy" "" 5))]
      (is (false? (retry/sql-contention?
                    backend (ex-info "rollback failed" {::retry/unsafe true} lock-error))))
      (is (false? (retry/sql-contention?
                    backend (doto (SQLException. "connection lost" "08006")
                              (.setNextException lock-error)))))))
  (is (false? (retry/sql-contention? :postgres (SQLException. "cancelled" "57014")))))

(deftest already-interrupted-writer-never-starts-an-attempt
  (let [attempts (atom 0)]
    (.interrupt (Thread/currentThread))
    (try
      (is (thrown? InterruptedException
                   (retry/transaction {} :in-memory :append (constantly true)
                                      #(swap! attempts inc))))
      (is (zero? @attempts))
      (finally (Thread/interrupted)))))
