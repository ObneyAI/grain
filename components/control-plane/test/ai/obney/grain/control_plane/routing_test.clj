(ns ai.obney.grain.control-plane.routing-test
  "Pure unit tests for route-for-tenant. No infrastructure needed — just maps."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.control-plane.routing :as routing]))

(def node-a #uuid "00000000-0000-0000-0000-00000000000a")
(def node-b #uuid "00000000-0000-0000-0000-00000000000b")
(def tenant-1 #uuid "10000000-0000-0000-0000-000000000001")
(def tenant-2 #uuid "10000000-0000-0000-0000-000000000002")
(def tenant-new #uuid "10000000-0000-0000-0000-000000000099")
(def dead-node #uuid "99999999-9999-9999-9999-999999999999")

(def active-nodes
  {node-a {:last-heartbeat-at 1000 :metadata {:address "10.0.1.1:8080"}}
   node-b {:last-heartbeat-at 1000 :metadata {:address "10.0.1.2:8080"}}})

(def leases {tenant-1 node-a, tenant-2 node-b})

;; =====================================
;; TR-T1: Local when owner
;; =====================================

(deftest tr-t1-local-when-owner
  (testing "TR1: request served locally when this node owns the tenant"
    (let [result (routing/route-for-tenant leases active-nodes node-a tenant-1)]
      (is (= :local (:route/decision result)))
      (is (= :owner (:route/reason result))))))

;; =====================================
;; TR-T2: Remote when not owner
;; =====================================

(deftest tr-t2-remote-when-not-owner
  (testing "TR2: retry signal when another active node owns the tenant"
    (let [result (routing/route-for-tenant leases active-nodes node-a tenant-2)]
      (is (= :remote (:route/decision result)))
      (is (= node-b (:route/owner result)))
      (is (= "10.0.1.2:8080" (:route/address result))))))

;; =====================================
;; TR-T3: Graceful degradation — no owner
;; =====================================

(deftest tr-t3-local-when-no-owner
  (testing "TR3: serve locally when no node owns the tenant (new tenant, transition)"
    (let [result (routing/route-for-tenant leases active-nodes node-a tenant-new)]
      (is (= :local (:route/decision result)))
      (is (= :no-owner (:route/reason result))))))

;; =====================================
;; TR-T3b: Graceful degradation — stale owner
;; =====================================

(deftest tr-t3b-local-when-owner-stale
  (testing "TR3: serve locally when lease owner is no longer in active-nodes"
    (let [stale-leases {tenant-1 dead-node}
          result (routing/route-for-tenant stale-leases active-nodes node-a tenant-1)]
      (is (= :local (:route/decision result)))
      (is (= :owner-stale (:route/reason result))))))
