(ns ai.obney.grain.control-plane.core-test
  "Tests for the control plane start/stop lifecycle with periodic loops and reactor."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]
            [clojure.java.io :as io]))

(defschemas test-schemas
  {:test/lifecycle-event [:map]})

(defn- delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(deftest start-and-stop-lifecycle
  (testing "Control plane starts, emits heartbeats, and stops cleanly"
    (let [dir (str "/tmp/cp-lifecycle-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
      (try
        (let [cp-instance (cp/start {:event-store store
                                     :cache cache
                                                                          :heartbeat-interval-ms 200
                                     :staleness-threshold-ms 1000})]
          (try
            ;; Wait for a few heartbeat cycles
            (Thread/sleep 700)
            ;; Check that heartbeats were emitted
            (rmp/l1-clear!)
            (let [ctx {:event-store store :cache cache
                       :tenant-id events/control-plane-tenant-id}
                  nodes (rmp/project ctx :grain.control/active-nodes)]
              (is (= 1 (count nodes)))
              (is (contains? nodes (:node-id cp-instance))))
            (finally
              (cp/stop cp-instance)))
          ;; After stop, departure event should exist
          (rmp/l1-clear!)
          (let [ctx {:event-store store :cache cache
                     :tenant-id events/control-plane-tenant-id}
                nodes (rmp/project ctx :grain.control/active-nodes)]
            (is (= 0 (count nodes)))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

(deftest coordinator-assigns-work-automatically
  (testing "Control plane coordinator automatically assigns tenant-processor pairs"
    (let [dir (str "/tmp/cp-coord-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
          tenant-1 (uuid/v4)]
      (try
        ;; Create a domain tenant
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/lifecycle-event :body {}})]})
        (let [cp-instance (cp/start {:event-store store
                                     :cache cache
                                                                          :heartbeat-interval-ms 200
                                     :staleness-threshold-ms 1000})]
          (try
            ;; Wait for heartbeat + coordinator cycle
            (Thread/sleep 700)
            ;; Check that leases were assigned
            (rmp/l1-clear!)
            (let [ctx {:event-store store :cache cache
                       :tenant-id events/control-plane-tenant-id}
                  leases (rmp/project ctx :grain.control/lease-ownership)]
              (is (= 1 (count leases)))
              (is (= (:node-id cp-instance)
                     (get leases tenant-1))))
            (finally
              (cp/stop cp-instance))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

;; =====================================
;; Reactor: start/stop processors
;; =====================================

(deftest reactor-starts-processors-for-assigned-leases
  (testing "Control plane reactor starts a todo processor when a lease is assigned"
    (let [dir (str "/tmp/cp-reactor-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
          tenant-1 (uuid/v4)
          processed (atom [])]
      (try
        (let [prev-registry @tp/processor-registry*]
          (try
            (tp/register-processor!
             :test/reactor-proc
             {:topics [:test/lifecycle-event]
              :handler-fn (fn [{:keys [event]}]
                            (swap! processed conj (:event/id event))
                            {})})
            ;; Create a domain tenant with events
            (es/append store {:tenant-id tenant-1
                              :events [(es/->event {:type :test/lifecycle-event :body {}})]})
            ;; Start the control plane — poller will pick up the event
            (let [cp-instance (cp/start {:event-store store
                                         :cache cache
                                         :heartbeat-interval-ms 200
                                         :staleness-threshold-ms 1000})]
              (try
                ;; Wait for heartbeat + assignment + poller to process
                (Thread/sleep 2000)
                ;; Append another event — poller should process it
                (es/append store {:tenant-id tenant-1
                                  :events [(es/->event {:type :test/lifecycle-event :body {}})]})
                (Thread/sleep 1000)
                (is (pos? (count @processed))
                    "Reactor-started poller should process events")
                (finally
                  (cp/stop cp-instance))))
            (finally
              (reset! tp/processor-registry* prev-registry))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

(deftest reactor-stops-processors-on-shutdown
  (testing "Control plane reactor stops todo processors when the control plane stops"
    (let [dir (str "/tmp/cp-reactor-stop-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
          tenant-1 (uuid/v4)]
      (try
        (let [prev-registry @tp/processor-registry*]
          (try
            (tp/register-processor!
             :test/stop-proc
             {:topics [:test/lifecycle-event]
              :handler-fn (fn [_] {})})
            (es/append store {:tenant-id tenant-1
                              :events [(es/->event {:type :test/lifecycle-event :body {}})]})
            (let [cp-instance (cp/start {:event-store store
                                         :cache cache
                                         :heartbeat-interval-ms 200
                                         :staleness-threshold-ms 1000})]
              ;; Wait for reactor to start poller
              (Thread/sleep 2000)
              ;; Verify tenants are being processed
              (is (pos? (count (or (cp/running-processors cp-instance) #{})))
                  "Should have running processors before stop")
              ;; Stop the control plane
              (cp/stop cp-instance)
              ;; Verify poller was stopped
              (is (nil? (cp/running-processors cp-instance))
                  "Should have no running processors after stop"))
            (finally
              (reset! tp/processor-registry* prev-registry))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))
