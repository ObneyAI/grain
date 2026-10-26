(ns ai.obney.grain.kv-store-lmdb.interface-test
  (:require [clojure.test :as test :refer :all]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.kv-store-lmdb.core :as core]
            [clojure.java.io :as io]))

(defn- delete-dir-recursively [dir]
  (let [f (io/file dir)]
    (when (.exists f)
      (run! #(when (.isFile %) (io/delete-file %))
            (file-seq f))
      (run! #(io/delete-file % true)
            (reverse (file-seq f))))))

(def ^:dynamic *cache* nil)

(defn test-fixture [f]
  (let [dir (str "/tmp/kv-lmdb-test-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir
                                                :db-name "test"
                                                :map-size (* 1024 1024 64)}))]
    (binding [*cache* cache]
      (try
        (f)
        (finally
          (kv/stop cache)
          (delete-dir-recursively dir))))))

(use-fixtures :each test-fixture)

(deftest validated-updates-reuse-only-the-same-invariant-and-known-preserving-writes
  (let [prefix (byte-array [10]) k (byte-array [10 1]) calls (atom 0)
        validator (fn [tx]
                    (swap! calls inc)
                    (when-let [v ((:get tx) k)]
                      (when-not (= [7] (vec v)) (throw (ex-info "Invalid value" {})))))
        update! (fn []
                  (kv/transact *cache* :write
                    (fn [tx]
                      ((:with-validation tx) prefix validator #((:put tx) k (byte-array [7]))))))]
    (update!)
    (let [env ^org.lmdbjava.Env (:env *cache*) before (.-lastTransactionId (.info env))]
      (update!)
      (is (= (inc before) (.-lastTransactionId (.info env)))
          "An equal-value put still commits this write transaction's native ID"))
    (is (= 1 @calls))
    (kv/transact *cache* :write #((:put %) (byte-array [20 1]) (byte-array [9])))
    (update!)
    (is (= 1 @calls) "Known unrelated writes preserve proof")
    (kv/transact *cache* :write
      (fn [tx]
        ((:with-validation tx) prefix (fn [_] nil) #((:put tx) k (byte-array [99])))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value" (update!))
        "A weaker validator on the same prefix cannot satisfy the original invariant")
    (is (= 2 @calls))
    (kv/put! *cache* {:k k :v (byte-array [7])})
    (update!)
    (is (= 3 @calls) "An overlapping public put invalidates the prefix proof")
    (is (nil? (kv/transact *cache* :read :with-validation)))))

(deftest validated-update-proofs-obey-rollback-final-mutations-and-lifetime
  (let [prefix (byte-array [10]) k (byte-array [10 1]) calls (atom 0)
        validator (fn [tx]
                    (swap! calls inc)
                    (when-let [v ((:get tx) k)]
                      (when-not (= [7] (vec v)) (throw (ex-info "Invalid value" {})))))
        checked (fn [tx]
                  ((:with-validation tx) prefix validator #((:put tx) k (byte-array [7]))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rollback"
          (kv/transact *cache* :write
            (fn [tx] (checked tx) (throw (ex-info "Rollback" {}))))))
    (is (nil? (kv/get! *cache* {:k k})))
    (kv/transact *cache* :write checked)
    (is (= 2 @calls) "A rolled-back validation cannot publish a proof")
    (kv/transact *cache* :write
      (fn [tx] (checked tx) ((:put tx) k (byte-array [99]))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Transaction aborted"
          (kv/transact *cache* :write
            (fn [tx]
              (try (checked tx) (catch clojure.lang.ExceptionInfo _ nil))
              ((:put tx) (byte-array [30]) (byte-array [1]))))))
    (is (nil? (kv/get! *cache* {:k (byte-array [30])})))
    (is (= 3 @calls) "Mutation after checked update invalidates its refreshed proof")
    (kv/put! *cache* {:k k :v (byte-array [7])})
    (let [escaped (kv/transact *cache* :write
                    (fn [tx] (checked tx) (:with-validation tx)))]
      (is (thrown? clojure.lang.ExceptionInfo (escaped prefix validator (fn [] nil)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Transaction aborted"
          (kv/transact *cache* :write
            (fn [tx]
              (is (= :rejected
                     @(future (try ((:with-validation tx) prefix validator (fn [] nil))
                                   (catch clojure.lang.ExceptionInfo _ :rejected)))))))))))

(deftest validated-update-proofs-copy-prefixes-and-bound-retention
  (let [validator (fn [_] nil) prefix (byte-array [10]) calls (atom 0)
        tracked (fn [_] (swap! calls inc))]
    (kv/transact *cache* :write
      (fn [tx]
        ((:with-validation tx) prefix tracked (fn [] nil))
        (aset-byte prefix 0 (byte 99))))
    (kv/transact *cache* :write
      #((:with-validation %) (byte-array [10]) tracked (fn [] nil)))
    (is (= 1 @calls) "Stored prefixes are immutable copies, including empty commits")
    (kv/transact *cache* :write
      (fn [tx]
        (doseq [n (range 40)]
          ((:with-validation tx) (byte-array [(byte n)]) validator (fn [] nil)))))
    (is (<= (count (:proofs @(:ai.obney.grain.kv-store-lmdb.core/validation-state *cache*)))
            16))
    (kv/transact *cache* :write
      #((:with-validation %) (byte-array [10]) tracked (fn [] nil)))
    (is (= 2 @calls) "Evicted proof is safely revalidated")))

(deftest legacy-writes-retain-only-unaffected-validation-proofs
  (let [prefix (byte-array [10 1]) k (byte-array [10 1 2]) calls (atom 0)
        validator (fn [tx]
                    (swap! calls inc)
                    (when-let [v ((:get tx) k)]
                      (when-not (= [7] (vec v)) (throw (ex-info "Invalid value" {})))))
        checked (fn []
                  (kv/transact *cache* :write
                    #((:with-validation %) prefix validator (fn [] nil))))]
    (kv/put! *cache* {:k k :v (byte-array [7])})
    (checked)
    (is (true? (kv/put! *cache* {:k (byte-array [20]) :v (byte-array [8])})))
    (is (nil? (kv/put-batch! *cache* {:entries [{:k (byte-array [10 2]) :v (byte-array [9])}
                                             {:k (byte-array [30]) :v (byte-array [8])}]})))
    (is (nil? (kv/put-batch! *cache* {:entries []})))
    (checked)
    (is (= 1 @calls) "Unrelated single, sibling-prefix and batch writes preserve proof")
    (kv/put! *cache* {:k (byte-array [10]) :v (byte-array [9])})
    (checked)
    (is (= 2 @calls) "Parent-prefix writes conservatively invalidate proof")
    (kv/put! *cache* {:k k :v (byte-array [99])})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value" (checked)))
    (is (= 3 @calls))
    (kv/put-batch! *cache* {:entries [{:k k :v (byte-array [7])}]})
    (checked)
    (is (= 4 @calls) "Overlapping batch writes require revalidation")))

(deftest failed-legacy-batches-publish-neither-values-nor-proof-invalidations
  (let [prefix (byte-array [10]) k (byte-array [10 1]) calls (atom 0)
        validator (fn [_] (swap! calls inc))
        checked (fn []
                  (kv/transact *cache* :write
                    #((:with-validation %) prefix validator (fn [] nil))))]
    (kv/put! *cache* {:k k :v (byte-array [7])})
    (checked)
    (is (thrown? Exception
          (kv/put-batch! *cache* {:entries [{:k k :v (byte-array [99])}
                                          {:k (byte-array 600) :v (byte-array [8])}]})))
    (is (= [7] (vec (kv/get! *cache* {:k k}))))
    (checked)
    (is (= 1 @calls) "Failed overlapping mutations do not publish invalidated proofs")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"entry realization failed"
          (kv/put-batch! *cache*
            {:entries (lazy-seq (cons {:k k :v (byte-array [99])}
                                    (lazy-seq (throw (ex-info "entry realization failed" {})))))})))
    (is (= [7] (vec (kv/get! *cache* {:k k}))))
    (checked)
    (is (= 1 @calls))))

(deftest legacy-writes-reject-nesting-and-work-without-validation-capability
  (doseq [mode [:read :write]
          write! [(fn [cache] (kv/put! cache {:k (byte-array [21]) :v (byte-array [8])}))
                  (fn [cache] (kv/put-batch! cache {:entries [{:k (byte-array [21]) :v (byte-array [8])}]}))]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nested"
          (kv/transact *cache* mode
            (fn [tx]
              (when (= mode :write) ((:put tx) (byte-array [20]) (byte-array [7])))
              (write! *cache*)))))
    (is (nil? (kv/get! *cache* {:k (byte-array [20])})))
    (is (nil? (kv/get! *cache* {:k (byte-array [21])}))))
  (let [cache (dissoc *cache* :ai.obney.grain.kv-store-lmdb.core/validation-state)]
    (is (true? (kv/put! cache {:k (byte-array [20]) :v (byte-array [7])})))
    (is (nil? (kv/put-batch! cache {:entries [{:k (byte-array [21]) :v (byte-array [8])}]})))
    (is (= [7] (vec (kv/get! cache {:k (byte-array [20])}))))
    (is (= [8] (vec (kv/get! cache {:k (byte-array [21])}))))))

(deftest concurrent-legacy-writes-preserve-proofs-but-native-commits-do-not
  (let [prefix (byte-array [10]) k (byte-array [10 1]) calls (atom 0)
        validator (fn [tx]
                    (swap! calls inc)
                    (when-let [v ((:get tx) k)]
                      (when-not (= [7] (vec v)) (throw (ex-info "Invalid value" {})))))
        checked (fn []
                  (kv/transact *cache* :write
                    #((:with-validation %) prefix validator (fn [] nil))))]
    (checked)
    (let [writes (mapv (fn [n]
                        (future
                          (let [entry {:k (byte-array [20 n]) :v (byte-array [7])}]
                            (if (even? n) (kv/put! *cache* entry)
                                (kv/put-batch! *cache* {:entries [entry]})))))
                      (range 32))]
      (doseq [write writes] (is (not= ::timeout (deref write 10000 ::timeout)))))
    (let [before @calls]
      (checked)
      (is (<= before @calls (inc before))
          "Racing publication may conservatively lose proof and revalidate once")
      (let [settled @calls]
        (checked)
        (is (= settled @calls) "Settled evidence is reused")))
    (let [before @calls]
      (let [env ^org.lmdbjava.Env (:env *cache*) db ^org.lmdbjava.Dbi (:db *cache*)
            key (doto (java.nio.ByteBuffer/allocateDirect (.getMaxKeySize env))
                  (.put (byte-array [30])) (.flip))
            value (doto (java.nio.ByteBuffer/allocateDirect 1) (.put (byte-array [8])) (.flip))]
        (.put db key value))
      (checked)
      (is (= (inc before) @calls) "Even unrelated unknown native commits require revalidation"))
    (let [writes (mapv (fn [n]
                        (future (kv/put! *cache* {:k (if (zero? n) k (byte-array [30 n]))
                                                 :v (byte-array [99])})))
                      (range 8))]
      (doseq [write writes] (is (not= ::timeout (deref write 10000 ::timeout)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid value" (checked))
        "Racing unrelated publishers cannot restore a proof invalidated by corruption")))

(deftest scratch-reuse-preserves-lengths-and-detached-values
  (let [entries (mapv (fn [i key-size value-size]
                        {:k (byte-array (repeat key-size (inc i)))
                         :v (byte-array (repeat value-size (+ 10 i)))})
                      (range) [511 1 128 2 3 4] [8192 0 65536 70000 1 17])
        expected (mapv #(vec (:v %)) entries)]
    (doseq [write! [(fn [] (kv/put-batch! *cache* {:entries entries}))
                   (fn [] (kv/transact *cache* :write
                            (fn [tx] (doseq [{:keys [k v]} entries] ((:put tx) k v)))))]]
      (write!)
      (doseq [read! [(fn [] (kv/read-snapshot *cache*
                             (fn [get-value]
                               (mapv (fn [entry] (get-value (select-keys entry [:k]))) entries))))
                    (fn [] (kv/transact *cache* :read
                             (fn [tx] (mapv #((:get tx) (:k %)) entries))))]]
        (let [values (read!)]
          (is (= expected (mapv vec values)))
          (kv/transact *cache* :write
            (fn [tx]
              (let [k (:k (first entries)) before ((:get tx) k)]
                ((:put tx) k (byte-array [42]))
                (is (= [42] (vec ((:get tx) k))))
                ((:delete tx) k)
                (is (nil? ((:get tx) k)))
                (is (= (first expected) (vec before))))))
          (is (= expected (mapv vec values)) "Later operations do not mutate returned arrays")
          (write!))))))

(deftest scratch-reuse-supports-nested-cursors-and-point-reads
  (kv/put-batch! *cache* {:entries (mapv #(hash-map :k (.getBytes %) :v (.getBytes (str % "-value")))
                                        ["a0" "a1" "a2" "b0"])})
  (doseq [reverse? [false true]]
    (let [saved (kv/transact *cache* :read
                  (fn [tx]
                    ((:reduce tx) {:prefix (.getBytes "a") :reverse? reverse?}
                     (fn [out entry]
                       (is (= "b0-value" (String. ^bytes ((:get tx) (.getBytes "b0")))))
                       (is (nil? ((:get tx) (.getBytes "a-missing-key"))))
                       (is (= ["b0"] ((:reduce tx) {:prefix (.getBytes "b")}
                                      #(conj %1 (String. ^bytes (:k %2))) [])))
                       (conj out entry)) [])))]
      (is (= (if reverse? ["a2" "a1" "a0"] ["a0" "a1" "a2"])
             (mapv #(String. ^bytes (:k %)) saved)))
      (is (= (mapv #(str (String. ^bytes (:k %)) "-value") saved)
             (mapv #(String. ^bytes (:v %)) saved))))))

(deftest value-scratch-growth-is-bounded
  (let [write-buffer (#'core/value-buffer-writer)
        small (write-buffer (byte-array [1 2 3]))
        grown (write-buffer (byte-array 8192))
        oversized (write-buffer (byte-array (inc @#'core/max-retained-value-size)))]
    (is (not (identical? small grown)))
    (is (> (.capacity ^java.nio.ByteBuffer oversized) @#'core/max-retained-value-size))
    (is (identical? grown (write-buffer (byte-array [9]))) "Oversized buffers are not retained")
    (is (= 1 (.remaining ^java.nio.ByteBuffer grown)))
    (is (= 9 (.get ^java.nio.ByteBuffer grown)))
    (is (= 0 (.remaining ^java.nio.ByteBuffer (write-buffer (byte-array 0)))))))

(deftest sequential-scans-reuse-transaction-cursors
  (kv/put-batch! *cache* {:entries (mapv #(hash-map :k (.getBytes %) :v (.getBytes %)) ["a0" "a1" "b0"])})
  (doseq [mode [:read :write]]
    (let [cursors (atom #{}) with-cursor @#'core/with-scan-cursor
          scan (fn [tx options]
                 ((:reduce tx) options #(conj %1 (String. ^bytes (:k %2))) []))]
      (with-redefs-fn {#'core/with-scan-cursor
                      (fn [db txn spare f]
                        (with-cursor db txn spare #(do (swap! cursors conj %) (f %))))}
        #(kv/transact *cache* mode
           (fn [tx]
             (dotimes [_ 10]
               (is (= ["a0" "a1"] (scan tx {:prefix (.getBytes "a")})))
               (is (= ["a1" "a0"] (scan tx {:prefix (.getBytes "a") :reverse? true})))
               (is (= [] (scan tx {:prefix (.getBytes "missing")})))
               (is (= "b0" ((:reduce tx) {:prefix (.getBytes "b")}
                            (fn [_ row] (reduced (String. ^bytes (:k row)))) nil)))))))
      (is (= 1 (count @cursors)))
      (doseq [^org.lmdbjava.Cursor cursor @cursors]
        (is (thrown? org.lmdbjava.Cursor$ClosedException (.first cursor)))))))

(deftest reused-cursors-close-on-callback-and-reducer-failure
  (kv/put! *cache* {:k (.getBytes "a") :v (.getBytes "value")})
  (doseq [mode [:read :write] failure [:callback :reducer :caught-reducer]]
    (let [cursors (atom #{}) with-cursor @#'core/with-scan-cursor
          fail (fn [& _] (throw (ex-info "Expected scan failure" {})))]
      (with-redefs-fn {#'core/with-scan-cursor
                      (fn [db txn spare f]
                        (with-cursor db txn spare #(do (swap! cursors conj %) (f %))))}
        #(is (thrown? clojure.lang.ExceptionInfo
               (kv/transact *cache* mode
                 (fn [tx]
                   (when (= mode :write)
                     ((:put tx) (.getBytes "uncommitted") (.getBytes "value")))
                   ((:reduce tx) {} conj [])
                   (case failure
                     :callback (fail)
                     :reducer ((:reduce tx) {} fail nil)
                     :caught-reducer (try ((:reduce tx) {} fail nil)
                                          (catch clojure.lang.ExceptionInfo _ nil))))))))
      (is (= 1 (count @cursors)))
      (doseq [^org.lmdbjava.Cursor cursor @cursors]
        (is (thrown? org.lmdbjava.Cursor$ClosedException (.first cursor))))
      (is (nil? (kv/get! *cache* {:k (.getBytes "uncommitted")})))
      (is (= 1 (kv/transact *cache* :read #((:reduce %) {} (fn [n _] (inc n)) 0)))))))

(deftest shared-scratch-rejects-escaped-and-cross-thread-operations
  (let [k (.getBytes "key") escaped (atom nil)]
    (kv/put! *cache* {:k k :v (.getBytes "value")})
    (kv/read-snapshot *cache*
      (fn [get-value]
        (reset! escaped get-value)
        (is (= :rejected @(future (try (get-value {:k k}) :accepted
                                      (catch clojure.lang.ExceptionInfo _ :rejected)))))
        (is (= "value" (String. ^bytes (get-value {:k k}))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escaped" (@escaped {:k k})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"aborted"
          (kv/transact *cache* :write
            (fn [tx]
              ((:put tx) k (.getBytes "changed"))
              (is (= :rejected @(future (try ((:get tx) k) :accepted
                                            (catch clojure.lang.ExceptionInfo _ :rejected)))))))))
    (is (= "value" (String. ^bytes (kv/get! *cache* {:k k}))))))

(deftest transactional-mutations-rollback-and-lifetime
  (let [k (.getBytes "transaction-key") v (.getBytes "value") escaped (atom nil)]
    (kv/transact *cache* :write
      (fn [tx]
        (reset! escaped tx)
        ((:put tx) k v)
        (is (= "value" (String. ((:get tx) k))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escaped"
                         ((:get @escaped) k)))
    (is (thrown? Exception
                 (kv/transact *cache* :write
                   (fn [tx] ((:delete tx) k) (throw (ex-info "abort" {}))))))
    (is (= "value" (String. (kv/get! *cache* {:k k}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"aborted"
                         (kv/transact *cache* :write
                           (fn [tx]
                             ((:delete tx) k)
                             (try ((:put tx) (byte-array 600) v) (catch Exception _ nil))))))
    (is (= "value" (String. (kv/get! *cache* {:k k}))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot write"
                         (kv/transact *cache* :read #((:delete %) k))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Nested"
                         (kv/transact *cache* :read
                           (fn [_] (kv/transact *cache* :read identity)))))
    (kv/transact *cache* :write (fn [tx] ((:delete tx) k) (is (nil? ((:get tx) k)))))
    (is (nil? (kv/get! *cache* {:k k})))))

(deftest transactional-ranges-and-read-snapshots
  (kv/transact *cache* :write
    (fn [tx]
      (doseq [s ["a0" "a1" "a2" "b0"]]
        ((:put tx) (.getBytes s) (.getBytes s)))))
  (kv/transact *cache* :read
    (fn [tx]
      (let [read-keys (fn [opts] ((:reduce tx) opts #(conj %1 (String. ^bytes (:k %2))) []))]
        (is (= ["a1" "a2"] (read-keys {:prefix (.getBytes "a") :from (.getBytes "a1")})))
        (is (= ["a1" "a0"] (read-keys {:prefix (.getBytes "a") :to (.getBytes "a2") :reverse? true})))
        (is (= [] (read-keys {:from (.getBytes "b") :to (.getBytes "a")})))
        (is (= ["a0" "a1" "a2" "b0"] (read-keys {:prefix (byte-array 0)})))
        (is (= ["a0" "a1" "a2" "b0"] (read-keys {:from (byte-array 0)})))
        (is (= [] (read-keys {:to (byte-array 0) :reverse? true})))
        (is (= "a0" ((:reduce tx) {} (fn [_ entry] (reduced (String. ^bytes (:k entry)))) nil)))
        (let [write (future (kv/transact *cache* :write
                             #((:put %) (.getBytes "a3") (.getBytes "new"))))]
          (is (not= ::timeout (deref write 10000 ::timeout)))
          (is (= ["a0" "a1" "a2"] (read-keys {:prefix (.getBytes "a")})))))))
  (is (= "new" (String. (kv/get! *cache* {:k (.getBytes "a3")})))))

(deftest unsigned-byte-prefix-at-upper-bound
  (kv/transact *cache* :write
    (fn [tx]
      (doseq [k [[0] [127] [-128] [-1] [-1 0] [-1 -1]]]
        ((:put tx) (byte-array k) (byte-array [1])))))
  (kv/transact *cache* :read
    (fn [tx]
      (let [read-keys (fn [opts] ((:reduce tx) opts #(conj %1 (vec (:k %2))) []))]
        (is (= [[0] [127] [-128] [-1] [-1 0] [-1 -1]] (read-keys {})))
        (is (= [[-1] [-1 0] [-1 -1]] (read-keys {:prefix (byte-array [-1])})))
        (is (= [[-1 -1] [-1 0] [-1]]
               (read-keys {:prefix (byte-array [-1]) :reverse? true})))))))

;; ---------------------------------------------------------------------------
;; Basic put/get
;; ---------------------------------------------------------------------------

(deftest put-and-get-round-trip
  (let [k (.getBytes "test-key")
        v (.getBytes "test-value")]
    (kv/put! *cache* {:k k :v v})
    (let [result (kv/get! *cache* {:k k})]
      (is (some? result))
      (is (= "test-value" (String. result))))))

(deftest get-missing-key-returns-nil
  (is (nil? (kv/get! *cache* {:k (.getBytes "nonexistent")}))))

(deftest read-snapshot-closes-on-callback-exception
  (let [k (.getBytes "snapshot-key")
        failure (ex-info "Callback failed" {:reason ::test})]
    (kv/put! *cache* {:k k :v (.getBytes "value")})
    (let [caught (try
                   (kv/read-snapshot *cache*
                     (fn [get-value]
                       (is (= "value" (String. (get-value {:k k}))))
                       (throw failure)))
                   nil
                   (catch Exception e e))]
      (is (identical? failure caught) "The original callback exception propagates"))
    ;; A leaked read transaction prevents another transaction on this thread
    ;; with the default LMDB flags used by our cache.
    (is (= "value"
           (kv/read-snapshot *cache*
             (fn [get-value] (String. (get-value {:k k}))))))))

(deftest read-snapshot-returns-callback-result-and-closes-normally
  (let [k (.getBytes "snapshot-key")
        result (Object.)]
    (kv/put! *cache* {:k k :v (.getBytes "old")})
    (is (identical? result
                   (kv/read-snapshot *cache*
                     (fn [get-value]
                       (is (= "old" (String. (get-value {:k k}))))
                       result))))
    (is (nil? (kv/read-snapshot *cache*
                (fn [get-value]
                  (is (= "old" (String. (get-value {:k k}))))
                  nil))))
    ;; Both callbacks have released their snapshots. A new snapshot observes
    ;; the intervening commit instead of retaining the earlier view.
    (kv/put! *cache* {:k k :v (.getBytes "new")})
    (is (= "new"
           (kv/read-snapshot *cache*
             (fn [get-value] (String. (get-value {:k k}))))))))

(deftest read-snapshot-retains-values-across-concurrent-commit
  (let [a (.getBytes "snapshot-a")
        b (.getBytes "snapshot-b")
        entries (fn [value] (mapv (fn [k] {:k k :v (.getBytes value)}) [a b]))]
    (kv/put-batch! *cache* {:entries (entries "old")})
    (let [values (kv/read-snapshot
                  *cache*
                  (fn [get-value]
                    (let [first-value (get-value {:k a})
                          writer (future (kv/put-batch! *cache* {:entries (entries "new")}))]
                      (try
                        (is (not= ::timeout (deref writer 10000 ::timeout)))
                        (is (nil? (get-value {:k (.getBytes "missing")})))
                        [first-value (get-value {:k b}) (get-value {:k a})]
                        (finally (future-cancel writer))))))]
      ;; Copied values remain usable after the snapshot closes.
      (is (= ["old" "old" "old"] (mapv #(String. ^bytes %) values)))
      (is (= "new" (String. (kv/get! *cache* {:k a}))))
      (is (= "new" (String. (kv/get! *cache* {:k b})))))))

;; ---------------------------------------------------------------------------
;; put-batch!
;; ---------------------------------------------------------------------------

(deftest put-batch-writes-all-entries
  (let [entries (mapv (fn [i]
                        {:k (.getBytes (str "batch-key-" i))
                         :v (.getBytes (str "batch-value-" i))})
                      (range 10))]
    (kv/put-batch! *cache* {:entries entries})
    (doseq [i (range 10)]
      (let [result (kv/get! *cache* {:k (.getBytes (str "batch-key-" i))})]
        (is (some? result) (str "Missing key batch-key-" i))
        (is (= (str "batch-value-" i) (String. result)))))))

(deftest put-batch-is-atomic-on-success
  (let [entries [{:k (.getBytes "atom-a") :v (.getBytes "val-a")}
                 {:k (.getBytes "atom-b") :v (.getBytes "val-b")}
                 {:k (.getBytes "atom-c") :v (.getBytes "val-c")}]]
    (kv/put-batch! *cache* {:entries entries})
    (is (= "val-a" (String. (kv/get! *cache* {:k (.getBytes "atom-a")}))))
    (is (= "val-b" (String. (kv/get! *cache* {:k (.getBytes "atom-b")}))))
    (is (= "val-c" (String. (kv/get! *cache* {:k (.getBytes "atom-c")}))))))

(deftest put-batch-empty-entries
  (kv/put-batch! *cache* {:entries []})
  (is (nil? (kv/get! *cache* {:k (.getBytes "nothing")}))))

;; ---------------------------------------------------------------------------
;; Max readers
;; ---------------------------------------------------------------------------

(deftest concurrent-readers-across-threads
  (testing "200 concurrent thread readers succeed (would fail with old default of 126)"
    (kv/put! *cache* {:k (.getBytes "shared") :v (.getBytes "value")})
    (let [results (doall
                    (pmap (fn [_]
                            (try
                              (String. (kv/get! *cache* {:k (.getBytes "shared")}))
                              (catch Exception e (.getMessage e))))
                          (range 200)))]
      (is (every? #(= "value" %) results)))))

(deftest custom-max-readers-config
  (testing "max-readers config is respected"
    (let [dir (str "/tmp/kv-lmdb-maxreaders-" (random-uuid))
          cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir
                                                   :db-name "test"
                                                   :max-readers 256}))]
      (try
        (kv/put! cache {:k (.getBytes "k") :v (.getBytes "v")})
        (is (= "v" (String. (kv/get! cache {:k (.getBytes "k")}))))
        (finally
          (kv/stop cache)
          (delete-dir-recursively dir))))))

(deftest exhausted-readers-report-the-original-lmdb-error
  (let [dir (str "/tmp/kv-lmdb-exhausted-" (random-uuid))
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "test"
                                             :max-readers 1}))
        ready (promise)
        release (promise)
        holder (future
                 (kv/read-snapshot cache
                   (fn [_]
                     (deliver ready true)
                     (deref release 10000 ::timeout))))]
    (try
      (is (= true (deref ready 10000 ::timeout)))
      ;; The occupied slot is live, so readerCheck cannot reclaim it. The
      ;; retry must report ReadersFullException, not a missing Java method.
      (is (thrown? org.lmdbjava.Env$ReadersFullException
                   (kv/get! cache {:k (.getBytes "key")})))
      (finally
        (deliver release true)
        (deref holder 10000 ::timeout)
        (kv/stop cache)
        (delete-dir-recursively dir)))))

(deftest randomized-range-bounds-match-unsigned-byte-order
  ;; OPT-10: scan bound checks (changed by OPT-1 and again by OPT-10);
  ;; every range must equal a naive unsigned-order filter,
  ;; including keys that are prefixes of a bound, bounds that are prefixes of
  ;; keys, 0x00/0x7F/0x80/0xFF bytes and long shared prefixes.
  (let [r (java.util.Random. 17)
        alphabet (byte-array (map unchecked-byte [0 1 127 128 254 255]))
        shared (byte-array (repeatedly 40 #(unchecked-byte (.nextInt r 256))))
        rand-key (fn []
                   (let [tail (byte-array (.nextInt r 5))]
                     (dotimes [i (alength tail)] (aset tail i (aget alphabet (.nextInt r (alength alphabet)))))
                     (if (zero? (.nextInt r 4)) tail
                         (byte-array (concat (take (.nextInt r 41) shared) tail)))))
        cmp (fn [^bytes a ^bytes b] (java.util.Arrays/compareUnsigned a b))
        keys (->> (repeatedly 400 rand-key) (remove #(zero? (alength ^bytes %)))
                  (map vec) distinct (map byte-array) (sort cmp) vec)]
    (kv/transact *cache* :write (fn [tx] (doseq [k keys] ((:put tx) k (byte-array [1])))))
    (kv/transact *cache* :read
      (fn [tx]
        (dotimes [_ 1500]
          (let [opt (fn [] (when (pos? (.nextInt r 3)) (rand-key)))
                prefix (opt) from (opt) to (opt) reverse? (.nextBoolean r)
                options (cond-> {:reverse? reverse?}
                          (seq prefix) (assoc :prefix prefix) (seq from) (assoc :from from) (seq to) (assoc :to to))
                in? (fn [^bytes k]
                      (and (or (empty? prefix)
                               (and (>= (alength k) (alength ^bytes prefix))
                                    (= (vec prefix) (vec (take (alength ^bytes prefix) k)))))
                           (or (empty? from) (not (neg? (cmp k from))))
                           (or (empty? to) (neg? (cmp k to)))))
                expected (mapv vec (cond-> (filter in? keys) reverse? reverse))
                got ((:reduce tx) options (fn [out {:keys [k]}] (conj out (vec k))) [])]
            (is (= expected got) (pr-str (update-vals options #(if (bytes? %) (vec %) %))))))))))
