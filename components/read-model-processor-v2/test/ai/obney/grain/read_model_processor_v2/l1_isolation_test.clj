(ns ai.obney.grain.read-model-processor-v2.l1-isolation-test
  "L1 entries belong to one (event store, cache) pair. Two contexts in one JVM
   with the same tenant, model, version and scope must each read their own
   event store, and L1 must not keep stopped stores or caches reachable."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.interface.testing :as cache-testing]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]])
  (:import [java.lang.ref WeakReference]))

(defschemas l1-isolation-schemas
  {:l1-isolation/counted [:map]
   :l1-isolation/placed [:map [:item-id :uuid] [:bucket :string]]})

(defn- delete-dir! [dir]
  (doseq [file (reverse (file-seq (io/file dir)))]
    (io/delete-file file true)))

(defn- start-cache [dir]
  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "l1-isolation"})))

(defn- with-stores
  "Calls f with n contexts that share one tenant id. Each context gets its own
   cache, and its own event store unless :shared-store."
  [{:keys [n shared-store]} f]
  (let [tenant-id (random-uuid)
        dirs (vec (repeatedly n #(str "/tmp/rmp-l1-isolation-" (random-uuid))))
        stores (if shared-store
                 (vec (repeat n (es/start {:conn {:type :in-memory}})))
                 (vec (repeatedly n #(es/start {:conn {:type :in-memory}}))))
        caches (mapv start-cache dirs)]
    (try
      (rmp/l1-clear!)
      (f (mapv (fn [store cache] {:event-store store :cache cache :tenant-id tenant-id}) stores caches))
      (finally
        (rmp/l1-clear!)
        (run! kv/stop caches)
        (run! es/stop (distinct stores))
        (run! delete-dir! dirs)))))

(defn- append-counted! [{:keys [event-store tenant-id]} n]
  (es/append event-store {:tenant-id tenant-id
                          :events (mapv (fn [_] (es/->event {:type :l1-isolation/counted})) (range n))}))

(defn- count-reducer [state _event]
  (update state :n (fnil inc 0)))

(defn- counter-args
  ([] (counter-args {}))
  ([overrides]
   (merge {:f count-reducer
           :query {:types #{:l1-isolation/counted}}
           :name :l1-isolation/counter
           :version 1
           :l1-ttl-ms 0}
          overrides)))

(rmp/defreadmodel :l1-isolation counter
  {:events #{:l1-isolation/counted}}
  [state event]
  (count-reducer state event))

(deftest separate-event-stores-with-one-tenant-read-their-own-events
  (with-stores {:n 2}
    (fn [[a b]]
      (append-counted! a 3)
      (append-counted! b 1)
      (testing "the audit D5 reproduction through project"
        (is (= {:n 3} (rmp/project a :l1-isolation/counter)))
        (is (= {:n 1} (rmp/project b :l1-isolation/counter)))
        (is (= {:n 3} (rmp/project a :l1-isolation/counter))))
      (testing "warm entries keep following their own store"
        (append-counted! b 2)
        (is (= {:n 3} (rmp/project b :l1-isolation/counter)))
        (is (= {:n 3} (rmp/project a :l1-isolation/counter)))
        (append-counted! a 1)
        (is (= {:n 4} (rmp/project a :l1-isolation/counter)))
        (is (= {:n 3} (rmp/project b :l1-isolation/counter))))
      (testing "each context holds its own L1 entry"
        (is (= 2 (:entries (rmp/l1-stats))))))))

(deftest a-ttl-hit-never-returns-another-stores-state
  (with-stores {:n 2}
    (fn [[a b]]
      (append-counted! a 5)
      (append-counted! b 2)
      (let [args (counter-args {:l1-ttl-ms 60000})]
        (is (= {:n 5} (rmp/p a args)))
        (is (= {:n 2} (rmp/p b args)))
        (is (= {:n 5} (rmp/p a args)))
        (is (= {:n 2} (rmp/p b args)))))))

(deftest one-event-store-read-through-two-caches
  (with-stores {:n 2 :shared-store true}
    (fn [[a b]]
      (append-counted! a 4)
      (is (= {:n 4} (rmp/p a (counter-args))))
      (is (= {:n 4} (rmp/p b (counter-args))))
      (is (= 2 (:entries (rmp/l1-stats))) "one entry per cache")
      (append-counted! a 1)
      (is (= {:n 5} (rmp/p b (counter-args))))
      (is (= {:n 5} (rmp/p a (counter-args)))))))

(defn- place! [{:keys [event-store tenant-id]} n bucket]
  (es/append event-store {:tenant-id tenant-id
                          :events (mapv (fn [_] (es/->event {:type :l1-isolation/placed
                                                             :body {:item-id (random-uuid) :bucket bucket}}))
                                        (range n))}))

(defn- partitioned-args [overrides]
  (merge {:f (fn [state event] (assoc state (:item-id event) {:bucket (:bucket event)}))
          :query {:types #{:l1-isolation/placed}}
          :name "l1-isolation-items"
          :version 1
          :partition-fn :bucket
          :entity-id-fn :item-id
          :l1-ttl-ms 0}
         overrides))

(deftest single-partition-reads-are-isolated-per-event-store
  (with-stores {:n 2}
    (fn [[a b]]
      (place! a 3 "x")
      (place! b 1 "x")
      (doseq [ctx [a b]]
        (rmp/p ctx (partitioned-args {})))
      (let [args (partitioned-args {:partition-key "x"})]
        (is (= 3 (count (rmp/p a args))))
        (is (= 1 (count (rmp/p b args))))
        (place! b 2 "x")
        (is (= 3 (count (rmp/p a args))))
        (is (= 3 (count (rmp/p b args))))))))

(deftest cache-key-inspection-and-eviction-cover-every-context
  (with-stores {:n 2}
    (fn [[a b]]
      (append-counted! a 3)
      (append-counted! b 1)
      (let [key (String. ^bytes (cache-testing/format-scoped-key :l1-isolation/counter 1 (:tenant-id a)))]
        (rmp/p a (counter-args))
        (Thread/sleep 2)
        (rmp/p b (counter-args))
        (testing "l1-entry returns the most recently used entry for the cache key"
          (is (= {:n 1} (:state (cache-testing/l1-entry key))))
          (Thread/sleep 2)
          (rmp/p a (counter-args))
          (is (= {:n 3} (:state (cache-testing/l1-entry key)))))
        (testing "evict-l1! removes the cache key for every context"
          (cache-testing/evict-l1! key)
          (is (nil? (cache-testing/l1-entry key)))
          (is (= 0 (:entries (rmp/l1-stats))))
          (is (= {:n 3} (rmp/p a (counter-args))))
          (is (= {:n 1} (rmp/p b (counter-args)))))
        (testing "l1-clear! removes every context's entries"
          (rmp/l1-clear!)
          (is (= 0 (:entries (rmp/l1-stats)))))))))

(deftest max-entries-bounds-cache-keys-least-recently-used-first
  (with-stores {:n 1}
    (fn [[ctx]]
      (append-counted! ctx 2)
      (doseq [model [:l1-isolation/first :l1-isolation/second :l1-isolation/third]]
        (rmp/p ctx (counter-args {:name model :l1-max-entries 2}))
        (Thread/sleep 2))
      (is (= 2 (:entries (rmp/l1-stats))))
      (let [key-of #(String. ^bytes (cache-testing/format-scoped-key % 1 (:tenant-id ctx)))]
        (is (nil? (cache-testing/l1-entry (key-of :l1-isolation/first))))
        (is (= {:n 2} (:state (cache-testing/l1-entry (key-of :l1-isolation/third)))))))))

(deftest live-contexts-sharing-a-cache-key-do-not-evict-each-other
  (with-stores {:n 3}
    (fn [contexts]
      (doseq [[i ctx] (map-indexed vector contexts)]
        (append-counted! ctx (inc i)))
      (is (= [{:n 1} {:n 2} {:n 3}]
             (mapv #(rmp/p % (counter-args {:l1-ttl-ms 60000 :l1-max-entries 1})) contexts)))
      (is (= 3 (:entries (rmp/l1-stats))))
      (is (= [{:n 1} {:n 2} {:n 3}]
             (mapv #(rmp/p % (counter-args {:l1-ttl-ms 60000 :l1-max-entries 1})) contexts))))))

(defn- collected? [^WeakReference ref]
  (loop [attempt 0]
    (cond
      (nil? (.get ref)) true
      (= attempt 50) false
      :else (do (System/gc)
                (byte-array (* 4 1024 1024))
                (Thread/sleep 20)
                (recur (inc attempt))))))

(deftest l1-does-not-keep-stopped-stores-or-caches-reachable
  (let [dir (str "/tmp/rmp-l1-isolation-" (random-uuid))
        tenant-id (random-uuid)
        refs (let [ctx {:event-store (es/start {:conn {:type :in-memory}})
                        :cache (start-cache dir)
                        :tenant-id tenant-id}]
               (append-counted! ctx 2)
               (is (= {:n 2} (rmp/p ctx (counter-args))))
               (kv/stop (:cache ctx))
               (es/stop (:event-store ctx))
               [(WeakReference. (:event-store ctx)) (WeakReference. (:cache ctx))])]
    (try
      (is (pos? (:entries (rmp/l1-stats))) "the entry is still held")
      (is (collected? (first refs)) "event store collected while its L1 entry remains")
      (is (collected? (second refs)) "cache collected while its L1 entry remains")
      (finally
        (rmp/l1-clear!)
        (delete-dir! dir)))))

(deftest replacing-an-owner-drops-the-collected-owners-entry
  (let [dir (str "/tmp/rmp-l1-isolation-" (random-uuid))
        tenant-id (random-uuid)
        store (es/start {:conn {:type :in-memory}})
        key (String. ^bytes (cache-testing/format-scoped-key :l1-isolation/counter 1 tenant-id))
        project-with-new-cache! (fn []
                                  (let [cache (start-cache dir)]
                                    (try
                                      (is (= {:n 3} (rmp/p {:event-store store :cache cache :tenant-id tenant-id}
                                                           (counter-args))))
                                      (finally (kv/stop cache)))
                                    (WeakReference. cache)))]
    (try
      (rmp/l1-clear!)
      (append-counted! {:event-store store :tenant-id tenant-id} 3)
      (dotimes [_ 5]
        (is (collected? (project-with-new-cache!)) "the replaced cache is collectable"))
      (project-with-new-cache!)
      (is (= 1 (:entries (rmp/l1-stats))) "only the newest owner's entry remains for the key")
      (is (= {:n 3} (:state (cache-testing/l1-entry key))))
      (finally
        (rmp/l1-clear!)
        (es/stop store)
        (delete-dir! dir)))))
