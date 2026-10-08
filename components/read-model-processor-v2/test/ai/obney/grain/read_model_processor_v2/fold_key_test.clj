(ns ai.obney.grain.read-model-processor-v2.fold-key-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key])
  (:import [java.io EOFException]
           [java.nio ByteBuffer BufferUnderflowException]
           [java.util UUID]))

(defn- read-physical-key! [^ByteBuffer in]
  (case (bit-and 255 (.get in))
    1 nil 2 false 3 true
    16 (let [out (StringBuilder.)
             read-unit (fn [] (let [b (bit-and 255 (.get in))]
                               (if (zero? b)
                                 (case (bit-and 255 (.get in)) 0 -1 255 0
                                   (throw (ex-info "Invalid ordered string escape" {}))) b)))]
         (loop []
           (let [hi (read-unit)]
             (if (= -1 hi) (.toString out)
               (let [lo (read-unit)]
                 (when (= -1 lo) (throw (ex-info "Truncated ordered character" {})))
                 (.append out (char (+ (bit-shift-left hi 8) lo))) (recur))))))
    32 (bit-xor (.getLong in) Long/MIN_VALUE)
    48 (UUID. (.getLong in) (.getLong in))
    56 (keyword (subs (read-physical-key! in) 1))
    64 (loop [out []]
         (.mark in)
         (if (zero? (.get in)) out
           (do (.reset in) (recur (conj out (read-physical-key! in))))))
    (throw (ex-info "Invalid ordered key type" {}))))

(defn decode-physical
  "Test oracle for physical key round-trips; production only decodes metadata."
  ([^bytes value] (decode-physical value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (try
     (read-physical-key! (ByteBuffer/wrap value offset length))
     (catch BufferUnderflowException _ (throw (EOFException.))))))

(defn encoded-order [xs encode]
  (sort #(java.util.Arrays/compareUnsigned ^bytes (encode %1) ^bytes (encode %2)) xs))

(deftest joined-storage-keys-preserve-bytes-and-own-their-buffer
  (let [a (byte-array (map unchecked-byte (range 256)))
        b (key/encode ["a\u0000b" :catalog/id 42])
        expected (into (vec a) b)
        joined (key/join a (byte-array 0) b)]
    (is (= expected (vec joined)))
    (is (= [] (vec (key/join))))
    (is (= (vec a) (vec (key/join a))))
    (aset-byte a 0 (byte 99))
    (aset-byte b 0 (byte 99))
    (is (= expected (vec joined)))))

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

(deftest ordered-decoding-preserves-values-and-field-boundaries
  (doseq [value [nil false true Long/MIN_VALUE -1 0 1 Long/MAX_VALUE
                 #uuid "00000000-0000-0000-0000-000000000000"
                 #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
                 :catalog/course :plain "" "a\u0000b" "\ud83d\ude00" "\ud800"
                 [] [nil false ["nested" 42 [:catalog/course true]]]
                 (String. (char-array (map char (range 65536))))]]
    (let [encoded (key/encode value)
          data (key/join (byte-array [99 98 97]) encoded (key/encode "following field"))]
      (is (= value (decode-physical encoded)))
      (is (= value (decode-physical data 3 (alength encoded))))
      (is (thrown? java.io.EOFException (decode-physical data 3 (dec (alength encoded))))
          "A truncated field must not consume its following field")
      (let [decoded (decode-physical data 3 (alength encoded))]
        (java.util.Arrays/fill data (byte 0))
        (is (= value decoded) "Results do not retain the mutable input view"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid ordered string escape"
                       (decode-physical (byte-array [16 0 1]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Truncated ordered character"
                       (decode-physical (byte-array [16 1 0 0])))))

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
