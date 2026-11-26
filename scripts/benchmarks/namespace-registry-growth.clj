;; Repository root, with the system LMDB native library available:
;; clojure -Sdeps '{:aliases {:bench {:extra-paths ["components/read-model-processor-v2/test"]}}}' \
;;   -M:dev:bench scripts/benchmarks/namespace-registry-growth.clj > results.edn
;; Run unchanged at baseline and optimized revisions. This isolates allocation
;; of 32 large scoped namespaces with interleaved materialization writes; it is
;; not an application-page latency benchmark. No domain events are folded.
(require '[ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
         '[ai.obney.grain.read-model-processor-v2.core :as core]
         '[ai.obney.grain.kv-store.interface :as kv])

(def tenant (java.util.UUID. 7 9))
(def scope
  {:queries (mapv #(hash-map :tags #{[:PaymentPlan
                                     (java.util.UUID/nameUUIDFromBytes
                                       (.getBytes (str "registry-tag-" %)
                                                  java.nio.charset.StandardCharsets/UTF_8))]})
                  (range 318))})
(def identities
  (let [identity! (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-namespace-identity)
        ctx {:tenant-id tenant :read-model/scope scope}]
    (mapv #(identity! ctx (keyword "namespace-benchmark" (str "large-" %)) {:version 1}) (range 32))))
(def seeds
  (mapv #(vector tenant (keyword "namespace-benchmark" (str "seed-" %)) 1 [:event-scope "abcd"])
        (range 128)))

(defn workload [seeded?]
  (fixture/keyed-fixture
    (fn []
      (let [cache (:cache fixture/*keyed-ctx*)]
        (when seeded? (core/namespace-resolve! cache seeds))
        (let [start (System/nanoTime)]
          (doseq [id identities]
            (let [prefix (get (core/namespace-resolve! cache [id]) id)]
              (kv/transact cache :write
                #((:put %) (core/key-join prefix (byte-array [20])) (byte-array [7])))))
          (/ (- (System/nanoTime) start) 1e6))))))

(let [validate-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'namespace-validate-registry!)
      validate! @validate-var]
  (doseq [seeded? [false true]]
    (workload seeded?)
    (let [median (nth (sort (repeatedly 5 #(workload seeded?))) 2)
          counts (atom {:whole-registry-scans 0 :registry-rows-visited 0})]
      (with-redefs-fn
        {validate-var
         (fn [tx next-id]
           (swap! counts update :whole-registry-scans inc)
           (validate! (update tx :reduce
                              (fn [scan]
                                (fn [options rf initial]
                                  (scan options (fn [acc row]
                                                  (swap! counts update :registry-rows-visited inc)
                                                  (rf acc row)) initial)))) next-id))}
        #(workload seeded?))
      (prn (merge {:seed-namespaces (if seeded? 128 0) :new-namespaces 32
                   :scope-clauses 318 :materialization-transactions 32
                   :median-ms median :timing-instrumented? false}
                  @counts)))))
(shutdown-agents)
