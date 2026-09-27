(ns ai.obney.grain.read-model-processor-v2.keyed-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [clojure.java.io :as io]))

(defschemas schemas {:keyed/change [:map] :keyed/other [:map]})
(def ^:dynamic *ctx* nil)
(def ^:dynamic *directory* nil)
(def ^:dynamic *cache-handle* nil)

(defn fixture [f]
  (let [dir (str "/tmp/grain-keyed-test-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "folds"
                                             :map-size (* 64 1024 1024)}))
        registry (rmp/global-read-model-registry)]
    (binding [*ctx* {:cache cache :event-store store :tenant-id (random-uuid)
                    :event-source "keyed-tests"}
              *directory* dir
              *cache-handle* (atom cache)]
      (try (f)
           (finally
             (reset! rmp/read-model-registry* registry)
             (when-let [cache @*cache-handle*] (kv/stop cache)) (es/stop store)
             (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true)))))))
(use-fixtures :each fixture)

(defn register! [step opts]
  (let [name (keyword "keyed-test" (str (random-uuid)))]
    (rmp/register-read-model! name step (merge {:events #{:keyed/change} :version 1 :key :owner} opts))
    name))

(defn append!
  ([bodies] (append! *ctx* bodies))
  ([ctx bodies]
   (es/append (:event-store ctx)
              {:tenant-id (:tenant-id ctx)
               :events (mapv #(es/->event {:type :keyed/change :body %}) bodies)})))

(defn history-step [state event]
  (update state :history (fnil conj []) (:delta event)))
(defn total [state] {:total (reduce + 0 (:history state))})
(defn results [ctx model]
  (into {} (map (juxt :key :value)) (rmp/values ctx model)))

(defn stored-bytes []
  (kv/transact (:cache *ctx*) :read
    #((:reduce %) {} (fn [out {:keys [k v]}] (assoc out (vec k) (vec v))) {})))

(deftest pure-fold-equivalence-across-batches-and-keys
  (let [events (mapv (fn [i] {:owner (str (mod i 7)) :delta (- (mod i 13) 6)}) (range 257))
        expected (into {} (map (fn [[id events]] [id (total (reduce history-step {} events))]))
                       (group-by :owner events))]
    (append! events)
    (doseq [batch-size [1 3 128]]
      (let [name (register! history-step {:result total :indexes {:total :total}})
            ctx (assoc *ctx* :read-model/batch-size batch-size)]
        (is (= expected (results ctx name)))
        (is (= (mapv :delta (filter #(= "0" (:owner %)) events))
               (:history (rmp/state ctx name "0"))))
        (is (= expected (results ctx name)) "Repeated reads do not reapply events")))))

(deftest incremental-result-and-unchanged-result-retain-accumulator
  (let [observed (atom [])
        ctx (assoc *ctx* :read-model/observer #(swap! observed conj %))
        name (register! history-step {:result total :indexes {:total :total}})]
    (append! [{:owner "alice" :delta 4} {:owner "bob" :delta 9}])
    (is (= {:total 4} (rmp/value ctx name "alice")))
    (reset! observed [])
    (append! [{:owner "alice" :delta 0}])
    (is (= {:total 4} (rmp/value ctx name "alice")))
    (is (= 1 (count (filter #(= :state-decoded (:operation %)) @observed))))
    (is (empty? (filter #(= :result-written (:operation %)) @observed)))
    (is (= [4 0] (:history (rmp/state ctx name "alice"))))
    (append! [{:owner "alice" :delta 3}])
    (is (= {:total 7} (rmp/value ctx name "alice"))))
  (let [calls (atom 0)
        name (register! history-step {:result #(do (swap! calls inc) (total %))})]
    (rmp/value *ctx* name "alice")
    (is (= 2 @calls) "One completion per key in the batch, not per event")))

(deftest warm-result-reads-never-decode-accumulators
  (let [name (register! history-step {:result total :indexes {:total :total}})
        seen (atom []) ctx (assoc *ctx* :read-model/observer #(swap! seen conj %))]
    (append! (mapv #(hash-map :owner (str %) :delta %) (range 30)))
    (rmp/value ctx name "0")
    (reset! seen [])
    (rmp/value ctx name "5")
    (is (= 25 (:results-decoded (rmp/page ctx name {:index :total}))))
    (is (= 30 (count (into [] (rmp/values ctx name)))))
    (is (empty? (filter #(= :state-decoded (:operation %)) @seen)))
    (is (empty? (filter #(= :event-applied (:operation %)) @seen)))))

(deftest pages-equality-clamping-and-stable-ties
  (let [name (register! (fn [_ e] (select-keys e [:score :label]))
                        {:indexes {:score :score :label :label}})]
    (append! [{:owner "b" :score -5 :label "same"}
              {:owner "a" :score -5 :label "same"}
              {:owner "c" :score 8 :label "other"}])
    (is (= ["a" "b" "c"] (mapv :key (:entries (rmp/page *ctx* name {:index :score})))) )
    (is (= ["c" "a" "b"] (mapv :key (:entries (rmp/page *ctx* name {:index :score :direction :desc})))) )
    (let [p (rmp/page *ctx* name {:index :label :equals "same" :limit 1 :page 99})]
      (is (= [2 2 2] ((juxt :total :total-pages :page) p)))
      (is (= "b" (:key (first (:entries p))))))
    (append! [{:owner "a" :score 20 :label "renamed"}])
    (is (= ["b" "c" "a"] (mapv :key (:entries (rmp/page *ctx* name {:index :score})))) )
    (is (= ["b"] (mapv :key (into [] (rmp/values *ctx* name {:index :label :equals "same"})))))))

(deftest absent-and-nil-instances-are-distinct
  (let [name (register! (fn [_ _] nil) {})]
    (is (= ::missing (rmp/value *ctx* name "never" ::missing)))
    (is (= 0 (:total (rmp/page *ctx* name {}))))
    (append! [{:owner "present"}])
    (is (nil? (rmp/value *ctx* name "present" ::missing)))
    (is (nil? (rmp/state *ctx* name "present" ::missing)))
    (is (= [{:key "present" :value nil}] (:entries (rmp/page *ctx* name {}))))))

(deftest explicit-membership-preserves-state-and-atomically-updates-pages
  (let [name (register! (fn [s e] (merge s (select-keys e [:included? :label])))
                        {:include? :included? :indexes {:label :label}})]
    (append! [{:owner "a" :label "Zoe" :included? false}
              {:owner "b" :label "Bob" :included? true}])
    (is (= ::absent (rmp/value *ctx* name "a" ::absent)))
    (is (= "Zoe" (:label (rmp/state *ctx* name "a"))))
    (is (= 1 (:total (rmp/page *ctx* name {}))))
    (append! [{:owner "a" :included? true}])
    (is (= ["b" "a"] (mapv :key (:entries (rmp/page *ctx* name {:index :label})))))
    (append! [{:owner "a" :label "Alice"} {:owner "b" :included? false}])
    (doseq [direction [:asc :desc]]
      (let [p (rmp/page *ctx* name {:index :label :direction direction})]
        (is (= 1 (:total p)))
        (is (= ["a"] (mapv :key (:entries p))))))
    (is (= [] (:entries (rmp/page *ctx* name {:index :label :equals "Zoe"})) ))
    (append! [{:owner "a" :included? false}])
    (is (= 0 (:total (rmp/page *ctx* name {}))))
    (append! [{:owner "b" :included? true}])
    (is (= "Bob" (:label (rmp/value *ctx* name "b"))))
    (is (= 1 (:total (rmp/page *ctx* name {}))))))

(deftest inclusion-failure-rolls-back-state-index-count-and-progress
  (let [fail? (atom false)
        name (register! (fn [_ e] (select-keys e [:included? :label]))
                        {:include? #(if @fail? (throw (ex-info "inclusion failed" {})) (:included? %))
                         :indexes {:label #(if (:included? %) (:label %)
                                            (throw (ex-info "excluded result indexed" {})))}})]
    (append! [{:owner "a" :included? true :label "Alice"}])
    (rmp/page *ctx* name {})
    (let [before (stored-bytes)]
      (append! [{:owner "a" :included? false :label "Changed"}])
      (reset! fail? true)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inclusion failed" (rmp/page *ctx* name {})))
      (is (= before (stored-bytes)))
      (reset! fail? false)
      (is (= 0 (:total (rmp/page *ctx* name {}))))
      (is (= "Changed" (:label (rmp/state *ctx* name "a")))))))

(deftest transaction-failure-retries-from-durable-progress
  (doseq [phase [:step :result :index]]
    (let [fail? (atom true)
          name (register!
                (fn [s e]
                  (when (and @fail? (= phase :step) (= 99 (:delta e)))
                    (throw (ex-info "fail-step" {})))
                  (history-step s e))
                {:result (fn [s]
                           (when (and @fail? (= phase :result) (some #{99} (:history s)))
                             (throw (ex-info "fail-result" {}))) (total s))
                 :indexes {:total (fn [v]
                                    (when (and @fail? (= phase :index) (>= (:total v) 99))
                                      (throw (ex-info "fail-index" {}))) (:total v))}})
          ctx (assoc *ctx* :event-source (str "failure-" phase) :read-model/batch-size 1)]
      ;; An independent tenant keeps each failure scenario's history small.
      (let [ctx (assoc ctx :tenant-id (random-uuid))]
        (append! ctx [{:owner "a" :delta 1} {:owner "a" :delta 99}])
        (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a")))
        (reset! fail? false)
        (is (= {:total 100} (rmp/value ctx name "a")))
        (is (= [1 99] (:history (rmp/state ctx name "a"))))
        (is (= 1 (:total (rmp/page ctx name {:index :total}))))))))

(deftest invalid-owner-and-invalid-index-commit-nothing
  (let [name (register! history-step {:result total})]
    (append! [{:owner "a" :delta 1} {:delta 2}])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no owner" (rmp/value *ctx* name "a")))
    (is (empty? (stored-bytes))))
  (let [ctx (assoc *ctx* :tenant-id (random-uuid))
        name (register! (fn [_ e] (:label e)) {:indexes {:label identity}})]
    (append! ctx [{:owner "a" :label "ok"} {:owner "b" :label (apply str (repeat 600 "x"))}])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"511" (rmp/page ctx name {})))
    (is (empty? (stored-bytes)))))

(deftest isolation-and-scoped-read-lifetime
  (let [name (register! history-step {:result total})
        other (assoc *ctx* :tenant-id (random-uuid))]
    (append! [{:owner "a" :delta 1}])
    (append! other [{:owner "a" :delta 2}])
    (is (= {:total 1} (rmp/value *ctx* name "a")))
    (is (= {:total 2} (rmp/value other name "a")))
    (let [reads (atom 0) original es/read
          escaped (with-redefs [es/read (fn [& args] (swap! reads inc) (apply original args))]
                    (rmp/read-session [[*ctx* [name]] [other [name]]]
                      (fn [[a b]]
                        (let [before @reads]
                          (dotimes [_ 5]
                            (is (= {:total 1} (rmp/value a name "a")))
                            (is (= {:total 2} (rmp/value b name "a"))))
                          (is (= before @reads)))
                        a)))]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/value escaped name "a"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rmp/read-session *ctx* [name]
                   #(rmp/value (assoc % :tenant-id (:tenant-id other)) name "a"))))))

(deftest concurrent-readers-and-restart
  (let [name (register! history-step {:result total})]
    (append! (repeat 50 {:owner "a" :delta 1}))
    (let [workers (doall (repeatedly 12 #(future (rmp/value *ctx* name "a"))))]
      (doseq [worker workers] (is (= {:total 50} (deref worker 20000 ::timeout)))))
    ;; Reopening the same directory proves the checkpoint and both representations
    ;; are durable, not dependent on L1 or the original store handle.
    (let [cache (:cache *ctx*)]
      (kv/stop cache)
      (reset! *cache-handle* nil)
      (let [reopened (kv/start (lmdb/->KV-Store-LMDB (:config cache)))
            ctx (assoc *ctx* :cache reopened)]
        (reset! *cache-handle* reopened)
          (is (= {:total 50} (rmp/value ctx name "a")))
          (append! [{:owner "a" :delta 5}])
          (is (= {:total 55} (rmp/value ctx name "a")))
          (is (= 51 (count (:history (rmp/state ctx name "a")))))))))

(deftest declaration-and-access-validation
  (doseq [opts [{:key nil} {:key :owner :events #{}} {:key :owner :l1-ttl-ms 1}
                {:key :owner :indexes {:identity identity}} {:key :owner :result nil}
                {:key :owner :tenant-key :organization} {:key :owner :queries []}
                {:key :owner :include? false}]]
    (is (thrown? clojure.lang.ExceptionInfo (register! history-step opts))))
  (let [name (register! history-step {:result total})]
    (is (thrown? clojure.lang.ExceptionInfo (rmp/project *ctx* name)))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/value (dissoc *ctx* :event-source) name "a")))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/page *ctx* name {:limit 0})))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/page *ctx* name {:index :unknown})))))

(deftest validation-and-codec-failures-roll-back-the-entire-batch
  (doseq [phase [:state :result :codec]]
    (let [ctx (assoc *ctx* :tenant-id (random-uuid))
          name (register! history-step
                 (merge {:result total}
                        (case phase
                          :state {:state-schema [:map [:history [:vector [:int {:max 10}]]]]}
                          :result {:result-schema [:map [:total [:int {:max 10}]]]}
                          :codec {})))]
      (append! ctx [{:owner "a" :delta 1} {:owner "b" :delta 99}])
      (let [before (stored-bytes) original codec/encode]
        (if (= phase :codec)
          (with-redefs [codec/encode (fn [v]
                                      (when (= [99] (:history v))
                                        (throw (ex-info "Injected encoding failure" {})))
                                      (original v))]
            (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a"))))
          (is (thrown? clojure.lang.ExceptionInfo (rmp/value ctx name "a"))))
        (is (= before (stored-bytes)) "Includes indexes, counts and progress")
        (when (= phase :codec)
          (is (= {"a" {:total 1} "b" {:total 99}} (results ctx name))))))))

(deftest initial-state-source-and-version-isolation
  (let [name (register! (fn [s e] (+ s (:delta e))) {:initial 10})
        other-store (es/start {:conn {:type :in-memory}})
        other (assoc *ctx* :event-store other-store :event-source "another-log")]
    (try
      (append! [{:owner "a" :delta 1}])
      (append! other [{:owner "a" :delta 2}])
      (is (= 11 (rmp/value *ctx* name "a")))
      (is (= 12 (rmp/value other name "a")))
      (rmp/register-read-model! name (fn [s e] (+ s (* 2 (:delta e))))
                                {:events #{:keyed/change} :key :owner :version 2 :initial 20})
      (is (= 22 (rmp/value *ctx* name "a")))
      (is (= 24 (rmp/value other name "a")))
      (finally (es/stop other-store)))))

(deftest finite-head-and-unrelated-events
  (let [append-once? (atom true)
        name (register! (fn [s e]
                          ;; Fault-injection only: an append during catch-up must
                          ;; not extend the head captured for this request.
                          (when (compare-and-set! append-once? true false)
                            (append! [{:owner "a" :delta 2}]))
                          (history-step s e)) {:result total})]
    (append! [{:owner "a" :delta 1}])
    (is (= {:total 1} (rmp/value *ctx* name "a")))
    (is (= {:total 3} (rmp/value *ctx* name "a")))
    (let [watermark (:watermark (rmp/progress *ctx* name))]
      (es/append (:event-store *ctx*)
                 {:tenant-id (:tenant-id *ctx*)
                  :events [(es/->event {:type :keyed/other :body {}})]})
      (let [progress (rmp/progress *ctx* name)]
        (is (= watermark (:watermark progress)))
        (is (pos? (compare (:checked-through progress) watermark)))))))

(deftest result-traversal-stops-early-and-values-are-detached
  (let [name (register! history-step {:result total}) seen (atom [])
        ctx (assoc *ctx* :read-model/observer #(swap! seen conj %))]
    (append! (mapv #(hash-map :owner (str %) :delta %) (range 20)))
    (rmp/value ctx name "0")
    (reset! seen [])
    (let [entry (reduce (fn [_ v] (reduced v)) nil (rmp/values ctx name))]
      (is (= {:key "0" :value {:total 0}} entry))
      (is (= 1 (count (filter #(= :result-decoded (:operation %)) @seen)))))
    (let [escaped (rmp/read-session ctx [name] #(rmp/values % name))]
      (is (thrown? clojure.lang.ExceptionInfo (into [] escaped))))
    (is (= 20 (count (into [] (rmp/values ctx name)))))))

(deftest active-read-snapshot-allows-another-reader-to-catch-up
  (let [name (register! history-step {:result total})
        entered (promise) advanced (promise)]
    (append! [{:owner "a" :delta 1}])
    (let [old-reader (future
                       (rmp/read-session *ctx* [name]
                         (fn [ctx]
                           (let [before (rmp/value ctx name "a")]
                             (deliver entered true)
                             (deref advanced 10000 ::timeout)
                             [before (rmp/value ctx name "a")]))))]
      (is (= true (deref entered 10000 ::timeout)))
      (let [new-reader (future
                         (append! [{:owner "a" :delta 2}])
                         (let [value (rmp/value *ctx* name "a")]
                           (deliver advanced true)
                           value))]
        (try
          (is (= {:total 3} (deref new-reader 5000 ::timeout)))
          (is (= [{:total 1} {:total 1}] (deref old-reader 5000 ::timeout)))
          (finally (deliver advanced true)))))))
