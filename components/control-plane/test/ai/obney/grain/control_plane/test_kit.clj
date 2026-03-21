(ns ai.obney.grain.control-plane.test-kit
  "Reusable conformance suite for control plane properties.
   Each function takes a `make-env` fn that returns
   {:store event-store-instance :cleanup (fn [])}
   and runs assertions using clojure.test."
  (:require [clojure.test :refer [is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.harness :as harness]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.control-plane.read-models]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.core :as tp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid]))

(defschemas test-kit-schemas
  {:test/domain-event [:map [:n :int]]})

(def staleness-ms 15000)

(defn- make-two-instances [shared-store]
  (let [node-a (uuid/v7)
        _ (Thread/sleep 2)
        node-b (uuid/v7)
        inst-a (harness/make-instance shared-store node-a)
        inst-b (harness/make-instance shared-store node-b)]
    [inst-a inst-b]))

;; =====================================
;; CP1: Lease Exclusivity
;; =====================================

(defn cp1-lease-exclusivity [make-env]
  (testing "CP1: each (tenant, processor) pair is leased to at most one node"
    (let [{:keys [store cleanup]} (make-env)
          [inst-a inst-b] (make-two-instances store)
          tenant-1 (uuid/v4)
          tenant-2 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (es/append store {:tenant-id tenant-2
                          :events [(es/->event {:type :test/domain-event :body {:n 2}})]})
        (harness/emit-heartbeat! inst-a)
        (harness/emit-heartbeat! inst-b)
        (harness/run-assignment! inst-a [:proc/a :proc/b] staleness-ms :round-robin)
        (rmp/l1-clear!)
        (let [leases-a (harness/project-lease-ownership inst-a)
              leases-b (harness/project-lease-ownership inst-b)]
          (is (= leases-a leases-b) "Both instances see the same leases")
          (is (= 4 (count leases-a)) "2 tenants * 2 processors = 4 leases")
          (let [owners (set (vals leases-a))]
            (is (every? #{(:node-id inst-a) (:node-id inst-b)} owners)
                "All leases owned by one of the two nodes")))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

(defn cp1-concurrent-assignment [make-env]
  (testing "CP1: concurrent assignment attempts don't create duplicate leases"
    (let [{:keys [store cleanup]} (make-env)
          [inst-a inst-b] (make-two-instances store)
          tenant-1 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (harness/emit-heartbeat! inst-a)
        (harness/emit-heartbeat! inst-b)
        (let [ra (future (harness/run-assignment! inst-a [:proc/a] staleness-ms :round-robin))
              rb (future (harness/run-assignment! inst-b [:proc/a] staleness-ms :round-robin))]
          @ra @rb)
        (rmp/l1-clear!)
        (let [leases (harness/project-lease-ownership inst-a)]
          (is (= 1 (count leases)) "Exactly one lease for the pair")
          (is (contains? #{(:node-id inst-a) (:node-id inst-b)}
                         (get leases [tenant-1 :proc/a]))))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; CP3/CP9: Coordinator Convergence
;; =====================================

(defn cp3-coordinator-convergence [make-env]
  (testing "CP3: both instances agree on coordinator"
    (let [{:keys [store cleanup]} (make-env)
          [inst-a inst-b] (make-two-instances store)]
      (try
        (harness/emit-heartbeat! inst-a)
        (harness/emit-heartbeat! inst-b)
        (rmp/l1-clear!)
        (let [active-a (harness/project-active-nodes inst-a staleness-ms)
              active-b (harness/project-active-nodes inst-b staleness-ms)
              coord-a (assignment/coordinator active-a)
              coord-b (assignment/coordinator active-b)]
          (is (= coord-a coord-b) "Both agree on coordinator")
          (is (= (:node-id inst-a) coord-a) "Oldest node is coordinator"))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; CP5: Failover Liveness
;; =====================================

(defn cp5-failover-liveness [make-env]
  (testing "CP5: when node A departs, node B picks up its leases"
    (let [{:keys [store cleanup]} (make-env)
          [inst-a inst-b] (make-two-instances store)
          tenant-1 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (harness/emit-heartbeat! inst-a)
        (harness/emit-heartbeat! inst-b)
        (harness/run-assignment! inst-a [:proc/a :proc/b] staleness-ms :round-robin)
        (rmp/l1-clear!)
        (let [leases-before (harness/project-lease-ownership inst-a)]
          (is (= 2 (count leases-before)))
          (harness/emit-departed! inst-a)
          (rmp/l1-clear!)
          (harness/run-assignment! inst-b [:proc/a :proc/b] staleness-ms :round-robin)
          (rmp/l1-clear!)
          (let [leases-after (harness/project-lease-ownership inst-b)]
            (is (= 2 (count leases-after)) "All work reassigned")
            (is (every? #(= (:node-id inst-b) %) (vals leases-after))
                "All leases now on node B")))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; CP8: Tenant Isolation
;; =====================================

(defn cp8-tenant-isolation [make-env]
  (testing "CP8: control plane events don't leak to domain tenants"
    (let [{:keys [store cleanup]} (make-env)
          domain-tenant (uuid/v4)
          node-a (uuid/v7)]
      (try
        (es/append store {:tenant-id events/control-plane-tenant-id
                          :events [(events/->heartbeat node-a {})]})
        (es/append store {:tenant-id domain-tenant
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (let [domain-events (into [] (es/read store {:tenant-id domain-tenant}))]
          (is (every? #(not= :grain.control/node-heartbeat (:event/type %))
                      domain-events)
              "Domain tenant sees no control plane events"))
        (let [cp-events (into [] (es/read store {:tenant-id events/control-plane-tenant-id}))]
          (is (every? #(not= :test/domain-event (:event/type %))
                      cp-events)
              "Control plane tenant sees no domain events"))
        (finally
          (cleanup))))))

;; =====================================
;; Rebalance on join
;; =====================================

(defn rebalance-on-join [make-env]
  (testing "When a new node joins, work is redistributed"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          tenant-1 (uuid/v4)
          tenant-2 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (es/append store {:tenant-id tenant-2
                          :events [(es/->event {:type :test/domain-event :body {:n 2}})]})
        (harness/emit-heartbeat! inst-a)
        (harness/run-assignment! inst-a [:proc/a] staleness-ms :round-robin)
        (rmp/l1-clear!)
        (let [leases-before (harness/project-lease-ownership inst-a)]
          (is (= 2 (count leases-before)))
          (is (every? #(= node-a-id %) (vals leases-before))))
        (Thread/sleep 2)
        (let [node-b-id (uuid/v7)
              inst-b (harness/make-instance store node-b-id)]
          (try
            (harness/emit-heartbeat! inst-b)
            (rmp/l1-clear!)
            (harness/run-assignment! inst-a [:proc/a] staleness-ms :round-robin)
            (rmp/l1-clear!)
            (let [leases-after (harness/project-lease-ownership inst-a)
                  owners (set (vals leases-after))]
              (is (= 2 (count leases-after)))
              (is (= #{node-a-id node-b-id} owners) "Work distributed across both"))
            (finally
              (harness/stop-instance inst-b))))
        (finally
          (harness/stop-instance inst-a)
          (cleanup))))))

;; =====================================
;; Run all
;; =====================================

;; =====================================
;; CP6: Lease Fencing (checkpoint CAS)
;; =====================================

(defn cp6-lease-fencing [make-env]
  (testing "CP6: two nodes checkpointing the same event — only one succeeds"
    (let [{:keys [store cleanup]} (make-env)
          tenant-1 (uuid/v4)
          event (es/->event {:type :test/domain-event :body {:n 1}})]
      (try
        (es/append store {:tenant-id tenant-1 :events [event]})
        (let [processor-name :test/fencing-proc
              handler (fn [_] {})
              result-a (tp/process-event
                         {:event event :handler-fn handler :event-store store
                          :tenant-id tenant-1 :processor-name processor-name})
              result-b (tp/process-event
                         {:event event :handler-fn handler :event-store store
                          :tenant-id tenant-1 :processor-name processor-name})]
          ;; One succeeds (nil), one gets conflict
          (is (or (nil? result-a) (= ::anom/conflict (::anom/category result-a))))
          (is (or (nil? result-b) (= ::anom/conflict (::anom/category result-b))))
          ;; Exactly one checkpoint
          (let [proc-uuid (tp/processor-name->uuid processor-name)
                checkpoints (into [] (es/read store
                                       {:tenant-id tenant-1
                                        :types #{:grain/todo-processor-checkpoint}
                                        :tags #{[:processor proc-uuid]}}))]
            (is (= 1 (count checkpoints)))))
        (finally
          (cleanup))))))

;; =====================================
;; CP7: Catch-up Completeness
;; =====================================

(defn cp7-catch-up-completeness [make-env]
  (testing "CP7: after lease transfer, new owner catches up from last checkpoint"
    (let [{:keys [store cleanup]} (make-env)
          tenant-1 (uuid/v4)
          processor-name :test/catchup-proc
          events-1 (mapv #(es/->event {:type :test/domain-event :body {:n %}}) (range 1 6))]
      (try
        (es/append store {:tenant-id tenant-1 :events events-1})
        ;; Node A processes events 1-5
        (let [handler (fn [_] {})]
          (doseq [event events-1]
            (tp/process-event
              {:event event :handler-fn handler :event-store store
               :tenant-id tenant-1 :processor-name processor-name})))
        ;; Append 5 more events (simulating events during lease transfer)
        (let [events-2 (mapv #(es/->event {:type :test/domain-event :body {:n %}}) (range 6 11))]
          (es/append store {:tenant-id tenant-1 :events events-2})
          ;; Node B does catch-up — should process only events 6-10
          (let [processed-by-b (atom [])
                catch-up-handler (fn [{:keys [event]}]
                                   (swap! processed-by-b conj (:n event))
                                   {})
                ps (pubsub/start {:type :core-async :topic-fn :event/type})
                processor (tp/start {:event-pubsub ps
                                     :topics [:test/domain-event]
                                     :handler-fn catch-up-handler
                                     :context {:event-store store}
                                     :processor-name processor-name})]
            (try
              (Thread/sleep 500)
              (is (= [6 7 8 9 10] (sort @processed-by-b))
                  "New owner should process exactly the events after last checkpoint")
              (finally
                (tp/stop processor)
                (pubsub/stop ps)))))
        (finally
          (cleanup))))))

;; =====================================
;; Run all
;; =====================================

(defn run-all
  "Run all control plane property tests against the given backend."
  [make-env]
  (cp1-lease-exclusivity make-env)
  (cp1-concurrent-assignment make-env)
  (cp3-coordinator-convergence make-env)
  (cp5-failover-liveness make-env)
  (cp6-lease-fencing make-env)
  (cp7-catch-up-completeness make-env)
  (cp8-tenant-isolation make-env)
  (rebalance-on-join make-env))
