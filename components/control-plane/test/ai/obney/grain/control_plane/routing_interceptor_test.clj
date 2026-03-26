(ns ai.obney.grain.control-plane.routing-interceptor-test
  "Interceptor tests for tenant-aware routing.
   Uses in-memory event store + harness. Simulated Pedestal contexts (plain maps)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.control-plane.routing :as routing]
            [ai.obney.grain.control-plane.harness :as harness]
            [ai.obney.grain.control-plane.core :as cp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clj-uuid :as uuid]))

(defschemas routing-test-schemas
  {:test/domain-event [:map [:n :int]]})

(use-fixtures :each (fn [f] (rmp/l1-clear!) (f)))

(def staleness-ms 15000)

(defn- make-env []
  (let [store (es/start {:conn {:type :in-memory}})]
    {:store store :cleanup #(es/stop store)}))

(defn- make-interceptor [instance]
  (routing/tenant-routing-interceptor
    {:extract-tenant-id (fn [ctx] (get-in ctx [:request :headers "x-tenant-id"]))
     :this-node-id (:node-id instance)
     :ctx (:ctx instance)
     :staleness-threshold-ms staleness-ms}))

(defn- pedestal-ctx [tenant-id]
  {:request {:headers (when tenant-id {"x-tenant-id" tenant-id})}})

;; =====================================
;; TR-T4: Interceptor passes through when local
;; =====================================

(deftest tr-t4-interceptor-passes-through-when-local
  (testing "TR-T4: interceptor continues chain when this node owns the tenant"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          _ (Thread/sleep 2)
          node-b-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          inst-b (harness/make-instance store node-b-id)
          tenant-1 (uuid/v4)]
      (try
        ;; Setup: create tenant, heartbeat both nodes, assign
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (cp/emit-heartbeat! (:ctx inst-b) node-b-id {:address "10.0.1.2:8080"})
        (harness/run-assignment! inst-a staleness-ms :round-robin)
        (rmp/l1-clear!)

        ;; Find which node owns tenant-1
        (let [leases (harness/project-lease-ownership inst-a)
              owner-id (get leases tenant-1)
              owner-inst (if (= owner-id node-a-id) inst-a inst-b)
              interceptor (make-interceptor owner-inst)
              enter-fn (:enter interceptor)
              result (enter-fn (pedestal-ctx tenant-1))]
          ;; No :response set — chain continues
          (is (nil? (:response result))
              "No response set — interceptor lets the chain continue")
          ;; Routing decision annotated
          (is (= :local (get-in result [::routing/routing-decision :route/decision]))
              "Routing decision annotated on context"))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; TR-T5: Interceptor returns 503 when remote
;; =====================================

(deftest tr-t5-interceptor-returns-503-when-remote
  (testing "TR-T5: interceptor short-circuits with 503 when another node owns tenant"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          _ (Thread/sleep 2)
          node-b-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          inst-b (harness/make-instance store node-b-id)
          tenant-1 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (cp/emit-heartbeat! (:ctx inst-b) node-b-id {:address "10.0.1.2:8080"})
        (harness/run-assignment! inst-a staleness-ms :round-robin)
        (rmp/l1-clear!)

        ;; Find which node does NOT own tenant-1
        (let [leases (harness/project-lease-ownership inst-a)
              owner-id (get leases tenant-1)
              non-owner-inst (if (= owner-id node-a-id) inst-b inst-a)
              interceptor (make-interceptor non-owner-inst)
              enter-fn (:enter interceptor)
              result (enter-fn (pedestal-ctx tenant-1))]
          (is (= 503 (get-in result [:response :status]))
              "Returns 503 for wrong node")
          (is (some? (get-in result [:response :headers "Retry-After"]))
              "Includes Retry-After header"))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; TR-T6: Cookie set on leave when owner
;; =====================================

(deftest tr-t6-interceptor-sets-cookie-on-leave
  (testing "TR-T6: leave phase sets sticky cookie when served as owner"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          _ (Thread/sleep 2)
          node-b-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          inst-b (harness/make-instance store node-b-id)
          tenant-1 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (cp/emit-heartbeat! (:ctx inst-b) node-b-id {:address "10.0.1.2:8080"})
        (harness/run-assignment! inst-a staleness-ms :round-robin)
        (rmp/l1-clear!)

        (let [leases (harness/project-lease-ownership inst-a)
              owner-id (get leases tenant-1)
              owner-inst (if (= owner-id node-a-id) inst-a inst-b)
              interceptor (make-interceptor owner-inst)
              enter-fn (:enter interceptor)
              leave-fn (:leave interceptor)
              ;; Simulate enter → handler → leave
              after-enter (enter-fn (pedestal-ctx tenant-1))
              after-handler (assoc after-enter :response {:status 200 :body "ok" :headers {}})
              after-leave (leave-fn after-handler)
              cookie (get-in after-leave [:response :headers "Set-Cookie"])]
          (is (some? cookie) "Set-Cookie header present")
          (is (clojure.string/includes? cookie "GRAIN_TENANT_STICKY")
              "Cookie name is correct")
          (is (clojure.string/includes? cookie (str owner-id))
              "Cookie value contains the node-id"))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; TR-T6b: No cookie during graceful degradation
;; =====================================

(deftest tr-t6b-no-cookie-during-graceful-degradation
  (testing "TR-T6b: no sticky cookie when serving as fallback (no owner)"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          tenant-new (uuid/v4)]
      (try
        ;; Don't create tenant — no lease exists
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (rmp/l1-clear!)

        (let [interceptor (make-interceptor inst-a)
              enter-fn (:enter interceptor)
              leave-fn (:leave interceptor)
              after-enter (enter-fn (pedestal-ctx tenant-new))
              _ (is (nil? (:response after-enter)) "Passes through on degradation")
              after-handler (assoc after-enter :response {:status 200 :body "ok" :headers {}})
              after-leave (leave-fn after-handler)
              cookie (get-in after-leave [:response :headers "Set-Cookie"])]
          (is (nil? cookie) "No Set-Cookie header during graceful degradation"))
        (finally
          (harness/stop-instance inst-a)
          (cleanup))))))

;; =====================================
;; TR-T7: Routing changes after lease migration
;; =====================================

(deftest tr-t7-routing-changes-after-lease-migration
  (testing "TR-T7: after lease migration, old node returns 503, new node serves locally"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          _ (Thread/sleep 2)
          node-b-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)
          inst-b (harness/make-instance store node-b-id)
          tenant-1 (uuid/v4)]
      (try
        (es/append store {:tenant-id tenant-1
                          :events [(es/->event {:type :test/domain-event :body {:n 1}})]})
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (cp/emit-heartbeat! (:ctx inst-b) node-b-id {:address "10.0.1.2:8080"})
        (harness/run-assignment! inst-a staleness-ms :round-robin)
        (rmp/l1-clear!)

        (let [leases (harness/project-lease-ownership inst-a)
              owner-id (get leases tenant-1)
              ;; Original owner serves locally
              owner-inst (if (= owner-id node-a-id) inst-a inst-b)
              interceptor-owner (make-interceptor owner-inst)
              result-before ((:enter interceptor-owner) (pedestal-ctx tenant-1))]
          (is (nil? (:response result-before)) "Owner serves locally before migration")

          ;; Simulate departure of the owner
          (if (= owner-id node-a-id)
            (harness/emit-departed! inst-a)
            (harness/emit-departed! inst-b))
          (rmp/l1-clear!)

          ;; Surviving node runs reassignment
          (let [survivor-inst (if (= owner-id node-a-id) inst-b inst-a)]
            (harness/run-assignment! survivor-inst staleness-ms :round-robin)
            (rmp/l1-clear!)

            ;; Old owner now returns 503 (or local with stale-owner since it departed)
            ;; New owner serves locally
            (let [interceptor-survivor (make-interceptor survivor-inst)
                  result-after ((:enter interceptor-survivor) (pedestal-ctx tenant-1))]
              (is (nil? (:response result-after))
                  "Surviving node now serves locally after migration"))))
        (finally
          (harness/stop-instance inst-a)
          (harness/stop-instance inst-b)
          (cleanup))))))

;; =====================================
;; Nil tenant-id passthrough
;; =====================================

(deftest nil-tenant-id-passes-through
  (testing "Requests without tenant-id pass through untouched"
    (let [{:keys [store cleanup]} (make-env)
          node-a-id (uuid/v7)
          inst-a (harness/make-instance store node-a-id)]
      (try
        (cp/emit-heartbeat! (:ctx inst-a) node-a-id {:address "10.0.1.1:8080"})
        (rmp/l1-clear!)
        (let [interceptor (make-interceptor inst-a)
              enter-fn (:enter interceptor)
              result (enter-fn (pedestal-ctx nil))]
          (is (nil? (:response result)) "No response — passes through")
          (is (nil? (::routing/routing-decision result)) "No routing decision annotated"))
        (finally
          (harness/stop-instance inst-a)
          (cleanup))))))
