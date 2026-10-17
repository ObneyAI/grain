;; Run from the repository root:
;;   clojure -M:dev scripts/repro-read-model-cache-race.clj
;; Deterministic regression for the segmented cache snapshot race, plus a
;; monolithic control. Both must return the same state as a full event replay.
(require '[clojure.test :as test])
(load-file "components/read-model-processor-v2/test/ai/obney/grain/read_model_processor_v2/interface_test.clj")
(let [result (try
               (binding [test/*report-counters* (ref test/*initial-report-counters*)]
                 (test/test-vars
                  [#'ai.obney.grain.read-model-processor-v2.interface-test/monolithic-cache-remains-coherent-during-checkpoint-rollback
                   #'ai.obney.grain.read-model-processor-v2.interface-test/segmented-cache-remains-coherent-during-checkpoint-rollback])
                 (test/do-report (assoc @test/*report-counters* :type :summary))
                 @test/*report-counters*)
               (finally (shutdown-agents)))]
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
