(ns ai.obney.grain.code-agent-tools.mandate-test
  "Integration tests for the boot-time mandate: defeventmodel registration +
   event-model-validator/verify-or-throw! against the live :example runtime.

   Requiring example-base loads the example-service namespaces, whose def* macros
   register the handlers and whose event-model ns registers the :example model."
  (:require [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.grain.event-model.interface :as em]
            [ai.obney.grain.event-retention.interface :as retention]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.example-base.core]
            [clojure.test :refer [deftest is testing]]))

(defn- types [v] (set (map :type (:findings v))))

(deftest registered-example-model-verifies-strict
  (let [v (emv/verify-event-model!)]
    (is (true? (:valid? v)) (str "unexpected findings: " (vec (:findings v))))
    (is (true? (get-in v [:summary :strict])))
    (is (zero? (get-in v [:summary :fatal])))))

(deftest verify-or-throw-passes-on-the-good-model
  (is (map? (emv/verify-or-throw!))))

(deftest verify-or-throw-rejects-an-incomplete-model
  (let [ex (try (emv/verify-or-throw! {:model {:example {:commands {}}}}) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "an empty model must refuse to boot")
    (is (= :event-model/invalid (:type (ex-data ex))))
    (is (false? (get-in (ex-data ex) [:verdict :valid?])))
    (is (contains? (types (:verdict (ex-data ex))) :block/uncovered))))

(deftest strict-boot-reconciles-durable-retention-activation
  (let [store (es/start {:conn {:type :in-memory}})
        admin (retention/administration store)]
    (try
      (retention/activate! admin :grain/todo-processor-checkpoint)
      (is (true? (:valid? (emv/verify-event-model! {:event-store store}))))
      (let [original es/event-definition
            verdict (with-redefs [es/event-definition
                                  (fn [event-type]
                                    (cond-> (original event-type)
                                      (= event-type :grain/todo-processor-checkpoint)
                                      (assoc :history/normalized
                                             {:retain-at-least {:seconds 7200 :nanos 0}
                                              :keep-latest-per {:tags #{:processor}}})))]
                      (emv/verify-event-model! {:event-store store}))]
        (is (false? (:valid? verdict)))
        (is (contains? (types verdict) :history/active-policy-mismatch)))
      (finally
        (es/stop store)))))

(deftest strict-mandates-full-coverage
  (testing "dropping a live command from the model -> :block/uncovered -> fatal"
    (let [model (update-in (em/registered-model) [:example :commands] dissoc :example/increment-counter)
          v (emv/verify-event-model! {:model model})]
      (is (false? (:valid? v)))
      (is (contains? (types v) :block/uncovered)))))

(deftest strict-is-topology-only
  (testing "strict boot validation accepts commands without behavioural examples"
    (let [v (emv/verify-event-model!)]
      (is (true? (:valid? v)))
      (is (not (contains? (types v) :gwt/missing))))))

(deftest lenient-validate-stays-backward-compatible
  (testing "non-strict validate of the registered model has no errors (warnings/info only)"
    (let [v (emv/validate-event-model (em/registered-model))]
      (is (true? (:valid? v)))
      (is (false? (get-in v [:summary :strict]))))))

(deftest structural-only-validates-a-foreign-model
  (testing "a non-grain model passes structural-only even with the grain runtime loaded"
    (let [foreign {:billing {:commands {:billing/charge {:description "x" :schema [:map]
                                                         :reads #{:billing/invoices} :produces #{:billing/charged}}}
                             :events {:billing/charged {:description "x" :schema [:map]}}
                             :read-models {:billing/invoices {:description "x" :consumes #{:billing/charged}}}}}
          with (emv/validate-event-model foreign {:structural-only true})
          without (emv/validate-event-model foreign)]
      (is (true? (:valid? with)))
      (is (false? (get-in with [:summary :runtime/registries-present?])))
      ;; without the opt, the loaded grain runtime correctly flags the foreign blocks
      (is (false? (:valid? without)))
      (is (contains? (set (map :type (:findings without))) :block/undeclared)))))
