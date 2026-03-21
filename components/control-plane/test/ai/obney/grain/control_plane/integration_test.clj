(ns ai.obney.grain.control-plane.integration-test
  "Runs the control plane conformance suite against the Postgres event store.
   Gated by PG_EVENT_STORE_TESTS=true."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-postgres-v3.core :as pg-core]
            [ai.obney.grain.control-plane.test-kit :as test-kit]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [next.jdbc :as jdbc]))

(defn pg-config []
  {:conn {:type          :postgres
          :server-name   (or (System/getenv "PG_HOST") "localhost")
          :port-number   (or (System/getenv "PG_PORT") "5432")
          :username      (or (System/getenv "PG_USER") "postgres")
          :password      (or (System/getenv "PG_PASSWORD") "password")
          :database-name (or (System/getenv "PG_DATABASE") "obneyai")}})

(def ^:dynamic *store* nil)

(defn once-fixture [f]
  (if (= "true" (System/getenv "PG_EVENT_STORE_TESTS"))
    (let [store (es/start (pg-config))]
      (binding [*store* store]
        (try
          (f)
          (finally
            (es/stop store)))))
    (println "SKIPPING control-plane Postgres integration tests (set PG_EVENT_STORE_TESTS=true to enable)")))

(defn each-fixture [f]
  (let [pool (get-in *store* [:state ::pg-core/connection-pool])]
    (jdbc/execute! pool ["TRUNCATE grain.tenants CASCADE"]))
  (rmp/l1-clear!)
  (f)
  (rmp/l1-clear!))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

(defn make-env []
  {:store *store*
   :cleanup (fn []
              (let [pool (get-in *store* [:state ::pg-core/connection-pool])]
                (jdbc/execute! pool ["TRUNCATE grain.tenants CASCADE"])))})

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

(deftest cp6-lease-fencing
  (test-kit/cp6-lease-fencing make-env))

(deftest cp7-catch-up-completeness
  (test-kit/cp7-catch-up-completeness make-env))

(deftest rebalance-on-join
  (test-kit/rebalance-on-join make-env))
