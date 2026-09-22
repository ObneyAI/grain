;; Run from the repository root:
;;   clojure -M:dev scripts/repro-read-model-cache-race.clj
;; Deterministic regression for the segmented cache snapshot race, plus a
;; monolithic control. Both must return the same state as a full event replay.
(require '[clojure.test :as test])
(load-file "components/read-model-processor-v2/test/ai/obney/grain/read_model_processor_v2/concurrency_test.clj")
(let [result (try
               (test/run-tests 'ai.obney.grain.read-model-processor-v2.concurrency-test)
               (finally (shutdown-agents)))]
  (when (pos? (+ (:fail result) (:error result)))
    (System/exit 1)))
