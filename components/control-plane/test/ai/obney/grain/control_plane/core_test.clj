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

;; =====================================
;; DR1: No departure before drain
;; =====================================

(deftest dr1-departure-after-drain
  (testing "DR1: departure event is emitted only after in-flight work has drained"
    (let [dir (str "/tmp/cp-dr1-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
          tenant-1 (uuid/v4)
          effect-started (promise)
          effect-gate (promise)]
      (try
        (let [prev-registry @tp/processor-registry*]
          (try
            ;; Register a processor with a blocking effect — we control when it finishes
            (tp/register-processor!
             :test/dr1-slow-proc
             {:topics [:test/lifecycle-event]
              :handler-fn (fn [{:keys [event]}]
                            {:result/effect (fn []
                                              (deliver effect-started true)
                                              ;; Block until test releases the gate
                                              (deref effect-gate 10000 :timeout))
                             :result/checkpoint :after
                             :result/on-success []})})
            ;; Create tenant and event
            (es/append store {:tenant-id tenant-1
                              :events [(es/->event {:type :test/lifecycle-event :body {}})]})
            ;; Start control plane — will assign tenant and start processing
            (let [cp-instance (cp/start {:event-store store
                                         :cache cache
                                         :heartbeat-interval-ms 200
                                         :staleness-threshold-ms 1000})]
              ;; Wait for assignment + processing to begin
              (Thread/sleep 2000)
              ;; Wait for the effect to start (proves the processor picked up the event)
              (deref effect-started 5000 :timeout)
              ;; Now stop the control plane in a separate thread
              (let [stop-future (future (cp/stop cp-instance))]
                ;; Give stop a moment to begin draining
                (Thread/sleep 500)
                ;; Check: departure event should NOT exist yet (drain still in progress)
                (rmp/l1-clear!)
                (let [all-events (into []
                                   (remove #(= :grain/tx (:event/type %)))
                                   (es/read store {:tenant-id events/control-plane-tenant-id}))
                      departures (filter #(= :grain.control/node-departed (:event/type %)) all-events)]
                  (is (empty? departures)
                      "Departure event must not exist while drain is in progress"))
                ;; Release the gate — allow the effect to complete
                (deliver effect-gate :done)
                ;; Wait for stop to finish
                (deref stop-future 10000 :timeout)
                ;; Now departure should exist
                (rmp/l1-clear!)
                (let [all-events (into []
                                   (remove #(= :grain/tx (:event/type %)))
                                   (es/read store {:tenant-id events/control-plane-tenant-id}))
                      departures (filter #(= :grain.control/node-departed (:event/type %)) all-events)]
                  (is (= 1 (count departures))
                      "Departure event should exist after drain completes"))))
            (finally
              (reset! tp/processor-registry* prev-registry))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

;; =====================================
;; DR2: Heartbeat stops before drain
;; =====================================

(deftest dr2-heartbeat-stops-after-shutdown
  (testing "DR2: no new heartbeats appear after stop completes"
    (let [dir (str "/tmp/cp-dr2-test-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))]
      (try
        (let [cp-instance (cp/start {:event-store store
                                     :cache cache
                                     :heartbeat-interval-ms 200
                                     :staleness-threshold-ms 1000})]
          ;; Wait for several heartbeats
          (Thread/sleep 800)
          ;; Stop the control plane — heartbeat schedule closes first
          (cp/stop cp-instance)
          ;; Record heartbeat count immediately after stop
          (let [all-after-stop (into []
                                 (remove #(= :grain/tx (:event/type %)))
                                 (es/read store {:tenant-id events/control-plane-tenant-id}))
                hb-count-after-stop (count (filter #(= :grain.control/node-heartbeat (:event/type %))
                                                   all-after-stop))]
            ;; Wait long enough for 2+ heartbeat cycles to have fired if not stopped
            (Thread/sleep 600)
            ;; Count again — should be the same
            (let [all-later (into []
                              (remove #(= :grain/tx (:event/type %)))
                              (es/read store {:tenant-id events/control-plane-tenant-id}))
                  hb-count-later (count (filter #(= :grain.control/node-heartbeat (:event/type %))
                                                all-later))]
              (is (= hb-count-after-stop hb-count-later)
                  "No new heartbeats after stop completes"))))
        (finally
          (kv/stop cache)
          (es/stop store)
          (delete-dir-recursively dir))))))

;; =====================================
;; PT-CAS3: Periodic task deduplication
;; =====================================

(defschemas pt-cas3-schemas
  {:test/billing-trigger [:map [:period :string]]
   :test/billing-done [:map [:period :string]]})

(deftest pt-cas3-periodic-trigger-deduplication
  (testing "PT-CAS3: Two instances both run periodic trigger, CAS deduplicates, processor runs once"
    (let [dir-a (str "/tmp/cp-ptcas3-a-" (uuid/v4))
          dir-b (str "/tmp/cp-ptcas3-b-" (uuid/v4))
          store (es/start {:conn {:type :in-memory}})
          cache-a (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir-a :db-name "test"}))
          cache-b (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir-b :db-name "test"}))
          tenant-1 (uuid/v4)
          tenant-2 (uuid/v4)
          cycle-count (atom 0)]
      (try
        (let [prev-registry @tp/processor-registry*]
          (try
            ;; Register a billing processor
            (tp/register-processor! :test/billing-proc
              {:topics [:test/billing-trigger]
               :handler-fn (fn [{:keys [event]}]
                             {:result/events
                              [(es/->event {:type :test/billing-done
                                            :body {:period (:period event)}})]})})
            ;; Create tenants
            (es/append store {:tenant-id tenant-1
                              :events [(es/->event {:type :test/lifecycle-event :body {}})]})
            (es/append store {:tenant-id tenant-2
                              :events [(es/->event {:type :test/lifecycle-event :body {}})]})
            ;; Start two control plane instances
            (let [cp-a (cp/start {:event-store store :cache cache-a
                                  :heartbeat-interval-ms 200
                                  :staleness-threshold-ms 1000})
                  _ (Thread/sleep 100)
                  cp-b (cp/start {:event-store store :cache cache-b
                                  :heartbeat-interval-ms 200
                                  :staleness-threshold-ms 1000})]
              (try
                ;; Wait for assignment
                (Thread/sleep 2000)
                ;; Both "nodes" try to append billing triggers with CAS
                ;; Simulate 3 periodic cycles
                (dotimes [i 3]
                  (let [period (str "2026-03-23-cycle-" i)]
                    ;; Node A tries
                    (es/append store
                      {:tenant-id tenant-1
                       :events [(es/->event {:type :test/billing-trigger :body {:period period}})]
                       :cas {:types #{:test/billing-trigger}
                             :predicate-fn (fn [existing]
                                             (not (some #(= period (:period %))
                                                        (into [] existing))))}})
                    ;; Node B tries the same
                    (es/append store
                      {:tenant-id tenant-1
                       :events [(es/->event {:type :test/billing-trigger :body {:period period}})]
                       :cas {:types #{:test/billing-trigger}
                             :predicate-fn (fn [existing]
                                             (not (some #(= period (:period %))
                                                        (into [] existing))))}})
                    ;; Same for tenant-2
                    (es/append store
                      {:tenant-id tenant-2
                       :events [(es/->event {:type :test/billing-trigger :body {:period period}})]
                       :cas {:types #{:test/billing-trigger}
                             :predicate-fn (fn [existing]
                                             (not (some #(= period (:period %))
                                                        (into [] existing))))}})
                    (es/append store
                      {:tenant-id tenant-2
                       :events [(es/->event {:type :test/billing-trigger :body {:period period}})]
                       :cas {:types #{:test/billing-trigger}
                             :predicate-fn (fn [existing]
                                             (not (some #(= period (:period %))
                                                        (into [] existing))))}})))
                ;; Wait for processing
                (Thread/sleep 3000)
                ;; Verify: each tenant has exactly 3 triggers (one per cycle, CAS deduped)
                (doseq [tid [tenant-1 tenant-2]]
                  (let [all (into []
                              (remove #(= :grain/tx (:event/type %)))
                              (es/read store {:tenant-id tid}))
                        triggers (filter #(= :test/billing-trigger (:event/type %)) all)
                        results (filter #(= :test/billing-done (:event/type %)) all)]
                    (is (= 3 (count triggers))
                        (str "Tenant should have 3 triggers, got " (count triggers)))
                    (is (= 3 (count results))
                        (str "Tenant should have 3 billing results, got " (count results)))))
                (finally
                  (cp/stop cp-a)
                  (cp/stop cp-b))))
            (finally
              (reset! tp/processor-registry* prev-registry))))
        (finally
          (kv/stop cache-a)
          (kv/stop cache-b)
          (es/stop store)
          (delete-dir-recursively dir-a)
          (delete-dir-recursively dir-b))))))
