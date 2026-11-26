;; Controlled diagnostic, not a production latency benchmark.
;; clojure -J-Dlmdbjava.native.lib=/path/to/liblmdb.dylib -M:dev \
;;   scripts/benchmarks/registry-stall-hypothesis.clj
;; Optional namespace counts: ...clj 128 512 2048
;; Compare publication coordination: ...clj --comparison 8192 318
;; Uses fresh temporary 2 GiB maps and removes only its own temporary files.
(require '[ai.obney.grain.read-model-processor-v2.core :as core]
         '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.interface :as lmdb]
         '[clojure.java.io :as io])

(def validation-key :ai.obney.grain.kv-store-lmdb.core/validation-state)
(def validate-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'namespace-validate-registry!))
(def identity-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-namespace-identity))
(def ^:dynamic *pause-publication?* false)

(defn await! [p]
  (let [v (deref p 120000 ::timeout)]
    (when (= ::timeout v) (throw (ex-info "Diagnostic timed out" {})))
    v))

(defn elapsed-ms [start] (/ (- (System/nanoTime) start) 1e6))

(defn with-cache [f]
  (let [dir (str (java.nio.file.Files/createTempDirectory "grain-registry-stall-"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        cache (kv/start (lmdb/->KV-Store-LMDB
                         {:storage-dir dir :db-name "probe" :map-size (* 2 1024 1024 1024)}))]
    (try (f cache)
         (finally
           (kv/stop cache)
           (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file))))))

(defn identity-fn [clauses]
  (let [scope {:queries (mapv #(hash-map :tags #{[:payment-plan (java.util.UUID. 0 %)]})
                              (range clauses))}
        ctx {:tenant-id (java.util.UUID. 7 9) :read-model/scope scope}]
    #(@identity-var ctx (keyword "stall-probe" (str %)) {:version 1})))

(defn seed! [cache identity n]
  (doseq [batch (partition-all 64 (map identity (range n)))]
    (core/namespace-resolve! cache (vec batch))))

(defn publication-race! [n clauses]
  (with-cache
    (fn [cache]
      (let [identity (identity-fn clauses)
            state (validation-key cache)
            committed (promise) release (promise)
            original-swap swap! original-validate @validate-var scans (atom 0)]
        (seed! cache identity n)
        (with-redefs-fn
          {#'clojure.core/swap!
           (fn [a f & args]
             ;; Force a scheduler pause at the existing post-commit publication
             ;; boundary. No native transaction or registry data is modified.
             (when (and *pause-publication?* (identical? a state))
               (deliver committed true)
               (await! release))
             (apply original-swap a f args))
           validate-var (fn [tx next-id]
                          (original-swap scans inc)
                          (original-validate tx next-id))}
          (fn []
            (let [writer-a (future
                             (binding [*pause-publication?* true]
                               (kv/put! cache {:k (byte-array [90 1]) :v (byte-array [1])})))
                  thread-b (promise)
                  writer-b (future
                             (await! committed)
                             (deliver thread-b (Thread/currentThread))
                             (kv/put! cache {:k (byte-array [90 2]) :v (byte-array [2])}))]
              (try
                (await! committed)
                (let [proofs-before (count (:proofs @state))
                      ^Thread thread (await! thread-b)
                      deadline (+ (System/nanoTime) 5000000000)]
                  ;; Old code lets B finish with no proof. Fixed code blocks B
                  ;; until A publishes. Release either way, without timing the
                  ;; artificial scheduler pause as application latency.
                  (loop []
                    (when (and (not (realized? writer-b))
                               (not= Thread$State/BLOCKED (.getState thread))
                               (< (System/nanoTime) deadline))
                      (Thread/yield)
                      (recur)))
                  (let [waited? (= Thread$State/BLOCKED (.getState thread))]
                    (assert (or waited? (realized? writer-b)))
                    (deliver release true)
                    (await! writer-a)
                    (await! writer-b)
                    (let [proofs-after (count (:proofs @state))
                          start (System/nanoTime)]
                      (core/namespace-resolve! cache [(identity n)])
                      (prn {:case :forced-publication-race :namespaces n :scope-clauses clauses
                            :proofs-before proofs-before :waited-for-publication? waited?
                            :proofs-after-unrelated-writer proofs-after
                            :full-scans-on-next-allocation @scans
                            :allocation-ms (elapsed-ms start)}))))
                (finally (deliver release true) (await! writer-a) (await! writer-b))))))))))

(defn growth! [n clauses]
  (with-cache
    (fn [cache]
      (let [identity (identity-fn clauses)
            original @validate-var scans (atom [])]
        (seed! cache identity n)
        (with-redefs-fn
          {validate-var (fn [tx next-id]
                          (let [start (System/nanoTime)]
                            (original tx next-id)
                            (swap! scans conj {:namespaces next-id :rows (inc (* 2 next-id))
                                               :ms (elapsed-ms start)})))}
          (fn []
            (let [start (System/nanoTime)]
              (core/namespace-resolve! cache [(identity n)])
              (assert (empty? @scans))
              (prn {:case :proof-retained :namespaces n :scope-clauses clauses
                    :allocation-ms (elapsed-ms start) :full-scans 0}))
            ;; An explicit loss control isolates validation cost from random
            ;; race frequency. Sequential writes otherwise retain the proof.
            (dotimes [i 3]
              (reset! (validation-key cache) {:revision nil :proofs []})
              (let [start (System/nanoTime)]
                (core/namespace-resolve! cache [(identity (+ n i 1))])
                (prn (merge {:case :proof-lost :scope-clauses clauses :iteration i
                             :allocation-ms (elapsed-ms start)} (peek @scans)))))))
        ;; Observe another writer waiting for a genuine registry scan. The
        ;; handshake happens before the scan timer; no simulated scan sleep.
        (reset! (validation-key cache) {:revision nil :proofs []})
        (let [entered (promise) scan-started (promise) scan-ms (promise)
              attempting (java.util.concurrent.CountDownLatch. 8)]
          (with-redefs-fn
            {validate-var (fn [tx next-id]
                            (deliver entered true)
                            (assert (.await attempting 120 java.util.concurrent.TimeUnit/SECONDS))
                            (let [start (System/nanoTime)]
                              (deliver scan-started true)
                              (original tx next-id)
                              (deliver scan-ms (elapsed-ms start))))}
            (fn []
              (let [holder (future (core/namespace-resolve! cache [(identity (+ n 4))]))
                    waiters (mapv (fn [i]
                                    (future
                                      (await! entered)
                                      (let [start (System/nanoTime)]
                                        (.countDown attempting)
                                        (kv/put! cache {:k (byte-array [90 i]) :v (byte-array [3])})
                                        (elapsed-ms start))))
                                  (range 8))
                    reader (future
                             (await! scan-started)
                             (let [start (System/nanoTime)]
                               (core/namespace-resolve! cache [(identity 0)])
                               (elapsed-ms start)))]
                (await! holder)
                (prn {:case :queued-writer :namespaces (+ n 4) :scope-clauses clauses
                      :scan-ms (await! scan-ms) :writer-total-ms (mapv await! waiters)
                      :known-namespace-reader-ms (await! reader)
                      :space (kv/transact cache :read #((:space %)))})))))))))

(defn concurrent-writers! []
  (with-cache
    (fn [cache]
      (let [identity (identity-fn 1) original @validate-var scans (atom 0) go (promise)]
        (seed! cache identity 128)
        (with-redefs-fn
          {validate-var (fn [tx next-id] (swap! scans inc) (original tx next-id))}
          (fn []
            (let [start (System/nanoTime)
                  workers (mapv (fn [worker]
                                  (future
                                    (await! go)
                                    (dotimes [i 32]
                                      (kv/put! cache {:k (byte-array [90 worker]) :v (byte-array [1])})
                                      (core/namespace-resolve! cache [(identity (+ 128 (* worker 32) i))]))))
                                (range 8))]
              (deliver go true)
              (run! await! workers)
              (prn {:case :unforced-concurrent-writers :workers 8 :new-namespaces 256
                    :unrelated-writes 256 :full-scans @scans :total-ms (elapsed-ms start)}))))))))

(try
  (if (= "--comparison" (first *command-line-args*))
    (let [n (parse-long (or (second *command-line-args*) "8192"))
          clauses (parse-long (or (nth *command-line-args* 2 nil) "318"))]
      (dotimes [_ 3] (publication-race! n clauses))
      (dotimes [_ 3] (concurrent-writers!)))
    (do
      (publication-race! 32 1)
      (concurrent-writers!)
      (doseq [clauses [1 318]
              n (if (seq *command-line-args*) (map parse-long *command-line-args*) [128 512 2048])]
        (growth! n clauses))))
  (finally (shutdown-agents)))
