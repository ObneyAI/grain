(ns ai.obney.grain.read-model-processor-v2.interface-test
  "Read-model behavior, storage tests, and the LMDB pagination benchmark.
   Benchmark: -m ai.obney.grain.read-model-processor-v2.interface-test population report.edn."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.pprint :as pprint]
            [malli.core :as m]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.fressian-util.interface :as fressian-util]
            [ai.obney.grain.schema-util.interface :as schemas :refer [defschemas]])
  (:import [java.time OffsetDateTime]
           [java.io EOFException]
           [java.nio ByteBuffer BufferUnderflowException]
           [java.util UUID Arrays Random]
           [java.util.zip CRC32]
           [org.roaringbitmap RoaringBitmap]
           [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

;; interface test
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
           fressian-util/decode)))

;; ---------------------------------------------------------------------------
;; A. Cache Miss
;; ---------------------------------------------------------------------------

(deftest empty-event-store-returns-empty-state
  (test-fixture
    (fn []
      (is (= {} (rmp/p (make-context) (make-args)))))))

(deftest cache-miss-processes-all-events
  (test-fixture
    (fn []
      (append-test-events! 5)
      (is (= {:count 5} (rmp/p (make-context) (make-args)))))))

(deftest cache-miss-populates-cache-for-subsequent-hit
  (test-fixture
    (fn []
      (append-test-events! 5)
      (let [first-result  (rmp/p (make-context) (make-args))
            second-result (rmp/p (make-context) (make-args))]
        (is (= {:count 5} first-result))
        (is (= {:count 5} second-result))))))

(deftest fressian-round-trip-cache-shape
  (test-fixture
    (fn []
      (append-test-events! 5)
      (let [events (into [] (es/read *event-store* {:tenant-id test-tenant-id :types #{:test/counter-incremented}}))
            last-id (:event/id (last events))]
        (rmp/p (make-context) (make-args))
        (let [cached (read-cached :test/counter 1)]
          (is (= #{:data :watermark} (set (keys cached))))
          (is (= {:count 5} (:data cached)))
          (is (= last-id (:watermark cached))))))))

(deftest fressian-round-trip-after-threshold-update
  (test-fixture
    (fn []
      (append-test-events! 3)
      (rmp/p (make-context) (make-args))
      (append-test-events! 10)
      (rmp/p (make-context) (make-args))
      (let [events (into [] (es/read *event-store* {:tenant-id test-tenant-id :types #{:test/counter-incremented}}))
            last-id (:event/id (last events))
            cached  (read-cached :test/counter 1)]
        (is (= #{:data :watermark} (set (keys cached))))
        (is (= {:count 13} (:data cached)))
        (is (= last-id (:watermark cached)))))))

(deftest reducer-state-round-trips-offset-date-time-through-lmdb
  (test-fixture
    (fn []
      (testing "A reducer that returns OffsetDateTime in state survives the LMDB Fressian cache"
        (append-test-events! 1)
        (let [events (into [] (es/read *event-store*
                                       {:tenant-id test-tenant-id
                                        :types #{:test/counter-incremented}}))
              expected-ts (:event/timestamp (first events))
              timestamp-reducer (fn [state event]
                                  (assoc state :last-at (:event/timestamp event)))
              args (make-args {:f timestamp-reducer :name :test/ts-counter})]
          ;; First projection — L2 miss writes the reducer state to LMDB via fressian-util/encode.
          (rmp/p (make-context) args)
          ;; Direct cache inspection confirms the encode side wrote a decodable OffsetDateTime.
          (let [{:keys [data]} (read-cached :test/ts-counter 1)]
            (is (instance? OffsetDateTime (:last-at data)))
            (is (= expected-ts (:last-at data))))
          ;; End-to-end: dropping L1 forces the next p call to read from LMDB and decode.
          (rmp/l1-clear!)
          (let [result (rmp/p (make-context) args)]
            (is (instance? OffsetDateTime (:last-at result)))
            (is (= expected-ts (:last-at result)))))))))

;; ---------------------------------------------------------------------------
;; B. Cache Hit Threshold
;; ---------------------------------------------------------------------------

(deftest cache-hit-under-threshold-no-cache-update
  (test-fixture
    (fn []
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
          (is (= 0 @call-count)))))))

(deftest cache-hit-at-threshold-updates-cache
  (test-fixture
    (fn []
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
          (is (= 0 @call-count)))))))

;; ---------------------------------------------------------------------------
;; C. State Accumulation
;; ---------------------------------------------------------------------------

(deftest state-accumulates-across-incremental-calls
  (test-fixture
    (fn []
      (append-test-events! 3)
      (is (= {:count 3} (rmp/p (make-context) (make-args))))

      (append-test-events! 4)
      (is (= {:count 7} (rmp/p (make-context) (make-args))))

      (append-test-events! 12)
      (is (= {:count 19} (rmp/p (make-context) (make-args))))

      (append-test-events! 2)
      (is (= {:count 21} (rmp/p (make-context) (make-args)))))))

;; ---------------------------------------------------------------------------
;; D. Isolation & Filtering
;; ---------------------------------------------------------------------------

(deftest separate-cache-per-name-version
  (test-fixture
    (fn []
      (append-test-events! 5)
      (let [result-a (rmp/p (make-context) (make-args {:name :test/counter-a :version 1}))
            result-b (rmp/p (make-context) (make-args {:name :test/counter-b :version 1}))
            result-v2 (rmp/p (make-context) (make-args {:name :test/counter-a :version 2}))]
        (is (= {:count 5} result-a))
        (is (= {:count 5} result-b))
        (is (= {:count 5} result-v2))))))

(deftest qualified-read-model-names-isolate-registered-projections
  (test-fixture
    (fn []
      (let [prev-registry @rmp/read-model-registry*]
        (try
          (rmp/register-read-model! :alpha/shared
                                    counter-reducer
                                    {:events #{:test/counter-incremented}
                                     :version 1
                                     :l1-ttl-ms 0})
          (rmp/register-read-model! :beta/shared
                                    (fn [state _event]
                                      (update state :total (fnil + 0) 10))
                                    {:events #{:test/counter-incremented}
                                     :version 1
                                     :l1-ttl-ms 0})
          (append-test-events! 3)

          (is (= {:count 3} (rmp/project (make-context) :alpha/shared)))
          (is (= {:total 30} (rmp/project (make-context) :beta/shared)))

          ;; Force both projections through the persistent tier as well as L1.
          (rmp/l1-clear!)
          (is (= {:total 30} (rmp/project (make-context) :beta/shared)))
          (is (= {:count 3} (rmp/project (make-context) :alpha/shared)))

          (let [alpha-key (core/format-scoped-key :alpha/shared 1 test-tenant-id)
                beta-key  (core/format-scoped-key :beta/shared 1 test-tenant-id)]
            (is (not= (seq alpha-key) (seq beta-key)))
            (is (= {:count 3}
                   (:data (fressian-util/decode (kv/get! *cache* {:k alpha-key})))))
            (is (= {:total 30}
                   (:data (fressian-util/decode (kv/get! *cache* {:k beta-key}))))))
          (finally
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest query-filters-events-correctly
  (test-fixture
    (fn []
      (append-test-events! 5 :test/counter-incremented)
      (append-test-events! 3 :test/other-event)
      (is (= {:count 5}
             (rmp/p (make-context)
                    (make-args {:query {:types #{:test/counter-incremented}}})))))))

;; ---------------------------------------------------------------------------
;; E. Reducer Contract
;; ---------------------------------------------------------------------------

(deftest reducer-receives-full-events
  (test-fixture
    (fn []
      (let [captured (atom [])
            capturing-reducer (fn [state event]
                                (swap! captured conj event)
                                (update state :count (fnil inc 0)))]
        (append-test-events! 3)
        (rmp/p (make-context) (make-args {:f capturing-reducer}))

        (is (= 3 (count @captured)))
        (is (every? #(= :test/counter-incremented (:event/type %)) @captured))
        (is (every? :event/id @captured))
        (is (every? :event/timestamp @captured))))))

;; ---------------------------------------------------------------------------
;; F. defreadmodel Macro & Registry
;; ---------------------------------------------------------------------------

(defn counter-reducer-multi [state event]
  (case (:event/type event)
    :test/counter-incremented (update state :count (fnil inc 0))
    state))

(deftest defreadmodel-creates-function-and-registers
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest read-model-schema-is-validated-at-registration
  (test-fixture
    (fn []
      (let [previous @rmp/read-model-registry*]
        (try
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Read model state schema is malformed"
               (rmp/register-read-model! :test/invalid-schema identity
                                         {:schema [:map [:count :not-a-schema]]})))
          (is (not (contains? @rmp/read-model-registry* :test/invalid-schema)))
          (finally
            (reset! rmp/read-model-registry* previous)))))))

(deftest defreadmodel-with-docstring
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest defreadmodel-without-opts
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest global-read-model-registry-returns-snapshot
  (test-fixture
    (fn []
      (let [prev-registry @rmp/read-model-registry*]
        (try
          (rmp/register-read-model! :test/dummy identity {:events #{:test/e1} :version 1})
          (let [reg (rmp/global-read-model-registry)]
            (is (map? reg))
            (is (contains? reg :test/dummy)))
          (finally
            (reset! rmp/read-model-registry* prev-registry)))))))

;; ---------------------------------------------------------------------------
;; G. Scoped Projections
;; ---------------------------------------------------------------------------

(deftest cross-tenant-cache-isolation
  (test-fixture
    (fn []
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
          (is (= {:count 7} (:data cached-b))))))))

(deftest cross-tenant-scoped-cache-isolation
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest scoped-projection-with-tags
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest cache-isolation-between-scopes
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest scoped-projection-with-custom-queries
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

(deftest vector-query-cache-hit
  (test-fixture
    (fn []
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
          (is (= {:count 10} second-result)))))))

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
          fressian-util/decode))

(defn read-segment
  "Read a single segment from LMDB."
  [base-key idx]
  (let [seg-k (let [suffix (.getBytes (format ":s%d" idx))
                    result (byte-array (+ (alength ^bytes base-key) (alength suffix)))]
                (System/arraycopy base-key 0 result 0 (alength ^bytes base-key))
                (System/arraycopy suffix 0 result (alength ^bytes base-key) (alength suffix))
                result)]
    (some-> (kv/get! *cache* {:k seg-k})
            fressian-util/decode)))

;; H1
(deftest segmented-cache-round-trip
  (test-fixture
    (fn []
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
            (is (= result merged))))))))

;; H2
(deftest segmented-cache-incremental-update
  (test-fixture
    (fn []
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
            (is (uuid? (:watermark manifest)))))))))

;; H3
(deftest segmented-incremental-writes-only-changed-segments
  (test-fixture
    (fn []
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
              (is (>= (count unchanged) (- 64 10))))))))))

;; H4
(deftest small-model-stays-monolithic
  (test-fixture
    (fn []
      (testing "read models under threshold use legacy single-entry format"
        (append-entity-events! 100)
        (let [result (rmp/p (make-context) (make-entity-args))
              cached (read-raw-cache "test-entities" 1 test-tenant-id)]
          (is (= 100 (count result)))
          ;; Legacy format: has :data and :watermark, no :segmented
          (is (contains? cached :data))
          (is (contains? cached :watermark))
          (is (not (contains? cached :segmented))))))))

;; H5
(deftest migration-monolithic-to-segmented
  (test-fixture
    (fn []
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
            (is (= 15000 (count merged)))))))))

;; H6
(deftest segmented-cache-with-scope
  (test-fixture
    (fn []
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
              (is (empty? (set/intersection (set (keys result-a))
                                                    (set (keys result-b)))))))
          (finally
            (reset! rmp/read-model-registry* prev-registry)))))))

;; H7
(deftest segmented-cache-cross-tenant-isolation
  (test-fixture
    (fn []
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
                                        (set (keys result-b))))))))))

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
           :l1-ttl-ms    0}
          overrides)))

;; I1
(deftest partitioned-single-partition-read
  (test-fixture
    (fn []
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
          (is (empty? (set/intersection (set (keys result-a)) (set (keys result-b))))))))))

;; I2
(deftest partitioned-full-merge
  (test-fixture
    (fn []
      (testing "project without partition-key returns all data merged"
        (append-item-events! 50 "a")
        (append-item-events! 50 "b")
        (let [result (rmp/p (make-context) (make-partitioned-args))]
          (is (= 100 (count result)))
          (is (= 50 (count (filter #(= "a" (:bucket %)) (vals result)))))
          (is (= 50 (count (filter #(= "b" (:bucket %)) (vals result))))))))))

;; I3
(deftest partitioned-cache-structure
  (test-fixture
    (fn []
      (testing "partitioned read model stores manifest, partitions, and index in LMDB"
        (append-item-events! 30 "x")
        (append-item-events! 20 "y")
        (append-item-events! 10 "z")
        (rmp/p (make-context) (make-partitioned-args))

        (let [base-key (core/format-scoped-key "test-items" 1 test-tenant-id)
              manifest (fressian-util/decode (kv/get! *cache* {:k base-key}))]
          ;; Manifest structure
          (is (true? (:partitioned manifest)))
          (is (= #{"x" "y" "z"} (:partition-keys manifest)))
          (is (uuid? (:global-watermark manifest)))

          ;; Each partition readable
          (doseq [pk ["x" "y" "z"]]
            (let [p-data (fressian-util/decode
                          (kv/get! *cache* {:k (core/partition-cache-key base-key pk)}))]
              (is (map? (:data p-data)))
              (is (uuid? (:watermark p-data)))))

          ;; Entity index stored for cross-partition move detection
          (let [idx (fressian-util/decode
                     (kv/get! *cache* {:k (core/suffix-key base-key ":eidx")}))]
            (is (= 60 (count idx)))
            (is (every? #(contains? #{"x" "y" "z"} (val %)) idx))))))))

(deftest qualified-read-model-names-isolate-partitioned-cache-structures
  (test-fixture
    (fn []
      (let [prev-registry @rmp/read-model-registry*
            beta-reducer (fn [state event]
                           (let [state (item-reducer state event)]
                             (update-vals state #(assoc % :owner :beta))))]
        (try
          (doseq [[rm-name reducer] [[:alpha/items item-reducer]
                                     [:beta/items beta-reducer]]]
            (rmp/register-read-model! rm-name reducer
                                      {:events #{:test/item-created :test/item-updated :test/item-moved}
                                       :version 1
                                       :partition-fn item-partition-fn
                                       :entity-id-fn item-entity-id-fn
                                       :l1-ttl-ms 0}))
          (append-item-events! 3 "x")

          (let [alpha-state (rmp/project (make-context) :alpha/items)
                beta-state  (rmp/project (make-context) :beta/items)]
            (is (= 3 (count alpha-state)))
            (is (every? #(nil? (:owner %)) (vals alpha-state)))
            (is (= 3 (count beta-state)))
            (is (every? #(= :beta (:owner %)) (vals beta-state))))

          (rmp/l1-clear!)
          (is (every? #(= :beta (:owner %))
                      (vals (rmp/project (make-context) :beta/items {:partition-key "x"}))))
          (is (every? #(nil? (:owner %))
                      (vals (rmp/project (make-context) :alpha/items {:partition-key "x"}))))

          (let [alpha-key (core/format-scoped-key :alpha/items 1 test-tenant-id)
                beta-key  (core/format-scoped-key :beta/items 1 test-tenant-id)
                alpha-manifest (fressian-util/decode (kv/get! *cache* {:k alpha-key}))
                beta-manifest  (fressian-util/decode (kv/get! *cache* {:k beta-key}))]
            (is (not= (seq alpha-key) (seq beta-key)))
            (is (= #{"x"} (:partition-keys alpha-manifest)))
            (is (= #{"x"} (:partition-keys beta-manifest)))
            (doseq [base-key [alpha-key beta-key]]
              (is (some? (kv/get! *cache* {:k (core/partition-cache-key base-key "x")})))
              (is (= 3 (count (fressian-util/decode
                               (kv/get! *cache* {:k (core/suffix-key base-key ":eidx")})))))))
          (finally
            (reset! rmp/read-model-registry* prev-registry)))))))

;; I4
(deftest partitioned-incremental-single-partition
  (test-fixture
    (fn []
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
          (is (= 50 (count result-b))))))))

;; I5
(deftest partitioned-cross-partition-move
  (test-fixture
    (fn []
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

    ))))))

;; I6
(deftest partitioned-update-within-partition
  (test-fixture
    (fn []
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
              (is (= 42 (:value (get result-a updated-id)))))))))))

;; I7
(deftest partitioned-without-entity-id-fn
  (test-fixture
    (fn []
      (testing "partitioning works without entity-id-fn"
        (append-item-events! 30 "a")
        (append-item-events! 20 "b")
        (let [args (make-partitioned-args {:entity-id-fn nil})
              result (rmp/p (make-context) args)]
          (is (= 50 (count result))))))))

;; I8
(deftest partitioned-with-scope
  (test-fixture
    (fn []
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
            (reset! rmp/read-model-registry* prev-registry)))))))

;; I9
(deftest partitioned-cross-tenant-isolation
  (test-fixture
    (fn []
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
          (is (empty? (set/intersection (set (keys result-a)) (set (keys result-b))))))))))

;; I10
(deftest partitioned-via-project-api
  (test-fixture
    (fn []
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
            (let [base-key (core/format-scoped-key :test/partitioned-items 1 test-tenant-id)
                  manifest (fressian-util/decode (kv/get! *cache* {:k base-key}))]
              (is (true? (:partitioned manifest)))
              (is (= #{"a" "b"} (:partition-keys manifest))))

            (finally
              (reset! rmp/read-model-registry* prev-registry))))))))

;; I11
(deftest partitioned-cross-partition-move-via-single-partition-read
  (test-fixture
    (fn []
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
              (is (= "b" (:bucket (get result-b moved-id)))))))))))

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
          :l1-ttl-ms 0}
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
  (test-fixture
    (fn []
      (testing "reading destination partition first detects move without reading source"
        (let [item-id (create-compound-item! "east" "a")]
          (rmp/p (make-context) (make-compound-args {}))
          (move-compound-item! item-id "b")

          (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "b"]}))]
            (is (contains? result item-id) "Entity should appear in destination partition")
            (is (= "b" (:bucket (get result item-id)))))

          (let [result (rmp/p (make-context) (make-compound-args {:partition-key ["east" "a"]}))]
            (is (not (contains? result item-id)) "Entity should be removed from source partition")))))))

(deftest partitioned-l1-syncs-after-cross-partition-move
  (test-fixture
    (fn []
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
            (is (not (contains? result item-id)) "Entity should be gone from source")))))))

(deftest partitioned-l1-removes-entity-after-move-out
  (test-fixture
    (fn []
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
            (is (empty? result) "Source partition should be empty after move out")))))))

(deftest partitioned-l1-consistent-after-multiple-reads
  (test-fixture
    (fn []
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
              (is (= "b" (:bucket (get result item-id)))))))))))

;; I12
(deftest partitioned-cached-read-is-fast
  (test-fixture
    (fn []
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
          (is (< elapsed-ms 50) (str "Cached read took " elapsed-ms "ms, expected < 50ms")))))))

;; ===========================================================================
;; J. L1 In-Process Cache
;; ===========================================================================

;; J1
(deftest l1-hit-within-ttl-skips-all-io
  (test-fixture
    (fn []
      (testing "second project within TTL returns in < 1ms with no I/O"
        (append-test-events! 5)
        ;; First call — L1 miss, populates L1
        (rmp/p (make-context) (make-args {:l1-ttl-ms 5000}))
        ;; Second call — L1 hit within TTL
        (let [start (System/nanoTime)
              result (rmp/p (make-context) (make-args {:l1-ttl-ms 5000}))
              elapsed-ms (/ (- (System/nanoTime) start) 1e6)]
          (is (= {:count 5} result))
          (is (< elapsed-ms 1) (str "L1 hit took " elapsed-ms "ms, expected < 1ms")))))))

;; J2
(deftest l1-revalidates-after-ttl-with-no-new-events
  (test-fixture
    (fn []
      (testing "after TTL expires with no new events, L1 revalidates and returns cached state"
        (append-test-events! 5)
        (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))
        ;; Wait for TTL to expire
        (Thread/sleep 5)
        ;; Should check event store, find nothing new, return cached state
        (let [result (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))]
          (is (= {:count 5} result)))))))

;; J3
(deftest l1-applies-new-events-after-ttl
  (test-fixture
    (fn []
      (testing "after TTL expires with new events, L1 applies them and updates"
        (append-test-events! 5)
        (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))
        (Thread/sleep 5)
        ;; Append new events
        (append-test-events! 3)
        (let [result (rmp/p (make-context) (make-args {:l1-ttl-ms 1}))]
          (is (= {:count 8} result)))))))

;; J4
(deftest l1-partitioned-caches-per-partition
  (test-fixture
    (fn []
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
          (is (< ms-b 1) (str "L1 partition B took " ms-b "ms")))))))

;; J5
(deftest l1-stats-reports-entry-count
  (test-fixture
    (fn []
      (testing "l1-stats reflects entries after projections"
        (is (= 0 (:entries (rmp/l1-stats))))
        (append-test-events! 3)
        (rmp/p (make-context) (make-args))
        (is (pos? (:entries (rmp/l1-stats))))))))

;; J6
(deftest l1-clear-removes-all-entries
  (test-fixture
    (fn []
      (testing "l1-clear! empties the cache"
        (append-test-events! 3)
        (rmp/p (make-context) (make-args))
        (is (pos? (:entries (rmp/l1-stats))))
        (rmp/l1-clear!)
        (is (= 0 (:entries (rmp/l1-stats))))))))

;; ==========================================================================
;; Partitioned: Unsafe reducer resilience
;; ==========================================================================
;;
;; Reducers that don't guard against nil entity state should not crash the
;; partition relevance filter. The framework calls (f {} event) speculatively
;; to check if a new event would create an entity for a partition. Non-creation
;; events will naturally fail when accessing fields on a nil entity — the
;; framework must handle this gracefully.

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
           :l1-ttl-ms    0}
          overrides)))

;; I-unsafe-1
(deftest partitioned-unsafe-reducer-relevance-filter
  (test-fixture
    (fn []
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
              (is (= 4000 (:total (get result new-id)))))))))))

;; I-unsafe-2
(deftest partitioned-unsafe-reducer-new-entity-in-batch
  (test-fixture
    (fn []
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
            (is (= 2000 (:total (get result order-id))))))))))

;; I-unsafe-3
(deftest partitioned-unsafe-reducer-full-rebuild
  (test-fixture
    (fn []
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
            (is (= 7500 (:total (get result order-id))))))))))

(deftest colliding-tenant-hashes-do-not-share-resident-or-persisted-state
  (test-fixture
    (fn []
      (let [a (java.util.UUID. 0 0) b (java.util.UUID. 1 1)
            args (make-args {:l1-ttl-ms 60000})]
        (is (not= a b))
        (is (= (hash a) (hash b)))
        (doseq [[tenant n] [[a 1] [b 3]]]
          (is (nil? (:cognitect.anomalies/category
                     (es/append *event-store*
                       {:tenant-id tenant
                        :events (vec (repeatedly n #(es/->event {:type :test/counter-incremented})))})))))
        (doseq [round (range 2)]
          (when (pos? round) (rmp/l1-clear!))
          (is (= {:count 1} (rmp/p (assoc (make-context) :tenant-id a) args)))
          (is (= {:count 3} (rmp/p (assoc (make-context) :tenant-id b) args))))))))

(deftest colliding-query-scopes-do-not-share-resident-or-persisted-state
  (test-fixture
    (fn []
      (let [a (java.util.UUID. 0 0) b (java.util.UUID. 1 1)
            scope #(hash-map :tags #{[:bucket %]})
            args #(make-args {:scope (scope %)
                              :query {:types #{:test/counter-incremented} :tags #{[:bucket %]}}
                              :l1-ttl-ms 60000})]
        (is (= (hash (scope a)) (hash (scope b))))
        (append-tagged-events! 1 :test/counter-incremented #{[:bucket a]})
        (append-tagged-events! 3 :test/counter-incremented #{[:bucket b]})
        (doseq [round (range 2)]
          (when (pos? round) (rmp/l1-clear!))
          (is (= {:count 1} (rmp/p (make-context) (args a))))
          (is (= {:count 3} (rmp/p (make-context) (args b)))))))))

(deftest colliding-partition-hashes-preserve-slices-and-full-merge
  (test-fixture
    (fn []
      (let [a (java.util.UUID. 0 0) b (java.util.UUID. 1 1)
            ids-a (set (append-item-events! 4 "a"))
            ids-b (set (append-item-events! 7 "b"))
            args (make-partitioned-args {:partition-fn #(get {"a" a "b" b} (:bucket %))
                                        :l1-ttl-ms 60000})]
        (is (= (hash a) (hash b)))
        (is (= (set/union ids-a ids-b) (set (keys (rmp/p (make-context) args)))))
        (doseq [round (range 2)]
          (when (pos? round) (rmp/l1-clear!))
          (is (= ids-a (set (keys (rmp/p (make-context) (assoc args :partition-key a))))))
          (is (= ids-b (set (keys (rmp/p (make-context) (assoc args :partition-key b)))))))
        (rmp/l1-clear!)
        (is (= (set/union ids-a ids-b) (set (keys (rmp/p (make-context) args)))))))))

(deftest cache-identities-ignore-unordered-construction-and-print-settings
  (test-fixture
    (fn []
      (let [a (java.util.UUID. 0 0) b (java.util.UUID. 1 1)
            left (array-map :tags (conj #{} [:bucket a] [:bucket b]) :types #{:test/counter-incremented})
            right (array-map :types #{:test/counter-incremented} :tags (conj #{} [:bucket b] [:bucket a]))
            cache-key #(vec (core/partition-cache-key (byte-array 0) %))]
        (is (= left right))
        (is (= (cache-key left) (cache-key right)))
        (is (= (cache-key left)
               (binding [*print-length* 0 *print-level* 0] (cache-key left))))
        (is (not= (cache-key a) (cache-key b)))
        (doseq [[x y] [[1 1N] [1 1M] [[1 2] '(1 2)] [0.0 -0.0] [1M 1.0M]
                       [#{} []] [{} []] [[:map []] {}]]]
          (is (= (= x y) (= (cache-key x) (cache-key y)))))))))

(deftest ambiguous-old-cache-addresses-are-not-reused
  (test-fixture
    (fn []
      (let [old-key (.getBytes (format "%s-%s-%s" :test/counter 1
                                      (Integer/toHexString (hash test-tenant-id))))]
        (kv/put! *cache* {:k old-key :v (fressian-util/encode {:data {:count 999} :watermark nil})})
        (append-test-events! 1)
        (is (= {:count 1} (rmp/p (make-context) (make-args))))
        (rmp/l1-clear!)
        (is (= {:count 1} (rmp/p (make-context) (make-args))))))))

;; fold key test
(defn- fold-key-read-physical-key! [^ByteBuffer in]
  (case (bit-and 255 (.get in))
    1 nil 2 false 3 true
    16 (let [out (StringBuilder.)
             read-unit (fn [] (let [b (bit-and 255 (.get in))]
                               (if (zero? b)
                                 (case (bit-and 255 (.get in)) 0 -1 255 0
                                   (throw (ex-info "Invalid ordered string escape" {}))) b)))]
         (loop []
           (let [hi (read-unit)]
             (if (= -1 hi) (.toString out)
               (let [lo (read-unit)]
                 (when (= -1 lo) (throw (ex-info "Truncated ordered character" {})))
                 (.append out (char (+ (bit-shift-left hi 8) lo))) (recur))))))
    32 (bit-xor (.getLong in) Long/MIN_VALUE)
    48 (UUID. (.getLong in) (.getLong in))
    56 (keyword (subs (fold-key-read-physical-key! in) 1))
    64 (loop [out []]
         (.mark in)
         (if (zero? (.get in)) out
           (do (.reset in) (recur (conj out (fold-key-read-physical-key! in))))))
    (throw (ex-info "Invalid ordered key type" {}))))

(defn fold-key-decode-physical
  "Test oracle for physical key round-trips; production only decodes metadata."
  ([^bytes value] (fold-key-decode-physical value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (try
     (fold-key-read-physical-key! (ByteBuffer/wrap value offset length))
     (catch BufferUnderflowException _ (throw (EOFException.))))))

(defn fold-key-encoded-order [xs encode]
  (sort #(java.util.Arrays/compareUnsigned ^bytes (encode %1) ^bytes (encode %2)) xs))

(deftest joined-storage-keys-preserve-bytes-and-own-their-buffer
  (let [a (byte-array (map unchecked-byte (range 256)))
        b (core/key-encode ["a\u0000b" :catalog/id 42])
        expected (into (vec a) b)
        joined (core/key-join a (byte-array 0) b)]
    (is (= expected (vec joined)))
    (is (= [] (vec (core/key-join))))
    (is (= (vec a) (vec (core/key-join a))))
    (aset-byte a 0 (byte 99))
    (aset-byte b 0 (byte 99))
    (is (= expected (vec joined)))))

(deftest prefix-upper-bounds
  (doseq [[prefix end] [[[0] [1]] [[1 -1] [2]] [[1 2 -1] [1 3]]
                        [[-2 -1] [-1]] [[-1] nil] [[-1 -1] nil] [[] nil]]]
    (is (= end (some-> (core/key-prefix-end (byte-array prefix)) vec)))))

(deftest ordered-keys-preserve-scalar-and-tuple-order
  (doseq [xs [[Long/MIN_VALUE -1000 -1 0 1 255 256 65536 Long/MAX_VALUE]
              ["" "\u0000" "\u0000\u0000" "a" "a\u0000" "aa" "b" "\u00ff" "\u0100" "\uffff"]
              [["a" -1] ["a" 0] ["a" 1] ["aa" -1] ["b" -100]]]]
    (is (= (sort xs) (fold-key-encoded-order (reverse xs) core/key-encode)))
    (is (= (reverse (sort xs)) (fold-key-encoded-order xs (comp core/key-descending core/key-encode))))))

(deftest encodings-are-distinct-and-self-delimiting
  (let [xs [nil false true "" "a" "a\u0000" "ab" "a/b" :a/b 0 1
            [] [nil] [false] ["a"] ["a" "b"] ["ab"]
            #uuid "00000000-0000-0000-0000-000000000001"]
        encodings (mapv #(vec (core/key-encode %)) xs)]
    (is (= (count xs) (count (set encodings))))
    (doseq [a encodings b encodings :when (not= a b)]
      (is (not= a (take (count a) b)))))
  (doseq [unsupported [1.1 1/2 (inc (bigint Long/MAX_VALUE)) {} #{} '(1)]]
    (is (thrown? clojure.lang.ExceptionInfo (core/key-encode unsupported)))))

(deftest ordered-decoding-preserves-values-and-field-boundaries
  (doseq [value [nil false true Long/MIN_VALUE -1 0 1 Long/MAX_VALUE
                 #uuid "00000000-0000-0000-0000-000000000000"
                 #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
                 :catalog/course :plain "" "a\u0000b" "\ud83d\ude00" "\ud800"
                 [] [nil false ["nested" 42 [:catalog/course true]]]
                 (String. (char-array (map char (range 65536))))]]
    (let [encoded (core/key-encode value)
          data (core/key-join (byte-array [99 98 97]) encoded (core/key-encode "following field"))]
      (is (= value (fold-key-decode-physical encoded)))
      (is (= value (fold-key-decode-physical data 3 (alength encoded))))
      (is (thrown? java.io.EOFException (fold-key-decode-physical data 3 (dec (alength encoded))))
          "A truncated field must not consume its following field")
      (let [decoded (fold-key-decode-physical data 3 (alength encoded))]
        (java.util.Arrays/fill data (byte 0))
        (is (= value decoded) "Results do not retain the mutable input view"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid ordered string escape"
                       (fold-key-decode-physical (byte-array [16 0 1]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Truncated ordered character"
                       (fold-key-decode-physical (byte-array [16 1 0 0])))))

(deftest string-encoding-preserves-stored-bytes
  ;; These are the existing wire bytes, expressed as unsigned decimal values.
  (doseq [[value expected] [["" [16 0 0]]
                           ["Ada" [16 0 255 65 0 255 100 0 255 97 0 0]]
                           ["\u0000" [16 0 255 0 255 0 0]]
                           ["\u00ff\u0100" [16 0 255 255 1 0 255 0 0]]
                           ["\ud83d\ude00" [16 216 61 222 0 255 0 0]]
                           ["\ud800" [16 216 0 255 0 0]]
                           ["\udfff" [16 223 255 0 0]]]]
    (is (= expected (mapv #(bit-and 255 %) (core/key-encode value)))))
  ;; Cover every UTF-16 code unit, including unpaired surrogates. The digest
  ;; freezes the old encoding; the length also checks zero-byte escaping.
  (let [value (String. (char-array (map char (range 65536))))
        encoded (core/key-encode value)]
    (is (= 131587 (alength ^bytes encoded)))
    (is (= "039b63a1afbedb79d5cd86d3e4fd896ec8d041380f1f6d7850fd21f1d1957d76"
           (.formatHex (java.util.HexFormat/of)
                       (.digest (java.security.MessageDigest/getInstance "SHA-256") encoded))))))

;; keyed test
(defschemas keyed-schemas {:keyed/change [:map] :keyed/other [:map]})
(def ^:dynamic *keyed-ctx* nil)
(def ^:dynamic *keyed-directory* nil)
(def ^:dynamic *keyed-cache-handle* nil)

(defn keyed-fixture [f]
  (let [dir (str "/tmp/grain-keyed-test-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "folds"
                                             :map-size (* 64 1024 1024)}))
        registry (rmp/global-read-model-registry)]
    (binding [*keyed-ctx* {:cache cache :event-store store :tenant-id (random-uuid)}
              *keyed-directory* dir
              *keyed-cache-handle* (atom cache)]
      (try (f)
           (finally
             (reset! rmp/read-model-registry* registry)
             (when-let [cache @*keyed-cache-handle*] (kv/stop cache)) (es/stop store)
             (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true)))))))


(defn keyed-register! [step opts]
  (let [name (keyword "keyed-test" (str (random-uuid)))]
    (rmp/register-read-model! name step (merge {:events #{:keyed/change} :version 1 :key :owner} opts))
    name))

(defn keyed-append!
  ([bodies] (keyed-append! *keyed-ctx* bodies))
  ([ctx bodies]
   (es/append (:event-store ctx)
              {:tenant-id (:tenant-id ctx)
               :events (mapv #(es/->event {:type :keyed/change :body %}) bodies)})))

(defn keyed-history-step [state event]
  (update state :history (fnil conj []) (:delta event)))
(defn keyed-total [state] {:total (reduce + 0 (:history state))})
(defn keyed-results [ctx model]
  (into {} (map (juxt :key :value)) (rmp/values ctx model)))

(defn keyed-stored-bytes []
  (kv/transact (:cache *keyed-ctx*) :read
    #((:reduce %) {} (fn [out {:keys [k v]}] (assoc out (vec k) (vec v))) {})))

(defn keyed-materialized-bytes []
  (let [stored (keyed-stored-bytes)
        registry? (fn [[k _]] (= [75 82 57] (vec (take 3 k))))
        registry (into {} (filter registry?) stored)]
    (when (seq registry)
      (let [decode #(fressian-util/decode (byte-array %))
            counter (decode (get registry [75 82 57 0]))
            records (fn [kind]
                      (mapv (fn [[_ v]] (decode v)) (filter (fn [[k _]] (= kind (nth k 3))) registry)))
            forward (records 1) reverse (records 2) n (:next-id counter)
            identities (mapv :identity forward)]
        (is (= #{:next-id} (set (keys counter))))
        (is (= (+ 1 (* 2 n)) (count registry)))
        (is (= n (count (set identities))))
        (is (= (set (range n)) (set (map :ordinal forward))))
        (is (= (frequencies forward) (frequencies reverse)))
        (is (every? #(and (= #{:identity :ordinal} (set (keys %)))
                          (= 3 (count (:identity %)))
                          (uuid? (nth (:identity %) 0))
                          (qualified-keyword? (nth (:identity %) 1))
                          (pos-int? (nth (:identity %) 2))) forward))
        (let [addresses (core/namespace-resolve! (:cache *keyed-ctx*) identities)]
          (doseq [{:keys [identity ordinal]} forward]
            (is (= (into [75 70 57]
                         (.array (doto (java.nio.ByteBuffer/allocate 4) (.putInt (int ordinal)))))
                   (vec (get addresses identity)))))
          (is (= stored (keyed-stored-bytes))))))
    (into {} (remove registry?) stored)))

(defn keyed-page-keys [ctx name options]
  (mapv :key (:entries (rmp/page ctx name options))))

(deftest declaration-options-must-have-an-effect
  (keyed-fixture
    (fn []
      (doseq [[opts invalid] [[{:key :owner :include some?} #{:include}]
                              [{:key :owner :index {:name :name}} #{:index}]
                              [{:key :owner :l1-ttl-ms 1000} #{:l1-ttl-ms}]
                              [{:indexes {:name :name}} #{:indexes}]
                              [{:initial []} #{:initial}]
                              [{:result identity} #{:result}]
                              [{:state-schema :map} #{:state-schema}]
                              [{:key :owner :ranked-indexes #{:identity}} #{:ranked-indexes}]
                              [{:key :owner :schema :map} #{:schema}]
                              [{:route (constantly []) :schema :map} #{:schema}]
                              [{:key :owner :state-schema :map :schema :map} #{:schema}]
                              [{:filtered-indexes #{:identity}} #{:filtered-indexes}]]]
        (let [name (keyword "invalid" (str (random-uuid)))
              error (try (rmp/register-read-model! name identity (merge {:events #{:keyed/change}} opts))
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= invalid (:options error)))
          (is (not (contains? (rmp/global-read-model-registry) name))))))))

(deftest filtered-indexes-use-the-declared-stored-order
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:name]))
                            {:indexes {:name :name} :filtered-indexes #{:name}})]
        (keyed-append! [{:owner "a" :name "Alice"} {:owner "b" :name "Bob"} {:owner "c" :name "Chris"}])
        (is (= ["c"] (:keys (rmp/filtered-key-page *keyed-ctx* name ["a" "c"] {:index :name :page 2 :limit 1}))))
        (is (= ["a" "c"] (:keys (rmp/filtered-key-page *keyed-ctx* name ["a" "c"] {:index :name}))))))))

(deftest option-errors-explain-the-model-contract
  (keyed-fixture
    (fn []
      (doseq [[opts unknown inapplicable correction]
              [[{:key :owner :index {}} #{:index} #{} #"Supported options"]
               [{:indexes {}} #{} #{:indexes} #"exactly one of :key or :route"]
               [{:key :owner :l1-ttl-ms 1} #{} #{:l1-ttl-ms} #"ordinary models read with project"]]]
        (let [error (try (rmp/register-read-model! :test/customer identity
                                                  (assoc opts :events #{:keyed/change}))
                         (catch clojure.lang.ExceptionInfo e e))
              data (ex-data error)]
          (is (= :test/customer (:model data)))
          (is (= unknown (:unknown-options data)))
          (is (= inapplicable (:inapplicable-options data)))
          (is (some #{:events} (:supported-options data)))
          (is (re-find correction (or (:hint data) "")))
          (is (re-find #":test/customer" (or (ex-message error) ""))))))))

(deftest read-only-index-composition
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:group :included?]))
                            {:include? :included? :indexes {:group :group}})
            observed (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))]
        (keyed-append! [{:owner "a" :group nil :included? true}
                  {:owner "b" :group "same" :included? true}
                  {:owner "d" :group "same" :included? true}
                  {:owner "excluded" :group "else" :included? false}])
        (rmp/read-session ctx [name]
          (fn [ctx]
            (is (= [{:value nil :total 1} {:value "same" :total 2}] (rmp/index-groups ctx name :group)))
            (reset! observed [])
            (is (= [{:value "same" :total 2} {:value nil :total 1}]
                   (rmp/index-groups ctx name :group ["same" nil "absent" "same"])))
            (is (= 3 (count (filter #(= :index-group (:operation %)) @observed))))
            (is (= [] (rmp/index-groups ctx name :group [])))
            (is (= [{:value "same" :total 2}] (rmp/index-groups ctx name :group #{"same" "absent"})))
            (doseq [direction [:asc :desc]]
              (let [selection {:index :group :equals "same" :direction direction}]
                (reset! observed [])
                (is (= ["b"] (rmp/index-keys ctx name (assoc selection :limit 1))))
                (is (= ["b" "d"] (rmp/index-keys ctx name (assoc selection :offset 0 :limit 10))))
                (is (= [] (rmp/index-keys ctx name (assoc selection :equals "missing"))))
                (is (= 3 (count (filter #(= :index-entry (:operation %)) @observed))))
                (is (empty? (filter #(= :rank-entry (:operation %)) @observed))
                    "A group's first slice needs no rank navigation")
                (is (= ["d"] (rmp/index-keys ctx name (assoc selection :offset 1 :limit 1))))
                (is (= [] (rmp/index-keys ctx name (assoc selection :offset 2))))
                (is (= {:rank 1 :present? true} (rmp/index-rank ctx name selection "d")))
                (is (= {:rank 1 :present? false} (rmp/index-rank ctx name selection "c")))
                (is (= {:rank 0 :present? false} (rmp/index-rank ctx name (assoc selection :equals "missing") "c")))
                (is (empty? (filter #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))))))
        (keyed-append! [{:owner "b" :group nil :included? true} {:owner "a" :group nil :included? false}])
        (is (= [{:value nil :total 1} {:value "same" :total 1}] (rmp/index-groups ctx name :group)))
        (is (= ["b"] (rmp/index-keys ctx name {:index :group :equals nil})))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/index-keys ctx name {:index :group})))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/index-keys ctx name {:equals nil :offset -1})))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/index-groups ctx name :unknown)))))))

(deftest forward-index-values-are-metadata-only
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:group :included?]))
                            {:include? :included? :indexes {:group :group}})
            observed (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))]
        (keyed-append! [{:owner "a" :group nil :included? true}
                  {:owner "b" :group "same" :included? true}
                  {:owner "excluded" :group "other" :included? false}])
        (rmp/read-session ctx [name]
          (fn [prepared]
            (reset! observed [])
            (is (= {"a" {:group nil} "b" {:group "same"}}
                   (into {} (map (juxt :key :value)) (rmp/index-values prepared name [:group]))))
            (is (= [{:key "b" :value {:group "same" :identity "b"}}]
                   (into [] (rmp/index-values prepared name [:group :identity] ["b" "absent" "b" "excluded"]))))
            (is (= [{:key "b" :value {:group "same"}}]
                   (into [] (rmp/index-values prepared name [:group] #{"b" "absent" "excluded"}))))
            (is (empty? (filter #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))
            (reset! observed [])
            (is (= 1 (count (into [] (take 1) (rmp/index-values prepared name [:group])))))
            (is (= 1 (count (filter #(= :sort-key-read (:operation %)) @observed))))
            (reset! observed [])
            (let [calls (atom [])
                  options {:indexes [:group] :sort-value (fn [id values]
                                                           (swap! calls conj id)
                                                           (or (:group values) "zero")) :limit 1}]
              (is (= ["b"] (:keys (rmp/key-page prepared name options))))
              (is (= #{"a" "b"} (set @calls)))
              (is (= 2 (count @calls)))
              (is (= 2 (count (filter #(= :sort-key-read (:operation %)) @observed))))
              (is (= ["b"] (:keys (rmp/key-page prepared name ["a" "b" "b"] options)))))))
        (keyed-append! [{:owner "a" :group "changed" :included? true}
                  {:owner "b" :group "same" :included? false}])
        (is (= [{:key "a" :value {:group "changed"}}] (into [] (rmp/index-values ctx name [:group]))))
        (is (= [] (into [] (rmp/index-values ctx name [:group] []))))
        (is (= [] (into [] (rmp/index-values (assoc ctx :tenant-id (random-uuid)) name [:group]))))
        (is (thrown? Exception (rmp/index-values ctx name [:absent])))
        (is (thrown? Exception (rmp/index-values ctx name [:group] nil)))))))

(deftest forward-metadata-reductions-are-detached-and-reusable
  (keyed-fixture
    (fn []
      (let [fields (mapv #(keyword (str "field-" %)) (range 12))
            first-values (zipmap fields (range 12))
            second-values (zipmap fields (repeat nil))
            name (keyed-register! (fn [_ event] (select-keys event fields))
                            {:indexes (zipmap fields fields)})]
        (keyed-append! [(assoc first-values :owner 1) (assoc second-values :owner 2)])
        (rmp/read-session *keyed-ctx* [name]
          (fn [ctx]
            (let [rows (rmp/index-values ctx name fields [1N 1 2N 2])
                  expected [{:key 1 :value first-values} {:key 2 :value second-values}]
                  retained (into [] rows)]
              (is (= expected retained))
              (is (= expected (into [] rows)) "Each reduction has fresh duplicate tracking")
              (is (= expected retained) "Later decoding cannot mutate retained metadata")
              (is (= [{:key 1 :value {}}] (into [] (rmp/index-values ctx name [] [1]))))
            (is (= [{:key 1 :value first-values}]
                   (into [] (take 1)
                         (rmp/index-values ctx name fields
                                           (concat [1] (lazy-seq (throw (ex-info "Unused identities were consumed" {})))))))))))))))

(deftest candidate-filtering-shares-metadata-with-ordering
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score :included?]))
                            {:include? :included? :indexes {:score :score}})
            observed (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))
            predicates (atom []) orders (atom [])
            options {:indexes [:score] :index :score :page 99 :limit 1
                     :include? (fn [id values] (swap! predicates conj [id values]) (some? (:score values)))
                     :sort-value (fn [id values] (swap! orders conj id) (:score values))}]
        (keyed-append! [{:owner "a" :score 2 :included? true}
                  {:owner "b" :score nil :included? true}
                  {:owner "c" :score 1 :included? true}
                  {:owner "excluded" :score 0 :included? false}])
        (rmp/read-session ctx [name]
          (fn [prepared]
            (reset! observed [])
            (let [page (rmp/key-page prepared name ["a" "b" "a" "absent" "excluded" "c"] options)]
              (is (= ["a"] (:keys page)))
              (is (= [2 2 2 3] (mapv page [:total :total-pages :page :sort-keys-read])))
              (is (= [["a" {:score 2}] ["b" {:score nil}] ["c" {:score 1}]] @predicates))
              (is (= ["a" "c"] @orders))
              (is (= 3 (count (filter #(= :sort-key-read (:operation %)) @observed))))
              (is (empty? (filter #(#{:result-decoded :state-decoded} (:operation %)) @observed))))
            (is (= ["c"] (:keys (rmp/key-page prepared name (assoc options :direction :desc)))))
            (is (= ["c"] (:keys (rmp/key-page prepared name (dissoc (assoc options :page 1) :sort-value)))))
            (is (= {:keys [] :total 0 :total-pages 1 :page 1 :page-size 1 :sort-keys-read 3}
                   (rmp/key-page prepared name (assoc options :include? (constantly false)))))
            (is (= ["a"] (:keys (rmp/key-page prepared name {:index :score :include? (fn [_ score] (= 2 score))}))))))
        (is (empty? (:keys (rmp/key-page (assoc ctx :tenant-id (random-uuid)) name options))))
        (is (thrown? Exception (rmp/key-page ctx name {:include? 42})))
        (keyed-append! [{:owner "c" :score nil :included? true}])
        (is (= ["a"] (:keys (rmp/key-page ctx name options))))))))

(deftest incompatible-storage-is-ignored-by-normal-materialization
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (:group e)) {:indexes {:group identity}})
            ;; Historical KF2 addressed each tenant/model/version by a SHA-256 hash.
            old-prefix (core/key-join (byte-array [75 70 50])
                                 (.digest (java.security.MessageDigest/getInstance "SHA-256")
                                          (core/key-encode [(:tenant-id *keyed-ctx*) name 1])))]
        (keyed-append! [{:owner "a" :group "same"} {:owner "b" :group "same"}])
        (let [head (:event/id (first (into [] (es/read (:event-store *keyed-ctx*)
                                                     {:tenant-id (:tenant-id *keyed-ctx*) :reverse? true :limit 1}))))
              old-key (core/key-join old-prefix (byte-array [0]))
              old-progress {:watermark head :checked-through head}]
          (kv/transact (:cache *keyed-ctx*) :write #((:put %) old-key (fressian-util/encode old-progress)))
          (is (= ["a" "b"] (keyed-page-keys *keyed-ctx* name {})))
          (is (= [{:value "same" :total 2}] (rmp/index-groups *keyed-ctx* name :group)))
          (is (= old-progress (kv/transact (:cache *keyed-ctx*) :read #(fressian-util/decode ((:get %) old-key)))))
          (let [before (keyed-stored-bytes)]
            (rmp/index-groups *keyed-ctx* name :group)
            (is (= before (keyed-stored-bytes)) "Warm reads do not rewrite materialized data")))))))

(deftest ranked-pages-match-oracle-through-membership-changes
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score :included?]))
                            {:include? :included? :indexes {:score :score}})
            scores [nil false true "" "a" "a\u0000" "aa" "\uffff" -10 0 10
                    :a :b #uuid "00000000-0000-0000-0000-000000000001"
                    [] [nil] [nil "a"] ["a" 1] ["a\u0000" 2] ["aa" 3]
                    [true "a"] [true "b"] [false "a"] [["a"] 1] [["a" 1] 2]]
            ids (vec (concat [1 "1" :a] (map #(str "id-" %) (range 80)) (repeatedly 5 random-uuid)))
            expected (atom {}) random (java.util.Random. 33)
            compare-rows (fn [direction [id-a a] [id-b b]]
                           (let [c (java.util.Arrays/compareUnsigned ^bytes (core/key-encode (:score a))
                                                                   ^bytes (core/key-encode (:score b)))]
                             (if (zero? c)
                               (let [c (compare (str id-a) (str id-b))]
                                 (if (zero? c)
                                   (java.util.Arrays/compareUnsigned ^bytes (core/key-encode id-a) ^bytes (core/key-encode id-b)) c))
                               (if (= direction :desc) (- c) c))))]
        (dotimes [_ 5]
          (let [events (mapv (fn [id] {:owner id :score (nth scores (.nextInt random (count scores)))
                                      :included? (.nextBoolean random)}) ids)]
            (keyed-append! events)
            (doseq [{:keys [owner] :as event} events]
              (swap! expected assoc owner (select-keys event [:score :included?]))))
          (doseq [direction [:asc :desc]
                  selection (concat [{}] (map #(hash-map :equals %) (conj scores "absent"))
                                    (map #(hash-map :prefix %)
                                         [[] [nil] [true] [false] ["a"] ["a\u0000"] ["aa"]
                                          [["a"]] [["a" 1]] ["a" 1] ["absent"]]))]
            (let [rows (->> @expected
                            (filter (fn [[_ v]] (and (:included? v)
                                                    (or (not (contains? selection :equals))
                                                        (= (:score v) (:equals selection)))
                                                    (or (not (contains? selection :prefix))
                                                        (and (vector? (:score v))
                                                             (<= (count (:prefix selection)) (count (:score v)))
                                                             (= (:prefix selection)
                                                                (subvec (:score v) 0 (count (:prefix selection)))))))))
                            (sort (partial compare-rows direction)))
                  expected-keys (mapv first rows)
                  options (merge {:index :score :direction direction} selection)]
              (is (= expected-keys (mapv :key (into [] (rmp/values *keyed-ctx* name options)))))
              (doseq [requested [1 2 999]]
                (let [page (rmp/page *keyed-ctx* name (assoc options :page requested :limit 7))
                      page-number (min requested (max 1 (quot (+ 6 (count rows)) 7)))]
                  (is (= (count rows) (:total page)))
                  (is (= page-number (:page page)))
                  (is (= (vec (take 7 (drop (* 7 (dec page-number)) expected-keys)))
                         (mapv :key (:entries page)))))))))
        (is (= @expected (into {} (map (fn [id] [id (rmp/state *keyed-ctx* name id)])) ids)))))))

(deftest tuple-prefix-pages-seek-and-count-without-scanning-matches
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})
            random (java.util.Random. 72)
            seen (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
        (with-redefs [core/ranked-random-hash (fn [_] (.nextInt random))]
          (keyed-append! (mapv #(hash-map :owner (format "%05d" %)
                                    :score [(< % 3000) (quot % 2)]) (range 4096)))
          (rmp/page ctx name {}))
        (doseq [direction [:asc :desc]]
          (reset! seen [])
          (let [page (rmp/page ctx name {:index :score :prefix [true] :direction direction :page 120})]
            (is (= 3000 (:total page)))
            (is (= 120 (:page page) (:total-pages page)))
            (is (= (if (= direction :asc) "02975" "00025") (:key (first (:entries page)))))
            (is (= 25 (:rows-examined page) (:results-decoded page)))
            (is (< (:rank-entries-examined page) 1000))
            (is (= 25 (count (filter #(= :result-decoded (:operation %)) @seen))))
            (is (empty? (filter #(#{:state-decoded :event-applied :sort-key-read} (:operation %)) @seen)))))
        (let [other (assoc ctx :tenant-id (random-uuid))]
          (keyed-append! other [{:owner "only-other" :score [true 0]}])
          (is (= ["only-other"] (keyed-page-keys other name {:index :score :prefix [true]})))
          (is (= 3000 (:total (rmp/page ctx name {:index :score :prefix [true]})))))
        (doseq [options [{:prefix true} {:prefix nil} {:prefix '(true)}
                         {:prefix [true] :equals [true 0]}]]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/page ctx name (assoc options :index :score))))
          (is (thrown? clojure.lang.ExceptionInfo (rmp/values ctx name (assoc options :index :score)))))))))

(deftest tuple-prefix-union-pages-match-canonical-membership
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score :included?]))
                            {:indexes {:score :score} :include? :included?})
            scores [nil true "a" [] [nil] [true 0] [false 1] ["a" 1] ["a" 2]
                    ["a\u0000" 1] ["ab" 0] ["b" 0] [["nested"] 1] ["" 0]]
            ids (mapv #(if (even? %) (str %) (java.util.UUID. 12 %)) (range 120))
            random (java.util.Random. 713)
            matches? (fn [prefix value]
                       (and (vector? value) (<= (count prefix) (count value))
                            (= prefix (subvec value 0 (count prefix)))))]
        (dotimes [_ 3]
          (keyed-append! (mapv (fn [id] {:owner id :score (nth scores (.nextInt random (count scores)))
                                   :included? (.nextBoolean random)}) ids))
          (doseq [direction [:asc :desc]]
            (let [ordered (into [] (rmp/values *keyed-ctx* name {:index :score :direction direction}))]
              (doseq [prefixes [[] [[]] [[true] [false]] [["b"] ["a"] ["a"]]
                                [["a" 1] ["a"] ["absent"]] [[["nested"]] [""]]
                                [[nil] [true]] [["a\u0000"] ["ab"]] [["missing"]]
                                [["a"] [] ["b"]]]]
                (let [expected (into [] (comp (filter #(some (fn [prefix] (matches? prefix (get-in % [:value :score]))) prefixes))
                                             (map :key)) ordered)
                      pages (max 1 (quot (+ (count expected) 6) 7))]
                  (doseq [requested (conj (vec (range 1 (inc pages))) 999)]
                    (let [page (rmp/page *keyed-ctx* name {:index :score :direction direction :prefixes prefixes
                                                    :page requested :limit 7})
                          page-number (min requested pages)]
                      (is (= (count expected) (:total page)))
                      (is (= pages (:total-pages page)))
                      (is (= page-number (:page page)))
                      (is (= (vec (take 7 (drop (* 7 (dec page-number)) expected)))
                             (mapv :key (:entries page)))))))))))
        (doseq [options [{:prefixes nil} {:prefixes '([true])} {:prefixes [true]}
                         {:prefixes [[] nil]} {:prefixes [[]] :equals nil}
                         {:prefixes [] :prefix []}]]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/page *keyed-ctx* name (assoc options :index :score)))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (rmp/values *keyed-ctx* name {:index :score :prefixes [[]]}))
            "Union selection is a page capability, not an implicit traversal API")))))

(deftest tuple-prefix-union-pages-skip-gaps-and-remain-snapshot-bound
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score :included?]))
                            {:indexes {:score :score} :include? :included?})
            random (java.util.Random. 321)
            work (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! work conj %))
            selection {:index :score :prefixes [["right"] ["left"] ["left" 0]] :limit 25}]
        (with-redefs [core/ranked-random-hash (fn [_] (.nextInt random))]
          (keyed-append! (mapv #(hash-map :owner (format "%05d" %) :included? true
                                    :score [(cond (< % 1500) "left" (< % 3500) "middle" :else "right")
                                            (quot % 2)]) (range 5000)))
          (rmp/page ctx name selection))
        (doseq [direction [:asc :desc]]
          (reset! work [])
          (let [page (rmp/page ctx name (assoc selection :page 119 :direction direction))]
            (is (= 3000 (:total page)))
            (is (= 120 (:total-pages page)))
            (is (= (if (= direction :asc) "04950" "00048") (:key (first (:entries page)))))
            (is (= 25 (:rows-examined page) (:results-decoded page)))
            (is (= 25 (count (filter #(= :result-decoded (:operation %)) @work))))
            (is (< (:rank-entries-examined page) 1500))
            (is (not-any? #(#{:state-decoded :sort-key-read :event-applied} (:operation %)) @work))))
        (rmp/read-session ctx [name]
          (fn [snapshot]
            (let [before (rmp/page snapshot name selection)]
              (keyed-append! [{:owner "00000" :score ["middle" 0] :included? true}
                        {:owner "00001" :score ["left" 0] :included? false}])
              (is (= (select-keys before [:entries :total])
                     (select-keys (rmp/page snapshot name selection) [:entries :total]))))))
        (is (= 2998 (:total (rmp/page ctx name selection))))
        (let [other (assoc ctx :tenant-id (random-uuid))]
          (keyed-append! other [{:owner "other" :score ["left" 0] :included? true}])
          (is (= ["other"] (keyed-page-keys other name selection)))
          (is (= 2998 (:total (rmp/page ctx name selection)))))))))

(deftest deep-pages-and-equality-counts-skip-members
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})
            random (java.util.Random. 92)
            seen (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
        ;; Fixed random promotions make the work regression assertion reproducible.
        (with-redefs [core/ranked-random-hash (fn [_] (.nextInt random))]
          (keyed-append! (mapv #(hash-map :owner (format "%05d" %) :score (if (< % 3000) 0 1)) (range 4096)))
          (rmp/page ctx name {}))
        (doseq [options [{:index :score :page 164}
                         {:index :score :equals 0 :page 120}
                         {:index :score :equals 0 :direction :desc :page 120}]]
          (reset! seen [])
          (let [page (rmp/page ctx name options)
                equality? (contains? options :equals)]
            (is (= (if equality? 3000 4096) (:total page)))
            (is (= (if equality? "02975" "04075") (:key (first (:entries page)))))
            (is (= (count (:entries page)) (:rows-examined page) (:results-decoded page)))
            (is (< (:rank-entries-examined page) 1000))
            (is (= (:rank-entries-examined page) (count (filter #(= :rank-entry (:operation %)) @seen))))
            (is (empty? (filter #(#{:state-decoded :event-applied} (:operation %)) @seen)))))))))

(deftest ranked-index-write-failure-rolls-back-business-batch
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] {:score (:score e)}) {:indexes {:score :score}})]
        (keyed-append! [{:owner "a" :score 1}])
        (rmp/page *keyed-ctx* name {})
        (let [before (keyed-stored-bytes) original core/ranked-add!]
          (keyed-append! [{:owner "a" :score 2}])
          (with-redefs [core/ranked-add! (fn [& args]
                                    (apply original args)
                                    (throw (ex-info "rank write failed" {})))]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rank write failed" (rmp/page *keyed-ctx* name {}))))
          (is (= before (keyed-stored-bytes)))
          (is (= ["a"] (keyed-page-keys *keyed-ctx* name {:index :score :equals 2})))
          (is (= 0 (:total (rmp/page *keyed-ctx* name {:index :score :equals 1})))))))))

(deftest pure-fold-equivalence-across-batches-and-keys
  (keyed-fixture
    (fn []
      (let [events (mapv (fn [i] {:owner (str (mod i 7)) :delta (- (mod i 13) 6)}) (range 257))
            expected (into {} (map (fn [[id events]] [id (keyed-total (reduce keyed-history-step {} events))]))
                           (group-by :owner events))]
        (keyed-append! events)
        (doseq [batch-size [1 3 128]]
          (let [name (keyed-register! keyed-history-step {:result keyed-total :indexes {:total :total}})
                ctx (assoc *keyed-ctx* :read-model/batch-size batch-size)]
            (is (= expected (keyed-results ctx name)))
            (is (= (mapv :delta (filter #(= "0" (:owner %)) events))
                   (:history (rmp/state ctx name "0"))))
            (is (= expected (keyed-results ctx name)) "Repeated reads do not reapply events")))))))

(deftest incremental-result-and-unchanged-result-retain-accumulator
  (keyed-fixture
    (fn []
      (let [observed (atom [])
            ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))
            name (keyed-register! keyed-history-step {:result keyed-total :indexes {:total :total}})]
        (keyed-append! [{:owner "alice" :delta 4} {:owner "bob" :delta 9}])
        (is (= {:total 4} (rmp/value ctx name "alice")))
        (reset! observed [])
        (keyed-append! [{:owner "alice" :delta 0}])
        (is (= {:total 4} (rmp/value ctx name "alice")))
        (is (= 1 (count (filter #(= :state-decoded (:operation %)) @observed))))
        (is (empty? (filter #(= :result-written (:operation %)) @observed)))
        (is (= [4 0] (:history (rmp/state ctx name "alice"))))
        (keyed-append! [{:owner "alice" :delta 3}])
        (is (= {:total 7} (rmp/value ctx name "alice"))))
      (let [calls (atom 0)
            name (keyed-register! keyed-history-step {:result #(do (swap! calls inc) (keyed-total %))})]
        (rmp/value *keyed-ctx* name "alice")
        (is (= 2 @calls) "One completion per key in the batch, not per event")))))

(deftest warm-result-reads-never-decode-accumulators
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total :indexes {:total :total}})
            seen (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
        (keyed-append! (mapv #(hash-map :owner (str %) :delta %) (range 30)))
        (rmp/value ctx name "0")
        (reset! seen [])
        (rmp/value ctx name "5")
        (is (= 25 (:results-decoded (rmp/page ctx name {:index :total}))))
        (is (= 30 (count (into [] (rmp/values ctx name)))))
        (is (empty? (filter #(= :state-decoded (:operation %)) @seen)))
        (is (empty? (filter #(= :event-applied (:operation %)) @seen)))))))

(deftest pages-equality-clamping-and-stable-ties
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ e] (select-keys e [:score :label]))
                            {:indexes {:score :score :label :label}})]
        (keyed-append! [{:owner "b" :score -5 :label "same"}
                  {:owner "a" :score -5 :label "same"}
                  {:owner "c" :score 8 :label "other"}])
        (is (= ["a" "b" "c"] (mapv :key (:entries (rmp/page *keyed-ctx* name {:index :score})))) )
        (is (= ["c" "a" "b"] (mapv :key (:entries (rmp/page *keyed-ctx* name {:index :score :direction :desc})))) )
        (let [p (rmp/page *keyed-ctx* name {:index :label :equals "same" :limit 1 :page 99})]
          (is (= [2 2 2] ((juxt :total :total-pages :page) p)))
          (is (= "b" (:key (first (:entries p))))))
        (keyed-append! [{:owner "a" :score 20 :label "renamed"}])
        (is (= ["b" "c" "a"] (mapv :key (:entries (rmp/page *keyed-ctx* name {:index :score})))) )
        (is (= ["b"] (mapv :key (into [] (rmp/values *keyed-ctx* name {:index :label :equals "same"})))))))))

(deftest absent-and-nil-instances-are-distinct
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [_ _] nil) {})]
        (is (= :ai.obney.grain.read-model-processor-v2.keyed-test/missing (rmp/value *keyed-ctx* name "never" :ai.obney.grain.read-model-processor-v2.keyed-test/missing)))
        (is (= 0 (:total (rmp/page *keyed-ctx* name {}))))
        (keyed-append! [{:owner "present"}])
        (is (nil? (rmp/value *keyed-ctx* name "present" :ai.obney.grain.read-model-processor-v2.keyed-test/missing)))
        (is (nil? (rmp/state *keyed-ctx* name "present" :ai.obney.grain.read-model-processor-v2.keyed-test/missing)))
        (is (= [{:key "present" :value nil}] (:entries (rmp/page *keyed-ctx* name {}))))))))

(deftest explicit-membership-preserves-state-and-atomically-updates-pages
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [s e] (merge s (select-keys e [:included? :label])))
                            {:include? :included? :indexes {:label :label}})]
        (keyed-append! [{:owner "a" :label "Zoe" :included? false}
                  {:owner "b" :label "Bob" :included? true}])
        (is (= :ai.obney.grain.read-model-processor-v2.keyed-test/absent (rmp/value *keyed-ctx* name "a" :ai.obney.grain.read-model-processor-v2.keyed-test/absent)))
        (is (= "Zoe" (:label (rmp/state *keyed-ctx* name "a"))))
        (is (= 1 (:total (rmp/page *keyed-ctx* name {}))))
        (keyed-append! [{:owner "a" :included? true}])
        (is (= ["b" "a"] (mapv :key (:entries (rmp/page *keyed-ctx* name {:index :label})))))
        (keyed-append! [{:owner "a" :label "Alice"} {:owner "b" :included? false}])
        (doseq [direction [:asc :desc]]
          (let [p (rmp/page *keyed-ctx* name {:index :label :direction direction})]
            (is (= 1 (:total p)))
            (is (= ["a"] (mapv :key (:entries p))))))
        (is (= [] (:entries (rmp/page *keyed-ctx* name {:index :label :equals "Zoe"})) ))
        (keyed-append! [{:owner "a" :included? false}])
        (is (= 0 (:total (rmp/page *keyed-ctx* name {}))))
        (keyed-append! [{:owner "b" :included? true}])
        (is (= "Bob" (:label (rmp/value *keyed-ctx* name "b"))))
        (is (= 1 (:total (rmp/page *keyed-ctx* name {}))))))))

(deftest inclusion-failure-rolls-back-state-index-count-and-progress
  (keyed-fixture
    (fn []
      (let [fail? (atom false)
            name (keyed-register! (fn [_ e] (select-keys e [:included? :label]))
                            {:include? #(if @fail? (throw (ex-info "inclusion failed" {})) (:included? %))
                             :indexes {:label #(if (:included? %) (:label %)
                                                (throw (ex-info "excluded result indexed" {})))}})]
        (keyed-append! [{:owner "a" :included? true :label "Alice"}])
        (rmp/page *keyed-ctx* name {})
        (let [before (keyed-stored-bytes)]
          (keyed-append! [{:owner "a" :included? false :label "Changed"}])
          (reset! fail? true)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inclusion failed" (rmp/page *keyed-ctx* name {})))
          (is (= before (keyed-stored-bytes)))
          (reset! fail? false)
          (is (= 0 (:total (rmp/page *keyed-ctx* name {}))))
          (is (= "Changed" (:label (rmp/state *keyed-ctx* name "a")))))))))

(deftest transaction-failure-retries-from-durable-progress
  (keyed-fixture
    (fn []
      (doseq [phase [:step :result :index]]
        (let [fail? (atom true)
              name (keyed-register!
                    (fn [s e]
                      (when (and @fail? (= phase :step) (= 99 (:delta e)))
                        (throw (ex-info "fail-step" {})))
                      (keyed-history-step s e))
                    {:result (fn [s]
                               (when (and @fail? (= phase :result) (some #{99} (:history s)))
                                 (throw (ex-info "fail-result" {}))) (keyed-total s))
                     :indexes {:total (fn [v]
                                        (when (and @fail? (= phase :index) (>= (:total v) 99))
                                          (throw (ex-info "fail-index" {}))) (:total v))}})
              ctx (assoc *keyed-ctx* :read-model/batch-size 1)]
          ;; An independent tenant keeps each failure scenario's history small.
          (let [ctx (assoc ctx :tenant-id (random-uuid))]
            (keyed-append! ctx [{:owner "a" :delta 1} {:owner "a" :delta 99}])
            (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a")))
            (reset! fail? false)
            (is (= {:total 100} (rmp/value ctx name "a")))
            (is (= [1 99] (:history (rmp/state ctx name "a"))))
            (is (= 1 (:total (rmp/page ctx name {:index :total}))))))))))

(deftest invalid-owner-and-invalid-index-commit-nothing
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total})]
        (keyed-append! [{:owner "a" :delta 1} {:delta 2}])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no owner" (rmp/value *keyed-ctx* name "a")))
        (is (empty? (keyed-materialized-bytes))))
      (let [ctx (assoc *keyed-ctx* :tenant-id (random-uuid))
            name (keyed-register! (fn [_ e] (:label e)) {:indexes {:label identity}})]
        (keyed-append! ctx [{:owner "a" :label "ok"} {:owner "b" :label (apply str (repeat 600 "x"))}])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"511" (rmp/page ctx name {})))
        (is (empty? (keyed-materialized-bytes)))))))

(deftest isolation-and-scoped-read-lifetime
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total})
            other (assoc *keyed-ctx* :tenant-id (random-uuid))]
        (keyed-append! [{:owner "a" :delta 1}])
        (keyed-append! other [{:owner "a" :delta 2}])
        (is (= {:total 1} (rmp/value *keyed-ctx* name "a")))
        (is (= {:total 2} (rmp/value other name "a")))
        (let [reads (atom 0) original es/read
              escaped (with-redefs [es/read (fn [& args] (swap! reads inc) (apply original args))]
                        (rmp/read-session [[*keyed-ctx* [name]] [other [name]]]
                          (fn [[a b]]
                            (let [before @reads]
                              (dotimes [_ 5]
                                (is (= {:total 1} (rmp/value a name "a")))
                                (is (= {:total 2} (rmp/value b name "a"))))
                              (is (= before @reads)))
                            a)))]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/value escaped name "a"))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (rmp/read-session *keyed-ctx* [name]
                       #(rmp/value (assoc % :tenant-id (:tenant-id other)) name "a"))))))))

(deftest concurrent-readers-and-restart
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total})]
        (keyed-append! (repeat 50 {:owner "a" :delta 1}))
        (let [workers (doall (repeatedly 12 #(future (rmp/value *keyed-ctx* name "a"))))]
          (doseq [worker workers] (is (= {:total 50} (deref worker 20000 :ai.obney.grain.read-model-processor-v2.keyed-test/timeout)))))
        ;; Reopening the same directory proves the checkpoint and both representations
        ;; are durable, not dependent on L1 or the original store handle.
        (let [cache (:cache *keyed-ctx*)]
          (kv/stop cache)
          (reset! *keyed-cache-handle* nil)
          (let [reopened (kv/start (lmdb/->KV-Store-LMDB (:config cache)))
                ctx (assoc *keyed-ctx* :cache reopened)]
            (reset! *keyed-cache-handle* reopened)
              (is (= {:total 50} (rmp/value ctx name "a")))
              (keyed-append! [{:owner "a" :delta 5}])
              (is (= {:total 55} (rmp/value ctx name "a")))
              (is (= 51 (count (:history (rmp/state ctx name "a")))))))))))

(deftest declaration-and-access-validation
  (keyed-fixture
    (fn []
      (doseq [opts [{:key nil} {:key :owner :events #{}} {:key :owner :l1-ttl-ms 1}
                    {:key :owner :indexes {:identity identity}} {:key :owner :result nil}
                    {:key :owner :tenant-key :organization} {:key :owner :queries []}
                    {:key :owner :include? false}]]
        (is (thrown? clojure.lang.ExceptionInfo (keyed-register! keyed-history-step opts))))
      (let [name (keyed-register! keyed-history-step {:result keyed-total})]
        (is (thrown? clojure.lang.ExceptionInfo (rmp/project *keyed-ctx* name)))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/value (dissoc *keyed-ctx* :tenant-id) name "a")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"share one event store"
                              (rmp/read-session [[*keyed-ctx* [name]] [(dissoc *keyed-ctx* :event-store) [name]]] identity)))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/page *keyed-ctx* name {:limit 0})))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/page *keyed-ctx* name {:index :unknown})))))))

(deftest validation-and-codec-failures-roll-back-the-entire-batch
  (keyed-fixture
    (fn []
      (doseq [phase [:state :result :codec]]
        (let [ctx (assoc *keyed-ctx* :tenant-id (random-uuid))
              name (keyed-register! keyed-history-step
                     (merge {:result keyed-total}
                            (case phase
                              :state {:state-schema [:map [:history [:vector [:int {:max 10}]]]]}
                              :result {:result-schema [:map [:total [:int {:max 10}]]]}
                              :codec {})))]
          (keyed-append! ctx [{:owner "a" :delta 1} {:owner "b" :delta 99}])
          (let [before (keyed-materialized-bytes) original fressian-util/encode]
            (if (= phase :codec)
              (with-redefs [fressian-util/encode (fn [v]
                                          (when (= [99] (:history v))
                                            (throw (ex-info "Injected encoding failure" {})))
                                          (original v))]
                (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a"))))
              (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a"))))
            (is (= before (keyed-materialized-bytes)) "Includes indexes, counts and progress")
            (when (= phase :codec)
              (is (= {"a" {:total 1} "b" {:total 99}} (keyed-results ctx name))))))))))

(deftest validators-are-reused-across-catch-up-batches
  (keyed-fixture
    (fn []
      (let [state-schema [:map [:history [:vector :int]]]
            result-schema [:map [:total :int]]
            name (keyed-register! keyed-history-step {:state-schema state-schema :result-schema result-schema
                                          :result keyed-total})
            ctx (assoc *keyed-ctx* :read-model/batch-size 2)
            prepared (atom []) checked (atom []) original m/validator]
        (keyed-append! [{:owner "a" :delta 1} {:owner "a" :delta 2}
                  {:owner "b" :delta 3} {:owner "b" :delta 4} {:owner "c" :delta 5}])
        (with-redefs [m/validator (fn [schema]
                                   (let [form (m/form schema) valid? (original schema nil)]
                                     (swap! prepared conj form)
                                     (fn [value] (swap! checked conj form) (valid? value))))]
          (is (= {"a" {:total 3} "b" {:total 7} "c" {:total 5}} (keyed-results ctx name)))
          (is (= {state-schema 1 result-schema 1} (frequencies @prepared)))
          (is (= {state-schema 5 result-schema 3} (frequencies @checked)))
          (reset! prepared []) (reset! checked [])
          (is (= {:total 3} (rmp/value ctx name "a")))
          (is (empty? @prepared) "An already current model needs no validators")
          (is (empty? @checked)))))))

(deftest later-catch-ups-resolve-current-schemas-and-preserve-failure-details
  (keyed-fixture
    (fn []
      (doseq [option [:state-schema :result-schema]]
        (let [schema-name (keyword "keyed-test" (str (random-uuid)))
              ctx (assoc *keyed-ctx* :tenant-id (random-uuid))
              kind (if (= option :result-schema) :result :state)]
          (try
            (schemas/register! {schema-name [:int {:max 10}]})
            (let [name (keyed-register! (fn [_ event] (:delta event)) {option schema-name})]
              (keyed-append! ctx [{:owner "a" :delta 1}])
              (is (= 1 (rmp/value ctx name "a")))
              ;; Change the registry between operations to detect stale validators.
              (schemas/register! {schema-name [:int {:max 2}]})
              (keyed-append! ctx [{:owner "a" :delta 2} {:owner "b" :delta 3}])
              (let [before (keyed-stored-bytes)
                    error (try (rmp/value ctx name "a") nil
                               (catch clojure.lang.ExceptionInfo e e))]
                (is (= "Keyed-fold schema validation failed" (some-> error ex-message)))
                (is (= {:model name :kind kind} (select-keys (ex-data error) [:model :kind])))
                (is (= 3 (get-in (ex-data error) [:explanation :value])))
                (is (seq (get-in (ex-data error) [:explanation :errors])))
                (is (= before (keyed-stored-bytes)) "Invalid state or result rolls back the whole batch"))
              (schemas/register! {schema-name [:int {:max 10}]})
              (is (= {"a" 2 "b" 3} (keyed-results ctx name)) "Retry resolves the schema again"))
            (finally (swap! schemas/registry* dissoc schema-name))))))))

(deftest initial-state-tenant-and-version-isolation
  (keyed-fixture
    (fn []
      (let [name (keyed-register! (fn [s e] (+ s (:delta e))) {:initial 10})
            other (assoc *keyed-ctx* :tenant-id (random-uuid))]
        (keyed-append! [{:owner "a" :delta 1}])
        (keyed-append! other [{:owner "a" :delta 2}])
        (is (= 11 (rmp/value *keyed-ctx* name "a")))
        (is (= 12 (rmp/value other name "a")))
        (rmp/register-read-model! name (fn [s e] (+ s (* 2 (:delta e))))
                                  {:events #{:keyed/change} :key :owner :version 2 :initial 20})
        (is (= 22 (rmp/value *keyed-ctx* name "a")))
        (is (= 24 (rmp/value other name "a")))))))

(deftest finite-head-and-unrelated-events
  (keyed-fixture
    (fn []
      (let [append-once? (atom true)
            name (keyed-register! (fn [s e]
                              ;; Fault-injection only: an append during catch-up must
                              ;; not extend the head captured for this request.
                              (when (compare-and-set! append-once? true false)
                                (keyed-append! [{:owner "a" :delta 2}]))
                              (keyed-history-step s e)) {:result keyed-total})]
        (keyed-append! [{:owner "a" :delta 1}])
        (is (= {:total 1} (rmp/value *keyed-ctx* name "a")))
        (is (= {:total 3} (rmp/value *keyed-ctx* name "a")))
        (let [watermark (:watermark (rmp/progress *keyed-ctx* name))]
          (es/append (:event-store *keyed-ctx*)
                     {:tenant-id (:tenant-id *keyed-ctx*)
                      :events [(es/->event {:type :keyed/other :body {}})]})
          (let [progress (rmp/progress *keyed-ctx* name)]
            (is (= watermark (:watermark progress)))
            (is (pos? (compare (:checked-through progress) watermark)))))))))

(deftest result-traversal-stops-early-and-values-are-detached
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total}) seen (atom [])
            ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
        (keyed-append! (mapv #(hash-map :owner (str %) :delta %) (range 20)))
        (rmp/value ctx name "0")
        (reset! seen [])
        (let [entry (reduce (fn [_ v] (reduced v)) nil (rmp/values ctx name))]
          (is (= {:key "0" :value {:total 0}} entry))
          (is (= 1 (count (filter #(= :result-decoded (:operation %)) @seen)))))
        (let [escaped (rmp/read-session ctx [name] #(rmp/values % name))]
          (is (thrown? clojure.lang.ExceptionInfo (into [] escaped))))
        (is (= 20 (count (into [] (rmp/values ctx name)))))))))

(deftest active-read-snapshot-allows-another-reader-to-catch-up
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total})
            entered (promise) advanced (promise)]
        (keyed-append! [{:owner "a" :delta 1}])
        (let [old-reader (future
                           (rmp/read-session *keyed-ctx* [name]
                             (fn [ctx]
                               (let [before (rmp/value ctx name "a")]
                                 (deliver entered true)
                                 (deref advanced 10000 :ai.obney.grain.read-model-processor-v2.keyed-test/timeout)
                                 [before (rmp/value ctx name "a")]))))]
          (is (= true (deref entered 10000 :ai.obney.grain.read-model-processor-v2.keyed-test/timeout)))
          (let [new-reader (future
                             (keyed-append! [{:owner "a" :delta 2}])
                             (let [value (rmp/value *keyed-ctx* name "a")]
                               (deliver advanced true)
                               value))]
            (try
              (is (= {:total 3} (deref new-reader 5000 :ai.obney.grain.read-model-processor-v2.keyed-test/timeout)))
              (is (= [{:total 1} {:total 1}] (deref old-reader 5000 :ai.obney.grain.read-model-processor-v2.keyed-test/timeout)))
              (finally (deliver advanced true)))))))))

(deftest prepared-readers-reuse-addresses-without-sharing-results-or-lifetimes
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total :indexes {:total :total}})
            second-model (keyed-register! keyed-history-step {:result keyed-total})
            ctx (assoc *keyed-ctx* :read-model/batch-size 1)
            other (assoc ctx :tenant-id (random-uuid))
            calls (atom []) resolutions (atom 0) original core/namespace-resolve!]
        (keyed-append! [{:owner "a" :delta 1} {:owner "a" :delta 2} {:owner "b" :delta 4}])
        (keyed-append! other [{:owner "a" :delta 9}])
        (let [escaped
              (with-redefs [core/namespace-resolve! (fn [cache identities]
                                                 (swap! resolutions inc)
                                                 (swap! calls into identities)
                                                 (original cache identities))]
                (rmp/read-session [[ctx [name name second-model]] [other [name]] [ctx [name]]]
                  (fn [[a b duplicate]]
                    (is (= (repeat 10 [{:total 3} {:history [1 2]} {:total 9} {:total 3}])
                           (repeatedly 10 #(vector (rmp/value a name "a") (rmp/state a name "a")
                                                   (rmp/value b name "a") (rmp/value duplicate name "a")))))
                    (is (= {:total 3} (rmp/value a second-model "a")))
                    (is (= [{:key "a" :value {:total 3}} {:key "b" :value {:total 4}}]
                           (into [] (rmp/values a name {:index :total}))))
                    (is (= [{:key "b" :value {:total 4}}]
                           (:entries (rmp/page a name {:index :total :direction :desc :limit 1}))))
                    (is (some? (:watermark (rmp/progress a name))))
                    (doseq [changed [(assoc a :tenant-id (:tenant-id other))
                                     (assoc a :event-store nil)
                                     (assoc a :cache nil)]]
                      (is (thrown? clojure.lang.ExceptionInfo (rmp/value changed name "a"))))
                    (is (thrown? clojure.lang.ExceptionInfo (rmp/value b second-model "a")))
                    a)))]
          (is (= #{[(:tenant-id ctx) name 1]
                   [(:tenant-id ctx) second-model 1]
                   [(:tenant-id other) name 1]
                   [(:tenant-id ctx) :grain.read-query/shared-identities 1]
                   [(:tenant-id other) :grain.read-query/shared-identities 1]}
                 (set @calls))
              "Address preparation is bounded by distinct tenant/model identities, including cold batches")
          (is (= 1 @resolutions))
          (is (thrown? clojure.lang.ExceptionInfo (rmp/value escaped name "a"))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Transaction aborted"
              (rmp/read-session ctx [name]
                (fn [prepared]
                  (is (= :rejected @(future
                                     (try (rmp/value prepared name "a")
                                          (catch clojure.lang.ExceptionInfo _ :rejected)))))))))
        (keyed-append! [{:owner "a" :delta 5}])
        (is (= {:total 8} (rmp/value ctx name "a")) "The next session catches up; no result cache")
        (rmp/register-read-model! name keyed-history-step
                                 {:events #{:keyed/change} :version 2 :key :owner
                                  :result (fn [s] {:total (* 2 (:total (keyed-total s)))})})
        (is (= {:total 16} (rmp/value ctx name "a")))
        (is (= {:total 18} (rmp/value other name "a")))))))


(deftest warm-keyed-reads-use-the-committed-head-without-reading-events
  (keyed-fixture
    (fn []
      (let [name (keyed-register! keyed-history-step {:result keyed-total})]
        (keyed-append! (repeat 300 {:owner "a" :delta 1}))
        (is (= {:total 300} (rmp/value *keyed-ctx* name "a")))
        (with-redefs [es/read (fn [& _] (throw (ex-info "Unexpected event payload read" {})))]
          (is (= {:total 300} (rmp/value *keyed-ctx* name "a")))
          (is (= {:total 300} (:value (first (:entries (rmp/page *keyed-ctx* name {})))))))
        (with-redefs [es/head (fn [& _] {:cognitect.anomalies/category :cognitect.anomalies/unavailable})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"committed event-store head"
                               (rmp/value *keyed-ctx* name "a"))))))))

(deftest candidate-page-prefix-selection-preserves-global-order-and-clamping
  (keyed-fixture
    (fn []
      (let [rows (mapv (fn [i] {:owner (str i) :score (mod i 7)}) (range 203))
            name (keyed-register! (fn [_ e] {:score (:score e)}) {:indexes {:score :score}})
            candidate-ids (concat (map :owner (reverse rows)) ["absent" "0" "3"])]
        (keyed-append! rows)
        (rmp/read-session *keyed-ctx* [name]
          (fn [ctx]
            (doseq [direction [:asc :desc] computed? [false true] explicit? [false true]]
              (let [order-value (if computed? (comp - :score) :score)
                    ordered (->> rows (filter #(odd? (:score %)))
                                 (sort-by (fn [row] [(cond-> (order-value row) (= direction :desc) -)
                                                     (:owner row)]))
                                 (mapv :owner))
                    options (cond-> {:index :score :indexes [:score] :direction direction :limit 7
                                     :include? (fn [_ value] (odd? (:score value)))}
                              computed? (assoc :sort-value (fn [_ value] (order-value value))))
                    request (fn [options]
                              (if explicit? (rmp/key-page ctx name candidate-ids options)
                                  (rmp/key-page ctx name options)))]
                (doseq [page [1 3 13 99 Long/MAX_VALUE]]
                  (let [result (request (assoc options :page page))
                        clamped (min page 13)
                        offset (* 7 (dec clamped))]
                    (is (= (subvec ordered offset (min (count ordered) (+ offset 7))) (:keys result)))
                    (is (= [87 13 clamped 203] (mapv result [:total :total-pages :page :sort-keys-read])))))
                (let [result (request (assoc options :page Long/MAX_VALUE :limit Long/MAX_VALUE))]
                  (is (= ordered (:keys result)))
                  (is (= [87 1 1] (mapv result [:total :total-pages :page]))))))))))))

(deftest ordinary-schema-remains-supported
  (keyed-fixture
    (fn []
      (rmp/register-read-model! :test/ordinary-schema (fn [state _] state)
                                {:events #{:keyed/change} :schema [:map]})
      (is (= [:map] (:schema (get (rmp/global-read-model-registry) :test/ordinary-schema))))
      (is (= {} (rmp/project *keyed-ctx* :test/ordinary-schema))))))

;; compact cache test
(schemas/register! {:compact-test/change [:map]})
(def ^:dynamic *compact-cache-context* nil)
(def ^:dynamic *compact-cache-options* nil)
(def ^:dynamic *compact-cache* nil)

(defn compact-cache-fixture [f]
  (let [directory (str (io/file (System/getProperty "java.io.tmpdir")
                                (str "grain-compact-test-" (random-uuid))))
        _ (.mkdirs (io/file directory))
        store (es/start {:conn {:type :sqlite :database-file (str directory "/events.db")}})
        options {:storage-dir (str directory "/cache") :db-name "test" :map-size (* 128 1024 1024)}
        cache (atom (kv/start (lmdb/->KV-Store-LMDB options)))
        registry (rmp/global-read-model-registry)]
    (binding [*compact-cache-context* {:event-store store :tenant-id (UUID. 4 7)}
              *compact-cache-options* options *compact-cache* cache]
      (try (f)
           (finally
             (reset! rmp/read-model-registry* registry)
             (when @cache (kv/stop @cache))
             (es/stop store)
             (doseq [file (reverse (file-seq (io/file directory)))] (io/delete-file file true)))))))


(defn compact-cache-ctx [] (assoc *compact-cache-context* :cache @*compact-cache*))
(defn compact-cache-reopen! []
  (kv/stop @*compact-cache*)
  (reset! *compact-cache* nil)
  (reset! *compact-cache* (kv/start (lmdb/->KV-Store-LMDB *compact-cache-options*))))
(defn compact-cache-append! [context rows]
  (es/append (:event-store context)
             {:tenant-id (:tenant-id context)
              :events (mapv #(es/->event {:type :compact-test/change :body %}) rows)}))
(defn compact-cache-register! [name options]
  (rmp/register-read-model! name (fn [_ e] (select-keys e [:owner :score :group :text :included? :ordered?]))
                           (merge {:events #{:compact-test/change} :version 1 :key :owner
                                   :include? :included? :indexes {:score :score :group :group}
                                   :searches {:text :text}} options))
  name)
(defn compact-cache-type-rank [x]
  (cond (boolean? x) (if x 3 2) (string? x) 16 (integer? x) 32
        (uuid? x) 48 (keyword? x) 56 (vector? x) 64))
(defn compact-cache-identity-compare [direction a b]
  (let [primary (compare (str a) (str b))]
    (if (zero? primary) (compare (compact-cache-type-rank a) (compact-cache-type-rank b))
        (if (= direction :desc) (- primary) primary))))
(defn compact-cache-ordered [rows index direction]
  (vec (sort (fn [a b]
               (let [value #(if (= index :identity) (str (:owner %)) (get % index))
                     c (compare (value a) (value b))]
                 (if (zero? c) (compact-cache-identity-compare :asc (:owner a) (:owner b))
                     (if (= direction :desc) (- c) c)))) rows)))
(defn compact-cache-page-ids [context model options]
  (mapv :key (:entries (rmp/page context model options))))
(defn compact-cache-snapshot []
  (kv/transact @*compact-cache* :read #((:reduce %) {} (fn [m {:keys [k v]}] (assoc m (vec k) (vec v))) {})))

(deftest ordered-codec-preserves-all-utf16-units-and-boundaries
  (compact-cache-fixture
    (fn []
      (let [units (mapv #(str (char %)) (range 65536))
            encoded (mapv core/key-encode units)]
        (is (every? true? (map #(= %1 (fold-key-decode-physical %2)) units encoded)))
        (is (every? neg? (map #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) encoded (rest encoded))))
        (is (every? pos? (map #(Arrays/compareUnsigned ^bytes (core/key-descending %1) ^bytes (core/key-descending %2)) encoded (rest encoded)))))
      (doseq [a ["" "a" "a\u0000" "\u0000" "\u007f" "\u0080" "\u0101" "\ud800" "\udc00" "\ud800\udc00" "\ue000" "\uffff"]
              b ["" "a" "\u0000" "\u0101" "\uffff"]]
        (is (= [a [b :x] 17] (fold-key-decode-physical (core/key-encode [a [b :x] 17]))))
        (let [prefix (core/key-tuple-prefix [a]) value (core/key-encode [a b])]
          (is (= (vec prefix) (vec (take (alength ^bytes prefix) value))))))
      (is (= 511 (alength ^bytes (core/key-checked (byte-array 511)))))
      (is (= :grain.read-model/key-too-large
             (try (core/key-checked (byte-array 512)) nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (let [k (byte-array (repeat 511 (byte 42)))]
        (kv/put! @*compact-cache* {:k k :v (byte-array [19])})
        (is (= [19] (vec (kv/get! @*compact-cache* {:k k}))))))))

(deftest previously-accepted-long-unicode-filtered-identities-still-fit
  (compact-cache-fixture
    (fn []
      (let [model (compact-cache-register! :compact-test/unicode {:filtered-indexes #{:identity}})
            owners [(apply str (repeat 60 "\u0101")) (apply str (repeat 60 "\u07ff"))
                    (apply str (repeat 60 "\uffff"))]
            rows (mapv #(hash-map :owner % :score 1 :group ["a" 1] :text "alpha" :included? true) owners)]
        (compact-cache-append! (compact-cache-ctx) rows)
        (doseq [direction [:asc :desc]]
          (is (= (vec (sort #(compact-cache-identity-compare direction %1 %2) owners))
                 (:keys (rmp/filtered-key-page (compact-cache-ctx) model owners {:direction direction :limit 10})))))
        (compact-cache-reopen!)
        (doseq [row rows] (is (= row (rmp/value (compact-cache-ctx) model (:owner row)))))
        (is (= (set owners) (set (rmp/search-keys (compact-cache-ctx) model {:index :text :query "alpha"}))))))))

(deftest compact-codec-preserves-utf16-order-and-doubled-legacy-size-bound
  (compact-cache-fixture
    (fn []
      (let [units (mapv #(str (char %)) (range 65536))
            encoded (mapv core/key-encode-compact units)]
        (is (every? true? (map #(= %1 (core/key-decode-compact %2)) units encoded)))
        (is (every? neg? (map #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) encoded (rest encoded))))
        (is (every? true? (map #(<= (alength ^bytes %2) (* 2 (alength ^bytes (core/key-encode %1)))) units encoded)))
        (doseq [value ["" "a\u0000b" "\ud800" "\udc00" "\ud800\udc00"
                       ["a" ["\u0101" :x] 1] [false 9 (UUID. 1 2)]]]
          (is (= value (core/key-decode-compact (core/key-encode-compact value)))))))))

(deftest absent-identity-selection-at-physical-key-boundary-is-empty
  (compact-cache-fixture
    (fn []
      (let [model (compact-cache-register! :compact-test/empty-boundary {})
            overhead (alength ^bytes (#'core/keyed-index-prefix (byte-array 7) :identity :asc))
            exact (apply str (repeat (- 511 overhead 3) "x"))
            probes [exact (str exact "x") (apply str (repeat 200 "\u0101"))]]
        (is (= 511 (+ overhead (alength ^bytes (core/key-encode-compact exact)))))
        (compact-cache-append! (compact-cache-ctx) [{:owner "a" :score 1 :group ["a" 1] :text "alpha" :included? true}])
        (rmp/read-session (compact-cache-ctx) [model]
          (fn [prepared]
            (doseq [direction [:asc :desc] probe probes]
              (let [options {:index :identity :equals probe :direction direction}]
                (is (= [] (compact-cache-page-ids prepared model (assoc options :limit 10))))
                (is (= [] (rmp/index-keys prepared model (assoc options :limit 10))))
                (is (= [] (into [] (rmp/values prepared model options))))
                (is (= {:rank 0 :present? false} (rmp/index-rank prepared model options "a")))))))))))

(deftest absent-unicode-probes-retain-one-read-session-and-snapshot
  (compact-cache-fixture
    (fn []
      (let [model (compact-cache-register! :compact-test/probes {:filtered-indexes #{:identity}})
            long-id (apply str (repeat 60 "\u0101"))
            rows [{:owner "a" :score 1 :group ["a" 1] :text "alpha" :included? true}]
            callbacks (atom 0)]
        (compact-cache-append! (compact-cache-ctx) rows)
        (rmp/read-session (compact-cache-ctx) [model]
          (fn [prepared]
            (swap! callbacks inc)
            (is (nil? (rmp/value prepared model long-id)))
            (doseq [direction [:asc :desc] group ["a" long-id "absent"]]
              (is (= {:rank (if (= group "a") 1 0) :present? false}
                     (rmp/index-rank prepared model {:index :identity :equals group :direction direction} long-id))))
            (is (= ["a"] (:keys (rmp/filtered-key-page prepared model [long-id "a"] {:limit 10}))))))
        (is (= 1 @callbacks))))))

(deftest model-tenant-version-isolation-and-failed-first-materialization
  (compact-cache-fixture
    (fn []
      (let [model (compact-cache-register! :compact-test/isolation {})
            other (compact-cache-register! :compact-test/isolation-other {:result #(assoc % :variant :other)})
            alternate (assoc (compact-cache-ctx) :tenant-id (UUID. 4 8))
            row {:owner "same" :score 1 :group ["a" 1] :text "alpha" :included? true}]
        (compact-cache-append! (compact-cache-ctx) [row])
        (compact-cache-append! alternate [(assoc row :score 9)])
        (is (= 1 (:score (rmp/value (compact-cache-ctx) model "same"))))
        (is (= 9 (:score (rmp/value alternate model "same"))))
        (is (= :other (:variant (rmp/value (compact-cache-ctx) other "same"))))
        (compact-cache-register! model {:version 2 :result #(assoc % :variant :v2)})
        (is (= :v2 (:variant (rmp/value (compact-cache-ctx) model "same"))))
        (compact-cache-reopen!)
        (is (= :v2 (:variant (rmp/value (compact-cache-ctx) model "same"))))
        (is (= 9 (:score (rmp/value (assoc (compact-cache-ctx) :tenant-id (UUID. 4 8)) model "same"))))
        (compact-cache-register! model {})
        (is (= row (rmp/value (compact-cache-ctx) model "same")))
        (let [broken (compact-cache-register! :compact-test/abort {:result (fn [_] (throw (ex-info "expected failure" {})))})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected failure" (rmp/value (compact-cache-ctx) broken "same")))
          (let [identity [(:tenant-id (compact-cache-ctx)) broken 1]
                base (vec (get (core/namespace-resolve! @*compact-cache* [identity]) identity))
                after (compact-cache-snapshot)]
            (is (empty? (filter (fn [[k _]] (= base (vec (take (count base) k)))) after)))
            (compact-cache-reopen!)
            (is (= after (compact-cache-snapshot)))
            (compact-cache-register! broken {})
            (is (= row (rmp/value (compact-cache-ctx) broken "same")))
            (is (= base (vec (get (core/namespace-resolve! @*compact-cache* [identity]) identity))))
            (let [registry-records #(into {} (filter (fn [[k _]] (= [75 82 57] (vec (take 3 k))))) %)]
              (is (= (registry-records after) (registry-records (compact-cache-snapshot)))))))))))

(def compact-cache-owners [false "false" 1 "1" 2 "2" 10 "10" :a ":a" [1] "[1]"
             #uuid "00000000-0000-0000-0000-000000000003" "00000000-0000-0000-0000-000000000003"
             "" "a" "aa" "a\u0000" "\u007f" "\u0080" "\u0101" "\ue000" "\uffff"])

(defn compact-cache-assert-view! [context model ordering rows]
  (let [included (filterv :included? (vals rows)) ids (mapv :owner included)]
    (doseq [index [:identity :score :group] direction [:asc :desc]]
      (let [expected (mapv :owner (compact-cache-ordered included index direction))]
        (is (= expected (compact-cache-page-ids context model {:index index :direction direction :limit 100})))
        (is (= expected (compact-cache-page-ids context :compact-test/plain {:index index :direction direction :limit 100})))
        (is (= expected (:keys (rmp/filtered-key-page context model (concat ids ids [:absent])
                                                    {:index index :direction direction :limit 100}))))))
    (doseq [direction [:asc :desc] group ["1" "false" ":a" "[1]" "absent" nil 1]
            probe [1 "1" 2 "2" false "false" :a ":a" [1] "[1]" "missing"]]
      (let [matches (filter #(= group (str %)) ids)]
        (is (= {:rank (count (filter #(neg? (compact-cache-identity-compare :asc % probe)) matches))
                :present? (boolean (some #(= probe %) matches))}
               (rmp/index-rank context model {:index :identity :equals group :direction direction} probe)))
        (is (= {:rank (count (filter #(neg? (compact-cache-identity-compare :asc % probe)) matches))
                :present? (boolean (some #(= probe %) matches))}
               (rmp/index-rank context :compact-test/plain {:index :identity :equals group :direction direction} probe)))))
    (doseq [direction [:asc :desc] prefix [["a"] ["b"] ["absent"]]]
      (let [matching (filterv #(= (first prefix) (first (:group %))) included)]
        (is (= (mapv :owner (compact-cache-ordered matching :group direction))
               (compact-cache-page-ids context model {:index :group :prefix prefix :direction direction :limit 100})))))
    (let [expected (filterv #(str/includes? (:text %) "alpha") included)
          found (rmp/search-keys context model {:index :text :query "alpha"})]
      (is (= (set (map :owner expected)) (set found)))
      (doseq [direction [:asc :desc]]
        (is (= (mapv :owner (compact-cache-ordered expected :score direction))
               (:keys (rmp/key-page context model found {:index :score :direction direction :limit 100}))))))
    (doseq [direction [:asc :desc]]
      (let [expected (mapv :owner (compact-cache-ordered (mapv #(assoc % :score (if (:ordered? %) (:score %) -99)) included)
                                            :score direction))]
        (is (= expected (:keys (rmp/joined-page context model ordering
                                                {:index :score :direction direction :missing-value -99 :limit 100}))))))))

(deftest mixed-identities-corrections-exclusion-and-reopen-have-literal-oracles
  (compact-cache-fixture
    (fn []
      (let [opts {:filtered-indexes #{:identity :score :group}}
            model (compact-cache-register! :compact-test/members opts)
            _ (compact-cache-register! :compact-test/plain {})
            ordering (compact-cache-register! :compact-test/ordering (assoc opts :include? :ordered?))
            rows (into {} (map-indexed (fn [i id] [id {:owner id :score (mod i 4)
                                                       :group [(if (even? i) "a" "b") (mod i 3)]
                                                       :text (if (odd? i) "alpha" "beta")
                                                       :included? true :ordered? (not (zero? (mod i 3)))}]) compact-cache-owners))]
        (compact-cache-append! (compact-cache-ctx) (mapv rows compact-cache-owners))
        (compact-cache-assert-view! (compact-cache-ctx) model ordering rows)
        (let [changed (reduce #(update %1 %2 assoc :included? false :ordered? false :text "beta" :score 99)
                              rows [1 "2" false])]
          (compact-cache-append! (compact-cache-ctx) (mapv changed [1 "2" false]))
          (compact-cache-assert-view! (compact-cache-ctx) model ordering changed)
          (compact-cache-reopen!)
          (compact-cache-assert-view! (compact-cache-ctx) model ordering changed)
          (let [restored (reduce #(update %1 %2 assoc :included? true :ordered? true :text "alpha" :score -5)
                                changed [1 "2" false])]
            (compact-cache-append! (compact-cache-ctx) (mapv restored [1 "2" false]))
            (compact-cache-assert-view! (compact-cache-ctx) model ordering restored)))))))

;; ranked set test
;; Includes adaptations of FoundationDB RankedSetTest.java, commit
;; 8a20d30f318e529fa30084b265563e64eb3956e1.
;; Copyright 2015-2018 Apple Inc. and the FoundationDB project authors.
;; SPDX-License-Identifier: Apache-2.0
;; Modifications: Clojure tests over Grain LMDB transactions, unique membership
;; with payloads, and additional prefix, mutation, rollback and snapshot cases.
;; License and attribution: resources/META-INF/licenses/.


(def ^:dynamic *ranked-set-store* nil)
(defn ranked-set-fixture [f]
  (let [dir (str "/tmp/grain-ranked-test-" (random-uuid))
        store (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "ranked"}))]
    (binding [*ranked-set-store* store]
      (try (f)
           (finally
             (kv/stop store)
             (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true)))))))


(defn ranked-set-crc-hash [^bytes k]
  (let [crc (CRC32.)] (.update crc k) (unchecked-int (.getValue crc))))
(defn ranked-set-id-bytes [^UUID id]
  (.array (doto (ByteBuffer/allocate 16)
            (.putLong (.getMostSignificantBits id)) (.putLong (.getLeastSignificantBits id)))))
(defn ranked-set-level-entries [tx cfg level]
  ((:reduce tx) {:prefix (core/ranked-prefix cfg level)}
   (fn [out {:keys [k]}]
     (let [member (core/ranked-unpack cfg k)]
       (conj out [member (core/ranked-count-key tx cfg level member)]))) []))
(defn ranked-set-check-spans [tx cfg]
  ;; Upstream checkConsistency: each span is the sum of its lower-level range.
  (doseq [[k n] (ranked-set-level-entries tx cfg 0)]
    (is (= n (if (zero? (alength ^bytes k)) 0 1))))
  (doseq [level (range 1 (:levels cfg))]
    (let [entries (ranked-set-level-entries tx cfg level)]
      (doseq [[[k n] next] (map vector entries (concat (rest entries) [nil]))]
        (is (= n (core/ranked-count-range tx cfg (dec level) k (first next))))))))
(defn ranked-set-check-set [tx cfg expected]
  (let [expected (vec (sort #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) expected))]
    (ranked-set-check-spans tx cfg)
    (is (= (count expected) (core/ranked-size tx cfg)))
    (is (nil? (core/ranked-get-nth tx cfg -1)))
    (is (nil? (core/ranked-get-nth tx cfg (count expected))))
    (doseq [[i k] (map-indexed vector expected)]
      (is (= i (core/ranked-rank tx cfg k true)))
      (is (Arrays/equals ^bytes k ^bytes (core/ranked-get-nth tx cfg i))))))

(deftest upstream-unique-set-cases-with-payloads
  (ranked-set-fixture
    (fn []
      ;; Derived from basic/basicCrc/basicChange/basicRandom/rankAsThoughPresent.
      (doseq [[i hash-fn] (map-indexed vector [#(Arrays/hashCode ^bytes %) ranked-set-crc-hash core/ranked-random-hash])]
        (let [cfg (core/ranked-config (byte-array [(inc i)]) hash-fn 6)
              encode #(.array (doto (ByteBuffer/allocate 4) (.putInt (int %))))
              keys (mapv encode [5 15 25 35 45])
              payload (byte-array [42 7 9])]
          (kv/transact *ranked-set-store* :read
            (fn [tx]
              (is (= 0 (core/ranked-rank tx cfg (encode 9) false)))
              (is (nil? (core/ranked-rank tx cfg (encode 9) true)))
              (ranked-set-check-set tx cfg [])))
          (kv/transact *ranked-set-store* :write
            (fn [tx]
              (core/ranked-init! tx cfg)
              (doseq [k (reverse keys)] (is (true? (core/ranked-add! tx cfg k payload))))
              (core/ranked-init! tx cfg)
              (ranked-set-check-set tx cfg keys)))
          (kv/transact *ranked-set-store* :write
            (fn [tx]
              (doseq [k keys]
                (is (false? (core/ranked-add! tx cfg k (byte-array [99]))))
                (is (Arrays/equals payload ^bytes ((:get tx) (core/ranked-pack cfg 0 k)))))
              (doseq [i (range 56)]
                (is (= (count (filter #(< % i) [5 15 25 35 45]))
                       (core/ranked-rank tx cfg (encode i) false))))
              (is (false? (core/ranked-remove! tx cfg (encode 99))))
              (doseq [k keys] (is (true? (core/ranked-remove! tx (assoc cfg :hash-fn ranked-set-crc-hash) k))))
              (ranked-set-check-set tx cfg [])))
          (is (thrown? IllegalArgumentException
                       (kv/transact *ranked-set-store* :read #(core/ranked-rank % cfg (byte-array 0) false)))))))))

(deftest prefix-keys-random-mutations-and-rollback
  (ranked-set-fixture
    (fn []
      (let [cfg (core/ranked-config (byte-array [12]) ranked-set-crc-hash 6)
            keys (vec (concat (map byte-array [[0] [0 0] [0 0 0] [0 1] [1] [1 0] [1 -1] [-1] [-1 0] [-1 -1]])
                              (map #(ranked-set-id-bytes (UUID. % %)) (range 80))))
            model (atom #{}) random (Random. 724)]
        (kv/transact *ranked-set-store* :write #(core/ranked-init! % cfg))
        (dotimes [_ 30]
          (kv/transact *ranked-set-store* :write
            (fn [tx]
              (dotimes [_ 20]
                (let [i (.nextInt random (count keys)) add? (.nextBoolean random)
                      had (contains? @model i)]
                  (is (= (if add? (not had) had)
                         ((if add? core/ranked-add! core/ranked-remove!) tx cfg (nth keys i))))
                  (swap! model (if add? conj disj) i)))))
          (kv/transact *ranked-set-store* :read #(ranked-set-check-set % cfg (map keys @model))))
        (is (thrown-with-msg? Exception #"rollback"
              (kv/transact *ranked-set-store* :write
                #(do (doseq [k keys] (core/ranked-add! % cfg k)) (throw (ex-info "rollback" {}))))))
        (kv/transact *ranked-set-store* :read #(ranked-set-check-set % cfg (map keys @model)))))))

(deftest colliding-id-patterns-do-not-determine-promotion
  (ranked-set-fixture
    (fn []
      (let [random (Random. 42)
            cfg (core/ranked-config (byte-array [13]) (fn [_] (.nextInt random)) 6)
            keys (mapv #(ranked-set-id-bytes (UUID. % %)) (range 4096))]
        (kv/transact *ranked-set-store* :write
          #(do (core/ranked-init! % cfg) (doseq [k keys] (core/ranked-add! % cfg k))))
        (kv/transact *ranked-set-store* :read
          (fn [tx]
            (ranked-set-check-spans tx cfg)
            (let [visits (atom 0)
                  counted (assoc tx :reduce
                                 (fn [bounds rf initial]
                                   ((:reduce tx) bounds
                                    (fn [acc entry] (swap! visits inc) (rf acc entry)) initial)))]
              (is (Arrays/equals ^bytes (peek keys) ^bytes (core/ranked-get-nth counted cfg 4095)))
              (is (< @visits 500) "Seek skips spans instead of following the ID pattern"))))))))

(deftest concurrent-writers-and-snapshot-isolation
  (ranked-set-fixture
    (fn []
      (let [cfg (core/ranked-config (byte-array [14]))
            keys (mapv #(ranked-set-id-bytes (UUID. 0 %)) (range 200))]
        (kv/transact *ranked-set-store* :write #(core/ranked-init! % cfg))
        (let [writers (mapv (fn [batch]
                             (future (kv/transact *ranked-set-store* :write #(doseq [k batch] (core/ranked-add! % cfg k)))))
                           (partition-all 50 keys))]
          (doseq [writer writers] (is (not= :ai.obney.grain.read-model-processor-v2.ranked-set-test/timeout (deref writer 20000 :ai.obney.grain.read-model-processor-v2.ranked-set-test/timeout)))))
        (kv/transact *ranked-set-store* :read
          (fn [tx]
            (ranked-set-check-set tx cfg keys)
            (is (not= :ai.obney.grain.read-model-processor-v2.ranked-set-test/timeout
                      (deref (future (kv/transact *ranked-set-store* :write #(doseq [k (take 100 keys)] (core/ranked-remove! % cfg k))))
                             20000 :ai.obney.grain.read-model-processor-v2.ranked-set-test/timeout)))
            (ranked-set-check-set tx cfg keys)))
        (kv/transact *ranked-set-store* :read #(ranked-set-check-set % cfg (drop 100 keys)))))))

;; concurrency test
(defschemas concurrency-race-schemas
  {:race/seed [:map]
   :race/increment [:map]})

(def ^:dynamic *concurrency-role* nil)

(defn concurrency-await! [p]
  (let [result (deref p 20000 :ai.obney.grain.read-model-processor-v2.concurrency-test/timeout)]
    (when (= :ai.obney.grain.read-model-processor-v2.concurrency-test/timeout result)
      (throw (ex-info "Timed out waiting for deterministic race barrier" {})))
    result))

(defn concurrency-check! [condition message]
  (when-not (is condition message) (throw (ex-info message {}))))

(defn concurrency-reproduce! [segmented?]
  (let [dir (str "/tmp/grain-cache-race-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "race"}))
        tenant (random-uuid)
        name (if segmented? :race/segmented :race/monolithic)
        base-key (core/format-scoped-key name 1 tenant)
        reducer (fn [state event]
                  (case (:event/type event)
                    :race/seed (assoc (if segmented?
                                       (into {} (map (fn [i] [i 0]) (range 10001)))
                                       {})
                                     :count 0)
                    :race/increment (update state :count inc)))
        context {:event-store store :cache cache :tenant-id tenant}
        query {:tenant-id tenant :types #{:race/seed :race/increment}}
        args {:f reducer :query (dissoc query :tenant-id)
              :name name :version 1 :l1-ttl-ms 0}
        project #(rmp/p context args)
        append! (fn [type n]
                  (es/append store {:tenant-id tenant
                                    :events (mapv (fn [_] (es/->event {:type type}))
                                                  (range n))}))
        older-ready (promise)
        release-older (promise)
        reader-ready (promise)
        release-reader (promise)
        workers (atom [])
        original-snapshot kv/read-snapshot
        original-put kv/put!
        original-batch kv/put-batch!
        pause-older! (fn []
                       (when (= *concurrency-role* :older)
                         (deliver older-ready true)
                         (concurrency-await! release-older)))]
    (try
      (rmp/l1-clear!)
      (append! :race/seed 1)
      (concurrency-check! (= 0 (:count (project))) "Seed projection failed")
      (let [initial (fressian-util/decode (kv/get! cache {:k base-key}))]
        (concurrency-check! (= segmented? (boolean (:segmented initial))) "Wrong cache format"))
      (append! :race/increment 10)
      ;; Wrappers only pause real operations at legal scheduling boundaries.
      ;; All bytes, event reads, reducers, and LMDB commits remain unchanged.
      (with-redefs [kv/read-snapshot
                    (fn [c f]
                      (original-snapshot
                       c
                       (fn [get-value]
                         (f (fn [{:keys [k] :as request}]
                              (let [bytes (get-value request)]
                                (when (and (= *concurrency-role* :reader)
                                           (java.util.Arrays/equals ^bytes k ^bytes base-key))
                                  (deliver reader-ready (fressian-util/decode bytes))
                                  (concurrency-await! release-reader))
                                bytes))))))
                    kv/put! (fn [c request]
                              (pause-older!)
                              (original-put c request))
                    kv/put-batch! (fn [c request]
                                    (pause-older!)
                                    (original-batch c request))]
        (try
          ;; A computes count=10 and updates L1, then pauses before its L2 write.
          (let [older (future (binding [*concurrency-role* :older] (project)))]
            (swap! workers conj older)
            (concurrency-await! older-ready)
            ;; B sees ten more events and publishes a coherent count=20 snapshot.
            (append! :race/increment 10)
            (concurrency-check! (= 20 (:count (project))) "Newer writer did not reach 20")
            ;; Force C through L2. This is also the path taken after L1 eviction.
            (rmp/l1-clear!)
            (let [reader (future (binding [*concurrency-role* :reader] (project)))]
              (swap! workers conj reader)
              (let [captured (concurrency-await! reader-ready)
                    events (into [] (es/read store query))
                    head (:event/id (last events))
                    expected (reduce reducer {} events)]
                (concurrency-check! (= head (:watermark captured)) "Reader did not capture latest watermark")
                ;; A now commits the older snapshot while C retains the new manifest.
                (deliver release-older true)
                (concurrency-check! (= 10 (:count (concurrency-await! older))) "Older writer did not finish at 10")
                (deliver release-reader true)
                (let [actual (concurrency-await! reader)
                      cached (core/l1-get-entry (String. ^bytes base-key))
                      remaining (into [] (es/read store (assoc query :after (:watermark cached))))
                      again (project)
                      result {:format (if segmented? :segmented :monolithic)
                              :log-increments 20
                              :full-replay (:count expected)
                              :reader-result (:count actual)
                              :l1-count (get-in cached [:state :count])
                              :l1-watermark-is-log-head (= head (:watermark cached))
                              :events-after-l1-watermark (count remaining)
                              :next-read (:count again)}]
                  (prn result)
                  (concurrency-check! (= head (:watermark cached)) "L1 watermark is not at head")
                  (concurrency-check! (empty? remaining) "Unexpected unprocessed events")
                  (concurrency-check! (= expected actual (:state cached) again)
                          "Every read must equal the pure fold through its watermark")
                  (concurrency-check! (= 20 (:count expected)) "Full replay oracle failed")))))
          (finally
            ;; Join workers before restoring the wrapped Vars or closing LMDB.
            (deliver release-older true)
            (deliver release-reader true)
            (doseq [worker @workers]
              (when (= :ai.obney.grain.read-model-processor-v2.concurrency-test/timeout (deref worker 20000 :ai.obney.grain.read-model-processor-v2.concurrency-test/timeout))
                (future-cancel worker))))))
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (doseq [file (reverse (file-seq (io/file dir)))]
          (io/delete-file file true))))))


(deftest monolithic-cache-remains-coherent-during-checkpoint-rollback
  (concurrency-reproduce! false))

(deftest segmented-cache-remains-coherent-during-checkpoint-rollback
  (concurrency-reproduce! true))

;; joined page test
(def joined-page-shape [:keys :total :total-pages :page :page-size])
(defn joined-page-models []
  [(keyed-register! merge {:include? :member?})
   (keyed-register! merge {:include? :ledger? :indexes {:balance :balance}
                             :filtered-indexes #{:balance}})])
(defn joined-page-expected [rows {:keys [direction page limit missing-value] :as options}]
  (let [candidates (when (contains? options :keys) (set (:keys options)))
        value #(if (:ledger? %) (:balance %) missing-value)
        ordered (vec (sort (fn [a b]
                             (let [c (compare (value a) (value b))]
                               (if (zero? c) (compare (str (:owner a)) (str (:owner b)))
                                 (if (= :desc direction) (- c) c))))
                           (filter #(and (:member? %) (or (nil? candidates) (candidates (:owner %)))) rows)))
        total (count ordered) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min page pages) offset (* (dec page) limit)]
    {:keys (mapv :owner (subvec ordered offset (min total (+ offset limit))))
     :total total :total-pages pages :page page :page-size limit}))

(deftest joined-pages-match-independent-left-join-oracle
  (keyed-fixture
    (fn []
      (let [[members ledger] (joined-page-models)
            rows (mapv (fn [i] {:owner (str i) :member? (not (zero? (mod i 11)))
                                :ledger? (not (zero? (mod i 3)))
                                :balance (nth [-100 0 100 200] (mod i 4))}) (range 257))
            check! (fn [rows]
                     (rmp/read-session *keyed-ctx* [members ledger]
                       (fn [ctx]
                         (doseq [direction [:asc :desc] page [1 2 99] missing [-250 0 100 250]
                                 selection [{} {:keys []} {:keys (concat (map :owner (take-nth 2 rows)) ["ghost" "2" "2"])}]
                                 :let [options (merge {:index :balance :direction direction :page page :limit 25
                                                      :missing-value missing} selection)]]
                           (is (= (joined-page-expected rows options)
                                  (select-keys (rmp/joined-page ctx members ledger options) joined-page-shape)))))))]
        (keyed-append! rows)
        (check! rows)
        (let [changed (mapv #(-> % (update :member? not) (update :ledger? not) (assoc :balance 0)) (take 80 rows))]
          (keyed-append! changed)
          (check! (into changed (drop 80 rows))))
        (keyed-append! rows)
        (check! rows)
        (let [seen (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
          (rmp/joined-page ctx members ledger {:index :balance :missing-value 0 :page 4})
          (is (not-any? #(#{:state-decoded :result-decoded :sort-key-read :event-applied} (:operation %)) @seen)))
        (is (= 0 (:total (rmp/joined-page (assoc *keyed-ctx* :tenant-id (random-uuid)) members ledger
                                          {:index :balance :missing-value 0}))))))))

(deftest completely-absent-ledgers-and-empty-populations
  (keyed-fixture
    (fn []
      (let [members (keyed-register! merge {:include? :member?})
            ledger (keyed-register! merge {:events #{:keyed/other} :indexes {:balance :balance}
                                            :filtered-indexes #{:balance}})
            rows (mapv #(hash-map :owner (str %) :member? true) (range 53))]
        (is (= 0 (:total (rmp/joined-page *keyed-ctx* members ledger {:index :balance :missing-value 0}))))
        (keyed-append! rows)
        (doseq [direction [:asc :desc] page [1 2 999]
                selection [{} {:keys (reverse (map :owner rows))}]
                :let [opts (merge {:index :balance :missing-value 0 :direction direction :page page :limit 25} selection)]]
          (is (= (joined-page-expected rows opts)
                 (select-keys (rmp/joined-page *keyed-ctx* members ledger opts) joined-page-shape))))
        (is (thrown? Exception (rmp/joined-page *keyed-ctx* members ledger {:index :balance})))
        (is (thrown? Exception (rmp/joined-page *keyed-ctx* members ledger
                                               {:index :balance :missing-value 0 :sort-value identity})))))))

(deftest membership-and-ordering-share-snapshot
  (keyed-fixture
    (fn []
      (let [[members ledger] (joined-page-models) ctx *keyed-ctx*
            opts {:index :balance :missing-value 0 :page 2 :limit 1}]
        (keyed-append! [{:owner "a" :member? true :ledger? true :balance -100}
                         {:owner "b" :member? true :ledger? false :balance 500}
                         {:owner "c" :member? true :ledger? true :balance 100}])
        (rmp/read-session ctx [members ledger]
          (fn [prepared]
            (is (= ["b"] (:keys (rmp/joined-page prepared members ledger opts))))
            (let [writer (future
                           (keyed-append! ctx [{:owner "b" :member? true :ledger? true :balance 200}])
                           (rmp/read-session ctx [members ledger] (constantly nil)))]
              (is (not= :ai.obney.grain.read-model-processor-v2.joined-page-test/timeout (deref writer 20000 :ai.obney.grain.read-model-processor-v2.joined-page-test/timeout)))
              (is (= ["b"] (:keys (rmp/joined-page prepared members ledger opts)))))))
        (is (= ["c"] (:keys (rmp/joined-page ctx members ledger opts))))))))

;; namespace registry test
(def namespace-registry-identities
  [[(UUID. 1 1) :registry-test/a 1]
   [(UUID. 1 2) :registry-test/a 1]
   [(UUID. 1 1) :registry-test/b 1]
   [(UUID. 1 1) :registry-test/a 2]
   [(UUID. 1 1) :grain.read-query/shared-identities 1]])

(defn namespace-registry-bytes-map [m] (into {} (map (fn [[k v]] [k (vec v)])) m))

(deftest registry-addresses-are-compact-distinct-durable-and-order-independent
  (compact-cache-fixture
    (fn []
      (let [first (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* namespace-registry-identities))
            before (compact-cache-snapshot)]
        (is (= (set namespace-registry-identities) (set (keys first))))
        (is (every? #(= 7 (count %)) (vals first)))
        (is (= (count namespace-registry-identities) (count (set (vals first)))))
        (is (= first (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* (reverse namespace-registry-identities)))))
        (is (= before (compact-cache-snapshot)))
        (compact-cache-reopen!)
        (is (= first (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* namespace-registry-identities))))
        (is (= before (compact-cache-snapshot)))))))

(deftest concurrent-first-resolution-publishes-one-collision-free-address-per-identity
  (compact-cache-fixture
    (fn []
      (let [ready (promise)
            tasks (mapv (fn [i]
                          (future
                            @ready
                            (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache*
                                                 (conj namespace-registry-identities [(UUID. 9 i) :registry-test/concurrent 1])))))
                        (range 12))]
        (deliver ready true)
        (let [results (mapv #(deref % 20000 :ai.obney.grain.read-model-processor-v2.namespace-registry-test/timeout) tasks)]
          (is (not-any? #{:ai.obney.grain.read-model-processor-v2.namespace-registry-test/timeout} results))
          (when (every? map? results)
            (is (apply = (map #(select-keys % namespace-registry-identities) results)))
            (let [all (apply merge results)]
              (is (= (+ 12 (count namespace-registry-identities)) (count all)))
              (is (= (count all) (count (set (vals all)))))
              (compact-cache-reopen!)
              (is (= all (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* (keys all))))))))))))

(deftest registry-write-failure-rolls-back-forward-reverse-and-counter-together
  (compact-cache-fixture
    (fn []
      (doseq [fail-at [1 2 3 4 5]]
        (let [before (compact-cache-snapshot)
              original kv/transact
              writes (atom 0)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected registry write failure"
                (with-redefs [kv/transact
                              (fn [cache mode f]
                                (original cache mode
                                          (fn [tx]
                                            (f (if (= mode :write)
                                                 (assoc tx :put
                                                        (fn [k v]
                                                          (when (= fail-at (swap! writes inc))
                                                            (throw (ex-info "injected registry write failure" {})))
                                                          ((:put tx) k v)))
                                                 tx)))))]
                  (core/namespace-resolve! @*compact-cache* namespace-registry-identities))))
          (is (= before (compact-cache-snapshot)))
          (compact-cache-reopen!)
          (is (= before (compact-cache-snapshot)))))
      (let [all (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* namespace-registry-identities))]
        (is (= (count namespace-registry-identities) (count (set (vals all)))))))))

(deftest invalid-canonical-identities-cannot-partially-reserve-earlier-valid-ones
  (compact-cache-fixture
    (fn []
      (doseq [invalid [["tenant" :registry-test/a 1]
                       [(UUID. 1 1) :unqualified 1]
                       [(UUID. 1 1) :registry-test/a 0]
                       [(UUID. 1 1) :registry-test/a -1]
                       [(UUID. 1 1) :registry-test/a 1 "extra"]]]
        (let [before (compact-cache-snapshot)]
          (is (thrown? clojure.lang.ExceptionInfo (core/namespace-resolve! @*compact-cache* (conj namespace-registry-identities invalid))))
          (is (= before (compact-cache-snapshot))))))))

(defn namespace-registry-registry-var [sym]
  (ns-resolve 'ai.obney.grain.read-model-processor-v2.core (symbol (str "namespace-" sym))))

(defn namespace-registry-restore! [snapshot]
  (kv/transact @*compact-cache* :write
    (fn [tx]
      (doseq [k ((:reduce tx) {} (fn [out row] (conj out (:k row))) [])]
        ((:delete tx) k))
      (doseq [[k v] snapshot] ((:put tx) (byte-array k) (byte-array v))))))

(deftest malformed-missing-inverse-and-exhausted-registry-fail-closed
  (compact-cache-fixture
    (fn []
      (core/namespace-resolve! @*compact-cache* namespace-registry-identities)
      (let [original (compact-cache-snapshot)
            counter-key @(namespace-registry-registry-var 'counter-key)
            forward-key @(namespace-registry-registry-var 'forward-key)
            reverse-key @(namespace-registry-registry-var 'reverse-key)
            target (first namespace-registry-identities)
            forward (forward-key target)
            record (fressian-util/decode (kv/get! @*compact-cache* {:k forward}))
            reverse (reverse-key (:ordinal record))
            alter! (fn [k value] (kv/put! @*compact-cache* {:k k :v (fressian-util/encode value)}))]
        (doseq [mutation [(fn [] (kv/transact @*compact-cache* :write #((:delete %) reverse)))
                          (fn [] (kv/transact @*compact-cache* :write #((:delete %) forward)))
                          (fn [] (alter! reverse (assoc record :identity (second namespace-registry-identities))))
                          (fn [] (alter! forward (assoc record :ordinal -1)))
                          (fn [] (alter! counter-key {:next-id -1}))
                          (fn [] (alter! counter-key {:next-id 0}))
                          (fn [] (alter! counter-key {:next-id "bad"}))
                          (fn [] (kv/put! @*compact-cache* {:k forward :v (byte-array [127])}))]]
          (namespace-registry-restore! original)
          (mutation)
          (let [corrupt (compact-cache-snapshot)]
            (is (thrown? Exception (core/namespace-resolve! @*compact-cache* namespace-registry-identities)))
            (is (= corrupt (compact-cache-snapshot)))
            (compact-cache-reopen!)
            (is (thrown? Exception (core/namespace-resolve! @*compact-cache* namespace-registry-identities)))
            (is (= corrupt (compact-cache-snapshot)))))
        (namespace-registry-restore! original)
        (alter! counter-key {:next-id @(namespace-registry-registry-var 'max-ordinal)})
        (let [exhausted (compact-cache-snapshot)]
          (is (thrown? clojure.lang.ExceptionInfo
                (core/namespace-resolve! @*compact-cache* (conj namespace-registry-identities [(UUID. 8 8) :registry-test/new 1]))))
          (is (= exhausted (compact-cache-snapshot))))))))

(deftest canonical-identity-check-rejects-hash-collision-without-overwrite
  (compact-cache-fixture
    (fn []
      (with-redefs-fn {(namespace-registry-registry-var 'digest) (fn [_] (byte-array (repeat 32 (byte 7))))}
        (fn []
          (core/namespace-resolve! @*compact-cache* [(first namespace-registry-identities)])
          (let [before (compact-cache-snapshot)]
            (is (thrown? clojure.lang.ExceptionInfo (core/namespace-resolve! @*compact-cache* [(second namespace-registry-identities)])))
            (is (= before (compact-cache-snapshot)))
            (compact-cache-reopen!)
            (is (thrown? clojure.lang.ExceptionInfo (core/namespace-resolve! @*compact-cache* [(second namespace-registry-identities)])))
            (is (= before (compact-cache-snapshot)))))))))

(deftest genuinely-exhausted-ordinal-space-preserves-existing-addresses
  (compact-cache-fixture
    (fn []
      (with-redefs-fn {(namespace-registry-registry-var 'max-ordinal) 2}
        (fn []
          (let [existing (take 2 namespace-registry-identities)
                addresses (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* existing))
                before (compact-cache-snapshot)
                error (try (core/namespace-resolve! @*compact-cache* [(nth namespace-registry-identities 2)]) nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (some? error))
            (is (= :grain.read-model/namespace-exhausted (:type (ex-data error))))
            (is (= before (compact-cache-snapshot)))
            (compact-cache-reopen!)
            (is (= addresses (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* existing))))
            (is (= before (compact-cache-snapshot)))))))))

(deftest lost-registry-cannot-reassign-existing-materialization-addresses
  (compact-cache-fixture
    (fn []
      (let [model (compact-cache-register! :registry-test/materialized {})
            row {:owner "retained" :score 11 :group ["a" 1] :text "alpha" :included? true}]
        (compact-cache-append! (compact-cache-ctx) [row])
        (is (= row (rmp/value (compact-cache-ctx) model "retained")))
        (kv/transact @*compact-cache* :write
          (fn [tx]
            (doseq [k ((:reduce tx) {:prefix (byte-array [75 82 57])}
                       (fn [out row] (conj out (:k row))) [])]
              ((:delete tx) k))))
        (let [orphaned (compact-cache-snapshot)]
          (is (seq orphaned))
          (is (thrown? clojure.lang.ExceptionInfo
                (core/namespace-resolve! @*compact-cache* [[(UUID. 99 99) :registry-test/unrelated 1]])))
          (is (= orphaned (compact-cache-snapshot)))
          (compact-cache-reopen!)
          (is (thrown? clojure.lang.ExceptionInfo
                (core/namespace-resolve! @*compact-cache* [[(UUID. 99 99) :registry-test/unrelated 1]])))
          (is (= orphaned (compact-cache-snapshot))))))))

(deftest uncertain-successful-commit-is-recovered-without-a-second-allocation
  (compact-cache-fixture
    (fn []
      (let [original kv/transact
            injected (atom false)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"uncertain commit result"
              (with-redefs [kv/transact
                            (fn [cache mode f]
                              (let [result (original cache mode f)]
                                (when (and (= mode :write) (compare-and-set! injected false true))
                                  (throw (ex-info "uncertain commit result" {})))
                                result))]
                (core/namespace-resolve! @*compact-cache* namespace-registry-identities))))
        (let [committed (compact-cache-snapshot)]
          (compact-cache-reopen!)
          (let [addresses (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* namespace-registry-identities))]
            (is (= (count namespace-registry-identities) (count (set (vals addresses)))))
            (is (= committed (compact-cache-snapshot)))
            (compact-cache-reopen!)
            (is (= addresses (namespace-registry-bytes-map (core/namespace-resolve! @*compact-cache* namespace-registry-identities))))
            (is (= committed (compact-cache-snapshot)))))))))

;; ranked membership test
(def ranked-membership-shape [:keys :total :total-pages :page :page-size])
(defn ranked-membership-step [_ e] (select-keys e [:name :score :included?]))
(def ranked-membership-options {:include? :included? :indexes {:name :name :score :score}
              :searches {:text :name} :filtered-indexes #{:name :score :identity}})

(defn ranked-membership-assert-pages [ctx model keys]
  (rmp/read-session ctx [model]
    (fn [ctx]
      (doseq [index [:identity :name :score] direction [:asc :desc] page [1 2 7 999]]
        (let [opts {:index index :direction direction :page page :limit 7}]
          (is (= (select-keys (rmp/key-page ctx model keys opts) ranked-membership-shape)
                 (select-keys (rmp/filtered-key-page ctx model keys opts) ranked-membership-shape))))))))

(deftest composed-candidates-mutations-restart-and-isolation
  (keyed-fixture
    (fn []
      (let [model (keyed-register! ranked-membership-step ranked-membership-options)
            ;; Deliberately different membership/ordinal allocation in another model.
            other (keyed-register! ranked-membership-step (assoc ranked-membership-options :include? #(and (:included? %) (odd? (:score %)))))
            events (mapv (fn [i] {:owner (str i) :name (str "name-" (mod i 13))
                                  :score (- i 100) :included? (not (zero? (mod i 7)))}) (range 180))
            keys (mapv :owner events)]
        (keyed-append! events)
        (doseq [candidates [[] ["absent"] keys (take-nth 9 keys) (concat keys keys ["absent"])]]
          (ranked-membership-assert-pages *keyed-ctx* model candidates))
        (let [candidates (rmp/read-session *keyed-ctx* [model other]
                           #(rmp/search-keys % other {:index :text :query "name-1"}))]
          (ranked-membership-assert-pages *keyed-ctx* model candidates))
        (keyed-append! (mapv #(assoc % :name "changed" :score 0 :included? (not (:included? %))) (take 60 events)))
        (ranked-membership-assert-pages *keyed-ctx* model keys)
        (keyed-append! (take 60 events))
        (ranked-membership-assert-pages *keyed-ctx* model keys)
        (let [observed (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))]
          (rmp/filtered-key-page ctx model keys {:index :name :page 10})
          (is (not-any? #(#{:result-decoded :state-decoded :sort-key-read} (:operation %)) @observed)))
        (kv/stop (:cache *keyed-ctx*))
        (let [cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir *keyed-directory* :db-name "folds"
                                                   :map-size (* 64 1024 1024)}))]
          (reset! *keyed-cache-handle* cache)
          (ranked-membership-assert-pages (assoc *keyed-ctx* :cache cache) model keys)
          (is (= 0 (:total (rmp/filtered-key-page (assoc *keyed-ctx* :cache cache :tenant-id (random-uuid))
                                                model keys {:index :name})))))))))

(deftest validation-and-atomic-rollback
  (keyed-fixture
    (fn []
      (is (thrown? Exception (keyed-register! ranked-membership-step (assoc ranked-membership-options :filtered-indexes #{:missing}))))
      (let [plain (keyed-register! ranked-membership-step (dissoc ranked-membership-options :filtered-indexes))
            model (keyed-register! ranked-membership-step (assoc ranked-membership-options :result #(if (= "fail" (:name %))
                                                                   (throw (ex-info "fail" {})) %)))]
        (is (thrown? Exception (rmp/filtered-key-page *keyed-ctx* plain [] {:index :name})))
        (is (thrown? Exception (rmp/filtered-key-page *keyed-ctx* model [] {:index :name :sort-value identity})))
        (keyed-append! [{:owner "a" :name "before" :score 1 :included? true}])
        (rmp/filtered-key-page *keyed-ctx* model ["a"] {:index :name})
        (let [before (keyed-stored-bytes)]
          (keyed-append! [{:owner "b" :name "good" :score 2 :included? true}
                           {:owner "c" :name "fail" :score 3 :included? true}])
          (is (thrown? Exception (rmp/filtered-key-page *keyed-ctx* model ["a" "b" "c"] {:index :name})))
          (is (= before (keyed-stored-bytes))))))))

(deftest membership-without-text-index-and-with-nil-results
  (keyed-fixture
    (fn []
      (let [model (keyed-register! (fn [_ _] nil) {:filtered-indexes #{:identity}})]
        (keyed-append! [{:owner :b} {:owner :a}])
        (is (nil? (rmp/value *keyed-ctx* model :a :ai.obney.grain.read-model-processor-v2.ranked-membership-test/missing)))
        (is (= {:keys [:b] :total 2 :total-pages 2 :page 2 :page-size 1}
               (select-keys (rmp/filtered-key-page *keyed-ctx* model [:b :missing :a :a]
                                                 {:page 2 :limit 1}) ranked-membership-shape)))))))

(deftest span-splits-merges-and-random-mutations
  (keyed-fixture
    (fn []
      (let [rng (Random. 713) cfg (assoc (core/ranked-config (byte-array [99])
                                                      (fn [_] (nth [0 16 256 1 1 1] (.nextInt rng 6))) 6)
                                        :membership? true)
            store (:cache *keyed-ctx*) present (atom #{})
            candidates (doto (RoaringBitmap.) (.add (long 0) (long 160)))
            check! (fn []
                     (kv/transact store :read
                       (fn [tx]
                         (is (= (count @present) (core/ranked-size tx cfg)))
                         (doseq [level (range 1 6)]
                           (let [entries ((:reduce tx) {:prefix (core/ranked-prefix cfg level)}
                                          (fn [out {:keys [k]}] (conj out (core/ranked-unpack cfg k))) [])]
                             (doseq [[begin end] (map vector entries (concat (rest entries) [nil]))]
                               (let [expected (set (filter (fn [i]
                                                            (let [k (core/membership-number-bytes i)]
                                                              (and (not (neg? (Arrays/compareUnsigned k begin)))
                                                                   (or (nil? end) (neg? (Arrays/compareUnsigned k end)))))) @present))
                                     bits (core/membership-bitmap tx (core/ranked-membership-key cfg level begin))]
                                 (is (= expected (set (.toArray bits))))))))
                         (doseq [page [1 3 30]]
                           (let [result (core/ranked-filtered-page tx cfg candidates page 7 (fn [& _]))
                                 ordered (vec (sort @present))
                                 offset (* (dec (:page result)) 7)]
                             (is (= (count ordered) (:total result)))
                             (is (= (subvec ordered offset (min (count ordered) (+ offset 7)))
                                    (mapv core/membership-number-value (:payloads result)))))))))]
        (kv/transact store :write #(core/ranked-init! % cfg))
        (dotimes [_ 16]
          (kv/transact store :write
            (fn [tx]
              (let [tx (core/membership-transaction tx)]
                (dotimes [_ 40]
                  (let [id (.nextInt rng 160) key (core/membership-number-bytes id)]
                    (if (.nextBoolean rng)
                      (do (core/ranked-add! tx cfg key key id) (swap! present conj id))
                      (do (core/ranked-remove! tx cfg key) (swap! present disj id)))))
                (core/membership-flush! tx (fn [& _])))))
          (check!))
        (is (thrown? Exception
              (kv/transact store :write
                (fn [tx]
                  (let [tx (core/membership-transaction tx)]
                    (doseq [id @present] (core/ranked-remove! tx cfg (core/membership-number-bytes id)))
                    (core/membership-flush! tx (fn [& _]))
                    (throw (ex-info "rollback" {})))))))
        (check!)))))

(deftest snapshot-and-replay-equivalence
  (keyed-fixture
    (fn []
      (let [model (keyed-register! ranked-membership-step ranked-membership-options)
            ctx *keyed-ctx* keys (mapv str (range 40))
            events (mapv (fn [id] {:owner id :name "same" :score 0 :included? true}) keys)
            page-options {:index :name :page 2 :limit 10 :direction :desc}]
        (keyed-append! events)
        (rmp/read-session ctx [model]
          (fn [prepared]
            (let [before (rmp/filtered-key-page prepared model keys page-options)
                  writer (future
                           (keyed-append! ctx (mapv #(assoc % :included? false) (take 30 events)))
                           (rmp/progress ctx model))]
              (is (not= :ai.obney.grain.read-model-processor-v2.ranked-membership-test/timeout (deref writer 20000 :ai.obney.grain.read-model-processor-v2.ranked-membership-test/timeout)))
              (is (= before (rmp/filtered-key-page prepared model keys page-options))))))
        (let [current (rmp/filtered-key-page ctx model keys page-options)
              replay (keyed-register! ranked-membership-step ranked-membership-options)]
          (is (= 10 (:total current)))
          (is (= (select-keys current ranked-membership-shape)
                 (select-keys (rmp/filtered-key-page (assoc ctx :read-model/batch-size 1) replay keys page-options) ranked-membership-shape))))))))

;; read query test
(defn read-query-setup []
  (let [customer (keyed-register! (fn [_ e] (:customer e))
                                   {:include? some? :indexes {:name (rmp/order-key [:name]) :company :company}
                                    :searches {:text (rmp/search-text [:name :email])}})
        company (keyed-register! (fn [_ e] (:company e))
                                  {:key :company-id :include? some? :searches {:text (rmp/search-text [:name])}})
        ledger (keyed-register! (fn [_ e] (:ledger e))
                                  {:include? some? :indexes {:amount (rmp/order-key [:amount])}
                                   :filtered-indexes #{:amount}})
        definition {:from {:customer customer}
                    :related {:company {:model company :key :customer/company}
                              :ledger {:model ledger :key [:id :customer] :missing [:default {:amount 0}]}}
                    :search [:customer/name :customer/email :company/name]
                    :orders {:name [:customer/name] :company [:company/name] :amount [:ledger/amount]
                             :computed [[:call + :ledger/amount [:param :offset]]]}}]
    {:customer customer :company company :ledger ledger :definition definition}))

(defn read-query-seed! []
  (keyed-append! (mapv (fn [i] {:owner (str i) :company-id (str "c" (mod i 3))
                                  :customer {:name (str "Name " (mod i 7)) :email "example.invalid"
                                            :company (str "c" (mod i 3))}
                                  :company {:name (if (= 2 (mod i 3)) "Zulu" "Same")}
                                  :ledger {:amount (- i 20)}}) (range 61))))

(defn read-query-oracle [ctx {:keys [customer company ledger]} options]
  (let [companies (into {} (map (juxt :key :value)) (rmp/values ctx company))
        ledgers (into {} (map (juxt :key :value)) (rmp/values ctx ledger))
        rows (map (fn [{:keys [key value]}] {:key key :customer value :company (get companies (:company value))
                                             :ledger (get ledgers key {:amount 0})}) (into [] (rmp/values ctx customer)))
        text (str/lower-case (str/trim (:search options "")))
        rows (filter #(str/includes? (str/lower-case (str (get-in % [:customer :name]) " "
                                                          (get-in % [:customer :email]) " " (get-in % [:company :name]))) text) rows)
        order (case (:order options) :name #(str/lower-case (get-in % [:customer :name]))
                    :company #(str/lower-case (get-in % [:company :name]))
                    :amount #(get-in % [:ledger :amount])
                    :computed #(+ (get-in % [:ledger :amount]) (get-in options [:params :offset])))
        rows (sort (fn [a b] (let [c (compare (order a) (order b))]
                               (if (zero? c) (compare (:key a) (:key b))
                                   (if (= :desc (:direction options)) (- c) c)))) rows)
        total (count rows) limit (:limit options 7) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min (:page options 1) pages)]
    {:rows (vec (take limit (drop (* (dec page) limit) rows))) :total total :total-pages pages
     :page page :page-size limit}))

(deftest query-matches-composition-and-preserves-exact-membership
  (keyed-fixture
    (fn []
      (let [{:keys [definition ledger] :as models} (read-query-setup) ctx *keyed-ctx*]
        (read-query-seed!)
        (doseq [order [:name :company :amount :computed] direction [:asc :desc]
                search ["" "NAME" "example" "valid sam" "absent"] page [1 2 999]
                :let [options {:order order :direction direction :search search :page page :limit 7 :params {:offset 10}}]]
          (is (= (read-query-oracle ctx models options) (dissoc (rmp/query ctx definition options) :work)) (pr-str options)))
        (testing "Equal cardinality is not sufficient to use the related index directly"
          (keyed-append! [{:owner "0" :company-id "c0" :customer {:name "Missing" :email "example.invalid" :company "c0"}
                             :company {:name "Same"} :ledger nil}
                            {:owner "outsider" :company-id "unused" :customer nil :company nil :ledger {:amount -999}}])
          (doseq [direction [:asc :desc] page [1 2 999]
                  :let [options {:order :amount :direction direction :page page :limit 7}]]
            (is (= (read-query-oracle ctx models options) (dissoc (rmp/query ctx definition options) :work)))
            (is (= :joined (get-in (rmp/query ctx definition options) [:work :strategy])))))
        (is (= 3 (count (rmp/query-models definition))))
        (is (= #{ledger} (set (filter #(= % ledger) (keys (rmp/query-models definition))))))))))

(deftest indexed-paths-do-not-read-off-page-customers
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup) ctx *keyed-ctx* seen (atom [])
            measured (assoc ctx :read-model/observer #(swap! seen conj %))]
        (read-query-seed!)
        (rmp/query ctx definition {:order :name})
        (doseq [order [:name :company :amount] page [1 999]]
          (reset! seen [])
          (let [result (rmp/query measured definition {:order order :page page :limit 7})]
            (is (<= (count (filter #(= :result-decoded (:operation %)) @seen)) 24))
            (is (not-any? #(#{:state-decoded :join-membership-entry} (:operation %)) @seen))
            (is (#{:indexed :related-groups} (get-in result [:work :strategy])))))))))

(deftest malformed-definitions-and-short-searches
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup) ctx *keyed-ctx*]
        (doseq [bad [(assoc definition :fields {})
                     (assoc-in definition [:related :company :key] :missing/id)
                     (assoc-in definition [:related :company :key] :company/id)
                     (assoc-in definition [:orders :bad] [[:unknown :customer]])
                     (assoc-in definition [:related :ledger :missing] :optional)]]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/query-models bad))))
        (let [seen (atom []) result (rmp/query (assoc ctx :read-model/observer #(swap! seen conj %))
                                               definition {:order :name :search "😀a"})]
          (is (= [] (:rows result)))
          (is (= 0 (:total result)))
          (is (empty? @seen)))))))

(deftest required-validation-and-optional-nil
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup) ctx *keyed-ctx*]
        (keyed-append! [{:owner "a" :company-id "missing" :customer {:name "Anna" :company "missing"}}])
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required related result"
                              (rmp/query ctx definition {:order :name})))
        (let [optional (assoc-in definition [:related :company :missing] [:default nil])]
          (is (nil? (get-in (rmp/query ctx optional {:order :name}) [:rows 0 :company]))))
        (let [checked (assoc-in definition [:related :company :validate]
                                (fn [id value] (when-not value (throw (ex-info "Domain company error" {:id id})))))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Domain company error"
                                (rmp/query ctx checked {:order :name}))))))))

(deftest stored-nil-is-not-a-missing-default
  (keyed-fixture
    (fn []
      (let [root (keyed-register! (fn [_ e] (:customer e)) {:indexes {:name (rmp/order-key [:name])}})
            nullable (keyed-register! (fn [_ _] nil) {})
            definition {:from {:customer root}
                        :related {:nullable {:model nullable :key [:id :customer] :missing [:default :absent]}}
                        :orders {:name [:customer/name]}}]
        (keyed-append! [{:owner "a" :customer {:name "Anna"}}])
        (is (nil? (get-in (rmp/query *keyed-ctx* definition {:order :name}) [:rows 0 :nullable])))))))

(deftest search-defaults-cannot-silently-lose-matches
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup)
            definition (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}])]
        (keyed-append! [{:owner "a" :company-id "absent" :customer {:name "Anna" :company "absent"}}])
        (is (= "Fallback" (get-in (rmp/query *keyed-ctx* (dissoc definition :search) {:order :name})
                                  [:rows 0 :company :name])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nil missing default"
                              (rmp/query-models definition)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nil missing default"
                              (rmp/query *keyed-ctx* definition {:order :name :search "Fallback"}))))
      (let [{:keys [definition]} (read-query-setup)]
        (read-query-seed!)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with root"
                              (rmp/query *keyed-ctx* (assoc definition :search [:company/name])
                                         {:order :name :search "Same"})))))))

(deftest query-freshness-and-tenant-isolation
  (keyed-fixture
    (fn []
      (let [{:keys [definition] :as models} (read-query-setup) ctx *keyed-ctx*
            other (assoc ctx :tenant-id (random-uuid))]
        (read-query-seed!)
        (keyed-append! other [{:owner "0" :company-id "c0"
                                :customer {:name "Other" :email "other.invalid" :company "c0"}
                                :company {:name "Other company"} :ledger {:amount 777}}])
        (is (= 1 (:total (rmp/query other definition {:order :amount}))))
        (is (= 61 (:total (rmp/query ctx definition {:order :amount}))))
        (keyed-append! [{:owner "0" :company-id "c0"
                           :customer {:name "Renamed" :email "example.invalid" :company "c0"}
                           :company {:name "Renamed company"} :ledger {:amount 999}}])
        (doseq [options [{:order :company :search "Renamed company"}
                        {:order :amount :direction :desc}
                        {:order :computed :params {:offset 100} :direction :desc}]]
          (is (= (read-query-oracle ctx models (assoc options :limit 7))
                 (dissoc (rmp/query ctx definition (assoc options :limit 7)) :work))))
        (is (= "Other company" (get-in (rmp/query other definition {:order :name}) [:rows 0 :company :name])))))))

(deftest related-validation-is-independent-of-page-strategy
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup)
            checked (assoc-in definition [:related :ledger :validate]
                              (fn [_ _] (throw (ex-info "Invalid ledger" {}))))]
        (read-query-seed!)
        (doseq [order [:name :amount] search ["" "example"]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid ledger"
                                (rmp/query *keyed-ctx* checked {:order order :search search}))))))))

(deftest unsupported-field-orders-fail-before-reading
  (keyed-fixture
    (fn []
      (let [root (keyed-register! (fn [_ e] (:customer e)) {:indexes {:name :name}})
            definition {:from {:customer root} :orders {:name [:customer/name]}}
            seen (atom [])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ordering"
                              (rmp/query-models definition)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (rmp/query (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))
                                definition {:order :name})))
        (is (empty? @seen))))))

(deftest search-definition-is-checked-before-reading
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup)]
        (doseq [bad [(assoc definition :search [:company/name])
                     (assoc definition :search [:customer/name])
                     (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}])]]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/query-models bad))))))))

(defn read-query-definition-error [definition]
  (try (rmp/query-models definition) nil
       (catch clojure.lang.ExceptionInfo e {:message (ex-message e) :data (ex-data e)})))

(deftest index-errors-give-usable-extractor-corrections
  (keyed-fixture
    (fn []
      (let [step (fn [_ e] (:customer e))
            root (keyed-register! step {:indexes {:name :name}})
            definition {:from {:customer root} :orders {:name [:customer/name]}}
            {:keys [message data]} (read-query-definition-error definition)
            extractor (:suggested-extractor data)]
        (is (= [:orders :name] (:path data)))
        (is (= root (:model data)))
        (is (= [:customer/name] (:fields data)))
        (is (= '(rmp/order-key [:name]) extractor))
        (is (str/includes? message (str root)))
        (is (str/includes? message (pr-str extractor)))
        (rmp/register-read-model! root step {:events #{:keyed/change} :key :owner
                                            :indexes {:name (apply rmp/order-key (rest extractor))}})
        (is (= {root 1} (rmp/query-models definition)))
        (let [searchable (assoc definition :search [:customer/name])
              {:keys [message data]} (read-query-definition-error searchable)
              extractor (:suggested-extractor data)]
          (is (= [:search] (:path data)))
          (is (= root (:model data)))
          (is (= '(rmp/search-text [:name]) extractor))
          (is (str/includes? message (pr-str extractor)))
          (rmp/register-read-model! root step {:events #{:keyed/change} :key :owner
                                              :indexes {:name (rmp/order-key [:name])}
                                              :searches {:text (apply rmp/search-text (rest extractor))}})
          (is (= {root 1} (rmp/query-models searchable))))))))

(deftest search-errors-identify-the-offending-shape-and-relationship
  (keyed-fixture
    (fn []
      (let [{:keys [definition customer company]} (read-query-setup)
            bad-start (read-query-definition-error (assoc definition :search [:company/name]))
            bad-segments (read-query-definition-error (assoc definition :search [:customer/name :company/name :customer/email]))
            bad-default (read-query-definition-error (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}]))]
        (is (= [:search] (get-in bad-start [:data :path])))
        (is (= :customer (get-in bad-start [:data :root])))
        (is (str/includes? (:message bad-start) ":customer"))
        (is (= [:customer :company :customer] (get-in bad-segments [:data :aliases])))
        (is (re-find #"together and first" (get-in bad-segments [:data :hint])))
        (is (= [:related :company :missing] (get-in bad-default [:data :path])))
        (is (= company (get-in bad-default [:data :model])))
        (is (= [:company/name] (get-in bad-default [:data :fields])))
        (is (re-find #"if the domain permits" (get-in bad-default [:data :hint])))
        (let [stored (get (rmp/global-read-model-registry) customer)
              opts (-> stored (dissoc :reducer-fn) (update :indexes dissoc :company))]
          (rmp/register-read-model! customer (:reducer-fn stored) opts)
          (let [{:keys [message data]} (read-query-definition-error definition)]
            (is (= [:related :company :key] (:path data)))
            (is (= company (:model data)))
            (is (= customer (:index-model data)))
            (is (= :company (:suggested-extractor data)))
            (is (re-find #"raw keyword extractor" message))
            (rmp/register-read-model! customer (:reducer-fn stored)
                                      (assoc-in opts [:indexes :company] (:suggested-extractor data)))
            (is (= 3 (count (rmp/query-models definition))))))))))

(deftest one-text-index-serves-root-and-related-search
  (keyed-fixture
    (fn []
      (let [customer (keyed-register! (fn [_ e] (:customer e))
                                        {:indexes {:name (rmp/order-key [:name]) :company :company}
                                         :searches {:text (rmp/search-text [:name :email])}})
            company (keyed-register! (fn [_ e] (:company e))
                                       {:key :company-id :searches {:text (rmp/search-text [:name])}})
            root-only {:from {:customer customer} :search [:customer/name :customer/email]
                       :orders {:name [:customer/name]}}
            related (assoc root-only :related {:company {:model company :key :customer/company}}
                                     :search [:customer/name :customer/email :company/name])]
        (keyed-append! [{:owner "a" :company-id "c" :company {:name "Acme"}
                           :customer {:name "Anna" :email "mail.test" :company "c"}}])
        (doseq [[definition text] [[root-only "anna"] [related "anna"]
                                   [related "acme"] [related "test acm"]]]
          (is (= ["a"] (mapv :key (:rows (rmp/query *keyed-ctx* definition {:order :name :search text}))))))))))

(deftest changed-membership-does-not-turn-field-ordering-into-a-scan
  (keyed-fixture
    (fn []
      (let [{:keys [definition]} (read-query-setup)
            checked (-> definition
                        (update-in [:related :ledger] dissoc :missing)
                        (assoc :search [:customer/name :customer/email]))
            seen (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! seen conj %))]
        (read-query-seed!)
        (is (= :indexed (get-in (rmp/query ctx checked {:order :amount}) [:work :strategy])))
        (keyed-append! [{:owner "outsider" :company-id "unused" :ledger {:amount -999}}])
        ;; Catch up first so the observations below concern query execution only.
        (rmp/read-session ctx (keys (rmp/query-models checked)) (constantly nil))
        (doseq [search ["" "example"]]
          (reset! seen [])
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported field ordering"
                                (rmp/query ctx checked {:order :amount :search search})))
          (is (not-any? #(= :result-decoded (:operation %)) @seen)))))))

(deftest oversized-index-keys-fail-without-unindexed-replay
  (keyed-fixture
    (fn []
      (let [reductions (atom 0)
            root (keyed-register! (fn [_ e] (swap! reductions inc) (:customer e))
                                    {:indexes {:name (rmp/order-key [:name])}})
            definition {:from {:customer root} :orders {:name [:customer/name]}}]
        (keyed-append! [{:owner "a" :customer {:name (apply str (repeat 600 "x"))}}])
        (is (= :grain.read-model/key-too-large
               (try (rmp/query *keyed-ctx* definition {:order :name})
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
        (is (= 1 @reductions))))))

(deftest search-boundaries-match-literal-composition
  (keyed-fixture
    (fn []
      (let [customer (keyed-register! (fn [_ e] (:customer e))
                                        {:indexes {:name (rmp/order-key [:name]) :company :company}
                                         :searches {:text (rmp/search-text [:name :email])}})
            company (keyed-register! (fn [_ e] (:company e))
                                       {:key :company-id :searches {:text (rmp/search-text [:name])}})
            root-only {:from {:customer customer} :search [:customer/name :customer/email]
                       :orders {:name [:customer/name]}}
            related (assoc root-only :related {:company {:model company :key :customer/company}}
                                     :search [:customer/name :customer/email :company/name])
            examples [["Anna" "mail.test" "Acme"] ["BOB" "  tail " " Beta"]
                      ["😀𐐀" "end😀" "𐐀Co"] ["Empty" "" "Company"]]
            texts (mapv (fn [[name email company]] [(str name " " email) (str name " " email " " company)]) examples)
            queries ["anna" "test acm" "tail   be" "tail  be" "😀 𐐀" "end😀 𐐀" "empty  com" "bob" "missing"]]
        (keyed-append! (mapv (fn [i [name email company]]
                                {:owner (str i) :company-id (str "c" i) :company {:name company}
                                 :customer {:name name :email email :company (str "c" i)}})
                              (range) examples))
        (doseq [[definition column] [[root-only 0] [related 1]] query queries]
          (let [expected (into #{} (keep-indexed (fn [i fields]
                                                  (when (str/includes? (str/lower-case (nth fields column))
                                                                       (str/lower-case query)) (str i)))) texts)]
            (is (= expected (set (map :key (:rows (rmp/query *keyed-ctx* definition
                                                            {:order :name :search query})))))
                (pr-str {:related? (= column 1) :query query}))))))))

(deftest search-text-accepts-only-fields
  (keyed-fixture
    (fn []
      (is (= "Anna mail.test" ((rmp/search-text [:name :email]) {:name "Anna" :email "mail.test"})))
      (doseq [options [{} {:suffix " "} nil]]
        (is (thrown? clojure.lang.ArityException (rmp/search-text [:name] options)))))))

;; routed test
(defn routed-register! [step opts]
  (let [name (keyword "routed-test" (str (random-uuid)))]
    (rmp/register-read-model! name step (merge {:events #{:keyed/change} :version 1
                                              :route :inputs} opts))
    name))

(deftest routed-order-empty-events-tenancy-and-restart
  (keyed-fixture
    (fn []
      (let [route-calls (atom 0)
            route (fn [event] (swap! route-calls inc) (:inputs event))
            model (routed-register! keyed-history-step {:route route :result keyed-total
                                             :indexes {:total (rmp/order-key [:total])}})
            _ (keyed-append! [{:inputs [["a" {:delta 1}] ["b" {:delta 8}] ["a" {:delta 2}]]}])]
        (is (= {"a" {:total 3} "b" {:total 8}} (keyed-results *keyed-ctx* model)))
        (is (= {:history [1 2]} (rmp/state *keyed-ctx* model "a")))
        (keyed-append! [{:inputs [["a" {:delta 4}]]} {:inputs []}])
        (is (= {"a" {:total 7} "b" {:total 8}} (keyed-results *keyed-ctx* model)))
        (let [progress (rmp/progress *keyed-ctx* model) before (keyed-stored-bytes)]
          (is (:watermark progress))
          (is (= 3 @route-calls) "Empty routes are checkpointed and never reprocessed on warm reads")
          (is (= ["b"] (keyed-page-keys *keyed-ctx* model {:index :total :page 2 :limit 1})))
          (is (= :indexed (get-in (rmp/query *keyed-ctx* {:from {:item model} :orders {:total [:item/total]}}
                                             {:order :total :limit 1}) [:work :strategy])))
          (is (true? (= before (keyed-stored-bytes))))
          (let [cache (:cache *keyed-ctx*)]
            (kv/stop cache)
            (let [reopened (kv/start (lmdb/->KV-Store-LMDB (:config cache)))
                  ctx (assoc *keyed-ctx* :cache reopened)]
              (reset! *keyed-cache-handle* reopened)
              (is (= progress (rmp/progress ctx model)))
              (is (= {:total 7} (rmp/value ctx model "a")))
              (is (= 3 @route-calls))
              (is (= {} (keyed-results (assoc ctx :tenant-id (random-uuid)) model))))))))))

(deftest malformed-routes-are-errors-and-key-remains-strict
  (keyed-fixture
    (fn []
      (keyed-append! [{:owner nil}])
      (doseq [bad [nil {} #{["a" {}]} [["a"]] [["a" {} :extra]] [[nil {}]]]]
        (let [model (routed-register! keyed-history-step {:route (constantly bad)})
              before (keyed-materialized-bytes)]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/value *keyed-ctx* model "a")) (pr-str bad))
          (is (true? (= before (keyed-materialized-bytes))))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (rmp/value *keyed-ctx* (keyed-register! keyed-history-step {}) "a")))
      (doseq [opts [{:key :owner} {:route nil} {:route 123}]]
        (is (thrown? clojure.lang.ExceptionInfo (routed-register! keyed-history-step opts)))))))

(deftest failures-roll-back-every-owner-index-and-checkpoint
  (keyed-fixture
    (fn []
      (doseq [failure [:routing :reduction :index]]
        (let [fail? (atom false)
              route (fn [event]
                      (map-indexed (fn [i entry]
                                     (when (and @fail? (= failure :routing) (= 1 i))
                                       (throw (ex-info "Routing failed part way through" {})))
                                     entry) (if (= failure (:scenario event)) (:inputs event) [])))
              step (fn [state input]
                     (when (and @fail? (= failure :reduction) (= 13 (:delta input)))
                       (throw (ex-info "Reducer failed" {})))
                     (keyed-history-step state input))
              opts {:route route :result keyed-total :searches {:text #(str (:total %))}
                    :indexes {:total (fn [value]
                                       (when (and @fail? (= failure :index) (= 13 (:total value)))
                                         (throw (ex-info "Index failed after another owner" {})))
                                       (:total value))}}
              model (routed-register! step opts)]
          (keyed-append! [{:scenario failure :inputs [["baseline" {:delta 1}]]}])
          (keyed-results *keyed-ctx* model)
          (let [before (keyed-stored-bytes)]
            (keyed-append! [{:scenario failure :inputs [["a" {:delta 2}] ["b" {:delta 13}]]}])
            (reset! fail? true)
            (is (thrown? clojure.lang.ExceptionInfo (rmp/value *keyed-ctx* model "a")) (str failure))
            (is (true? (= before (keyed-stored-bytes))) "Includes every state, result, index, count and checkpoint")
            (reset! fail? false)
            (let [recovered (keyed-results *keyed-ctx* model)
                  fresh (routed-register! step opts)]
              (is (= recovered (keyed-results *keyed-ctx* fresh)))
              (is (= 13 (:total (get recovered "b")))))))))))

;; text search test
(def text-search-opts {:searches {:text :text} :indexes {:score :score} :include? :included?})
(defn text-search-step [_ event] (select-keys event [:text :score :included?]))
(defn text-search-find-keys [ctx model q mode]
  (set (rmp/search-keys ctx model {:index :text :query q :mode mode})))

(deftest exact-positions-boundaries-and-compact-ordering
  (keyed-fixture
    (fn []
      (let [model (keyed-register! text-search-step text-search-opts)
            texts ["Anna Jones anna@example.com Dental" "aana Annan" "aaaaa aaa"
                   "😀Ana İΣ Σ 😀𐐀" "abc---def abcdef" "abc bcabc" "" "a" "ab"]
            rows (mapv (fn [i text] {:owner (str i) :text text :score (mod i 3) :included? true}) (range) texts)
            cases [[:contains "anna"] [:contains "aana"] [:contains "aaaaaa"] [:contains "aaaaa"]
                   [:contains "abcdef"] [:contains "abcabc"] [:contains " abc"] [:contains "com den"]
                   [:contains "Σ 😀"] [:contains "😀ana"] [:contains "İΣ "]
                   [:prefix "a"] [:prefix "ab"] [:prefix "ann"] [:prefix "😀"]
                   [:suffix "a"] [:suffix "ab"] [:suffix "abc"] [:suffix "😀𐐀"]]]
        (keyed-append! rows)
        (doseq [[mode q] cases]
          (let [matches (case mode :prefix str/starts-with? :suffix str/ends-with? str/includes?)
                expected (filter #(matches (core/text-search-normalize (:text %)) (core/text-search-normalize q)) rows)]
            (is (= (set (map :owner expected)) (text-search-find-keys *keyed-ctx* model q mode)))
            (doseq [direction [:asc :desc] page [1 2 999]]
              (let [ordered (sort (fn [a b] (let [c (compare (:score a) (:score b))]
                                             (if (zero? c) (compare (:owner a) (:owner b))
                                               (if (= :desc direction) (- c) c)))) expected)
                    pages (max 1 (quot (+ (count ordered) 1) 2))
                    actual (rmp/key-page *keyed-ctx* model (mapcat #(repeat 2 (:owner %)) expected)
                                         {:index :score :direction direction :page page :limit 2})]
                (is (= (count expected) (:total actual)))
                (is (= (mapv :owner (take 2 (drop (* 2 (dec (min page pages))) ordered))) (:keys actual)))))))
        (doseq [q ["" "a" "ab" "😀a"]]
          (is (thrown? clojure.lang.ExceptionInfo (text-search-find-keys *keyed-ctx* model q :contains))))
        (let [observed (atom []) ctx (assoc *keyed-ctx* :read-model/observer #(swap! observed conj %))]
          (rmp/read-session ctx [model]
            (fn [ctx]
              (let [ids (rmp/search-keys ctx model {:index :text :query "abc"})
                    page (rmp/key-page ctx model (conj ids "absent")
                                       {:index :score :sort-value (fn [_ v] (- v))})]
                (is (= ["5" "4"] (:keys page))))))
          (is (not-any? #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/search-keys *keyed-ctx* model {:index :absent :query "abc"})))
        (is (thrown? clojure.lang.ExceptionInfo (rmp/key-page *keyed-ctx* model ["1"] {:index :absent})))))))

(deftest update-exclusion-reentry-restart-and-isolation
  (keyed-fixture
    (fn []
      (let [model (keyed-register! text-search-step text-search-opts)
            other (assoc *keyed-ctx* :tenant-id (random-uuid))]
        (keyed-append! [{:owner "a" :text "old alpha" :score 1 :included? true}])
        (keyed-append! other [{:owner "a" :text "other beta" :score 2 :included? true}])
        (is (= #{"a"} (text-search-find-keys *keyed-ctx* model "alpha" :contains)))
        (is (empty? (text-search-find-keys *keyed-ctx* model "beta" :contains)))
        (is (= #{"a"} (text-search-find-keys other model "beta" :contains)))
        (keyed-append! [{:owner "a" :text "new beta" :score 2 :included? true}])
        (is (empty? (text-search-find-keys *keyed-ctx* model "alpha" :contains)))
        (is (= #{"a"} (text-search-find-keys *keyed-ctx* model "beta" :contains)))
        (keyed-append! [{:owner "a" :text "new beta" :score 2 :included? false}])
        (is (empty? (text-search-find-keys *keyed-ctx* model "beta" :contains)))
        (is (= 0 (:total (rmp/key-page *keyed-ctx* model ["a"] {}))))
        (keyed-append! [{:owner "a" :text "new beta" :score 2 :included? true}])
        (is (= #{"a"} (text-search-find-keys *keyed-ctx* model "beta" :contains)))
        (kv/stop @*keyed-cache-handle*) (reset! *keyed-cache-handle* nil)
        (let [cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir *keyed-directory* :db-name "folds"
                                                   :map-size (* 64 1024 1024)}))
              ctx (assoc *keyed-ctx* :cache cache)]
          (reset! *keyed-cache-handle* cache)
          (is (= #{"a"} (text-search-find-keys ctx model "beta" :contains)))
          (is (= ["a"] (:keys (rmp/key-page ctx model ["a"] {}))))
          (let [unchanged (binding [*keyed-ctx* ctx] (keyed-stored-bytes))]
            (text-search-find-keys ctx model "beta" :contains)
            (is (= unchanged (binding [*keyed-ctx* ctx] (keyed-stored-bytes))))))))))

(deftest failed-indexing-rolls-back-state-search-and-progress
  (keyed-fixture
    (fn []
      (let [model (keyed-register! text-search-step text-search-opts)]
        (keyed-append! [{:owner "a" :text "old alpha" :score 1 :included? true}])
        (text-search-find-keys *keyed-ctx* model "alpha" :contains)
        (let [before (keyed-stored-bytes)]
          (keyed-append! [{:owner "a" :text "new beta" :score 2 :included? true}
                           {:owner "b" :text 123 :score 3 :included? true}])
          (is (thrown? clojure.lang.ExceptionInfo (text-search-find-keys *keyed-ctx* model "beta" :contains)))
          (is (= before (keyed-stored-bytes)))
          (keyed-append! [{:owner "b" :text "valid beta" :score 3 :included? true}])
          (is (= #{"a" "b"} (text-search-find-keys *keyed-ctx* model "beta" :contains)))
          (is (empty? (text-search-find-keys *keyed-ctx* model "alpha" :contains))))))))

(deftest ordered-values-round-trip
  (keyed-fixture
    (fn []
      (doseq [value [nil false true "" "a" "a\u0000b" "😀𐐀" (str (char 0xD800))
                     Long/MIN_VALUE -1 0 Long/MAX_VALUE :name :namespaced/name (random-uuid)
                     [] [nil "A" :x 42] [["nested"]]]]
        (is (= value (fold-key-decode-physical (core/key-encode value))))))))

(deftest repeated-and-concurrent-searches-match-literal-oracle
  (keyed-fixture
    (fn []
      (let [model (keyed-register! text-search-step text-search-opts)
            texts ["abcdefghi" "abc---def" "def---ghi" "ghi---abc"
                   "abcabcabc" "aaaaaa" "😀abcdefghi😀" "ABCdef" "xyz"]
            rows (mapv (fn [i]
                         {:owner (case (mod i 4) 0 (str i) 1 (java.util.UUID. 0 i)
                                       2 [:row i] (keyword (str "row-" i)))
                          :text (nth texts (mod i (count texts))) :score i :included? true})
                       (range 300))
            cases (vec (for [mode [:contains :prefix :suffix]
                             query ["abcdefghi" "abcabc" "aaaaaa" "😀abc" "abc" "ghi" "missing"]]
                         [mode query]))
            expected (mapv (fn [[mode query]]
                             (let [matches (case mode :prefix str/starts-with? :suffix str/ends-with? str/includes?)]
                               (into #{} (comp (filter #(matches (core/text-search-normalize (:text %)) query))
                                               (map :owner)) rows))) cases)
            run #(mapv (fn [[mode query]] (text-search-find-keys *keyed-ctx* model query mode)) cases)]
        (keyed-append! rows)
        (let [first-result (run)
              readers (mapv (fn [_] (future (run))) (range 8))]
          (is (= expected first-result))
          (doseq [reader readers] (is (= expected (deref reader 10000 :ai.obney.grain.read-model-processor-v2.text-search-test/timeout))))
          (is (= expected first-result) "Later reads cannot mutate detached identities"))))))

(deftest search-and-ordering-share-the-read-session-snapshot
  (keyed-fixture
    (fn []
      (let [model (keyed-register! text-search-step text-search-opts)
            entered (promise) advanced (promise)]
        (keyed-append! [{:owner "a" :text "alpha" :score 1 :included? true}
                          {:owner "b" :text "alpha" :score 2 :included? true}])
        (let [reader (future
                       (rmp/read-session *keyed-ctx* [model]
                         (fn [ctx]
                           (let [ids (rmp/search-keys ctx model {:index :text :query "alpha"})]
                             (deliver entered true)
                             (deref advanced 10000 :ai.obney.grain.read-model-processor-v2.text-search-test/timeout)
                             [(text-search-find-keys ctx model "alpha" :contains)
                              (:keys (rmp/key-page ctx model ids {:index :score}))]))))]
          (try
            (is (= true (deref entered 10000 :ai.obney.grain.read-model-processor-v2.text-search-test/timeout)))
            (keyed-append! [{:owner "a" :text "beta" :score 3 :included? true}])
            (is (= #{"b"} (text-search-find-keys *keyed-ctx* model "alpha" :contains)))
            (is (= ["b" "a"] (:keys (rmp/key-page *keyed-ctx* model ["a" "b"] {:index :score}))))
            (deliver advanced true)
            (is (= [#{"a" "b"} ["a" "b"]] (deref reader 10000 :ai.obney.grain.read-model-processor-v2.text-search-test/timeout)))
            (finally (deliver advanced true))))))))

;; ranked membership bench
(def ranked-membership-bean ^ThreadMXBean (ManagementFactory/getThreadMXBean))
(defschemas ranked-membership-schemas {:rank-benchmark/change [:map]})
(defn ranked-membership-allocated [] (.getThreadAllocatedBytes ranked-membership-bean (.getId (Thread/currentThread))))
(defn ranked-membership-measure [f]
  (let [before (ranked-membership-allocated) start (System/nanoTime) result (f)]
    {:ms (/ (- (System/nanoTime) start) 1e6)
     :mib (/ (- (ranked-membership-allocated) before) 1048576.0) :result result}))
(defn ranked-membership-stats [samples]
  {:mean-ms (/ (reduce + (map :ms samples)) (count samples))
   :p95-ms (nth (vec (sort (map :ms samples))) (dec (int (Math/ceil (* 0.95 (count samples))))))
   :mean-mib (/ (reduce + (map :mib samples)) (count samples))})
(defn ranked-membership-samples [f]
  (dotimes [_ 10] (f))
  (ranked-membership-stats (repeatedly 30 #(dissoc (ranked-membership-measure f) :result))))
(defn ranked-membership-storage
  "Stored key/value bytes in the model's current KF9 namespace, excluding
   namespace registry records and the shared identity namespace."
  [ctx model]
  (let [identity [(:tenant-id ctx) model 1]
        prefix (get (core/namespace-resolve! (:cache ctx) [identity]) identity)]
    (kv/transact (:cache ctx) :read
      #((:reduce %) {:prefix prefix}
         (fn [out {:keys [k v]}]
           (-> out (update :entries inc) (update :bytes + (alength ^bytes k) (alength ^bytes v))))
         {:entries 0 :bytes 0}))))
(defn ranked-membership-comparable [page] (select-keys page [:keys :total :total-pages :page :page-size]))
(defn ranked-membership-run! [n]
  (let [dir (str "/tmp/grain-filtered-ranks-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "folds"
                                             :map-size (* 4 1024 1024 1024)}))
        ctx {:cache cache :event-store store :tenant-id (random-uuid)}
        ids (mapv #(UUID. 0 %) (range n))
        events (mapv (fn [i] (es/->event {:type :rank-benchmark/change
                                         :body {:owner (ids i)
                                                :name (format "name-%06d" (mod (* i 7919) n))
                                                :text (if (zero? (mod i 100)) "narrow example" "example")
                                                :included? (not (zero? (mod i 997)))}})) (range n))
        plain :rank-benchmark/plain indexed :rank-benchmark/indexed
        opts {:events #{:rank-benchmark/change} :version 1 :key :owner
              :include? :included? :indexes {:name :name} :searches {:text :text}}
        step (fn [_ e] (select-keys e [:name :text :included?]))]
    (try
      (rmp/register-read-model! plain step opts)
      (rmp/register-read-model! indexed step (assoc opts :filtered-indexes #{:name}))
      (es/append store {:tenant-id (:tenant-id ctx) :events events})
      ;; Same promotion decisions for both indexes: isolate membership overhead.
      (with-redefs [core/ranked-random-hash ranked-set-crc-hash]
        (let [build (into {} (for [model [plain indexed]]
                               (do (println :building n model) (flush)
                                   [model (dissoc (ranked-membership-measure #(rmp/progress ctx model)) :result)])))
              _ (assert (= (count (remove #(zero? (mod % 997)) (range n)))
                           (:total (rmp/page ctx plain {:index :name}))) "Fixture population missing")
              sizes (into {} (for [model [plain indexed]] [model (ranked-membership-storage ctx model)]))
              _ (assert (every? #(and (pos? (:entries %)) (pos? (:bytes %))) (vals sizes))
                        "Materialized model namespace storage missing")
              candidates [[:absent []] [:sparse (vec (take-nth 100 ids))]
                          [:half (vec (take-nth 2 ids))] [:broad ids]]
              cases
              (rmp/read-session ctx [plain indexed]
                (fn [prepared]
                  (vec
                    (for [[label keys] candidates page [1 999999] direction [:asc :desc]]
                      (let [options {:index :name :page page :limit 25 :direction direction}
                            baseline #(rmp/key-page prepared plain keys options)
                            experiment #(rmp/filtered-key-page prepared indexed keys options)
                            before (baseline) after (experiment)
                            _ (assert (= (ranked-membership-comparable before) (ranked-membership-comparable after)) "Pagination mismatch")
                            a (ranked-membership-samples baseline) b (ranked-membership-samples experiment)
                            observed (volatile! {})
                            counters (rmp/filtered-key-page
                                       (assoc prepared :read-model/observer
                                              (fn [{:keys [operation bytes]}]
                                                (vswap! observed update operation
                                                        (fn [v] (-> (or v {:calls 0 :bytes 0})
                                                                    (update :calls inc) (update :bytes + bytes))))))
                                       indexed keys options)
                            result {:case label :page (:page after) :direction direction
                                    :total (:total after) :baseline a :ranked b
                                    :work (dissoc counters :keys) :observed @observed}]
                        (println (pr-str (dissoc result :observed))) (flush)
                        result)))))
              search-cases
              (rmp/read-session ctx [plain indexed]
                (fn [prepared]
                  (vec (for [query ["missing" "narrow" "example"]]
                         (let [options {:index :name :page 999999 :limit 25}
                               baseline #(rmp/key-page prepared plain
                                                       (rmp/search-keys prepared plain {:index :text :query query}) options)
                               experiment #(rmp/filtered-key-page prepared indexed
                                                               (rmp/search-keys prepared indexed {:index :text :query query}) options)
                               _ (assert (= (ranked-membership-comparable (baseline)) (ranked-membership-comparable (experiment))))]
                           {:query query :baseline (ranked-membership-samples baseline) :ranked (ranked-membership-samples experiment)})))))
              _ (es/append store {:tenant-id (:tenant-id ctx)
                                  :events (mapv #(es/->event {:type :rank-benchmark/change
                                                             :body {:owner (ids %) :name (str "moved-" %)
                                                                    :text "changed example" :included? (even? %)}})
                                                (range 100))})
              updates (into {} (for [model [plain indexed]]
                                [model (dissoc (ranked-membership-measure #(rmp/progress ctx model)) :result)]))]
          {:population n :java (System/getProperty "java.version") :warmups 10 :samples 30
           :build build :storage sizes :update-100 updates :pages cases :search-and-page search-cases}))
      (finally
        (kv/stop cache) (es/stop store)
        (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true))))))

(defn -main [population report]
  (let [result (ranked-membership-run! (parse-long population))]
    (with-open [writer (io/writer report)] (binding [*out* writer] (pprint/pprint result)))
    (println :report report))
  (shutdown-agents))
