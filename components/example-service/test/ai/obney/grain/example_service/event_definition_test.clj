(ns ai.obney.grain.example-service.event-definition-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.example-service.interface.schemas]))

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
