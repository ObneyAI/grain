(ns ai.obney.grain.read-model-processor-v2.key-cap-bound-test
  "FINAL (OPT-8 audit C1): level-zero members whose packed key is exactly the
   511-byte LMDB key limit (and one byte below it) must be readable in every
   direction. Descending reads bound their scans just after a member
   (ranked-after); k ++ 00 used to be 512 bytes for a 511-byte key and threw
   BufferOverflowException in the store's key buffer. Covers plain pages,
   values, index-keys/index-rank, filtered pages and joined pages (with
   virtual members at the cap), for incremental and deferred builds. The
   oracle orders by value then (str id) (ASCII values and ids, so UTF-16
   order is byte order); descending keeps ties ascending by id."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]))

(def ^:private ids (mapv #(format "id%02d" %) (range 12)))

(defn- key-length
  "Packed level-zero key length of value/id in index :s of a model namespace
   (namespace prefix = KF9 3 bytes + 4-byte ordinal)."
  [d value id]
  (+ (alength ^bytes (core/ranked-prefix (#'core/keyed-index-config (byte-array 7) d :s :asc) 0))
     (alength ^bytes (#'core/keyed-index-member d :s :asc value id))))

(defn- value-of-length
  "The ASCII value starting with lead whose level-zero key is exactly target
   bytes (each further ASCII unit adds exactly one byte)."
  [d lead target]
  (let [v (loop [n 0]
            (let [v (str lead (apply str (repeat n "x")))]
              (if (>= (key-length d v (first ids)) target) v (recur (inc n)))))]
    (assert (= target (key-length d v (first ids))) [lead target])
    v))

(defn- page-of [xs page limit]
  (let [total (count xs) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min page pages) offset (* (dec page) limit)]
    {:keys (subvec xs (min offset total) (min total (+ offset limit))) :total total :page page}))

(defn- ordered [rows direction]
  ;; rows: [[value id] ...]; desc = values descending, ties ascending by id
  (mapv second (sort (fn [[va ia] [vb ib]]
                       (let [c (compare va vb)]
                         (cond (not (zero? c)) (if (= :desc direction) (- c) c)
                               :else (compare ia ib))))
                     rows)))

(defn- rows-for [d target]
  ;; Six distinct cap-length values, two of them shared by three owners
  ;; (runs at the cap), plus two short values so pages cross lengths.
  (let [cap (mapv #(value-of-length d (str (char (+ 98 %))) target) (range 6))
        values (concat cap [(cap 2) (cap 2) (cap 4) (cap 4) "a" "zz"])]
    (mapv vector values ids)))

(defn- check-reads! [ctx model rows label]
  (doseq [direction [:asc :desc]
          :let [expected (ordered rows direction)]]
    (testing [label direction]
      (doseq [limit [1 2 3 5] page (range 1 (+ 2 (quot (count rows) limit)))]
        (let [p (rmp/page ctx model {:index :s :direction direction :page page :limit limit})]
          (is (= (page-of expected page limit)
                 {:keys (mapv :key (:entries p)) :total (:total p) :page (:page p)})
              [label :page direction limit page])))
      (is (= expected (mapv :key (into [] (rmp/values ctx model {:index :s :direction direction}))))
          [label :values direction])
      (doseq [[value _] rows
              :let [run (filterv (fn [id] (some #(= [value id] %) rows)) expected)]]
        (is (= run (vec (rmp/index-keys ctx model {:index :s :direction direction :equals value :limit 20})))
            [label :index-keys direction])
        (doseq [id run]
          (is (= (.indexOf ^java.util.List run id)
                 (:rank (rmp/index-rank ctx model {:index :s :direction direction :equals value} id)))
              [label :index-rank direction id]))))))

(defn- check-filtered! [ctx model rows label]
  (doseq [direction [:asc :desc]
          candidates [ids (conj (vec (take-nth 2 ids)) "ghost") (vec (take 4 (reverse ids)))]
          limit [1 2 3]
          :let [cset (set candidates)
                expected (filterv cset (ordered rows direction))]
          page (range 1 (+ 2 (quot (count expected) limit)))]
    (let [p (rmp/filtered-key-page ctx model (shuffle candidates)
                                   {:index :s :direction direction :page page :limit limit})]
      (is (= (page-of expected page limit) (select-keys p [:keys :total :page]))
          [label :filtered direction (count candidates) limit page]))))

(defn- check-joined! [ctx members ledger rows target-value label]
  ;; Owners id08..id11 are members without a ledger result: they read as
  ;; virtual members with :missing-value, which here is a cap-length value
  ;; equal to a real run (virtual members at the cap) or a short one.
  (let [real (into {} (map (fn [[v id]] [id v])) (take 8 rows))]
    (rmp/read-session ctx [members ledger]
      (fn [ctx]
        (doseq [missing [target-value "a" "zzz"]
                direction [:asc :desc]
                limit [1 2 3 5]
                :let [expected (ordered (mapv (fn [id] [(get real id missing) id]) ids) direction)]
                page (range 1 (+ 2 (quot (count ids) limit)))]
          (let [p (rmp/joined-page ctx members ledger {:index :s :direction direction :page page :limit limit
                                                       :missing-value missing})]
            (is (= (page-of expected page limit) (select-keys p [:keys :total :page]))
                [label :joined (count missing) direction limit page])))))))

(defn- run-case! [opts target]
  (fixture/keyed-fixture
    (fn []
      (let [ctx (merge fixture/*keyed-ctx* opts)
            live (fn [v] (not (:deleted v)))
            plain (fixture/keyed-register! merge {:indexes {:s :s} :include? live})
            filtered (fixture/keyed-register! merge {:indexes {:s :s} :filtered-indexes #{:s} :include? live})
            members (fixture/keyed-register! merge {:include? :member?})
            ledger (fixture/keyed-register! merge {:include? :ledger? :indexes {:s :s} :filtered-indexes #{:s}})
            rows (rows-for {:indexes {:s :s}} target)
            target-value (ffirst rows)]
        (is (every? (fn [[v id]] (or (< (count v) 4) (= target (key-length {:indexes {:s :s}} v id)))) rows))
        (fixture/keyed-append! ctx (for [[v id] rows]
                                     {:owner id :s v :member? true :ledger? (< (.indexOf ^java.util.List ids id) 8)}))
        (check-reads! ctx plain rows [target :plain])
        (check-reads! ctx filtered rows [target :filtered-model])
        (check-filtered! ctx filtered rows [target :filtered])
        (check-joined! ctx members ledger rows target-value [target :joined])
        ;; incremental changes on top: move a cap member to another cap
        ;; value (joining a run) and exclude another, then re-read
        (fixture/keyed-append! ctx [{:owner "id00" :s (first (rows 1))} {:owner "id05" :deleted true}])
        (let [rows' (vec (remove #(= "id05" (second %)) (assoc rows 0 [(first (rows 1)) "id00"])))]
          (check-reads! ctx plain rows' [target :plain-after-changes])
          (check-reads! ctx filtered rows' [target :filtered-model-after-changes])
          (check-filtered! ctx filtered rows' [target :filtered-after-changes]))))))

(deftest descending-reads-at-the-511-byte-key-limit
  (doseq [[label opts] {:incremental {:read-model/batch-size 3 :read-model/deferred-build-threshold 0}
                        :deferred {:read-model/deferred-build-threshold 2}}]
    (testing label
      (run-case! opts 511))))

(deftest descending-reads-one-byte-below-the-key-limit
  (doseq [[label opts] {:incremental {:read-model/batch-size 3 :read-model/deferred-build-threshold 0}
                        :deferred {:read-model/deferred-build-threshold 2}}]
    (testing label
      (run-case! opts 510))))

(deftest ranked-after-is-the-exact-successor-bound
  ;; Below the limit k ++ 00; at the limit the prefix end of k, never longer.
  (let [k (byte-array (concat [75 70 57] (repeat 506 65) [-1 -1]))]
    (is (= 511 (alength k)))
    (is (= (vec (concat [75 70 57] (repeat 505 65) [66])) (vec (core/ranked-after k))))
    (let [short (byte-array [75 70 57 1])]
      (is (= [75 70 57 1 0] (vec (core/ranked-after short)))))
    (is (every? #(<= (alength ^bytes (core/ranked-after (byte-array % (byte 7)))) 511) [1 200 510 511]))))
