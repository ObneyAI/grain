(ns ai.obney.grain.control-plane-test-base.read-model-scenarios
  "Live projection checks using the supplied event store and isolated LMDB caches."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.interface.testing :as cache-testing]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store.interface.protocol :as kp]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.fressian-util.interface :as fressian]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [clojure.java.io :as io]))

(es/defevent :live-read-model/seed
  "Initialize a live projection with a chosen number of state keys."
  {:schema [:map [:key-count :int]]})

(es/defevent :live-read-model/increment
  "Increment the counter in a live projection."
  {:schema [:map]})

(defn- reducer [state event]
  (case (:event/type event)
    :live-read-model/seed (assoc (into {} (map (fn [i] [i 0])
                                             (range (:key-count event)))) :count 0)
    :live-read-model/increment (update state :count inc)))

(defn- await! [work]
  (let [result (deref work 20000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Live projection worker timed out" {})))
    result))

(defn- append! [store tenant events]
  (let [result (es/append store {:tenant-id tenant :events events})]
    (when (anomaly? result)
      (throw (ex-info "Live projection append failed" {:anomaly result})))
    result))

(defn- with-model [store segmented? f]
  (let [tenant (random-uuid)
        dir (str "/tmp/grain-live-read-model-" tenant)
        cache (kv/start (lmdb/->KV-Store-LMDB
                        {:storage-dir dir :db-name "projection" :map-size (* 64 1024 1024)}))
        name :live-read-model/counter
        key (cache-testing/format-scoped-key name 1 tenant)
        l1-key (String. ^bytes key)
        query {:tenant-id tenant :types #{:live-read-model/seed :live-read-model/increment}}
        args {:name name :version 1 :f reducer :query (dissoc query :tenant-id) :l1-ttl-ms 0}
        ctx {:event-store store :cache cache :tenant-id tenant}]
    (try
      (append! store tenant [(es/->event {:type :live-read-model/seed
                                        :body {:key-count (if segmented? 10001 0)}})])
      (f {:cache cache :key key :l1-key l1-key :context ctx :args args
          :project #(rmp/p ctx args)
          :evict! #(cache-testing/evict-l1! l1-key)
          :append! (fn [n]
                     (append! store tenant (mapv (fn [_] (es/->event {:type :live-read-model/increment}))
                                                 (range n))))
          :events #(into [] (es/read store query))})
      (finally
        (cache-testing/evict-l1! l1-key)
        (kv/stop cache)
        (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true))))))

(defn lifecycle [store]
  (with-model store false
    (fn [{:keys [cache key l1-key project evict! append! events]}]
      (let [absent? (and (nil? (cache-testing/l1-entry l1-key)) (nil? (kv/get! cache {:k key})))
            _ (append! 3)
            expected-cold (reduce reducer {} (events))
            cold (project)
            warm (project)
            _ (append! 10)
            expected-new (reduce reducer {} (events))
            incremental (project)
            checkpoint (fressian/decode (kv/get! cache {:k key}))
            _ (evict!)
            l2 (project)
            _ (append! 4)
            _ (evict!)
            expected-tail (reduce reducer {} (events))
            l2-tail (project)]
        {:checks {"Projection starts with both cache tiers empty" absent?
                  "Cold build equals full replay" (= expected-cold cold {:count 3})
                  "Warm read equals full replay" (= expected-cold warm)
                  "Incremental L1 update equals full replay" (= expected-new incremental {:count 13})
                  "L2 checkpoint contains the updated state" (= expected-new (:data checkpoint))
                  "L2 reload equals full replay" (= expected-new l2)
                  "L2 reload applies events after its checkpoint" (= expected-tail l2-tail {:count 17})}}))))

(defn- concurrent-segmented [store]
  (with-model store true
    (fn [{:keys [cache key project evict! append! events]}]
      (project)
      (let [segmented? (:segmented (fressian/decode (kv/get! cache {:k key})))
            rounds
            (doall
             (for [_ (range 5)]
               (do
                 ;; Evict before launching workers, not midway through L1 access.
                 (evict!)
                 (let [go (promise)
                       readers (mapv (fn [_] (future (await! go) (project))) (range 3))
                       writer (future (await! go) (append! 10))]
                   (try
                     (deliver go true)
                     (await! writer)
                     (let [results (mapv await! readers)
                           log (events)]
                       ;; Reads concurrent with appends may observe an earlier prefix.
                       (every? (fn [state]
                                 (let [n (:count state)]
                                   (and (integer? n) (<= 0 n (dec (count log)))
                                        (= state (reduce reducer {} (take (inc n) log))))))
                               results))
                     (finally
                       (deliver go true)
                       (doseq [w (conj readers writer)] (await! w))))))))
            expected (reduce reducer {} (events))
            settled-readers (mapv (fn [_] (future (project))) (range 3))
            settled (try (mapv await! settled-readers)
                         (finally (doseq [w settled-readers] (await! w))))
            _ (evict!)
            from-l2 (project)]
        {"Large projection uses segmented LMDB storage" (true? segmented?)
         "Concurrent reads equal a valid log-prefix replay" (every? true? rounds)
         "Every reader converges to full replay after writes stop"
         (and (= 50 (:count expected)) (every? #(= expected %) settled))
         "Segmented L2 reload converges to full replay" (= expected from-l2)}))))

(def ^:private ^:dynamic *role* nil)

(defn- checkpoint-race [store]
  (with-model store true
    (fn [{:keys [cache key l1-key context args project evict! append! events]}]
      (let [older-ready (promise) release-older (promise)
            reader-ready (promise) release-reader (promise)
            workers (atom [])
            pause-write! #(when (= *role* :older)
                            (deliver older-ready true) (await! release-older))
            ;; Instrument only this scenario's cache, never global KV functions.
            wrapped (reify kp/KVStore
                      (start [this] this)
                      (stop [_] nil)
                      (get! [_ request] (kv/get! cache request))
                      (read-snapshot [_ f]
                        (kv/read-snapshot cache
                          (fn [get-value]
                            (f (fn [{k :k :as request}]
                                 (let [value (get-value request)]
                                   (when (and (= *role* :reader)
                                              (java.util.Arrays/equals ^bytes key ^bytes k))
                                     (deliver reader-ready (fressian/decode value))
                                     (await! release-reader))
                                   value))))))
                      (put! [_ request] (pause-write!) (kv/put! cache request))
                      (put-batch! [_ request] (pause-write!) (kv/put-batch! cache request)))
            run #(rmp/p (assoc context :cache wrapped) args)]
        (try
          (project)
          (append! 10)
          (let [older (future (binding [*role* :older] (run)))]
            (swap! workers conj older)
            (await! older-ready)
            (append! 10)
            (let [newer (run)]
              (evict!)
              (let [reader (future (binding [*role* :reader] (run)))]
                (swap! workers conj reader)
                (let [manifest (await! reader-ready)
                      log (events)
                      expected (reduce reducer {} log)
                      head (:event/id (last log))]
                  (deliver release-older true)
                  (let [old-state (await! older)]
                    (deliver release-reader true)
                    (let [state (await! reader)
                          cached (cache-testing/l1-entry l1-key)
                          again (project)]
                      {"Race reached old and new checkpoints" (= [10 20] (mapv :count [old-state newer]))
                       "Reader captured the latest manifest before the older commit" (= head (:watermark manifest))
                       "Snapshot remains coherent across the older checkpoint commit" (= expected state)
                       "L1 state and watermark describe the same log prefix"
                       (and (= expected (:state cached)) (= head (:watermark cached)))
                       "Next ordinary read remains correct" (= expected again)}))))))
          (finally
            (deliver release-older true)
            (deliver release-reader true)
            (doseq [worker @workers] (await! worker))))))))

(defn segmented [store]
  {:checks (merge (concurrent-segmented store) (checkpoint-race store))})
