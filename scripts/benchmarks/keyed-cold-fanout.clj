;; Frozen cold materialization benchmark. Run unchanged before and after.
;; Real LMDB, one event routed to N small records, four ownership-like indexes
;; plus identity. Namespace resolution happens before append, not in the timer.
;; Timing and instrumentation runs are separate. Fixed promotion hashes and
;; persistence metadata permit exact byte parity across implementations.
(require '[ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
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

(let [cursor-var #'lmdb-core/with-scan-cursor
      cursor-fn @cursor-var
      tx-var #'kv/transact tx-fn @tx-var]
  (with-redefs [core/ranked-random-hash #(java.util.Arrays/hashCode ^bytes %)]
    (doseq [membership? [false true] n [10 100 318 1000]]
      (workload n membership?)
      (let [samples (repeatedly 5 #(workload n membership?))
            samples (vec samples)
            cursors (atom #{}) scans (atom 0) modes (atom {}) active? (atom false)
            observed (atom {})
            instrumentation {:active? active?
                             :observer (fn [{:keys [operation bytes]}]
                                         (when @active?
                                           (swap! observed update operation
                                                  (fn [before]
                                                    {:calls (inc (:calls before 0))
                                                     :bytes (+ (:bytes before 0) bytes)}))))}
            counted (with-redefs-fn
                      {cursor-var (fn [db txn spare f]
                                    (when @active? (swap! scans inc))
                                    (cursor-fn db txn spare #(do (when @active? (swap! cursors conj %)) (f %))))
                       tx-var (fn [cache mode f]
                                (when @active? (swap! modes update mode (fnil inc 0)))
                                (tx-fn cache mode f))}
                      #(workload n membership? instrumentation))]
        (prn {:owners n :membership-indexes? membership? :indexes 5 :events 1
              :median-ms (nth (sort (map :ms samples)) 2)
              :samples-ms (mapv :ms samples) :timing-instrumented? false
              :cursor-handles (count @cursors) :range-scans @scans :transactions @modes
              :counter-scope :timed-materialization-only :observed @observed
              :stored-rows (:rows counted) :sha256 (:sha256 counted)
              :stored-key-bytes (:stored-key-bytes counted) :stored-value-bytes (:stored-value-bytes counted)
              :sample-byte-parity? (every? #(= (:sha256 counted) (:sha256 %)) samples)})))))
(shutdown-agents)
