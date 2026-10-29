(ns ai.obney.grain.read-model-processor-v2.start-after-test
  "A read model's :start-after <event-id> skips every event at or before that
   id. Each test runs against the in-memory and SQLite event stores."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.scoped-test :as scoped]
            [ai.obney.grain.read-model-processor-v2.empty-interval-test :as empty-interval]
            [ai.obney.grain.event-store-v3.interface :as es]
            [clj-uuid :as uuid]))

(use-fixtures :each (fn [f] (empty-interval/reset-proofs!) (rmp/l1-clear!)
                      (try (f) (finally (empty-interval/reset-proofs!) (rmp/l1-clear!)))))

(def ^:private all-types #{:keyed/change :keyed/other})

(defn- head-id
  "Id of the newest event of the tenant."
  [ctx]
  (:event/id (last (into [] (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :types all-types})))))

(defn- append! [ctx owner delta]
  (let [event (es/->event {:type :keyed/change :tags #{[:User owner]} :body {:owner "a" :delta delta}})]
    (es/append (:event-store ctx) {:tenant-id (:tenant-id ctx) :events [event]})
    (head-id ctx)))

(defn- append-events!
  "Append bodies ({:owner :delta :type :tags ...}) in one batch; return the newest id."
  [ctx bodies]
  (scoped/append! ctx (mapv #(update % :tags (fnil identity #{[:Other (java.util.UUID. 5 5)]})) bodies))
  (head-id ctx))

(defn- counting
  "Wrap step so every folded event's :delta is recorded in seen."
  [seen step]
  (fn [state event] (swap! seen conj (:delta event)) (step state event)))

(defn- total-step [state event] (update state :total (fnil + 0) (:delta event)))

(defn- ordinary! [step opts]
  (let [name (keyword "ordinary-test" (str (random-uuid)))]
    (rmp/register-read-model! name step (merge {:events #{:keyed/change}} opts))
    name))

(defn- keyed-reregister!
  "Replace keyed model name's registration (same name, new options)."
  [name step opts]
  (rmp/register-read-model! name step (merge {:events #{:keyed/change} :version 1 :key :owner} opts)))

(defn- ordinary-reregister! [name step opts]
  (rmp/register-read-model! name step (merge {:events #{:keyed/change}} opts)))

;; ---------------------------------------------------------------------------
;; 1, 2, 5 (smoke): cold build, warm catch-up, scoped read
;; ---------------------------------------------------------------------------

(deftest keyed-model-skips-history-through-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            cut (append! ctx u 2)
            _ (append! ctx u 4)
            full (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after cut})
            own (rmp/scoped-context ctx {:tags #{[:User u]}})]
        (is (= {:total 7} (rmp/value ctx full "a")))
        (is (= {:total 4} (rmp/value ctx model "a")) "cold build starts after start-after")
        (is (= {:total 4} (rmp/value own model "a")) "scoped reads skip the same history")
        (append! ctx u 8)
        (is (= {:total 12} (rmp/value ctx model "a")) "checkpoint later than start-after wins")
        (is (= {:total 12} (rmp/value own model "a")))))))

(deftest ordinary-model-skips-history-through-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            cut (append! ctx u 2)
            _ (append! ctx u 4)
            model (ordinary! total-step {:start-after cut})]
        (is (= {:total 4} (rmp/project ctx model)))
        (is (= {:total 4} (rmp/project ctx model {:tags #{[:User u]}})))
        (append! ctx u 8)
        (is (= {:total 12} (rmp/project ctx model)))))))

;; ---------------------------------------------------------------------------
;; 1 + 2: exactly which events are folded, cold and warm
;; ---------------------------------------------------------------------------

(deftest keyed-folds-only-events-after-start-after-cold-then-warm
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            _ (append! ctx u 1)
            cut (append! ctx u 2)
            model (fixture/keyed-register! (counting seen fixture/keyed-history-step)
                                           {:result fixture/keyed-total :start-after cut})]
        (is (= :absent (rmp/value ctx model "a" :absent))
            "start-after at the head: nothing to fold, the start-after event itself is excluded")
        (is (= [] @seen))
        (append! ctx u 4)
        (append! ctx u 8)
        (is (= {:total 12} (rmp/value ctx model "a")))
        (is (= [4 8] @seen) "cold build folds only later events")
        (reset! seen [])
        (append! ctx u 16)
        (is (= {:total 28} (rmp/value ctx model "a")))
        (is (= [16] @seen) "warm catch-up resumes from the checkpoint, not from start-after")
        (reset! seen [])
        (is (= {:total 28} (rmp/value ctx model "a")))
        (is (= [] @seen))))))

(deftest ordinary-folds-only-events-after-start-after-cold-then-warm
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            _ (append! ctx u 1)
            cut (append! ctx u 2)
            _ (append! ctx u 4)
            _ (append! ctx u 8)
            model (ordinary! (counting seen total-step) {:start-after cut :l1-ttl-ms 0})]
        (is (= {:total 12} (rmp/project ctx model)))
        (is (= [4 8] @seen) "cold build folds only later events; the start-after event is excluded")
        (reset! seen [])
        (append! ctx u 16)
        (is (= {:total 28} (rmp/project ctx model)))
        (is (= [16] @seen) "warm catch-up resumes from the checkpoint")))))

;; ---------------------------------------------------------------------------
;; 3: start-after later (or earlier) than an existing cached checkpoint
;; ---------------------------------------------------------------------------

(deftest keyed-cached-checkpoint-is-not-reused-for-a-new-start
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            step (counting seen fixture/keyed-history-step)
            e1 (append! ctx u 1)
            _ (append! ctx u 2)
            model (fixture/keyed-register! step {:result fixture/keyed-total})]
        (is (= {:total 3} (rmp/value ctx model "a")) "cache built without start-after, checkpoint at e2")
        (let [e4 (append! ctx u 4)]
          (append! ctx u 8)
          (testing "start-after later than the cached checkpoint"
            (keyed-reregister! model step {:result fixture/keyed-total :start-after e4})
            (reset! seen [])
            (is (= {:total 8} (rmp/value ctx model "a")))
            (is (= [8] @seen)))
          (testing "start-after earlier than the cached checkpoint"
            (keyed-reregister! model step {:result fixture/keyed-total :start-after e1})
            (reset! seen [])
            (is (= {:total 14} (rmp/value ctx model "a")))
            (is (= [2 4 8] @seen))))))))

(deftest ordinary-cached-checkpoint-is-not-reused-for-a-new-start
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            step (counting seen total-step)
            e1 (append! ctx u 1)
            _ (append! ctx u 2)
            model (ordinary! step {:l1-ttl-ms 0})]
        (is (= {:total 3} (rmp/project ctx model)))
        (let [e4 (append! ctx u 4)]
          (append! ctx u 8)
          (testing "start-after later than the cached checkpoint"
            (ordinary-reregister! model step {:l1-ttl-ms 0 :start-after e4})
            (reset! seen [])
            (is (= {:total 8} (rmp/project ctx model)))
            (is (= [8] @seen)))
          (testing "start-after earlier than the cached checkpoint"
            (ordinary-reregister! model step {:l1-ttl-ms 0 :start-after e1})
            (reset! seen [])
            (is (= {:total 14} (rmp/project ctx model)))
            (is (= [2 4 8] @seen))))))))

(deftest ordinary-l1-entry-is-not-reused-for-a-new-start
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            cut (append! ctx u 2)
            _ (append! ctx u 4)
            model (ordinary! total-step {:l1-ttl-ms 60000})]
        (is (= {:total 7} (rmp/project ctx model)) "L1 entry is warm and within TTL")
        (ordinary-reregister! model total-step {:l1-ttl-ms 60000 :start-after cut})
        (is (= {:total 4} (rmp/project ctx model)) "a hot L1 entry for no start is not served for a start")
        (is (= {:total 4} (rmp/project ctx model)) "and the start's own L1 entry is correct")
        (ordinary-reregister! model total-step {:l1-ttl-ms 60000})
        (is (= {:total 7} (rmp/project ctx model)) "removing start-after does not serve the start's L1 entry")))))

;; ---------------------------------------------------------------------------
;; 4: same model, start-after added / changed / removed / restored
;; ---------------------------------------------------------------------------

(deftest keyed-start-after-added-changed-removed
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            a (append! ctx u 2)
            b (append! ctx u 4)
            _ (append! ctx u 8)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User u]}})
            check (fn [start expected]
                    (keyed-reregister! model fixture/keyed-history-step
                                       (cond-> {:result fixture/keyed-total} start (assoc :start-after start)))
                    (is (= {:total expected} (rmp/value ctx model "a")))
                    (is (= {:total expected} (rmp/value own model "a"))))]
        (testing "none" (check nil 15))
        (testing "added" (check a 12))
        (testing "changed later" (check b 8))
        (testing "changed earlier" (check a 12))
        (testing "removed" (check nil 15))
        (append! ctx u 16)
        (testing "each start catches up from its own checkpoint"
          (check b 24) (check nil 31) (check a 28))))))

(deftest ordinary-start-after-added-changed-removed
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            a (append! ctx u 2)
            b (append! ctx u 4)
            _ (append! ctx u 8)
            model (ordinary! total-step {})
            check (fn [start expected]
                    (ordinary-reregister! model total-step (cond-> {} start (assoc :start-after start)))
                    (is (= {:total expected} (rmp/project ctx model)))
                    (is (= {:total expected} (rmp/project ctx model {:tags #{[:User u]}}))))]
        (testing "none" (check nil 15))
        (testing "added" (check a 12))
        (testing "changed later" (check b 8))
        (testing "changed earlier" (check a 12))
        (testing "removed" (check nil 15))
        (append! ctx u 16)
        (testing "each start catches up from its own checkpoint"
          (check b 24) (check nil 31) (check a 28))))))

(defn- declare-in [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rmp 'ai.obney.grain.read-model-processor-v2.interface)
    (eval form)))

(deftest defreadmodel-reload-with-changing-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            a (append! ctx u 2)
            _ (append! ctx u 4)
            suffix (str (gensym "m"))
            rm-name (keyword "start-after-reload" suffix)
            form (fn [opts]
                   `(rmp/defreadmodel :start-after-reload ~(symbol suffix)
                      ~(merge {:events #{:keyed/change}} opts)
                      "Totals."
                      [~'state ~'event]
                      (update ~'state :total (fnil + 0) (:delta ~'event))))
            keyed-name (keyword "start-after-reload" (str suffix "-keyed"))
            keyed-form (fn [opts]
                         `(rmp/defreadmodel :start-after-reload ~(symbol (str suffix "-keyed"))
                            ~(merge {:events #{:keyed/change} :key :owner} opts)
                            "Keyed totals."
                            [~'state ~'event]
                            (update ~'state :total (fnil + 0) (:delta ~'event))))]
        (doseq [[opts expected] [[{} 7] [{:start-after a} 4] [{} 7] [{:start-after a} 4]]]
          (declare-in 'start-after-reload.models (form opts))
          (declare-in 'start-after-reload.models (keyed-form opts))
          (is (= {:total expected} (rmp/project ctx rm-name)) (pr-str opts))
          (is (= {:total expected} (rmp/value ctx keyed-name "a")) (pr-str opts)))))))

;; ---------------------------------------------------------------------------
;; 5: scoped and partitioned reads skip the same history and agree
;; ---------------------------------------------------------------------------

(deftest keyed-scopes-agree-on-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) v (random-uuid)
            _ (append-events! ctx [{:owner "a" :delta 1 :tags #{[:User u]}}
                                   {:owner "b" :delta 1 :tags #{[:User v]}}])
            cut (append-events! ctx [{:owner "a" :delta 2 :tags #{[:User u]}}
                                     {:owner "b" :delta 2 :tags #{[:User v]}}])
            _ (append-events! ctx [{:owner "a" :delta 4 :tags #{[:User u]}}
                                   {:owner "b" :delta 8 :tags #{[:User v]}}])
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after cut})
            tag (rmp/scoped-context ctx {:tags #{[:User u]}})
            one-query (rmp/scoped-context ctx {:queries {:tags #{[:User u]}}})
            union (rmp/scoped-context ctx {:queries [{:tags #{[:User u]}} {:tags #{[:User v]}}]})
            typed (rmp/scoped-context ctx {:queries [{:types #{:keyed/change} :tags #{[:User u]}}]})]
        (is (= {"a" {:total 4} "b" {:total 8}} (fixture/keyed-results ctx model)))
        (is (= {"a" {:total 4}} (fixture/keyed-results tag model)))
        (is (= {"a" {:total 4}} (fixture/keyed-results one-query model)))
        (is (= {"a" {:total 4}} (fixture/keyed-results typed model)))
        (is (= {"a" {:total 4} "b" {:total 8}} (fixture/keyed-results union model)))
        (append-events! ctx [{:owner "a" :delta 16 :tags #{[:User u]}}])
        (is (= {:total 20} (rmp/value tag model "a")))
        (is (= {:total 20} (rmp/value union model "a")))
        (is (= {:total 20} (rmp/value ctx model "a")))))))

(defn- item-step [state {:keys [item bucket delta]}]
  (update state item (fn [x] {:item item :bucket bucket :total (+ (:total x 0) delta)})))

(deftest ordinary-scopes-and-partitions-agree-on-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append-events! ctx [{:item 1 :bucket "a" :delta 1 :tags #{[:User u]}}
                                   {:item 2 :bucket "b" :delta 1}])
            cut (append-events! ctx [{:item 1 :bucket "a" :delta 2 :tags #{[:User u]}}
                                     {:item 3 :bucket "b" :delta 2}])
            _ (append-events! ctx [{:item 1 :bucket "a" :delta 4 :tags #{[:User u]}}
                                   {:item 2 :bucket "b" :delta 8}])
            plain (ordinary! item-step {:start-after cut :l1-ttl-ms 0})
            partitioned (ordinary! item-step {:start-after cut :l1-ttl-ms 0
                                              :partition-fn :bucket :entity-id-fn :item})
            expected {1 {:item 1 :bucket "a" :total 4} 2 {:item 2 :bucket "b" :total 8}}]
        (is (= expected (rmp/project ctx plain)))
        (is (= {1 (get expected 1)} (rmp/project ctx plain {:tags #{[:User u]}})))
        (is (= {1 (get expected 1)} (rmp/project ctx plain {:queries [{:types #{:keyed/change} :tags #{[:User u]}}]})))
        (is (= expected (rmp/project ctx plain {:queries {:types #{:keyed/change}}})))
        (testing "partitioned: single partition first (cold), then full"
          (is (= {2 (get expected 2)} (rmp/project ctx partitioned {:partition-key "b"})))
          (is (= expected (rmp/project ctx partitioned)))
          (is (= {1 (get expected 1)} (rmp/project ctx partitioned {:partition-key "a"}))))
        (append-events! ctx [{:item 3 :bucket "b" :delta 16}])
        (let [expected (assoc expected 3 {:item 3 :bucket "b" :total 16})]
          (is (= expected (rmp/project ctx plain)))
          (is (= expected (rmp/project ctx partitioned)))
          (is (= (dissoc expected 1) (rmp/project ctx partitioned {:partition-key "b"}))))))))

;; ---------------------------------------------------------------------------
;; 6: scope query with its own :after
;; ---------------------------------------------------------------------------

;; ^:known-bug -- when a scope query's :after is later than :start-after, the
;; scope's :after should win on a cold build. Kept in its own deftest so the
;; lead can see it fail on its own.
(deftest ordinary-scope-after-later-than-start-after-scope-after-wins
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            _ (append! ctx u 1)
            start (append! ctx u 2)
            scope-after (append! ctx u 4)
            _ (append! ctx u 8)
            model (ordinary! (counting seen total-step) {:start-after start :l1-ttl-ms 0})]
        (is (= {:total 8} (rmp/project ctx model {:queries [{:types #{:keyed/change} :after scope-after}]}))
            "^:known-bug: scope :after later than start-after must win on a cold build")
        (is (= [8] @seen))))))

(deftest ordinary-scope-after-earlier-than-start-after-start-after-wins
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid) seen (atom [])
            scope-after (append! ctx u 1)
            _ (append! ctx u 2)
            start (append! ctx u 4)
            _ (append! ctx u 8)
            model (ordinary! (counting seen total-step) {:start-after start :l1-ttl-ms 0})]
        (is (= {:total 8} (rmp/project ctx model {:queries [{:types #{:keyed/change} :after scope-after}]})))
        (is (= [8] @seen))))))

(deftest keyed-scopes-cannot-carry-their-own-after
  ;; Keyed scopes reject :after (at scoped-context and again at read time), so
  ;; a scope :after racing start-after cannot happen for keyed models.
  (fixture/keyed-fixture
    (fn []
      (is (thrown? clojure.lang.ExceptionInfo
                   (rmp/scoped-context fixture/*keyed-ctx* {:queries [{:tags #{[:User (random-uuid)]} :after (uuid/v7)}]}))))))

;; ---------------------------------------------------------------------------
;; 7: start-after beyond the head / not in the store
;; ---------------------------------------------------------------------------

(deftest start-after-beyond-head-reads-nothing-then-later-events
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            _ (append! ctx u 2)
            beyond (uuid/v7)
            keyed (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after beyond})
            ordinary (ordinary! total-step {:start-after beyond :l1-ttl-ms 0})]
        (is (= {} (fixture/keyed-results ctx keyed)))
        (is (= :absent (rmp/value (rmp/scoped-context ctx {:tags #{[:User u]}}) keyed "a" :absent)))
        (is (= {} (rmp/project ctx ordinary)))
        (is (= {} (rmp/project ctx ordinary {:tags #{[:User u]}})))
        (append! ctx u 4)
        (is (= {"a" {:total 4}} (fixture/keyed-results ctx keyed)) "a non-existent id still orders events")
        (is (= {:total 4} (rmp/project ctx ordinary)))))))

(deftest start-after-far-future-id-not-in-store-reads-nothing
  (scoped/with-stores
    (fn [ctx]
      (let [u (random-uuid)
            _ (append! ctx u 1)
            ;; A valid v7 id with the maximum timestamp; no event can follow it.
            far (java.util.UUID. (unchecked-long 0x7FFFFFFFFFFF7000) (unchecked-long 0x8000000000000000))
            keyed (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after far})
            ordinary (ordinary! total-step {:start-after far :l1-ttl-ms 0})]
        (is (= 7 (uuid/get-version far)))
        (append! ctx u 2)
        (is (= {} (fixture/keyed-results ctx keyed)))
        (is (= {} (rmp/project ctx ordinary)))))))

(deftest non-v7-start-after-is-rejected
  ;; Event ids are UUID v7; the event store rejects any other :after, so a
  ;; non-v7 start-after is rejected when the model is declared.
  (let [v4 (random-uuid)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid :start-after"
          (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after v4})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid :start-after"
          (ordinary! total-step {:start-after v4 :l1-ttl-ms 0})))))

;; ---------------------------------------------------------------------------
;; 8: empty-interval proofs and deferred builds
;; ---------------------------------------------------------------------------

(deftest empty-interval-proofs-hold-with-start-after
  (scoped/with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            _ (empty-interval/append-user! ctx a 1)
            cut (append-events! ctx [{:owner "a" :delta 2 :tags #{[:User a]}}])
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :start-after cut})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (is (= :absent (rmp/value own model "a" :absent)))
        (empty-interval/append-user! ctx a 4)
        (is (= {:total 4} (rmp/value own model "a")))
        (empty-interval/append-user! ctx b 100)
        (is (= {:value {:total 4} :counts {:heads 1 :event-queries 1 :writes 0}}
               (empty-interval/counted #(rmp/value own model "a"))))
        (is (= {:value {:total 4} :counts {:heads 1 :event-queries 0 :writes 0}}
               (empty-interval/counted #(rmp/value own model "a")))
            "unrelated appends are proven empty without re-reading")
        (empty-interval/append-user! ctx a 8)
        (is (= {:total 12} (rmp/value own model "a")))))))

(deftest deferred-build-with-start-after-matches-incremental
  (scoped/with-stores
    (fn [ctx]
      (let [owners (mapv #(str "o" %) (range 12))
            body (fn [i] {:owner (nth owners (mod i 12)) :delta (inc i)})
            _ (append-events! ctx (mapv body (range 40)))
            cut (head-id ctx)
            _ (append-events! ctx (mapv body (range 40 140)))
            expected (reduce (fn [m i] (update-in m [(nth owners (mod i 12)) :total] (fnil + 0) (inc i)))
                             {} (range 40 140))
            seen-d (atom []) seen-i (atom [])
            deferred-model (fixture/keyed-register! (counting seen-d fixture/keyed-history-step)
                                                    {:result fixture/keyed-total :start-after cut})
            incremental-model (fixture/keyed-register! (counting seen-i fixture/keyed-history-step)
                                                       {:result fixture/keyed-total :start-after cut})
            deferred (assoc ctx :read-model/batch-size 8 :read-model/deferred-build-threshold 16)
            incremental (assoc ctx :read-model/batch-size 8 :read-model/deferred-build-threshold 0)]
        (is (= expected (fixture/keyed-results deferred deferred-model)))
        (is (= expected (fixture/keyed-results incremental incremental-model)))
        (is (every? #(> % 40) @seen-d) "deferred build never folds events at or before start-after")
        (is (every? #(> % 40) @seen-i))
        (append-events! ctx [{:owner "o0" :delta 1000}])
        (is (= (update-in expected ["o0" :total] + 1000) (fixture/keyed-results deferred deferred-model)))
        (is (= (fixture/keyed-results deferred deferred-model) (fixture/keyed-results incremental incremental-model)))))))

;; ---------------------------------------------------------------------------
;; 9: baseline scenario
;; ---------------------------------------------------------------------------

(defn- baseline-step [state event]
  (case (:event/type event)
    :keyed/other {:total (:total event)}
    (update state :total (fnil + 0) (:delta event))))

(defn- baseline-history!
  "Many changes for owners o0..o9, then one baseline chunk carrying the true
   totals (as of the chunk), then a few changes. Returns the id of the last
   event before the baseline chunk."
  [ctx]
  (let [owners (mapv #(str "o" %) (range 10))
        history (vec (for [i (range 300)] {:owner (nth owners (mod i 10)) :delta (inc (mod i 7))}))
        totals (reduce (fn [m {:keys [owner delta]}] (update m owner (fnil + 0) delta)) {} history)]
    (doseq [chunk (partition-all 50 history)] (append-events! ctx (vec chunk)))
    (let [before-baseline (head-id ctx)]
      (append-events! ctx (vec (for [[owner total] (sort totals)] {:type :keyed/other :owner owner :total total})))
      (append-events! ctx [{:owner "o0" :delta 100} {:owner "o3" :delta 7}])
      before-baseline)))

(deftest keyed-baseline-start-after-matches-full-replay-without-reading-history
  (scoped/with-stores
    (fn [ctx]
      (let [cut (baseline-history! ctx)
            folded (atom 0)
            full (fixture/keyed-register! baseline-step {:events all-types})
            baselined (fixture/keyed-register! (fn [s e] (swap! folded inc) (baseline-step s e))
                                               {:events all-types :start-after cut})]
        (is (= 10 (count (fixture/keyed-results ctx full))))
        (is (= (fixture/keyed-results ctx full) (fixture/keyed-results ctx baselined)))
        (is (= 12 @folded) "only the 10 baseline events and the 2 later changes are folded")))))

(deftest ordinary-baseline-start-after-matches-full-replay-without-reading-history
  (scoped/with-stores
    (fn [ctx]
      (let [cut (baseline-history! ctx)
            folded (atom 0)
            step (fn [state event] (update state (:owner event) baseline-step event))
            full (ordinary! step {:events all-types :l1-ttl-ms 0})
            baselined (ordinary! (fn [s e] (swap! folded inc) (step s e))
                                 {:events all-types :start-after cut :l1-ttl-ms 0})]
        (is (= 10 (count (rmp/project ctx full))))
        (is (= (rmp/project ctx full) (rmp/project ctx baselined)))
        (is (= 12 @folded))))))

;; ---------------------------------------------------------------------------
;; 10: validation
;; ---------------------------------------------------------------------------

(deftest non-uuid-start-after-is-rejected
  (fixture/keyed-fixture
    (fn []
      (doseq [bad ["x" :kw 42 (str (uuid/v7)) nil]]
        (testing (pr-str bad)
          (is (thrown? clojure.lang.ExceptionInfo (fixture/keyed-register! fixture/keyed-history-step {:start-after bad}))
              "keyed")
          (is (thrown? clojure.lang.ExceptionInfo (ordinary! total-step {:start-after bad}))
              "ordinary"))))))
