(ns ai.obney.grain.control-plane.integration-test
  "Runs the control plane conformance suite against the SQLite event store."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.core :as sqlite-core]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.control-plane.harness :as harness]
            [ai.obney.grain.control-plane.test-kit :as test-kit]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [clj-uuid :as uuid]
            [next.jdbc :as jdbc])
  (:import [java.io File]))

(def ^:dynamic *store* nil)
(def ^:dynamic *db-file* nil)

(defn- delete-sidecar-files [^String path]
  (doseq [suffix ["-wal" "-shm" "-journal"]]
    (let [f (File. (str path suffix))]
      (when (.exists f) (.delete f)))))

(defn once-fixture [f]
  (let [tmp (File/createTempFile "grain-cp-sqlite-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)
        store (es/start {:conn {:type :sqlite :database-file path}})]
    (binding [*store* store
              *db-file* path]
      (try
        (f)
        (finally
          (es/stop store)
          (.delete (File. path))
          (delete-sidecar-files path))))))

(defn each-fixture [f]
  (let [pool (get-in *store* [:state ::sqlite-core/connection-pool])]
    ;; Wipe state between tests. Order matters for FK: tags, events, then tenants.
    (jdbc/execute! pool ["DELETE FROM event_tags"])
    (jdbc/execute! pool ["DELETE FROM events"])
    (jdbc/execute! pool ["DELETE FROM tenants"]))
  (rmp/l1-clear!)
  (f)
  (rmp/l1-clear!))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

(defn make-env []
  {:store *store*
   :cleanup (fn []
              (let [pool (get-in *store* [:state ::sqlite-core/connection-pool])]
                (jdbc/execute! pool ["DELETE FROM event_tags"])
                (jdbc/execute! pool ["DELETE FROM events"])
                (jdbc/execute! pool ["DELETE FROM tenants"])))})

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

(deftest reassignment-delay-survives-sqlite-store-and-cache-restart
  (let [tmp (File/createTempFile "grain-cp-reassignment-" ".sqlite")
        _ (.delete tmp)
        path (.getAbsolutePath tmp)
        tenant-id (uuid/v4)
        node-a (uuid/v7)
        node-b (uuid/v7)
        interval-ms 5000
        now-ms (atom 0)
        options {:reassignment-interval-ms interval-ms
                 :clock-ms-fn #(deref now-ms)}]
    (try
      (let [store-1 (es/start {:conn {:type :sqlite :database-file path}})
            inst-a (harness/make-instance store-1 node-a)
            inst-b (harness/make-instance store-1 node-b)]
        (try
          (rmp/l1-clear!)
          (es/append store-1
            {:tenant-id tenant-id
             :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
          (harness/emit-heartbeat! inst-a)
          (cp/run-assignment! (:ctx inst-a) node-a 60000 :round-robin options)
          (harness/emit-heartbeat! inst-b)
          (harness/emit-departed! inst-a)
          (cp/run-assignment! (:ctx inst-b) node-b 60000 :round-robin options)
          (rmp/l1-clear!)
          (let [released-at-ms (get (cp/project-lease-release-history (:ctx inst-b))
                                    tenant-id)]
            (is (= {} (cp/project-lease-ownership (:ctx inst-b))))
            (harness/stop-instance inst-a)
            (harness/stop-instance inst-b)
            (es/stop store-1)
            (rmp/l1-clear!)
            (let [store-2 (es/start {:conn {:type :sqlite :database-file path}})
                  restarted-b (harness/make-instance store-2 node-b)]
              (try
                (reset! now-ms (+ released-at-ms interval-ms -1))
                (cp/run-assignment! (:ctx restarted-b) node-b 60000 :round-robin options)
                (rmp/l1-clear!)
                (is (= released-at-ms
                       (get (cp/project-lease-release-history (:ctx restarted-b))
                            tenant-id)))
                (is (= {} (cp/project-lease-ownership (:ctx restarted-b))))
                (reset! now-ms (+ released-at-ms interval-ms))
                (cp/run-assignment! (:ctx restarted-b) node-b 60000 :round-robin options)
                (rmp/l1-clear!)
                (is (= {tenant-id node-b}
                       (cp/project-lease-ownership (:ctx restarted-b))))
                (finally
                  (harness/stop-instance restarted-b)
                  (es/stop store-2)))))
        (catch Throwable t
          (try (harness/stop-instance inst-a) (catch Throwable _))
          (try (harness/stop-instance inst-b) (catch Throwable _))
          (try (es/stop store-1) (catch Throwable _))
          (throw t))))
      (finally
        (rmp/l1-clear!)
        (.delete (File. path))
        (delete-sidecar-files path)))))
