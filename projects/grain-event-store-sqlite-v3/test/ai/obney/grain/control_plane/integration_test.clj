(ns ai.obney.grain.control-plane.integration-test
  "Runs the control plane conformance suite against the SQLite event store."
  (:require [clojure.test :refer :all]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.core :as sqlite-core]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.control-plane.test-kit :as test-kit]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
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
