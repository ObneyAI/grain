(ns ai.obney.grain.event-store-sqlite-v3.exact-transfer-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.exact-transfer :as transfer]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.event-store-sqlite-v3.core :as sqlite]
            [ai.obney.grain.event-store-v3.interface.protocol :as protocol]
            [ai.obney.grain.fressian-util.interface :as fressian]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(deftest copies-complete-history
  (let [source (es/start {:conn {:type :in-memory}})
        file (java.io.File/createTempFile "grain-transfer" ".sqlite")
        target (es/start {:conn {:type :sqlite :database-file (.getAbsolutePath file)}})
        tenant (random-uuid)]
    (try
      (es/append source {:tenant-id tenant :events [] :tx-metadata {:reason "empty"}})
      (let [snapshot (transfer/snapshot source tenant)]
        (transfer/restore! target snapshot)
        (is (= (into [] (es/read source {:tenant-id tenant}))
               (into [] (es/read target {:tenant-id tenant}))))
        (is (= (get (es/tenants source) tenant) (get (es/tenants target) tenant))))
      (finally (es/stop source) (es/stop target) (.delete file)))))

(ai.obney.grain.schema-util.interface/defschemas transfer-test-schemas
  {:transfer/a [:map] :other/b [:map]})

(defn event [type body] (es/->event {:type type :body body}))
(defn raw [store tenant] (into [] (es/read store {:tenant-id tenant})))
(defn with-stores [f]
  (let [source (es/start {:conn {:type :in-memory}})
        file (java.io.File/createTempFile "grain-transfer" ".sqlite")
        target (es/start {:conn {:type :sqlite :database-file (.getAbsolutePath file)}})]
    (try (f source target) (finally (es/stop source) (es/stop target) (.delete file)))))

(deftest preserved-values-and-refusal
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid) bytes (byte-array [1 2 3])]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:blob bytes}) (event :other/b {:value 4})]
                           :tx-metadata {:reason "transfer"}})
        (es/append source {:tenant-id tenant :events []})
        (let [snap (transfer/snapshot source tenant)
              expected (raw source tenant)]
          (aset-byte bytes 0 (byte 9))
          (is (= [1 2 3] (vec (get-in snap [:events 0 :blob]))))
          (transfer/restore! target snap)
          (let [restored (raw target tenant)]
            (is (= [1 2 3] (vec (:blob (first restored)))))
            (is (= (mapv #(dissoc % :blob) expected) (mapv #(dissoc % :blob) restored)))
            (is (= (:watermark snap) (get-in (es/tenants target) [tenant :tenant/last-event-id])))
            (is (thrown? Exception (transfer/restore! target snap)))
            (let [again (raw target tenant)]
              (is (= (mapv #(dissoc % :blob) restored) (mapv #(dissoc % :blob) again)))
              (is (= (vec (:blob (first restored))) (vec (:blob (first again))))))))))))

(deftest malformed-snapshots-refuse-without-writes
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 1})]})
        (let [snap (transfer/snapshot source tenant)
              ev (:events snap)
              bad [(assoc snap :watermark (random-uuid))
                   (assoc snap :events (pop ev))
                   (assoc-in snap [:events 1 :event-ids] #{})
                   (assoc-in snap [:events 1 :event-ids] #{(random-uuid)})
                   (assoc-in snap [:events 0 :event/id] (:event/id (second ev)))
                   (assoc-in snap [:events 0 :event/timestamp] (.minusSeconds (:event/timestamp (first ev)) 1))
                   (assoc-in snap [:events 0 :event/type] :grain.retention/compacted)
                   (assoc-in snap [:events 0 :bad] (Object.))]]
          (doseq [s bad]
            (is (thrown? Exception (transfer/restore! target s)))
            (is (empty? (es/tenants target)))))))))

(deftest source-and-target-cuts-and-append
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 1})]})
        (let [snap (transfer/snapshot source tenant)]
          (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 2})]})
          (transfer/restore! target snap)
          (is (= (:events snap) (raw target tenant)))
          (is (< (count (raw target tenant)) (count (raw source tenant))))
          (let [result (es/append target {:tenant-id tenant :events [(event :other/b {:x 3})]})]
            (is (clj-uuid/> (:event/id (first result)) (:watermark snap)))
            (is (= :cognitect.anomalies/conflict
                   (:cognitect.anomalies/category
                     (es/append target {:tenant-id tenant :events [(event :other/b {})]
                                        :cas {:predicate-fn (constantly false)}}))))))))))

(deftest rollback-during-insert
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 1})]})
        (let [snap (transfer/snapshot source tenant)
              insert-var (ns-resolve 'ai.obney.grain.event-store-sqlite-v3.core 'insert-events!)
              original @insert-var]
          (is (thrown? Exception
                (with-redefs-fn {insert-var (fn [conn tid events]
                                             (original conn tid events)
                                             (throw (ex-info "injected after insert" {})))}
                  #(transfer/restore! target snap))))
          (is (empty? (es/tenants target)))
          (is (empty? (raw target tenant)))
          (transfer/restore! target snap)
          (is (= (:events snap) (raw target tenant))))))))

(deftest competing-target-append
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid) other (random-uuid)
            entered (promise) release (promise)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {})]})
        (let [snap (transfer/snapshot source tenant)
              insert-var (ns-resolve 'ai.obney.grain.event-store-sqlite-v3.core 'insert-events!)
              original @insert-var]
          (with-redefs-fn {insert-var (fn [conn tid events]
                                      (when (= tid tenant) (deliver entered true) @release)
                                      (original conn tid events))}
            (fn []
              (let [restoring (future (transfer/restore! target snap))]
                (is (= true (deref entered 5000 :timeout)))
                (let [appending (future (es/append target {:tenant-id tenant :events [(event :other/b {})] :cas {:predicate-fn #(empty? (into [] %))}}))]
                  (deliver release true)
                  (is (= tenant (:tenant-id (deref restoring 5000 :timeout))))
                  (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category (deref appending 5000 :timeout))))
                  (is (= (:events snap) (raw target tenant)))
                  (is (thrown? Exception (transfer/restore! target snap))))))))))))

(deftest snapshot-captures-one-state
  (with-stores
    (fn [source _]
      (let [tenant (random-uuid) entered (promise) release (promise)
            validate transfer/validate!]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:n 1})]})
        (with-redefs [transfer/validate! (fn [snapshot]
                                          (deliver entered true)
                                          @release
                                          (validate snapshot))]
          (let [reading (future (transfer/snapshot source tenant))]
            (is (= true (deref entered 5000 :timeout)))
            (es/append source {:tenant-id tenant :events [(event :other/b {:n 2})]})
            (deliver release true)
            (let [snap (deref reading 5000 :timeout)]
              (is (= 2 (count (:events snap))))
              (is (= (:watermark snap) (:event/id (peek (:events snap)))))
              (is (= 4 (count (raw source tenant)))))))))))

(defn runtime-classpath []
  ;; Polylith adds component paths via a DynamicClassLoader, outside java.class.path.
  (let [loaders (take-while some? (iterate #(.getParent ^ClassLoader %) (clojure.lang.RT/baseLoader)))
        urls (mapcat #(when (instance? java.net.URLClassLoader %) (.getURLs ^java.net.URLClassLoader %)) loaders)]
    (str/join java.io.File/pathSeparator
              (cons (System/getProperty "java.class.path")
                    (map #(let [uri (.toURI %)]
                            (.getAbsolutePath (if (.isOpaque uri) (java.io.File. (.getSchemeSpecificPart uri)) (java.io.File. uri))))
                         (filter #(= "file" (.getProtocol %)) urls))))))

(deftest fresh-jvm-reopen
  (let [source (es/start {:conn {:type :in-memory}})
        db (java.io.File/createTempFile "grain-transfer-jvm" ".sqlite")
        expected (java.io.File/createTempFile "grain-transfer-snapshot" ".bin")
        tenant (random-uuid)
        target (es/start {:conn {:type :sqlite :database-file (.getAbsolutePath db)}})]
    (try
      (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 123}) (event :other/b {:blob (byte-array [1 2 3])})]
                         :tx-metadata {:request "abc"}})
      (let [snap (transfer/snapshot source tenant)]
        (with-open [out (io/output-stream expected)] (.write out (fressian/encode snap)))
        (transfer/restore! target snap)
        (es/stop target)
        (let [expr (str "(require '[ai.obney.grain.event-store-v3.interface :as es] "
                        "'[ai.obney.grain.event-store-sqlite-v3.interface] "
                        "'[ai.obney.grain.fressian-util.interface :as f]) "
                        "(let [s (es/start {:conn {:type :sqlite :database-file " (pr-str (.getAbsolutePath db)) "}}) "
                        "x (f/decode (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get " (pr-str (.getAbsolutePath expected)) " (make-array String 0)))) "
                        "v (into [] (es/read s {:tenant-id (:tenant-id x)})) "
                        "same? (deref (ns-resolve 'ai.obney.grain.event-store-v3.interface.exact-transfer 'same-value?))] "
                        "(when-not (and (same? (:events x) v) (= (:watermark x) (get-in (es/tenants s) [(:tenant-id x) :tenant/last-event-id]))) (System/exit 2)) "
                        "(es/stop s) (println :reopen-ok))")
              proc (-> (ProcessBuilder. ^java.util.List [(str (System/getProperty "java.home") "/bin/java") "-cp" (runtime-classpath) "clojure.main" "-e" expr])
                       (.redirectErrorStream true) (.start))
              output (future (slurp (.getInputStream proc)))]
          (is (.waitFor proc 45 java.util.concurrent.TimeUnit/SECONDS))
          (is (= 0 (.exitValue proc)) (deref output 1000 "timeout"))
          (is (clojure.string/includes? @output ":reopen-ok"))))
      (finally (es/stop source) (.delete db) (.delete expected)))))

(deftest serialization-failure-rolls-back
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {:x 1})]})
        (let [snap (transfer/snapshot source tenant) encode fressian/encode calls (atom 0)]
          (is (thrown? Exception
                (with-redefs [fressian/encode (fn [x]
                                                (if (= 2 (swap! calls inc))
                                                  (throw (ex-info "injected serialization failure" {}))
                                                  (encode x)))]
                  (transfer/restore! target snap))))
          (is (empty? (es/tenants target)))
          (is (empty? (raw target tenant))))))))

(deftest unsupported-values-and-conflicting-target
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid) other (random-uuid)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {})]})
        (let [snap (transfer/snapshot source tenant)]
          (es/append target {:tenant-id other :events [(event :other/b {})]})
          (let [before (raw target other)]
            (is (thrown? Exception (transfer/restore! target snap)))
            (is (= before (raw target other)))
            (is (empty? (raw target tenant))))
          (is (thrown? Exception (transfer/validate! (assoc-in snap [:events 0 :bad] (java.util.Date.)))))
          (is (thrown? Exception (transfer/snapshot source (random-uuid)))))))))

(deftest restore-emits-no-publication-and-append-stays-guarded
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid) calls (atom []) target (assoc-in target [:config :event-pubsub] ::publisher)]
        (es/append source {:tenant-id tenant :events [(event :transfer/a {})]})
        (let [snap (transfer/snapshot source tenant)]
          (with-redefs [ai.obney.grain.pubsub.interface/pub (fn [_ value] (swap! calls conj value))]
            (transfer/restore! target snap)
            (is (empty? @calls))
            (is (= :cognitect.anomalies/incorrect
                   (:cognitect.anomalies/category
                     (es/append target {:tenant-id tenant :events [(first (:events snap))]}))))
            (is (empty? @calls))
            (es/append target {:tenant-id tenant :events [(event :transfer/a {})]})
            (is (= 1 (count @calls)))))))))

(deftest historical-unknown-types-and-unsupported-source-values
  (with-stores
    (fn [source target]
      (let [tenant (random-uuid) tag [:entity (random-uuid)]]
        ;; Backend injection represents already persisted history whose schema is no longer loaded.
        (protocol/append source {:tenant-id tenant :events [{:event/type :historical/unregistered :event/tags #{tag} :value "retained"}]})
        (let [snap (transfer/snapshot source tenant)]
          (transfer/restore! target snap)
          (is (= (:events snap) (raw target tenant)))
          (is (= #{tag} (:event/tags (first (raw target tenant))))))
        (protocol/append source {:tenant-id tenant :events [{:event/type :historical/unregistered :event/tags #{} :value (java.util.Date.)}]})
        (is (thrown? Exception (transfer/snapshot source tenant)))))))
