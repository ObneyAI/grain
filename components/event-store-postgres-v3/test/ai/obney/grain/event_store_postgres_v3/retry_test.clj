(ns ai.obney.grain.event-store-postgres-v3.retry-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface.write-retry :as retry]
            [ai.obney.grain.event-store-postgres-v3.core :as pg]
            [cognitect.anomalies :as anom]
            [next.jdbc :as jdbc])
  (:import [java.sql Connection SQLException]
           [javax.sql DataSource]))

;; Allium: store/WriteContention (shared contract, PostgreSQL error adapter).
(defn run-transaction [operation f]
  (retry/transaction {} :postgres operation #(retry/sql-contention? :postgres %) f))

(deftest retries-lock-timeouts-with-bounded-backoff
  (let [attempts (atom 0)
        delays (atom [])
        error (SQLException. "lock timeout" "55P03")]
    (with-redefs [retry/pause! #(swap! delays conj %)
                  clojure.core/rand (constantly 0)]
      (is (= :committed
             (run-transaction :append
               #(if (< (swap! attempts inc) 4) (throw error) :committed))))
      (is (= 4 @attempts))
      (is (= [100 200 400] @delays))
      (reset! attempts 0)
      (let [result (run-transaction :append #(do (swap! attempts inc) (throw error)))]
        (is (= ::anom/busy (::anom/category result)))
        (is (= 4 (:event-store/attempts result))))
      (is (= 4 @attempts)))))

(deftest recognizes-wrapped-and-jdbc-chained-lock-errors
  (doseq [error [(ex-info "wrapper" {} (SQLException. "timeout" "55P03"))
                (doto (SQLException. "batch failure")
                  (.setNextException (SQLException. "timeout" "55P03")))]]
    (let [attempts (atom 0)]
      (with-redefs [retry/pause! (constantly nil)]
        (is (= :ok (run-transaction :append
                     #(if (= 1 (swap! attempts inc)) (throw error) :ok))))
        (is (= 2 @attempts))))))

(deftest unrelated-failures-and-conflicts-are-not-retried
  (doseq [error [(SQLException. "connection lost" "08006")
                (SQLException. "commit outcome unknown" "08007")
                (SQLException. "duplicate key" "23505")
                (SQLException. "statement timeout" "57014")
                (ex-info "CAS predicate failed" {})
                (InterruptedException. "cancelled")]]
    (let [attempts (atom 0)]
      (is (identical? error
                     (try
                       (run-transaction :append #(do (swap! attempts inc) (throw error)))
                       (catch Exception e e))))
      (is (= 1 @attempts))))
  (let [conflict {::anom/category ::anom/conflict}
        attempts (atom 0)]
    (is (= conflict (run-transaction :append #(do (swap! attempts inc) conflict))))
    (is (= 1 @attempts))))

(deftest cancellation-during-backoff-stops-retries
  (let [attempts (atom 0)]
    (with-redefs [retry/pause! #(throw (InterruptedException. (str %)))]
      (is (thrown? InterruptedException
                   (run-transaction :append
                     #(do (swap! attempts inc)
                          (throw (SQLException. "timeout" "55P03")))))))
    (is (= 1 @attempts))))

(defn- instrumented-pool [lifecycle]
  (reify DataSource
    (getConnection [_]
      (swap! lifecycle conj :open)
      (proxy [Connection] []
        (getAutoCommit [] true)
        (getTransactionIsolation [] Connection/TRANSACTION_READ_COMMITTED)
        (isReadOnly [] false)
        (setAutoCommit [_])
        (commit [] (swap! lifecycle conj :commit))
        (rollback [] (swap! lifecycle conj :rollback))
        (close [] (swap! lifecycle conj :close))))))

(deftest append-rolls-back-and-releases-connection-before-retrying
  ;; Exercise real next.jdbc transaction handling with instrumented connections.
  (let [lifecycle (atom [])
        sql (atom [])
        writes (atom 0)
        pool (instrumented-pool lifecycle)
        store {:state {::pg/connection-pool pool}}]
    (with-redefs [jdbc/execute! (fn [_ statement & _] (swap! sql conj (first statement)) [])
                  jdbc/execute-one! (constantly nil)
                  pg/insert-events (fn [& _]
                                     (when (= 1 (swap! writes inc))
                                       (throw (SQLException. "timeout during write" "55P03")))
                                     :written)
                  retry/pause! (fn [_] (swap! lifecycle conj :pause))]
      (pg/append store {:tenant-id (random-uuid) :events []}))
    (is (= 2 @writes))
    (is (= [:open :rollback :close :pause :open :commit :close] @lifecycle))
    (is (= 2 (count (filter #{"SET LOCAL lock_timeout = '5000ms'"} @sql))))))

(deftest append-rechecks-cas-after-a-lock-timeout
  (let [lifecycle (atom [])
        checks (atom 0)
        writes (atom 0)
        store {:state {::pg/connection-pool (instrumented-pool lifecycle)}}]
    (with-redefs [jdbc/execute! (constantly [])
                  jdbc/execute-one! (constantly nil)
                  jdbc/plan (constantly [])
                  pg/insert-events (fn [& _]
                                     (swap! writes inc)
                                     (throw (SQLException. "timeout during write" "55P03")))
                  retry/pause! (fn [_] (swap! lifecycle conj :pause))]
      (is (= ::anom/conflict
             (::anom/category
              (pg/append store {:tenant-id (random-uuid) :events []
                                :cas {:predicate-fn
                                      (fn [_] (= 1 (swap! checks inc)))}})))))
    (is (= 2 @checks))
    (is (= 1 @writes))
    (is (= [:open :rollback :close :pause :open :commit :close] @lifecycle))))

(deftest compaction-reacquires-locks-and-rechecks-policy-after-timeout
  (let [lifecycle (atom [])
        sql (atom [])
        locks (atom 0)
        policy-checks (atom 0)
        store {:state {::pg/connection-pool (instrumented-pool lifecycle)}}]
    (with-redefs [jdbc/execute! (fn [_ [statement] & _]
                                 (swap! sql conj statement)
                                 (when (and (= statement "SELECT pg_advisory_xact_lock(?)")
                                            (= 1 (swap! locks inc)))
                                   (throw (SQLException. "timeout" "55P03")))
                                 [])
                  pg/postgres-compaction-context (fn [& _]
                                                   (swap! policy-checks inc)
                                                   {:eligible []})
                  retry/pause! (fn [_] (swap! lifecycle conj :pause))]
      (is (nil? (pg/compact-events! store {:tenant-id (random-uuid)}))))
    (is (= 3 @locks))
    (is (= 1 @policy-checks))
    (is (= [:open :rollback :close :pause :open :commit :close] @lifecycle))
    (is (= 2 (count (filter #{"SET LOCAL lock_timeout = '5000ms'"} @sql))))))
