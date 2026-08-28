(ns ai.obney.grain.example-service.event-definition-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.example-service.interface.schemas]))

(use-fixtures
  :each
  (fn [test-fn]
    ;; Other event-store suites deliberately reset the process-wide definition
    ;; registry. Reload the declaration namespace so this test is independent of
    ;; suite ordering and exercises defevent registration itself.
    (require 'ai.obney.grain.example-service.interface.schemas :reload)
    (test-fn)))

(deftest example-events-are-defined
  (doseq [event-type [:example/counter-created
                      :example/counter-incremented
                      :example/counter-decremented
                      :example/average-calculated]]
    (let [definition (event-store/event-definition event-type)]
      (is (= event-type (:event/type definition)))
      (is (string? (:description definition)))
      (is (some? (:schema definition)))
      (is (nil? (:history definition))))))
