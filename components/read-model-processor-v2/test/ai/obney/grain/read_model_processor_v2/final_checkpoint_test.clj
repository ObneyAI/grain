(ns ai.obney.grain.read-model-processor-v2.final-checkpoint-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.fressian-util.interface :as codec]))

(defn counted-writes [f]
  (let [calls (atom 0) transact kv/transact]
    (with-redefs [kv/transact (fn [cache mode callback]
                              (when (= :write mode) (swap! calls inc))
                              (transact cache mode callback))]
      {:value (f) :writes @calls})))

(deftest final-partial-batch-publishes-checkpoint-with-one-less-commit
  (doseq [n [0 1 127 128 129 256]]
    (fixture/keyed-fixture
      (fn []
        ;; Fixed batches: this test is about the boundary of a full batch.
        (let [ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 128)
              model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
          (rmp/progress ctx model)
          (if (zero? n)
            (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx)
                      :events [(es/->event {:type :keyed/other :body {}})]})
            (fixture/keyed-append! (vec (repeat n {:owner "a" :delta 1}))))
          (with-redefs [es/stable-empty-intervals? (constantly false)]
            (let [{:keys [value writes]} (counted-writes #(rmp/value ctx model "a"))
                  progress (rmp/progress ctx model)]
              (is (= (if (zero? n) nil {:total n}) value))
              (is (= (inc (quot n 128)) writes)
                  (str "event count " n))
              (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through progress)))
              (is (zero? (:writes (counted-writes #(rmp/value ctx model "a"))))))))))))

(deftest final-tail-failure-keeps-full-batches-and-retries-exactly-once
  (fixture/keyed-fixture
    (fn []
      (let [fail? (atom true)
            model (fixture/keyed-register!
                    (fn [state event]
                      (when (and @fail? (:fail? event)) (throw (ex-info "tail failed" {})))
                      (fixture/keyed-history-step state event))
                    {:result fixture/keyed-total})
            ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 128)]
        (fixture/keyed-append! (conj (vec (repeat 128 {:owner "a" :delta 1}))
                                     {:owner "a" :delta 2 :fail? true}))
        (is (thrown-with-msg? Exception #"tail failed" (rmp/value ctx model "a")))
        (let [apply-var #'core/keyed-apply-batch! apply-batch @apply-var calls (atom [])]
          (reset! fail? false)
          (with-redefs-fn {apply-var (fn [& args]
                                     (swap! calls conj (mapv :delta (nth args 6)))
                                     (apply apply-batch args))}
            #(is (= {:total 130} (rmp/value ctx model "a"))))
          (is (= [[2]] @calls)))
        (is (= {:total 130} (rmp/value ctx model "a")))))))

(deftest source-failure-before-tail-publication-keeps-tail-and-checkpoint-absent
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 128)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            read-events es/read]
        (rmp/progress ctx model)
        (fixture/keyed-append! (vec (repeat 129 {:owner "a" :delta 1})))
        (with-redefs [es/read (fn [& args]
                               (let [events (into [] (apply read-events args))]
                                 (concat events (lazy-seq (throw (ex-info "source failed" {}))))))]
          (is (thrown-with-msg? Exception #"source failed" (rmp/value ctx model "a"))))
        (let [apply-var #'core/keyed-apply-batch! apply-batch @apply-var calls (atom [])]
          (with-redefs-fn {apply-var (fn [& args] (swap! calls conj (count (nth args 6)))
                                     (apply apply-batch args))}
            #(is (= {:total 129} (rmp/value ctx model "a"))))
          (is (= [1] @calls)))))))

(deftest concurrent-filtered-tail-preserves-newer-progress-and-other-fields
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            apply-var #'core/keyed-apply-batch! apply-batch @apply-var
            once? (atom true) newer (atom nil)]
        (fixture/keyed-append! [{:owner "a" :delta 1}])
        (with-redefs-fn
          {apply-var
           (fn [& args]
             (when (and (= 8 (count args)) (compare-and-set! once? true false))
               (fixture/keyed-append! [{:owner "a" :delta 2}])
               (reset! newer (es/head (:event-store ctx) (:tenant-id ctx)))
               (let [all-events (into [] (es/read (:event-store ctx)
                                      {:tenant-id (:tenant-id ctx) :types #{:keyed/change}}))
                     b (nth args 3) progress-key (#'core/keyed-progress-key b)]
                 (apply apply-batch (concat (take 6 args) [all-events]))
                 (kv/transact (:cache ctx) :write
                   (fn [tx]
                     (let [p (codec/decode ((:get tx) progress-key))]
                       ((:put tx) progress-key (codec/encode
                           (assoc p :checked-through @newer :other-field :preserved))))))))
             (apply apply-batch args))}
          #(is (= {:total 3} (rmp/value ctx model "a"))))
        (let [progress (rmp/progress ctx model)]
          (is (= @newer (:checked-through progress)))
          (is (= :preserved (:other-field progress)))
          (is (= {:total 3} (rmp/value ctx model "a"))))))))

(deftest routed-empty-tail-still-publishes-normal-progress
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (keyword "checkpoint-test" (str (random-uuid)))]
        (rmp/register-read-model! model merge
          {:events #{:keyed/change} :version 1 :route (constantly [])})
        (rmp/progress ctx model)
        (fixture/keyed-append! [{:owner "a" :delta 1}])
        (is (= {:value nil :writes 1} (counted-writes #(rmp/value ctx model "a"))))
        (let [progress (rmp/progress ctx model)]
          (is (some? (:watermark progress)))
          (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through progress))))))))
