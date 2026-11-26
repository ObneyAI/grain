(ns ai.obney.grain.event-store-v3.write-contention-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.compaction :as compaction]
            [ai.obney.grain.event-store-v3.interface.write-retry :as retry]
            [cognitect.anomalies :as anom]))

;; Allium: WriteContention.BoundedRecovery / FreshAtomicAttempt / SharedConfiguration
(deftest memory-append-and-compaction-obey-the-contention-budget
  (let [store (es/start {:conn {:type :in-memory
                                :write-lock-timeout-ms 1
                                :write-max-retries 1
                                :write-retry-backoff-ms 0}})
        tenant (random-uuid)
        entered (promise)
        release (promise)
        writer (future
                 (es/append store {:tenant-id tenant :events []
                                   :cas {:predicate-fn
                                         (fn [_]
                                           (deliver entered true)
                                           (deref release 5000 false))}}))]
    (try
      (is (= true (deref entered 1000 :timeout)))
      (doseq [write [#(es/append store {:tenant-id tenant :events []})
                     #(compaction/compact! store {:tenant-id tenant})]]
        (let [result (write)]
          (is (= ::anom/busy (::anom/category result)))
          (is (= 2 (:event-store/attempts result)))))
      (is (nil? (es/head store tenant)))
      (finally
        (deliver release true)
        (deref writer 5000 :timeout)
        (es/stop store)))))

;; Allium: WriteContention.FreshAtomicAttempt
(deftest memory-retry-rechecks-the-current-cas-state
  (let [store (es/start {:conn {:type :in-memory :write-lock-timeout-ms 1}})
        tenant (random-uuid)
        entered (promise)
        release (promise)
        retries (atom 0)
        writer (future
                 (es/append store {:tenant-id tenant :events []
                                   :cas {:predicate-fn
                                         (fn [_] (deliver entered true)
                                           (deref release 5000 false))}}))]
    (try
      (is (= true (deref entered 1000 :timeout)))
      (with-redefs [retry/pause! (fn [_]
                                  (swap! retries inc)
                                  (deliver release true)
                                  (deref writer 1000 :timeout))]
        (let [result (es/append store {:tenant-id tenant :events []
                                       :cas {:predicate-fn
                                             #(empty? (into [] %))}})]
          (is (= ::anom/conflict (::anom/category result)))))
      (is (= 1 @retries))
      (is (= 1 (count (into [] (es/read store {:tenant-id tenant})))))
      (finally (deliver release true)
               (deref writer 5000 :timeout)
               (es/stop store)))))
