(ns ai.obney.grain.event-store-postgres-v2.integration-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.event-store-postgres-v2.core :as pg-core]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clj-uuid :as uuid])
  (:import [java.sql Timestamp]))

;; -------------------- ;;
;; Schema Registration  ;;
;; -------------------- ;;

(defschemas test-event-schemas
  {:test/alpha [:map]
   :test/beta  [:map]
   :test/gamma [:map]
   :hello/world [:map]})

;; -------------------- ;;
;; Config & Dynamic Var ;;
;; -------------------- ;;

(def ^:dynamic *event-store* nil)

(defn pg-config []
  {:conn {:type          :postgres
          :server-name   (or (System/getenv "PG_HOST") "localhost")
          :port-number   (or (System/getenv "PG_PORT") "5432")
          :username      (or (System/getenv "PG_USER") "postgres")
          :password      (or (System/getenv "PG_PASSWORD") "password")
          :database-name (or (System/getenv "PG_DATABASE") "obneyai")}})

(defn pg-pool []
  (get-in *event-store* [:state ::pg-core/connection-pool]))

;; ---------- ;;
;; Fixtures   ;;
;; ---------- ;;

(defn once-fixture [f]
  (if (= "true" (System/getenv "PG_EVENT_STORE_TESTS"))
    (let [store (es/start (pg-config))]
      (binding [*event-store* store]
        (try
          (f)
          (finally
            (es/stop store)))))
    (println "SKIPPING Postgres integration tests (set PG_EVENT_STORE_TESTS=true to enable)")))

(defn each-fixture [f]
  (jdbc/execute! (pg-pool) ["TRUNCATE grain.events"])
  (f))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

;; ---------- ;;
;; Helpers    ;;
;; ---------- ;;

(defn append-event!
  ([type tags] (append-event! type tags nil))
  ([type tags body]
   (let [event (es/->event (cond-> {:type type :tags tags}
                             body (assoc :body body)))]
     (es/append *event-store* {:events [event]})
     event)))

(defn append-events! [events-data]
  (let [events (mapv es/->event events-data)]
    (es/append *event-store* {:events events})
    events))

(defn read-events [args]
  (into [] (es/read *event-store* args)))

(defn non-tx-events [events]
  (filterv #(not= :grain/tx (:event/type %)) events))

(defn tx-events [events]
  (filterv #(= :grain/tx (:event/type %)) events))

;; ======================== ;;
;; A. Lifecycle (3 tests)   ;;
;; ======================== ;;

(deftest start-and-stop-without-error
  (let [store (es/start (pg-config))]
    (try
      (is (some? store))
      (finally
        (es/stop store)))))

(deftest idempotent-initialization
  (let [store (es/start (pg-config))]
    (try
      ;; Second store on same DB works — append and read succeed
      (let [event (es/->event {:type :test/alpha :tags #{} :body {:from "second-store"}})]
        (es/append store {:events [event]})
        (let [events (into [] (es/read store {}))]
          (is (pos? (count events)))))
      (finally
        (es/stop store)))))

(deftest schema-tables-and-indexes-created
  (let [opts {:builder-fn rs/as-unqualified-maps}
        tables (jdbc/execute! (pg-pool)
                 ["SELECT table_name FROM information_schema.tables WHERE table_schema = 'grain'"]
                 opts)
        indexes (jdbc/execute! (pg-pool)
                  ["SELECT indexname FROM pg_indexes WHERE schemaname = 'grain'"]
                  opts)
        table-names (set (map :table_name tables))
        index-names (set (map :indexname indexes))]
    (is (contains? table-names "events"))
    (is (contains? table-names "global_lock"))
    (is (contains? index-names "idx_events_type"))
    (is (contains? index-names "idx_events_tags_gin"))))

;; ================================ ;;
;; B. Basic Append & Read (8 tests) ;;
;; ================================ ;;

(deftest append-single-event-and-read-back
  (let [event (append-event! :test/alpha #{} {:val 1})
        events (non-tx-events (read-events {}))]
    (is (= 1 (count events)))
    (is (= (:event/id event) (:event/id (first events))))))

(deftest append-multiple-events-and-read-all
  (let [events (append-events! [{:type :test/alpha :tags #{} :body {:n 1}}
                                {:type :test/beta  :tags #{} :body {:n 2}}
                                {:type :test/gamma :tags #{} :body {:n 3}}])
        read (non-tx-events (read-events {}))]
    (is (= 3 (count read)))
    (is (= (set (map :event/id events))
           (set (map :event/id read))))))

(deftest event-fields-round-trip-correctly
  (let [tag-id (uuid/v4)
        event (append-event! :test/alpha #{[:user tag-id]} {:val 42})
        read (first (non-tx-events (read-events {})))]
    (is (= (:event/id event) (:event/id read)))
    (is (= :test/alpha (:event/type read)))
    (is (= #{[:user tag-id]} (:event/tags read)))
    (is (= 42 (:val read)))
    (is (instance? Timestamp (:event/timestamp read)))))

(deftest body-data-with-various-edn-types
  (let [event (append-event! :test/alpha #{}
                {:string-val "hello"
                 :int-val 42
                 :float-val 3.14
                 :keyword-val :some-kw
                 :nested-map {:a 1 :b {:c 2}}
                 :vector-val [1 2 3]
                 :nil-val nil
                 :bool-true true
                 :bool-false false})
        read (first (non-tx-events (read-events {})))]
    (is (= "hello" (:string-val read)))
    (is (= 42 (:int-val read)))
    (is (= 3.14 (:float-val read)))
    (is (= :some-kw (:keyword-val read)))
    (is (= {:a 1 :b {:c 2}} (:nested-map read)))
    (is (= [1 2 3] (:vector-val read)))
    (is (nil? (:nil-val read)))
    (is (true? (:bool-true read)))
    (is (false? (:bool-false read)))))

(deftest events-with-multiple-tags
  (let [id1 (uuid/v4)
        id2 (uuid/v4)
        id3 (uuid/v4)
        event (append-event! :test/alpha #{[:user id1] [:org id2] [:team id3]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:user id1] [:org id2] [:team id3]} (:event/tags read)))))

(deftest events-with-qualified-keyword-types
  (let [event (append-event! :hello/world #{} {:msg "hi"})
        read (first (non-tx-events (read-events {})))]
    (is (= :hello/world (:event/type read)))
    (is (= "hi" (:msg read)))))

(deftest grain-tx-event-created-automatically
  (let [events (append-events! [{:type :test/alpha :tags #{} :body {:n 1}}
                                {:type :test/beta  :tags #{} :body {:n 2}}])
        all (read-events {})
        txs (tx-events all)]
    (is (= 1 (count txs)))
    (let [tx (first txs)]
      (is (= :grain/tx (:event/type tx)))
      (is (= (set (map :event/id events)) (:event-ids tx))))))

(deftest events-with-empty-body
  (let [event (es/->event {:type :test/alpha :tags #{}})
        _ (es/append *event-store* {:events [event]})
        read (first (non-tx-events (read-events {})))]
    (is (= (:event/id event) (:event/id read)))
    (is (= :test/alpha (:event/type read)))))

;; ============================ ;;
;; C. Read Filtering (9 tests)  ;;
;; ============================ ;;

(deftest filter-by-single-tag
  (let [id1 (uuid/v4)
        id2 (uuid/v4)
        evt1 (append-event! :test/alpha #{[:user id1]} {:from "a"})
        _evt2 (append-event! :test/alpha #{[:user id2]} {:from "b"})
        events (non-tx-events (read-events {:tags #{[:user id1]}}))]
    (is (= 1 (count events)))
    (is (= (:event/id evt1) (:event/id (first events))))))

(deftest filter-by-multiple-tags
  (let [id1 (uuid/v4)
        id2 (uuid/v4)
        evt1 (append-event! :test/alpha #{[:user id1] [:org id2]} {:both true})
        _evt2 (append-event! :test/alpha #{[:user id1]} {:only-user true})
        events (non-tx-events (read-events {:tags #{[:user id1] [:org id2]}}))]
    (is (= 1 (count events)))
    (is (= (:event/id evt1) (:event/id (first events))))))

(deftest filter-by-single-type
  (let [_evt-a (append-event! :test/alpha #{} {:n 1})
        evt-b (append-event! :test/beta #{} {:n 2})
        events (non-tx-events (read-events {:types #{:test/beta}}))]
    (is (= 1 (count events)))
    (is (= (:event/id evt-b) (:event/id (first events))))))

(deftest filter-by-multiple-types
  (let [evt-a (append-event! :test/alpha #{} {:n 1})
        evt-b (append-event! :test/beta #{} {:n 2})
        _evt-c (append-event! :test/gamma #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha :test/beta}}))]
    (is (= 2 (count events)))
    (is (= #{(:event/id evt-a) (:event/id evt-b)}
           (set (map :event/id events))))))

(deftest filter-by-after
  (let [evt1 (append-event! :test/alpha #{} {:n 1})
        evt2 (append-event! :test/alpha #{} {:n 2})
        evt3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha}
                                            :after (:event/id evt1)}))]
    (is (= 2 (count events)))
    (is (= #{(:event/id evt2) (:event/id evt3)}
           (set (map :event/id events))))))

(deftest filter-by-as-of
  (let [evt1 (append-event! :test/alpha #{} {:n 1})
        evt2 (append-event! :test/alpha #{} {:n 2})
        _evt3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha}
                                            :as-of (:event/id evt2)}))]
    (is (= 2 (count events)))
    (is (= #{(:event/id evt1) (:event/id evt2)}
           (set (map :event/id events))))))

(deftest combined-filter-tags-and-types
  (let [id (uuid/v4)
        evt1 (append-event! :test/alpha #{[:user id]} {:n 1})
        _evt2 (append-event! :test/beta #{[:user id]} {:n 2})
        _evt3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:tags #{[:user id]}
                                            :types #{:test/alpha}}))]
    (is (= 1 (count events)))
    (is (= (:event/id evt1) (:event/id (first events))))))

(deftest combined-filter-tags-and-after
  (let [id (uuid/v4)
        evt1 (append-event! :test/alpha #{[:user id]} {:n 1})
        evt2 (append-event! :test/alpha #{[:user id]} {:n 2})
        events (non-tx-events (read-events {:tags #{[:user id]}
                                            :after (:event/id evt1)}))]
    (is (= 1 (count events)))
    (is (= (:event/id evt2) (:event/id (first events))))))

(deftest filter-matching-nothing-returns-empty
  (append-event! :test/alpha #{} {:n 1})
  (let [events (read-events {:types #{:test/nonexistent}})]
    (is (empty? events))))

;; ========================== ;;
;; D. Batch Read (7 tests)    ;;
;; ========================== ;;

(deftest batch-single-query-equals-map-form
  (let [id (uuid/v4)
        _evt1 (append-event! :test/alpha #{[:thing id]} {:val 1})
        _evt2 (append-event! :test/beta #{[:thing id]} {:val 2})
        map-result (non-tx-events (read-events {:types #{:test/alpha}}))
        vec-result (non-tx-events (read-events [{:types #{:test/alpha}}]))]
    (is (= (count map-result) (count vec-result)))
    (is (= (mapv :event/id map-result)
           (mapv :event/id vec-result)))))

(deftest batch-multiple-queries-different-tags
  (let [id-a (uuid/v4)
        id-b (uuid/v4)
        evt-a (append-event! :test/alpha #{[:foo id-a]} {:from "a"})
        evt-b (append-event! :test/alpha #{[:bar id-b]} {:from "b"})
        events (non-tx-events
                (read-events [{:tags #{[:foo id-a]}}
                              {:tags #{[:bar id-b]}}]))]
    (is (= 2 (count events)))
    (is (= #{(:event/id evt-a) (:event/id evt-b)}
           (set (map :event/id events))))))

(deftest batch-multiple-queries-different-types
  (let [evt-a (append-event! :test/alpha #{} {:n 1})
        evt-b (append-event! :test/beta #{} {:n 2})
        _evt-c (append-event! :test/gamma #{} {:n 3})
        events (non-tx-events
                (read-events [{:types #{:test/alpha}}
                              {:types #{:test/beta}}]))]
    (is (= 2 (count events)))
    (is (= #{:test/alpha :test/beta}
           (set (map :event/type events))))))

(deftest batch-deduplication-across-queries
  (let [id-a (uuid/v4)
        id-b (uuid/v4)
        evt (append-event! :test/alpha #{[:foo id-a] [:bar id-b]} {:shared true})
        events (non-tx-events
                (read-events [{:tags #{[:foo id-a]}}
                              {:tags #{[:bar id-b]}}]))]
    (is (= 1 (count events)))
    (is (= (:event/id evt) (:event/id (first events))))))

(deftest batch-per-query-after-filter
  (let [id-a (uuid/v4)
        id-b (uuid/v4)
        evt1 (append-event! :test/alpha #{[:foo id-a]} {:n 1})
        evt2 (append-event! :test/alpha #{[:foo id-a]} {:n 2})
        evt3 (append-event! :test/alpha #{[:bar id-b]} {:n 3})
        events (non-tx-events
                (read-events [{:tags #{[:foo id-a]} :after (:event/id evt1)}
                              {:tags #{[:bar id-b]}}]))]
    (is (= 2 (count events)))
    (is (= #{(:event/id evt2) (:event/id evt3)}
           (set (map :event/id events))))))

(deftest batch-ordering-by-event-id-preserved
  (let [id-a (uuid/v4)
        id-b (uuid/v4)
        _evt1 (append-event! :test/alpha #{[:foo id-a]} {:n 1})
        _evt2 (append-event! :test/beta #{[:bar id-b]} {:n 2})
        _evt3 (append-event! :test/alpha #{[:foo id-a]} {:n 3})
        events (non-tx-events
                (read-events [{:tags #{[:foo id-a]}}
                              {:tags #{[:bar id-b]}}]))
        ids (mapv :event/id events)]
    (is (= ids (sort (fn [a b]
                       (cond (uuid/< a b) -1
                             (uuid/= a b) 0
                             :else 1))
                     ids)))))

(deftest batch-empty-result
  (let [events (non-tx-events
                (read-events [{:types #{:test/nonexistent}}
                              {:tags #{[:nope (uuid/v4)]}}]))]
    (is (empty? events))))

;; ===================================== ;;
;; E. CAS — Compare and Swap (5 tests)   ;;
;; ===================================== ;;

(deftest cas-predicate-true-stores-events
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})
        result (es/append *event-store*
                 {:events [event]
                  :cas {:types #{:test/alpha}
                        :predicate-fn (constantly true)}})]
    (is (not (::anom/category result)))
    (is (= 1 (count (non-tx-events (read-events {:types #{:test/alpha}})))))))

(deftest cas-predicate-false-returns-conflict-anomaly
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})
        result (es/append *event-store*
                 {:events [event]
                  :cas {:types #{:test/alpha}
                        :predicate-fn (constantly false)}})]
    (is (= ::anom/conflict (::anom/category result)))
    (is (empty? (non-tx-events (read-events {:types #{:test/alpha}}))))))

(deftest cas-reads-correct-subset-based-on-types
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/beta #{} {:n 2})
  (let [captured (atom nil)
        event (es/->event {:type :test/alpha :tags #{} :body {:n 3}})
        _ (es/append *event-store*
            {:events [event]
             :cas {:types #{:test/alpha}
                   :predicate-fn (fn [events]
                                   (reset! captured (into [] events))
                                   true)}})]
    (is (every? #(= :test/alpha (:event/type %)) @captured))
    (is (= 1 (count @captured)))))

(deftest cas-predicate-receives-correct-events-with-tags
  (let [id (uuid/v4)
        other-id (uuid/v4)]
    (append-event! :test/alpha #{[:user id]} {:n 1})
    (append-event! :test/alpha #{[:user other-id]} {:n 2})
    (let [captured (atom nil)
          event (es/->event {:type :test/alpha :tags #{[:user id]} :body {:n 3}})
          _ (es/append *event-store*
              {:events [event]
               :cas {:tags #{[:user id]}
                     :predicate-fn (fn [events]
                                     (reset! captured (into [] events))
                                     true)}})]
      (is (= 1 (count @captured)))
      (is (every? #(contains? (:event/tags %) [:user id]) @captured)))))

(deftest cas-with-empty-predicate-match
  (append-event! :test/alpha #{} {:n 1})
  (let [event (es/->event {:type :test/beta :tags #{} :body {:n 2}})
        result (es/append *event-store*
                 {:events [event]
                  :cas {:types #{:test/beta}
                        :predicate-fn (fn [events]
                                        (empty? (into [] events)))}})]
    (is (not (::anom/category result)))
    (is (= 1 (count (non-tx-events (read-events {:types #{:test/beta}})))))))

;; ====================================== ;;
;; F. Transaction Metadata (2 tests)      ;;
;; ====================================== ;;

(deftest tx-metadata-included-in-grain-tx-event
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
    (es/append *event-store* {:events [event]
                              :tx-metadata {:user-id "u123" :reason "test"}})
    (let [tx (first (tx-events (read-events {})))]
      (is (= {:user-id "u123" :reason "test"} (:metadata tx))))))

(deftest tx-metadata-absent-when-not-provided
  (append-event! :test/alpha #{} {:n 1})
  (let [tx (first (tx-events (read-events {})))]
    (is (not (contains? tx :metadata)))))

;; ================================= ;;
;; G. Reducible Behavior (5 tests)   ;;
;; ================================= ;;

(deftest reduce-with-init-value
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (reduce
                 (fn [acc event] (conj acc (:event/type event)))
                 []
                 (es/read *event-store* {:types #{:test/alpha}}))]
    (is (= [:test/alpha :test/alpha] result))))

(deftest reduce-without-init-value
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (reduce
                 (fn [a b] (update a :count (fnil inc 0)))
                 (es/read *event-store* {:types #{:test/alpha}}))]
    (is (map? result))
    (is (= 1 (:count result)))))

(deftest transduce-works
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/beta #{} {:n 2})
  (append-event! :test/alpha #{} {:n 3})
  (let [result (transduce
                 (filter #(= :test/alpha (:event/type %)))
                 conj
                 []
                 (es/read *event-store* {}))]
    (is (= 2 (count result)))
    (is (every? #(= :test/alpha (:event/type %)) result))))

(deftest into-works
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (into [] (es/read *event-store* {:types #{:test/alpha}}))]
    (is (vector? result))
    (is (= 2 (count result)))))

(deftest early-termination-with-reduced
  (dotimes [_ 5]
    (append-event! :test/alpha #{} {:n 1}))
  (let [result (reduce
                 (fn [acc event]
                   (if (>= (count acc) 3)
                     (reduced acc)
                     (conj acc event)))
                 []
                 (es/read *event-store* {}))]
    (is (= 3 (count result)))))

;; ========================== ;;
;; H. Edge Cases (5 tests)    ;;
;; ========================== ;;

(deftest empty-event-store-read
  (let [events (read-events {})]
    (is (empty? events))))

(deftest events-with-empty-tags-set
  (let [event (append-event! :test/alpha #{})
        read (first (non-tx-events (read-events {})))]
    (is (= #{} (:event/tags read)))))

(deftest large-number-of-events
  (let [events (mapv #(es/->event {:type :test/alpha :tags #{} :body {:n %}})
                     (range 150))]
    (es/append *event-store* {:events events})
    (let [read (non-tx-events (read-events {:types #{:test/alpha}}))]
      (is (= 150 (count read))))))

(deftest concurrent-appends
  (let [store *event-store*
        futures (doall
                  (for [i (range 10)]
                    (future
                      (let [events (mapv #(es/->event {:type :test/alpha :tags #{}
                                                       :body {:thread i :n %}})
                                         (range 5))]
                        (es/append store {:events events})))))]
    (run! deref futures)
    (let [all (non-tx-events (read-events {:types #{:test/alpha}}))]
      (is (= 50 (count all))))))

(deftest read-empty-filter-returns-all
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/beta #{} {:n 2})
  (let [all (read-events {})]
    ;; 2 user events + 2 tx events
    (is (= 4 (count all)))
    (is (= 2 (count (non-tx-events all))))
    (is (= 2 (count (tx-events all))))))

;; =============================== ;;
;; I. Data Integrity (5 tests)     ;;
;; =============================== ;;

(deftest tag-round-trip-with-qualified-keywords
  (let [id (uuid/v4)
        event (append-event! :test/alpha #{[:ns/entity-type id]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:ns/entity-type id]} (:event/tags read)))))

(deftest tag-round-trip-with-simple-keywords
  (let [id (uuid/v4)
        event (append-event! :test/alpha #{[:user id]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:user id]} (:event/tags read)))))

(deftest type-round-trip-keywords-preserved
  (let [event (append-event! :hello/world #{} {:msg "test"})
        read (first (non-tx-events (read-events {})))]
    (is (= :hello/world (:event/type read)))))

(deftest uuid-v7-ordering-preserved
  (let [evt1 (append-event! :test/alpha #{} {:n 1})
        evt2 (append-event! :test/alpha #{} {:n 2})
        evt3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha}}))
        ids (mapv :event/id events)]
    (is (= 3 (count ids)))
    (is (uuid/< (nth ids 0) (nth ids 1)))
    (is (uuid/< (nth ids 1) (nth ids 2)))))

(deftest timestamp-preserved-as-sql-timestamp
  (let [before (Timestamp. (System/currentTimeMillis))
        _ (append-event! :test/alpha #{} {:n 1})
        after (Timestamp. (System/currentTimeMillis))
        read (first (non-tx-events (read-events {})))
        ts (:event/timestamp read)]
    (is (instance? Timestamp ts))
    (is (not (.before ts before)))
    (is (not (.after ts after)))))
