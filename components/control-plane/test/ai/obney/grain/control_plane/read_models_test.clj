(ns ai.obney.grain.control-plane.read-models-test
  "Tests for control plane read models: active-nodes and lease-ownership.
   Verifies CP3 (coordinator convergence from projected state) and CP8 (tenant isolation)."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.read-models]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [clj-uuid :as uuid]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.java.io :as io]))

(defschemas test-schemas
  {:domain/something [:map]})

;; -------------------- ;;
;; Helpers              ;;
;; -------------------- ;;

(defn- delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(def ^:dynamic *ctx* nil)
(def ^:dynamic *cache-dir* nil)

(defn test-fixture [f]
  (let [dir   (str "/tmp/cp-read-model-test-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
    (binding [*ctx* {:event-store store
                     :cache cache
                     :tenant-id events/control-plane-tenant-id}
              *cache-dir* dir]
      (try
        (rmp/l1-clear!)
        (f)
        (finally
          (rmp/l1-clear!)
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

(use-fixtures :each test-fixture)

(defn append-control-events! [events]
  (es/append (:event-store *ctx*)
    {:tenant-id events/control-plane-tenant-id
     :events events}))

(defn project-active-nodes []
  (rmp/project *ctx* :grain.control/active-nodes))

(defn project-lease-ownership []
  (rmp/project *ctx* :grain.control/lease-ownership))

;; =====================================
;; Active Nodes Read Model
;; =====================================

(deftest active-nodes-empty-initially
  (is (= {} (project-active-nodes))))

(deftest active-nodes-tracks-heartbeats
  (let [node-a (uuid/v7)
        node-b (uuid/v7)]
    (append-control-events! [(events/->heartbeat node-a {:hostname "a"})
                             (events/->heartbeat node-b {:hostname "b"})])
    (let [nodes (project-active-nodes)]
      (is (= 2 (count nodes)))
      (is (contains? nodes node-a))
      (is (contains? nodes node-b))
      (is (= {:hostname "a"} (:metadata (get nodes node-a))))
      (is (= {:hostname "b"} (:metadata (get nodes node-b)))))))

(deftest active-nodes-departure-removes-node
  (let [node-a (uuid/v7)]
    (append-control-events! [(events/->heartbeat node-a {})])
    (is (= 1 (count (project-active-nodes))))
    (append-control-events! [(events/->node-departed node-a)])
    (is (= 0 (count (project-active-nodes))))))

(deftest active-nodes-latest-heartbeat-wins
  (let [node-a (uuid/v7)]
    (append-control-events! [(events/->heartbeat node-a {:v 1})])
    (append-control-events! [(events/->heartbeat node-a {:v 2})])
    (let [nodes (project-active-nodes)]
      (is (= 1 (count nodes)))
      (is (= {:v 2} (:metadata (get nodes node-a)))))))

;; =====================================
;; CP3: Coordinator from projected state
;; =====================================

(deftest cp3-coordinator-from-projected-active-nodes
  (let [node-a (uuid/v7)
        _ (Thread/sleep 1)
        node-b (uuid/v7)]
    (append-control-events! [(events/->heartbeat node-a {})
                             (events/->heartbeat node-b {})])
    (let [nodes (project-active-nodes)
          coord (assignment/coordinator nodes)]
      ;; Coordinator should be the smaller UUID (node-a, created first)
      (is (= node-a coord)))))

;; =====================================
;; Lease Ownership Read Model
;; =====================================

(deftest lease-ownership-empty-initially
  (is (= {} (project-lease-ownership))))

(deftest lease-ownership-tracks-acquisitions
  (let [node-a (uuid/v7)
        tenant-1 (uuid/v4)
        tenant-2 (uuid/v4)]
    (append-control-events! [(events/->lease-acquired node-a tenant-1)
                             (events/->lease-acquired node-a tenant-2)])
    (let [leases (project-lease-ownership)]
      (is (= 2 (count leases)))
      (is (= node-a (get leases tenant-1)))
      (is (= node-a (get leases tenant-2))))))

(deftest lease-ownership-release-removes-lease
  (let [node-a (uuid/v7)
        tenant-1 (uuid/v4)]
    (append-control-events! [(events/->lease-acquired node-a tenant-1)])
    (is (= 1 (count (project-lease-ownership))))
    (append-control-events! [(events/->lease-released node-a tenant-1)])
    (is (= 0 (count (project-lease-ownership))))))

(deftest lease-ownership-transfer
  (let [node-a (uuid/v7)
        node-b (uuid/v7)
        tenant-1 (uuid/v4)]
    (append-control-events! [(events/->lease-acquired node-a tenant-1)])
    (is (= node-a (get (project-lease-ownership) tenant-1)))
    ;; Release from A, then acquire by B (separate appends to guarantee order)
    (append-control-events! [(events/->lease-released node-a tenant-1)])
    (append-control-events! [(events/->lease-acquired node-b tenant-1)])
    (is (= node-b (get (project-lease-ownership) tenant-1)))))

;; =====================================
;; CP8: Tenant Isolation
;; =====================================

(deftest cp8-control-plane-events-do-not-leak-to-domain-tenants
  (let [domain-tenant (uuid/v4)
        node-a (uuid/v7)]
    ;; Append control plane events
    (append-control-events! [(events/->heartbeat node-a {})])
    ;; Append domain events to a different tenant
    (es/append (:event-store *ctx*)
      {:tenant-id domain-tenant
       :events [(es/->event {:type :domain/something :body {:x 1}})]})
    ;; Domain tenant should not see control plane events
    (let [domain-events (into [] (es/read (:event-store *ctx*)
                                   {:tenant-id domain-tenant}))]
      (is (every? #(not= :grain.control/node-heartbeat (:event/type %))
                  domain-events)))
    ;; Control plane tenant should not see domain events
    (let [cp-events (into [] (es/read (:event-store *ctx*)
                                {:tenant-id events/control-plane-tenant-id}))]
      (is (every? #(not= :domain/something (:event/type %))
                  cp-events)))))
