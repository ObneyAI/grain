;; Adapted from FoundationDB RankedSet.java, commit
;; 8a20d30f318e529fa30084b265563e64eb3956e1.
;; Copyright 2015-2018 Apple Inc. and the FoundationDB project authors.
;; Licensed under the Apache License, Version 2.0.
;; https://www.apache.org/licenses/LICENSE-2.0
;; Modifications: synchronous Grain KV operations, raw ordered byte-key packing,
;; transactional read/modify/write in place of FDB atomic ADD, unique sets only.
;; Grain extension: optional span membership summaries and filtered navigation.
;; Level-zero members hold opaque payloads with implicit count 1. Sentinels and
;; upper levels retain little-endian span counts. See resources/META-INF/licenses.
(ns ai.obney.grain.read-model-processor-v2.ranked-set
  "Internal counted ordered index, adapted from FoundationDB RankedSet.
   All operations run inside the caller's KV transaction. Promotion is random;
   physical layout may differ on replay, but membership and order do not.
   Optional :membership? requires one unique ordinal per logical instance."
  (:refer-clojure :exclude [contains?])
  (:require [ai.obney.grain.read-model-processor-v2.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.instance-ids :as ids]
            [ai.obney.grain.read-model-processor-v2.span-membership :as membership])
  (:import [java.nio ByteBuffer ByteOrder]
           [java.util Arrays HashMap]
           [org.roaringbitmap RoaringBitmap]
           [java.util.concurrent ThreadLocalRandom]))

(def empty-key (byte-array 0))
(defn random-hash [_] (.nextInt (ThreadLocalRandom/current)))
(defn config
  ([prefix] (config prefix random-hash 6))
  ([prefix hash-fn levels]
   (when-not (<= 2 levels 8)
     (throw (IllegalArgumentException. "Ranked index requires 2 to 8 levels")))
   {:prefix prefix :hash-fn hash-fn :levels levels}))
(defn prefix ^bytes [cfg level]
  (let [^bytes p (:prefix cfg) n (alength p) a (Arrays/copyOf p (inc n))]
    (aset-byte a n (byte level)) a))
(defn pack ^bytes [cfg level ^bytes key]
  (let [^bytes p (prefix cfg level) n (alength p) a (Arrays/copyOf p (+ n (alength key)))]
    (System/arraycopy key 0 a n (alength key)) (key/checked a)))
(defn unpack ^bytes [cfg ^bytes key]
  (Arrays/copyOfRange key (inc (alength ^bytes (:prefix cfg))) (alength key)))
(defn after ^bytes [^bytes k] (Arrays/copyOf k (inc (alength k))))
(defn encode-count ^bytes [n]
  (.array (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN) (.putLong (long n)))))
(defn decode-count [^bytes v]
  (when v (.getLong (doto (ByteBuffer/wrap v) (.order ByteOrder/LITTLE_ENDIAN)))))
(defn check-key! [^bytes key]
  (when (zero? (alength key)) (throw (IllegalArgumentException. "Empty key not allowed"))))
(defn- entry-count [cfg level ^bytes k v]
  (if (and (zero? level) (> (alength k) (inc (alength ^bytes (:prefix cfg)))))
    1 (decode-count v)))
(defn count-key [tx cfg level key]
  (let [k (pack cfg level key)]
    (when-let [v ((:get tx) k)] (entry-count cfg level k v))))
(defn set-count! [tx cfg level key n] ((:put tx) (pack cfg level key) (encode-count n)))
(defn increment! [tx cfg level key delta]
  ;; LMDB permits one writer. All reads/updates use that same write transaction.
  (set-count! tx cfg level key (+ (or (count-key tx cfg level key) 0) delta)))

(defn init! [tx cfg]
  ;; Upstream initLevels: preserve an already initialized sentinel.
  (dotimes [level (:levels cfg)]
    (when (nil? (count-key tx cfg level empty-key)) (set-count! tx cfg level empty-key 0))))
(defn ensure-init! [tx cfg]
  (when (nil? (count-key tx cfg 0 empty-key)) (init! tx cfg)))
(defn contains? [tx cfg key]
  (check-key! key) (pos? (or (count-key tx cfg 0 key) 0)))

(defn previous-key [tx cfg level key or-equal?]
  ;; Upstream getPreviousKey. Range conflicts are unnecessary under LMDB's
  ;; serialized writer; this read and its following mutation cannot interleave.
  (or ((:reduce tx) {:prefix (prefix cfg level)
                    :from (pack cfg level empty-key)
                    :to ((if or-equal? after identity) (pack cfg level key)) :reverse? true}
       (fn [_ {:keys [k]}] (reduced (unpack cfg k))) nil)
      (throw (ex-info "no key found on level" {:level level}))))

(defn count-range [tx cfg level begin end]
  ;; Upstream countRange; bounds are [begin,end).
  ((:reduce tx) (cond-> {:prefix (prefix cfg level)}
                 begin (assoc :from (pack cfg level begin))
                 end (assoc :to (pack cfg level end)))
   (fn [n {:keys [k v]}] (+ n (entry-count cfg level k v))) 0))

(defn membership-key [cfg level key] (pack cfg (+ 16 level) key))

(defn- range-members ^RoaringBitmap [tx cfg level begin end]
  ((:reduce tx) {:prefix (prefix cfg level) :from (pack cfg level begin)
                :to (pack cfg level end)}
   (fn [^RoaringBitmap out {:keys [k]}]
     (let [member (unpack cfg k) address (membership-key cfg level member)]
       (if (zero? level)
         (when (pos? (alength ^bytes member))
           (.add out (int (ids/number-value ((:get tx) address)))))
         (.or out (membership/bitmap tx address)))) out)
   (RoaringBitmap.)))

(defn add!
  "Add a unique member, optionally storing its opaque payload at level zero.
   Duplicate adds leave the existing payload intact. Initialize before adding."
  ([tx cfg key] (add! tx cfg key (encode-count 1) nil))
  ([tx cfg key value] (add! tx cfg key value nil))
  ([tx cfg key value ordinal]
    (check-key! key)
    (when (and (:membership? cfg) (not (and (integer? ordinal) (<= 0 ordinal Integer/MAX_VALUE))))
      (throw (ex-info "Membership index requires a model-local ordinal" {})))
    (let [hash ((:hash-fn cfg) key)]
      (if (contains? tx cfg key) false
        (do
          ;; Same lowest-to-highest order as upstream's split dependencies.
          (dotimes [level (:levels cfg)]
            (cond
              (zero? level)
              (do ((:put tx) (pack cfg 0 key) value)
                  (when (:membership? cfg)
                    ((:put tx) (membership-key cfg 0 key) (ids/number-bytes ordinal))))
              (not (zero? (bit-and hash (dec (bit-shift-left 1 (* 4 level))))))
              (let [prev (previous-key tx cfg level key false)]
                (increment! tx cfg level prev 1)
                (when (:membership? cfg)
                  (let [address (membership-key cfg level prev)
                        bits (membership/bitmap tx address)]
                    (.add bits (int ordinal))
                    (membership/put! tx address bits))))
              :else
              ;; addInsertLevelKey: split the preceding span at this key using
              ;; the already-updated next lower level.
              (let [prev (previous-key tx cfg level key false)
                    prev-count (count-key tx cfg level prev)
                    new-prev-count (count-range tx cfg (dec level) prev key)]
                (set-count! tx cfg level prev new-prev-count)
                (set-count! tx cfg level key (inc (- prev-count new-prev-count)))
                (when (:membership? cfg)
                  (let [address (membership-key cfg level prev)
                        right (membership/bitmap tx address)
                        left (range-members tx cfg (dec level) prev key)]
                    (.andNot right left)
                    (.add right (int ordinal))
                    (membership/put! tx address left)
                    (membership/put! tx (membership-key cfg level key) right))))))
          true)))))

(defn remove! [tx cfg key]
  (check-key! key)
  (if-not (contains? tx cfg key) false
    (let [ordinal (when (:membership? cfg)
                    (ids/number-value ((:get tx) (membership-key cfg 0 key))))]
      (dotimes [level (:levels cfg)]
        (if (zero? level)
          (do ((:delete tx) (pack cfg 0 key))
              (when (:membership? cfg) ((:delete tx) (membership-key cfg 0 key))))
          ;; Upstream remove, unique-set branch. Inspect stored levels rather
          ;; than rehashing: deletion stays correct if hash config changes.
          (let [count (count-key tx cfg level key)
                prev (previous-key tx cfg level key false)]
            (when count ((:delete tx) (pack cfg level key)))
            (increment! tx cfg level prev (+ -1 (or count 0)))
            (when (:membership? cfg)
              (let [address (membership-key cfg level prev)
                    bits (membership/bitmap tx address)]
                (when count
                  (.or bits (membership/bitmap tx (membership-key cfg level key)))
                  (membership/delete! tx (membership-key cfg level key)))
                (.remove bits (int ordinal))
                (membership/put! tx address bits))))))
      true)))

(defn size [tx cfg]
  ;; Upstream size sums the highest level. It is not a separate total counter.
  (count-range tx cfg (dec (:levels cfg)) nil nil))

(defn members
  "Included model ordinals from the disjoint top spans, without result reads."
  ^RoaringBitmap [tx cfg observe]
  (let [level (dec (:levels cfg))]
    ((:reduce tx) {:prefix (prefix cfg level)}
     (fn [^RoaringBitmap out {:keys [k]}]
       (when-let [raw ((:get tx) (membership-key cfg level (unpack cfg k)))]
         (observe :membership-read (alength ^bytes raw))
         (.or out (membership/decode raw))) out)
     (RoaringBitmap.))))

(deftype VirtualMember [^bytes member ^bytes payload])

(defn- virtual-bound [^java.util.List virtual ^bytes boundary]
  (if (nil? boundary) (.size virtual)
    (loop [lo 0 hi (.size virtual)]
      (if (= lo hi) lo
        (let [mid (quot (+ lo hi) 2)
              member (.-member ^VirtualMember (.get virtual mid))]
          (if (neg? (Arrays/compareUnsigned member boundary))
            (recur (inc mid) hi) (recur lo mid)))))))

(defn filtered-page
  "Experimental span navigation. Candidate ordinals belong to this model.
   Returns selected opaque level-zero payloads, never full model results."
  ([tx cfg candidates requested-page limit observe]
   (filtered-page tx cfg candidates requested-page limit observe []))
  ([tx cfg ^RoaringBitmap candidates requested-page limit observe ^java.util.List virtual]
  (let [counts (HashMap.) visits (volatile! 0) leaves (volatile! 0)
        remaining (volatile! 0) out (java.util.ArrayList.)
        top (dec (:levels cfg))]
    (letfn [(matches [level member]
              (if (and (zero? level) (zero? (alength ^bytes member))) 0
                (let [address (membership-key cfg level member)
                      lookup (ByteBuffer/wrap address)]
                  (or (.get counts lookup)
                      (let [raw ((:get tx) address)
                            _ (when raw (observe :membership-read (alength ^bytes raw)))
                            n (if (zero? level)
                                (do (vswap! leaves inc)
                                    (if (and raw (.contains candidates (int (ids/number-value raw)))) 1 0))
                                (if raw (RoaringBitmap/andCardinality candidates (membership/decode raw)) 0))]
                        (.put counts lookup n) n)))))
            (visit [level {:keys [k v]} end]
              (let [member (unpack cfg k) real (matches level member)
                    begin-virtual (virtual-bound virtual member)
                    end-virtual (virtual-bound virtual end)
                    n (+ real (- end-virtual begin-virtual))]
                (if (<= n @remaining)
                  (vswap! remaining - n)
                  (if (zero? level)
                    (do
                      ;; The real leaf starts this span; virtual members follow it.
                      (when (pos? real)
                        (if (pos? @remaining) (vswap! remaining dec) (.add out v)))
                      (let [start (+ begin-virtual @remaining)
                            stop (min end-virtual (+ start (- limit (.size out))))]
                        (doseq [i (range start stop)]
                          (.add out (.-payload ^VirtualMember (.get virtual i))))
                        (vreset! remaining 0)))
                    (walk (dec level) member end)))))
            (walk [level begin end]
              ;; Delay one entry to obtain its span's exclusive upper bound.
              (let [last-entry
                    ((:reduce tx) (cond-> {:prefix (prefix cfg level)
                                          :from (pack cfg level begin)}
                                   end (assoc :to (pack cfg level end)))
                     (fn [previous entry]
                       (vswap! visits inc)
                       (when previous (visit level previous (unpack cfg (:k entry))))
                       (if (= limit (.size out)) (reduced nil) entry)) nil)]
                (when (and last-entry (< (.size out) limit)) (visit level last-entry end))))]
      (let [real-total (if (.isEmpty candidates) 0
                    ((:reduce tx) {:prefix (prefix cfg top)}
                     (fn [n {:keys [k]}]
                       (vswap! visits inc)
                       (+ n (matches top (unpack cfg k)))) 0))
            total (+ real-total (.size virtual))
            pages (max 1 (quot (+ total (dec limit)) limit))
            page (min requested-page pages)]
        (vreset! remaining (* (dec page) limit))
        (when (pos? total)
          (if (zero? real-total)
            (doseq [i (range @remaining (min total (+ @remaining limit)))]
              (.add out (.-payload ^VirtualMember (.get virtual i))))
            (walk top empty-key nil)))
        {:payloads (vec out) :total total :total-pages pages :page page :page-size limit
         :rank-entries-examined @visits :membership-leaves-examined @leaves})))))

(defn get-nth [tx cfg ordinal]
  ;; Upstream NthLookup: at each level subtract spans until a span contains
  ;; the target, then descend from its key. No recursion per member.
  (when-not (neg? ordinal)
    (loop [level (dec (:levels cfg)) key empty-key remaining ordinal]
      (when (>= level 0)
        (let [found
              ((:reduce tx) {:prefix (prefix cfg level) :from (pack cfg level key)}
               (fn [{:keys [remaining]} {:keys [k v]}]
                 (let [key (unpack cfg k) n (entry-count cfg level k v)]
                   (cond
                     (and (zero? remaining) (pos? (alength ^bytes key))) (reduced {:done key})
                     (> n remaining) (reduced {:descend key :remaining remaining})
                     :else {:remaining (- remaining n)}))) {:remaining remaining})]
          (cond (:done found) (:done found)
                (:descend found) (recur (dec level) (:descend found) (:remaining found))
                :else nil))))))

(defn rank [tx cfg key null-if-missing?]
  ;; Upstream RankLookup, unique sets: sum an inclusive prefix on each level,
  ;; subtract the last span, and descend until the exact/insertion rank is known.
  (check-key! key)
  (when (or (not null-if-missing?) (contains? tx cfg key))
    (loop [level (dec (:levels cfg)) rank-key empty-key ordinal 0]
      (let [found ((:reduce tx) {:prefix (prefix cfg level) :from (pack cfg level rank-key)
                               :to (after (pack cfg level key))}
                   (fn [{:keys [ordinal]} {:keys [k v]}]
                     (let [n (entry-count cfg level k v)] {:rank-key (unpack cfg k) :last-count n :ordinal (+ ordinal n)}))
                   {:rank-key rank-key :last-count 0 :ordinal ordinal})
            next-rank (- (:ordinal found) (:last-count found))]
        (cond
          (Arrays/equals ^bytes (:rank-key found) ^bytes key) next-rank
          (zero? level) (+ next-rank (if (and (not null-if-missing?) (pos? (:last-count found))) 1 0))
          :else (recur (dec level) (:rank-key found) next-rank))))))
