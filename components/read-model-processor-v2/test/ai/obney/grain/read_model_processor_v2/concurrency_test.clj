(ns ai.obney.grain.read-model-processor-v2.concurrency-test
  (:require [clojure.test :refer [deftest is]]
         [ai.obney.grain.read-model-processor-v2.interface :as rmp]
         [ai.obney.grain.read-model-processor-v2.core :as core]
         [ai.obney.grain.read-model-processor-v2.l1-cache :as l1]
         [ai.obney.grain.event-store-v3.interface :as es]
         [ai.obney.grain.kv-store.interface :as kv]
         [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
         [ai.obney.grain.fressian-util.interface :as fressian]
         [ai.obney.grain.schema-util.interface :refer [defschemas]]
         [clojure.java.io :as io]))

(defschemas race-schemas
  {:race/seed [:map]
   :race/increment [:map]})

(def ^:dynamic *role* nil)

(defn await! [p]
  (let [result (deref p 20000 ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Timed out waiting for deterministic race barrier" {})))
    result))

(defn check! [condition message]
  (when-not (is condition message) (throw (ex-info message {}))))

(defn reproduce! [segmented?]
  (let [dir (str "/tmp/grain-cache-race-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "race"}))
        tenant (random-uuid)
        name (if segmented? :race/segmented :race/monolithic)
        base-key (core/format-scoped-key name 1 tenant)
        reducer (fn [state event]
                  (case (:event/type event)
                    :race/seed (assoc (if segmented?
                                       (into {} (map (fn [i] [i 0]) (range 10001)))
                                       {})
                                     :count 0)
                    :race/increment (update state :count inc)))
        context {:event-store store :cache cache :tenant-id tenant}
        query {:tenant-id tenant :types #{:race/seed :race/increment}}
        args {:f reducer :query (dissoc query :tenant-id)
              :name name :version 1 :l1-ttl-ms 0}
        project #(rmp/p context args)
        append! (fn [type n]
                  (es/append store {:tenant-id tenant
                                    :events (mapv (fn [_] (es/->event {:type type}))
                                                  (range n))}))
        older-ready (promise)
        release-older (promise)
        reader-ready (promise)
        release-reader (promise)
        workers (atom [])
        original-snapshot kv/read-snapshot
        original-put kv/put!
        original-batch kv/put-batch!
        pause-older! (fn []
                       (when (= *role* :older)
                         (deliver older-ready true)
                         (await! release-older)))]
    (try
      (rmp/l1-clear!)
      (append! :race/seed 1)
      (check! (= 0 (:count (project))) "Seed projection failed")
      (let [initial (fressian/decode (kv/get! cache {:k base-key}))]
        (check! (= segmented? (boolean (:segmented initial))) "Wrong cache format"))
      (append! :race/increment 10)
      ;; Wrappers only pause real operations at legal scheduling boundaries.
      ;; All bytes, event reads, reducers, and LMDB commits remain unchanged.
      (with-redefs [kv/read-snapshot
                    (fn [c f]
                      (original-snapshot
                       c
                       (fn [get-value]
                         (f (fn [{:keys [k] :as request}]
                              (let [bytes (get-value request)]
                                (when (and (= *role* :reader)
                                           (java.util.Arrays/equals ^bytes k ^bytes base-key))
                                  (deliver reader-ready (fressian/decode bytes))
                                  (await! release-reader))
                                bytes))))))
                    kv/put! (fn [c request]
                              (pause-older!)
                              (original-put c request))
                    kv/put-batch! (fn [c request]
                                    (pause-older!)
                                    (original-batch c request))]
        (try
          ;; A computes count=10 and updates L1, then pauses before its L2 write.
          (let [older (future (binding [*role* :older] (project)))]
            (swap! workers conj older)
            (await! older-ready)
            ;; B sees ten more events and publishes a coherent count=20 snapshot.
            (append! :race/increment 10)
            (check! (= 20 (:count (project))) "Newer writer did not reach 20")
            ;; Force C through L2. This is also the path taken after L1 eviction.
            (rmp/l1-clear!)
            (let [reader (future (binding [*role* :reader] (project)))]
              (swap! workers conj reader)
              (let [captured (await! reader-ready)
                    events (into [] (es/read store query))
                    head (:event/id (last events))
                    expected (reduce reducer {} events)]
                (check! (= head (:watermark captured)) "Reader did not capture latest watermark")
                ;; A now commits the older snapshot while C retains the new manifest.
                (deliver release-older true)
                (check! (= 10 (:count (await! older))) "Older writer did not finish at 10")
                (deliver release-reader true)
                (let [actual (await! reader)
                      cached (l1/get-entry (String. ^bytes base-key))
                      remaining (into [] (es/read store (assoc query :after (:watermark cached))))
                      again (project)
                      result {:format (if segmented? :segmented :monolithic)
                              :log-increments 20
                              :full-replay (:count expected)
                              :reader-result (:count actual)
                              :l1-count (get-in cached [:state :count])
                              :l1-watermark-is-log-head (= head (:watermark cached))
                              :events-after-l1-watermark (count remaining)
                              :next-read (:count again)}]
                  (prn result)
                  (check! (= head (:watermark cached)) "L1 watermark is not at head")
                  (check! (empty? remaining) "Unexpected unprocessed events")
                  (check! (= expected actual (:state cached) again)
                          "Every read must equal the pure fold through its watermark")
                  (check! (= 20 (:count expected)) "Full replay oracle failed")))))
          (finally
            ;; Join workers before restoring the wrapped Vars or closing LMDB.
            (deliver release-older true)
            (deliver release-reader true)
            (doseq [worker @workers]
              (when (= ::timeout (deref worker 20000 ::timeout))
                (future-cancel worker))))))
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (doseq [file (reverse (file-seq (io/file dir)))]
          (io/delete-file file true))))))


(deftest monolithic-cache-remains-coherent-during-checkpoint-rollback
  (reproduce! false))

(deftest segmented-cache-remains-coherent-during-checkpoint-rollback
  (reproduce! true))
