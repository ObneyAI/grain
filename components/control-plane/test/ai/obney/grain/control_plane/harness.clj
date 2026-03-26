(ns ai.obney.grain.control-plane.harness
  "Two-instance simulation harness for control plane property tests.
   Shared in-memory event store + notification bus simulates
   two JVMs sharing Postgres with LISTEN/NOTIFY."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clojure.java.io :as io]))

(defn delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(defn make-instance
  "Creates a simulated Grain instance with its own cache and context,
   sharing the given event store."
  [shared-store node-id]
  (let [dir (str "/tmp/cp-harness-" node-id)
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    {:node-id node-id
     :event-store shared-store
     :cache cache
     :cache-dir dir
     :ctx {:event-store shared-store
           :cache cache
           :tenant-id events/control-plane-tenant-id}}))

(defn stop-instance [{:keys [cache cache-dir]}]
  (kv/stop cache)
  (delete-dir-recursively cache-dir))

(defn emit-heartbeat!
  ([instance] (emit-heartbeat! instance {}))
  ([{:keys [ctx node-id]} metadata]
   (cp/emit-heartbeat! ctx node-id metadata)))

(defn emit-departed! [{:keys [ctx node-id]}]
  (cp/emit-node-departed! ctx node-id))

(defn project-active-nodes
  [{:keys [ctx]} staleness-threshold-ms]
  (cp/project-active-nodes ctx staleness-threshold-ms))

(defn project-lease-ownership [{:keys [ctx]}]
  (cp/project-lease-ownership ctx))

(defn run-assignment!
  [{:keys [ctx node-id]} staleness-threshold-ms strategy]
  (cp/run-assignment! ctx node-id staleness-threshold-ms strategy))

(defn is-coordinator?
  "Returns true if this instance is the coordinator."
  [instance staleness-threshold-ms]
  (let [active (project-active-nodes instance staleness-threshold-ms)
        coord (ai.obney.grain.control-plane.assignment/coordinator active)]
    (= coord (:node-id instance))))
