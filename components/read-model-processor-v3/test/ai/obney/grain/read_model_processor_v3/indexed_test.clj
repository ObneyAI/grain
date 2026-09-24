(ns ai.obney.grain.read-model-processor-v3.indexed-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v3.interface.testing :as ct]
            [ai.obney.grain.read-model-processor-v3.fixtures :as fixture]
            [ai.obney.grain.fressian-util.interface :as codec]
            [clojure.java.io :as io]))

(es/defevent :indexed-test/put "Store an indexed test record."
  {:schema [:map [:id :string] [:record :any]]})
(es/defevent :indexed-test/delete "Delete an indexed test record."
  {:schema [:map [:id :string]]})

(def model :indexed-test/students)
(def options
  {:version 1 :events #{:indexed-test/put :indexed-test/delete}

   :schema [:map-of :string [:map [:surname :string] [:status :keyword] [:balance :int]]]
   :indexes {:status-name {:fields [:status :surname]} :balance {:fields [:balance]}}})
(defn reducer [records e]
  (case (:event/type e)
    :indexed-test/put (assoc records (:id e) (:record e))
    :indexed-test/delete (dissoc records (:id e))))
(def ^:dynamic *context* nil)
(def ^:dynamic *directory* nil)
(defn test-fixture [f]
  (fixture/fixture
    #(do (rmp/register-read-model! model reducer options)
         (binding [*context* fixture/*context* *directory* fixture/*directory*] (f)))))

(use-fixtures :each test-fixture)

(defn append! [type body]
  (es/append (:event-store *context*)
             {:tenant-id (:tenant-id *context*) :events [(es/->event {:type type :body body})]}))
(defn put! [id surname status balance]
  (append! :indexed-test/put {:id id :record {:surname surname :status status :balance balance}}))
(defn page
  ([] (page {}))
  ([opts] (rmp/page *context* model (merge {:index :status-name :prefix [:active] :limit 2} opts))))
(defn ids [result] (mapv :id (:items result)))
(defn error-code [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))

(deftest empty-create-move-delete-and-ordered-pages
  (is (= {:items [] :next-cursor nil :watermark nil} (page)))
  (put! "c" "Smith" :active 10)
  (put! "b" "Jones" :active -10)
  (put! "a" "Jones" :active 0)
  (put! "d" "Adams" :inactive 20)
  (let [first-page (page) last-page (page {:after (:next-cursor first-page)})]
    (is (= ["a" "b"] (ids first-page)))
    (is (= ["c"] (ids last-page)))
    (is (nil? (:next-cursor last-page)))
    (is (uuid? (:watermark first-page)))
    (is (= ["b" "a" "c" "d"] (ids (page {:index :balance :prefix [] :limit 10}))))
    (append! :indexed-test/delete {:id "b"})
    (is (= ["c"] (ids (page {:after (:next-cursor first-page)})))))
  (put! "c" "Aaron" :inactive 99)
  (is (= ["a"] (ids (page))))
  (is (= ["c" "d"] (ids (page {:prefix [:inactive]}))))
  (is (nil? (:item (rmp/record *context* model "missing"))))
  (is (= 99 (get-in (rmp/record *context* model "c") [:item :value :balance]))))

(deftest streaming-reduction-and-short-pages
  (doseq [i (range 7)] (put! (str i) (str "Name" i) :active i))
  (is (= 21 (rmp/reduce-records *context* model {:index :balance}
                               (fn [sum {:keys [value]}] (+ sum (:balance value))) 0)))
  (is (= ["0"] (rmp/reduce-records *context* model {:index :balance}
                                   (fn [a item] (reduced (conj a (:id item)))) [])))
  (let [record-bytes (alength (codec/encode {:surname "Name0" :status :active :balance 0}))
        ctx (assoc-in *context* [:projection-options :page-bytes] record-bytes)]
    (loop [after nil seen []]
      (let [result (rmp/page ctx model {:index :status-name :prefix [:active] :limit 5 :after after})]
        (is (= #{:items :next-cursor :watermark} (set (keys result))))
        (is (= 1 (count (:items result))))
        (if-let [next (:next-cursor result)]
          (recur next (into seen (ids result)))
          (is (= (mapv str (range 7)) (into seen (ids result))))))))
  (is (= :record-exceeds-budget
         (error-code #(rmp/page (assoc-in *context* [:projection-options :page-bytes] 1)
                                model {:index :balance :limit 2}))))
  (is (= :resource-budget-exceeded
         (error-code #(rmp/reduce-records (assoc-in *context* [:projection-options :reduce-bytes] 1)
                                          model {:index :balance} conj [])))))

(deftest isolation-validation-and-bounded-decoding
  (doseq [i (range 30)] (put! (str i) "Same" :active i))
  (page)
  (let [observed (atom []) result (rmp/page (assoc *context* :projection-observer #(swap! observed conj %))
                                           model {:index :balance :limit 3})]
    (is (= ["0" "1" "2"] (ids result)))
    (is (= 3 (count (filter #(= :decode (:operation %)) @observed))))
    (is (= 4 (count (filter #(= :index-entry (:operation %)) @observed))))
    (is (= :invalid-cursor (error-code #(page {:after (:next-cursor result)}))))
    (is (= :invalid-cursor
           (error-code #(rmp/page (assoc *context* :tenant-id (random-uuid)) model
                                  {:index :balance :limit 3 :after (:next-cursor result)})))))
  (is (= [] (:items (rmp/page (assoc *context* :tenant-id (random-uuid)) model {:index :balance :limit 3}))))
  (doseq [[request code] [[{:index :missing :limit 1} :unknown-index]
                          [{:index :balance :limit 0} :invalid-limit]
                          [{:index :balance} :invalid-limit]
                          [{:index :balance :prefix [:active] :limit 1} :invalid-prefix]
                          [{:index :balance :limit 1 :offset 2} :invalid-query]
                          [{:index :balance :limit 1 :after false} :invalid-cursor]
                          [{:index :balance :limit 1 :after "garbage"} :invalid-cursor]]]
    (is (= code (error-code #(rmp/page *context* model request)))))
  (is (map? (rmp/project *context* model))))

(deftest failure-rolls-back-records-indexes-and-watermark
  (put! "a" "Jones" :active 1)
  (let [before (page)]
    (put! "a" "Smith" :inactive 2)
    (append! :indexed-test/put {:id "bad" :record {:surname nil :status :active :balance 3}})
    (is (= :invalid-record-output (error-code page)))
    ;; Complete prior events stay committed; the invalid event changes nothing.
    (let [stored (ct/committed *context* model)]
      (is (= {:surname "Smith" :status :inactive :balance 2} (get-in stored [:data "a"])))
      (is (pos? (compare (:watermark stored) (:watermark before)))))))

(deftest model-version-rebuild-and-registration-failure
  (put! "a" "Alpha" :active 1)
  (put! "b" "Beta" :active 2)
  (let [first-page (page {:limit 1}) saved (get @rmp/read-model-registry* model)]
    (is (= :invalid-indexed-definition
           (error-code #(rmp/register-read-model! model reducer (assoc options :indexes [])))))
    (is (= saved (get @rmp/read-model-registry* model)))
    (is (= :definition-version-conflict
           (error-code #(rmp/register-read-model! model reducer
                                                 (assoc options :indexes {:name {:fields [:surname]}})))))
    (is (= saved (get @rmp/read-model-registry* model)))
    (rmp/register-read-model! model reducer (assoc options :version 2))
    (is (= :invalid-cursor (error-code #(page {:after (:next-cursor first-page)})))))
  (is (= ["a" "b"] (ids (page))))
  (let [token (:next-cursor (page {:limit 1}))
        path (str *directory* "/fresh")
        fresh (rmp/open-store {:storage-dir path :backend :file})]
    (try
      (is (= :invalid-cursor
             (error-code #(rmp/page (assoc *context* :projection-store fresh) model
                                    {:index :status-name :prefix [:active] :limit 1 :after token}))))
      (finally (fixture/release! fresh)))))

(deftest concurrent-catch-up-does-not-double-apply-or-lose-events
  (rmp/register-read-model!
   model
   (fn [records e]
     (update records (:id e)
             (fn [v] (-> (or v {:surname "Jones" :status :active :balance 0}) (update :balance inc)))))
   options)
  (dotimes [_ 40] (put! "a" "ignored" :active 0))
  (let [gate (promise) workers (doall (repeatedly 8 #(future @gate (page))))]
    (deliver gate true)
    (doseq [worker workers]
      (let [result (deref worker 10000 ::timeout)]
        (is (not= ::timeout result))
        (is (= 40 (get-in result [:items 0 :value :balance]))))))
  (is (= 40 (get-in (rmp/record *context* model "a") [:item :value :balance]))))

(deftest reduction-retains-one-snapshot-during-concurrent-update
  (doseq [i (range 5)] (put! (str i) "Jones" :active i))
  (let [started (atom false)
        balances
        (rmp/reduce-records
         *context* model {:index :balance}
         (fn [a {:keys [value]}]
           (when (compare-and-set! started false true)
             (let [writer (future (put! "4" "Changed" :inactive 99) (page))]
               (is (not= ::timeout (deref writer 10000 ::timeout)))))
           (conj a (:balance value))) [])]
    (is (= [0 1 2 3 4] balances))
    (is (= 99 (get-in (rmp/record *context* model "4") [:item :value :balance]))))
  (is (= :callback-failure
         (error-code #(rmp/reduce-records *context* model {:index :balance}
                                          (fn [_ _] (throw (ex-info "callback" {:error :callback-failure}))) nil))))
  (is (= 5 (rmp/reduce-records *context* model {:index :balance} (fn [n _] (inc n)) 0))))

(deftest random-updates-agree-with-independent-oracle
  (let [rng (java.util.Random. 9283) expected (atom {})]
    (dotimes [_ 100]
      (let [id (str (.nextInt rng 20)) remove? (zero? (.nextInt rng 5))]
        (if remove?
          (do (append! :indexed-test/delete {:id id}) (swap! expected dissoc id))
          (let [v {:surname (str "Name" (.nextInt rng 5))
                   :status (if (.nextBoolean rng) :active :inactive)
                   :balance (- (.nextInt rng 100) 50)}]
            (append! :indexed-test/put {:id id :record v})
            (swap! expected assoc id v)))
        (let [actual (rmp/reduce-records *context* model {:index :balance} conj [])
              ordered (sort-by (fn [[id v]] [(:balance v) id]) @expected)]
          (is (= (mapv (fn [[id v]] {:id id :value v}) ordered) actual)))
        (doseq [status [:active :inactive]]
          (is (= (->> @expected (filter #(= status (:status (val %))))
                      (sort-by (fn [[id v]] [(:surname v) id])) (take 2) (mapv key))
                 (ids (page {:prefix [status]})))))))))

(deftest time-budgets-progress-and-catch-up-resume
  (doseq [i (range 3)] (put! (str i) "Jones" :active i))
  (let [slow-context (-> *context*
                         (assoc :projection-options {:batch-events 1 :catch-up-ms 100})
                         (assoc :projection-observer (fn [{:keys [operation]}]
                                                    (when (= :batch operation) (Thread/sleep 150)))))]
    (is (= :resource-budget-exceeded (error-code #(rmp/page slow-context model {:index :balance :limit 3}))))
    (let [batches (atom [])
          result (rmp/page (assoc *context* :projection-observer #(when (= :batch (:operation %))
                                                               (swap! batches conj %)))
                           model {:index :balance :limit 3})]
      (is (= ["0" "1" "2"] (ids result)))
      (is (= 2 (reduce + (map :events @batches))) "Previously committed first event is not replayed")))
  (let [slow-decode (fn [{:keys [operation]}] (when (= :decode operation) (Thread/sleep 150)))
        ctx (-> *context* (assoc-in [:projection-options :page-ms] 100)
                 (assoc :projection-observer slow-decode))
        result (rmp/page ctx model {:index :balance :limit 3})]
    (is (= ["0"] (ids result)))
    (is (some? (:next-cursor result)))
    (is (= ["1" "2"] (ids (page {:index :balance :prefix [] :limit 3 :after (:next-cursor result)})))))
  (is (= :resource-budget-exceeded
         (error-code #(rmp/reduce-records (assoc-in *context* [:projection-options :reduce-ms] 100)
                                          model {:index :balance}
                                          (fn [n _] (Thread/sleep 150) (inc n)) 0)))))

(deftest invalid-reducer-output-and-oversized-key-are-atomic
  (put! "a" "Name" :active 1)
  (rmp/register-read-model! model (fn [records _] (assoc records "a" nil)) options)
  (is (= :invalid-record-output (error-code page)))
  (rmp/register-read-model! model reducer options)
  (page)
  (put! "a" (apply str (repeat 70000 "x")) :active 1)
  (is (= :index-key-too-large (error-code page))))

(deftest nested-index-fields-and-uuid-identities
  (let [a #uuid "00000000-0000-0000-0000-000000000001"
        b #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
        opts (assoc options
                    :version 2
                    :schema [:map-of :uuid [:map [:profile [:map [:surname :string]]]]]
                    :indexes {:nested {:fields [[:profile :surname]]}})]
    (rmp/register-read-model! model
                             (fn [records e]
                               (assoc records (java.util.UUID/fromString (:id e))
                                      {:profile {:surname (get-in e [:record :surname])}})) opts)
    (put! (str b) "Same" :active 0)
    (put! (str a) "Same" :active 0)
    (let [first-page (rmp/page *context* model {:index :nested :limit 1 :prefix ["Same"]})
          second-page (rmp/page *context* model {:index :nested :limit 1 :prefix ["Same"]
                                                :after (:next-cursor first-page)})]
      (is (= [a] (ids first-page)))
      (is (= [b] (ids second-page)))
      (is (nil? (:next-cursor second-page)))
      (is (= {:profile {:surname "Same"}} (:value (:item (rmp/record *context* model a))))))))

(deftest no-op-events-advance-watermarks-and-do-not-create-records
  (rmp/register-read-model! model (fn [records _] records) options)
  (put! "missing" "Ignored" :active 0)
  (let [result (page)]
    (is (= [] (:items result)))
    (is (uuid? (:watermark result)))
    (is (= result (page)))))

(deftest event-byte-budgets-and-no-progress-page-errors
  (doseq [i (range 3)] (put! (str i) "Jones" :active i))
  (let [events (into [] (es/read (:event-store *context*) {:tenant-id (:tenant-id *context*)}))
        allowance (apply max (map #(alength (codec/encode %)) events))
        batches (atom [])
        ctx (-> *context*
                (assoc-in [:projection-options :batch-bytes] allowance)
                (assoc :projection-observer #(when (= :batch (:operation %)) (swap! batches conj %))))]
    (is (= :event-exceeds-budget
           (error-code #(rmp/page (assoc-in ctx [:projection-options :batch-bytes] 1)
                                  model {:index :balance :limit 3}))))
    (is (empty? @batches) "An oversized event commits no batch")
    (is (= ["0" "1" "2"] (ids (rmp/page ctx model {:index :balance :limit 3}))))
    (is (= [1 1 1] (mapv :events @batches)))
    (is (every? #(<= (:event-bytes %) allowance) @batches)))
  (let [ctx (-> *context*
                (assoc-in [:projection-options :page-ms] 10)
                (assoc :projection-observer #(when (= :index-entry (:operation %)) (Thread/sleep 20))))]
    (is (= :resource-budget-exceeded
           (error-code #(rmp/page ctx model {:index :balance :limit 3}))))
    (is (= [] (:items (rmp/page ctx model {:index :status-name :prefix [:inactive] :limit 3}))))
    (is (= :initial (rmp/reduce-records ctx model {:index :status-name :prefix [:inactive]}
                                       (fn [_ _] (throw (ex-info "Empty range invoked reducer" {})))
                                       :initial)))))

(deftest catch-up-uses-a-finite-captured-event-head
  (doseq [i (range 3)] (put! (str i) "Jones" :active i))
  (let [appended? (atom false)
        ctx (-> *context*
                (assoc-in [:projection-options :batch-events] 1)
                (assoc :projection-observer
                       (fn [{:keys [operation]}]
                         (when (and (= :batch operation) (compare-and-set! appended? false true))
                           (put! "later" "Jones" :active 3)))))
        first-page (rmp/page ctx model {:index :balance :limit 10})
        next-page (page {:index :balance :prefix [] :limit 10})]
    (is @appended?)
    (is (= ["0" "1" "2"] (ids first-page)))
    (is (= ["0" "1" "2" "later"] (ids next-page)))
    (is (neg? (compare (:watermark first-page) (:watermark next-page))))))

(deftest custom-query-order-cannot-skip-or-replay-events
  (doseq [id ["a" "b" "c"]]
    (put! id id :active 0))
  (doseq [batch-size [1 2 10]
          vector-query? [false true]]
    (let [name (keyword "ordering" (str batch-size "-" vector-query?))
          query {:types #{:indexed-test/put} :reverse? true}
          scope {:queries (if vector-query? [query] query)}
          context (assoc *context* :projection-options {:batch-events batch-size})]
      (rmp/register-read-model! name
                               (fn [s e] (update s :seen (fnil conj []) (:id e))) {})
      (is (= ["a" "b" "c"] (:seen (rmp/project context name scope)))))))

(deftest registration-conflicts-preserve-definition-and-allow-new-versions
  (doseq [documented? [false true]]
    (let [name (keyword "registration" (str documented?))
          register (fn [opts]
                     (if documented?
                       (rmp/register-declared! name reducer opts
                                               {:definition/value {:description "Records" :options opts}})
                       (rmp/register-read-model! name reducer opts)))]
      (register options)
      (let [saved (get @rmp/read-model-registry* name)]
        (is (= :definition-version-conflict
               (error-code #(register (assoc options :indexes {})))))
        (is (= saved (get @rmp/read-model-registry* name))))
      (register (assoc options :version 2))
      (is (= 2 (:version (get @rmp/read-model-registry* name)))))))
