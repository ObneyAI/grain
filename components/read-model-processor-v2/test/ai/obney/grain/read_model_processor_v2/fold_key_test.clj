(ns ai.obney.grain.read-model-processor-v2.fold-key-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.read-model-processor-v2.fold-key :as key]))

(defn encoded-order [xs encode]
  (sort #(java.util.Arrays/compareUnsigned ^bytes (encode %1) ^bytes (encode %2)) xs))

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
