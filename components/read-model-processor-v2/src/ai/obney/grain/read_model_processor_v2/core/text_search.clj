(ns ai.obney.grain.read-model-processor-v2.core.text-search
  "Transactional positional trigrams. Exact phrase alignment follows the same
   offset rule as Lucene ExactPhraseMatcher; disjoint grams plus the last gram
   cover the query as in NGramPhraseQuery. No Lucene storage or copied source."
  (:require [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.core.sort-data :as data]
            [ai.obney.grain.read-model-processor-v2.core.instance-ids :as ids]
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
    (let [ordinal (ids/ensure-ordinal! tx b id)]
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

(defn- aligned? [^bytes pivot pivot-offset postings query-length mode]
  (let [length (data/int-at pivot 0)]
    (loop [at 4]
      (when (< at (alength pivot))
        (let [start (- (data/int-at pivot at) pivot-offset)]
          (if (and (<= 0 start (- length query-length))
                   (case mode :prefix (zero? start) :suffix (= start (- length query-length)) true)
                   (every? (fn [[^bytes posting offset]] (has-position? posting (+ start offset))) postings))
            true (recur (+ at 4))))))))

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
          pivot (apply min-key :count candidates)]
      (if (zero? (:count pivot)) []
        (persistent!
          ((:reduce tx) {:prefix (:prefix pivot)}
           (fn [out {:keys [^bytes k ^bytes v]}]
             (observe :search-candidate (alength v))
             (let [ordinal (data/int-at k (- (alength k) 4))
                   postings (reduce (fn [out {:keys [term prefix offset]}]
                                      (if-let [raw (if (= term (:term pivot)) v
                                                      ((:get tx) (posting-key prefix ordinal)))]
                                        (conj out [raw offset]) (reduced nil))) [] candidates)]
               (if (and postings (aligned? v (:offset pivot) postings n mode))
                 (do (observe :search-match 0)
                     (conj! out (codec/decode ((:get tx) (slot b 8 (number-bytes ordinal)))))) out)))
           (transient [])))))))
