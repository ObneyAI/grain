(ns ai.obney.grain.event-store-sqlite-v3.integration-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.core :as sqlite-core]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [clojure.core.async :as async]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string]
            [clj-uuid :as uuid])
  (:import [java.time OffsetDateTime ZoneOffset Instant]
           [java.io File]))

;; -------------------- ;;
;; Schema Registration  ;;
;; -------------------- ;;

(defschemas test-event-schemas
  {:test/alpha  [:map]
   :test/beta   [:map]
   :test/gamma  [:map]
   :hello/world [:map]})

;; -------------------- ;;
;; Config & Dynamic Var ;;
;; -------------------- ;;

(def ^:dynamic *event-store* nil)
(def ^:dynamic *tenant-id* nil)
(def ^:dynamic *db-file* nil)

(defn pool []
  (get-in *event-store* [:state ::sqlite-core/connection-pool]))

(defn- delete-sidecar-files [^String path]
  (doseq [suffix ["-wal" "-shm" "-journal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

;; ---------- ;;
;; Fixtures   ;;
;; ---------- ;;

(defn each-fixture [f]
  (let [tmp (File/createTempFile "grain-sqlite-test-" ".sqlite")]
    ;; Hikari/sqlite-jdbc will recreate the file with correct WAL setup;
    ;; remove the empty placeholder so init-idempotently starts fresh.
    (.delete tmp)
    (binding [*db-file* (.getAbsolutePath tmp)
              *tenant-id* (uuid/v4)]
      (let [store (es/start {:conn {:type :sqlite :database-file *db-file*}})]
        (binding [*event-store* store]
          (try
            (f)
            (finally
              (es/stop store)
              (.delete (File. ^String *db-file*))
              (delete-sidecar-files *db-file*))))))))

(use-fixtures :each each-fixture)

;; ---------- ;;
;; Helpers    ;;
;; ---------- ;;

(defn append-event!
  ([type tags] (append-event! type tags nil))
  ([type tags body]
   (let [event (es/->event (cond-> {:type type :tags tags}
                             body (assoc :body body)))]
     (es/append *event-store* {:tenant-id *tenant-id* :events [event]})
     event)))

(defn append-events! [events-data]
  (let [events (mapv es/->event events-data)]
    (es/append *event-store* {:tenant-id *tenant-id* :events events})
    events))

(defn read-events [args]
  (into [] (es/read *event-store* (if (vector? args)
                                    (mapv #(assoc % :tenant-id *tenant-id*) args)
                                    (assoc args :tenant-id *tenant-id*)))))

(defn non-tx-events [events]
  (filterv #(not= :grain/tx (:event/type %)) events))

(defn tx-events [events]
  (filterv #(= :grain/tx (:event/type %)) events))

;; ======================== ;;
;; A. Lifecycle             ;;
;; ======================== ;;

(deftest start-and-stop-without-error
  (let [tmp (File/createTempFile "grain-life-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)]
    (try
      (let [store (es/start {:conn {:type :sqlite :database-file path}})]
        (try
          (is (some? store))
          (finally
            (es/stop store))))
      (finally
        (.delete tmp)
        (delete-sidecar-files path)))))

(deftest start-returns-functional-store
  (let [tmp (File/createTempFile "grain-life-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)]
    (try
      (let [store (es/start {:conn {:type :sqlite :database-file path}})
            tenant-id (uuid/v4)
            event (es/->event {:type :test/alpha :tags #{} :body {:val 1}})]
        (try
          (es/append store {:tenant-id tenant-id :events [event]})
          (let [events (into [] (es/read store {:tenant-id tenant-id}))]
            (is (pos? (count events))))
          (finally
            (es/stop store))))
      (finally
        (.delete tmp)
        (delete-sidecar-files path)))))

(deftest idempotent-initialization
  ;; Start a second store on the *same* db file — schema migration must be a no-op.
  (let [store2 (es/start {:conn {:type :sqlite :database-file *db-file*}})]
    (try
      (let [event (es/->event {:type :test/alpha :tags #{} :body {:from "second-store"}})]
        (es/append store2 {:tenant-id *tenant-id* :events [event]})
        (let [events (into [] (es/read store2 {:tenant-id *tenant-id*}))]
          (is (pos? (count events)))))
      (finally
        (es/stop store2)))))

(deftest schema-objects-created
  (let [opts {:builder-fn rs/as-unqualified-maps}
        tables (jdbc/execute! (pool)
                              ["SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"]
                              opts)
        indexes (jdbc/execute! (pool)
                               ["SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%'"]
                               opts)
        table-names (set (map :name tables))
        index-names (set (map :name indexes))]
    (is (contains? table-names "tenants"))
    (is (contains? table-names "events"))
    (is (contains? table-names "event_tags"))
    (is (contains? index-names "idx_events_tenant_type"))
    (is (contains? index-names "idx_events_tenant_id_order"))
    (is (contains? index-names "idx_event_tags_event"))))

;; ================================ ;;
;; B. Basic Append & Read           ;;
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
    (is (instance? OffsetDateTime (:event/timestamp read)))))

(deftest body-data-with-various-types
  (let [_ (append-event! :test/alpha #{}
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

(deftest body-data-with-java-time-values
  (let [odt (OffsetDateTime/now (ZoneOffset/of "-05:00"))
        inst (Instant/now)
        _ (append-event! :test/alpha #{}
                         {:started-at odt
                          :recorded-at inst
                          :nested {:deadlines [odt odt]}})
        read (first (non-tx-events (read-events {})))]
    (is (instance? OffsetDateTime (:started-at read)))
    (is (= odt (:started-at read)))
    (is (= (.getOffset odt) (.getOffset ^OffsetDateTime (:started-at read)))
        "Non-UTC offset is preserved, not normalized to UTC")
    (is (instance? Instant (:recorded-at read)))
    (is (= inst (:recorded-at read)))
    (is (= [odt odt] (get-in read [:nested :deadlines]))
        "java.time values round-trip inside nested Clojure collections too")))

(deftest events-with-multiple-tags
  (let [id1 (uuid/v4)
        id2 (uuid/v4)
        id3 (uuid/v4)
        _ (append-event! :test/alpha #{[:user id1] [:org id2] [:team id3]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:user id1] [:org id2] [:team id3]} (:event/tags read)))))

(deftest events-with-qualified-keyword-types
  (let [_ (append-event! :hello/world #{} {:msg "hi"})
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
        _ (es/append *event-store* {:tenant-id *tenant-id* :events [event]})
        read (first (non-tx-events (read-events {})))]
    (is (= (:event/id event) (:event/id read)))
    (is (= :test/alpha (:event/type read)))))

;; ============================ ;;
;; C. Read Filtering            ;;
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
;; D. Batch Read              ;;
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
;; E. CAS                                ;;
;; ===================================== ;;

(deftest cas-predicate-true-stores-events
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})
        result (es/append *event-store*
                          {:tenant-id *tenant-id*
                           :events [event]
                           :cas {:types #{:test/alpha}
                                 :predicate-fn (constantly true)}})]
    (is (not (::anom/category result)))
    (is (= 1 (count (non-tx-events (read-events {:types #{:test/alpha}})))))))

(deftest cas-predicate-false-returns-conflict-anomaly
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})
        result (es/append *event-store*
                          {:tenant-id *tenant-id*
                           :events [event]
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
                     {:tenant-id *tenant-id*
                      :events [event]
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
                       {:tenant-id *tenant-id*
                        :events [event]
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
                          {:tenant-id *tenant-id*
                           :events [event]
                           :cas {:types #{:test/beta}
                                 :predicate-fn (fn [events]
                                                 (empty? (into [] events)))}})]
    (is (not (::anom/category result)))
    (is (= 1 (count (non-tx-events (read-events {:types #{:test/beta}})))))))

;; ====================================== ;;
;; F. Transaction Metadata                ;;
;; ====================================== ;;

(deftest tx-metadata-included-in-grain-tx-event
  (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
    (es/append *event-store* {:tenant-id *tenant-id*
                              :events [event]
                              :tx-metadata {:user-id "u123" :reason "test"}})
    (let [tx (first (tx-events (read-events {})))]
      (is (= {:user-id "u123" :reason "test"} (:metadata tx))))))

(deftest tx-metadata-absent-when-not-provided
  (append-event! :test/alpha #{} {:n 1})
  (let [tx (first (tx-events (read-events {})))]
    (is (not (contains? tx :metadata)))))

;; ================================= ;;
;; G. Reducible Behavior             ;;
;; ================================= ;;

(deftest reduce-with-init-value
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (reduce
                (fn [acc event] (conj acc (:event/type event)))
                []
                (es/read *event-store* {:tenant-id *tenant-id*
                                        :types #{:test/alpha}}))]
    (is (= [:test/alpha :test/alpha] result))))

(deftest reduce-without-init-value
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (reduce
                (fn [a _b] (update a :count (fnil inc 0)))
                (es/read *event-store* {:tenant-id *tenant-id*
                                        :types #{:test/alpha}}))]
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
                (es/read *event-store* {:tenant-id *tenant-id*}))]
    (is (= 2 (count result)))
    (is (every? #(= :test/alpha (:event/type %)) result))))

(deftest into-works
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/alpha #{} {:n 2})
  (let [result (into [] (es/read *event-store* {:tenant-id *tenant-id*
                                                :types #{:test/alpha}}))]
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
                (es/read *event-store* {:tenant-id *tenant-id*}))]
    (is (= 3 (count result)))))

;; ========================== ;;
;; H. Edge Cases              ;;
;; ========================== ;;

(deftest empty-event-store-read
  (let [events (read-events {})]
    (is (empty? events))))

(deftest events-with-empty-tags-set
  (let [_ (append-event! :test/alpha #{})
        read (first (non-tx-events (read-events {})))]
    (is (= #{} (:event/tags read)))))

(deftest large-number-of-events
  (let [events (mapv #(es/->event {:type :test/alpha :tags #{} :body {:n %}})
                     (range 150))]
    (es/append *event-store* {:tenant-id *tenant-id* :events events})
    (let [read (non-tx-events (read-events {:types #{:test/alpha}}))]
      (is (= 150 (count read))))))

(deftest concurrent-appends
  (let [store *event-store*
        tenant-id *tenant-id*
        futures (doall
                 (for [i (range 10)]
                   (future
                     (let [events (mapv #(es/->event {:type :test/alpha :tags #{}
                                                      :body {:thread i :n %}})
                                        (range 5))]
                       (es/append store {:tenant-id tenant-id :events events})))))]
    (run! deref futures)
    (let [all (non-tx-events (into [] (es/read store {:tenant-id tenant-id
                                                      :types #{:test/alpha}})))]
      (is (= 50 (count all))))))

(deftest read-empty-filter-returns-all
  (append-event! :test/alpha #{} {:n 1})
  (append-event! :test/beta #{} {:n 2})
  (let [all (read-events {})]
    (is (= 4 (count all)))
    (is (= 2 (count (non-tx-events all))))
    (is (= 2 (count (tx-events all))))))

;; =============================== ;;
;; I. Data Integrity               ;;
;; =============================== ;;

(deftest tag-round-trip-with-qualified-keywords
  (let [id (uuid/v4)
        _ (append-event! :test/alpha #{[:ns/entity-type id]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:ns/entity-type id]} (:event/tags read)))))

(deftest tag-round-trip-with-simple-keywords
  (let [id (uuid/v4)
        _ (append-event! :test/alpha #{[:user id]})
        read (first (non-tx-events (read-events {})))]
    (is (= #{[:user id]} (:event/tags read)))))

(deftest type-round-trip-keywords-preserved
  (let [_ (append-event! :hello/world #{} {:msg "test"})
        read (first (non-tx-events (read-events {})))]
    (is (= :hello/world (:event/type read)))))

(deftest uuid-v7-ordering-preserved
  (let [_evt1 (append-event! :test/alpha #{} {:n 1})
        _evt2 (append-event! :test/alpha #{} {:n 2})
        _evt3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha}}))
        ids (mapv :event/id events)]
    (is (= 3 (count ids)))
    (is (uuid/< (nth ids 0) (nth ids 1)))
    (is (uuid/< (nth ids 1) (nth ids 2)))))

(deftest timestamp-preserved-as-offsetdatetime
  (let [before (OffsetDateTime/now)
        _ (append-event! :test/alpha #{} {:n 1})
        after (OffsetDateTime/now)
        read (first (non-tx-events (read-events {})))
        ts (:event/timestamp read)]
    (is (instance? OffsetDateTime ts))
    (is (not (.isBefore ts before)))
    (is (not (.isAfter ts after)))))

;; ======================================== ;;
;; J. Tenant Isolation                      ;;
;; ======================================== ;;

(deftest tenant-a-cannot-read-tenant-b-events
  (let [tenant-a (uuid/v4)
        tenant-b (uuid/v4)
        event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
    (es/append *event-store* {:tenant-id tenant-a :events [event]})
    (let [a-events (into [] (es/read *event-store* {:tenant-id tenant-a}))
          b-events (into [] (es/read *event-store* {:tenant-id tenant-b}))]
      (is (pos? (count a-events)))
      (is (empty? b-events)))))

(deftest tenant-events-do-not-leak-in-filtered-reads
  (let [tenant-a (uuid/v4)
        tenant-b (uuid/v4)
        id (uuid/v4)]
    (es/append *event-store*
               {:tenant-id tenant-a
                :events [(es/->event {:type :test/alpha :tags #{[:user id]} :body {:from "a"}})]})
    (es/append *event-store*
               {:tenant-id tenant-b
                :events [(es/->event {:type :test/alpha :tags #{[:user id]} :body {:from "b"}})]})
    (let [a-events (non-tx-events (into [] (es/read *event-store* {:tenant-id tenant-a :tags #{[:user id]}})))
          b-events (non-tx-events (into [] (es/read *event-store* {:tenant-id tenant-b :tags #{[:user id]}})))]
      (is (= 1 (count a-events)))
      (is (= "a" (:from (first a-events))))
      (is (= 1 (count b-events)))
      (is (= "b" (:from (first b-events)))))))

(deftest tenant-isolation-in-batch-reads
  (let [tenant-a (uuid/v4)
        tenant-b (uuid/v4)
        id-a (uuid/v4)
        id-b (uuid/v4)]
    (es/append *event-store*
               {:tenant-id tenant-a
                :events [(es/->event {:type :test/alpha :tags #{[:foo id-a]} :body {:from "a"}})]})
    (es/append *event-store*
               {:tenant-id tenant-b
                :events [(es/->event {:type :test/alpha :tags #{[:bar id-b]} :body {:from "b"}})]})
    (let [events (non-tx-events
                  (into [] (es/read *event-store*
                                    [{:tenant-id tenant-a :tags #{[:foo id-a]}}
                                     {:tenant-id tenant-a :tags #{[:bar id-b]}}])))]
      (is (= 1 (count events)))
      (is (= "a" (:from (first events)))))))

(deftest read-events-do-not-contain-internal-tenant-key
  (let [_ (append-event! :test/alpha #{} {:n 1})
        read (first (non-tx-events (read-events {})))]
    (is (not (contains? read :grain/tenant-id)))))

(deftest concurrent-appends-different-tenants
  (let [store *event-store*
        tenants (repeatedly 5 uuid/v4)
        futures (doall
                 (for [tenant-id tenants]
                   (future
                     (let [events (mapv #(es/->event {:type :test/alpha :tags #{}
                                                      :body {:n %}})
                                        (range 10))]
                       (es/append store {:tenant-id tenant-id :events events})))))]
    (run! deref futures)
    (doseq [tenant-id tenants]
      (let [events (non-tx-events (into [] (es/read store {:tenant-id tenant-id})))]
        (is (= 10 (count events)))))))

(deftest auto-tenant-registration
  (let [tenant-id (uuid/v4)
        event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
    (es/append *event-store* {:tenant-id tenant-id :events [event]})
    (let [rows (jdbc/execute! (pool)
                              ["SELECT id FROM tenants WHERE id = ?" (str tenant-id)]
                              {:builder-fn rs/as-unqualified-maps})]
      (is (= 1 (count rows)))
      (is (= (str tenant-id) (:id (first rows)))))))

(deftest fressian-data-stored-as-blob
  (let [event (append-event! :test/alpha #{} {:val 99})]
    (let [raw-row (first (jdbc/execute! (pool)
                                        ["SELECT data FROM events WHERE id = ?" (str (:event/id event))]
                                        {:builder-fn rs/as-unqualified-maps}))]
      (is (some? (:data raw-row)) "data column should be non-NULL")
      (is (instance? (Class/forName "[B") (:data raw-row)) "data should be a byte array"))))

;; ============================================ ;;
;; K. Pubsub Tenant-ID Propagation              ;;
;; ============================================ ;;

(deftest published-events-contain-tenant-id
  (let [tmp (File/createTempFile "grain-pubsub-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)
        tenant-id (uuid/v4)
        published (atom [])
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        sub-ch (async/chan 10)
        store (es/start {:conn {:type :sqlite :database-file path}
                         :event-pubsub ps})]
    (pubsub/sub ps {:topic :test/alpha :sub-chan sub-ch})
    (async/go-loop []
      (when-let [evt (async/<! sub-ch)]
        (swap! published conj evt)
        (recur)))
    (try
      (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
        (es/append store {:tenant-id tenant-id :events [event]})
        (Thread/sleep 100)
        (is (= 1 (count @published)))
        (is (= tenant-id (:grain/tenant-id (first @published)))))
      (finally
        (async/close! sub-ch)
        (es/stop store)
        (pubsub/stop ps)
        (.delete (File. path))
        (delete-sidecar-files path)))))

(deftest stored-events-do-not-contain-tenant-id-with-pubsub
  (let [tmp (File/createTempFile "grain-pubsub-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)
        tenant-id (uuid/v4)
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        store (es/start {:conn {:type :sqlite :database-file path}
                         :event-pubsub ps})]
    (try
      (let [event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
        (es/append store {:tenant-id tenant-id :events [event]})
        (let [read (into [] (es/read store {:tenant-id tenant-id :types #{:test/alpha}}))]
          (is (not-any? :grain/tenant-id read))))
      (finally
        (es/stop store)
        (pubsub/stop ps)
        (.delete (File. path))
        (delete-sidecar-files path)))))

;; ============================================ ;;
;; SQLite-specific durability & concurrency     ;;
;; ============================================ ;;

(deftest wal-mode-enabled
  (let [row (jdbc/execute-one! (pool)
                               ["PRAGMA journal_mode"]
                               {:builder-fn rs/as-unqualified-maps})]
    (is (= "wal" (:journal_mode row)))))

(deftest foreign-keys-enforced
  (let [row (jdbc/execute-one! (pool)
                               ["PRAGMA foreign_keys"]
                               {:builder-fn rs/as-unqualified-maps})]
    (is (= 1 (:foreign_keys row))))
  ;; Inserting an event_tags row referencing a non-existent event must fail.
  (is (thrown? Exception
               (jdbc/execute! (pool)
                              ["INSERT INTO event_tags (tenant_id, event_id, tag) VALUES (?, ?, ?)"
                               (str (uuid/v4)) (str (uuid/v4)) "user:abc"]))))

(deftest persistence-across-restart
  (let [tmp (File/createTempFile "grain-persist-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)
        tenant-id (uuid/v4)]
    (try
      (let [store1 (es/start {:conn {:type :sqlite :database-file path}})
            event (es/->event {:type :test/alpha :tags #{} :body {:n 1}})]
        (es/append store1 {:tenant-id tenant-id :events [event]})
        (es/stop store1))
      (let [store2 (es/start {:conn {:type :sqlite :database-file path}})]
        (try
          (let [events (non-tx-events (into [] (es/read store2 {:tenant-id tenant-id})))]
            (is (= 1 (count events)))
            (is (= 1 (:n (first events)))))
          (finally
            (es/stop store2))))
      (finally
        (.delete (File. path))
        (delete-sidecar-files path)))))

(deftest cas-rollback-leaves-no-orphan-tags
  ;; Append an event with tags whose insert is then rolled back via failed CAS.
  (let [tag-id (uuid/v4)
        event (es/->event {:type :test/alpha :tags #{[:user tag-id]} :body {:n 1}})
        result (es/append *event-store*
                          {:tenant-id *tenant-id*
                           :events [event]
                           :cas {:types #{:test/alpha}
                                 :predicate-fn (constantly false)}})]
    (is (= ::anom/conflict (::anom/category result)))
    (let [n-events (-> (jdbc/execute-one! (pool)
                                          ["SELECT COUNT(*) AS n FROM events"]
                                          {:builder-fn rs/as-unqualified-maps})
                       :n)
          n-tags (-> (jdbc/execute-one! (pool)
                                        ["SELECT COUNT(*) AS n FROM event_tags"]
                                        {:builder-fn rs/as-unqualified-maps})
                     :n)]
      (is (zero? n-events))
      (is (zero? n-tags)))))

(deftest begin-immediate-serializes-writers
  ;; Two concurrent CAS appends with overlapping predicates: the read-then-write
  ;; window must be serialized so that the *second* writer sees the first
  ;; writer's event when evaluating its CAS predicate. Without BEGIN IMMEDIATE
  ;; the two transactions could overlap and both see the empty pre-state.
  (let [store *event-store*
        tenant-id *tenant-id*
        only-if-empty (fn [events] (empty? (into [] events)))
        f1 (future
             (es/append store
                        {:tenant-id tenant-id
                         :events [(es/->event {:type :test/alpha :body {:n 1}})]
                         :cas {:types #{:test/alpha} :predicate-fn only-if-empty}}))
        f2 (future
             (es/append store
                        {:tenant-id tenant-id
                         :events [(es/->event {:type :test/alpha :body {:n 2}})]
                         :cas {:types #{:test/alpha} :predicate-fn only-if-empty}}))
        results [@f1 @f2]
        successes (filter #(not (::anom/category %)) results)
        conflicts (filter #(= ::anom/conflict (::anom/category %)) results)]
    (is (= 1 (count successes)))
    (is (= 1 (count conflicts)))
    (is (= 1 (count (non-tx-events (into [] (es/read store {:tenant-id tenant-id
                                                            :types #{:test/alpha}}))))))))

;; ===================================================== ;;
;; Reverse / Limit single-read primitive + EXPLAIN       ;;
;; ===================================================== ;;

(deftest reverse-returns-descending-by-id-sqlite
  (let [e1 (append-event! :test/alpha #{} {:n 1})
        e2 (append-event! :test/alpha #{} {:n 2})
        e3 (append-event! :test/alpha #{} {:n 3})
        events (non-tx-events (read-events {:types #{:test/alpha} :reverse? true}))]
    (is (= [(:event/id e3) (:event/id e2) (:event/id e1)]
           (mapv :event/id events)))))

(deftest reverse-limit-1-returns-single-newest-sqlite
  (let [_e1 (append-event! :test/alpha #{} {:n 1})
        _e2 (append-event! :test/alpha #{} {:n 2})
        e3 (append-event! :test/alpha #{} {:n 3})
        events (read-events {:types #{:test/alpha} :reverse? true :limit 1})]
    (is (= 1 (count events)))
    (is (= (:event/id e3) (:event/id (first events))))))

(deftest reverse-limit-1-with-type-and-tag-sqlite
  ;; Mirrors the checkpoint read shape: type + processor tag, newest only.
  (let [pid (uuid/v4)
        _e1 (append-event! :test/alpha #{[:processor pid]} {:n 1})
        e2 (append-event! :test/alpha #{[:processor pid]} {:n 2})
        events (read-events {:types #{:test/alpha}
                             :tags #{[:processor pid]}
                             :reverse? true :limit 1})]
    (is (= 1 (count events)))
    (is (= (:event/id e2) (:event/id (first events))))))

(deftest limit-without-reverse-returns-oldest-sqlite
  (let [e1 (append-event! :test/alpha #{} {:n 1})
        _e2 (append-event! :test/alpha #{} {:n 2})
        events (read-events {:types #{:test/alpha} :limit 1})]
    (is (= 1 (count events)))
    (is (= (:event/id e1) (:event/id (first events))))))

(deftest checkpoint-read-uses-index-not-full-scan
  ;; The field-relevant proof: the checkpoint-shaped reverse+limit-1 query plans
  ;; as an index SEARCH (seek), never a full TABLE SCAN of events.
  (let [pid (uuid/v4)]
    (dotimes [n 50] (append-event! :test/alpha #{[:processor pid]} {:n n}))
    (let [{:keys [sql params]} (#'sqlite-core/build-single-query
                                {:tenant-id *tenant-id*
                                 :types #{:test/alpha}
                                 :tags #{[:processor pid]}
                                 :reverse? true :limit 1})
          plan-rows (jdbc/execute! (pool)
                                   (into [(str "EXPLAIN QUERY PLAN " sql)] params)
                                   {:builder-fn rs/as-unqualified-maps})
          details (mapv :detail plan-rows)
          joined (clojure.string/join " | " details)]
      (testing (str "query plan: " joined)
        ;; Every access path the planner picks must be an index SEARCH, not a
        ;; full SCAN of the events table.
        (is (some #(re-find #"(?i)SEARCH" %) details)
            "plan uses an index SEARCH/seek")
        (is (not (some #(re-find #"(?i)SCAN .*\bevents\b" %) details))
            "plan does NOT full-scan the events table")))))
