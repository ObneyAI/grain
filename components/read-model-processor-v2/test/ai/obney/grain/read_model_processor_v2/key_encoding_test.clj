(ns ai.obney.grain.read-model-processor-v2.key-encoding-test
  "OPT-8 (W3) compact key encoding: byte order equals the former (KF9
   physical) comparator order for every supported value type, no encoding is
   longer than the former one, and every decoder agrees with the encoder
   (W2 audit condition W2-C3: key-skip, the member skipper/reader and the
   member-id reader are checked against key-encode over random keys)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.core :as core])
  (:import [java.util Arrays Random UUID]))

(defn- cv [s] (ns-resolve 'ai.obney.grain.read-model-processor-v2.core s))
(def ^:private legacy (cv 'key-encode-legacy))
(defn- sgn ^long [^long x] (Long/signum x))
(defn- cmp ^long [^bytes a ^bytes b] (sgn (Arrays/compareUnsigned a b)))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def ^:private boundary-units
  ;; every code-word boundary of the string code, plus script samples
  [0 1 2 3 4 0x1f 0x20 0x41 0x61 0x7e 0x7f 0x80 0x81 0xa0 0xc0 0xe9 0xfe 0xff
   0x100 0x101 0x17f 0x1ff 0x200 0x3b1 0x430 0x5d0 0x627 0x7ff 0x800 0xfff 0x1000
   0x3042 0x30a2 0x4e00 0x4e2d 0x9fa5 0xac00 0xd7a3 0xd800 0xd83d 0xdbff 0xdc00 0xde00
   0xdfff 0xe000 0xf8ff 0xfeff 0xff00 0xff01 0xfffe 0xffff])

(defn- rand-unit ^long [^Random r script]
  (case script
    :ascii (+ 0x20 (.nextInt r 95))
    :latin1 (if (.nextBoolean r) (+ 0x20 (.nextInt r 95)) (+ 0xa0 (.nextInt r 96)))
    :cjk (+ 0x4e00 (.nextInt r 0x5200))
    :emoji (if (.nextBoolean r) 0xd83d (+ 0xde00 (.nextInt r 0x50)))
    :boundary (nth boundary-units (.nextInt r (count boundary-units)))
    :any (.nextInt r 65536)
    :mixed (rand-unit r (nth [:ascii :latin1 :cjk :emoji :boundary :any] (.nextInt r 6)))))

(defn- rand-string [^Random r script max-len]
  (let [n (.nextInt r (inc max-len)) sb (StringBuilder.)]
    (dotimes [_ n] (.append sb (char (rand-unit r script))))
    (str sb)))

(def ^:private long-boundaries
  (vec (distinct (concat [Long/MIN_VALUE (inc Long/MIN_VALUE) -1 0 1 Long/MAX_VALUE (dec Long/MAX_VALUE)]
                         (for [k (range 1 8) d [-1 0 1] s [1 -1]] (* s (+ d (bit-shift-left 1 (* 8 k)))))
                         (for [k (range 1 8) s [1 -1]] (* s (dec (bit-shift-left 1 (* 8 k)))))
                         [1700000000000 1760572800000 -1700000000000]))))

(defn- rand-long [^Random r]
  (case (.nextInt r 5)
    0 (nth long-boundaries (.nextInt r (count long-boundaries)))
    1 (- (.nextInt r 512) 256)
    2 (.nextLong r)
    3 (bit-shift-right (.nextLong r) (.nextInt r 64))
    4 (+ 1700000000000 (.nextInt r 1000000000))))

(defn- rand-keyword [^Random r]
  (case (.nextInt r 6)
    0 (keyword (rand-string r :ascii 6))
    1 (keyword (rand-string r :ascii 4) (rand-string r :ascii 6))
    2 (keyword (rand-string r :mixed 4) (rand-string r :mixed 4))
    3 (keyword (str (rand-string r :ascii 2) "/" (rand-string r :ascii 2)) (rand-string r :ascii 2)) ; odd form
    4 (keyword "")
    5 (keyword (rand-string r :boundary 3))))

(defn- rand-value [^Random r depth]
  (case (.nextInt r (if (pos? depth) 12 11))
    0 nil
    1 (.nextBoolean r)
    2 (rand-long r)
    3 (int (.nextInt r))
    4 (bigint (rand-long r))                       ; BigInt within long range
    5 (biginteger (rand-long r))
    6 (UUID. (.nextLong r) (.nextLong r))
    7 (rand-keyword r)
    8 (rand-string r :mixed 8)
    9 (rand-string r (nth [:ascii :latin1 :cjk :emoji :boundary] (.nextInt r 5)) 12)
    10 (rand-string r :boundary 4)
    11 (vec (repeatedly (.nextInt r 4) #(rand-value r (dec depth))))))

;; Semantic comparator of the former byte order (independent of any encoder):
;; type rank, then UTF-16 units, signed longs, unsigned 128-bit UUIDs,
;; keyword printed forms, element-wise vectors (shorter first).
(defn- rank ^long [v]
  (cond (nil? v) 0 (false? v) 1 (true? v) 2 (string? v) 3 (integer? v) 4
        (uuid? v) 5 (keyword? v) 6 (vector? v) 7))
(defn- semantic-compare ^long [a b]
  (let [ra (rank a) rb (rank b)]
    (if (not= ra rb) (sgn (- ra rb))
      (case ra
        (0 1 2) 0
        3 (sgn (.compareTo ^String a ^String b))
        4 (sgn (compare (long a) (long b)))
        5 (let [c (Long/compareUnsigned (.getMostSignificantBits ^UUID a) (.getMostSignificantBits ^UUID b))]
            (sgn (if (zero? c) (Long/compareUnsigned (.getLeastSignificantBits ^UUID a) (.getLeastSignificantBits ^UUID b)) c)))
        6 (sgn (.compareTo ^String (str a) ^String (str b)))
        7 (loop [a (seq a) b (seq b)]
            (cond (and (nil? a) (nil? b)) 0 (nil? a) -1 (nil? b) 1
                  :else (let [c (semantic-compare (first a) (first b))]
                          (if (zero? c) (recur (next a) (next b)) c))))))))

;; ---------------------------------------------------------------------------
;; Order and size
;; ---------------------------------------------------------------------------

(deftest random-values-order-like-the-former-encoding-and-never-grow
  (let [r (Random. 8)
        values (vec (repeatedly 3000 #(rand-value r 2)))
        enc (mapv core/key-encode values)
        leg (mapv legacy values)
        mismatches (atom 0) pairs (atom 0)]
    (dotimes [i (count values)]
      (is (<= (alength ^bytes (enc i)) (alength ^bytes (leg i))) (pr-str (values i))))
    ;; 3000 x 3000 = 9M ordered pairs, all of them
    (dotimes [i (count values)]
      (let [^bytes ei (enc i) ^bytes li (leg i)]
        (dotimes [j (count values)]
          (swap! pairs inc)
          (let [n (cmp ei (enc j)) o (cmp li (leg j)) s (semantic-compare (values i) (values j))]
            (when-not (= n o s)
              (swap! mismatches inc)
              (when (< @mismatches 5)
                (is false (pr-str [:order (values i) (values j) n o s]))))))))
    (is (= 9000000 @pairs))
    (is (zero? @mismatches))))

(deftest every-unit-pair-from-the-boundary-set-sorts-as-strings
  ;; All strings of length 0..3 over the 51 boundary units (135,304 strings),
  ;; sorted by encoded bytes, equal String order; each no longer than before.
  (let [us (mapv char boundary-units)
        strings (vec (concat [""] (map str us)
                             (for [a us b us] (str a b))
                             (for [a us b us c us] (str a b c))))
        by-bytes (sort-by core/key-encode (fn [a b] (Arrays/compareUnsigned ^bytes a ^bytes b)) strings)]
    (is (= 135304 (count strings)))
    (is (= (sort strings) by-bytes))
    (is (every? #(<= (alength ^bytes (core/key-encode %)) (alength ^bytes (legacy %))) strings))
    (is (every? #(= % (core/key-decode-compact (core/key-encode %))) strings))))

(deftest all-single-units-and-unit-runs-never-grow
  (doseq [u (range 65536)
          n [1 2 5]
          :let [s (apply str (repeat n (char u)))]]
    (when-not (<= (alength ^bytes (core/key-encode s)) (alength ^bytes (legacy s)))
      (is false (pr-str [:grew u n]))))
  ;; size per script, as specified: ASCII n+2, Latin-1 n+2, CJK 2n+2 (n >= 1)
  (doseq [n [1 10 100]]
    (is (= (+ n 2) (alength ^bytes (core/key-encode (apply str (repeat n "a"))))))
    (is (= (+ n 2) (alength ^bytes (core/key-encode (apply str (repeat n "é"))))))
    (is (= (+ (* 2 n) 3) (alength ^bytes (core/key-encode (apply str (repeat n "中"))))))
    (is (= (+ (* 3 n) 3) (alength ^bytes (legacy (apply str (repeat n "a"))))))
    (is (= (+ (* 2 n) 3) (alength ^bytes (legacy (apply str (repeat n "中"))))))))

(deftest integers-of-every-width-order-and-round-trip
  (let [xs (sort (concat long-boundaries (range -300 300)))]
    (is (= xs (sort-by core/key-encode (fn [a b] (Arrays/compareUnsigned ^bytes a ^bytes b)) (shuffle xs))))
    (doseq [x xs]
      (is (= x (core/key-decode-compact (core/key-encode x))))
      (is (= (vec (core/key-encode x)) (vec (core/key-encode (bigint x))) (vec (core/key-encode (biginteger x)))))
      (when (<= Integer/MIN_VALUE x Integer/MAX_VALUE)
        (is (= (vec (core/key-encode x)) (vec (core/key-encode (int x))))))))
  (is (= [32] (vec (core/key-encode 0))))
  (is (= 7 (alength ^bytes (core/key-encode 1760572800000))) "epoch-ms timestamps: 7 B (was 9)"))

(deftest unsupported-values-are-rejected-as-before
  (doseq [v [1.5 (float 1.5) 1/2 1.5M 1M (inc (bigint Long/MAX_VALUE)) (dec (bigint Long/MIN_VALUE))
             {} #{} '(1) (Object.) \a (byte-array 1)]]
    (is (thrown? clojure.lang.ExceptionInfo (core/key-encode v)) (pr-str v))
    (is (thrown? clojure.lang.ExceptionInfo (legacy v)) (pr-str v))))

(deftest keywords-of-every-form
  (let [ks [(keyword "") (keyword "a") :a :a/b :ab (keyword "a/b" "c") (keyword "a" "b/c") (keyword "é")
            (keyword "中" "文") (keyword "\u0000") (keyword "a" "") :zzz/a]]
    (doseq [a ks b ks]
      (is (= (cmp (core/key-encode a) (core/key-encode b)) (cmp (legacy a) (legacy b))) (pr-str [a b])))
    (doseq [k ks]
      ;; decoding reads the printed form, as the former decoder did
      (is (= (keyword (subs (str k) 1)) (core/key-decode-compact (core/key-encode k)))))))

(deftest long-strings-near-the-key-cap
  ;; Values and member keys near and above the 511-byte cap (former ~114-char
  ;; :subject cap with UUID ids): encodings stay exact and ordered.
  (doseq [script [:ascii :latin1 :cjk :emoji :mixed]
          n [100 113 114 115 200 250 300 450 500 600]]
    (let [r (Random. n)
          s (apply str (repeatedly n #(char (rand-unit r script))))
          e (core/key-encode s)]
      (is (= s (core/key-decode-compact e)))
      (is (<= (alength ^bytes e) (alength ^bytes (legacy s))))
      (is (= (cmp e (core/key-encode (str s "a"))) -1)))))

;; ---------------------------------------------------------------------------
;; Member suffixes and members
;; ---------------------------------------------------------------------------

(defn- rand-id [^Random r]
  (case (.nextInt r 9)
    0 (UUID. (.nextLong r) (.nextLong r))
    1 (rand-long r)
    2 (str (rand-long r))                          ; collides with the long's (str id)
    3 (rand-string r :mixed 6)
    4 (rand-keyword r)
    5 (.nextBoolean r)
    6 [(rand-long r) (rand-string r :ascii 3)]
    7 (str (UUID. (.nextLong r) (.nextLong r)))    ; collides with the UUID's (str id)
    8 (str ":" (rand-string r :ascii 3))))          ; collides with keywords' (str id)

(defn- legacy-suffix ^bytes [id] (core/key-join (legacy (str id)) (legacy id)))

(deftest member-suffixes-order-ties-as-the-former-suffix
  (let [r (Random. 81)
        ids (vec (concat (repeatedly 3000 #(rand-id r))
                         [1 "1" (int 1) (bigint 1) :a ":a" true "true" false "false"
                          (UUID. 0 1) (str (UUID. 0 1)) [1] "[1]" "" (keyword "")]))
        nw (mapv core/key-id-suffix ids) od (mapv legacy-suffix ids)
        bad (atom 0)]
    (dotimes [i (count ids)]
      (is (<= (alength ^bytes (nw i)) (alength ^bytes (od i))))
      (dotimes [j (count ids)]
        (when-not (= (cmp (nw i) (nw j)) (cmp (od i) (od j)))
          (swap! bad inc)
          (when (< @bad 5) (is false (pr-str [:suffix (ids i) (ids j)]))))))
    (is (zero? @bad))
    ;; equal suffix bytes exactly when the former suffixes were equal (same entity key)
    (is (= (count (distinct (map vec od))) (count (distinct (map vec nw)))))))

(defn- legacy-member ^bytes [identity? value id]
  (if identity?
    (core/key-join (legacy value) (legacy id))
    (core/key-join (legacy value) (legacy-suffix id))))

(deftest whole-members-order-as-the-former-members
  (let [r (Random. 82)
        rows (vec (repeatedly 2500 (fn [] (let [id (rand-id r)] [(rand-value r 1) id]))))
        member (cv 'keyed-index-member)
        d {:indexes {:v :v}}]
    (doseq [identity? [false true]]
      (let [ms (mapv (fn [[v id]] (if identity? (member d :identity :asc (str id) id) (member d :v :asc v id))) rows)
            ls (mapv (fn [[v id]] (if identity? (legacy-member true (str id) id) (legacy-member false v id))) rows)
            bad (atom 0)]
        (dotimes [i (count rows)]
          (dotimes [j (count rows)]
            (when-not (= (cmp (ms i) (ms j)) (cmp (ls i) (ls j)))
              (swap! bad inc)
              (when (< @bad 5) (is false (pr-str [:member identity? (rows i) (rows j)]))))))
        (is (zero? @bad))))))

;; ---------------------------------------------------------------------------
;; W2-C3: every decoder against the encoder
;; ---------------------------------------------------------------------------

(defn- norm
  "What decoding returns for v: integers as Long, keywords re-read from their
   printed form, element-wise for vectors."
  [v]
  (cond (integer? v) (long v)
        (keyword? v) (keyword (subs (str v) 1))
        (vector? v) (mapv norm v)
        :else v))

(deftest every-decoder-agrees-with-the-encoder-over-random-keys
  (let [r (Random. 83)
        skip (cv 'skip-member-key)
        read! (cv 'member-read!)
        read-id! (cv 'member-read-id!)
        member-id (cv 'keyed-member-id)
        member (cv 'keyed-index-member)
        exact? (cv 'keyed-id-exact?)
        d {:indexes {:v :v}}
        d-filtered {:indexes {:v :v} :filtered-indexes #{:identity}}
        checks (atom 0)]
    (dotimes [_ 20000]
      (let [v (rand-value r 2)
            id (rand-id r)
            ^bytes e (core/key-encode v)
            n (alength e)
            ^bytes suffix (core/key-id-suffix id)
            ^bytes k (core/key-join e suffix)
            ^bytes inv (core/key-join (core/key-descending e) suffix)
            expected (norm v)
            expected-id (norm id)]
        ;; skippers: OPT-57 key-skip, OPT-9 skip-member-key (ascending and through the mask)
        (is (= n (core/key-skip k 0) (core/key-skip k 0 true) (skip k 0 0) (skip inv 0 255)))
        ;; reader: build and skip modes stop where the skippers stop
        (let [p (long-array [0 (alength k)]) q (long-array [0 (alength k)]) m (long-array [0 (alength inv)])]
          (is (= expected (read! k p 0 true) (read! inv m 255 true)))
          (read! k q 0 false)
          (is (= n (aget p 0) (aget q 0) (aget m 0))))
        ;; metadata decoder (sort data fields)
        (is (= expected (core/key-decode-compact e) (core/key-decode-compact k 0 n)))
        ;; id suffix reader: decodes the id and consumes the suffix exactly
        (let [p (long-array [n (alength k)])]
          (is (= expected-id (read-id! k p 0)))
          (is (= (alength k) (aget p 0))))
        ;; members of every layout decode to their id (both directions' value bytes)
        (let [plain (member d :v :asc v id)
              ident (member d :identity :asc (str id) id)
              fident (member d-filtered :identity :asc (str id) id)]
          (is (= expected-id (member-id d :v :asc plain 0)
                 (member-id d :identity :asc ident 0)
                 (member-id d-filtered :identity :asc fident 0)))
          (is (= expected-id (member-id d :v :desc (core/key-join (core/key-descending e) suffix) 0)))
          (when (exact? id) (is (= id expected-id))))
        (swap! checks inc)))
    (is (= 20000 @checks))))

(deftest truncated-or-corrupt-keys-are-rejected
  (let [r (Random. 84) member-id (cv 'keyed-member-id) member (cv 'keyed-index-member)
        d {:indexes {:v :v}}]
    (dotimes [_ 2000]
      (let [v (rand-value r 1) id (rand-id r)
            ^bytes m (member d :v :asc v id)
            cut (java.util.Arrays/copyOf m (.nextInt r (alength m)))]
        (is (thrown? clojure.lang.ExceptionInfo (member-id d :v :asc cut 0)))
        (is (thrown? clojure.lang.ExceptionInfo (member-id d :v :asc (core/key-join m (byte-array [7])) 0)))))
    (doseq [bad [[16 0 1 2 0 0] [17 1 0] [18 0 1 0 0] [17 97 255 0 1 0 0] [9] [0x29] [0x40 9 0]]]
      (is (thrown? Exception (core/key-decode-compact (byte-array (map unchecked-byte bad)))) (pr-str bad)))))

(deftest index-ordinals-follow-sorted-names-and-are-prefix-free
  (let [ordinal (cv 'keyed-index-ordinal) ob (cv 'keyed-index-ordinal-bytes)
        d {:indexes {:updated 1 :status 1 :a/z 1 :b 1}}]
    ;; :identity 0, then sorted names (= sort-data field - 1)
    (is (= [0 1 2 3 4] (mapv #(ordinal d %) [:identity :b :status :updated :a/z])))
    (is (= (mapv #(dec ((cv 'sort-data-field) (:indexes d) %)) [:b :status :updated :a/z])
           (mapv #(ordinal d %) [:b :status :updated :a/z])))
    (is (thrown? clojure.lang.ExceptionInfo (ordinal d :missing)))
    ;; large models: FF hi lo beyond 254, all distinct and prefix-free
    (let [big {:indexes (into {} (map (fn [i] [(keyword (format "i%05d" i)) 1])) (range 600))}
          codes (mapv #(vec (ob big %)) (cons :identity (sort (keys (:indexes big)))))]
      (is (= (range 601) (map #(ordinal big %) (cons :identity (sort (keys (:indexes big)))))))
      (is (= 601 (count (set codes))))
      (is (= codes (sort-by identity (fn [a b] (Arrays/compareUnsigned (byte-array a) (byte-array b))) codes)))
      (is (every? (fn [[a b]] (not= a (take (count a) b))) (partition 2 1 codes))))))
