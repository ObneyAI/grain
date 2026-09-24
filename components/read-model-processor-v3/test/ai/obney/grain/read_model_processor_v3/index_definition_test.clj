(ns ai.obney.grain.read-model-processor-v3.index-definition-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.index-definition :as d]))

(def options
  { :events #{:student/changed}
   :schema [:map-of :string [:map [:name [:map [:surname :string]]] [:balance :int]]]
   :indexes {:name {:fields [[:name :surname]]} :balance {:fields [:balance]}}})

(deftest schema-derived-validators
  (let [compiled (d/compile-definition :test/students options)]
    (is ((:id-valid? compiled) "s1"))
    (is (not ((:id-valid? compiled) 1)))
    (is ((:record-valid? compiled) {:name {:surname "Jones"} :balance 0}))
    (is (= :string (get-in compiled [:indexes :name 0 :type])))
    (is (= [:name :surname] (get-in compiled [:indexes :name 0 :path]))))
  (is (empty? (:indexes (d/compile-definition :test/ordinary {})))))

(deftest invalid-declarations-have-actionable-data
  (doseq [[opts offending]
          [[(assoc options :kind :collection) :kind]
           [(assoc options :indexes []) :indexes]
           [(assoc options :version 0) :version]
           [(assoc options :version (inc (bigint Long/MAX_VALUE))) :version]
           [(assoc options :schema [:map]) :schema]
           [(assoc options :partition-fn 42) :partition-fn]
           [(assoc options :l1-ttl-ms 10) :l1-ttl-ms]
           [(assoc options :indexes {:name {:fields []}}) :fields]
           [(assoc options :indexes {:name {:fields [:surname]}}) nil]
           [(assoc options :indexes {:name {:fields [:name]}}) nil]
           [(assoc options :schema [:map-of :string [:map [:balance [:maybe :int]]]]) nil]
           [(assoc options :schema [:map-of :string [:map [:balance {:optional true} :int]]]) nil]]]
    (let [e (try (d/compile-definition :test/students opts) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :test/students (:read-model (ex-data e))))
      (is (= :invalid-indexed-definition (:error (ex-data e))))
      (when offending (is (= offending (:option (ex-data e))))))))

(deftest map-cardinality-schema-properties-are-enforced
  (let [compiled (d/compile-definition :test/bounded {:schema [:map-of {:min 1 :max 2} :string :int]})]
    (is (not ((:state-valid? compiled) {})))
    (is ((:state-valid? compiled) {"a" 1}))
    (is (not ((:state-valid? compiled) {"a" 1 "b" 2 "c" 3})))))
