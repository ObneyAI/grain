(ns ai.obney.grain.read-model-processor-v2.core.text-search
  "Transactional positional trigrams. Exact phrase alignment follows the same
   offset rule as Lucene ExactPhraseMatcher; disjoint grams plus the last gram
   cover the query as in NGramPhraseQuery. No Lucene storage or copied source."
  (:require [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.core.sort-data :as data]
            [ai.obney.grain.read-model-processor-v2.core.span-membership :as membership]
            [ai.obney.grain.fressian-util.interface :as codec])
  (:import [java.nio ByteBuffer]
           [java.util Locale Arrays]))

(defn normalize [text]
  (when-not (or (nil? text) (string? text))
    (throw (ex-info "Search text extractor must return a string or nil" {})))
  (let [^String text (or text "")] (.toLowerCase text Locale/ROOT)))

(defn- number-bytes ^bytes [n] (.array (doto (ByteBuffer/allocate 4) (.putInt (int n)))))
(defn- slot [b kind suffix] (key/checked (key/join b (byte-array [kind]) suffix)))

(defn- config [b index] (slot b 6 (key/encode index)))
(defn- term-key [cfg kind term] (key/checked (key/join cfg (byte-array [kind]) (key/encode term))))
(defn- posting-key [prefix ordinal] (key/checked (key/join prefix (number-bytes ordinal))))

(defn- terms [^String text]
  (let [^ints points (.toArray (.codePoints text)) n (alength points)
        grams (reduce (fn [out i] (update out [:gram (String. points (int i) 3)] (fnil conj []) i))
                      {} (range (max 0 (- n 2))))
        terms (reduce (fn [out width]
                        (assoc out [:prefix (String. points 0 (int width))] [0]
                                   [:suffix (String. points (int (- n width)) (int width))] [(- n width)]))
                      grams (range 1 (inc (min 2 n))))]
    (into {} (for [[term positions] terms]
               (let [out (ByteBuffer/allocate (* 4 (inc (count positions))))]
                 (.putInt out n) (doseq [position positions] (.putInt out (int position)))
                 [term (.array out)])))))

(defn- change-count! [tx cfg term delta]
  (let [k (term-key cfg 1 term)
        old (if-let [raw ((:get tx) k)] (data/int-at raw 0) 0)
        next (+ old delta)]
    (when (neg? next) (throw (ex-info "Negative text posting count" {})))
    (if (zero? next) ((:delete tx) k) ((:put tx) k (number-bytes next)))))

(defn update!
  "Called only inside the fold's write transaction. before/after are included
   result entries or nil. Removed identities retain their ordinal for re-entry."
  [tx b searches id before after]
  (when (seq searches)
    (let [ordinal (membership/ensure-ordinal! tx b id)]
      (doseq [[index extract] searches
              :let [old (when before (normalize (extract (:value before))))
                    next (when after (normalize (extract (:value after))))]
              :when (not= old next)]
        (let [cfg (config b index) old (if old (terms old) {}) next (if next (terms next) {})]
          (doseq [[term _] old :when (not (contains? next term))]
            ((:delete tx) (posting-key (term-key cfg 0 term) ordinal))
            (change-count! tx cfg term -1))
          (doseq [[term ^bytes value] next]
            (when-not (Arrays/equals ^bytes (get old term) value)
              ((:put tx) (posting-key (term-key cfg 0 term) ordinal) value)
              (when-not (contains? old term) (change-count! tx cfg term 1)))))))))

(defn- has-position? [^bytes posting position]
  (loop [lo 1 hi (quot (alength posting) 4)]
    (if (>= lo hi) false
      (let [mid (quot (+ lo hi) 2) found (data/int-at posting (* 4 mid))]
        (cond (= found position) true (< found position) (recur (inc mid) hi)
              :else (recur lo mid))))))

(defn- aligned? [^bytes pivot pivot-offset ^objects postings ^ints offsets query-length mode]
  (let [length (data/int-at pivot 0)]
    (loop [at 4]
      (when (< at (alength pivot))
        (let [start (- (data/int-at pivot at) pivot-offset)]
          (if (and (<= 0 start (- length query-length))
                   (case mode :prefix (zero? start) :suffix (= start (- length query-length)) true)
                   (loop [i 0]
                     (or (= i (alength postings))
                         (and (has-position? (aget postings i) (+ start (aget offsets i)))
                              (recur (inc i))))))
            true (recur (+ at 4))))))))

(defn- copy-ordinal! [^bytes source ^bytes target]
  (System/arraycopy source (- (alength source) 4) target (- (alength target) 4) 4))

(defn- read-postings! [tx ^bytes k v ^objects lookups ^objects postings]
  (loop [i 0]
    (if (= i (alength lookups)) true
      (let [lookup (aget lookups i)
            raw (if lookup
                  (do (copy-ordinal! k lookup) ((:get tx) lookup))
                  v)]
        (when raw
          (aset postings i raw)
          (recur (inc i)))))))

(defn search-keys [tx b searches {:keys [index query mode] :or {mode :contains} :as options} observe]
  (when-not (and (every? #{:index :query :mode} (keys options))
                 (contains? searches index) (string? query) (#{:contains :prefix :suffix} mode))
    (throw (ex-info "Invalid text search; supply a declared :index, string :query and optional :mode" {:options options})))
  (let [query (normalize query) ^ints points (.toArray (.codePoints ^String query)) n (alength points)]
    (when (< n (if (= mode :contains) 3 1))
      (throw (ex-info "Contains search needs three code points; boundary search needs a nonempty fragment"
                      {:type :grain.read-model/short-search :mode mode})))
    (let [cfg (config b index)
          query-terms (if (< n 3) [[[mode query] 0]]
                        (mapv (fn [offset] [[:gram (String. points (int offset) 3)] offset])
                              (distinct (concat (range 0 (- n 2) 3) [(- n 3)]))))
          candidates (mapv (fn [[term offset]]
                             {:term term :offset offset :prefix (term-key cfg 0 term)
                              :count (if-let [raw ((:get tx) (term-key cfg 1 term))] (data/int-at raw 0) 0)}) query-terms)
          pivot (apply min-key :count candidates)
          ;; Private scratch space for this search's synchronous point reads.
          ;; Only decoded, detached identities leave this operation.
          lookups (object-array (map #(when (not= (:term %) (:term pivot))
                                        (posting-key (:prefix %) 0)) candidates))
          offsets (int-array (map :offset candidates))
          postings (object-array (count candidates))
          identity-key (slot b 8 (number-bytes 0))]
      (if (zero? (:count pivot)) []
        (persistent!
          ((:reduce tx) {:prefix (:prefix pivot)}
           (fn [out {:keys [^bytes k ^bytes v]}]
             (observe :search-candidate (alength v))
             (if (and (read-postings! tx k v lookups postings)
                      (aligned? v (:offset pivot) postings offsets n mode))
               (do (observe :search-match 0)
                   (copy-ordinal! k identity-key)
                   (conj! out (codec/decode ((:get tx) identity-key)))) out))
           (transient [])))))))
