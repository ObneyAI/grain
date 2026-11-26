;; From the repository root (real LMDB, in-memory event-store):
;; clojure -Sdeps '{:aliases {:bench {:extra-paths ["components/read-model-processor-v2/test"]}}}' \
;;   -M:dev:bench scripts/benchmarks/keyed-scope-preparation.clj > results.edn
;; Compare this script unchanged at the baseline and optimized revisions.
;; This isolates warm read setup; it is not an application-page latency claim.
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp]
         '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.read-model-processor-v2.interface-test :as fixture])

(defn median-ms [f]
  (dotimes [_ 20] (f))
  (nth (sort (repeatedly 11 #(let [start (System/nanoTime)]
                              (f)
                              (/ (- (System/nanoTime) start) 1e6)))) 5))

(fixture/keyed-fixture
  (fn []
    (let [tags (into #{} (map #(vector :PaymentPlan
                                 (java.util.UUID/nameUUIDFromBytes
                                   (.getBytes (str "scope-tag-" %) java.nio.charset.StandardCharsets/UTF_8))))
                     (range 318))
          scope {:queries (mapv #(hash-map :tags #{%}) (sort-by pr-str tags))}
          ctx (rmp/scoped-context fixture/*keyed-ctx* scope)
          model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
          owner "plan"
          _ (es/append (:event-store ctx)
                       {:tenant-id (:tenant-id ctx)
                        :events [(es/->event {:type :keyed/change :tags #{(first tags)}
                                            :body {:owner owner :delta 7}})]})
          _ (assert (= {:total 7} (rmp/value ctx model owner)))
          reads (fn [prepared]
                  (dotimes [_ 83] (assert (= {:total 7} (rmp/value prepared model owner)))))
          workloads {:implicit #(reads ctx)
                     :batched #(rmp/read-session ctx [model] reads)}
          identity-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-namespace-identity)
          canonical-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'canonical-cache-value)
          original-identity @identity-var original-canonical @canonical-var]
      (doseq [[name work] workloads]
        (let [counts (atom {:identities 0 :scope-serializations 0})]
          (with-redefs-fn {identity-var (fn [& args]
                                         (swap! counts update :identities inc)
                                         (apply original-identity args))
                          canonical-var (fn [value]
                                          (when (= value scope)
                                            (swap! counts update :scope-serializations inc))
                                          (original-canonical value))}
            work)
          (prn (merge {:workload name :reads 83 :scope-tags 318
                       :scope-shape :singleton-tag-query-union
                       :median-ms (with-redefs [es/read (fn [& _] (throw (ex-info "Warm replay" {})))]
                                    (median-ms work))
                       :timing-instrumented? false}
                      @counts)))))))
(shutdown-agents)
