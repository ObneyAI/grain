(ns ai.obney.grain.read-model-processor-v2.promotion-hash-test
  "Deterministic ranked promotion (OPT-3): SipHash-2-4 port, determinism,
   distribution, and safety of caches that mix promotion histories."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp])
  (:import [java.util Arrays Random]
           [org.roaringbitmap RoaringBitmap]))

;; [k0 k1 message split expected] from an independent Python implementation of
;; the paper's algorithm (SCRATCH/opt/OPT-3/tools/siphash_ref.py).
(def vectors
  [[-8561465848722683868 6745769884264228881 [24] 0 7281743178401785302]
   [-7850496877187009729 -8758578641956429739 [-96 -18 -24 -71 -103 127 92] 3 -166540277533998112]
   [-1230947375956593323 7778961656703135618 [-3 -81 -27 -109 37 60 -42 84] 5 -9207632502202153150]
   [-5588587507666222551 1199037988655718682 [20 39 -96 -82 -77 -2 -23 35 47] 4 4746707703579683106]
   [4883902672325290085 -3157061016479139934 [76 -127 -79 -70 -14 62 59 -7 -18 -11 -9 -97 43 73 52] 10 -2828625095034692740]
   [7391322017632240791 -3627993656693599897 [82 11 105 -71 75 13 -104 46 -123 -69 85 -74 114 -88 114 99] 7 -7100625439965773681]
   [3628588334472178038 3770023144559343277 [116 102 -4 -74 14 14 -113 -15 -124 99 -80 -28 -78 -70 41 112 52] 7 7748373201490164401]
   [8729347026296166929 1975205012463998475 [7 26 72 -53 45 -67 87 74 -78 -111 82 87 34 55 -60 -5 101 -102 64 22 -9 -95 27 -58 44 82 113 -49 100 -14 93 111 21 -52 80 -60 -73 63 76 126 98 21 19 -91 60 -57 -23 -100 -41 -99 127 -39 -57 -68 -28 -32 91 11 1 -6 -18 120 -28 -22] 22 2260474588300763185]
   [3260573794395513173 -4729842623386673000 [34 65 -73 -36 -69 46 -30 20 20 66 42 -96 40 27 -63 69 13 33 56 99 67 -5 -109 84 113 33 -77 -127 81 -91 -116 -23 73 -126 -11 106 -122 121 -93 -66 18 101 93 -50 82 -114 -89 -64 86 -121 58 24 -72 -25 53 -127 -55 -66 -121 -64 -68 74 -72 -87 41] 56 -9155761028425911616]])

(defn- split-hash [k0 k1 ^bytes m s junk]
  ;; Message = a[junk..] ++ b with the split at s: offsets and splits must not matter.
  (let [a (byte-array (concat (repeat junk 99) (take s m))) b (byte-array (drop s m))]
    (core/siphash24 (long-array [k0 k1]) a junk b)))

(deftest siphash-2-4-reference-vectors
  (let [k0 (.getLong (doto (java.nio.ByteBuffer/wrap (byte-array (range 8))) (.order java.nio.ByteOrder/LITTLE_ENDIAN)))
        k1 (.getLong (doto (java.nio.ByteBuffer/wrap (byte-array (range 8 16))) (.order java.nio.ByteOrder/LITTLE_ENDIAN)))]
    ;; Aumasson and Bernstein, SipHash paper, appendix A; reference vectors.h entry 0.
    (is (= (unchecked-long 0xa129ca6149be45e5) (split-hash k0 k1 (byte-array (range 15)) 7 0)))
    (is (= 0x726fdb47dd0e0e31 (split-hash k0 k1 (byte-array 0) 0 0))))
  (doseq [[k0 k1 m _ expected] vectors
          :let [m (byte-array m)]
          s (range (inc (alength m))) junk [0 3 9]]
    (is (= expected (split-hash k0 k1 m s junk)) (str "len " (alength m) " split " s " junk " junk))))

(deftest promotion-is-deterministic-and-ignores-namespace-ordinal
  (let [rel (byte-array [5 56 16 0 117 255 0 0 0])
        member (.getBytes "member-1")
        k (long-array [11 22])
        hash (fn [^bytes ns] (core/ranked-promotion-hash k (byte-array (concat ns rel)) (alength ns) member))]
    (is (= (hash (byte-array [75 70 57 0 0 0 1])) (hash (byte-array [75 70 57 0 0 0 1]))))
    (is (= (hash (byte-array [75 70 57 0 0 0 1])) (hash (byte-array [75 70 57 0 0 0 42])))
        "The namespace ordinal is an allocation artifact")
    (is (= (hash (byte-array [75 70 57 0 0 0 1])) (hash (byte-array [75 70 83 57 0 0 0 1])))
        "Scoped and ordinary namespaces of an index level a member alike")
    (is (not= (core/ranked-promotion-hash k (byte-array [1 2 3]) 0 member)
              (core/ranked-promotion-hash k (byte-array [1 2 4]) 0 member))
        "Index and direction bytes are hashed")
    (is (not= (hash (byte-array [75 70 57 0 0 0 1]))
              (core/ranked-promotion-hash (long-array [11 23]) (byte-array (concat [75 70 57 0 0 0 1] rel)) 7 member))
        "The key changes every decision")))

(deftest promotion-distribution-on-patterned-ids
  ;; Sequential and shared-prefix ids must still promote with probability 16^-l.
  (doseq [[label keys] [["sequential uuid" (mapv #(fixture/ranked-set-id-bytes (java.util.UUID. 0 %)) (range 65536))]
                        ["shared prefix" (mapv #(.getBytes (format "ticket-%08d" %)) (range 65536))]]]
    (let [k (core/pinned-promotion-key (.getBytes "0123456789abcdef"))
          h #(core/ranked-promotion-hash k (byte-array [75 70 57 0 0 0 9 5 0]) 7 %)
          hashes (mapv h keys) n (count hashes)
          promoted (fn [l] (count (filter #(zero? (bit-and (long %) (dec (bit-shift-left 1 (* 4 l))))) hashes)))]
      (doseq [l [1 2 3]]
        (let [expected (/ n (Math/pow 16 l)) sigma (Math/sqrt (* expected (- 1 (Math/pow 16 (- l)))))]
          (is (< (Math/abs (- (promoted l) expected)) (+ 1 (* 5 sigma)))
              (str label " level " l ": " (promoted l) " vs " expected)))))))

;; ---------------------------------------------------------------------------
;; Mixed promotion histories: levels are discovered from storage
;; ---------------------------------------------------------------------------

(def ^:private hashers
  {:random (fn [^Random r] (fn [_] (.nextInt r)))
   :all-levels (fn [_] (constantly 0))
   :never (fn [_] (constantly 1))
   :default nil})

(defn- cfg-with [base kind ^Random r]
  (if-let [make (hashers kind)] (assoc base :hash-fn (make r)) base))

(defn- check-structure! [store cfg present label]
  (kv/transact store :read
    (fn [tx]
      (let [ordered (vec (sort @present)) n (count ordered)
            key-of core/membership-number-bytes]
        (fixture/ranked-set-check-set tx cfg (map key-of ordered))
        ;; count-range over level 0 and by rank difference
        (doseq [[lo hi] [[0 400] [17 213] [100 101] [250 399]]]
          (let [expected (count (filter #(and (<= lo %) (< % hi)) ordered))]
            (is (= expected (core/ranked-count-range tx cfg 0 (key-of lo) (key-of hi))) label)
            (is (= expected (- (core/ranked-rank tx cfg (key-of hi) false)
                               (core/ranked-rank tx cfg (key-of lo) false))) label)))
        ;; span membership summaries at every upper level
        (doseq [level (range 1 (:levels cfg))]
          (let [entries ((:reduce tx) {:prefix (core/ranked-prefix cfg level)}
                         (fn [out {:keys [k]}] (conj out (core/ranked-unpack cfg k))) [])]
            (doseq [[begin end] (map vector entries (concat (rest entries) [nil]))]
              (let [expected (set (filter (fn [i]
                                           (let [k (key-of i)]
                                             (and (not (neg? (Arrays/compareUnsigned k begin)))
                                                  (or (nil? end) (neg? (Arrays/compareUnsigned k end))))))
                                         ordered))
                    bits (core/membership-bitmap tx (core/ranked-membership-key cfg level begin))]
                (is (= expected (set (.toArray bits))) label)))))
        ;; filtered pages (deep pages navigate upper spans)
        (doseq [[candidates selected] [[(doto (RoaringBitmap.) (.add (long 0) (long 400))) ordered]
                                       [(RoaringBitmap/bitmapOf (int-array (filter even? (range 400))))
                                        (filterv even? ordered)]]
                page [1 4 20]]
          (let [result (core/ranked-filtered-page tx cfg candidates page 7 (fn [& _]))
                offset (* (dec (:page result)) 7)]
            (is (= (count selected) (:total result)) label)
            (is (= (subvec selected offset (min (count selected) (+ offset 7)))
                   (mapv core/membership-number-value (:payloads result))) label)))))))

(defn- mutate! [store cfg ^Random rng present ops]
  (kv/transact store :write
    (fn [tx]
      (let [tx (core/membership-transaction tx)]
        (dotimes [_ ops]
          (let [id (.nextInt rng 400) key (core/membership-number-bytes id)]
            (if (< (.nextInt rng 3) 2)
              (do (is (= (not (contains? @present id)) (core/ranked-add! tx cfg key key id)))
                  (swap! present conj id))
              (do (is (= (contains? @present id) (core/ranked-remove! tx cfg key)))
                  (swap! present disj id)))))
        (core/membership-flush! tx (fn [& _]))))))

(deftest mixed-promotion-histories-stay-consistent
  ;; Phase 1 builds with one promotion function (old random caches, or skewed
  ;; extremes); phase 2 continues with another. Removal, rank, nth, count-range,
  ;; span summaries and filtered pages read stored levels only.
  (fixture/keyed-fixture
    (fn []
      (let [store (:cache fixture/*keyed-ctx*)]
        (doseq [[i [old new]] (map-indexed vector [[:random :default] [:default :random]
                                                   [:all-levels :default] [:never :default]
                                                   [:default :all-levels] [:default :never]])]
          (let [label (str old " -> " new)
                rng (Random. (+ 500 i)) present (atom #{})
                base (assoc (core/ranked-config (byte-array [75 70 57 0 0 0 (+ 60 i) 5 0])) :hash-offset 7 :membership? true)]
            (testing label
              (kv/transact store :write #(core/ranked-init! % base))
              (dotimes [_ 6] (mutate! store (cfg-with base old rng) rng present 60))
              (check-structure! store base present label)
              (dotimes [_ 10]
                (mutate! store (cfg-with base new rng) rng present 60)
                (check-structure! store base present label))
              ;; remove everything through the new configuration: no stale spans
              (kv/transact store :write
                (fn [tx]
                  (let [tx (core/membership-transaction tx)]
                    (doseq [id @present] (is (true? (core/ranked-remove! tx base (core/membership-number-bytes id)))))
                    (core/membership-flush! tx (fn [& _])))))
              (reset! present #{})
              (check-structure! store base present label)
              (kv/transact store :read
                (fn [tx]
                  (doseq [level (range 1 (:levels base))]
                    (is (= [[0 0]] (mapv (fn [[k n]] [(alength ^bytes k) n])
                                         (fixture/ranked-set-level-entries tx base level)))
                        (str label " level " level " keeps only its empty sentinel"))))))))))))

;; ---------------------------------------------------------------------------
;; Per-cache promotion key (keyed models)
;; ---------------------------------------------------------------------------

(def ^:private key-address (byte-array [75 80 57 -1]))
(defn- stored-key [store] (some-> (kv/transact store :read #((:get %) key-address)) vec))
(defn- upper-levels [rows]
  ;; kind-5 rank entries above level 0 (and membership summaries) of any namespace
  (into {} (filter (fn [[k _]] (and (= [75 70 57] (subvec k 0 3)) (= 5 (nth k 7)))) rows)))

(deftest cache-key-generated-once-stored-and-reused
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx* store (:cache ctx)
            model (fixture/keyed-register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})]
        (is (nil? (stored-key store)) "No key before the first keyed write")
        (fixture/keyed-append! (mapv #(hash-map :owner (format "%04d" %) :score (mod % 7)) (range 300)))
        (rmp/page ctx model {})
        (let [k1 (stored-key store)]
          (is (= 16 (count k1)))
          (fixture/keyed-append! (mapv #(hash-map :owner (format "%04d" %) :score (mod % 5)) (range 300)))
          (rmp/page ctx model {})
          (is (= k1 (stored-key store)) "Later batches reuse the stored key")
          ;; every upper-level :score member satisfies the rule under the stored key
          (let [kk (core/pinned-promotion-key (byte-array k1))
                ;; OPT-8: the index is addressed by its ordinal (:score is 1 of {:score})
                enc (vec (#'core/keyed-index-ordinal-bytes {:indexes {:score :score}} :score)) plen (+ 7 1 (count enc) 1)
                checked (atom 0)]
            (doseq [[k _] (upper-levels (fixture/keyed-stored-bytes))
                    :when (= enc (subvec k 8 (+ 8 (count enc))))
                    :let [level (bit-and 255 (nth k plen))]
                    :when (<= 1 level 5)
                    :let [member (byte-array (subvec k (inc plen)))]
                    :when (pos? (alength member))]
              (swap! checked inc)
              (is (zero? (bit-and (core/ranked-promotion-hash kk (byte-array (subvec k 0 plen)) 7 member)
                                  (dec (bit-shift-left 1 (* 4 level)))))))
            (is (pos? @checked))))))))

(deftest pinned-key-is-used-and-not-persisted
  (let [rows (atom [])]
    (doseq [_ [1 2]]
      (fixture/keyed-fixture
        (fn []
          (let [ctx (assoc fixture/*keyed-ctx* :tenant-id (java.util.UUID. 9 9)
                           :read-model/promotion-key "00112233445566778899aabbccddeeff")
                model :promotion-hash-test/pinned]
            (rmp/register-read-model! model merge {:events #{:keyed/change} :version 1 :route :inputs
                                                    :indexes {:score :score}})
            (fixture/keyed-append! ctx [{:inputs (mapv (fn [n] [n {:score (mod n 11)}]) (range 2000))}])
            (rmp/progress ctx model)
            (is (nil? (stored-key (:cache ctx))) "A pinned key is never written")
            (swap! rows conj (upper-levels (fixture/keyed-stored-bytes)))))))
    (is (seq (first @rows)))
    (is (= (first @rows) (second @rows)) "Two caches with one pinned key level identically")))

(deftest old-cache-without-key-gets-one-and-keeps-its-levels
  ;; A cache whose members were promoted before any key existed (random, as
  ;; built by the baseline) gets a key on its next write; existing upper-level
  ;; entries are untouched because nothing rehashes, and the index stays exact.
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx* store (:cache ctx)
            model (fixture/keyed-register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})
            random (Random. 3)]
        ;; Emulate a pre-OPT-3 cache: random promotion, no stored key (a pinned
        ;; key is never written).
        (with-redefs [core/ranked-promotion-hash (fn [_ _ _ _] (.nextInt random))]
          (fixture/keyed-append! (mapv #(hash-map :owner (format "%04d" %) :score %) (range 1500)))
          (rmp/page (assoc ctx :read-model/promotion-key (byte-array 16)) model {}))
        (let [before (upper-levels (fixture/keyed-stored-bytes))]
          (is (nil? (stored-key store)))
          ;; new members only: no existing member is moved
          (fixture/keyed-append! (mapv #(hash-map :owner (format "n%04d" %) :score (+ 5000 %)) (range 300)))
          (rmp/page ctx model {})
          (is (= 16 (count (stored-key store))) "First write generated and stored a key")
          (let [after (upper-levels (fixture/keyed-stored-bytes))]
            (is (every? #(contains? after %) (keys before))
                "No pre-existing upper-level member is rehashed away")
            (doseq [dir [:asc :desc]]
              (let [page (rmp/page ctx model {:index :score :direction dir :limit 2000})
                    expected (sort (concat (range 1500) (range 5000 5300)))]
                (is (= (if (= dir :asc) expected (reverse expected))
                       (mapv #(get-in % [:value :score]) (:entries page))))))))))))
