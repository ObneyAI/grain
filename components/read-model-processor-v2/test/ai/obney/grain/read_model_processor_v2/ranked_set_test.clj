;; Includes adaptations of FoundationDB RankedSetTest.java, commit
;; 8a20d30f318e529fa30084b265563e64eb3956e1.
;; Copyright 2015-2018 Apple Inc. and the FoundationDB project authors.
;; SPDX-License-Identifier: Apache-2.0
;; Modifications: Clojure tests over Grain LMDB transactions, unique membership
;; with payloads, and additional prefix, mutation, rollback and snapshot cases.
;; License and attribution: resources/META-INF/licenses/.
(ns ai.obney.grain.read-model-processor-v2.ranked-set-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.ranked-set :as ranked]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [clojure.java.io :as io])
  (:import [java.nio ByteBuffer]
           [java.util Arrays Random UUID]
           [java.util.zip CRC32]))

(def ^:dynamic *store* nil)
(defn fixture [f]
  (let [dir (str "/tmp/grain-ranked-test-" (random-uuid))
        store (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "ranked"}))]
    (binding [*store* store]
      (try (f)
           (finally
             (kv/stop store)
             (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true)))))))
(use-fixtures :each fixture)

(defn crc-hash [^bytes k]
  (let [crc (CRC32.)] (.update crc k) (unchecked-int (.getValue crc))))
(defn id-bytes [^UUID id]
  (.array (doto (ByteBuffer/allocate 16)
            (.putLong (.getMostSignificantBits id)) (.putLong (.getLeastSignificantBits id)))))
(defn level-entries [tx cfg level]
  ((:reduce tx) {:prefix (ranked/prefix cfg level)}
   (fn [out {:keys [k]}]
     (let [member (ranked/unpack cfg k)]
       (conj out [member (ranked/count-key tx cfg level member)]))) []))
(defn check-spans [tx cfg]
  ;; Upstream checkConsistency: each span is the sum of its lower-level range.
  (doseq [[k n] (level-entries tx cfg 0)]
    (is (= n (if (zero? (alength ^bytes k)) 0 1))))
  (doseq [level (range 1 (:levels cfg))]
    (let [entries (level-entries tx cfg level)]
      (doseq [[[k n] next] (map vector entries (concat (rest entries) [nil]))]
        (is (= n (ranked/count-range tx cfg (dec level) k (first next))))))))
(defn check-set [tx cfg expected]
  (let [expected (vec (sort #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) expected))]
    (check-spans tx cfg)
    (is (= (count expected) (ranked/size tx cfg)))
    (is (nil? (ranked/get-nth tx cfg -1)))
    (is (nil? (ranked/get-nth tx cfg (count expected))))
    (doseq [[i k] (map-indexed vector expected)]
      (is (= i (ranked/rank tx cfg k true)))
      (is (Arrays/equals ^bytes k ^bytes (ranked/get-nth tx cfg i))))))

(deftest upstream-unique-set-cases-with-payloads
  ;; Derived from basic/basicCrc/basicChange/basicRandom/rankAsThoughPresent.
  (doseq [[i hash-fn] (map-indexed vector [#(Arrays/hashCode ^bytes %) crc-hash ranked/random-hash])]
    (let [cfg (ranked/config (byte-array [(inc i)]) hash-fn 6)
          encode #(.array (doto (ByteBuffer/allocate 4) (.putInt (int %))))
          keys (mapv encode [5 15 25 35 45])
          payload (byte-array [42 7 9])]
      (kv/transact *store* :read
        (fn [tx]
          (is (= 0 (ranked/rank tx cfg (encode 9) false)))
          (is (nil? (ranked/rank tx cfg (encode 9) true)))
          (check-set tx cfg [])))
      (kv/transact *store* :write
        (fn [tx]
          (ranked/init! tx cfg)
          (doseq [k (reverse keys)] (is (true? (ranked/add! tx cfg k payload))))
          (ranked/init! tx cfg)
          (check-set tx cfg keys)))
      (kv/transact *store* :write
        (fn [tx]
          (doseq [k keys]
            (is (false? (ranked/add! tx cfg k (byte-array [99]))))
            (is (Arrays/equals payload ^bytes ((:get tx) (ranked/pack cfg 0 k)))))
          (doseq [i (range 56)]
            (is (= (count (filter #(< % i) [5 15 25 35 45]))
                   (ranked/rank tx cfg (encode i) false))))
          (is (false? (ranked/remove! tx cfg (encode 99))))
          (doseq [k keys] (is (true? (ranked/remove! tx (assoc cfg :hash-fn crc-hash) k))))
          (check-set tx cfg [])))
      (is (thrown? IllegalArgumentException
                   (kv/transact *store* :read #(ranked/rank % cfg (byte-array 0) false)))))))

(deftest prefix-keys-random-mutations-and-rollback
  (let [cfg (ranked/config (byte-array [12]) crc-hash 6)
        keys (vec (concat (map byte-array [[0] [0 0] [0 0 0] [0 1] [1] [1 0] [1 -1] [-1] [-1 0] [-1 -1]])
                          (map #(id-bytes (UUID. % %)) (range 80))))
        model (atom #{}) random (Random. 724)]
    (kv/transact *store* :write #(ranked/init! % cfg))
    (dotimes [_ 30]
      (kv/transact *store* :write
        (fn [tx]
          (dotimes [_ 20]
            (let [i (.nextInt random (count keys)) add? (.nextBoolean random)
                  had (contains? @model i)]
              (is (= (if add? (not had) had)
                     ((if add? ranked/add! ranked/remove!) tx cfg (nth keys i))))
              (swap! model (if add? conj disj) i)))))
      (kv/transact *store* :read #(check-set % cfg (map keys @model))))
    (is (thrown-with-msg? Exception #"rollback"
          (kv/transact *store* :write
            #(do (doseq [k keys] (ranked/add! % cfg k)) (throw (ex-info "rollback" {}))))))
    (kv/transact *store* :read #(check-set % cfg (map keys @model)))))

(deftest colliding-id-patterns-do-not-determine-promotion
  (let [random (Random. 42)
        cfg (ranked/config (byte-array [13]) (fn [_] (.nextInt random)) 6)
        keys (mapv #(id-bytes (UUID. % %)) (range 4096))]
    (kv/transact *store* :write
      #(do (ranked/init! % cfg) (doseq [k keys] (ranked/add! % cfg k))))
    (kv/transact *store* :read
      (fn [tx]
        (check-spans tx cfg)
        (let [visits (atom 0)
              counted (assoc tx :reduce
                             (fn [bounds rf initial]
                               ((:reduce tx) bounds
                                (fn [acc entry] (swap! visits inc) (rf acc entry)) initial)))]
          (is (Arrays/equals ^bytes (peek keys) ^bytes (ranked/get-nth counted cfg 4095)))
          (is (< @visits 500) "Seek skips spans instead of following the ID pattern"))))))

(deftest concurrent-writers-and-snapshot-isolation
  (let [cfg (ranked/config (byte-array [14]))
        keys (mapv #(id-bytes (UUID. 0 %)) (range 200))]
    (kv/transact *store* :write #(ranked/init! % cfg))
    (let [writers (mapv (fn [batch]
                         (future (kv/transact *store* :write #(doseq [k batch] (ranked/add! % cfg k)))))
                       (partition-all 50 keys))]
      (doseq [writer writers] (is (not= ::timeout (deref writer 20000 ::timeout)))))
    (kv/transact *store* :read
      (fn [tx]
        (check-set tx cfg keys)
        (is (not= ::timeout
                  (deref (future (kv/transact *store* :write #(doseq [k (take 100 keys)] (ranked/remove! % cfg k))))
                         20000 ::timeout)))
        (check-set tx cfg keys)))
    (kv/transact *store* :read #(check-set % cfg (drop 100 keys)))))
