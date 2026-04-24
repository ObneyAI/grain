(ns ai.obney.grain.read-model-processor.interface-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor.interface :as rmp]
            [ai.obney.grain.read-model-processor.core :as core]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.fressian-util.interface :as fressian-util]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.java.io :as io]))

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
        (f)
        (finally
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
   :cache       *cache*})

(defn make-args
  ([] (make-args {}))
  ([overrides]
   (merge {:f       counter-reducer
           :query   {:types #{:test/counter-incremented}}
           :name    :test/counter
           :version 1}
          overrides)))

(defn append-test-events!
  ([n] (append-test-events! n :test/counter-incremented))
  ([n event-type]
   (let [events (mapv (fn [_] (es/->event {:type event-type}))
                      (range n))]
     (es/append *event-store* {:events events}))))

(defn append-tagged-events!
  [n event-type tags]
  (let [events (mapv (fn [_] (es/->event {:type event-type :tags tags})) (range n))]
    (es/append *event-store* {:events events})))

(defn read-cached [name version]
  (some-> (kv/get! *cache* {:k (core/format-key name version)})
          fressian-util/decode))

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
  (let [events (into [] (es/read *event-store* {:types #{:test/counter-incremented}}))
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
  (let [events (into [] (es/read *event-store* {:types #{:test/counter-incremented}}))
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
    ;; First call: cache miss, processes 3 events, populates cache
    (append-test-events! 3)
    (rmp/p (make-context) (make-args {:f counting-reducer}))
    (reset! call-count 0)

    ;; Append 5 more (< 10 threshold) and call again — cache hit, processes 5 incrementally
    (append-test-events! 5)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 8} result))
      (is (= 5 @call-count)))

    ;; Because event-count was < 10, cache was NOT updated.
    ;; Third call should re-process the same 5 events from the original watermark.
    (reset! call-count 0)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 8} result))
      (is (= 5 @call-count)))))

(deftest cache-hit-at-threshold-updates-cache
  (let [call-count (atom 0)
        counting-reducer (fn [state event]
                           (swap! call-count inc)
                           (counter-reducer state event))]
    ;; First call: cache miss, processes 3 events, populates cache
    (append-test-events! 3)
    (rmp/p (make-context) (make-args {:f counting-reducer}))
    (reset! call-count 0)

    ;; Append 10 more (>= 10 threshold) and call again
    (append-test-events! 10)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 13} result))
      (is (= 10 @call-count)))

    ;; Because event-count was >= 10, cache WAS updated.
    ;; Third call should process 0 new events.
    (reset! call-count 0)
    (let [result (rmp/p (make-context) (make-args {:f counting-reducer}))]
      (is (= {:count 13} result))
      (is (= 0 @call-count)))))

;; ---------------------------------------------------------------------------
;; C. State Accumulation
;; ---------------------------------------------------------------------------

(deftest state-accumulates-across-incremental-calls
  ;; Round 1: 3 events
  (append-test-events! 3)
  (is (= {:count 3} (rmp/p (make-context) (make-args))))

  ;; Round 2: +4 events (7 total) — under threshold, no cache update
  (append-test-events! 4)
  (is (= {:count 7} (rmp/p (make-context) (make-args))))

  ;; Round 3: +12 events (19 total) — the incremental batch is 12 (>= 10), cache updates
  (append-test-events! 12)
  (is (= {:count 19} (rmp/p (make-context) (make-args))))

  ;; Round 4: +2 events (21 total)
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

;; Reset registry before each test via fixture — the test-fixture already runs per :each
;; but we also reset explicitly to isolate macro tests from each other.

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
        ;; Append 4 unscoped, 3 for org-a, 5 for org-b
        (append-test-events! 4)
        (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
        (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
        (let [unscoped (rmp/project (make-context) :test/counter)
              scoped-a (rmp/project (make-context) :test/counter {:tags #{[:org org-a]}})
              scoped-b (rmp/project (make-context) :test/counter {:tags #{[:org org-b]}})]
          ;; Unscoped sees all 12 events (no tag filter)
          (is (= {:count 12} unscoped))
          ;; Scoped see only their tagged events
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
        ;; Use :queries to override — batch query for both orgs
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
    ;; Initial events
    (append-tagged-events! 3 :test/counter-incremented #{[:org org-a]})
    (append-tagged-events! 5 :test/counter-incremented #{[:org org-b]})
    (let [first-result (rmp/p (make-context) args)]
      (is (= {:count 8} first-result)))
    ;; Append more and verify incremental processing (cache hit with vector query)
    (append-tagged-events! 2 :test/counter-incremented #{[:org org-a]})
    (let [second-result (rmp/p (make-context) args)]
      (is (= {:count 10} second-result)))))
