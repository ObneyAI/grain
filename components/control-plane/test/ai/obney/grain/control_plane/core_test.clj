(ns ai.obney.grain.control-plane.core-test
  "Tests for the control plane start/stop lifecycle with periodic loops and reactor."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.control-plane.harness :as harness]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [chime.core :as chime]
            [clj-uuid :as uuid]
            [clojure.java.io :as io]))

(defschemas test-schemas
  {:test/lifecycle-event [:map]})

(defn- control-plane-events
  [store]
  (into []
        (remove #(= :grain/tx (:event/type %)))
        (es/read store {:tenant-id events/control-plane-tenant-id})))

(defn- delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(deftest positive-reassignment-interval-delays-handover-until-the-exact-boundary
  (let [dir (str "/tmp/cp-reassignment-delay-test-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        tenant-id (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        interval-ms 5000
        now-ms (atom 0)
        ctx {:event-store store
             :cache cache
             :tenant-id events/control-plane-tenant-id}
        assignment-options {:reassignment-interval-ms interval-ms
                            :clock-ms-fn #(deref now-ms)}]
    (try
      (rmp/l1-clear!)
      (es/append store
        {:tenant-id tenant-id
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx node-a {})
      (cp/run-assignment! ctx node-a 15000 :round-robin assignment-options)
      (rmp/l1-clear!)
      (is (= {tenant-id node-a} (cp/project-lease-ownership ctx))
          "a tenant with no release history is assigned immediately")

      (cp/emit-heartbeat! ctx node-b {})
      (cp/emit-node-departed! ctx node-a)
      (cp/run-assignment! ctx node-b 15000 :round-robin assignment-options)
      (rmp/l1-clear!)
      (is (= {} (cp/project-lease-ownership ctx))
          "a positive interval splits release from acquisition")
      (let [release-history (rmp/project ctx :grain.control/lease-release-history)
            released-at-ms (get release-history tenant-id)
            lease-events (filter #(contains? #{:grain.control/lease-acquired
                                               :grain.control/lease-released}
                                             (:event/type %))
                                 (control-plane-events store))]
        (is (int? released-at-ms)
            "the release time is reconstructed from durable control-plane history")
        (is (= [[:grain.control/lease-acquired node-a]
                [:grain.control/lease-released node-a]]
               (mapv (juxt :event/type :lease/node-id) lease-events))
            "raw events contain a release without a same-cycle acquisition")

        (reset! now-ms (+ released-at-ms interval-ms -1))
        (cp/run-assignment! ctx node-b 15000 :round-robin assignment-options)
        (rmp/l1-clear!)
        (is (= {} (cp/project-lease-ownership ctx))
            "the tenant remains unowned one millisecond before the boundary")

        (reset! now-ms (+ released-at-ms interval-ms))
        (cp/run-assignment! ctx node-b 15000 :round-robin assignment-options)
        (rmp/l1-clear!)
        (is (= {tenant-id node-b} (cp/project-lease-ownership ctx))
            "the desired owner acquires exactly at the boundary")
        (is (= [[:grain.control/lease-acquired node-a]
                [:grain.control/lease-released node-a]
                [:grain.control/lease-acquired node-b]]
               (->> (control-plane-events store)
                    (filter #(contains? #{:grain.control/lease-acquired
                                          :grain.control/lease-released}
                                        (:event/type %)))
                    (mapv (juxt :event/type :lease/node-id))))
            "the boundary cycle appends the delayed acquisition"))
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest reassignment-delay-survives-fresh-process-cache-state
  (let [dir-a (str "/tmp/cp-reassignment-restart-a-" (uuid/v4))
        dir-b (str "/tmp/cp-reassignment-restart-b-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache-a (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir-a :db-name "test"}))
        cache-a-stopped? (atom false)
        tenant-id (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        interval-ms 5000
        now-ms (atom 0)
        options {:reassignment-interval-ms interval-ms
                 :clock-ms-fn #(deref now-ms)}
        ctx-a {:event-store store
               :cache cache-a
               :tenant-id events/control-plane-tenant-id}]
    (try
      (rmp/l1-clear!)
      (es/append store
        {:tenant-id tenant-id
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx-a node-a {})
      (cp/run-assignment! ctx-a node-a 15000 :round-robin options)
      (cp/emit-heartbeat! ctx-a node-b {})
      (cp/emit-node-departed! ctx-a node-a)
      (cp/run-assignment! ctx-a node-b 15000 :round-robin options)
      (rmp/l1-clear!)
      (let [released-at-ms (get (cp/project-lease-release-history ctx-a) tenant-id)]
        (kv/stop cache-a)
        (reset! cache-a-stopped? true)
        (delete-dir-recursively dir-a)
        (rmp/l1-clear!)
        (let [cache-b (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir-b :db-name "test"}))
              ctx-b {:event-store store
                     :cache cache-b
                     :tenant-id events/control-plane-tenant-id}]
          (try
            (reset! now-ms (+ released-at-ms interval-ms -1))
            (cp/run-assignment! ctx-b node-b 15000 :round-robin options)
            (rmp/l1-clear!)
            (is (= released-at-ms
                   (get (cp/project-lease-release-history ctx-b) tenant-id))
                "fresh cache state rebuilds the same durable release time")
            (is (= {} (cp/project-lease-ownership ctx-b))
                "a restart does not erase the remaining delay")

            (reset! now-ms (+ released-at-ms interval-ms))
            (cp/run-assignment! ctx-b node-b 15000 :round-robin options)
            (rmp/l1-clear!)
            (is (= {tenant-id node-b} (cp/project-lease-ownership ctx-b))
                "the restarted coordinator acquires at the original boundary")
            (finally
              (kv/stop cache-b)))))
      (finally
        (rmp/l1-clear!)
        (when-not @cache-a-stopped?
          (kv/stop cache-a))
        (es/stop store)
        (delete-dir-recursively dir-a)
        (delete-dir-recursively dir-b)))))

(deftest start-rejects-a-negative-reassignment-interval-before-emitting-events
  (let [dir (str "/tmp/cp-negative-reassignment-start-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        started (atom nil)]
    (try
      (rmp/l1-clear!)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"reassignment interval must be a nonnegative integer"
            (reset! started
                    (cp/start {:event-store store
                               :cache cache
                               :heartbeat-interval-ms 10000
                               :reassignment-interval-ms -1}))))
      (is (empty? (control-plane-events store))
          "invalid configuration fails before the initial heartbeat")
      (finally
        (when-let [instance @started]
          (cp/stop instance))
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest assignment-cycle-rejects-a-negative-reassignment-interval
  (let [dir (str "/tmp/cp-negative-reassignment-cycle-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        ctx {:event-store store
             :cache cache
             :tenant-id events/control-plane-tenant-id}]
    (try
      (rmp/l1-clear!)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"reassignment interval must be a nonnegative integer"
            (cp/run-assignment! ctx (uuid/v7) 15000 :round-robin
                                {:reassignment-interval-ms -1})))
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest zero-reassignment-interval-transfers-in-one-cycle-without-reading-the-clock
  (let [dir (str "/tmp/cp-zero-reassignment-cycle-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        tenant-id (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        ctx {:event-store store
             :cache cache
             :tenant-id events/control-plane-tenant-id}]
    (try
      (rmp/l1-clear!)
      (es/append store
        {:tenant-id tenant-id
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx node-a {})
      (cp/run-assignment! ctx node-a 15000 :round-robin)
      (cp/emit-heartbeat! ctx node-b {})
      (cp/emit-node-departed! ctx node-a)
      (cp/run-assignment!
        ctx node-b 15000 :round-robin
        {:reassignment-interval-ms 0
         :clock-ms-fn #(throw (ex-info "zero-delay clock must not run" {}))})
      (rmp/l1-clear!)
      (is (= {tenant-id node-b} (cp/project-lease-ownership ctx))
          "zero delay preserves same-cycle release and acquisition")
      (is (= [[:grain.control/lease-acquired node-a]
              [:grain.control/lease-released node-a]
              [:grain.control/lease-acquired node-b]]
             (->> (control-plane-events store)
                  (filter #(contains? #{:grain.control/lease-acquired
                                        :grain.control/lease-released}
                                      (:event/type %)))
                  (mapv (juxt :event/type :lease/node-id))))
          "the compatibility path retains the existing lease-event order")
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest a-delayed-handover-does-not-block-a-never-owned-tenant
  (let [dir (str "/tmp/cp-independent-reassignment-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        handover-tenant (uuid/v4)
        new-tenant (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        ctx {:event-store store
             :cache cache
             :tenant-id events/control-plane-tenant-id}
        options {:reassignment-interval-ms 5000
                 :clock-ms-fn (constantly 0)}]
    (try
      (rmp/l1-clear!)
      (es/append store
        {:tenant-id handover-tenant
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx node-a {})
      (cp/run-assignment! ctx node-a 15000 :round-robin options)

      (es/append store
        {:tenant-id new-tenant
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx node-b {})
      (cp/emit-node-departed! ctx node-a)
      (cp/run-assignment! ctx node-b 15000 :round-robin options)
      (rmp/l1-clear!)
      (is (= {new-tenant node-b} (cp/project-lease-ownership ctx))
          "the handover tenant waits while independent new work is assigned")
      (is (= #{handover-tenant}
             (set (keys (cp/project-lease-release-history ctx))))
          "only the previously owned tenant has release history")
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest start-threads-the-reassignment-interval-through-coordinator-cycles
  (let [dir (str "/tmp/cp-start-reassignment-" (uuid/v4))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"}))
        tenant-id (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        interval-ms 5000
        now-ms (atom 0)
        scheduled-handlers (atom [])
        closed-schedules (atom 0)
        ctx {:event-store store
             :cache cache
             :tenant-id events/control-plane-tenant-id}]
    (try
      (rmp/l1-clear!)
      (es/append store
        {:tenant-id tenant-id
         :events [(es/->event {:type :test/lifecycle-event :body {}})]})
      (cp/emit-heartbeat! ctx node-a {})
      (cp/run-assignment! ctx node-a 15000 :round-robin)
      (cp/emit-node-departed! ctx node-a)
      (with-redefs [chime/chime-at
                    (fn [_times handler]
                      (swap! scheduled-handlers conj handler)
                      (reify java.io.Closeable
                        (close [_] (swap! closed-schedules inc))))
                    tp/start-tenant-poller
                    (fn [{:keys [tenant-ids]}]
                      {:tenant-ids-atom tenant-ids})
                    tp/stop-tenant-poller (constantly nil)]
        (let [instance (cp/start {:event-store store
                                  :cache cache
                                  :node-id node-b
                                  :heartbeat-interval-ms 1000
                                  :staleness-threshold-ms 15000
                                  :reassignment-interval-ms interval-ms
                                  :clock-ms-fn #(deref now-ms)})
              coordinator-tick (second @scheduled-handlers)]
          (is (= 2 (count @scheduled-handlers))
              "start schedules heartbeat and coordinator callbacks")
          (coordinator-tick nil)
          (rmp/l1-clear!)
          (is (= {} (cp/project-lease-ownership (:ctx instance)))
              "the configured positive interval releases without reacquiring")
          (let [released-at-ms (get (cp/project-lease-release-history (:ctx instance))
                                    tenant-id)]
            (reset! now-ms (+ released-at-ms interval-ms -1))
            (coordinator-tick nil)
            (rmp/l1-clear!)
            (is (= {} (cp/project-lease-ownership (:ctx instance)))
                "the start-configured coordinator still waits before the boundary")

            (reset! now-ms (+ released-at-ms interval-ms))
            (coordinator-tick nil)
            (rmp/l1-clear!)
            (is (= {tenant-id node-b}
                   (cp/project-lease-ownership (:ctx instance)))
                "the start-configured coordinator acquires at the boundary"))
          (cp/stop instance)
          (is (= 2 @closed-schedules))))
      (finally
        (rmp/l1-clear!)
        (kv/stop cache)
        (es/stop store)
        (delete-dir-recursively dir)))))

(deftest control-plane-events-are-defined
  (doseq [event-type [:grain.control/node-heartbeat
                      :grain.control/node-departed
                      :grain.control/lease-acquired
                      :grain.control/lease-released]]
    (let [definition (es/event-definition event-type)]
      (is (= event-type (:event/type definition)))
      (is (string? (:description definition)))
      (is (some? (:schema definition)))))
  (is (= {:retain-at-least {:seconds 3600 :nanos 0}
          :keep-latest-per {:tags #{:node}}}
         (:history/normalized
          (es/event-definition :grain.control/node-heartbeat))))
  (doseq [event-type [:grain.control/node-departed
                      :grain.control/lease-acquired
                      :grain.control/lease-released]]
    (is (nil? (:history (es/event-definition event-type))))))

(deftest reactor-supplies-a-live-lease-predicate-to-the-tenant-poller
  (let [tenant-id (uuid/v4)
        node-a (uuid/v4)
        node-b (uuid/v4)
        leases (atom {tenant-id node-a})
        captured (atom nil)
        poller-atom (atom nil)
        context {::cp/app-context {:service :test}
                 :event-store ::store}]
    (with-redefs [cp/project-lease-ownership (fn [_] @leases)
                  tp/start-tenant-poller
                  (fn [config]
                    (reset! captured config)
                    {:running (atom true)})]
      (#'cp/reconcile-tenants! context node-a poller-atom)
      (let [lease-check (:lease-check-fn @captured)]
        (is (fn? lease-check) (pr-str @captured))
        (is (true? (lease-check tenant-id :test/processor)))
        (reset! leases {tenant-id node-b})
        (is (false? (lease-check tenant-id :test/processor))
            "the predicate reprojects ownership instead of capturing startup state")))))

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
            (let [ctx {:event-store store :cache cache
                       :tenant-id events/control-plane-tenant-id}]
              (harness/wait-for
               #(do (rmp/l1-clear!)
                    (= 1 (count (rmp/project ctx :grain.control/active-nodes)))))
              (let [nodes (rmp/project ctx :grain.control/active-nodes)]
                (is (= 1 (count nodes)))
                (is (contains? nodes (:node-id cp-instance)))))
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
            (let [ctx {:event-store store :cache cache
                       :tenant-id events/control-plane-tenant-id}]
              (harness/wait-for
               #(do (rmp/l1-clear!)
                    (= 1 (count (rmp/project ctx :grain.control/lease-ownership)))))
              (let [leases (rmp/project ctx :grain.control/lease-ownership)]
                (is (= 1 (count leases)))
                (is (= (:node-id cp-instance)
                       (get leases tenant-1)))))
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
                ;; Wait for reactor to start the poller for tenant-1
                (harness/wait-for
                 #(contains? (or (cp/running-processors cp-instance) #{}) tenant-1))
                ;; Append another event — poller should process it
                (es/append store {:tenant-id tenant-1
                                  :events [(es/->event {:type :test/lifecycle-event :body {}})]})
                (harness/wait-for #(pos? (count @processed)))
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
              (harness/wait-for
               #(pos? (count (or (cp/running-processors cp-instance) #{}))))
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
              ;; Wait for the effect to start (proves assignment fired, poller picked up the event, and the effect ran)
              (deref effect-started 10000 :timeout)
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
                                     :staleness-threshold-ms 1000})
              hb-count (fn []
                         (count (filter #(= :grain.control/node-heartbeat (:event/type %))
                                        (into []
                                              (remove #(= :grain/tx (:event/type %)))
                                              (es/read store
                                                       {:tenant-id events/control-plane-tenant-id})))))]
          ;; Wait for at least 3 heartbeats (initial + 2 scheduled ticks)
          (harness/wait-for #(>= (hb-count) 3))
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
                ;; Wait for assignment — each tenant gets a lease
                (let [ctx {:event-store store :cache cache-a
                           :tenant-id events/control-plane-tenant-id}]
                  (harness/wait-for
                   #(do (rmp/l1-clear!)
                        (= 2 (count (rmp/project ctx :grain.control/lease-ownership))))))
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
                ;; Wait until both tenants show 3 billing-done events each
                (let [done-count (fn [tid]
                                   (count (filter #(= :test/billing-done (:event/type %))
                                                  (into []
                                                        (remove #(= :grain/tx (:event/type %)))
                                                        (es/read store {:tenant-id tid})))))]
                  (harness/wait-for
                   #(and (= 3 (done-count tenant-1)) (= 3 (done-count tenant-2)))
                   {:timeout-ms 15000}))
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
