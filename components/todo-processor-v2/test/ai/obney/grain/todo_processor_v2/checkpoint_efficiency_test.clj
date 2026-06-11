(ns ai.obney.grain.todo-processor-v2.checkpoint-efficiency-test
  "Guards against the O(N²) checkpoint regression: the checkpoint read helpers
   must consume a CONSTANT number of event-store rows regardless of how many
   checkpoints already exist. This is the deterministic, machine-independent
   proof of the O(N)->O(1) fix — it counts work units, not wall-clock time."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.obney.grain.event-store-v3.interface :as es :refer [->event]]
            [ai.obney.grain.event-store-v3.interface.protocol :as p]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as tp-core]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))

(defschemas events
  {:test/trigger [:map]})

(def proc-name :test/efficiency-proc)

;; ----------------------------------------------------------------- ;;
;; Counting decorator — wraps `read` and tallies rows the helpers     ;;
;; actually consume (no production-code change). Seed via the inner   ;;
;; store so only helper reads are counted.                            ;;
;; ----------------------------------------------------------------- ;;

(defn counting-store [inner counter]
  (reify p/EventStore
    (start [_] inner)
    (stop  [_] (es/stop inner))
    (tenants [_] (es/tenants inner))
    (append  [_ args] (p/append inner args))
    (read [_ args]
      (let [rdbl (es/read inner args)]
        (reify
          clojure.lang.IReduceInit
          (reduce [_ f init]
            (reduce (fn [acc x] (swap! counter inc) (f acc x)) init rdbl))
          clojure.lang.IReduce
          (reduce [_ f]
            (reduce (fn [acc x] (swap! counter inc) (f acc x)) (f) rdbl)))))))

(defn- seed-checkpoints!
  "Append n checkpoint events for proc-name directly to `store`, returning the
   ordered vector of :triggered-by ids (ascending, monotonic)."
  [store tenant-id n]
  (let [proc-uuid (tp-core/processor-name->uuid proc-name)
        trigger-ids (vec (repeatedly n uuid/v7))
        cps (mapv (fn [tid]
                    (->event {:type :grain/todo-processor-checkpoint
                              :tags #{[:processor proc-uuid]}
                              :body {:processor/name proc-name
                                     :triggered-by tid}}))
                  trigger-ids)]
    (es/append store {:tenant-id tenant-id :events cps})
    trigger-ids))

(deftest get-last-processed-id-consumes-one-row-regardless-of-n
  (testing "rows consumed is constant (== 1) for N=50 and N=500 — proves O(1)"
    (doseq [n [50 500]]
      (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
            tid (uuid/v4)]
        (try
          (let [trigger-ids (seed-checkpoints! store tid n)
                counter (atom 0)
                cstore (counting-store store counter)
                result (#'tp-core/get-last-processed-id cstore tid proc-name)]
            (is (= 1 @counter)
                (str "expected exactly 1 row consumed for N=" n ", got " @counter))
            (is (= (last trigger-ids) result)
                "returns the newest checkpoint's :triggered-by (the watermark)"))
          (finally (es/stop store)))))))

(deftest already-checkpointed?-consumes-one-row-and-is-correct
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        tid (uuid/v4)]
    (try
      (let [trigger-ids (seed-checkpoints! store tid 200)
            watermark (last trigger-ids)
            below (first trigger-ids)
            above (uuid/v7)                ; generated after seeding => greater
            counter (atom 0)
            cstore (counting-store store counter)]
        (testing "work count is O(1)"
          (#'tp-core/already-checkpointed? cstore tid proc-name below)
          (is (= 1 @counter) "one row consumed regardless of 200 checkpoints"))
        (testing "watermark semantics"
          (is (true?  (#'tp-core/already-checkpointed? cstore tid proc-name below))
              "id below the watermark is already checkpointed")
          (is (true?  (#'tp-core/already-checkpointed? cstore tid proc-name watermark))
              "id equal to the watermark is already checkpointed")
          (is (not (#'tp-core/already-checkpointed? cstore tid proc-name above))
              "id above the watermark is NOT yet checkpointed")))
      (finally (es/stop store)))))

(deftest already-checkpointed?-false-on-empty-history
  (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
        tid (uuid/v4)]
    (try
      (is (not (#'tp-core/already-checkpointed? store tid proc-name (uuid/v7)))
          "no checkpoints => nothing is checkpointed")
      (finally (es/stop store)))))

(deftest cas-still-rejects-duplicates-with-large-history
  (testing "latest-row CAS is equivalent to the old full scan: it still rejects a
            checkpoint at-or-before the watermark even with many prior checkpoints"
    (let [store (es/start {:conn {:type :in-memory} :event-pubsub nil :logger nil})
          tid (uuid/v4)]
      (try
        (let [trigger-ids (seed-checkpoints! store tid 300)
              watermark (last trigger-ids)
              ;; A fresh, higher id => forward progress, must succeed.
              forward-id (uuid/v7)
              ;; CAS conflict-threshold at/below the watermark => must conflict.
              dup-result (#'tp-core/append-with-checkpoint
                          store tid proc-name watermark [])
              forward-result (#'tp-core/append-with-checkpoint
                              store tid proc-name forward-id [])]
          (is (anomaly? dup-result) "re-checkpointing the watermark conflicts")
          (is (= ::anom/conflict (::anom/category dup-result))
              "conflict category preserved")
          (is (nil? forward-result)
              "a fresh forward checkpoint still succeeds (nil = no anomaly)"))
        (finally (es/stop store))))))
