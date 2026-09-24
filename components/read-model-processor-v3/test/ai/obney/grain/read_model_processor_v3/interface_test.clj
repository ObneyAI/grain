(ns ai.obney.grain.read-model-processor-v3.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.fixtures :as fixture]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.set :as set]
            [ai.obney.grain.read-model-processor-v3.core :as core])
  (:import [java.time OffsetDateTime]))
(def ^:dynamic *event-store* nil)
(declare test-tenant-id)
(defn make-context [] (assoc fixture/*context* :tenant-id test-tenant-id))
(use-fixtures :each (fn [f] (fixture/fixture #(binding [*event-store* (:event-store fixture/*context*)] (f)))))
(def test-tenant-id (random-uuid))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------


(defschemas test-schemas
  {:test/counter-incremented [:map]
   :test/other-event [:map]})

;; ---------------------------------------------------------------------------
;; Dynamic vars & fixture
;; ---------------------------------------------------------------------------


(defn counter-reducer [state _event]
  (update state :count (fnil inc 0)))


(defn make-args
  ([] (make-args {}))
  ([overrides]
   (merge {:f       counter-reducer
           :query   {:types #{:test/counter-incremented}}
           :name    :test/counter
           :version 1
           }
          overrides)))


(defn append-test-events!
  ([n] (append-test-events! n :test/counter-incremented))
  ([n event-type]
   (let [events (mapv (fn [_] (es/->event {:type event-type}))
                      (range n))]
     (es/append *event-store* {:tenant-id test-tenant-id :events events}))))


(defn append-tagged-events!
  [n event-type tags]
  (let [events (mapv (fn [_] (es/->event {:type event-type :tags tags})) (range n))]
    (es/append *event-store* {:tenant-id test-tenant-id :events events})))


(defn read-cached
  ([name version] (read-cached name version test-tenant-id))
  ([name version tenant]
  (let [ctx (assoc (make-context) :tenant-id tenant)
        args (make-args {:name name :version version})
        definition (#'ai.obney.grain.read-model-processor-v3.interface/registration name (:f args) (dissoc args :f :name))
        state (core/snapshot ctx name definition nil)]
    {:data (into {} (#'core/projection-value state)) :watermark (:watermark state)})))
(deftest empty-event-store-returns-empty-state
  (is (= {} (rmp/p (make-context) (make-args)))))


(deftest cache-miss-processes-all-events
  (append-test-events! 5)
  (is (= {:count 5} (rmp/p (make-context) (make-args)))))


(deftest cache-miss-populates-cache-for-subsequent-hit
  (append-test-events! 5)
  (let [first-result  (rmp/p (make-context) (make-args))
        second-result (rmp/p (make-context) (make-args))]
    (is (= {:count 5} first-result))
    (is (= {:count 5} second-result))))


(deftest fressian-round-trip-cache-shape
  (append-test-events! 5)
  (let [events (into [] (es/read *event-store* {:tenant-id test-tenant-id :types #{:test/counter-incremented}}))
        last-id (:event/id (last events))]
    (rmp/p (make-context) (make-args))
    (let [cached (read-cached :test/counter 1)]
      (is (= #{:data :watermark} (set (keys cached))))
      (is (= {:count 5} (:data cached)))
      (is (= last-id (:watermark cached))))))


(deftest fressian-round-trip-after-threshold-update
  (append-test-events! 3)
  (rmp/p (make-context) (make-args))
  (append-test-events! 10)
  (rmp/p (make-context) (make-args))
  (let [events (into [] (es/read *event-store* {:tenant-id test-tenant-id :types #{:test/counter-incremented}}))
        last-id (:event/id (last events))
        cached  (read-cached :test/counter 1)]
    (is (= #{:data :watermark} (set (keys cached))))
    (is (= {:count 13} (:data cached)))
    (is (= last-id (:watermark cached)))))


(deftest cache-hit-under-threshold-no-cache-update
  (let [call-count (atom 0)
        counting-reducer (fn [state event]
                           (swap! call-count inc)
                           (counter-reducer state event))]
    (append-test-events! 3)
    (rmp/p (make-context) (make-args {:f counting-reducer}))
    (reset! call-count 0)

    (append-test-events! 5)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 8} result))
      (is (= 5 @call-count)))

    ;; Third call: L1 has the updated state from second call.
    ;; With L1 and no new events, reducer is NOT called — L1 returns cached state.
    (reset! call-count 0)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 8} result))
      (is (= 0 @call-count)))))


(deftest cache-hit-at-threshold-updates-cache
  (let [call-count (atom 0)
        counting-reducer (fn [state event]
                           (swap! call-count inc)
                           (counter-reducer state event))]
    (append-test-events! 3)
    (rmp/p (make-context) (make-args {:f counting-reducer}))
    (reset! call-count 0)

    (append-test-events! 10)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 13} result))
      (is (= 10 @call-count)))

    (reset! call-count 0)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 13} result))
      (is (= 0 @call-count)))))

;; ---------------------------------------------------------------------------
;; C. State Accumulation
;; ---------------------------------------------------------------------------


(deftest state-accumulates-across-incremental-calls
  (append-test-events! 3)
  (is (= {:count 3} (rmp/p (make-context) (make-args))))

  (append-test-events! 4)
  (is (= {:count 7} (rmp/p (make-context) (make-args))))

  (append-test-events! 12)
  (is (= {:count 19} (rmp/p (make-context) (make-args))))

  (append-test-events! 2)
  (is (= {:count 21} (rmp/p (make-context) (make-args)))))

;; ---------------------------------------------------------------------------
;; D. Isolation & Filtering
;; ---------------------------------------------------------------------------


(deftest separate-cache-per-name-version
  (append-test-events! 5)
  (let [result-a (rmp/p (make-context) (make-args {:name :test/counter-a :version 1}))
        result-b (rmp/p (make-context) (make-args {:name :test/counter-b :version 1}))
        result-v2 (rmp/p (make-context) (make-args {:name :test/counter-a :version 2}))]
    (is (= {:count 5} result-a))
    (is (= {:count 5} result-b))
    (is (= {:count 5} result-v2))))


(deftest query-filters-events-correctly
  (append-test-events! 5 :test/counter-incremented)
  (append-test-events! 3 :test/other-event)
  (is (= {:count 5}
         (rmp/p (make-context)
                (make-args {:query {:types #{:test/counter-incremented}}})))))

;; ---------------------------------------------------------------------------
;; E. Reducer Contract
;; ---------------------------------------------------------------------------


(deftest reducer-receives-full-events
  (let [captured (atom [])
        capturing-reducer (fn [state event]
                            (swap! captured conj event)
                            (update state :count (fnil inc 0)))]
    (append-test-events! 3)
    (rmp/p (make-context) (make-args {:f capturing-reducer}))

    (is (= 3 (count @captured)))
    (is (every? #(= :test/counter-incremented (:event/type %)) @captured))
    (is (every? :event/id @captured))
    (is (every? :event/timestamp @captured))))

;; ---------------------------------------------------------------------------
;; F. defreadmodel Macro & Registry
;; ---------------------------------------------------------------------------


(defn counter-reducer-multi [state event]
  (case (:event/type event)
    :test/counter-incremented (update state :count (fnil inc 0))
    state))


(deftest defreadmodel-creates-function-and-registers
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/defreadmodel :test counter-rm
        {:events #{:test/counter-incremented}
         :version 2
         :schema [:map [:count :int]]}
        "Counts increment events in the macro registration test."
        [state event]
        (counter-reducer-multi state event))

      (testing "function is created and callable"
        (is (fn? test-counter-rm))
        (is (= {:count 1}
               (test-counter-rm {} {:event/type :test/counter-incremented}))))

      (testing "registry entry exists with correct keys"
        (let [entry (get @rmp/read-model-registry* :test/counter-rm)]
          (is (some? entry))
          (is (ifn? (:reducer-fn entry)))
          (is (= #{:test/counter-incremented} (:events entry)))
          (is (= 2 (:version entry)))
          (is (= [:map [:count :int]] (:schema entry)))))

      (testing "registry function matches the defn"
        (let [entry (get @rmp/read-model-registry* :test/counter-rm)]
          (is (= {:count 1}
                 ((:reducer-fn entry) {} {:event/type :test/counter-incremented})))))

      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest read-model-schema-is-validated-at-registration
  (let [previous @rmp/read-model-registry*]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Supply a valid state schema"
           (rmp/register-read-model! :test/invalid-schema identity
                                     {:schema [:map [:count :not-a-schema]]})))
      (is (not (contains? @rmp/read-model-registry* :test/invalid-schema)))
      (finally
        (reset! rmp/read-model-registry* previous)))))


(deftest defreadmodel-with-docstring
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/defreadmodel :test documented-rm
        {:events #{:test/counter-incremented}
         :version 1
         :schema [:map [:count :int]]}
        "A documented read model."
        [state event]
        (update state :count (fnil inc 0)))

      (testing "docstring is attached to the var"
        (is (= "A documented read model." (:doc (meta #'test-documented-rm)))))

      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest defreadmodel-without-opts
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/defreadmodel :test bare-rm
        [state _event]
        (update state :count (fnil inc 0)))

      (testing "works without opts map"
        (is (fn? test-bare-rm))
        (is (= {:count 1} (test-bare-rm {} {}))))

      (testing "registry entry exists with empty opts"
        (let [entry (get @rmp/read-model-registry* :test/bare-rm)]
          (is (some? entry))
          (is (ifn? (:reducer-fn entry)))
          (is (nil? (:events entry)))
          (is (nil? (:version entry)))))

      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest global-read-model-registry-returns-snapshot
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/dummy identity {:events #{:test/e1} :version 1})
      (let [reg (rmp/global-read-model-registry)]
        (is (map? reg))
        (is (contains? reg :test/dummy)))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; ---------------------------------------------------------------------------
;; G. Scoped Projections
;; ---------------------------------------------------------------------------


(deftest cross-tenant-cache-isolation
  (let [tenant-a (random-uuid)
        tenant-b (random-uuid)
        ctx-a    (assoc (make-context) :tenant-id tenant-a)
        ctx-b    (assoc (make-context) :tenant-id tenant-b)]
    ;; Append different event counts to each tenant
    (es/append *event-store* {:tenant-id tenant-a
                              :events (mapv (fn [_] (es/->event {:type :test/counter-incremented}))
                                            (range 3))})
    (es/append *event-store* {:tenant-id tenant-b
                              :events (mapv (fn [_] (es/->event {:type :test/counter-incremented}))
                                            (range 7))})
    (let [result-a (rmp/p ctx-a (make-args))
          result-b (rmp/p ctx-b (make-args))]
      (is (= {:count 3} result-a))
      (is (= {:count 7} result-b)))

    ;; Verify cached values are also isolated
    (let [cached-a (read-cached :test/counter 1 tenant-a)
          cached-b (read-cached :test/counter 1 tenant-b)]
      (is (= {:count 3} (:data cached-a)))
      (is (= {:count 7} (:data cached-b))))))


(deftest cross-tenant-scoped-cache-isolation
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/counter counter-reducer
                                {:events #{:test/counter-incremented} :version 1})
      (let [tenant-a (random-uuid)
            tenant-b (random-uuid)
            org-id   (random-uuid)
            ctx-a    (assoc (make-context) :tenant-id tenant-a)
            ctx-b    (assoc (make-context) :tenant-id tenant-b)]
        ;; Same org-id tag, different tenants
        (es/append *event-store* {:tenant-id tenant-a
                                  :events (mapv (fn [_] (es/->event {:type :test/counter-incremented
                                                                     :tags #{[:org org-id]}}))
                                                (range 2))})
        (es/append *event-store* {:tenant-id tenant-b
                                  :events (mapv (fn [_] (es/->event {:type :test/counter-incremented
                                                                     :tags #{[:org org-id]}}))
                                                (range 6))})
        (let [result-a (rmp/project ctx-a :test/counter {:tags #{[:org org-id]}})
              result-b (rmp/project ctx-b :test/counter {:tags #{[:org org-id]}})]
          (is (= {:count 2} result-a))
          (is (= {:count 6} result-b))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest scoped-projection-with-tags
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/counter counter-reducer
                                {:events #{:test/counter-incremented} :version 1})
      (let [org-a (random-uuid)
            org-b (random-uuid)]
        (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
        (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
        (let [result-a (rmp/project (make-context) :test/counter {:tags #{[:org org-a]}})
              result-b (rmp/project (make-context) :test/counter {:tags #{[:org org-b]}})]
          (is (= {:count 3} result-a))
          (is (= {:count 5} result-b))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest cache-isolation-between-scopes
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/counter counter-reducer
                                {:events #{:test/counter-incremented} :version 1})
      (let [org-a (random-uuid)
            org-b (random-uuid)]
        (append-test-events! 4)
        (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
        (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
        (let [unscoped (rmp/project (make-context) :test/counter)
              scoped-a (rmp/project (make-context) :test/counter {:tags #{[:org org-a]}})
              scoped-b (rmp/project (make-context) :test/counter {:tags #{[:org org-b]}})]
          (is (= {:count 12} unscoped))
          (is (= {:count 3} scoped-a))
          (is (= {:count 5} scoped-b))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest scoped-projection-with-custom-queries
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/counter counter-reducer
                                {:events #{:test/counter-incremented} :version 1})
      (let [org-a (random-uuid)
            org-b (random-uuid)]
        (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
        (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
        (let [result (rmp/project (make-context) :test/counter
                                  {:queries [{:types #{:test/counter-incremented}
                                              :tags #{[:org org-a]}}
                                             {:types #{:test/counter-incremented}
                                              :tags #{[:org org-b]}}]})]
          (is (= {:count 8} result))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))


(deftest vector-query-cache-hit
  (let [org-a (random-uuid)
        org-b (random-uuid)
        query [{:types #{:test/counter-incremented} :tags #{[:org org-a]}}
               {:types #{:test/counter-incremented} :tags #{[:org org-b]}}]
        scope {:queries query}
        args  {:f       counter-reducer
               :query   query
               :name    "test-vector"
               :version 1
               :scope   scope}]
    (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
    (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
    (let [first-result (rmp/p (make-context) args)]
      (is (= {:count 8} first-result)))
    (append-tagged-events! 2 :test/counter-incremented #{[:org org-a]})
    (let [second-result (rmp/p (make-context) args)]
      (is (= {:count 10} second-result)))))

;; ---------------------------------------------------------------------------
;; H. Segmented Cache
;; ---------------------------------------------------------------------------


(defschemas entity-schemas
  {:test/entity-created [:map [:entity-id :uuid]]})


(defn map-reducer
  "Reducer that accumulates a map of {entity-id -> entity-data}."
  [state event]
  (let [eid (:entity-id event)]
    (assoc state eid {:id eid :seq (count state)})))


(defn append-entity-events!
  "Append n events each with a unique :entity-id."
  [n]
  (let [events (mapv (fn [_]
                       (es/->event {:type :test/entity-created
                                    :body {:entity-id (random-uuid)}}))
                     (range n))]
    (es/append *event-store* {:tenant-id test-tenant-id :events events})))


(defn append-entity-events-to-tenant!
  "Append n events with unique entity-ids to a specific tenant."
  [tenant-id n]
  (let [events (mapv (fn [_]
                       (es/->event {:type :test/entity-created
                                    :body {:entity-id (random-uuid)}}))
                     (range n))]
    (es/append *event-store* {:tenant-id tenant-id :events events})))


(defn make-entity-args
  ([] (make-entity-args {}))
  ([overrides]
   (merge {:f       map-reducer
           :query   {:types #{:test/entity-created}}
           :name    "test-entities"
           :version 1
           }
          overrides)))


(deftest map-state-with-scope
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/entities map-reducer
                                {:events #{:test/entity-created} :version 1})
      (let [org-a (random-uuid)
            org-b (random-uuid)]
        ;; 15K tagged events for each org
        (let [events-a (mapv (fn [_] (es/->event {:type :test/entity-created
                                                   :tags #{[:org org-a]}
                                                   :body {:entity-id (random-uuid)}}))
                             (range 50))
              events-b (mapv (fn [_] (es/->event {:type :test/entity-created
                                                   :tags #{[:org org-b]}
                                                   :body {:entity-id (random-uuid)}}))
                             (range 50))]
          (es/append *event-store* {:tenant-id test-tenant-id :events events-a})
          (es/append *event-store* {:tenant-id test-tenant-id :events events-b}))

        (let [result-a (rmp/project (make-context) :test/entities {:tags #{[:org org-a]}})
              result-b (rmp/project (make-context) :test/entities {:tags #{[:org org-b]}})]
          ;; Each scope gets its own 15K entries
          (is (= 50 (count result-a)))
          (is (= 50 (count result-b)))
          ;; Data is isolated — no overlap
          (is (empty? (clojure.set/intersection (set (keys result-a))
                                                (set (keys result-b)))))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; H7

(deftest map-state-cross-tenant-isolation
  (let [tenant-a (random-uuid)
        tenant-b (random-uuid)
        ctx-a (assoc (make-context) :tenant-id tenant-a)
        ctx-b (assoc (make-context) :tenant-id tenant-b)]
    (append-entity-events-to-tenant! tenant-a 50)
    (append-entity-events-to-tenant! tenant-b 50)
    (let [result-a (rmp/p ctx-a (make-entity-args))
          result-b (rmp/p ctx-b (make-entity-args))]
      ;; Each tenant gets 15K entries
      (is (= 50 (count result-a)))
      (is (= 50 (count result-b)))
      ;; Completely isolated — no shared keys
      (is (empty? (set/intersection (set (keys result-a))
                                    (set (keys result-b))))))))

;; ---------------------------------------------------------------------------
;; I. Partitioned Projections
;; ---------------------------------------------------------------------------


(defschemas partition-schemas
  {:test/item-created [:map [:item-id :uuid] [:bucket :string]]
   :test/item-updated [:map [:item-id :uuid] [:bucket :string] [:value :int]]
   :test/item-moved   [:map [:item-id :uuid] [:old-bucket :string] [:new-bucket :string]]
   ;; For unsafe-reducer partitioned tests
   :test/order-created   [:map [:order-id :uuid] [:location :string]]
   :test/payment-applied [:map [:order-id :uuid] [:amount :int]]})


(defn item-reducer
  "Reducer for partitioned tests. Handles create, update, and cross-partition move.
   No partition awareness — just updates entity state normally."
  [state event]
  (case (:event/type event)
    :test/item-created
    (assoc state (:item-id event) {:id (:item-id event) :bucket (:bucket event) :value 0})

    :test/item-updated
    (update state (:item-id event) assoc :value (:value event))

    :test/item-moved
    (update state (:item-id event) assoc :bucket (:new-bucket event))

    state))


(def item-partition-fn
  "Partition by bucket field on entity state."
  (fn [entity]
    (:bucket entity)))


(def item-entity-id-fn :item-id)


(defn append-item-events!
  "Append n item-created events with the given bucket."
  ([n bucket] (append-item-events! test-tenant-id n bucket))
  ([tid n bucket]
   (let [events (mapv (fn [_]
                        (es/->event {:type :test/item-created
                                     :body {:item-id (random-uuid)
                                            :bucket bucket}}))
                      (range n))]
     (es/append *event-store* {:tenant-id tid :events events})
     ;; Return the item-ids for reference
     (mapv :item-id events))))


(defn make-partitioned-args
  ([] (make-partitioned-args {}))
  ([overrides]
   (merge {:f             item-reducer
           :query         {:types #{:test/item-created :test/item-updated :test/item-moved}}
           :name          "test-items"
           :version       1
           :partition-fn  item-partition-fn
           :entity-id-fn item-entity-id-fn
           }
          overrides)))

;; I1

(deftest partitioned-single-partition-read
  (testing "reading a single partition returns only that partition's data"
    (append-item-events! 50 "a")
    (append-item-events! 50 "b")
    ;; Full project to populate cache
    (rmp/p (make-context) (make-partitioned-args))
    ;; Single partition reads
    (let [result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))
          result-b (rmp/p (make-context) (make-partitioned-args {:partition-key "b"}))]
      (is (= 50 (count result-a)))
      (is (= 50 (count result-b)))
      (is (every? #(= "a" (:bucket %)) (vals result-a)))
      (is (every? #(= "b" (:bucket %)) (vals result-b)))
      ;; No cross-contamination
      (is (empty? (set/intersection (set (keys result-a)) (set (keys result-b))))))))

;; I2

(deftest partitioned-full-merge
  (testing "project without partition-key returns all data merged"
    (append-item-events! 50 "a")
    (append-item-events! 50 "b")
    (let [result (rmp/p (make-context) (make-partitioned-args))]
      (is (= 100 (count result)))
      (is (= 50 (count (filter #(= "a" (:bucket %)) (vals result)))))
      (is (= 50 (count (filter #(= "b" (:bucket %)) (vals result))))))))

;; I3

(deftest partitioned-incremental-single-partition
  (testing "adding events to one partition doesn't affect others"
    (append-item-events! 50 "a")
    (append-item-events! 50 "b")
    (rmp/p (make-context) (make-partitioned-args))

    ;; Add 10 more to "a"
    (append-item-events! 10 "a")
    ;; Project just "a"
    (let [result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))]
      (is (= 60 (count result-a))))
    ;; "b" unchanged
    (let [result-b (rmp/p (make-context) (make-partitioned-args {:partition-key "b"}))]
      (is (= 50 (count result-b))))))

;; I5

(deftest partitioned-cross-partition-move
  (testing "entity moves between partitions via auto-index"
    (let [ids-a (append-item-events! 10 "a")]
      (rmp/p (make-context) (make-partitioned-args))

      ;; Move first item from "a" to "b"
      (let [moved-id (first ids-a)]
        (es/append *event-store*
                   {:tenant-id test-tenant-id
                    :events [(es/->event {:type :test/item-moved
                                          :body {:item-id moved-id
                                                 :old-bucket "a"
                                                 :new-bucket "b"}})]})
        ;; Full project to process the move
        (rmp/p (make-context) (make-partitioned-args))

        (let [result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))
              result-b (rmp/p (make-context) (make-partitioned-args {:partition-key "b"}))]
          (is (= 9 (count result-a)))
          (is (= 1 (count result-b)))
          (is (not (contains? result-a moved-id)))
          (is (contains? result-b moved-id)))

))))

;; I6

(deftest partitioned-update-within-partition
  (testing "updating an entity within same partition works correctly"
    (let [ids-a (append-item-events! 10 "a")]
      (rmp/p (make-context) (make-partitioned-args))

      ;; Update first item's value (stays in bucket "a")
      (let [updated-id (first ids-a)]
        (es/append *event-store*
                   {:tenant-id test-tenant-id
                    :events [(es/->event {:type :test/item-updated
                                          :body {:item-id updated-id
                                                 :bucket "a"
                                                 :value 42}})]})
        (rmp/p (make-context) (make-partitioned-args))

        (let [result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))]
          (is (= 10 (count result-a)))
          (is (= 42 (:value (get result-a updated-id)))))))))

;; I7

(deftest partitioned-without-entity-id-fn
  (testing "partitioning works without entity-id-fn"
    (append-item-events! 30 "a")
    (append-item-events! 20 "b")
    (let [args (make-partitioned-args {:entity-id-fn nil})
          result (rmp/p (make-context) args)]
      (is (= 50 (count result))))))

;; I8

(deftest partitioned-with-scope
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/partitioned-items item-reducer
                                {:events #{:test/item-created :test/item-updated :test/item-moved}
                                 :version 1
                                 :partition-fn item-partition-fn
                                 :entity-id-fn item-entity-id-fn})
      (let [org-a (random-uuid)
            org-b (random-uuid)]
        ;; Tagged events for org-a
        (es/append *event-store*
                   {:tenant-id test-tenant-id
                    :events (mapv (fn [_] (es/->event {:type :test/item-created
                                                       :tags #{[:org org-a]}
                                                       :body {:item-id (random-uuid) :bucket "x"}}))
                                 (range 30))})
        ;; Tagged events for org-b
        (es/append *event-store*
                   {:tenant-id test-tenant-id
                    :events (mapv (fn [_] (es/->event {:type :test/item-created
                                                       :tags #{[:org org-b]}
                                                       :body {:item-id (random-uuid) :bucket "x"}}))
                                 (range 20))})

        ;; Scoped + partitioned read
        (let [result-a (rmp/project (make-context) :test/partitioned-items
                                    {:tags #{[:org org-a]} :partition-key "x"})
              result-b (rmp/project (make-context) :test/partitioned-items
                                    {:tags #{[:org org-b]} :partition-key "x"})]
          (is (= 30 (count result-a)))
          (is (= 20 (count result-b)))
          (is (empty? (set/intersection (set (keys result-a)) (set (keys result-b)))))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; I9

(deftest partitioned-cross-tenant-isolation
  (let [tenant-a (random-uuid)
        tenant-b (random-uuid)
        ctx-a (assoc (make-context) :tenant-id tenant-a)
        ctx-b (assoc (make-context) :tenant-id tenant-b)]
    (let [events-a (mapv (fn [_] (es/->event {:type :test/item-created
                                               :body {:item-id (random-uuid) :bucket "x"}}))
                         (range 40))
          events-b (mapv (fn [_] (es/->event {:type :test/item-created
                                               :body {:item-id (random-uuid) :bucket "x"}}))
                         (range 25))]
      (es/append *event-store* {:tenant-id tenant-a :events events-a})
      (es/append *event-store* {:tenant-id tenant-b :events events-b}))

    (let [result-a (rmp/p ctx-a (make-partitioned-args {:partition-key "x"}))
          result-b (rmp/p ctx-b (make-partitioned-args {:partition-key "x"}))]
      (is (= 40 (count result-a)))
      (is (= 25 (count result-b)))
      (is (empty? (set/intersection (set (keys result-a)) (set (keys result-b))))))))

;; I10

(deftest partitioned-cross-partition-move-via-single-partition-read
  (testing "single-partition read detects move and writes eviction without full projection"
    (let [ids-a (append-item-events! 10 "a")]
      ;; Full projection to populate cache
      (rmp/p (make-context) (make-partitioned-args))

      ;; Move first item from "a" to "b" — only 1 event, below write-back threshold
      (let [moved-id (first ids-a)]
        (es/append *event-store*
                   {:tenant-id test-tenant-id
                    :events [(es/->event {:type :test/item-moved
                                          :body {:item-id moved-id
                                                 :old-bucket "a"
                                                 :new-bucket "b"}})]})

        ;; Single-partition read of "a" — should evict and write to "b"
        (let [result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))]
          (is (= 9 (count result-a)))
          (is (not (contains? result-a moved-id))))

        ;; Single-partition read of "b" — should find the moved entity
        (let [result-b (rmp/p (make-context) (make-partitioned-args {:partition-key "b"}))]
          (is (contains? result-b moved-id))
          (is (= "b" (:bucket (get result-b moved-id)))))))))

;; Shared setup for compound partition tests (simulates appointment reschedule scenario).
;; partition-fn depends on [category bucket] but move events only update bucket.

(def compound-reducer
  (fn [state event]
    (case (:event/type event)
      :test/item-created
      (assoc state (:item-id event)
             {:id (:item-id event)
              :category (:category event)
              :bucket (:bucket event)})
      :test/item-moved
      (update state (:item-id event) assoc :bucket (:new-bucket event))
      state)))


(def compound-partition-fn (fn [entity] [(:category entity) (:bucket entity)]))


(defn make-compound-args [overrides]
  (merge {:f compound-reducer
          :query {:types #{:test/item-created :test/item-moved}}
          :name "compound-items"
          :version 1
          :partition-fn compound-partition-fn
          :entity-id-fn :item-id
          }
         overrides))


(defn create-compound-item! [category bucket]
  (let [id (random-uuid)]
    (es/append *event-store*
               {:tenant-id test-tenant-id
                :events [(es/->event {:type :test/item-created
                                      :body {:item-id id :category category :bucket bucket}})]})
    id))


(defn move-compound-item! [item-id new-bucket]
  (es/append *event-store*
             {:tenant-id test-tenant-id
              :events [(es/->event {:type :test/item-moved
                                    :body {:item-id item-id
                                           :old-bucket "ignored"
                                           :new-bucket new-bucket}})]}))


(deftest partitioned-cross-partition-move-destination-first
  (testing "reading destination partition first detects move without reading source"
    (let [item-id (create-compound-item! "east" "a")]
      (rmp/p (make-context) (make-compound-args {}))
      (move-compound-item! item-id "b")

      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
        (is (contains? result item-id) "Entity should appear in destination partition")
        (is (= "b" (:bucket (get result item-id)))))

      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "a"]}))]
        (is (not (contains? result item-id)) "Entity should be removed from source partition")))))


(deftest partitioned-snapshot-syncs-after-cross-partition-move
  (testing "L1 populated with empty state syncs with L2 after entity moves in"
    ;; Simulates: double-booking check populates L1 for destination with empty state,
    ;; then entity moves in via another partition's processing, next read must see it.
    (let [item-id (create-compound-item! "east" "a")]
      ;; Full projection → L1+L2 populated
      (rmp/p (make-context) (make-compound-args {}))
      ;; Read destination partition → L1 populated with empty state for ["east" "b"]
      (let [empty-result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
        (is (empty? empty-result)))
      ;; Move entity from "a" to "b"
      (move-compound-item! item-id "b")
      ;; Read destination again → L1-stale must detect entity moved in
      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
        (is (= 1 (count result)) "Entity should appear in destination after L1-stale sync")
        (is (= "b" (:bucket (get result item-id)))))
      ;; Source should be empty
      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "a"]}))]
        (is (not (contains? result item-id)) "Entity should be gone from source")))))


(deftest partitioned-snapshot-removes-entity-after-move-out
  (testing "L1 with entity reflects removal after entity moves to another partition"
    (let [item-id (create-compound-item! "east" "a")]
      (rmp/p (make-context) (make-compound-args {}))
      ;; Warm L1 for source — has 1 entity
      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "a"]}))]
        (is (= 1 (count result))))
      ;; Move entity
      (move-compound-item! item-id "b")
      ;; Read DESTINATION first
      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
        (is (contains? result item-id) "Entity should be in destination"))
      ;; Read SOURCE — L1-stale should see entity gone
      (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "a"]}))]
        (is (empty? result) "Source partition should be empty after move out")))))


(deftest partitioned-snapshot-consistent-after-multiple-reads
  (testing "repeated reads after cross-partition move are consistent"
    (let [item-id (create-compound-item! "east" "a")]
      (rmp/p (make-context) (make-compound-args {}))
      ;; Populate L1 for destination with empty state
      (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))
      ;; Move entity
      (move-compound-item! item-id "b")
      ;; Three consecutive reads — all must return the entity
      (dotimes [_ 3]
        (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
          (is (= 1 (count result)) "Entity must be present on every read")
          (is (= "b" (:bucket (get result item-id)))))))))

;; I12

(defn unsafe-order-reducer
  "A reducer that does NOT guard against missing entity state.
   This is realistic — developers naturally write reducers assuming
   creation events come before update events."
  [state event]
  (case (:event/type event)
    :test/order-created
    (assoc state (:order-id event)
           {:id (:order-id event)
            :location (:location event)
            :total 0})

    :test/payment-applied
    (let [order (get state (:order-id event))]
      ;; UNSAFE: accesses :total on potentially nil order
      (assoc-in state [(:order-id event) :total]
                (+ (:total order) (:amount event))))

    state))


(defn make-order-partitioned-args
  ([] (make-order-partitioned-args {}))
  ([overrides]
   (merge {:f             unsafe-order-reducer
           :query         {:types #{:test/order-created :test/payment-applied}}
           :name          "test-orders"
           :version       1
           :partition-fn  :location
           :entity-id-fn :order-id
           }
          overrides)))

;; I-unsafe-1

(deftest partitioned-unsafe-reducer-relevance-filter
  (testing "non-creation event for new entity in relevance filter does not crash"
    ;; Seed an initial order to populate L1 for partition "east"
    (let [seed-id (random-uuid)]
      (es/append *event-store*
        {:tenant-id test-tenant-id
         :events [(es/->event {:type :test/order-created
                               :body {:order-id seed-id :location "east"}})]})
      ;; Full project → L2, then single-partition read → L1 for "east"
      (rmp/p (make-context) (make-order-partitioned-args))
      (rmp/p (make-context) (make-order-partitioned-args {:partition-key "east"}))
      ;; Now append a NEW order (create + payment) in a single batch.
      ;; L1 for "east" exists but is expired (TTL=0).
      ;; The relevance filter iterates all-events since watermark.
      ;; For payment-applied: eid is new-order-id, NOT in L1 state.
      ;; Filter calls (f {} payment-applied-event) → unsafe reducer NPEs
      ;; on (:total nil).
      (let [new-id (random-uuid)]
        (es/append *event-store*
          {:tenant-id test-tenant-id
           :events [(es/->event {:type :test/order-created
                                 :body {:order-id new-id :location "east"}})
                    (es/->event {:type :test/payment-applied
                                 :body {:order-id new-id :amount 4000}})]})
        (let [result (rmp/p (make-context)
                            (make-order-partitioned-args {:partition-key "east"}))]
          (is (= 2 (count result)))
          (is (= 4000 (:total (get result new-id)))))))))

;; I-unsafe-2

(deftest partitioned-unsafe-reducer-new-entity-in-batch
  (testing "new entity creation + update in same batch does not crash with unsafe reducer"
    ;; Both events arrive in a single batch after L2 is populated.
    ;; The relevance filter probes payment-applied with (f {} event) —
    ;; the entity doesn't exist in L1 state yet because it was just created
    ;; in the same batch.
    ;; Full project with empty state first
    (rmp/p (make-context) (make-order-partitioned-args))
    ;; Now append both events at once
    (let [order-id (random-uuid)]
      (es/append *event-store*
        {:tenant-id test-tenant-id
         :events [(es/->event {:type :test/order-created
                               :body {:order-id order-id :location "west"}})
                  (es/->event {:type :test/payment-applied
                               :body {:order-id order-id :amount 2000}})]})
      ;; Single partition read — both events are new since watermark
      (let [result (rmp/p (make-context)
                          (make-order-partitioned-args {:partition-key "west"}))]
        (is (= 1 (count result)))
        (is (= 2000 (:total (get result order-id))))))))

;; I-unsafe-3

(deftest partitioned-unsafe-reducer-full-rebuild
  (testing "full partition rebuild with unsafe reducer processes all events correctly"
    ;; process-events-partitioned also calls (f {} event) for unknown entities
    (let [order-id (random-uuid)]
      (es/append *event-store*
        {:tenant-id test-tenant-id
         :events [(es/->event {:type :test/order-created
                               :body {:order-id order-id :location "north"}})
                  (es/->event {:type :test/payment-applied
                               :body {:order-id order-id :amount 7500}})]})
      ;; Full rebuild (no cache)
      (let [result (rmp/p (make-context) (make-order-partitioned-args))]
        (is (= 1 (count result)))
        (is (= 7500 (:total (get result order-id))))))))


(deftest qualified-read-model-names-isolate-registered-projections
  (rmp/register-read-model! :alpha/shared counter-reducer {:events #{:test/counter-incremented}})
  (rmp/register-read-model! :beta/shared (fn [s _] (update s :total (fnil + 0) 10)) {:events #{:test/counter-incremented}})
  (append-test-events! 3)
  (is (= {:count 3} (rmp/project (make-context) :alpha/shared)))
  (is (= {:total 30} (rmp/project (make-context) :beta/shared))))

(deftest timestamps-roundtrip-through-durable-state
  (append-test-events! 1)
  (let [e (first (into [] (es/read *event-store* {:tenant-id test-tenant-id})))
        args (make-args {:f (fn [s e] (assoc s :at (:event/timestamp e)))})]
    (rmp/p (make-context) args)
    (is (= (:event/timestamp e) (:at (rmp/p (make-context) args))))))

(deftest map-updates-preserve-unmodified-entries-and-retained-state
  (append-entity-events! 10)
  (let [before (rmp/p (make-context) (make-entity-args))]
    (append-entity-events! 2)
    (let [after (rmp/p (make-context) (make-entity-args))]
      (is (= 10 (count before))) (is (= 12 (count after)))
      (is (= (into {} before) (select-keys after (keys before)))))))

(deftest partitioned-via-project-api-and-qualified-name-isolation
  (doseq [name [:alpha/items :beta/items]]
    (rmp/register-read-model! name item-reducer
                              {:events #{:test/item-created :test/item-moved}
                               :partition-fn item-partition-fn :version 1}))
  (append-item-events! 2 "a") (append-item-events! 3 "b")
  (doseq [name [:alpha/items :beta/items]]
    (is (= 5 (count (rmp/project (make-context) name))))
    (is (= 2 (count (rmp/project (make-context) name {:partition-key "a"}))))
    (is (= 3 (count (rmp/project (make-context) name {:partition-key "b"}))))))
