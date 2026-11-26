(ns ai.obney.grain.read-model-processor-v2.empty-interval-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.scoped-test :as scoped]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb])
  (:import [java.nio ByteBuffer]
           [org.lmdbjava Dbi]))

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

(deftest scoped-checkpoints-preserve-idle-and-relevant-query-counts
  (scoped/with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            own (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (append-user! ctx a 1)
        (is (= {:total 1} (rmp/value own model "a")))
        (append-user! ctx b 3)
        (is (= {:value {:total 1} :counts {:heads 1 :event-queries 1 :writes 1}}
               (counted #(rmp/value own model "a"))))
        (let [checkpointed (bytes-in-cache ctx)]
          (dotimes [_ 3]
            (is (= {:value {:total 1} :counts {:heads 1 :event-queries 0 :writes 0}}
                   (counted #(rmp/value own model "a")))))
          (is (= checkpointed (bytes-in-cache ctx))))
        (append-user! ctx b 4)
        (is (= {:heads 1 :event-queries 1 :writes 1}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx a 5)
        (is (= {:value {:total 6} :counts {:heads 1 :event-queries 1 :writes 1}}
               (counted #(rmp/value own model "a"))))
        (is (= {:heads 1 :event-queries 0 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx b 7)
        (is (= {:heads 1 :event-queries 1 :writes 1}
               (:counts (counted #(rmp/value own model "a")))))
        (is (= {:heads 1 :event-queries 0 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))))))

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
        ;; An unrelated native write leaves durable model progress intact.
        (let [^Dbi db (:db (:cache ctx))
              key (doto (ByteBuffer/allocateDirect 1) (.put (byte-array [1])) (.flip))
              value (doto (ByteBuffer/allocateDirect 1) (.put (byte-array [2])) (.flip))]
          (.put db key value))
        (is (= {:heads 1 :event-queries 0 :writes 0}
               (:counts (counted #(rmp/value own model "a")))))
        (let [replacement (es/start {:conn {:type :in-memory}})]
          (try
            (dosync (ref-set (:state replacement) @(:state (:event-store ctx))))
            (is (= {:value {:total 1} :counts {:heads 1 :event-queries 0 :writes 0}}
                   (counted #(rmp/value (assoc own :event-store replacement) model "a"))))
            (finally (es/stop replacement))))
        ;; Clearing the materialization requires replay from the source.
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

(deftest failed-reads-and-folds-do-not-advance-progress
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
        (is (= {:heads 1 :event-queries 1 :writes 1}
               (:counts (counted #(rmp/value own model "a")))))
        (append-user! ctx a 5)
        (reset! fail? true)
        (let [before (bytes-in-cache ctx)]
          (is (thrown-with-msg? Exception #"Fold failed" (rmp/value own model "a")))
          (is (= before (bytes-in-cache ctx))))
        (reset! fail? false)
        (is (= {:total 6} (rmp/value own model "a")))))))

(deftest idle-scopes-reuse-their-durable-checkpoints
  (doseq [n [257 600]]
    (fixture/keyed-fixture
      (fn []
        (let [ctx fixture/*keyed-ctx* b (random-uuid)
              model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
              contexts (mapv #(rmp/scoped-context ctx {:tags #{[:User (java.util.UUID. 62 %)]}}) (range n))
              read-all #(mapv (fn [own] (rmp/value own model "a" :missing)) contexts)]
          ;; Prime only namespace resolution before the first source event.
          (read-all)
          (append-user! ctx b 1)
          (is (= {:heads n :event-queries n :writes n} (:counts (counted read-all))))
          (dotimes [_ 2]
            (is (= {:heads n :event-queries 0 :writes 0} (:counts (counted read-all)))))
          (append-user! ctx b 2)
          (is (= {:heads n :event-queries n :writes n} (:counts (counted read-all))))
          (is (= {:heads n :event-queries 0 :writes 0} (:counts (counted read-all)))))))))
