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
