(ns ai.obney.grain.read-model-processor-v2.core.sort-data
  "Compact forward index values. Compare slices without decoding result maps."
  (:refer-clojure :exclude [comparator])
  (:require [ai.obney.grain.read-model-processor-v2.core.fold-key :as key])
  (:import [java.nio ByteBuffer]
           [java.util Arrays]))

(defn int-at ^long [^bytes data ^long offset]
  (bit-or (bit-shift-left (bit-and 255 (aget data offset)) 24)
          (bit-shift-left (bit-and 255 (aget data (inc offset))) 16)
          (bit-shift-left (bit-and 255 (aget data (+ offset 2))) 8)
          (bit-and 255 (aget data (+ offset 3)))))

(defn field [indexes index]
  (if (= :identity index) 0
    (+ 2 (.indexOf ^java.util.List (vec (sort (keys indexes))) index))))

(defn encode ^bytes [id indexes]
  (let [fields (mapv key/encode (concat [(str id) id] (map val (sort-by key indexes))))
        header (* 4 (inc (count fields)))
        size (+ header (reduce + (map #(alength ^bytes %) fields)))
        out (ByteBuffer/allocate size)]
    (loop [offset header fields (seq fields)]
      (.putInt out offset)
      (when-let [^bytes value (first fields)] (recur (+ offset (alength value)) (next fields))))
    (doseq [^bytes value fields] (.put out value))
    (.array out)))

(defn value [^bytes data ^long field]
  (let [start (int-at data (* 4 field)) end (int-at data (* 4 (inc field)))]
    (key/decode data start (- end start))))

(defn values
  "Decode requested fields directly into a detached immutable map."
  [data indexes fields]
  (persistent!
   (reduce-kv (fn [out i index] (assoc! out index (value data (nth fields i))))
              (transient {}) indexes)))

(defn compare-field ^long [^bytes a ^bytes b ^long field]
  (Arrays/compareUnsigned a (int-at a (* 4 field)) (int-at a (* 4 (inc field)))
                          b (int-at b (* 4 field)) (int-at b (* 4 (inc field)))))

(deftype Entry [id ^bytes data ^bytes computed])

(defn comparator [field descending? computed?]
  (reify java.util.Comparator
    (compare [_ a b]
      (let [^Entry a a ^Entry b b
            c (if computed? (Arrays/compareUnsigned ^bytes (.-computed a) ^bytes (.-computed b))
                  (compare-field (.-data a) (.-data b) field))]
        (if (zero? c)
          (let [tie (compare-field (.-data a) (.-data b) 0)]
            (int (if (zero? tie) (compare-field (.-data a) (.-data b) 1) tie)))
          (int (if descending? (- c) c)))))))
