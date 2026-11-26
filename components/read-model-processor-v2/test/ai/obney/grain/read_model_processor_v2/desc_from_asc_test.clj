(ns ai.obney.grain.read-model-processor-v2.desc-from-asc-test
  "OPT-5/OPT-7: descending orders are read from the ascending index
   structure. The oracle is the order of the former (INT, KF9) stored descending copy:
   members sorted by inverted value bytes followed by the ascending id suffix
   (so equal values stay ascending by id), computed here independently."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core])
  (:import [java.util Arrays]))

(def ^:private statuses [:open :open :open :open :closed :closed :held])
(def ^:private words ["alpha" "bravo" "Bravo" "ünïcode" "日本語" "a\u0000b" "ā" "zz" ""])

(def ^:private owners
  ;; Colliding identity strings ("1" and 1, ":k1" and :k1) form identity runs.
  (vec (concat (range 260) (map #(str "s" %) (range 200)) (map #(java.util.UUID. 5 %) (range 120))
               ["1" "2" "17" ":k1" (keyword "k1") (keyword "k2") ":k2"])))

(defn- history [seed n]
  (let [r (java.util.Random. seed) pick #(nth % (.nextInt r (count %))) counter (atom 0)]
    (vec (concat
          ;; every owner once, then random updates, deletes and re-includes
          (for [batch (partition-all 50 owners)]
            {:inputs (vec (for [o batch]
                            [o {:deleted false :u (swap! counter inc) :status (pick statuses)
                                :group (pick [:a :a :b :c]) :score (.nextInt r 4)
                                :name (pick words) :maybe (when (zero? (.nextInt r 5)) (.nextInt r 3))}]))})
          (for [_ (range n)]
            {:inputs (vec (for [_ (range (inc (.nextInt r 4)))]
                            [(pick owners)
                             (case (.nextInt r 10)
                               0 {:deleted true}
                               1 {:deleted false}
                               2 {:u (swap! counter inc)}
                               3 {:status (pick statuses)}
                               4 {:name (pick words)}
                               {:score (.nextInt r 4) :group (pick [:a :b :c])})]))})))))

(def ^:private index-fns
  {:u :u :status :status :pair (fn [v] [(:group v) (:score v)]) :name :name :nilly :maybe})

(defn- register! [model filtered]
  (rmp/register-read-model! model merge
    {:events #{:keyed/change} :version 1 :route :inputs
     :include? (fn [v] (not (:deleted v)))
     :indexes index-fns
     :filtered-indexes filtered}))

(defn- member
  "KF9 stored member of index for value and id in direction (the oracle)."
  [filtered index direction value id]
  (let [desc (fn [^bytes b] (if (= :desc direction) (core/key-descending b) b))]
    (if (and (= :identity index) (not (contains? filtered :identity)))
      (core/key-join (desc (core/key-encode-compact value)) (core/key-encode id))
      (core/key-join (desc (core/key-encode value)) (core/key-encode (str id)) (core/key-encode id)))))

(defn- index-value [index id v] (if (= :identity index) (str id) ((index-fns index) v)))

(defn- ordered
  "Ids of results (id -> value) in the KF9 order of index and direction."
  [filtered results index direction]
  (->> results
       (map (fn [[id v]] [(member filtered index direction (index-value index id v) id) id]))
       (sort (fn [[a _] [b _]] (Arrays/compareUnsigned ^bytes a ^bytes b)))
       (mapv second)))

(defn- page-of [ids page limit]
  (let [total (count ids) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min page pages) offset (* (dec page) limit)]
    {:keys (subvec ids (min offset total) (min total (+ offset limit))) :total total :page page}))

(defn- page-numbers [total limit]
  (let [pages (max 1 (quot (+ total (dec limit)) limit))]
    (filter pos? (distinct (concat (range 1 (min (inc pages) 40)) [(quot pages 2) (dec pages) pages (inc pages)])))))

(defn- check-model! [ctx model filtered]
  (let [results (into {} (map (juxt :key :value)) (rmp/values ctx model))
        n (count results)]
    (is (< 300 n))
    (doseq [index (cons :identity (keys index-fns)) direction [:asc :desc]
            :let [expected (ordered filtered results index direction)
                  opts {:index index :direction direction}]]
      (testing (pr-str [model index direction])
        (is (= expected (mapv :key (rmp/values ctx model opts))) "values")
        (is (= (vec (take 5 expected))
               (mapv :key (reduce (fn [acc e] (if (= 5 (count acc)) (reduced acc) (conj acc e)))
                                  [] (rmp/values ctx model opts)))) "early-terminated values")
        (doseq [limit [1 7 25 1000] page (page-numbers n limit)
                :when (or (not= 1 limit) (zero? (mod page 9)) (< page 4) (> page (- n 3)))]
          (let [p (rmp/page ctx model (assoc opts :page page :limit limit))
                e (page-of expected page limit)]
            (is (= (:keys e) (mapv :key (:entries p))) (pr-str [:page limit page]))
            (is (= [(:total e) (:page e)] [(:total p) (:page p)]))))
        ;; equality selections and tuple prefixes, incl. absent values
        (doseq [[sel keep?] (case index
                              :status [[{:equals :open} #(= :open (:status %))]
                                       [{:equals :held} #(= :held (:status %))]
                                       [{:equals :absent} (constantly false)]]
                              :pair [[{:prefix [:a]} #(= :a (:group %))]
                                     [{:prefix [:a 2]} #(and (= :a (:group %)) (= 2 (:score %)))]
                                     [{:prefix []} (constantly true)]
                                     [{:prefixes [[:c] [:a] [:a 1]]} #(#{:a :c} (:group %))]
                                     [{:prefixes [[:b 0] [:b 3] [:zz]]} #(and (= :b (:group %)) (#{0 3} (:score %)))]]
                              :nilly [[{:equals nil} #(nil? (:maybe %))]]
                              :identity [[{:equals "1"} (fn [_] false)]]
                              [])
                :let [sub-expected (if (= :identity index)
                                     (filterv #(= "1" (str %)) expected)
                                     (filterv #(keep? (get results %)) expected))]]
          (when-not (contains? sel :prefixes)
            (is (= sub-expected (mapv :key (rmp/values ctx model (merge opts sel)))) (pr-str [:values sel])))
          (doseq [limit [1 3 25] page (page-numbers (count sub-expected) limit)]
            (let [p (rmp/page ctx model (merge opts sel {:page page :limit limit}))]
              (is (= (:keys (page-of sub-expected page limit)) (mapv :key (:entries p))) (pr-str [:page sel limit page]))
              (is (= (count sub-expected) (:total p))))))
        ;; equality groups read the same in both directions
        (when (= :status index)
          (let [group (filterv #(= :open (:status (get results %))) (ordered filtered results index :asc))]
            (doseq [offset [0 1 17 (dec (count group))]]
              (is (= (vec (take 9 (drop offset group)))
                     (rmp/index-keys ctx model (assoc opts :equals :open :offset offset :limit 9)))))
            (doseq [id (take 20 group)]
              (is (= (.indexOf ^java.util.List group id)
                     (:rank (rmp/index-rank ctx model {:index :status :direction direction :equals :open} id)))))))
        ;; filtered (ranked) pages over candidate subsets
        (when (contains? filtered index)
          (let [ids (vec (keys results)) r (java.util.Random. 11)
                subsets [ids (filterv (fn [_] (< (.nextInt r 10) 3)) ids) (vec (take 5 (reverse expected)))
                         (conj (vec (take-nth 7 expected)) "ghost") []]]
            (doseq [candidates subsets limit [1 10] page (page-numbers (count (set candidates)) limit)
                    :when (or (= 10 limit) (< page 5) (zero? (mod page 13)))]
              (let [cset (set candidates)
                    sub (filterv cset expected)
                    p (rmp/filtered-key-page ctx model (shuffle candidates) (assoc opts :page page :limit limit))
                    e (page-of sub page limit)]
                (is (= (:keys e) (:keys p)) (pr-str [:filtered (count candidates) limit page]))
                (is (= [(:total e) (:page e)] [(:total p) (:page p)]))))))))))

(defn- run-case! [opts]
  (fixture/keyed-fixture
    (fn []
      (let [ctx (merge fixture/*keyed-ctx* opts)
            a (keyword "desc-from-asc" (str "a" (random-uuid)))
            b (keyword "desc-from-asc" (str "b" (random-uuid)))]
        (register! a #{:status :u :pair})
        (register! b #{:identity :name :nilly})
        (let [[initial & updates] (partition-all 300 (history 3 500))]
          (fixture/keyed-append! ctx (vec initial))
          (check-model! ctx a #{:status :u :pair})
          (check-model! ctx b #{:identity :name :nilly})
          ;; incremental maintenance on top of the first build
          (doseq [u updates] (fixture/keyed-append! ctx (vec u)))
          (check-model! ctx a #{:status :u :pair})
          (check-model! ctx b #{:identity :name :nilly}))))))

(deftest desc-reads-match-the-stored-descending-order-incremental
  (run-case! {:read-model/batch-size 64 :read-model/deferred-build-threshold 0}))

(deftest desc-reads-match-the-stored-descending-order-deferred-build
  (run-case! {:read-model/deferred-build-threshold 16}))

(deftest joined-desc-pages-with-colliding-virtual-members
  (fixture/keyed-fixture
    (fn []
      (let [members (fixture/keyed-register! merge {:include? :member?})
            ledger (fixture/keyed-register! merge {:include? :ledger? :indexes {:balance :balance}
                                                   :filtered-indexes #{:balance}})
            rows (mapv (fn [i] {:owner (if (even? i) (str i) i) :member? (not (zero? (mod i 11)))
                                :ledger? (not (zero? (mod i 3)))
                                :balance (nth [-100 0 0 0 100 200] (mod i 6))}) (range 700))
            expected (fn [options]
                       (let [candidates (when (contains? options :keys) (set (:keys options)))
                             value #(if (:ledger? %) (:balance %) (:missing-value options))
                             ids (->> rows
                                      (filter #(and (:member? %) (or (nil? candidates) (candidates (:owner %)))))
                                      (map (fn [row] [(member #{:balance} :balance (:direction options) (value row) (:owner row))
                                                      (:owner row)]))
                                      (sort (fn [[a _] [b _]] (Arrays/compareUnsigned ^bytes a ^bytes b)))
                                      (mapv second))]
                         (page-of ids (:page options) (:limit options))))]
        (fixture/keyed-append! rows)
        (rmp/read-session fixture/*keyed-ctx* [members ledger]
          (fn [ctx]
            (doseq [direction [:asc :desc] missing [-250 0 100 250] limit [1 9 50]
                    selection [{} {:keys (concat (map :owner (take-nth 3 rows)) ["ghost" "2" 2])}]
                    page (page-numbers 700 limit)
                    :when (or (not= 1 limit) (< page 6) (zero? (mod page 37)))
                    :let [options (merge {:index :balance :direction direction :page page :limit limit
                                          :missing-value missing} selection)
                          e (expected options)
                          p (rmp/joined-page ctx members ledger options)]]
              (is (= (:keys e) (:keys p)) (pr-str (dissoc options :keys)))
              (is (= [(:total e) (:page e)] [(:total p) (:page p)])))))))))

(deftest no-descending-copy-is-stored
  (fixture/keyed-fixture
    (fn []
      (let [model (keyword "desc-from-asc" (str "c" (random-uuid)))]
        (register! model #{:status :identity})
        (fixture/keyed-append! (vec (take 3 (history 5 0))))
        (rmp/progress fixture/*keyed-ctx* model)
        (let [identity [(:tenant-id fixture/*keyed-ctx*) model 1]
              b (get (core/namespace-resolve! (:cache fixture/*keyed-ctx*) [identity]) identity)
              rows (fixture/keyed-stored-bytes)
              index-rows (filter (fn [[k _]] (and (< (count b) (count k)) (= (vec b) (subvec k 0 (count b)))
                                                  (= 5 (nth k (count b))))) rows)
              direction-of (fn [index]
                             ;; OPT-8: kind-5 keys carry the index ordinal, not its name
                             (let [p (vec (core/key-join b (byte-array [5]) (#'core/keyed-index-ordinal-bytes {:indexes index-fns} index)))]
                               (set (keep (fn [[k _]] (when (= p (subvec k 0 (min (count k) (count p))))
                                                        (nth k (count p)))) index-rows))))]
          (is (seq index-rows))
          (doseq [index (cons :identity (keys index-fns))]
            (is (= #{0} (direction-of index)) (pr-str index))))))))

(deftest key-skip-splits-every-encodable-value-from-its-suffix
  ;; Descending reads find a member's value run with core/key-skip; it must
  ;; agree with both encoders for every value type key-encode accepts.
  (let [r (java.util.Random. 57)
        chars (mapv char [0x61 0x5a 0x30 0 0x7f 0x80 0xff 0x100 0x101 0x4e2d 0xff00 0xffff 0xd83d 0xde00])
        rand-string #(apply str (repeatedly (.nextInt r 6) (fn [] (nth chars (.nextInt r (count chars))))))
        rand-value (fn rand-value [depth]
                     (case (.nextInt r (if (pos? depth) 9 8))
                       0 nil 1 (.nextBoolean r) 2 (.nextLong r) 3 (long (- (.nextInt r 5) 2))
                       4 (rand-string) 5 (keyword (str "k" (rand-string)))
                       6 (java.util.UUID. (.nextLong r) (.nextLong r))
                       7 (keyword "ns" "name")
                       8 (vec (repeatedly (.nextInt r 4) #(rand-value (dec depth))))))]
    (dotimes [_ 20000]
      (let [v (rand-value 3)
            suffix (core/key-join (core/key-encode (rand-string)) (core/key-encode (rand-value 1)))]
        (doseq [[compact? encode] [[false core/key-encode] [true core/key-encode-compact]]]
          (let [e (encode v)]
            (is (= (alength ^bytes e) (core/key-skip (core/key-join e suffix) 0 compact?)) (pr-str [compact? v]))))))))
