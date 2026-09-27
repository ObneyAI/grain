(ns ai.obney.grain.fressian-util.interface-test
  (:require [ai.obney.grain.fressian-util.interface :as fu]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant OffsetDateTime ZoneOffset]))

(deftest offset-date-time-round-trips
  (testing "UTC"
    (let [odt (OffsetDateTime/now ZoneOffset/UTC)
          decoded (fu/decode (fu/encode odt))]
      (is (instance? OffsetDateTime decoded))
      (is (= odt decoded))))

  (testing "non-UTC offset preserved"
    (let [odt (OffsetDateTime/now (ZoneOffset/of "-05:00"))
          decoded (fu/decode (fu/encode odt))]
      (is (instance? OffsetDateTime decoded))
      (is (= odt decoded))
      (is (= (.getOffset odt) (.getOffset ^OffsetDateTime decoded))))))

(deftest instant-round-trips
  (let [inst (Instant/now)
        decoded (fu/decode (fu/encode inst))]
    (is (instance? Instant decoded))
    (is (= inst decoded))))

(deftest nested-structure-round-trips
  (let [odt (OffsetDateTime/now ZoneOffset/UTC)
        inst (Instant/now)
        data {:event/timestamp odt
              :body {:started-at inst
                     :items #{1 2 3}
                     :history [{:at inst :v 1} {:at inst :v 2}]}}
        decoded (fu/decode (fu/encode data))]
    (is (= data decoded))
    (is (instance? OffsetDateTime (:event/timestamp decoded)))
    (is (instance? Instant (get-in decoded [:body :started-at])))
    (is (set? (get-in decoded [:body :items])) "sets stay Clojure sets after deep-clojurize")
    (is (vector? (get-in decoded [:body :history])) "vectors stay Clojure vectors after deep-clojurize")))

(deftest stock-clojure-types-still-work
  (testing "keyword, symbol, set, ratio, vector, map survive round-trip alongside the new handlers"
    (let [data {:kw :my/keyword
                :sym 'my.ns/sym
                :set #{:a :b :c}
                :ratio 3/7
                :vec [1 2 3]
                :nested {:inner [{:x 1}]}}
          decoded (fu/decode (fu/encode data))]
      (is (= data decoded))
      (is (set? (:set decoded))))))

(deftest old-payloads-without-java-time-decode-unchanged
  (testing "a payload that contains no java.time values decodes identically under the new decoder"
    (let [data {:data {:count 5 :items #{:a :b}} :watermark (random-uuid)}
          decoded (fu/decode (fu/encode data))]
      (is (= data decoded)))))

(deftest normalization-reuses-unchanged-clojure-collections
  (doseq [value [{} #{} []
                 {:name "Ada" :count 3 :optional nil}
                 (into {} (map (fn [i] [i {:items [i]}]) (range 40)))
                 #{[:a :b] {:id 1}}
                 [{:courses #{[1 2]}} nil]
                 {[:course 1] {:labels #{"A"}}}]]
    (is (identical? value (#'fu/deep-clojurize value)))))

(deftest normalization-replaces-only-changed-branches
  (let [unchanged {:name "Ada" :tags #{"student"}}
        java-list (java.util.ArrayList. ["course"])
        nested {:items [unchanged java-list] :untouched unchanged}
        result (#'fu/deep-clojurize nested)]
    (is (= {:items [unchanged ["course"]] :untouched unchanged} result))
    (is (not (identical? nested result)))
    (is (not (identical? (:items nested) (:items result))))
    (is (identical? unchanged (:untouched result)))
    (is (identical? unchanged (get-in result [:items 0])))
    (is (vector? (get-in result [:items 1])))
    (is (identical? java-list (get-in nested [:items 1])) "Input remains untouched")
    (is (identical? result (#'fu/deep-clojurize result)))))

(deftest normalization-converts-keys-set-members-and-nested-java-collections
  (let [java-key (java.util.ArrayList. ["course" 1])
        java-map (doto (java.util.HashMap.) (.put java-key (java.util.HashSet. [java-key])))
        native-map {java-key {:members #{java-key}}}
        native-set #{java-key}
        normalized-map (#'fu/deep-clojurize native-map)
        normalized-set (#'fu/deep-clojurize native-set)
        normalized-java (#'fu/deep-clojurize java-map)]
    (is (vector? (first (keys normalized-map))))
    (is (= {:members #{["course" 1]}} (get normalized-map ["course" 1])))
    (is (vector? (first (:members (get normalized-map ["course" 1])))))
    (is (vector? (first normalized-set)))
    (is (contains? normalized-set ["course" 1]))
    (is (map? normalized-java))
    (is (set? (get normalized-java ["course" 1])))
    (is (vector? (first (get normalized-java ["course" 1]))))
    (is (identical? java-key (first (keys native-map))))
    (is (identical? java-key (first native-set))))
  (testing "conversion survives an actual codec round-trip, including hashed lookups"
    (let [value {[:course 1] #{[:student 2] [:student 3]}}
          result (fu/decode (fu/encode value))]
      (is (= value result))
      (is (every? vector? (keys result)))
      (is (every? vector? (get result [:course 1])))
      (is (contains? (get result [:course 1]) [:student 2])))))
