;; Frozen empty-index append benchmark; original commit-tail batching is baseline. Run unchanged before and after.
;; Real LMDB, one event routed to N small records, four ownership-like indexes
;; plus identity. Namespace resolution happens before append, not in the timer.
;; Timing and instrumentation runs are separate. Fixed promotion hashes and
;; persistence metadata permit exact byte parity across implementations.
(require '[clojure.java.io :as io]
         '[ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
         '[ai.obney.grain.read-model-processor-v2.interface :as rmp]
         '[ai.obney.grain.read-model-processor-v2.core :as core]
         '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.core :as lmdb-core]
         '[ai.obney.grain.time.interface :as time]
         '[clj-uuid :as uuid])

(def tenant (java.util.UUID. 27 1))
(def model :cold-fanout/owners)
(def fields [:user-id :source-id :enrollment-id :application-id])
(def schema (into [:map] (map #(vector % [:maybe :uuid])) fields))
(def fixed-time (java.time.OffsetDateTime/parse "2026-10-14T00:00:00Z"))
(defn sha [rows]
  (.formatHex (java.util.HexFormat/of)
    (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
      (doseq [[k v] (sort-by first rows)]
        (.update digest (.array (doto (java.nio.ByteBuffer/allocate 8)
                                  (.putInt (count k)) (.putInt (count v)))))
        (.update digest (byte-array k)) (.update digest (byte-array v)))
      (.digest digest))))

(defn workload
  ([n membership?] (workload n membership? nil))
  ([n membership? instrumentation]
  (fixture/keyed-fixture
    (fn []
      (let [ctx (cond-> (assoc fixture/*keyed-ctx* :tenant-id tenant)
                  instrumentation (assoc :read-model/observer (:observer instrumentation)))
            owners (mapv #(java.util.UUID. 28 %) (range n))
            inputs (mapv (fn [id i]
                           [id {:user-id tenant :source-id (when (even? i) tenant)
                                :enrollment-id (java.util.UUID. 29 (mod i 11))
                                :application-id (when (zero? (mod i 3)) (java.util.UUID. 30 (mod i 7)))}])
                         owners (range n))]
        (rmp/register-read-model! model merge
          (cond-> {:events #{:keyed/change} :version 1 :route :inputs
                   :state-schema schema :result-schema schema
                   :indexes (zipmap fields fields)}
            membership? (assoc :filtered-indexes (set fields))))
        (rmp/progress ctx model)
        (let [id-seq (atom [(java.util.UUID/fromString "0199de29-0000-7000-8000-000000000001")
                           (java.util.UUID/fromString "0199de29-0000-7000-8000-000000000002")])]
          (with-redefs [uuid/v7 (fn [] (let [id (first @id-seq)] (swap! id-seq next) id))
                        time/now (constantly fixed-time)]
            (fixture/keyed-append! ctx [{:inputs inputs}])))
        (let [started (System/nanoTime)
              result (try
                       (when instrumentation (reset! (:active? instrumentation) true))
                       (rmp/value ctx model (first owners))
                       (finally (when instrumentation (reset! (:active? instrumentation) false))))
              ms (/ (- (System/nanoTime) started) 1e6)
              rows (fixture/keyed-stored-bytes)]
          (when-not (= (second (first inputs)) result) (throw (ex-info "Result mismatch" {})))
          {:ms ms :rows (count rows) :sha256 (sha rows)
           :stored-key-bytes (reduce + (map (comp count key) rows))
           :stored-value-bytes (reduce + (map (comp count val) rows))}))))))


(def baseline-source (or (first *command-line-args*)
                         (throw (ex-info "Pass frozen baseline core source path as first argument" {}))))
(def batch-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-apply-batch!))
(def candidate-batch @batch-var)
(def legacy-batch
  (let [form (with-open [reader (java.io.PushbackReader.
                                (io/reader baseline-source))]
               (loop []
                 (let [form (read {:eof nil} reader)]
                   (when-not form (throw (ex-info "Missing frozen baseline" {})))
                   (if (and (seq? form) (= 'defn- (first form)) (= 'keyed-apply-batch! (second form)))
                     form (recur)))))]
    (binding [*ns* (the-ns 'ai.obney.grain.read-model-processor-v2.core)] (eval form))
    (let [legacy @batch-var] (alter-var-root batch-var (constantly candidate-batch)) legacy)))
(defn run-build [batch n filtered? & [instrumentation]]
  (with-redefs-fn {batch-var batch} #(workload n filtered? instrumentation)))
(defn median [values] (nth (vec (sort values)) (quot (count values) 2)))
(with-redefs [;; Fixed promotion (the member's Arrays/hashCode) for reproducible counts.
              ;; Pre-OPT-3 code drew from ranked-random-hash; since OPT-3 every promotion
              ;; goes through ranked-promotion-hash [key prefix offset member].
              core/ranked-promotion-hash (fn [_ _ _ member] (java.util.Arrays/hashCode ^bytes member))]
  (doseq [n [318 1000] filtered? [false true]]
    (dotimes [_ 4] (run-build legacy-batch n filtered?) (run-build candidate-batch n filtered?))
    (let [pairs (mapv (fn [iteration]
                        (let [order (if (even? iteration) [:baseline :candidate] [:candidate :baseline])]
                          (into {} (map (fn [stage] [stage (run-build (if (= stage :baseline) legacy-batch candidate-batch)
                                                                     n filtered?)]) order))))
                      (range 5))
          counter (fn [batch]
                    (let [active? (atom false) operations (atom {}) transaction kv/transact
                          result (with-redefs [kv/transact
                                              (fn [cache mode f]
                                                (transaction cache mode
                                                  (fn [tx]
                                                    (f (reduce (fn [tx* operation]
                                                                 (assoc tx* operation (fn [& args]
                                                                   (when @active? (swap! operations update operation (fnil inc 0)))
                                                                   (apply (get tx operation) args))))
                                                               tx [:get :put :delete :reduce])))))]
                                   (run-build batch n filtered? {:active? active?}))]
                      (assoc result :operations @operations)))
          before (counter legacy-batch) after (counter candidate-batch)
          hashes (mapcat #(map :sha256 (vals %)) pairs)]
      (when-not (every? #(= (:sha256 before) %) (conj (vec hashes) (:sha256 after)))
        (throw (ex-info "Full native KV byte parity failed" {:owners n :filtered? filtered?})))
      (prn {:owners n :filtered? filtered? :timing-instrumented? false
            :before-median-ms (median (map #(get-in % [:baseline :ms]) pairs))
            :after-median-ms (median (map #(get-in % [:candidate :ms]) pairs))
            :pairs (mapv #(into {} (map (fn [[stage result]] [stage (:ms result)]) %)) pairs)
            :before-native (:operations before) :after-native (:operations after)
            :sha256 (:sha256 before) :full-kv-byte-parity? true}))))
(shutdown-agents)
