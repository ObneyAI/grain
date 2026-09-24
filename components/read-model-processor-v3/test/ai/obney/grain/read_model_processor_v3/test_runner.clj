(ns ai.obney.grain.read-model-processor-v3.test-runner
  (:require [clojure.test :as test]))
(def suites
  '[ai.obney.grain.read-model-processor-v3.interface-test
    ai.obney.grain.read-model-processor-v3.indexed-test
    ai.obney.grain.read-model-processor-v3.collection-test
    ai.obney.grain.read-model-processor-v3.cache-modes-test
    ai.obney.grain.read-model-processor-v3.concurrency-test
    ai.obney.grain.read-model-processor-v3.index-key-test
    ai.obney.grain.read-model-processor-v3.index-definition-test])
(defn -main [& _]
  (run! require suites)
  (let [result (apply test/run-tests suites)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
