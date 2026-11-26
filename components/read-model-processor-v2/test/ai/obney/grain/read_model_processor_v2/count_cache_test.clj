(ns ai.obney.grain.read-model-processor-v2.count-cache-test
  "OPT-10 W9/W10: the per-transaction span-count, group-count and term-count caches change no
   stored byte. The same randomized history (filtered and unfiltered indexes,
   low-cardinality, unique and odd-typed group values, moves, deletes,
   exclusions, re-includes, growth across 16^2 and shrink, several batch sizes
   including one-event batches and cold first batches) is applied with the
   caches and with them disabled; every stored row must be raw-byte-equal and
   reads must match. FINAL (OPT-10 audit C1): the same holds when the caches'
   entry bound is forced tiny (mid-batch flushes between owners, down to a
   flush before every owner)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(def ^:private model :count-cache-test/items)
(def ^:private frozen-time (java.time.OffsetDateTime/parse "2026-10-16T00:00:00Z"))
(def ^:private pin "0f1e2d3c4b5a69788796a5b4c3d2e1f0")

(defn- register! []
  (rmp/register-read-model! model merge
    {:events #{:keyed/change} :version 1 :route :inputs
     :include? (fn [v] (and (not (:deleted v)) (not (:hidden v))))
     :indexes {:k :k :n :n :t (fn [v] [(:k v) (:g v)]) :odd :odd :u :u}
     :filtered-indexes #{:n :k}
     :searches {:w :w}}))

(def ^:private odd-values [5N (biginteger 7) (keyword "a/b" "c") :x/y "日本" "" nil 9223372036854775807N (int 3)])

(def ^:private words ["alpha" "alphabet" "beta" "gamma" "ünïcode" "日本語" "x" "ab" "lpha"])

(defn- history [seed n-events]
  (let [r (java.util.Random. seed)
        owner (fn [] (let [x (.nextInt r 700)] (if (even? x) x (str "o" x))))]
    (vec (for [_ (range n-events)]
           {:inputs (vec (for [_ (range (inc (.nextInt r 3)))]
                           [(owner)
                            (case (.nextInt r 10)
                              0 {:deleted true}
                              1 {:deleted false :hidden false}
                              2 {:hidden true}
                              {:k (nth [:a :b :c] (.nextInt r 3)) :n (.nextInt r 50) :g (.nextInt r 4)
                               :odd (nth odd-values (.nextInt r (count odd-values)))
                               :u (.nextLong r)
                               :w (when (pos? (.nextInt r 5))
                                    (apply str (interpose " " (repeatedly (inc (.nextInt r 4)) #(nth words (.nextInt r (count words)))))))})]))}))))

(defn- flush-counting
  "with-redefs map: the cache entry bound forced to bound (when given), and
   term-count-flush! calls counted into flushes."
  [bound flushes]
  (let [flush! @#'core/term-count-flush!]
    (cond-> {#'core/term-count-flush! (fn [tx] (when (seq (::core/term-counts tx)) (swap! flushes inc)) (flush! tx))}
      bound (assoc #'core/count-cache-flush-entries bound))))

(defn- run [cached? batch-size & [bound]]
  (fixture/keyed-fixture
    (fn []
      (let [counter (atom 0) flushes (atom 0)
            ctx (merge fixture/*keyed-ctx* {:tenant-id (java.util.UUID. 7 7) :read-model/promotion-key pin
                                            :read-model/batch-size batch-size :read-model/deferred-build-threshold 0})]
        (with-redefs-fn (merge {#'time/now (constantly frozen-time)
                                #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
                               (flush-counting bound flushes)
                               (when-not cached? {#'core/span-count-transaction identity}))
          (fn []
            (register!)
            (doseq [chunk (partition-all 150 (history 5 1500))]
              (fixture/keyed-append! ctx chunk)
              (rmp/progress ctx model))
            ;; shrink: delete most owners
            (fixture/keyed-append! ctx (for [x (range 0 700 1) :when (pos? (mod x 7))]
                                         {:inputs [[(if (even? x) x (str "o" x)) {:deleted true}]]}))
            (rmp/progress ctx model)
            {:rows (fixture/keyed-stored-bytes)
             :reads (vec (for [index [:identity :k :n :t :odd :u] direction [:asc :desc]]
                           (mapv :key (:entries (rmp/page ctx model {:index index :direction direction :limit 1000})))))
             :groups (vec (for [index [:k :n :t :odd :u]] (vec (rmp/index-groups ctx model index))))
             :search (vec (for [q ["lph" "pha" "日本語" "bet"]] (sort-by str (rmp/search-keys ctx model {:index :w :query q}))))
             :filtered (:keys (rmp/filtered-key-page ctx model (vec (range 0 700 3)) {:index :n :direction :desc :limit 50}))
             :flushes @flushes}))))))

(deftest caches-change-no-stored-byte
  (doseq [batch-size [1 7 128 4096]]
    (testing batch-size
      (let [a (run true batch-size) b (run false batch-size)]
        (is (pos? (count (:rows a))))
        (is (= (count (:rows a)) (count (:rows b))))
        (is (= (:rows a) (:rows b)) "raw-byte-equal with and without the count caches")
        (is (= (dissoc a :rows :flushes) (dissoc b :rows :flushes)))))))

(deftest a-tiny-cache-bound-flushes-mid-batch-without-changing-bytes
  ;; Bound 0 flushes before every owner; 1, 5 and 37 flush at varying points
  ;; inside batches. Every variant is raw-byte-equal to the uncached path at
  ;; the same batching, and its reads equal the one-event-batch run's reads.
  (let [one (dissoc (run false 1) :rows :flushes)]
    (doseq [batch-size [128 4096]
            :let [uncached (run false batch-size)
                  unbounded (run true batch-size)]]
      (is (= one (dissoc uncached :rows :flushes)) [batch-size :reads-equal-one-event-batches])
      (doseq [bound [0 1 5 37]]
        (testing [batch-size bound]
          (let [a (run true batch-size bound)]
            (is (> (:flushes a) (:flushes unbounded)) "the bound forced extra mid-batch flushes")
            (is (= (count (:rows uncached)) (count (:rows a))))
            (is (= (:rows uncached) (:rows a)) "raw-byte-equal to the uncached path")
            (is (= one (dissoc a :rows :flushes)) "reads equal the one-event-batch run")))))))

(def ^:private big-model :count-cache-test/big)

(defn- run-big
  "Few large batches over thousands of owners: every batch adds/moves members
   whose promotion splits level-2/3 spans while the level-1/2 span counts they
   sum (ranked-count-range) are still dirty in the span-count cache."
  [cached? & [bound]]
  (fixture/keyed-fixture
    (fn []
      (let [counter (atom 0)
            ctx (merge fixture/*keyed-ctx* {:tenant-id (java.util.UUID. 8 8) :read-model/promotion-key pin
                                            :read-model/batch-size 3000 :read-model/deferred-build-threshold 0})
            r (java.util.Random. 3)]
        (with-redefs-fn (merge {#'time/now (constantly frozen-time)
                                #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
                               (when bound {#'core/count-cache-flush-entries bound})
                               (when-not cached? {#'core/span-count-transaction identity}))
          (fn []
            (rmp/register-read-model! big-model merge
              {:events #{:keyed/change} :version 1 :key :owner
               :indexes {:u :u :k :k} :filtered-indexes #{:u}})
            ;; a small first batch (cold builders), then large incremental batches
            (fixture/keyed-append! ctx (for [o (range 40)] {:owner o :u (.nextLong r) :k (.nextInt r 3)}))
            (rmp/progress ctx big-model)
            (doseq [part (range 3)]
              (fixture/keyed-append! ctx (for [o (range (+ 40 (* part 2500)) (+ 40 (* (inc part) 2500)))]
                                           {:owner o :u (.nextLong r) :k (.nextInt r 3)}))
              (rmp/progress ctx big-model))
            ;; one large batch of moves over every owner
            (fixture/keyed-append! ctx (for [o (range 0 7540 3)] {:owner o :u (.nextLong r)}))
            (rmp/progress ctx big-model)
            {:rows (fixture/keyed-stored-bytes)
             :page (mapv :key (:entries (rmp/page ctx big-model {:index :u :page 37 :limit 50})))
             :rank (rmp/index-rank ctx big-model {:index :k :equals 1} 4999)}))))))

(deftest caches-change-no-stored-byte-in-large-batches
  (let [a (run-big true) b (run-big false)]
    (is (= (count (:rows a)) (count (:rows b))))
    (is (= (:rows a) (:rows b)) "raw-byte-equal with and without the count caches")
    (is (= (dissoc a :rows) (dissoc b :rows)))))

(deftest a-tiny-cache-bound-changes-no-stored-byte-in-large-batches
  (let [b (run-big false)]
    (doseq [bound [3 200]]
      (let [a (run-big true bound)]
        (is (= (count (:rows a)) (count (:rows b))) [bound])
        (is (= (:rows a) (:rows b)) [bound "raw-byte-equal to the uncached path"])
        (is (= (dissoc a :rows) (dissoc b :rows)) [bound])))))
