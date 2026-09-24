(ns ai.obney.grain.control-plane-test-base.read-model-scenarios
  "Live v3 projection checks against the supplied event store and native LMDB."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.interface.testing :as projection-testing]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [clojure.java.io :as io]))

(es/defevent :live-read-model/seed "Initialize a projection."
  {:schema [:map [:key-count :int]]})
(es/defevent :live-read-model/increment "Increment a projected counter."
  {:schema [:map]})

(defn- reducer [state event]
  (case (:event/type event)
    :live-read-model/seed (assoc (into {} (map (fn [i] [i 0])
                                             (range (:key-count event)))) :count 0)
    :live-read-model/increment (update state :count inc)))

(defn- await! [work]
  (let [result (deref work 60000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Live projection worker timed out" {})))
    result))

(defn- append-events! [store tenant events]
  (let [result (es/append store {:tenant-id tenant :events events})]
    (when (anomaly? result)
      (throw (ex-info "Live projection append failed" {:anomaly result})))
    result))

(defn- with-model [events key-count f]
  (let [tenant (random-uuid) dir (str "/tmp/grain-live-read-model-" tenant)
        config {:storage-dir dir}
        store (atom (rmp/open-store config))
        model :live-read-model/counter
        query {:tenant-id tenant :types #{:live-read-model/seed :live-read-model/increment}}
        context #(hash-map :event-store events :projection-store @store :tenant-id tenant)]
    (rmp/register-read-model! model reducer {:events (:types query) :version 1})
    (try
      (append-events! events tenant [(es/->event {:type :live-read-model/seed
                                                 :body {:key-count key-count}})])
      (f {:project #(rmp/project (context) model)
          :committed #(projection-testing/committed (context) model)
          :collect! #(rmp/collect! @store)
          :reopen! #(do (projection-testing/release-store! @store)
                        (reset! store (rmp/open-store config)))
          :append! (fn [n]
                     (append-events! events tenant
                       (mapv (fn [_] (es/->event {:type :live-read-model/increment})) (range n))))
          :events #(into [] (es/read events query))})
      (finally
        (projection-testing/release-store! @store)
        (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true))))))

(defn lifecycle [store]
  (with-model store 0
    (fn [{:keys [project committed reopen! append! events]}]
      (append! 3)
      (let [cold (into {} (project)) warm (into {} (project))]
        (append! 10)
        (let [incremental (into {} (project)) durable (committed)
              expected (reduce reducer {} (events))]
          (reopen!)
          (let [reopened (into {} (project))]
            (append! 4)
            (let [tail (into {} (project))]
              {:checks {"Cold projection equals event replay" (= {:count 3} cold warm)
                        "Incremental projection commits its state" (= expected incremental (:data durable) {:count 13})
                        "Watermark matches last processed event" (= (:watermark durable) (:event/id (last (take 14 (events)))))
                        "Reopen preserves committed state" (= expected reopened)
                        "Reopened projection catches up" (= tail (reduce reducer {} (events)) {:count 17})}})))))))

(defn concurrent [store]
  (with-model store 10001
    (fn [{:keys [project collect! append! events committed]}]
      (let [retained (project)
            go (promise)
            readers (mapv (fn [_] (future (await! go) (into {} (project)))) (range 3))
            writer (future (await! go) (append! 10))]
        (try
          (deliver go true)
          (await! writer)
          (let [results (mapv await! readers)
                current (into {} (project))
                log (events)
                expected (reduce reducer {} log)
                checkpoint (committed)]
            (collect!)
            {:checks
             {"Concurrent reads represent complete event prefixes"
              (every? (fn [state]
                        (let [n (:count state)]
                          (and (<= 0 n 10) (= state (reduce reducer {} (take (inc n) log)))))) results)
              "Settled projection equals full replay" (= expected current)
              "Committed state and watermark agree" (and (= expected (:data checkpoint))
                                                          (= (:event/id (last log)) (:watermark checkpoint)))
              "Retained result survives updates and cleanup" (= retained (reduce reducer {} (take 1 log)))
              "Large projection retains all records" (= 10002 (count current))}})
          (finally
            (deliver go true)
            (doseq [worker (conj readers writer)] (await! worker))))))))
