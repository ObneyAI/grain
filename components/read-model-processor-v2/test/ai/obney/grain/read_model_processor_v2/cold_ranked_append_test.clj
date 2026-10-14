(ns ai.obney.grain.read-model-processor-v2.cold-ranked-append-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(deftest partially-built-cold-index-rolls-back-and-retries
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (keyword "cold-append-test" (str (random-uuid)))
            _ (rmp/register-read-model! model merge
                {:events #{:keyed/change} :version 1 :route :inputs
                 :indexes {:score :score} :filtered-indexes #{:score}})
            rows (mapv (fn [n] [n {:score (when (odd? n) (mod n 5))}]) (range 80))
            append-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'ranked-append!)
            append! @append-var]
        (with-redefs [core/ranked-random-hash (constantly 0)]
          (rmp/progress ctx model)
          (let [before (fixture/keyed-stored-bytes) calls (atom 0)]
            (fixture/keyed-append! [{:inputs rows}])
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"partial cold index"
                  (with-redefs-fn
                    {append-var (fn [& args]
                                  (let [added? (apply append! args)]
                                    (when (= 9 (swap! calls inc))
                                      (throw (ex-info "partial cold index" {}))) added?))}
                    #(rmp/value ctx model 79))))
            (is (= before (fixture/keyed-stored-bytes))
                "Partial ranks, groups, records, membership and progress all roll back")
            (is (= (into {} rows) (fixture/keyed-results ctx model))
                "Retry from unchanged durable progress includes every owner")))))))

(deftest captured-random-towers-and-filtered-sentinels-have-exact-native-parity
  (let [tenant (java.util.UUID. 61 1) identity (java.util.UUID. 62 1)
        owners (vec (concat (range 48) [identity (str identity)] (mapv vector (range 12))))
        rows (mapv (fn [id n] [id {:score (when (odd? n) (mod n 7))}]) owners (range))
        frozen-time (java.time.OffsetDateTime/parse "2026-10-14T00:00:00Z")
        run (fn [legacy? splits?]
              (fixture/keyed-fixture
                (fn []
                  (let [ctx (cond-> (assoc fixture/*keyed-ctx* :tenant-id tenant)
                              legacy? (assoc :read-model/observer (fn [_])))
                        random (java.util.Random. 41) counter (atom 0)
                        model :cold-append-test/captured-towers]
                    (with-redefs [core/ranked-random-hash (if splits? (constantly 0) (fn [_] (.nextInt random)))
                                  time/now (constantly frozen-time)
                                  uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))]
                      (rmp/register-read-model! model merge
                        {:events #{:keyed/change} :version 1 :route :inputs
                         :indexes {:score :score} :filtered-indexes #{:identity :score}})
                      (rmp/progress ctx model)
                      (fixture/keyed-append! ctx [{:inputs rows}])
                      (is (= (into {} rows) (fixture/keyed-results ctx model)))
                      (fixture/keyed-stored-bytes))))))]
    (doseq [all-splits? [false true]]
      (is (= (run true all-splits?) (run false all-splits?))
          "Original-order RNG assignment, typed identity ties, nil groups and empty filtered sentinels persist exactly"))))
