(ns ai.obney.grain.kv-store-sqlite.interface-test
  (:require [clojure.test :as test :refer :all]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-sqlite.interface]
            [clojure.java.io :as io]))

(def ^:dynamic *cache* nil)

(defn test-fixture [f]
  (let [file (str "/tmp/kv-sqlite-test-" (random-uuid) ".db")
        cache (kv/start {:type :sqlite :database-file file})]
    (binding [*cache* cache]
      (try
        (f)
        (finally
          (kv/stop cache)
          (io/delete-file file true))))))

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

(deftest put-overwrites-existing-key
  (let [k (.getBytes "overwrite-key")]
    (kv/put! *cache* {:k k :v (.getBytes "first-value")})
    (kv/put! *cache* {:k k :v (.getBytes "second-value")})
    (is (= "second-value" (String. (kv/get! *cache* {:k k}))))))

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
;; Concurrency
;; ---------------------------------------------------------------------------

(deftest concurrent-readers-across-threads
  (testing "many concurrent thread readers succeed against the pooled connections"
    (kv/put! *cache* {:k (.getBytes "shared") :v (.getBytes "value")})
    (let [results (doall
                    (pmap (fn [_]
                            (try
                              (String. (kv/get! *cache* {:k (.getBytes "shared")}))
                              (catch Exception e (.getMessage e))))
                          (range 50)))]
      (is (every? #(= "value" %) results)))))

(deftest custom-table-name-config
  (testing ":table-name config is respected"
    (let [file (str "/tmp/kv-sqlite-tablename-" (random-uuid) ".db")
          cache (kv/start {:type :sqlite :database-file file :table-name "custom_kv"})]
      (try
        (kv/put! cache {:k (.getBytes "k") :v (.getBytes "v")})
        (is (= "v" (String. (kv/get! cache {:k (.getBytes "k")}))))
        (finally
          (kv/stop cache)
          (io/delete-file file true))))))
