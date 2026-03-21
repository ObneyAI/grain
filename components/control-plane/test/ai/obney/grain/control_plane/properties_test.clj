(ns ai.obney.grain.control-plane.properties-test
  "Runs the control plane conformance suite against the in-memory event store."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.test-kit :as test-kit]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn clean-fixture [f]
  (rmp/l1-clear!)
  (f)
  (rmp/l1-clear!))

(use-fixtures :each clean-fixture)

(defn make-env []
  (let [store (es/start {:conn {:type :in-memory}})]
    {:store store
     :cleanup #(es/stop store)}))

(deftest cp1-lease-exclusivity
  (test-kit/cp1-lease-exclusivity make-env))

(deftest cp1-concurrent-assignment
  (test-kit/cp1-concurrent-assignment make-env))

(deftest cp3-coordinator-convergence
  (test-kit/cp3-coordinator-convergence make-env))

(deftest cp5-failover-liveness
  (test-kit/cp5-failover-liveness make-env))

(deftest cp8-tenant-isolation
  (test-kit/cp8-tenant-isolation make-env))

(deftest rebalance-on-join
  (test-kit/rebalance-on-join make-env))

(deftest cp6-lease-fencing
  (test-kit/cp6-lease-fencing make-env))

(deftest cp7-catch-up-completeness
  (test-kit/cp7-catch-up-completeness make-env))
