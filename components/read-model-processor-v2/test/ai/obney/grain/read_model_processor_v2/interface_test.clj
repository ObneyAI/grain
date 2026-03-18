(ns ai.obney.grain.read-model-processor-v2.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.java.io :as io]
            [clojure.set :as set]))

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

(def ^:dynamic *event-store* nil)
(def ^:dynamic *cache* nil)

(defn- delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(defn test-fixture [f]
  (let [dir   (str "/tmp/rmp-test-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    (binding [*event-store* store
              *cache*       cache]
      (try
        (rmp/l1-clear!)
        (f)
        (finally
          (rmp/l1-clear!)
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

(use-fixtures :each test-fixture)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn counter-reducer [state _event]
  (update state :count (fnil inc 0)))

(defn make-context []
  {:event-store *event-store*
   :cache       *cache*
   :tenant-id   test-tenant-id})

(defn make-args
  ([] (make-args {}))
  ([overrides]
   (merge {:f       counter-reducer
           :query   {:types #{:test/counter-incremented}}
           :name    :test/counter
           :version 1
           :l1-ttl-ms 0}
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
  ([name version tenant-id]
   (some-> (kv/get! *cache* {:k (core/format-scoped-key name version tenant-id)})
           core/fressian-decode)))

;; ---------------------------------------------------------------------------
;; A. Cache Miss
;; ---------------------------------------------------------------------------

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

;; ---------------------------------------------------------------------------
;; B. Cache Hit Threshold
;; ---------------------------------------------------------------------------

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
         :version 2}
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
          (is (= 2 (:version entry)))))

      (testing "registry function matches the defn"
        (let [entry (get @rmp/read-model-registry* :test/counter-rm)]
          (is (= {:count 1}
                 ((:reducer-fn entry) {} {:event/type :test/counter-incremented})))))

      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

(deftest defreadmodel-with-docstring
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/defreadmodel :test documented-rm
        {:events #{:test/counter-incremented}
         :version 1}
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
           :l1-ttl-ms 0}
          overrides)))

(defn read-raw-cache
  "Read raw bytes from LMDB at a cache key, decode to Clojure data."
  [name version tenant-id]
  (some-> (kv/get! *cache* {:k (core/format-scoped-key name version tenant-id)})
          core/fressian-decode))

(defn read-segment
  "Read a single segment from LMDB."
  [base-key idx]
  (let [seg-k (let [suffix (.getBytes (format ":s%d" idx))
                    result (byte-array (+ (alength ^bytes base-key) (alength suffix)))]
                (System/arraycopy base-key 0 result 0 (alength ^bytes base-key))
                (System/arraycopy suffix 0 result (alength ^bytes base-key) (alength suffix))
                result)]
    (some-> (kv/get! *cache* {:k seg-k})
            core/fressian-decode)))

;; H1
(deftest segmented-cache-round-trip
  (testing "15K entities produce a segmented cache with 64 segments"
    (append-entity-events! 15000)
    (let [result (rmp/p (make-context) (make-entity-args))
          base-key (core/format-scoped-key "test-entities" 1 test-tenant-id)
          manifest (read-raw-cache "test-entities" 1 test-tenant-id)]
      ;; Result has all 15K entries
      (is (= 15000 (count result)))

      ;; Manifest is segmented
      (is (true? (:segmented manifest)))
      (is (= 64 (:segment-count manifest)))
      (is (uuid? (:watermark manifest)))
      (is (map? (:checksums manifest)))

      ;; Read all segments and merge — must equal projected state
      (let [merged (reduce
                    (fn [acc idx]
                      (if-let [seg (read-segment base-key idx)]
                        (merge acc seg)
                        acc))
                    {}
                    (range 64))]
        (is (= 15000 (count merged)))
        (is (= result merged))))))

;; H2
(deftest segmented-cache-incremental-update
  (testing "adding events to a segmented cache updates correctly"
    (append-entity-events! 15000)
    (let [result1 (rmp/p (make-context) (make-entity-args))]
      (is (= 15000 (count result1)))

      ;; Add 10 more (meets threshold for cache write-back)
      (append-entity-events! 10)
      (let [result2 (rmp/p (make-context) (make-entity-args))
            manifest (read-raw-cache "test-entities" 1 test-tenant-id)]
        (is (= 15010 (count result2)))
        ;; All original keys still present
        (is (every? #(contains? result2 %) (keys result1)))
        ;; Watermark advanced
        (is (uuid? (:watermark manifest)))))))

;; H3
(deftest segmented-incremental-writes-only-changed-segments
  (testing "only changed segments are rewritten on incremental update"
    (append-entity-events! 15000)
    (rmp/p (make-context) (make-entity-args))
    (let [manifest-before (read-raw-cache "test-entities" 1 test-tenant-id)
          checksums-before (:checksums manifest-before)]

      ;; Add exactly 10 events (triggers write-back threshold)
      (append-entity-events! 10)
      (rmp/p (make-context) (make-entity-args))
      (let [manifest-after (read-raw-cache "test-entities" 1 test-tenant-id)
            checksums-after (:checksums manifest-after)
            changed-count (count (filter (fn [[idx cs]]
                                           (not= cs (get checksums-before idx)))
                                         checksums-after))]
        ;; At most 10 segments changed (could be fewer due to hash collisions)
        (is (<= changed-count 10))
        (is (pos? changed-count))
        ;; Unchanged segments have identical checksums
        (let [unchanged (filter (fn [[idx _]] (= (get checksums-before idx)
                                                  (get checksums-after idx)))
                                checksums-after)]
          (is (>= (count unchanged) (- 64 10))))))))

;; H4
(deftest small-model-stays-monolithic
  (testing "read models under threshold use legacy single-entry format"
    (append-entity-events! 100)
    (let [result (rmp/p (make-context) (make-entity-args))
          cached (read-raw-cache "test-entities" 1 test-tenant-id)]
      (is (= 100 (count result)))
      ;; Legacy format: has :data and :watermark, no :segmented
      (is (contains? cached :data))
      (is (contains? cached :watermark))
      (is (not (contains? cached :segmented))))))

;; H5
(deftest migration-monolithic-to-segmented
  (testing "cache migrates from monolithic to segmented when it grows past threshold"
    ;; Start small — monolithic
    (append-entity-events! 5000)
    (rmp/p (make-context) (make-entity-args))
    (let [cached (read-raw-cache "test-entities" 1 test-tenant-id)]
      (is (not (contains? cached :segmented)))
      (is (= 5000 (count (:data cached)))))

    ;; Grow past threshold — need >= 10 new events to trigger write-back
    (append-entity-events! 10000)
    (let [result (rmp/p (make-context) (make-entity-args))
          manifest (read-raw-cache "test-entities" 1 test-tenant-id)
          base-key (core/format-scoped-key "test-entities" 1 test-tenant-id)]
      (is (= 15000 (count result)))
      ;; Now segmented
      (is (true? (:segmented manifest)))
      (is (= 64 (:segment-count manifest)))
      ;; Segments contain the full data
      (let [merged (reduce
                    (fn [acc idx]
                      (if-let [seg (read-segment base-key idx)]
                        (merge acc seg)
                        acc))
                    {}
                    (range 64))]
        (is (= 15000 (count merged)))))))

;; H6
(deftest segmented-cache-with-scope
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
                             (range 15000))
              events-b (mapv (fn [_] (es/->event {:type :test/entity-created
                                                   :tags #{[:org org-b]}
                                                   :body {:entity-id (random-uuid)}}))
                             (range 15000))]
          (es/append *event-store* {:tenant-id test-tenant-id :events events-a})
          (es/append *event-store* {:tenant-id test-tenant-id :events events-b}))

        (let [result-a (rmp/project (make-context) :test/entities {:tags #{[:org org-a]}})
              result-b (rmp/project (make-context) :test/entities {:tags #{[:org org-b]}})]
          ;; Each scope gets its own 15K entries
          (is (= 15000 (count result-a)))
          (is (= 15000 (count result-b)))
          ;; Data is isolated — no overlap
          (is (empty? (clojure.set/intersection (set (keys result-a))
                                                (set (keys result-b)))))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; H7
(deftest segmented-cache-cross-tenant-isolation
  (let [tenant-a (random-uuid)
        tenant-b (random-uuid)
        ctx-a (assoc (make-context) :tenant-id tenant-a)
        ctx-b (assoc (make-context) :tenant-id tenant-b)]
    (append-entity-events-to-tenant! tenant-a 15000)
    (append-entity-events-to-tenant! tenant-b 15000)
    (let [result-a (rmp/p ctx-a (make-entity-args))
          result-b (rmp/p ctx-b (make-entity-args))]
      ;; Each tenant gets 15K entries
      (is (= 15000 (count result-a)))
      (is (= 15000 (count result-b)))
      ;; Completely isolated — no shared keys
      (is (empty? (set/intersection (set (keys result-a))
                                    (set (keys result-b))))))))

;; ---------------------------------------------------------------------------
;; I. Partitioned Projections
;; ---------------------------------------------------------------------------

(defschemas partition-schemas
  {:test/item-created [:map [:item-id :uuid] [:bucket :string]]
   :test/item-updated [:map [:item-id :uuid] [:bucket :string] [:value :int]]
   :test/item-moved   [:map [:item-id :uuid] [:old-bucket :string] [:new-bucket :string]]})

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
           :l1-ttl-ms    0}
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
(deftest partitioned-cache-structure
  (testing "partitioned read model stores manifest, partitions, and index in LMDB"
    (append-item-events! 30 "x")
    (append-item-events! 20 "y")
    (append-item-events! 10 "z")
    (rmp/p (make-context) (make-partitioned-args))

    (let [base-key (core/format-scoped-key "test-items" 1 test-tenant-id)
          manifest (core/fressian-decode (kv/get! *cache* {:k base-key}))]
      ;; Manifest structure
      (is (true? (:partitioned manifest)))
      (is (= #{"x" "y" "z"} (:partition-keys manifest)))
      (is (uuid? (:global-watermark manifest)))

      ;; Each partition readable
      (doseq [pk ["x" "y" "z"]]
        (let [p-data (core/fressian-decode
                      (kv/get! *cache* {:k (core/partition-cache-key base-key pk)}))]
          (is (map? (:data p-data)))
          (is (uuid? (:watermark p-data)))))

      ;; No entity index stored — eliminated in favor of partition-fn on entity state
      (is (nil? (kv/get! *cache* {:k (core/suffix-key base-key ":pidx")}))))))

;; I4
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
(deftest partitioned-via-project-api
  (testing "partition-key in scope uses same cache key as full projection"
    (let [prev-registry @rmp/read-model-registry*]
      (try
        (rmp/register-read-model! :test/partitioned-items item-reducer
                                  {:events #{:test/item-created :test/item-updated :test/item-moved}
                                   :version 1
                                   :partition-fn item-partition-fn
                                   :entity-id-fn item-entity-id-fn})
        (append-item-events! 50 "a")
        (append-item-events! 50 "b")

        ;; Full projection via project API (no partition-key) — populates cache
        (let [full (rmp/project (make-context) :test/partitioned-items)]
          (is (= 100 (count full))))

        ;; Single partition via project API — must find cached data
        (let [result-a (rmp/project (make-context) :test/partitioned-items {:partition-key "a"})
              result-b (rmp/project (make-context) :test/partitioned-items {:partition-key "b"})]
          (is (= 50 (count result-a)))
          (is (= 50 (count result-b)))
          (is (every? #(= "a" (:bucket %)) (vals result-a)))
          (is (every? #(= "b" (:bucket %)) (vals result-b))))

        ;; Verify manifest exists at the correct cache key
        (let [base-key (core/format-scoped-key "partitioned-items" 1 test-tenant-id)
              manifest (core/fressian-decode (kv/get! *cache* {:k base-key}))]
          (is (true? (:partitioned manifest)))
          (is (= #{"a" "b"} (:partition-keys manifest))))

        (finally
          (reset! rmp/read-model-registry* prev-registry))))))

;; I11
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

;; I12
(deftest partitioned-cached-read-is-fast
  (testing "cached single-partition read with no new events completes under 50ms"
    (append-item-events! 50 "a")
    (append-item-events! 50 "b")
    ;; Full projection to populate cache
    (rmp/p (make-context) (make-partitioned-args))
    ;; Warm JIT
    (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))
    ;; Timed read
    (let [start (System/nanoTime)
          result (rmp/p (make-context) (make-partitioned-args {:partition-key "a"}))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (is (= 50 (count result)))
      (is (< elapsed-ms 50) (str "Cached read took " elapsed-ms "ms, expected < 50ms")))))

;; ===========================================================================
;; J. L1 In-Process Cache
;; ===========================================================================

;; J1
(deftest l1-hit-within-ttl-skips-all-io
  (testing "second project within TTL returns in < 1ms with no I/O"
    (append-test-events! 5)
    ;; First call — L1 miss, populates L1
    (rmp/p (make-context) (make-args {:l1-ttl-ms 5000}))
    ;; Second call — L1 hit within TTL
    (let [start (System/nanoTime)
          result (rmp/p (make-context) (make-args {:l1-ttl-ms 5000}))
          elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
      (is (= {:count 5} result))
      (is (< elapsed-ms 1) (str "L1 hit took " elapsed-ms "ms, expected < 1ms")))))

;; J2
(deftest l1-revalidates-after-ttl-with-no-new-events
  (testing "after TTL expires with no new events, L1 revalidates and returns cached state"
    (append-test-events! 5)
    (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))
    ;; Wait for TTL to expire
    (Thread/sleep 5)
    ;; Should check event store, find nothing new, return cached state
    (let [result (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))]
      (is (= {:count 5} result)))))

;; J3
(deftest l1-applies-new-events-after-ttl
  (testing "after TTL expires with new events, L1 applies them and updates"
    (append-test-events! 5)
    (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))
    (Thread/sleep 5)
    ;; Append new events
    (append-test-events! 3)
    (let [result (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))]
      (is (= {:count 8} result)))))

;; J4
(deftest l1-partitioned-caches-per-partition
  (testing "L1 caches partitions independently"
    (append-item-events! 30 "a")
    (append-item-events! 20 "b")
    ;; Full projection populates L2
    (rmp/p (make-context) (make-partitioned-args))
    ;; Read partitions — populates L1 per partition
    (rmp/p (make-context) (make-partitioned-args {:partition-key "a" :l1-ttl-ms 5000}))
    (rmp/p (make-context) (make-partitioned-args {:partition-key "b" :l1-ttl-ms 5000}))
    ;; Both should be L1 hits now
    (let [start-a (System/nanoTime)
          result-a (rmp/p (make-context) (make-partitioned-args {:partition-key "a" :l1-ttl-ms 5000}))
          ms-a (/ (- (System/nanoTime) start-a) 1e6)
          start-b (System/nanoTime)
          result-b (rmp/p (make-context) (make-partitioned-args {:partition-key "b" :l1-ttl-ms 5000}))
          ms-b (/ (- (System/nanoTime) start-b) 1e6)]
      (is (= 30 (count result-a)))
      (is (= 20 (count result-b)))
      (is (< ms-a 1) (str "L1 partition A took " ms-a "ms"))
      (is (< ms-b 1) (str "L1 partition B took " ms-b "ms")))))

;; J5
(deftest l1-stats-reports-entry-count
  (testing "l1-stats reflects entries after projections"
    (is (= 0 (:entries (rmp/l1-stats))))
    (append-test-events! 3)
    (rmp/p (make-context) (make-args))
    (is (pos? (:entries (rmp/l1-stats))))))

;; J6
(deftest l1-clear-removes-all-entries
  (testing "l1-clear! empties the cache"
    (append-test-events! 3)
    (rmp/p (make-context) (make-args))
    (is (pos? (:entries (rmp/l1-stats))))
    (rmp/l1-clear!)
    (is (= 0 (:entries (rmp/l1-stats))))))
