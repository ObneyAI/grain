(ns ai.obney.grain.read-model-processor-v2.empty-interval-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.scoped-test :as scoped]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.protocol :as protocol]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb])
  (:import [java.lang.ref WeakReference]
           [java.nio ByteBuffer]
           [org.lmdbjava Dbi]))

(defn reset-proofs! [] (reset! (var-get #'core/empty-proofs*) {:entries {} :bytes 0}))
(use-fixtures :each (fn [f] (reset-proofs!) (try (f) (finally (reset-proofs!)))))

(defn counted [f]
  (let [counts (atom {:heads 0 :event-queries 0 :writes 0})
        head es/head events es/read transact kv/transact]
    (with-redefs [es/head (fn [& args] (swap! counts update :heads inc) (apply head args))
                  es/read (fn [& args] (swap! counts update :event-queries inc) (apply events args))
                  kv/transact (fn [cache mode callback]
                                (when (= :write mode) (swap! counts update :writes inc))
                                (transact cache mode callback))]
      {:value (f) :counts @counts})))

(defn bytes-in-cache [ctx]
  (kv/transact (:cache ctx) :read
    #((:reduce %) {} (fn [out {:keys [k v]}] (assoc out (vec k) (vec v))) {})))

(defn append-user! [ctx user delta]
  (scoped/append! ctx [{:owner "a" :delta delta :tags #{[:User user]}}]))

(deftest negative-evidence-preserves-idle-and-relevant-query-counts
  (scoped/with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (is (true? (es/stable-empty-intervals? (:event-store ctx))))
        (append-user! ctx a 1)
        (is (= {:total 1} (rmp/value own model "a")))
        (append-user! ctx b 3)
        (let [before (bytes-in-cache ctx)]
          (is (= {:value {:total 1} :counts {:heads 1 :event-queries 1 :writes 0}}
                 (counted #(rmp/value own model "a"))))
          (dotimes [_ 3]
            (is (= {:value {:total 1} :counts {:heads 1 :event-queries 0 :writes 0}}
                   (counted #(rmp/value own model "a")))))
          (is (= before (bytes-in-cache ctx))))
        (append-user! ctx b 4)
        (is (= {:heads 1 :event-queries 1 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx a 5)
        (is (= {:value {:total 6} :counts {:heads 1 :event-queries 1 :writes 1}}
               (counted #(rmp/value own model "a"))))
        (is (= {:heads 1 :event-queries 0 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx b 7)
        (with-redefs [es/stable-empty-intervals? (constantly false)]
          (is (= {:heads 1 :event-queries 1 :writes 1}
                 (:counts (counted #(rmp/value own model "a")))))
          (is (= {:heads 1 :event-queries 0 :writes 0}
                 (:counts (counted #(rmp/value own model "a"))))))))))

(deftest missing-first-event-and-captured-head-race
  (scoped/with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (is (= :missing (rmp/value own model "a" :missing)))
        (append-user! ctx b 2)
        (is (= :missing (rmp/value own model "a" :missing)))
        (let [head es/head]
          (append-user! ctx b 3)
          (with-redefs [es/head (fn [store tenant]
                                 (let [h (head store tenant)]
                                   (append-user! ctx a 4)
                                   h))]
            (is (= :missing (rmp/value own model "a" :missing)))))
        (is (= {:total 4} (rmp/value own model "a")))))))

(deftest source-replacement-and-cache-mutations-use-current-progress
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx* a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (append-user! ctx a 1)
        (is (= {:total 1} (rmp/value own model "a")))
        (append-user! ctx b 2)
        (is (= {:total 1} (rmp/value own model "a")))
        ;; A native unrelated cache mutation cannot alter an immutable event
        ;; interval; progress is still read from the current native snapshot.
        (let [^Dbi db (:db (:cache ctx))
              key (doto (ByteBuffer/allocateDirect 1) (.put (byte-array [1])) (.flip))
              value (doto (ByteBuffer/allocateDirect 1) (.put (byte-array [2])) (.flip))]
          (.put db key value))
        (is (= {:heads 1 :event-queries 0 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (let [replacement (es/start {:conn {:type :in-memory}})]
          (try
            (dosync (ref-set (:state replacement) @(:state (:event-store ctx))))
            (is (= {:heads 1 :event-queries 1 :writes 0}
                   (:counts (counted #(rmp/value (assoc own :event-store replacement) model "a")))))
            (finally (es/stop replacement))))
        ;; Clearing the materialization lowers durable progress below the
        ;; proven interval, so all matching history must be replayed normally.
        (kv/transact (:cache ctx) :write
          (fn [tx]
            (let [keys ((:reduce tx) {} (fn [out {:keys [k]}] (conj out k)) [])]
              (doseq [key keys] ((:delete tx) key)))))
        (let [{:keys [value counts]} (counted #(rmp/value own model "a"))]
          (is (= {:total 1} value))
          (is (= 1 (:event-queries counts))))
        (append-user! ctx b 3)
        (is (= {:total 1} (rmp/value own model "a")))
        (let [cache (:cache ctx)]
          (kv/stop cache)
          (let [reopened (kv/start (lmdb/->KV-Store-LMDB (:config cache)))]
            (reset! fixture/*keyed-cache-handle* reopened)
            (is (= {:value {:total 1} :counts {:heads 1 :event-queries 0 :writes 0}}
                   (counted #(rmp/value (assoc own :cache reopened) model "a"))))))))))

(deftest read-and-fold-failures-never-prove-absence
  (scoped/with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid) fail? (atom false)
            model (fixture/keyed-register!
                    (fn [s e] (when @fail? (throw (ex-info "Fold failed" {})))
                      (fixture/keyed-history-step s e))
                    {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (append-user! ctx a 1)
        (is (= {:total 1} (rmp/value own model "a")))
        (append-user! ctx b 2)
        (let [before (bytes-in-cache ctx)]
          (with-redefs [es/read (fn [& _] (throw (ex-info "Read failed" {})))]
            (is (thrown-with-msg? Exception #"Read failed" (rmp/value own model "a"))))
          (is (= before (bytes-in-cache ctx))))
        (is (= {:heads 1 :event-queries 1 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx a 5)
        (reset! fail? true)
        (let [before (bytes-in-cache ctx)]
          (is (thrown-with-msg? Exception #"Fold failed" (rmp/value own model "a")))
          (is (= before (bytes-in-cache ctx))))
        (reset! fail? false)
        (is (= {:total 6} (rmp/value own model "a")))))))

(deftest broad-working-set-falls-back-to-durable-checkpoints-without-thrashing
  (doseq [n [257 600]]
    (fixture/keyed-fixture
      (fn []
        (reset-proofs!)
        (let [ctx fixture/*keyed-ctx* b (random-uuid)
              model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
              contexts (mapv #(rmp/scoped-context ctx {:tags #{[:User (java.util.UUID. 62 %)]}}) (range n))
              read-all #(mapv (fn [own] (rmp/value own model "a" :missing)) contexts)]
          ;; Prime only namespace resolution before the first source event.
          (read-all)
          (append-user! ctx b 1)
          (is (= {:heads n :event-queries n :writes (- n 256)} (:counts (counted read-all))))
          (is (= 256 (count (:entries @(var-get #'core/empty-proofs*)))))
          (dotimes [_ 2]
            (is (= {:heads n :event-queries 0 :writes 0} (:counts (counted read-all)))))
          (append-user! ctx b 2)
          (is (= {:heads n :event-queries n :writes (- n 256)} (:counts (counted read-all))))
          (is (= {:heads n :event-queries 0 :writes 0} (:counts (counted read-all)))))))))

(deftest byte-budget-and-weak-owners-have-safe-fallbacks
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx* b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            one (rmp/scoped-context ctx {:tags #{[:User (random-uuid)]}})
            two (rmp/scoped-context ctx {:tags #{[:User (random-uuid)]}})]
        (rmp/value one model "a") (rmp/value two model "a")
        (append-user! ctx b 1)
        (with-redefs-fn {#'core/empty-proof-max-bytes 1}
          #(do (is (= {:heads 1 :event-queries 1 :writes 1}
                      (:counts (counted (fn [] (rmp/value one model "a"))))))
               (is (= {:heads 1 :event-queries 0 :writes 0}
                      (:counts (counted (fn [] (rmp/value one model "a"))))))))
        (is (empty? (:entries @(var-get #'core/empty-proofs*))))
        (is (= {:heads 1 :event-queries 1 :writes 0}
               (:counts (counted #(rmp/value two model "a")))))
        (let [proofs (:entries @(var-get #'core/empty-proofs*))
              [[key proof]] (vec proofs)]
          (is (instance? WeakReference (:source proof)))
          (is (not-any? #(identical? % (:event-store ctx)) (tree-seq coll? seq key)))
          (is (not-any? #(identical? % (:cache ctx)) (tree-seq coll? seq [key (dissoc proof :source)])))
          (.clear ^WeakReference (:source proof))
          (is (= {:heads 1 :event-queries 1 :writes 0}
                 (:counts (counted #(rmp/value two model "a"))))))))))

(deftest absent-or-explicitly-mutable-capability-keeps-ordinary-catch-up
  (is (false? (es/stable-empty-intervals? (reify protocol/EventStore))))
  (is (false? (es/stable-empty-intervals?
               (reify protocol/EventStoreStableEmptyIntervals
                 (stable-empty-intervals? [_] false))))))

(deftest capacity-cleanup-is-amortized-and-can-reclaim-dead-owners
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx* b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            contexts (mapv #(rmp/scoped-context ctx {:tags #{[:User (java.util.UUID. 81 %)]}}) (range 4))
            prune-var #'core/prune-empty-proofs prune @prune-var calls (atom 0)]
        (doseq [own contexts] (rmp/value own model "a"))
        (append-user! ctx b 1)
        (with-redefs-fn {#'core/empty-proof-max-entries 1 #'core/empty-proof-prune-every 2
                        prune-var (fn [state] (swap! calls inc) (prune state))}
          #(do
             (rmp/value (first contexts) model "a")
             (let [proof (first (vals (:entries @(var-get #'core/empty-proofs*))))]
               (.clear ^WeakReference (:source proof)))
             ;; The first full miss reclaims the dead owner and admits a proof.
             (is (= {:heads 1 :event-queries 1 :writes 0}
                    (:counts (counted (fn [] (rmp/value (second contexts) model "a"))))))
             (is (= 1 @calls))
             ;; Updating an admitted proof must not reset the cleanup counter.
             (append-user! ctx b 2)
             (rmp/value (second contexts) model "a")
             (is (= {:heads 1 :event-queries 1 :writes 1}
                    (:counts (counted (fn [] (rmp/value (nth contexts 2) model "a"))))))
             (is (= 1 @calls))
             (rmp/value (nth contexts 3) model "a")
             (is (= 2 @calls))
             (is (= 1 (count (:entries @(var-get #'core/empty-proofs*)))))))))))
