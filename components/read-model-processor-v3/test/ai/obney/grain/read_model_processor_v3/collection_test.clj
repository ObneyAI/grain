(ns ai.obney.grain.read-model-processor-v3.collection-test
  (:require [datahike.api :as d]
            [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.interface.testing :as ct]
            [ai.obney.grain.read-model-processor-v3.fixtures :as support]
            [ai.obney.grain.read-model-processor-v3.indexed-test :as fixture]))
(use-fixtures :each fixture/test-fixture)
(defn context [] fixture/*context*)
(defn register! [f & [opts]] (rmp/register-read-model! fixture/model f (merge fixture/options opts)))
(defn value [id] (get-in (rmp/record (context) fixture/model id) [:item :value]))

(deftest atomic-multi-record-handler-and-snapshot-discovery
  (let [observed (atom [])]
    (register!
     (fn [records e]
       (case (:id e)
         "merge" (let [next (-> records
                                (update-in ["a" :balance] + (get-in records ["b" :balance]))
                                (dissoc "b"))]
                   (is (= 3 (get-in next ["a" :balance])))
                   (is (nil? (get next "b"))) next)
         "all" (let [next (-> records
                              (assoc "new" {:surname "New" :status :active :balance 10})
                              (assoc-in ["a" :status] :inactive))]
                 ;; Traverse the immutable input, update a separate result.
                 (reduce-kv (fn [state id v]
                              (if (= :active (:status v))
                                (do (swap! observed conj [id (:status v)])
                                    (update-in state [id :balance] inc)) state)) next records))
         (assoc records (:id e) (:record e)))))
    (fixture/put! "a" "A" :active 1) (fixture/put! "b" "B" :active 2)
    (fixture/put! "merge" "ignored" :active 0) (fixture/put! "all" "ignored" :active 0)
    (is (= {:surname "A" :status :inactive :balance 4} (value "a")))
    (is (nil? (value "b"))) (is (= 10 (:balance (value "new"))))
    (is (= [["a" :active]] @observed))))

(deftest full-event-failure-rolls-back-even-if-operation-error-is-caught
  (fixture/put! "a" "A" :active 1) (value "a")
  (let [before (ct/committed (context) fixture/model)]
    (register! (fn [records _]
                 (let [next (-> records (assoc-in ["a" :surname] "Changed")
                                (assoc "b" {:surname "B" :status :active :balance 2}))]
                   (try (assoc next "invalid" nil) (catch Exception _ nil)) next)))
    (fixture/put! "bad" "Ignored" :active 0)
    (is (= :invalid-record-output (fixture/error-code #(value "a"))))
    (is (= before (ct/committed (context) fixture/model)))))

(deftest collections-without-indexes-and-scalar-records
  (register! (fn [records e] (update records (:id e) (fnil inc 0)))
             {:version 2 :schema [:map-of :string :int] :indexes {}})
  (fixture/put! "b" "ignored" :active 0) (fixture/put! "a" "ignored" :active 0)
  (fixture/put! "a" "ignored" :active 0)
  (is (= 2 (value "a")))
  (is (= [{:id "a" :value 2} {:id "b" :value 1}]
         (rmp/reduce-records (context) fixture/model {} conj [])))
  (is (= :unknown-index (fixture/error-code #(fixture/page))))
  (is (= {"a" 2 "b" 1} (rmp/project (context) fixture/model))))

(deftest capability-thread-lifetime-and-selected-return-value
  (let [escaped (atom nil) lazy-escaped (atom nil)]
    (register! (fn [records e]
                 (reset! escaped records) (reset! lazy-escaped (map val records))
                 (is (= :expired-reduction-map @(future (fixture/error-code #(get records "a")))))
                 (let [abandoned (assoc records "ignored" {:surname "No" :status :active :balance 42})]
                   (is (contains? abandoned "ignored")))
                 (assoc records (:id e) (:record e))))
    (fixture/put! "a" "A" :active 1) (fixture/put! "b" "B" :active 2)
    (is (= 1 (:balance (value "a")))) (is (nil? (value "ignored")))
    (is (= :expired-reduction-map (fixture/error-code #(get @escaped "a"))))
    (is (= :expired-reduction-map (fixture/error-code #(doall @lazy-escaped))))
    (is (= :committed-projection-read-only
           (fixture/error-code #(assoc (rmp/project (context) fixture/model) "c" {}))))))

(deftest unified-model-and-backend-errors-preserve-registration
  (let [before (get @rmp/read-model-registry* fixture/model)]
    (doseq [opts [(assoc fixture/options :kind :collection) (assoc fixture/options :kind :value)
                  (assoc fixture/options :l1-ttl-ms 10)]]
      (is (= :invalid-indexed-definition (fixture/error-code #(register! identity opts))))
      (is (= before (get @rmp/read-model-registry* fixture/model)))))
  (is (= :missing-projection-store
         (fixture/error-code #(rmp/record (dissoc (context) :projection-store) fixture/model "a"))))
  (rmp/close-store! (:projection-store (context)))
  (is (= :invalid-projection-store (fixture/error-code #(value "a")))))

(deftest model-isolation-and-detail-projections-share-one-store
  (rmp/register-read-model! :collection-test/detail (fn [state _] (update state :count (fnil inc 0)))
                            {:events (:events fixture/options) :version 1})
  (rmp/register-read-model! :collection-test/other
                            (fn [records e] (assoc records (:id e) (assoc (:record e) :balance 99))) fixture/options)
  (fixture/put! "a" "A" :active 1)
  (is (= {:count 1} (rmp/project (context) :collection-test/detail)))
  (is (= 1 (:balance (value "a"))))
  (is (= 99 (get-in (rmp/record (context) :collection-test/other "a") [:item :value :balance])))
  (is (= {:count 1} (rmp/project (context) :collection-test/detail))))

(deftest full-prefix-pages-use-id-and-native-string-order
  (doseq [[id name] [["a" "a\u0000b"] ["b" "a\u0000b"] ["c" "😀"] ["d" "\ue000"]]]
    (fixture/put! id name :active 1))
  (let [p (fixture/page {:prefix [:active "a\u0000b"] :limit 1})]
    (is (= ["a"] (fixture/ids p)))
    (is (= ["b"] (fixture/ids (fixture/page {:prefix [:active "a\u0000b"] :limit 1 :after (:next-cursor p)})))))
  (is (= ["a" "b" "c" "d"] (fixture/ids (fixture/page {:limit 10})))) )

(deftest manual-collection-reports-failure-and-clears-it-on-success
  (let [store (:projection-store (context))
        failure (ex-info "Collection failed" {:test true})]
    (with-redefs [d/gc-storage (fn [& _] (throw failure))]
      (is (identical? failure (try (rmp/collect! store) nil
                                  (catch Throwable e e))))
      (is (identical? failure (:last-error (rmp/store-status store)))))
    (rmp/collect! store)
    (is (nil? (:last-error (rmp/store-status store))))))
