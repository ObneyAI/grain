(ns ai.obney.grain.read-model-processor-v2.prepared-batch-test
  "Catch-up batches and deferred builds prepare their pure and read-only work
   in read transactions and keep only the writes in write transactions. A
   preparation is used only while the namespace's progress record is
   unchanged (and a staging build still owns its namespace); otherwise the
   batch is prepared again inside the write transaction. Stored bytes equal
   preparing inside the write transaction."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(def model :prepared-batch/items)
(def tenant (java.util.UUID. 74 1))
(def identity* [tenant model 1])
(def shared [tenant :grain.read-query/shared-identities 1])
(def frozen-time (java.time.OffsetDateTime/parse "2026-10-15T00:00:00Z"))

(defn- cv [s]
  (or (get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) s)
      (throw (ex-info "No such processor var" {:var s}))))
(defn- det-promotion-hash [_ _ _ ^bytes k] (clojure.lang.Murmur3/hashInt (java.util.Arrays/hashCode k)))

(def ^:private words ["alpha" "bravo" "charlie" "delta" "ünïcode" "日本語テキスト" "x"])

(defn- bodies
  "Seeded history over 40 owners: every input appends its delta to the owner's
   history, so an input applied twice is visible in the result."
  [seed n]
  (let [r (java.util.Random. seed)]
    (vec (for [i (range n)]
           {:inputs (vec (for [j (range (inc (.nextInt r 2)))]
                           [(.nextInt r 40) {:delta (+ (* 1000 i) j)
                                             :deleted (zero? (.nextInt r 9))
                                             :name (str (nth words (.nextInt r (count words))) " " (.nextInt r 5))}]))}))))

(defn- step [state input]
  (-> state (update :history (fnil conj []) (:delta input)) (assoc :deleted (:deleted input) :name (:name input))))

(defn- result [state]
  {:history (:history state) :total (reduce + 0 (:history state)) :name (:name state) :deleted (:deleted state)})

(defn- truth [history]
  (into (sorted-map)
        (keep (fn [[id state]] (when-not (:deleted state) [id (result state)])))
        (reduce (fn [m {:keys [inputs]}] (reduce (fn [m [o in]] (update m o step in)) m inputs)) {} history)))

(defn- register! [& [reducer]]
  (rmp/register-read-model! model (or reducer step)
    {:events #{:keyed/change} :version 1 :route :inputs :result result
     :include? (complement :deleted)
     :indexes {:total :total :name :name}
     :filtered-indexes #{:total}
     :searches {:name :name}}))

(defn- values [ctx] (into (sorted-map) (map (juxt :key :value)) (rmp/values ctx model)))

(defn- hex [b] (.formatHex (java.util.HexFormat/of) (byte-array b)))

(defn- rows-under [cache ^bytes prefix]
  (kv/transact cache :read
    #((:reduce %) {:prefix prefix}
      (fn [out {:keys [^bytes k v]}]
        (assoc out (hex (java.util.Arrays/copyOfRange k (alength prefix) (alength k))) (hex v)))
      (sorted-map))))

(defn- prefix-of [ctx identity] (get (core/namespace-resolve! (:cache ctx) [identity]) identity))

(defn- namespace-rows [ctx]
  {:model (rows-under (:cache ctx) (prefix-of ctx identity*))
   :shared (rows-under (:cache ctx) (prefix-of ctx shared))})

(defn- stored-progress [ctx]
  (let [b (prefix-of ctx identity*)]
    (kv/transact (:cache ctx) :read #(@(cv 'keyed-read-meta) % (@(cv 'keyed-progress-key) b)))))

(defn- events-of [ctx]
  (into [] (es/read (:event-store ctx) {:tenant-id tenant :types #{:keyed/change}})))

(defn- writer-ctx
  "A catch-up context as keyed-catch-up! passes to keyed-apply-batch!."
  [ctx]
  (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix (prefix-of ctx shared)))

(defn- apply-batch!
  "One batch, as keyed-catch-up! commits it."
  [ctx events]
  (@(cv 'keyed-apply-batch!) ctx model (get (rmp/global-read-model-registry) model) (prefix-of ctx identity*)
   (constantly nil) (constantly nil) events))

(defn- scenario
  "Fresh cache and store; frozen event ids, time and promotion hash so raw rows
   compare across runs. (f ctx) runs after registration."
  [redefs f]
  (fixture/keyed-fixture
    (fn []
      (let [counter (atom 0)
            ctx (assoc fixture/*keyed-ctx* :tenant-id tenant)]
        (with-redefs-fn (merge {(cv 'ranked-promotion-hash) det-promotion-hash
                                #'time/now (constantly frozen-time)
                                #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
                               redefs)
          (fn [] (register!) (f ctx)))))))

(defn- preparations
  "Redefinitions counting how write transactions obtained their batch:
   :prepared (validated read-transaction preparation) or :inline."
  [counts]
  (let [write @(cv 'keyed-write-batch!) prepare @(cv 'keyed-prepare-batch)]
    {(cv 'keyed-prepare-batch)
     (fn [ctx name d b vs vr events owners? tx]
       (when-not owners? (swap! counts update :inline (fnil inc 0)))
       (prepare ctx name d b vs vr events owners? tx))
     (cv 'keyed-write-batch!)
     (fn [ctx name d b vs vr events through prepared]
       (when prepared (swap! counts update :offered (fnil inc 0)))
       (write ctx name d b vs vr events through prepared))}))

;; ---------------------------------------------------------------------------

(deftest concurrent-writer-between-prepare-and-write-is-never-applied-twice
  ;; Mutation killed: dropping the progress validation (a prepared batch is
  ;; always used) applies the concurrent writer's events twice.
  (doseq [[label cut] {:partial 15 :whole 40}]
    (testing label
      (let [history (bodies 5 120)
            run (fn [disturb?]
                  (let [counts (atom {})]
                    (scenario
                      (preparations counts)
                      (fn [ctx]
                        (fixture/keyed-append! ctx history)
                        (let [ctx (writer-ctx ctx) events (events-of ctx)
                              write @(cv 'keyed-write-batch!) fired (atom false)]
                          (apply-batch! ctx (subvec events 0 40))
                          (if disturb?
                            ;; Another writer commits the first `cut` events of
                            ;; this batch after it was prepared.
                            (with-redefs-fn {(cv 'keyed-write-batch!)
                                             (fn [c name d b vs vr evs through prepared]
                                               (when (and prepared (compare-and-set! fired false true))
                                                 (apply-batch! c (subvec events 40 (+ 40 cut))))
                                               (write c name d b vs vr evs through prepared))}
                              #(apply-batch! ctx (subvec events 40 80)))
                            (do (apply-batch! ctx (subvec events 40 (+ 40 cut)))
                                (apply-batch! ctx (subvec events 40 80))))
                          (let [after-batch (namespace-rows ctx)]
                            (rmp/progress ctx model)
                            {:after-batch after-batch :rows (namespace-rows ctx) :values (values ctx)
                             :watermark (:watermark (rmp/progress ctx model)) :last (:event/id (peek events))
                             :counts @counts}))))))
            reference (run false)
            disturbed (run true)]
        (is (= (truth history) (:values disturbed)) "every input applied exactly once")
        (is (= (:last disturbed) (:watermark disturbed)))
        (is (= (:after-batch reference) (:after-batch disturbed))
            "the batch's commit equals the sequential commits, byte for byte")
        (is (= (:rows reference) (:rows disturbed)))
        (is (= 1 (get-in disturbed [:counts :inline])) "the invalidated preparation was redone in the write transaction")
        (is (nil? (get-in reference [:counts :inline])) "undisturbed batches use their preparation")))))

(deftest publish-between-prepare-and-write-is-revalidated
  ;; A writer of the cold namespace prepares a batch over a checkpointed
  ;; progress record; a deferred build publishes (wiping that namespace) before
  ;; the writer's write transaction. The writer must fold as if it had read the
  ;; wiped namespace, and the published namespace must be untouched.
  ;; Mutation killed: dropping the progress validation (the stale checkpoint
  ;; is written back into the wiped namespace).
  (let [history (bodies 6 200)
        setup (fn [ctx]
                (fixture/keyed-append! ctx [{:inputs []}])
                (let [ctx (writer-ctx ctx)]
                  ;; The model had no events at the last read: a checkpoint, no watermark.
                  (@(cv 'keyed-checkpoint!) ctx (get (rmp/global-read-model-registry) model) (prefix-of ctx identity*)
                   (@(cv 'keyed-head) ctx))
                  (fixture/keyed-append! ctx history)
                  ctx))
        counts (atom {})
        disturbed
        (scenario
          (preparations counts)
          (fn [ctx]
            (let [ctx (setup ctx) b (prefix-of ctx identity*)
                  events (vec (rest (events-of ctx)))
                  write @(cv 'keyed-write-batch!) fired (atom false) published (atom nil)]
              (is (and (:checked-through (stored-progress ctx)) (nil? (:watermark (stored-progress ctx))))
                  "precondition: the cold namespace holds a checkpoint")
              (with-redefs-fn {(cv 'keyed-write-batch!)
                               (fn [c name d bb vs vr evs through prepared]
                                 (when (and prepared (compare-and-set! fired false true))
                                   (let [p (assoc ctx :read-model/batch-size 8 :read-model/deferred-build-threshold 16)]
                                     (rmp/progress p model)
                                     (let [now (prefix-of ctx identity*)]
                                       (reset! published [now (rows-under (:cache ctx) now)]))))
                                 (write c name d bb vs vr evs through prepared))}
                #(apply-batch! ctx (subvec events 0 8)))
              (let [[now published-rows] @published]
                (is (not (java.util.Arrays/equals ^bytes now ^bytes b)) "the build published another namespace")
                (is (= published-rows (rows-under (:cache ctx) now)) "the late writer never touches the published namespace")
                {:values (values ctx) :late (rows-under (:cache ctx) b)}))))
        reference
        (scenario {}
          (fn [ctx]
            (fixture/keyed-append! ctx [{:inputs []}])
            (fixture/keyed-append! ctx history)
            (let [ctx (writer-ctx ctx) b (prefix-of ctx identity*)]
              (apply-batch! ctx (subvec (vec (rest (events-of ctx))) 0 8))
              (rows-under (:cache ctx) b))))]
    (is (= (truth history) (:values disturbed)))
    (is (= reference (:late disturbed)) "the late batch folded the wiped namespace from scratch")
    (is (= 1 (:inline @counts)) "the preparation over the checkpoint was redone after the publish")))

(defn- map-full-exception []
  (let [c (.getDeclaredConstructor org.lmdbjava.Env$MapFullException (into-array Class []))]
    (.setAccessible c true)
    (.newInstance c (object-array 0))))

(deftest map-full-during-a-prepared-write-backs-off
  ;; MapFull raised inside write transactions that use a validated preparation:
  ;; the batch is retried in halves, each prepared afresh, and every event is
  ;; applied once.
  ;; Mutation killed: reusing a namespace's last preparation while its
  ;; progress record is unchanged, regardless of the events (each half would
  ;; write the aborted whole batch again).
  (let [history (bodies 8 3000)
        run (fn [full?]
              (let [counts (atom {}) sizes (atom [])]
                (scenario
                  (merge
                    (preparations counts)
                    (when full?
                      (let [write @(cv 'keyed-write-batch!) bound @(cv 'count-caches-bound!) current (atom 0)]
                        {(cv 'keyed-write-batch!)
                         (fn [c name d b vs vr evs through prepared]
                           ;; The events the transaction writes: the prepared ones.
                           (reset! current (if prepared (count (:events prepared)) 0))
                           (when prepared (swap! counts update :offered (fnil inc 0)))
                           (swap! sizes conj (count evs))
                           (write c name d b vs vr evs through prepared))
                         ;; Runs inside the write transaction, after validation.
                         (cv 'count-caches-bound!)
                         (fn [tx] (when (> @current 300) (throw (map-full-exception))) (bound tx))})))
                  (fn [ctx]
                    (fixture/keyed-append! ctx history)
                    (let [ctx (assoc ctx :read-model/deferred-build-threshold 0)]
                      {:values (values ctx) :rows (namespace-rows ctx) :sizes @sizes :counts @counts})))))
        full (run true)
        plain (run false)]
    (is (= (truth history) (:values full)) "every event applied exactly once")
    (is (= [1024 512 256 256 512 256 256] (take 7 (:sizes full))))
    (is (pos? (get-in full [:counts :offered])))
    (is (nil? (get-in full [:counts :inline])) "MapFull is not a validation failure")
    (is (= (:values plain) (:values full)))))

(deftest reducer-exceptions-are-raised-from-the-write-transaction
  (testing "a failing reducer fails the batch and writes nothing"
    ;; The preparation fails in its read transaction; the batch is prepared
    ;; again inside the write transaction, which raises the reducer's own
    ;; exception and commits nothing.
    (let [counts (atom {})]
      (scenario
        (preparations counts)
        (fn [ctx]
          (register! (fn [state input]
                       (when (= 7 (:delta input)) (throw (ex-info "reducer failed" {:boom true})))
                       (step state input)))
          (fixture/keyed-append! ctx [{:inputs [[1 {:delta 1 :name "a"}]]}])
          (is (= {1 (result (step nil {:delta 1 :name "a"}))} (values ctx)))
          (let [before (namespace-rows ctx) progress (stored-progress ctx)]
            (reset! counts {})
            (fixture/keyed-append! ctx [{:inputs [[2 {:delta 2 :name "b"}]]} {:inputs [[3 {:delta 7 :name "c"}]]}])
            (let [e (try (rmp/progress ctx model) nil (catch clojure.lang.ExceptionInfo e e))]
              (is (= {:boom true} (some-> e ex-data)) "the reducer's exception, unwrapped"))
            (is (= 1 (:inline @counts)) "raised by the in-transaction preparation")
            (is (= before (namespace-rows ctx)) "nothing committed")
            (is (= (dissoc progress :index-layout) (dissoc (stored-progress ctx) :index-layout))))))))
  (testing "a failure seen only by the read-transaction preparation is not raised"
    ;; Mutation killed: raising preparation failures directly (no fallback).
    (let [counts (atom {}) failed (atom 0)]
      (scenario
        (preparations counts)
        (fn [ctx]
          (register! (fn [state input]
                       (when (and (= 7 (:delta input)) (= 1 (swap! failed inc)))
                         (throw (ex-info "transient" {})))
                       (step state input)))
          (let [history [{:inputs [[2 {:delta 2 :name "b"}]]} {:inputs [[3 {:delta 7 :name "c"}]]}]]
            (fixture/keyed-append! ctx history)
            (is (= (truth history) (values ctx)))
            (is (= 1 (:inline @counts)))))))))

(deftest superseded-staging-write-after-preparation-writes-nothing
  ;; A staged fold batch is prepared, then another process takes the build
  ;; over before the write transaction: the guard still runs first in the
  ;; write transaction, so the batch writes nothing and the build restarts.
  ;; Mutation killed: skipping deferred-guard! for a validated preparation.
  (let [counts (atom {}) staged-before (atom nil) staged-after (atom nil) taken (atom false)]
    (scenario
      (merge (preparations counts)
             (let [write @(cv 'keyed-write-batch!)]
               {(cv 'keyed-write-batch!)
                (fn [c name d b vs vr evs through prepared]
                  (if-let [s (and prepared (:ai.obney.grain.read-model-processor-v2.core/deferred c)
                                  (compare-and-set! taken false true)
                                  (:ai.obney.grain.read-model-processor-v2.core/deferred c))]
                    (do
                      ;; A dead process's marker: the restarted catch-up takes it over.
                      (kv/transact (:cache c) :write
                        (fn [tx] ((:put tx) (:marker s)
                                            (codec/encode {:identity identity* :build "other" :pid nil :at nil}))))
                      (reset! staged-before (rows-under (:cache c) b))
                      (try (write c name d b vs vr evs through prepared)
                           (finally (reset! staged-after (rows-under (:cache c) b)))))
                    (write c name d b vs vr evs through prepared)))}))
      (fn [ctx]
        (let [history (bodies 9 200) ctx (assoc ctx :read-model/batch-size 8 :read-model/deferred-build-threshold 16)]
          (fixture/keyed-append! ctx history)
          (is (= (truth history) (values ctx)))
          (is @taken)
          (is (= @staged-before @staged-after) "the superseded batch wrote nothing"))))))

(deftest superseded-build-read-failure-restarts-as-superseded
  ;; The index pass of a deferred build reads, decodes and sorts in a read
  ;; transaction. Another process takes the build over while it reads, and the
  ;; read fails: the build must still fail as superseded (as when it read after
  ;; its guard) and restart, never surface the read failure.
  ;; Mutation killed: raising a failed build read without the guarded write.
  (let [sorts (atom 0) taken (atom false)]
    (scenario
      (let [sort @(cv 'deferred-sort!)]
        {(cv 'deferred-sort!)
         (fn [index entries]
           (when (= 1 (swap! sorts inc))
             ;; Another process (thread) takes the build over, then this read fails.
             @(future
                (kv/transact (:cache fixture/*keyed-ctx*) :write
                  (fn [tx]
                    (let [k (@(cv 'deferred-slot) identity* 1)
                          m (codec/decode ((:get tx) k))]
                      ((:put tx) k (codec/encode (assoc m :build "other" :pid nil :at nil)))))))
             (reset! taken true)
             (throw (ex-info "build read failed" {})))
           (sort index entries))})
      (fn [ctx]
        (let [history (bodies 13 200) ctx (assoc ctx :read-model/batch-size 8 :read-model/deferred-build-threshold 16)]
          (fixture/keyed-append! ctx history)
          (is (= (truth history) (values ctx)))
          (is @taken))))))

(deftest observer-sees-each-owner-prepared-before-its-writes
  ;; With a :read-model/observer every batch is prepared inside its write
  ;; transaction, owner by owner, so each owner's result is calculated right
  ;; before its writes, as before preparation.
  ;; Mutation killed: preparing in a read transaction also with an observer.
  (scenario {}
    (fn [ctx]
      (let [seen (atom []) ctx (assoc ctx :read-model/observer #(swap! seen conj (:operation %)))]
        (fixture/keyed-append! ctx (bodies 14 40))
        (rmp/progress ctx model)
        (reset! seen [])
        (fixture/keyed-append! ctx (bodies 15 20))
        (rmp/progress ctx model)
        (let [owner-ops (filterv #{:result-calculated :state-written} @seen)]
          (is (< 4 (count owner-ops)))
          (is (= (take (count owner-ops) (cycle [:result-calculated :state-written])) owner-ops)))))))

;; Byte equality of the prepared paths -----------------------------------------

(defn- built [opts redefs]
  (scenario redefs
    (fn [ctx]
      (let [ctx (merge ctx opts) history (bodies 11 300)]
        (fixture/keyed-append! ctx history)
        (rmp/progress ctx model)
        (fixture/keyed-append! ctx (bodies 12 60))
        {:values (values ctx) :rows (namespace-rows ctx)}))))

(def ^:private inline-only
  ;; Every batch prepared inside its write transaction, as before preparation.
  (let [write @(cv 'keyed-write-batch!)]
    {(cv 'keyed-write-batch!) (fn [c name d b vs vr evs through _] (write c name d b vs vr evs through nil))}))

;; Cancellation and colliding owner addresses ---------------------------------

(deftest an-interrupt-during-preparation-cancels-the-catch-up
  ;; An interrupt while a reducer blocks in the read-transaction preparation
  ;; ends the catch-up with the InterruptedException, as when the reducer ran
  ;; in the write transaction: no second preparation, nothing committed.
  ;; Mutation killed: catching Throwable (or Exception without the interrupt
  ;; check) and preparing again in the write transaction.
  (scenario {}
    (fn [ctx]
      (let [entered (atom 0) gate (promise) out (promise)]
        (register! (fn [state input]
                     (when (= 99 (:delta input))
                       (swap! entered inc) (deliver gate true) (Thread/sleep 1500))
                     (step state input)))
        (fixture/keyed-append! ctx [{:inputs [[1 {:delta 99 :name "x"}]]}])
        (let [t (Thread. (fn []
                           (deliver out (try (rmp/progress ctx model) :ok
                                             (catch Throwable e
                                               (if (@(cv 'keyed-interrupt?) e) :interrupted e))))))]
          (.start t)
          @gate (Thread/sleep 100) (.interrupt t)
          (is (= :interrupted (deref out 20000 :timeout)))
          (is (= 1 @entered) "the reducer is not run again")
          (is (nil? (stored-progress ctx)) "nothing committed"))))))

(deftest owners-sharing-an-address-are-prepared-in-the-write-transaction
  ;; Two owner ids that are not = but encode to one address: the second must
  ;; see the first one's writes, as when both were prepared in the write
  ;; transaction.
  ;; Mutation killed: dropping the distinct-address check.
  (let [a (keyword "a/b" "c") b (keyword "a" "b/c")
        history [{:inputs [[a {:delta 1 :name "x"}] [b {:delta 2 :name "y"}] [:plain {:delta 3 :name "z"}]]}
                 {:inputs [[a {:delta 4 :name "x"}]]}]
        run (fn [redefs]
              (let [counts (atom {})]
                (scenario (merge (preparations counts) redefs)
                  (fn [ctx]
                    (fixture/keyed-append! ctx history)
                    (let [vs (values ctx) p (prefix-of ctx identity*)]
                      {:values vs :rows (namespace-rows ctx) :counts @counts
                       :count (kv/transact (:cache ctx) :read #(@(cv 'keyed-read-meta) % (@(cv 'keyed-count-key) p)))})))))
        reference (run inline-only)
        candidate (run {})]
    (is (not= a b))
    (is (java.util.Arrays/equals ^bytes (core/key-encode a) ^bytes (core/key-encode b)))
    (is (= 2 (:count reference)))
    (is (= (:count reference) (:count candidate)))
    (is (= (:values reference) (:values candidate)))
    (is (= (:rows reference) (:rows candidate)) "byte-equal to preparing in the write transaction")))

(deftest prepared-batches-and-builds-are-byte-identical
  ;; Mutations killed: an unsorted prepared deferred run, prepared owners out of
  ;; fold order.
  (let [incremental {:read-model/batch-size 8 :read-model/deferred-build-threshold 0}
        deferred {:read-model/batch-size 8 :read-model/deferred-build-threshold 16}
        reference (built incremental inline-only)]
    (is (seq (:values reference)))
    (doseq [[label opts] {:incremental incremental
                          :deferred deferred
                          :one-unit-per-pass (assoc deferred :read-model/deferred-build-entries 100)
                          :sorted-runs-below-n (assoc deferred :read-model/deferred-build-entries 7)}]
      (testing label
        (let [candidate (built opts {})]
          (is (= (:values reference) (:values candidate)))
          (is (= (get-in reference [:rows :shared]) (get-in candidate [:rows :shared])))
          (is (= (get-in reference [:rows :model]) (get-in candidate [:rows :model]))))))))
