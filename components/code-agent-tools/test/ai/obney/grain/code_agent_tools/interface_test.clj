(ns ai.obney.grain.code-agent-tools.interface-test
  (:require [ai.obney.grain.code-agent-tools.interface :as tools]
            [ai.obney.grain.example-base.core :as example-base]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(deftest catalog-includes-runtime-registries-and-schemas
  (let [cat (tools/catalog)]
    (is (contains? (:commands cat) :example/create-counter))
    (is (contains? (:queries cat) :example/counters))
    (is (contains? (:read-models cat) :example/counters))
    (is (contains? (:processors cat) :example/calculate-average-counter-value))
    (is (contains? (:periodic-triggers cat) :example/example-periodic-task))
    (is (contains? (:schemas cat) :example/create-counter))
    (is (contains? (:schemas cat) :example/counter-created))
    (is (true? (get-in cat [:commands :example/create-counter :schema :present?])))
    (is (true? (get-in cat [:read-models :example/counters :events/schemas :example/counter-created :present?])))
    (is (true? (get-in cat [:commands :example/create-counter :authorized?/present?])))))

(deftest validate-returns-malli-results
  (testing "valid command params"
    (is (:valid? (tools/validate :example/create-counter {:name "Counter A"}))))
  (testing "invalid command params"
    (let [result (tools/validate :example/create-counter {:name 1})]
      (is (false? (:valid? result)))
      (is (some? (:explain/humanized result))))))

(deftest installed-runtime-executes-against-example-app
  (let [app (ig/init (select-keys example-base/system
                                  [::example-base/event-store
                                   ::example-base/cache
                                   ::example-base/context]))
        ctx (::example-base/context app)]
    (try
      (tools/install! {:system app :context ctx :mode :dev})
      (testing "runtime summary"
        (is (= :dev (:mode (tools/runtime)))))
      (testing "invoke command"
        (is (nil? (:cognitect.anomalies/category
                  (tools/invoke-command! {:command/name :example/create-counter
                                          :name "Counter A"})))))
      (testing "invoke query"
        (let [result (tools/invoke-query {:query/name :example/counters})]
          (is (= 1 (count (:query/result result))))))
      (testing "read events"
        (is (= 1 (count (tools/events {:types #{:example/counter-created}
                                       :limit 10})))))
      (testing "project read model"
        (is (= 1 (count (tools/projection :example/counters)))))
      (testing "diagnostics degrade without control plane"
        (is (= false (get-in (tools/diagnostics) [:control-plane :control-plane/present?]))))
      (finally
        (ig/halt! app)))))
