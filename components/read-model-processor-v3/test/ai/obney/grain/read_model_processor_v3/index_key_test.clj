(ns ai.obney.grain.read-model-processor-v3.index-key-test
  "The old custom tuple codec is replaced by native-index ordering and opaque cursors."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.indexed-test :as fixture]
            [ai.obney.grain.read-model-processor-v3.fixtures :as support]))
(use-fixtures :each fixture/test-fixture)
(deftest cursor-roundtrip-and-exclusive-ordering
  (doseq [[id balance] [["negative" Long/MIN_VALUE] ["zero" 0] ["positive" Long/MAX_VALUE]]]
    (fixture/put! id "Same" :active balance))
  (loop [after nil ids []]
    (let [p (fixture/page {:index :balance :prefix [] :limit 1 :after after})
          ids (into ids (fixture/ids p))]
      (if (:next-cursor p) (recur (:next-cursor p) ids)
        (is (= ["negative" "zero" "positive"] ids))))))
(deftest malformed-and-query-mismatched-cursors-fail
  (fixture/put! "a" "A" :active 1) (fixture/put! "b" "B" :active 2)
  (let [cursor (:next-cursor (fixture/page {:limit 1}))]
    (doseq [token ["" "3.." (str cursor "x") (apply str (repeat 100000 "x"))]]
      (is (= :invalid-cursor (support/error-code #(fixture/page {:after token})))) )
    (is (= :invalid-cursor (support/error-code #(fixture/page {:prefix [:inactive] :after cursor}))))))

(deftest invalid-unicode-index-values-are-rejected
  (fixture/put! "a" (str (char 0xD800)) :active 0)
  (is (= :invalid-index-value (support/error-code fixture/page))))
