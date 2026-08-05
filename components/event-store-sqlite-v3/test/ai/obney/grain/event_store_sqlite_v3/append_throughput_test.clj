(ns ai.obney.grain.event-store-sqlite-v3.append-throughput-test
  "Correctness-first SQLite append load tests. Timing is diagnostic only."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defschemas append-throughput-schemas
  {:throughput/event [:map
                      [:writer :int]
                      [:n :int]]})

(defn- delete-db-files! [path]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
    (.delete (File. (str path suffix)))))

(defn- with-store [f]
  (let [tmp (File/createTempFile "grain-append-throughput-" ".sqlite")
        path (.getAbsolutePath tmp)]
    (.delete tmp)
    (let [store (es/start {:conn {:type :sqlite :database-file path}})]
      (try (f store)
           (finally
             (es/stop store)
             (delete-db-files! path))))))

(defn- percentile [values p]
  (when (seq values)
    (let [sorted (vec (sort values))
          index (min (dec (count sorted))
                     (long (Math/ceil (* p (count sorted)))))]
      (nth sorted (max 0 (dec index))))))

(defn- print-metrics! [label elapsed-ns latencies-ns]
  (let [seconds (/ elapsed-ns 1.0e9)
        millis #(when % (/ % 1.0e6))]
    (println (format "%s: %.1f writes/s, p50=%.2fms p95=%.2fms p99=%.2fms"
                     label
                     (/ (count latencies-ns) seconds)
                     (millis (percentile latencies-ns 0.50))
                     (millis (percentile latencies-ns 0.95))
                     (millis (percentile latencies-ns 0.99))))))

(defn- run-load!
  [store {:keys [writers appends-per-writer tenants timeout-ms]
          :or {timeout-ms 30000}}]
  (let [ready (CountDownLatch. writers)
        start (CountDownLatch. 1)
        results (atom [])
        tasks (mapv
               (fn [writer]
                 (future
                   (.countDown ready)
                   (.await start)
                   (dotimes [n appends-per-writer]
                     (let [tenant-id (nth tenants (mod (+ writer n) (count tenants)))
                           event (es/->event {:type :throughput/event
                                             :body {:writer writer :n n}})
                           before (System/nanoTime)
                           returned (es/append store {:tenant-id tenant-id
                                                      :events [event]})
                           elapsed (- (System/nanoTime) before)]
                       (swap! results conj {:tenant-id tenant-id
                                            :returned returned
                                            :latency-ns elapsed})))))
               (range writers))
        _ (.await ready 10 TimeUnit/SECONDS)
        began (System/nanoTime)
        _ (.countDown start)
        deadline (+ (System/currentTimeMillis) timeout-ms)
        completed? (every? true?
                           (map (fn [task]
                                  (let [remaining (max 1 (- deadline (System/currentTimeMillis)))]
                                    (not= ::timeout (deref task remaining ::timeout))))
                                tasks))
        elapsed (- (System/nanoTime) began)]
    (when-not completed? (run! future-cancel tasks))
    {:completed? completed?
     :elapsed-ns elapsed
     :results @results
     :latencies-ns (mapv :latency-ns @results)}))

(defn- domain-events [store tenant-id]
  (into []
        (remove #(= :grain/tx (:event/type %)))
        (es/read store {:tenant-id tenant-id})))

(defn- strictly-increasing? [ids]
  (every? true? (map uuid/< ids (rest ids))))

(deftest concurrent-hot-tenant-appends-are-complete-and-ordered
  (with-store
    (fn [store]
      (let [tenant-id (uuid/v4)
            initial-watermark (get-in (es/tenants store) [tenant-id :tenant/last-event-id])
            result (run-load! store {:writers 8 :appends-per-writer 100
                                     :tenants [tenant-id] :timeout-ms 30000})
            events (domain-events store tenant-id)
            ids (mapv :event/id events)
            returned (mapcat :returned (:results result))
            watermark (get-in (es/tenants store) [tenant-id :tenant/last-event-id])]
        (print-metrics! "SQLite hot tenant (8x100)" (:elapsed-ns result) (:latencies-ns result))
        (is (:completed? result) "all writers complete within 30 seconds")
        (is (= 800 (count (:results result))))
        (is (= 800 (count returned)) "every append returns one persisted event")
        (is (= 800 (count events)))
        (is (= 800 (count (set ids))))
        (is (strictly-increasing? ids))
        (is (and watermark (or (nil? initial-watermark)
                               (not (uuid/< watermark initial-watermark)))))
        (is (not (uuid/< watermark (last ids))))))))

(deftest concurrent-batches-receive-store-assigned-metadata
  (with-store
    (fn [store]
      (let [tenant-id (uuid/v4)
            writers 8
            groups-per-writer 25
            ready (CountDownLatch. writers)
            start (CountDownLatch. 1)
            observations (atom [])
            tasks (mapv
                   (fn [writer]
                     (future
                       (.countDown ready)
                       (.await start)
                       (dotimes [n groups-per-writer]
                         (let [events (mapv #(es/->event {:type :throughput/event
                                                          :body {:writer writer :n %}})
                                            [(* n 2) (inc (* n 2))])
                               watermark (get-in (es/tenants store)
                                                 [tenant-id :tenant/last-event-id])
                               returned (es/append store {:tenant-id tenant-id
                                                          :events events})]
                           (swap! observations conj
                                  {:submitted events :watermark watermark
                                   :returned returned})))))
                   (range writers))]
        (.await ready 10 TimeUnit/SECONDS)
        (.countDown start)
        (let [completed? (every? #(not= ::timeout (deref % 30000 ::timeout)) tasks)
              stored (domain-events store tenant-id)
              stored-by-payload (into {} (map (juxt #(select-keys % [:writer :n]) identity)) stored)
              returned (mapcat :returned @observations)
              returned-ids (mapv :event/id returned)]
          (when-not completed? (run! future-cancel tasks))
          (is completed? "store-assignment writers complete within 30 seconds")
          (is (= (* writers groups-per-writer 2) (count stored)))
          (is (= (count returned-ids) (count (set returned-ids))))
          (is (every? (fn [{:keys [submitted returned watermark]}]
                        (and (= (mapv #(select-keys % [:writer :n]) submitted)
                                (mapv #(select-keys % [:writer :n]) returned))
                             (not= (mapv :event/id submitted) (mapv :event/id returned))
                             (every? #(not (contains? % :event/timestamp)) submitted)
                             (apply = (map :event/timestamp returned))
                             (every? #(or (nil? watermark) (uuid/< watermark %))
                                     (map :event/id returned))))
                      @observations))
          (is (every? (fn [event]
                        (= (:event/id event)
                           (:event/id (stored-by-payload (select-keys event [:writer :n])))))
                      returned)))))))

(deftest hot-versus-distributed-throughput-is-diagnostic
  (let [run (fn [tenant-count]
              (with-store
                #(run-load! % {:writers 8 :appends-per-writer 50
                               :tenants (vec (repeatedly tenant-count uuid/v4))
                               :timeout-ms 30000})))
        hot (run 1)
        distributed (run 20)]
    (print-metrics! "SQLite hot comparison" (:elapsed-ns hot) (:latencies-ns hot))
    (print-metrics! "SQLite distributed comparison" (:elapsed-ns distributed)
                    (:latencies-ns distributed))
    (is (:completed? hot))
    (is (:completed? distributed))
    (is (= 400 (count (:results hot))))
    (is (= 400 (count (:results distributed))))))

(deftest ^:extended optional-concurrency-benchmark
  (if-not (= "true" (System/getenv "GRAIN_PERF_TESTS"))
    (is true "set GRAIN_PERF_TESTS=true to run the extended benchmark")
    (doseq [writers [1 5 20 50]]
      (with-store
        (fn [store]
          (run-load! store {:writers writers :appends-per-writer 5
                            :tenants [(uuid/v4)] :timeout-ms 30000})))
      (let [trials (repeatedly
                    3
                    #(with-store
                       (fn [store]
                         (run-load! store {:writers writers :appends-per-writer 20
                                           :tenants [(uuid/v4)] :timeout-ms 60000}))))
            trials (vec trials)
            throughputs (mapv #(/ (count (:results %)) (/ (:elapsed-ns %) 1.0e9)) trials)
            median (percentile throughputs 0.50)
            latencies (mapcat :latencies-ns trials)]
        (println (format "SQLite extended concurrency=%d: median %.1f writes/s"
                         writers median))
        (print-metrics! (str "SQLite extended concurrency=" writers)
                        (reduce + (map :elapsed-ns trials)) latencies)
        (is (every? :completed? trials))))))
