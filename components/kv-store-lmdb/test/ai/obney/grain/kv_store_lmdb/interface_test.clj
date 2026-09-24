(ns ai.obney.grain.kv-store-lmdb.interface-test
  (:require [clojure.test :as test :refer :all]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
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
