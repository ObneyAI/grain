(ns ai.obney.grain.read-model-processor-v2.fold-key-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]))

(defn encoded-order [xs encode]
  (sort #(java.util.Arrays/compareUnsigned ^bytes (encode %1) ^bytes (encode %2)) xs))

(deftest prefix-upper-bounds
  (doseq [[prefix end] [[[0] [1]] [[1 -1] [2]] [[1 2 -1] [1 3]]
                        [[-2 -1] [-1]] [[-1] nil] [[-1 -1] nil] [[] nil]]]
    (is (= end (some-> (key/prefix-end (byte-array prefix)) vec)))))

(deftest ordered-keys-preserve-scalar-and-tuple-order
  (doseq [xs [[Long/MIN_VALUE -1000 -1 0 1 255 256 65536 Long/MAX_VALUE]
              ["" "\u0000" "\u0000\u0000" "a" "a\u0000" "aa" "b" "\u00ff" "\u0100" "\uffff"]
              [["a" -1] ["a" 0] ["a" 1] ["aa" -1] ["b" -100]]]]
    (is (= (sort xs) (encoded-order (reverse xs) key/encode)))
    (is (= (reverse (sort xs)) (encoded-order xs (comp key/descending key/encode))))))

(deftest encodings-are-distinct-and-self-delimiting
  (let [xs [nil false true "" "a" "a\u0000" "ab" "a/b" :a/b 0 1
            [] [nil] [false] ["a"] ["a" "b"] ["ab"]
            #uuid "00000000-0000-0000-0000-000000000001"]
        encodings (mapv #(vec (key/encode %)) xs)]
    (is (= (count xs) (count (set encodings))))
    (doseq [a encodings b encodings :when (not= a b)]
      (is (not= a (take (count a) b)))))
  (doseq [unsupported [1.1 1/2 (inc (bigint Long/MAX_VALUE)) {} #{} '(1)]]
    (is (thrown? clojure.lang.ExceptionInfo (key/encode unsupported)))))

(deftest string-encoding-preserves-stored-bytes
  ;; These are the existing wire bytes, expressed as unsigned decimal values.
  (doseq [[value expected] [["" [16 0 0]]
                           ["Ada" [16 0 255 65 0 255 100 0 255 97 0 0]]
                           ["\u0000" [16 0 255 0 255 0 0]]
                           ["\u00ff\u0100" [16 0 255 255 1 0 255 0 0]]
                           ["\ud83d\ude00" [16 216 61 222 0 255 0 0]]
                           ["\ud800" [16 216 0 255 0 0]]
                           ["\udfff" [16 223 255 0 0]]]]
    (is (= expected (mapv #(bit-and 255 %) (key/encode value)))))
  ;; Cover every UTF-16 code unit, including unpaired surrogates. The digest
  ;; freezes the old encoding; the length also checks zero-byte escaping.
  (let [value (String. (char-array (map char (range 65536))))
        encoded (key/encode value)]
    (is (= 131587 (alength ^bytes encoded)))
    (is (= "039b63a1afbedb79d5cd86d3e4fd896ec8d041380f1f6d7850fd21f1d1957d76"
           (.formatHex (java.util.HexFormat/of)
                       (.digest (java.security.MessageDigest/getInstance "SHA-256") encoded))))))
