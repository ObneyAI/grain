(ns ai.obney.grain.read-model-processor-v3.concurrency-test
  (:require [clojure.test :refer :all]
            [datahike.api :as d]
            [datahike.gc-roots :as roots]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.lifetime :as life]
            [ai.obney.grain.read-model-processor-v3.fixtures :as support]
            [ai.obney.grain.read-model-processor-v3.indexed-test :as fixture]))
(use-fixtures :each fixture/test-fixture)

(deftest coherent-old-result-during-later-commit-and-collection
  (fixture/put! "a" "Original" :active 1)
  (let [old (rmp/project fixture/*context* fixture/model)
        seq-old (map val old) store (:projection-store fixture/*context*)]
    (fixture/put! "a" "Changed" :inactive 2)
    (is (= "Changed" (get-in (rmp/project fixture/*context* fixture/model) ["a" :surname])))
    (rmp/collect! store)
    (is (= "Original" (get-in old ["a" :surname])))
    (is (= [1] (mapv :balance seq-old)))
    (rmp/close-store! store)
    (is (= 1 (get-in old ["a" :balance])))
    (is (false? (:released? (rmp/store-status store))))))

(deftest collection-does-not-hold-request-gate
  (fixture/put! "a" "A" :active 1) (fixture/page)
  (let [entered (promise) release (promise) original d/gc-storage
        store (:projection-store fixture/*context*)]
    (with-redefs [d/gc-storage (fn [& args] (deliver entered true) @release (apply original args))]
      (let [gc (future (rmp/collect! store))]
        (try
          (is (= true (deref entered 10000 :timeout)))
          (let [reader (future (fixture/page))]
            (is (not= :timeout (deref reader 5000 :timeout))))
          (finally (deliver release true) @gc))))))

(deftest failed-pin-prevents-replay-until-reopen
  (fixture/put! "a" "A" :active 1) (fixture/page)
  (fixture/put! "b" "B" :active 2)
  (with-redefs [roots/pin! (fn [& _] (throw (ex-info "Injected pin failure" {:error :pin-failed})))]
    (is (= :pin-failed (support/error-code fixture/page))))
  (is (= :pin-failed (support/error-code fixture/page))))

(deftest second-owner-is-rejected
  (is (= :store-unavailable
         (support/error-code #(rmp/open-store {:storage-dir fixture/*directory* :backend :file})))))

(deftest native-pin-loss-stops-snapshot-reads-and-still-allows-close
  (fixture/put! "a" "A" :active 1)
  (let [s (rmp/project fixture/*context* fixture/model)
        store (:projection-store fixture/*context*) rt (:runtime store)
        lost (ex-info "Native lease lost" {:error :gc/root-lost})]
    ;; The on-lost callback records this error; every backed read must consult it.
    (reset! (:lease-error rt) lost)
    (is (= :gc/root-lost (support/error-code #(get s "a"))))
    (is (= :gc/root-lost (support/error-code fixture/page)))
    (rmp/close-store! store)
    (is (:closed? (rmp/store-status store)))))

(deftest reopen-completes-interrupted-schema-initialization
  (let [path (.getCanonicalPath (java.io.File. (str fixture/*directory* "/partial")))
        cfg {:store {:backend :file :path (str path "/db")
                     :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}
             :schema-flexibility :write :keep-history? false :attribute-refs? false
             :writer {:backend :self :writer-ownership :exclusive} :max-string-length 0}]
    (d/create-database cfg)
    (let [store (rmp/open-store {:storage-dir path :backend :file})]
      (try
        (is (= {} (rmp/project (assoc fixture/*context* :projection-store store) fixture/model)))
        (finally (support/release! store))))))

(def ^:dynamic *pause-reducer* false)
(def ^:dynamic *pause-pin* false)
(def ^:dynamic *pause-decode* false)

(defn await-result [worker]
  (deref worker 10000 ::timeout))

(deftest stalled-catch-up-is-local-to-projection-identity
  (fixture/put! "a" "Original" :active 1)
  (let [old (rmp/project fixture/*context* fixture/model)
        entered (promise)
        release (promise)
        other-model :indexed-test/other
        other-tenant (assoc fixture/*context* :tenant-id (random-uuid))]
    (rmp/register-read-model! fixture/model
                              (fn [state event]
                                (when *pause-reducer*
                                  (deliver entered true)
                                  @release)
                                (fixture/reducer state event))
                              fixture/options)
    (rmp/register-read-model! other-model fixture/reducer fixture/options)
    (binding [fixture/*context* other-tenant]
      (fixture/put! "other" "Other tenant" :active 2))
    (fixture/put! "b" "New" :active 3)
    (let [catch-up (future (binding [*pause-reducer* true]
                             (into {} (rmp/project fixture/*context* fixture/model))))]
      (try
        (is (= true (await-result entered)))
        (testing "an already-returned snapshot can be read during its own catch-up"
          (is (= ["Original" 1]
                 (await-result (future [(get-in old ["a" :surname]) (count (into {} old))])))))
        (testing "another model can initialize, reduce and commit"
          (is (= #{"a" "b"}
                 (await-result (future (set (keys (rmp/project fixture/*context* other-model))))))))
        (testing "another tenant of the same model can commit"
          (is (= #{"other"}
                 (await-result (future (set (keys (rmp/project other-tenant fixture/model))))))))
        (testing "another version of the same model can commit"
          (is (= #{"a" "b"}
                 (await-result
                  (future
                    (set (keys (rmp/p fixture/*context*
                                      (assoc fixture/options :name fixture/model
                                             :version 2 :f fixture/reducer)))))))))
        (testing "another query scope of the same model can commit"
          (is (= #{"a" "b"}
                 (await-result
                  (future (set (keys (rmp/project fixture/*context* fixture/model
                                                  {:queries {:types (:events fixture/options)}}))))))))
        (finally
          (deliver release true)
          (is (map? (await-result catch-up))))))
    (is (empty? @(:locks (:runtime (:projection-store fixture/*context*)))))))

(deftest same-projection-catch-up-does-not-replay-events
  (let [entered (promise)
        release (promise)
        second-started (promise)
        calls (atom 0)]
    (rmp/register-read-model! fixture/model
                              (fn [state event]
                                (swap! calls inc)
                                (deliver entered true)
                                @release
                                (fixture/reducer state event))
                              fixture/options)
    (fixture/put! "a" "A" :active 1)
    (let [first-reader (future (into {} (rmp/project fixture/*context* fixture/model)))]
      (try
        (is (= true (await-result entered)))
        (let [second-reader (future
                              (deliver second-started true)
                              (into {} (rmp/project fixture/*context* fixture/model)))]
          (is (= true (await-result second-started)))
          (is (= ::timeout (deref second-reader 200 ::timeout)))
          (deliver release true)
          (is (= (await-result first-reader) (await-result second-reader)))
          (is (= 1 @calls)))
        (finally
          (deliver release true)
          (await-result first-reader))))))

(deftest streaming-callback-does-not-block-updates
  (fixture/put! "a" "A" :active 1)
  (let [entered (promise)
        release (promise)
        reader (future
                 (rmp/reduce-records fixture/*context* fixture/model {}
                                     (fn [items item]
                                       (deliver entered true)
                                       @release
                                       (conj items item)) []))]
    (try
      (is (= true (await-result entered)))
      (fixture/put! "b" "B" :active 2)
      (is (= #{"a" "b"}
             (await-result (future (set (keys (rmp/project fixture/*context* fixture/model)))))))
      (finally
        (deliver release true)
        (is (= ["a"] (mapv :id (await-result reader))))))))

(deftest concurrent-pin-publication-does-not-regress-the-store-head
  (fixture/put! "a" "A" :active 1)
  (fixture/page)
  (let [other-model :indexed-test/other
        entered (promise)
        release (promise)
        original roots/pin!
        rt (:runtime (:projection-store fixture/*context*))]
    (rmp/register-read-model! other-model fixture/reducer fixture/options)
    (into {} (rmp/project fixture/*context* other-model))
    (fixture/put! "b" "B" :active 2)
    (with-redefs [roots/pin! (fn [& args]
                               (when *pause-pin*
                                 (deliver entered true)
                                 @release)
                               (apply original args))]
      (let [writer (future (binding [*pause-pin* true]
                             (into {} (rmp/project fixture/*context* fixture/model))))]
        (try
          (is (= true (await-result entered)))
          (is (= #{"a" "b"}
                 (await-result (future (set (keys (rmp/project fixture/*context* other-model)))))))
          ;; Exercise GC while an older transaction has committed but is not pinned.
          (is (not= ::timeout (await-result (future (rmp/collect! (:projection-store fixture/*context*))))))
          (finally
            (deliver release true)
            (is (= #{"a" "b"} (set (keys (await-result writer)))))))))
    (is (= (:max-tx @(:conn rt)) (:max-tx (:db @(:state rt)))))))

(deftest close-does-not-release-storage-under-an-admitted-reducer
  (let [entered (promise)
        release (promise)
        store (:projection-store fixture/*context*)]
    (rmp/register-read-model! fixture/model
                              (fn [state event]
                                (deliver entered true)
                                @release
                                (fixture/reducer state event))
                              fixture/options)
    (fixture/put! "a" "A" :active 1)
    (let [writer (future (into {} (rmp/project fixture/*context* fixture/model)))]
      (try
        (is (= true (await-result entered)))
        (is (not= ::timeout (await-result (future (rmp/close-store! store)))))
        (rmp/collect! store)
        (is (false? (:released? (rmp/store-status store))))
        (is (= :invalid-projection-store (support/error-code fixture/page)))
        (finally
          (deliver release true)
          (is (= #{"a"} (set (keys (await-result writer))))))))))

(deftest snapshot-decoding-does-not-block-writes-or-close
  (fixture/put! "a" "Original" :active 1)
  (let [old (rmp/project fixture/*context* fixture/model)
        store (:projection-store fixture/*context*)
        entered (promise)
        release (promise)
        original codec/decode]
    (with-redefs [codec/decode (fn [& args]
                                 (when *pause-decode*
                                   (deliver entered true)
                                   @release)
                                 (apply original args))]
      (let [reader (future (binding [*pause-decode* true] (get old "a")))]
        (try
          (is (= true (await-result entered)))
          (fixture/put! "a" "Updated" :active 2)
          (is (= "Updated"
                 (await-result
                  (future (get-in (rmp/record fixture/*context* fixture/model "a")
                                  [:item :value :surname])))))
          (is (not= ::timeout (await-result (future (rmp/close-store! store)))))
          (rmp/collect! store)
          (is (false? (:released? (rmp/store-status store))))
          (finally
            (deliver release true)
            (is (= "Original" (:surname (await-result reader))))))))))

(deftest partition-selections-share-catch-up
  (let [entered (promise)
        release (promise)
        calls (atom 0)]
    (rmp/register-read-model! fixture/model
                              (fn [state event]
                                (swap! calls inc)
                                (deliver entered true)
                                @release
                                (fixture/reducer state event))
                              (assoc fixture/options :version 2 :partition-fn :status))
    (fixture/put! "a" "A" :active 1)
    (let [active (future (into {} (rmp/project fixture/*context* fixture/model
                                               {:partition-key :active})))]
      (try
        (is (= true (await-result entered)))
        (let [inactive (future (into {} (rmp/project fixture/*context* fixture/model
                                                     {:partition-key :inactive})))]
          (is (= ::timeout (deref inactive 200 ::timeout)))
          (deliver release true)
          (is (= {} (await-result inactive)))
          (is (= #{"a"} (set (keys (await-result active)))))
          (is (= 1 @calls)))
        (finally
          (deliver release true)
          (await-result active))))))

(deftest binary-records-and-metadata-survive-reopen
  (let [payload (byte-array (map unchecked-byte (range 256)))
        store (:projection-store fixture/*context*)
        write-and-check
        (fn []
          (rmp/register-read-model! fixture/model
                                    (fn [state event]
                                      (with-meta (fixture/reducer state event) {:source :binary-test}))
                                    fixture/options)
          (fixture/append! :indexed-test/put
                           {:id "a" :record {:surname "A" :status :active :balance 1 :payload payload}})
          (let [result (rmp/project fixture/*context* fixture/model)
                db (:db @(:state (:runtime store)))]
            (is (= {:source :binary-test} (meta result)))
            (is (java.util.Arrays/equals payload ^bytes (get-in result ["a" :payload])))
            (is (false? (get-in db [:config :attribute-refs?])))
            ;; Assert the persisted representation as well as the public round trip.
            (doseq [attr [:projection/key :definition/key :record/key :record/scope :record/id
                          :record/value :record/original-id :projection/value
                          :projection/metadata :definition/value]]
              (let [values (map :v (d/datoms db :aevt attr))]
                (is (seq values))
                (is (every? bytes? values)))))
          nil)]
    (write-and-check)
    (support/release! store)
    (let [reopened (rmp/open-store {:storage-dir fixture/*directory* :backend :file})
          context (assoc fixture/*context* :projection-store reopened)]
      (try
        (is (= {:source :binary-test} (meta (rmp/project context fixture/model))))
        (is (java.util.Arrays/equals
             payload ^bytes (get-in (rmp/record context fixture/model "a") [:item :value :payload])))
        (is (= ["a"] (mapv :id (:items (rmp/page context fixture/model {:index :balance :limit 1})))))
        (finally
          (support/release! reopened))))))

(deftest old-base64-projection-store-is-rejected
  (let [path (.getCanonicalPath (java.io.File. (str fixture/*directory* "/old-base64")))
        cfg {:store {:backend :file :path (str path "/db")
                     :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}
             :schema-flexibility :write :keep-history? false :attribute-refs? true
             :writer {:backend :self :writer-ownership :exclusive} :max-string-length 0}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (d/transact conn [{:db/ident :projection/key :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                          {:db/ident :record/value :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one}])
        (finally (d/release conn))))
    (is (= :incompatible-projection-store
           (support/error-code #(rmp/open-store {:storage-dir path :backend :file}))))))

(deftest string-identifier-projection-store-is-rejected
  (let [path (.getCanonicalPath (java.io.File. (str fixture/*directory* "/string-identifiers")))
        cfg {:store {:backend :file :path (str path "/db")
                     :id (java.util.UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}
             :schema-flexibility :write :keep-history? false :attribute-refs? false
             :writer {:backend :self :writer-ownership :exclusive} :max-string-length 0}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (d/transact conn [{:db/ident :projection/key :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                          {:db/ident :record/value :db/valueType :db.type/bytes
                           :db/cardinality :db.cardinality/one}])
        (finally (d/release conn))))
    (is (= :incompatible-projection-store
           (support/error-code #(rmp/open-store {:storage-dir path :backend :file}))))))
