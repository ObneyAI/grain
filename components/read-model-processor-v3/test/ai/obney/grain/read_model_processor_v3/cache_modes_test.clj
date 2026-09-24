(ns ai.obney.grain.read-model-processor-v3.cache-modes-test
  "v2 tier-selection tests become unified-store and durable-checkpoint tests."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.fixtures :as support]
            [ai.obney.grain.read-model-processor-v3.interface.testing :as ct]
            [ai.obney.grain.read-model-processor-v3.indexed-test :as fixture]))
(use-fixtures :each fixture/test-fixture)

(deftest every-event-is-durable-without-tier-or-threshold-selection
  (let [calls (atom 0)]
    (rmp/register-read-model! fixture/model (fn [s e] (swap! calls inc) (assoc s (:id e) (:record e))) fixture/options)
    (fixture/put! "a" "A" :active 1)
    (let [result (fixture/page)]
      (is (= (:watermark result) (:watermark (ct/committed fixture/*context* fixture/model)))))
    (fixture/put! "b" "B" :active 2) (fixture/page)
    (is (= 2 @calls))
    (is (= 2 (count (:data (ct/committed fixture/*context* fixture/model)))) )
    (fixture/page) (is (= 2 @calls))))

(deftest obsolete-tuning-is-rejected
  (doseq [option [:cache-mode :checkpoint-threshold :segment-count :segment-threshold
                  :l1-ttl-ms :l1-max-entries :kind :storage]]
    (is (= :invalid-indexed-definition
           (support/error-code #(rmp/register-read-model! :unified/check identity {option 1}))))))

(deftest arbitrary-root-shapes-use-the-same-store
  (doseq [[suffix initial next] [[:scalar 42 43] [:vector [1 2] [1 2 3]]
                                  [:set #{1 2} #{3}] [:nil nil nil]
                                  [:false false true] [:map {:a nil :b [1 2]} {:c #{3}}]]]
    (let [name (keyword "shape" (name suffix))]
      (rmp/register-read-model! name (fn [_ e] (if (= "first" (:id e)) initial next))
                                {:version 2 :events (:events fixture/options)}) ))
  (fixture/put! "first" "A" :active 1)
  (doseq [[name expected] [[:shape/scalar 42] [:shape/vector [1 2]] [:shape/set #{1 2}]
                         [:shape/nil nil] [:shape/false false] [:shape/map {:a nil :b [1 2]}]]]
    (is (= expected (rmp/project fixture/*context* name))))
  (fixture/put! "second" "B" :active 2)
  (doseq [[name expected] [[:shape/scalar 43] [:shape/vector [1 2 3]] [:shape/set #{3}]
                         [:shape/nil nil] [:shape/false true] [:shape/map {:c #{3}}]]]
    (is (= expected (rmp/project fixture/*context* name))))
  (is (nil? (:item (rmp/record fixture/*context* :shape/map :missing))))
  (is (= :not-map-projection (support/error-code #(rmp/record fixture/*context* :shape/scalar :x)))))

(deftest map-replacement-clear-metadata-and-nil-membership
  (rmp/register-read-model! fixture/model
    (fn [s e]
      (case (:id e)
        "first" (with-meta {:a nil :nested {:x 1}} {:source :test})
        "replace" (with-meta (persistent! (assoc! (transient {}) :b false)) {:source :replacement})
        "clear" {}
        s)) {:version 2 :events (:events fixture/options)})
  (fixture/put! "first" "Ignored" :active 1)
  (let [s (rmp/project fixture/*context* fixture/model)]
    (is (contains? s :a)) (is (= [:a nil] (find s :a)))
    (is (= :fallback (get s :missing :fallback)))
    (is (= {:source :test} (meta s)))
    (is (= {:item {:id :a :value nil} :watermark (:watermark (ct/committed fixture/*context* fixture/model))}
           (rmp/record fixture/*context* fixture/model :a))))
  (fixture/put! "replace" "Ignored" :active 2)
  (is (= {:b false} (rmp/project fixture/*context* fixture/model)))
  (fixture/put! "clear" "Ignored" :active 3)
  (is (= {} (rmp/project fixture/*context* fixture/model))))

(deftest equal-compound-keys-address-the-same-entry
  (let [k1 (array-map :a 1 :b 2) k2 (array-map :b 2 :a 1)]
    (rmp/register-read-model! fixture/model (fn [s _] (assoc s k1 :yes [1 :a] :vector 1 :integer))
                              {:version 2 :events (:events fixture/options)})
    (fixture/put! "first" "Ignored" :active 1)
    (let [s (rmp/project fixture/*context* fixture/model)]
      (is (= :yes (get s k2))) (is (= :vector (get s '(1 :a))))
      (is (= :integer (get s 1N))))))

(deftest map-state-crosses-the-old-segmentation-boundary-without-a-format-change
  (rmp/register-read-model! fixture/model
    (fn [s e]
      (if (= "seed" (:id e))
        (into {} (map (fn [i] [i {:value i}])) (range 10001))
        (assoc-in s [10000 :value] -1)))
    {:version 2 :events (:events fixture/options)})
  (fixture/put! "seed" "Ignored" :active 0)
  (let [old (rmp/project fixture/*context* fixture/model)]
    (is (= 10001 (count old)))
    (fixture/put! "update" "Ignored" :active 0)
    (let [new (rmp/project fixture/*context* fixture/model)]
      (is (= 10001 (count new))) (is (= -1 (get-in new [10000 :value])))
      (is (= 10000 (get-in old [10000 :value])))
      (is (= 9999 (get-in new [9999 :value]))))))
