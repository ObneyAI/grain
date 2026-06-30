(ns ai.obney.grain.code-agent-tools.mandate-test
  "Integration tests for the boot-time mandate: defeventmodel registration +
   event-model-validator/verify-or-throw! against the live :example runtime.

   Requiring example-base loads the example-service namespaces, whose def* macros
   register the handlers and whose event-model ns registers the :example model."
  (:require [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.grain.event-model.interface :as em]
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

(deftest strict-mandates-full-coverage
  (testing "dropping a live command from the model -> :block/uncovered -> fatal"
    (let [model (update-in (em/registered-model) [:example :commands] dissoc :example/increment-counter)
          v (emv/verify-event-model! {:model model})]
      (is (false? (:valid? v)))
      (is (contains? (types v) :block/uncovered)))))

(deftest strict-mandates-gwt-on-commands
  (testing "removing Given/When/Then from a command -> :gwt/missing -> fatal"
    (let [model (update-in (em/registered-model) [:example :commands :example/create-counter]
                           dissoc :given-when-thens)
          v (emv/verify-event-model! {:model model})]
      (is (false? (:valid? v)))
      (is (contains? (types v) :gwt/missing)))))

(deftest lenient-validate-stays-backward-compatible
  (testing "non-strict validate of the registered model has no errors (warnings/info only)"
    (let [v (emv/validate-event-model (em/registered-model))]
      (is (true? (:valid? v)))
      (is (false? (get-in v [:summary :strict]))))))
