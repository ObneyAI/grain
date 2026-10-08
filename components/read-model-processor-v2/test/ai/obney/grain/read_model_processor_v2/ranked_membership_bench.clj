(ns ai.obney.grain.read-model-processor-v2.ranked-membership-bench
  "Actual LMDB keyed-fold benchmark; no alternate pagination implementation.
   Run with component tests on the classpath. Arguments: population report.edn."
  (:refer-clojure :exclude [bean run!])
  (:require [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core.namespace-registry :as namespaces]
            [ai.obney.grain.read-model-processor-v2.core.ranked-set :as ranked]
            [ai.obney.grain.read-model-processor-v2.ranked-set-test :as rank-test]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]
           [java.util UUID]))

(def bean ^ThreadMXBean (ManagementFactory/getThreadMXBean))
(defschemas schemas {:rank-benchmark/change [:map]})
(defn allocated [] (.getThreadAllocatedBytes bean (.getId (Thread/currentThread))))
(defn measure [f]
  (let [before (allocated) start (System/nanoTime) result (f)]
    {:ms (/ (- (System/nanoTime) start) 1e6)
     :mib (/ (- (allocated) before) 1048576.0) :result result}))
(defn stats [samples]
  {:mean-ms (/ (reduce + (map :ms samples)) (count samples))
   :p95-ms (nth (vec (sort (map :ms samples))) (dec (int (Math/ceil (* 0.95 (count samples))))))
   :mean-mib (/ (reduce + (map :mib samples)) (count samples))})
(defn samples [f]
  (dotimes [_ 10] (f))
  (stats (repeatedly 30 #(dissoc (measure f) :result))))
(defn storage
  "Stored key/value bytes in the model's current KF9 namespace, excluding
   namespace registry records and the shared identity namespace."
  [ctx model]
  (let [identity [(:tenant-id ctx) model 1]
        prefix (get (namespaces/resolve! (:cache ctx) [identity]) identity)]
    (kv/transact (:cache ctx) :read
      #((:reduce %) {:prefix prefix}
         (fn [out {:keys [k v]}]
           (-> out (update :entries inc) (update :bytes + (alength ^bytes k) (alength ^bytes v))))
         {:entries 0 :bytes 0}))))
(defn comparable [page] (select-keys page [:keys :total :total-pages :page :page-size]))
(defn run! [n]
  (let [dir (str "/tmp/grain-filtered-ranks-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "folds"
                                             :map-size (* 4 1024 1024 1024)}))
        ctx {:cache cache :event-store store :tenant-id (random-uuid)}
        ids (mapv #(UUID. 0 %) (range n))
        events (mapv (fn [i] (es/->event {:type :rank-benchmark/change
                                         :body {:owner (ids i)
                                                :name (format "name-%06d" (mod (* i 7919) n))
                                                :text (if (zero? (mod i 100)) "narrow example" "example")
                                                :included? (not (zero? (mod i 997)))}})) (range n))
        plain :rank-benchmark/plain indexed :rank-benchmark/indexed
        opts {:events #{:rank-benchmark/change} :version 1 :key :owner
              :include? :included? :indexes {:name :name} :searches {:text :text}}
        step (fn [_ e] (select-keys e [:name :text :included?]))]
    (try
      (rmp/register-read-model! plain step opts)
      (rmp/register-read-model! indexed step (assoc opts :filtered-indexes #{:name}))
      (es/append store {:tenant-id (:tenant-id ctx) :events events})
      ;; Same promotion decisions for both indexes: isolate membership overhead.
      (with-redefs [ranked/random-hash rank-test/crc-hash]
        (let [build (into {} (for [model [plain indexed]]
                               (do (println :building n model) (flush)
                                   [model (dissoc (measure #(rmp/progress ctx model)) :result)])))
              _ (assert (= (count (remove #(zero? (mod % 997)) (range n)))
                           (:total (rmp/page ctx plain {:index :name}))) "Fixture population missing")
              sizes (into {} (for [model [plain indexed]] [model (storage ctx model)]))
              _ (assert (every? #(and (pos? (:entries %)) (pos? (:bytes %))) (vals sizes))
                        "Materialized model namespace storage missing")
              candidates [[:absent []] [:sparse (vec (take-nth 100 ids))]
                          [:half (vec (take-nth 2 ids))] [:broad ids]]
              cases
              (rmp/read-session ctx [plain indexed]
                (fn [prepared]
                  (vec
                    (for [[label keys] candidates page [1 999999] direction [:asc :desc]]
                      (let [options {:index :name :page page :limit 25 :direction direction}
                            baseline #(rmp/key-page prepared plain keys options)
                            experiment #(rmp/filtered-key-page prepared indexed keys options)
                            before (baseline) after (experiment)
                            _ (assert (= (comparable before) (comparable after)) "Pagination mismatch")
                            a (samples baseline) b (samples experiment)
                            observed (volatile! {})
                            counters (rmp/filtered-key-page
                                       (assoc prepared :read-model/observer
                                              (fn [{:keys [operation bytes]}]
                                                (vswap! observed update operation
                                                        (fn [v] (-> (or v {:calls 0 :bytes 0})
                                                                    (update :calls inc) (update :bytes + bytes))))))
                                       indexed keys options)
                            result {:case label :page (:page after) :direction direction
                                    :total (:total after) :baseline a :ranked b
                                    :work (dissoc counters :keys) :observed @observed}]
                        (println (pr-str (dissoc result :observed))) (flush)
                        result)))))
              search-cases
              (rmp/read-session ctx [plain indexed]
                (fn [prepared]
                  (vec (for [query ["missing" "narrow" "example"]]
                         (let [options {:index :name :page 999999 :limit 25}
                               baseline #(rmp/key-page prepared plain
                                                       (rmp/search-keys prepared plain {:index :text :query query}) options)
                               experiment #(rmp/filtered-key-page prepared indexed
                                                               (rmp/search-keys prepared indexed {:index :text :query query}) options)
                               _ (assert (= (comparable (baseline)) (comparable (experiment))))]
                           {:query query :baseline (samples baseline) :ranked (samples experiment)})))))
              _ (es/append store {:tenant-id (:tenant-id ctx)
                                  :events (mapv #(es/->event {:type :rank-benchmark/change
                                                             :body {:owner (ids %) :name (str "moved-" %)
                                                                    :text "changed example" :included? (even? %)}})
                                                (range 100))})
              updates (into {} (for [model [plain indexed]]
                                [model (dissoc (measure #(rmp/progress ctx model)) :result)]))]
          {:population n :java (System/getProperty "java.version") :warmups 10 :samples 30
           :build build :storage sizes :update-100 updates :pages cases :search-and-page search-cases}))
      (finally
        (kv/stop cache) (es/stop store)
        (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true))))))

(defn -main [population report]
  (let [result (run! (parse-long population))]
    (with-open [writer (io/writer report)] (binding [*out* writer] (pprint/pprint result)))
    (println :report report))
  (shutdown-agents))
