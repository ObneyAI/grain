(ns ai.obney.grain.event-store-v2.read-batch-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(defschemas test-event-schemas
  {:test/a [:map]
   :test/b [:map]
   :test/x [:map]})

;; Test Fixtures

(def ^:dynamic *event-store* nil)

(defn event-store-fixture [f]
  (let [store (es/start {:conn {:type :in-memory}})]
    (binding [*event-store* store]
      (try
        (f)
        (finally
          (es/stop store))))))

(use-fixtures :each event-store-fixture)

;; Helpers

(defn append-event!
  [type tags & [body]]
  (let [event (es/->event (cond-> {:type type :tags tags}
                            body (assoc :body body)))]
    (es/append *event-store* {:events [event]})
    event))

(defn read-events
  [args]
  (into [] (es/read *event-store* args)))

(defn non-tx-events
  [events]
  (filterv #(not= :grain/tx (:event/type %)) events))

;; Tests

(deftest single-query-map-still-works
  (testing "Passing a map works exactly as before"
    (let [id (uuid/v4)
          _ (append-event! :test/a #{[:thing id]} {:val 1})
          _ (append-event! :test/b #{[:thing id]} {:val 2})
          events (non-tx-events (read-events {:types #{:test/a}}))]
      (is (= 1 (count events)))
      (is (= :test/a (:event/type (first events)))))))

(deftest vector-with-single-query
  (testing "A vector with one query returns same results as map form"
    (let [id (uuid/v4)
          _ (append-event! :test/a #{[:thing id]} {:val 1})
          _ (append-event! :test/b #{[:thing id]} {:val 2})
          map-result    (non-tx-events (read-events {:types #{:test/a}}))
          vector-result (non-tx-events (read-events [{:types #{:test/a}}]))]
      (is (= (count map-result) (count vector-result)))
      (is (= (mapv :event/id map-result)
             (mapv :event/id vector-result))))))

(deftest vector-with-multiple-queries-different-tags
  (testing "Batch read with different tags returns events from both"
    (let [id-a (uuid/v4)
          id-b (uuid/v4)
          evt-a (append-event! :test/x #{[:foo id-a]} {:from "a"})
          evt-b (append-event! :test/x #{[:bar id-b]} {:from "b"})
          events (non-tx-events
                  (read-events [{:tags #{[:foo id-a]}}
                                {:tags #{[:bar id-b]}}]))]
      (is (= 2 (count events)))
      (is (= #{(:event/id evt-a) (:event/id evt-b)}
             (set (map :event/id events)))))))

(deftest vector-with-multiple-queries-different-types
  (testing "Batch read with different types returns events of both types"
    (let [id (uuid/v4)
          evt-a (append-event! :test/a #{[:thing id]} {:val 1})
          evt-b (append-event! :test/b #{[:thing id]} {:val 2})
          events (non-tx-events
                  (read-events [{:types #{:test/a}}
                                {:types #{:test/b}}]))]
      (is (= 2 (count events)))
      (is (= #{:test/a :test/b}
             (set (map :event/type events)))))))

(deftest deduplicates-events-matching-multiple-queries
  (testing "An event matching multiple queries appears only once"
    (let [id-a (uuid/v4)
          id-b (uuid/v4)
          evt (append-event! :test/x #{[:foo id-a] [:bar id-b]} {:shared true})
          events (non-tx-events
                  (read-events [{:tags #{[:foo id-a]}}
                                {:tags #{[:bar id-b]}}]))]
      (is (= 1 (count events)))
      (is (= (:event/id evt) (:event/id (first events)))))))

(deftest preserves-ordering-by-event-id
  (testing "Batch read results are sorted by :event/id"
    (let [id-a (uuid/v4)
          id-b (uuid/v4)
          _evt1 (append-event! :test/a #{[:foo id-a]} {:order 1})
          _evt2 (append-event! :test/b #{[:bar id-b]} {:order 2})
          _evt3 (append-event! :test/a #{[:foo id-a]} {:order 3})
          events (non-tx-events
                  (read-events [{:tags #{[:foo id-a]}}
                                {:tags #{[:bar id-b]}}]))
          ids (mapv :event/id events)]
      (is (= ids (sort (fn [a b]
                         (cond (uuid/< a b) -1
                               (uuid/= a b) 0
                               :else 1))
                       ids))))))

(deftest per-query-after-filter
  (testing "Each query in a batch applies its own :after filter"
    (let [id-a (uuid/v4)
          id-b (uuid/v4)
          evt1 (append-event! :test/x #{[:foo id-a]} {:n 1})
          evt2 (append-event! :test/x #{[:foo id-a]} {:n 2})
          evt3 (append-event! :test/x #{[:bar id-b]} {:n 3})
          ;; Query 1: events tagged :foo, after evt1 (should get evt2 only)
          ;; Query 2: events tagged :bar, no after filter (should get evt3)
          events (non-tx-events
                  (read-events [{:tags #{[:foo id-a]} :after (:event/id evt1)}
                                {:tags #{[:bar id-b]}}]))]
      (is (= 2 (count events)))
      (is (= #{(:event/id evt2) (:event/id evt3)}
             (set (map :event/id events)))))))

(deftest empty-result
  (testing "Batch read with queries matching nothing returns empty reducible"
    (let [events (non-tx-events
                  (read-events [{:types #{:test/nonexistent}}
                                {:tags #{[:nope (uuid/v4)]}}]))]
      (is (empty? events)))))
