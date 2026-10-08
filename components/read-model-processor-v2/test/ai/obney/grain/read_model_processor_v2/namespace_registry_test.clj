(ns ai.obney.grain.read-model-processor-v2.namespace-registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.compact-cache-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv])
  (:import [java.util UUID]))

(use-fixtures :each fixture/fixture)

(defn resolver []
  (try (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.namespace-registry/resolve!)
       (catch java.io.FileNotFoundException _ nil)))

(def identities
  [[(UUID. 1 1) :registry-test/a 1]
   [(UUID. 1 2) :registry-test/a 1]
   [(UUID. 1 1) :registry-test/b 1]
   [(UUID. 1 1) :registry-test/a 2]
   [(UUID. 1 1) :grain.read-query/shared-identities 1]])

(defn bytes-map [m] (into {} (map (fn [[k v]] [k (vec v)])) m))

(deftest registry-addresses-are-compact-distinct-durable-and-order-independent
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (let [first (bytes-map (resolve! @fixture/*cache* identities))
            before (fixture/snapshot)]
        (is (= (set identities) (set (keys first))))
        (is (every? #(= 7 (count %)) (vals first)))
        (is (= (count identities) (count (set (vals first)))))
        (is (= first (bytes-map (resolve! @fixture/*cache* (reverse identities)))))
        (is (= before (fixture/snapshot)))
        (fixture/reopen!)
        (is (= first (bytes-map (resolve! @fixture/*cache* identities))))
        (is (= before (fixture/snapshot)))))))

(deftest concurrent-first-resolution-publishes-one-collision-free-address-per-identity
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (let [ready (promise)
            tasks (mapv (fn [i]
                          (future
                            @ready
                            (bytes-map (resolve! @fixture/*cache*
                                                 (conj identities [(UUID. 9 i) :registry-test/concurrent 1])))))
                        (range 12))]
        (deliver ready true)
        (let [results (mapv #(deref % 20000 ::timeout) tasks)]
          (is (not-any? #{::timeout} results))
          (when (every? map? results)
            (is (apply = (map #(select-keys % identities) results)))
            (let [all (apply merge results)]
              (is (= (+ 12 (count identities)) (count all)))
              (is (= (count all) (count (set (vals all)))))
              (fixture/reopen!)
              (is (= all (bytes-map (resolve! @fixture/*cache* (keys all))))))))))))

(deftest registry-write-failure-rolls-back-forward-reverse-and-counter-together
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (doseq [fail-at [1 2 3 4 5]]
        (let [before (fixture/snapshot)
              original kv/transact
              writes (atom 0)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected registry write failure"
                (with-redefs [kv/transact
                              (fn [cache mode f]
                                (original cache mode
                                          (fn [tx]
                                            (f (if (= mode :write)
                                                 (assoc tx :put
                                                        (fn [k v]
                                                          (when (= fail-at (swap! writes inc))
                                                            (throw (ex-info "injected registry write failure" {})))
                                                          ((:put tx) k v)))
                                                 tx)))))]
                  (resolve! @fixture/*cache* identities))))
          (is (= before (fixture/snapshot)))
          (fixture/reopen!)
          (is (= before (fixture/snapshot)))))
      (let [all (bytes-map (resolve! @fixture/*cache* identities))]
        (is (= (count identities) (count (set (vals all)))))))))

(deftest invalid-canonical-identities-cannot-partially-reserve-earlier-valid-ones
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (doseq [invalid [["tenant" :registry-test/a 1]
                       [(UUID. 1 1) :unqualified 1]
                       [(UUID. 1 1) :registry-test/a 0]
                       [(UUID. 1 1) :registry-test/a -1]
                       [(UUID. 1 1) :registry-test/a 1 "extra"]]]
        (let [before (fixture/snapshot)]
          (is (thrown? clojure.lang.ExceptionInfo (resolve! @fixture/*cache* (conj identities invalid))))
          (is (= before (fixture/snapshot))))))))

(defn registry-var [sym]
  (ns-resolve 'ai.obney.grain.read-model-processor-v2.core.namespace-registry sym))

(defn restore! [snapshot]
  (kv/transact @fixture/*cache* :write
    (fn [tx]
      (doseq [k ((:reduce tx) {} (fn [out row] (conj out (:k row))) [])]
        ((:delete tx) k))
      (doseq [[k v] snapshot] ((:put tx) (byte-array k) (byte-array v))))))

(deftest malformed-missing-inverse-and-exhausted-registry-fail-closed
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (resolve! @fixture/*cache* identities)
      (let [original (fixture/snapshot)
            counter-key @(registry-var 'counter-key)
            forward-key @(registry-var 'forward-key)
            reverse-key @(registry-var 'reverse-key)
            target (first identities)
            forward (forward-key target)
            record (codec/decode (kv/get! @fixture/*cache* {:k forward}))
            reverse (reverse-key (:ordinal record))
            alter! (fn [k value] (kv/put! @fixture/*cache* {:k k :v (codec/encode value)}))]
        (doseq [mutation [(fn [] (kv/transact @fixture/*cache* :write #((:delete %) reverse)))
                          (fn [] (kv/transact @fixture/*cache* :write #((:delete %) forward)))
                          (fn [] (alter! reverse (assoc record :identity (second identities))))
                          (fn [] (alter! forward (assoc record :ordinal -1)))
                          (fn [] (alter! counter-key {:next-id -1}))
                          (fn [] (alter! counter-key {:next-id 0}))
                          (fn [] (alter! counter-key {:next-id "bad"}))
                          (fn [] (kv/put! @fixture/*cache* {:k forward :v (byte-array [127])}))]]
          (restore! original)
          (mutation)
          (let [corrupt (fixture/snapshot)]
            (is (thrown? Exception (resolve! @fixture/*cache* identities)))
            (is (= corrupt (fixture/snapshot)))
            (fixture/reopen!)
            (is (thrown? Exception (resolve! @fixture/*cache* identities)))
            (is (= corrupt (fixture/snapshot)))))
        (restore! original)
        (alter! counter-key {:next-id @(registry-var 'max-ordinal)})
        (let [exhausted (fixture/snapshot)]
          (is (thrown? clojure.lang.ExceptionInfo
                (resolve! @fixture/*cache* (conj identities [(UUID. 8 8) :registry-test/new 1]))))
          (is (= exhausted (fixture/snapshot))))))))

(deftest canonical-identity-check-rejects-hash-collision-without-overwrite
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (with-redefs-fn {(registry-var 'digest) (fn [_] (byte-array (repeat 32 (byte 7))))}
        (fn []
          (resolve! @fixture/*cache* [(first identities)])
          (let [before (fixture/snapshot)]
            (is (thrown? clojure.lang.ExceptionInfo (resolve! @fixture/*cache* [(second identities)])))
            (is (= before (fixture/snapshot)))
            (fixture/reopen!)
            (is (thrown? clojure.lang.ExceptionInfo (resolve! @fixture/*cache* [(second identities)])))
            (is (= before (fixture/snapshot)))))))))

(deftest genuinely-exhausted-ordinal-space-preserves-existing-addresses
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (with-redefs-fn {(registry-var 'max-ordinal) 2}
        (fn []
          (let [existing (take 2 identities)
                addresses (bytes-map (resolve! @fixture/*cache* existing))
                before (fixture/snapshot)
                error (try (resolve! @fixture/*cache* [(nth identities 2)]) nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (some? error))
            (is (= :grain.read-model/namespace-exhausted (:type (ex-data error))))
            (is (= before (fixture/snapshot)))
            (fixture/reopen!)
            (is (= addresses (bytes-map (resolve! @fixture/*cache* existing))))
            (is (= before (fixture/snapshot)))))))))

(deftest lost-registry-cannot-reassign-existing-materialization-addresses
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (let [model (fixture/register! :registry-test/materialized {})
            row {:owner "retained" :score 11 :group ["a" 1] :text "alpha" :included? true}]
        (fixture/append! (fixture/ctx) [row])
        (is (= row (rmp/value (fixture/ctx) model "retained")))
        (kv/transact @fixture/*cache* :write
          (fn [tx]
            (doseq [k ((:reduce tx) {:prefix (byte-array [75 82 57])}
                       (fn [out row] (conj out (:k row))) [])]
              ((:delete tx) k))))
        (let [orphaned (fixture/snapshot)]
          (is (seq orphaned))
          (is (thrown? clojure.lang.ExceptionInfo
                (resolve! @fixture/*cache* [[(UUID. 99 99) :registry-test/unrelated 1]])))
          (is (= orphaned (fixture/snapshot)))
          (fixture/reopen!)
          (is (thrown? clojure.lang.ExceptionInfo
                (resolve! @fixture/*cache* [[(UUID. 99 99) :registry-test/unrelated 1]])))
          (is (= orphaned (fixture/snapshot))))))))

(deftest uncertain-successful-commit-is-recovered-without-a-second-allocation
  (let [resolve! (resolver)]
    (is (some? resolve!))
    (when resolve!
      (let [original kv/transact
            injected (atom false)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"uncertain commit result"
              (with-redefs [kv/transact
                            (fn [cache mode f]
                              (let [result (original cache mode f)]
                                (when (and (= mode :write) (compare-and-set! injected false true))
                                  (throw (ex-info "uncertain commit result" {})))
                                result))]
                (resolve! @fixture/*cache* identities))))
        (let [committed (fixture/snapshot)]
          (fixture/reopen!)
          (let [addresses (bytes-map (resolve! @fixture/*cache* identities))]
            (is (= (count identities) (count (set (vals addresses)))))
            (is (= committed (fixture/snapshot)))
            (fixture/reopen!)
            (is (= addresses (bytes-map (resolve! @fixture/*cache* identities))))
            (is (= committed (fixture/snapshot)))))))))
