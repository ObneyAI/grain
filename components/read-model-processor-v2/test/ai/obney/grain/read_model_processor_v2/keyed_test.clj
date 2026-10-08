(ns ai.obney.grain.read-model-processor-v2.keyed-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.core.ranked-set :as ranked]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.schema-util.interface :as schemas :refer [defschemas]]
            [malli.core :as m]
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
    (binding [*ctx* {:cache cache :event-store store :tenant-id (random-uuid)}
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

(defn page-keys [ctx name options]
  (mapv :key (:entries (rmp/page ctx name options))))

(deftest declaration-options-must-have-an-effect
  (doseq [[opts invalid] [[{:key :owner :include some?} #{:include}]
                          [{:key :owner :index {:name :name}} #{:index}]
                          [{:key :owner :l1-ttl-ms 1000} #{:l1-ttl-ms}]
                          [{:indexes {:name :name}} #{:indexes}]
                          [{:initial []} #{:initial}]
                          [{:result identity} #{:result}]
                          [{:state-schema :map} #{:state-schema}]
                          [{:key :owner :ranked-indexes #{:identity}} #{:ranked-indexes}]
                          [{:filtered-indexes #{:identity}} #{:filtered-indexes}]]]
    (let [name (keyword "invalid" (str (random-uuid)))
          error (try (rmp/register-read-model! name identity (merge {:events #{:keyed/change}} opts))
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= invalid (:options error)))
      (is (not (contains? (rmp/global-read-model-registry) name))))))

(deftest filtered-indexes-use-the-declared-stored-order
  (let [name (register! (fn [_ e] (select-keys e [:name]))
                        {:indexes {:name :name} :filtered-indexes #{:name}})]
    (append! [{:owner "a" :name "Alice"} {:owner "b" :name "Bob"} {:owner "c" :name "Chris"}])
    (is (= ["c"] (:keys (rmp/filtered-key-page *ctx* name ["a" "c"] {:index :name :page 2 :limit 1}))))
    (is (= ["a" "c"] (:keys (rmp/filtered-key-page *ctx* name ["a" "c"] {:index :name}))))))

(deftest option-errors-explain-the-model-contract
  (doseq [[opts unknown inapplicable correction]
          [[{:key :owner :index {}} #{:index} #{} #"Supported options"]
           [{:indexes {}} #{} #{:indexes} #"exactly one of :key or :route"]
           [{:key :owner :l1-ttl-ms 1} #{} #{:l1-ttl-ms} #"ordinary models read with project"]]]
    (let [error (try (rmp/register-read-model! :test/customer identity
                                              (assoc opts :events #{:keyed/change}))
                     (catch clojure.lang.ExceptionInfo e e))
          data (ex-data error)]
      (is (= :test/customer (:model data)))
      (is (= unknown (:unknown-options data)))
      (is (= inapplicable (:inapplicable-options data)))
      (is (some #{:events} (:supported-options data)))
      (is (re-find correction (or (:hint data) "")))
      (is (re-find #":test/customer" (or (ex-message error) ""))))))

(deftest read-only-index-composition
  (let [name (register! (fn [_ e] (select-keys e [:group :included?]))
                        {:include? :included? :indexes {:group :group}})
        observed (atom []) ctx (assoc *ctx* :read-model/observer #(swap! observed conj %))]
    (append! [{:owner "a" :group nil :included? true}
              {:owner "b" :group "same" :included? true}
              {:owner "d" :group "same" :included? true}
              {:owner "excluded" :group "else" :included? false}])
    (rmp/read-session ctx [name]
      (fn [ctx]
        (is (= [{:value nil :total 1} {:value "same" :total 2}] (rmp/index-groups ctx name :group)))
        (reset! observed [])
        (is (= [{:value "same" :total 2} {:value nil :total 1}]
               (rmp/index-groups ctx name :group ["same" nil "absent" "same"])))
        (is (= 3 (count (filter #(= :index-group (:operation %)) @observed))))
        (is (= [] (rmp/index-groups ctx name :group [])))
        (is (= [{:value "same" :total 2}] (rmp/index-groups ctx name :group #{"same" "absent"})))
        (doseq [direction [:asc :desc]]
          (let [selection {:index :group :equals "same" :direction direction}]
            (reset! observed [])
            (is (= ["b"] (rmp/index-keys ctx name (assoc selection :limit 1))))
            (is (= ["b" "d"] (rmp/index-keys ctx name (assoc selection :offset 0 :limit 10))))
            (is (= [] (rmp/index-keys ctx name (assoc selection :equals "missing"))))
            (is (= 3 (count (filter #(= :index-entry (:operation %)) @observed))))
            (is (empty? (filter #(= :rank-entry (:operation %)) @observed))
                "A group's first slice needs no rank navigation")
            (is (= ["d"] (rmp/index-keys ctx name (assoc selection :offset 1 :limit 1))))
            (is (= [] (rmp/index-keys ctx name (assoc selection :offset 2))))
            (is (= {:rank 1 :present? true} (rmp/index-rank ctx name selection "d")))
            (is (= {:rank 1 :present? false} (rmp/index-rank ctx name selection "c")))
            (is (= {:rank 0 :present? false} (rmp/index-rank ctx name (assoc selection :equals "missing") "c")))
            (is (empty? (filter #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))))))
    (append! [{:owner "b" :group nil :included? true} {:owner "a" :group nil :included? false}])
    (is (= [{:value nil :total 1} {:value "same" :total 1}] (rmp/index-groups ctx name :group)))
    (is (= ["b"] (rmp/index-keys ctx name {:index :group :equals nil})))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/index-keys ctx name {:index :group})))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/index-keys ctx name {:equals nil :offset -1})))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/index-groups ctx name :unknown)))))

(deftest forward-index-values-are-metadata-only
  (let [name (register! (fn [_ e] (select-keys e [:group :included?]))
                        {:include? :included? :indexes {:group :group}})
        observed (atom []) ctx (assoc *ctx* :read-model/observer #(swap! observed conj %))]
    (append! [{:owner "a" :group nil :included? true}
              {:owner "b" :group "same" :included? true}
              {:owner "excluded" :group "other" :included? false}])
    (rmp/read-session ctx [name]
      (fn [prepared]
        (reset! observed [])
        (is (= {"a" {:group nil} "b" {:group "same"}}
               (into {} (map (juxt :key :value)) (rmp/index-values prepared name [:group]))))
        (is (= [{:key "b" :value {:group "same" :identity "b"}}]
               (into [] (rmp/index-values prepared name [:group :identity] ["b" "absent" "b" "excluded"]))))
        (is (= [{:key "b" :value {:group "same"}}]
               (into [] (rmp/index-values prepared name [:group] #{"b" "absent" "excluded"}))))
        (is (empty? (filter #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))
        (reset! observed [])
        (is (= 1 (count (into [] (take 1) (rmp/index-values prepared name [:group])))))
        (is (= 1 (count (filter #(= :sort-key-read (:operation %)) @observed))))
        (reset! observed [])
        (let [calls (atom [])
              options {:indexes [:group] :sort-value (fn [id values]
                                                       (swap! calls conj id)
                                                       (or (:group values) "zero")) :limit 1}]
          (is (= ["b"] (:keys (rmp/key-page prepared name options))))
          (is (= #{"a" "b"} (set @calls)))
          (is (= 2 (count @calls)))
          (is (= 2 (count (filter #(= :sort-key-read (:operation %)) @observed))))
          (is (= ["b"] (:keys (rmp/key-page prepared name ["a" "b" "b"] options)))))))
    (append! [{:owner "a" :group "changed" :included? true}
              {:owner "b" :group "same" :included? false}])
    (is (= [{:key "a" :value {:group "changed"}}] (into [] (rmp/index-values ctx name [:group]))))
    (is (= [] (into [] (rmp/index-values ctx name [:group] []))))
    (is (= [] (into [] (rmp/index-values (assoc ctx :tenant-id (random-uuid)) name [:group]))))
    (is (thrown? Exception (rmp/index-values ctx name [:absent])))
    (is (thrown? Exception (rmp/index-values ctx name [:group] nil)))))

(deftest forward-metadata-reductions-are-detached-and-reusable
  (let [fields (mapv #(keyword (str "field-" %)) (range 12))
        first-values (zipmap fields (range 12))
        second-values (zipmap fields (repeat nil))
        name (register! (fn [_ event] (select-keys event fields))
                        {:indexes (zipmap fields fields)})]
    (append! [(assoc first-values :owner 1) (assoc second-values :owner 2)])
    (rmp/read-session *ctx* [name]
      (fn [ctx]
        (let [rows (rmp/index-values ctx name fields [1N 1 2N 2])
              expected [{:key 1 :value first-values} {:key 2 :value second-values}]
              retained (into [] rows)]
          (is (= expected retained))
          (is (= expected (into [] rows)) "Each reduction has fresh duplicate tracking")
          (is (= expected retained) "Later decoding cannot mutate retained metadata")
          (is (= [{:key 1 :value {}}] (into [] (rmp/index-values ctx name [] [1]))))
        (is (= [{:key 1 :value first-values}]
               (into [] (take 1)
                     (rmp/index-values ctx name fields
                                       (concat [1] (lazy-seq (throw (ex-info "Unused identities were consumed" {})))))))))))))

(deftest candidate-filtering-shares-metadata-with-ordering
  (let [name (register! (fn [_ e] (select-keys e [:score :included?]))
                        {:include? :included? :indexes {:score :score}})
        observed (atom []) ctx (assoc *ctx* :read-model/observer #(swap! observed conj %))
        predicates (atom []) orders (atom [])
        options {:indexes [:score] :index :score :page 99 :limit 1
                 :include? (fn [id values] (swap! predicates conj [id values]) (some? (:score values)))
                 :sort-value (fn [id values] (swap! orders conj id) (:score values))}]
    (append! [{:owner "a" :score 2 :included? true}
              {:owner "b" :score nil :included? true}
              {:owner "c" :score 1 :included? true}
              {:owner "excluded" :score 0 :included? false}])
    (rmp/read-session ctx [name]
      (fn [prepared]
        (reset! observed [])
        (let [page (rmp/key-page prepared name ["a" "b" "a" "absent" "excluded" "c"] options)]
          (is (= ["a"] (:keys page)))
          (is (= [2 2 2 3] (mapv page [:total :total-pages :page :sort-keys-read])))
          (is (= [["a" {:score 2}] ["b" {:score nil}] ["c" {:score 1}]] @predicates))
          (is (= ["a" "c"] @orders))
          (is (= 3 (count (filter #(= :sort-key-read (:operation %)) @observed))))
          (is (empty? (filter #(#{:result-decoded :state-decoded} (:operation %)) @observed))))
        (is (= ["c"] (:keys (rmp/key-page prepared name (assoc options :direction :desc)))))
        (is (= ["c"] (:keys (rmp/key-page prepared name (dissoc (assoc options :page 1) :sort-value)))))
        (is (= {:keys [] :total 0 :total-pages 1 :page 1 :page-size 1 :sort-keys-read 3}
               (rmp/key-page prepared name (assoc options :include? (constantly false)))))
        (is (= ["a"] (:keys (rmp/key-page prepared name {:index :score :include? (fn [_ score] (= 2 score))}))))))
    (is (empty? (:keys (rmp/key-page (assoc ctx :tenant-id (random-uuid)) name options))))
    (is (thrown? Exception (rmp/key-page ctx name {:include? 42})))
    (append! [{:owner "c" :score nil :included? true}])
    (is (= ["a"] (:keys (rmp/key-page ctx name options))))))

(deftest incompatible-storage-is-ignored-by-normal-materialization
  (let [name (register! (fn [_ e] (:group e)) {:indexes {:group identity}})
        current-prefix (key/prefix *ctx* name 1)
        old-prefix (aclone current-prefix)]
    (aset-byte old-prefix 2 (byte 50))
    (append! [{:owner "a" :group "same"} {:owner "b" :group "same"}])
    (let [head (:event/id (first (into [] (es/read (:event-store *ctx*)
                                                 {:tenant-id (:tenant-id *ctx*) :reverse? true :limit 1}))))
          old-key (key/join old-prefix (byte-array [0]))
          old-progress {:watermark head :checked-through head}]
      (kv/transact (:cache *ctx*) :write #((:put %) old-key (codec/encode old-progress)))
      (is (= ["a" "b"] (page-keys *ctx* name {})))
      (is (= [{:value "same" :total 2}] (rmp/index-groups *ctx* name :group)))
      (is (= old-progress (kv/transact (:cache *ctx*) :read #(codec/decode ((:get %) old-key)))))
      (let [before (stored-bytes)]
        (rmp/index-groups *ctx* name :group)
        (is (= before (stored-bytes)) "Warm reads do not rewrite materialized data")))))

(deftest ranked-pages-match-oracle-through-membership-changes
  (let [name (register! (fn [_ e] (select-keys e [:score :included?]))
                        {:include? :included? :indexes {:score :score}})
        scores [nil false true "" "a" "a\u0000" "aa" "\uffff" -10 0 10
                :a :b #uuid "00000000-0000-0000-0000-000000000001"
                [] [nil] [nil "a"] ["a" 1] ["a\u0000" 2] ["aa" 3]
                [true "a"] [true "b"] [false "a"] [["a"] 1] [["a" 1] 2]]
        ids (vec (concat [1 "1" :a] (map #(str "id-" %) (range 80)) (repeatedly 5 random-uuid)))
        expected (atom {}) random (java.util.Random. 33)
        compare-rows (fn [direction [id-a a] [id-b b]]
                       (let [c (java.util.Arrays/compareUnsigned ^bytes (key/encode (:score a))
                                                               ^bytes (key/encode (:score b)))]
                         (if (zero? c)
                           (let [c (compare (str id-a) (str id-b))]
                             (if (zero? c)
                               (java.util.Arrays/compareUnsigned ^bytes (key/encode id-a) ^bytes (key/encode id-b)) c))
                           (if (= direction :desc) (- c) c))))]
    (dotimes [_ 5]
      (let [events (mapv (fn [id] {:owner id :score (nth scores (.nextInt random (count scores)))
                                  :included? (.nextBoolean random)}) ids)]
        (append! events)
        (doseq [{:keys [owner] :as event} events]
          (swap! expected assoc owner (select-keys event [:score :included?]))))
      (doseq [direction [:asc :desc]
              selection (concat [{}] (map #(hash-map :equals %) (conj scores "absent"))
                                (map #(hash-map :prefix %)
                                     [[] [nil] [true] [false] ["a"] ["a\u0000"] ["aa"]
                                      [["a"]] [["a" 1]] ["a" 1] ["absent"]]))]
        (let [rows (->> @expected
                        (filter (fn [[_ v]] (and (:included? v)
                                                (or (not (contains? selection :equals))
                                                    (= (:score v) (:equals selection)))
                                                (or (not (contains? selection :prefix))
                                                    (and (vector? (:score v))
                                                         (<= (count (:prefix selection)) (count (:score v)))
                                                         (= (:prefix selection)
                                                            (subvec (:score v) 0 (count (:prefix selection)))))))))
                        (sort (partial compare-rows direction)))
              expected-keys (mapv first rows)
              options (merge {:index :score :direction direction} selection)]
          (is (= expected-keys (mapv :key (into [] (rmp/values *ctx* name options)))))
          (doseq [requested [1 2 999]]
            (let [page (rmp/page *ctx* name (assoc options :page requested :limit 7))
                  page-number (min requested (max 1 (quot (+ 6 (count rows)) 7)))]
              (is (= (count rows) (:total page)))
              (is (= page-number (:page page)))
              (is (= (vec (take 7 (drop (* 7 (dec page-number)) expected-keys)))
                     (mapv :key (:entries page)))))))))
    (is (= @expected (into {} (map (fn [id] [id (rmp/state *ctx* name id)])) ids)))))

(deftest tuple-prefix-pages-seek-and-count-without-scanning-matches
  (let [name (register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})
        random (java.util.Random. 72)
        seen (atom []) ctx (assoc *ctx* :read-model/observer #(swap! seen conj %))]
    (with-redefs [ranked/random-hash (fn [_] (.nextInt random))]
      (append! (mapv #(hash-map :owner (format "%05d" %)
                                :score [(< % 3000) (quot % 2)]) (range 4096)))
      (rmp/page ctx name {}))
    (doseq [direction [:asc :desc]]
      (reset! seen [])
      (let [page (rmp/page ctx name {:index :score :prefix [true] :direction direction :page 120})]
        (is (= 3000 (:total page)))
        (is (= 120 (:page page) (:total-pages page)))
        (is (= (if (= direction :asc) "02975" "00025") (:key (first (:entries page)))))
        (is (= 25 (:rows-examined page) (:results-decoded page)))
        (is (< (:rank-entries-examined page) 1000))
        (is (= 25 (count (filter #(= :result-decoded (:operation %)) @seen))))
        (is (empty? (filter #(#{:state-decoded :event-applied :sort-key-read} (:operation %)) @seen)))))
    (let [other (assoc ctx :tenant-id (random-uuid))]
      (append! other [{:owner "only-other" :score [true 0]}])
      (is (= ["only-other"] (page-keys other name {:index :score :prefix [true]})))
      (is (= 3000 (:total (rmp/page ctx name {:index :score :prefix [true]})))))
    (doseq [options [{:prefix true} {:prefix nil} {:prefix '(true)}
                     {:prefix [true] :equals [true 0]}]]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/page ctx name (assoc options :index :score))))
      (is (thrown? clojure.lang.ExceptionInfo (rmp/values ctx name (assoc options :index :score)))))))

(deftest tuple-prefix-union-pages-match-canonical-membership
  (let [name (register! (fn [_ e] (select-keys e [:score :included?]))
                        {:indexes {:score :score} :include? :included?})
        scores [nil true "a" [] [nil] [true 0] [false 1] ["a" 1] ["a" 2]
                ["a\u0000" 1] ["ab" 0] ["b" 0] [["nested"] 1] ["" 0]]
        ids (mapv #(if (even? %) (str %) (java.util.UUID. 12 %)) (range 120))
        random (java.util.Random. 713)
        matches? (fn [prefix value]
                   (and (vector? value) (<= (count prefix) (count value))
                        (= prefix (subvec value 0 (count prefix)))))]
    (dotimes [_ 3]
      (append! (mapv (fn [id] {:owner id :score (nth scores (.nextInt random (count scores)))
                               :included? (.nextBoolean random)}) ids))
      (doseq [direction [:asc :desc]]
        (let [ordered (into [] (rmp/values *ctx* name {:index :score :direction direction}))]
          (doseq [prefixes [[] [[]] [[true] [false]] [["b"] ["a"] ["a"]]
                            [["a" 1] ["a"] ["absent"]] [[["nested"]] [""]]
                            [[nil] [true]] [["a\u0000"] ["ab"]] [["missing"]]
                            [["a"] [] ["b"]]]]
            (let [expected (into [] (comp (filter #(some (fn [prefix] (matches? prefix (get-in % [:value :score]))) prefixes))
                                         (map :key)) ordered)
                  pages (max 1 (quot (+ (count expected) 6) 7))]
              (doseq [requested (conj (vec (range 1 (inc pages))) 999)]
                (let [page (rmp/page *ctx* name {:index :score :direction direction :prefixes prefixes
                                                :page requested :limit 7})
                      page-number (min requested pages)]
                  (is (= (count expected) (:total page)))
                  (is (= pages (:total-pages page)))
                  (is (= page-number (:page page)))
                  (is (= (vec (take 7 (drop (* 7 (dec page-number)) expected)))
                         (mapv :key (:entries page)))))))))))
    (doseq [options [{:prefixes nil} {:prefixes '([true])} {:prefixes [true]}
                     {:prefixes [[] nil]} {:prefixes [[]] :equals nil}
                     {:prefixes [] :prefix []}]]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/page *ctx* name (assoc options :index :score)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rmp/values *ctx* name {:index :score :prefixes [[]]}))
        "Union selection is a page capability, not an implicit traversal API")))

(deftest tuple-prefix-union-pages-skip-gaps-and-remain-snapshot-bound
  (let [name (register! (fn [_ e] (select-keys e [:score :included?]))
                        {:indexes {:score :score} :include? :included?})
        random (java.util.Random. 321)
        work (atom []) ctx (assoc *ctx* :read-model/observer #(swap! work conj %))
        selection {:index :score :prefixes [["right"] ["left"] ["left" 0]] :limit 25}]
    (with-redefs [ranked/random-hash (fn [_] (.nextInt random))]
      (append! (mapv #(hash-map :owner (format "%05d" %) :included? true
                                :score [(cond (< % 1500) "left" (< % 3500) "middle" :else "right")
                                        (quot % 2)]) (range 5000)))
      (rmp/page ctx name selection))
    (doseq [direction [:asc :desc]]
      (reset! work [])
      (let [page (rmp/page ctx name (assoc selection :page 119 :direction direction))]
        (is (= 3000 (:total page)))
        (is (= 120 (:total-pages page)))
        (is (= (if (= direction :asc) "04950" "00048") (:key (first (:entries page)))))
        (is (= 25 (:rows-examined page) (:results-decoded page)))
        (is (= 25 (count (filter #(= :result-decoded (:operation %)) @work))))
        (is (< (:rank-entries-examined page) 1500))
        (is (not-any? #(#{:state-decoded :sort-key-read :event-applied} (:operation %)) @work))))
    (rmp/read-session ctx [name]
      (fn [snapshot]
        (let [before (rmp/page snapshot name selection)]
          (append! [{:owner "00000" :score ["middle" 0] :included? true}
                    {:owner "00001" :score ["left" 0] :included? false}])
          (is (= (select-keys before [:entries :total])
                 (select-keys (rmp/page snapshot name selection) [:entries :total]))))))
    (is (= 2998 (:total (rmp/page ctx name selection))))
    (let [other (assoc ctx :tenant-id (random-uuid))]
      (append! other [{:owner "other" :score ["left" 0] :included? true}])
      (is (= ["other"] (page-keys other name selection)))
      (is (= 2998 (:total (rmp/page ctx name selection)))))))

(deftest deep-pages-and-equality-counts-skip-members
  (let [name (register! (fn [_ e] (select-keys e [:score])) {:indexes {:score :score}})
        random (java.util.Random. 92)
        seen (atom []) ctx (assoc *ctx* :read-model/observer #(swap! seen conj %))]
    ;; Fixed random promotions make the work regression assertion reproducible.
    (with-redefs [ranked/random-hash (fn [_] (.nextInt random))]
      (append! (mapv #(hash-map :owner (format "%05d" %) :score (if (< % 3000) 0 1)) (range 4096)))
      (rmp/page ctx name {}))
    (doseq [options [{:index :score :page 164}
                     {:index :score :equals 0 :page 120}
                     {:index :score :equals 0 :direction :desc :page 120}]]
      (reset! seen [])
      (let [page (rmp/page ctx name options)
            equality? (contains? options :equals)]
        (is (= (if equality? 3000 4096) (:total page)))
        (is (= (if equality? "02975" "04075") (:key (first (:entries page)))))
        (is (= (count (:entries page)) (:rows-examined page) (:results-decoded page)))
        (is (< (:rank-entries-examined page) 1000))
        (is (= (:rank-entries-examined page) (count (filter #(= :rank-entry (:operation %)) @seen))))
        (is (empty? (filter #(#{:state-decoded :event-applied} (:operation %)) @seen)))))))

(deftest ranked-index-write-failure-rolls-back-business-batch
  (let [name (register! (fn [_ e] {:score (:score e)}) {:indexes {:score :score}})]
    (append! [{:owner "a" :score 1}])
    (rmp/page *ctx* name {})
    (let [before (stored-bytes) original ranked/add!]
      (append! [{:owner "a" :score 2}])
      (with-redefs [ranked/add! (fn [& args]
                                (apply original args)
                                (throw (ex-info "rank write failed" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rank write failed" (rmp/page *ctx* name {}))))
      (is (= before (stored-bytes)))
      (is (= ["a"] (page-keys *ctx* name {:index :score :equals 2})))
      (is (= 0 (:total (rmp/page *ctx* name {:index :score :equals 1})))))))

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
          ctx (assoc *ctx* :read-model/batch-size 1)]
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
    (is (thrown? clojure.lang.ExceptionInfo (rmp/value (dissoc *ctx* :tenant-id) name "a")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"share one event store"
                          (rmp/read-session [[*ctx* [name]] [(dissoc *ctx* :event-store) [name]]] identity)))
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

(deftest validators-are-reused-across-catch-up-batches
  (let [state-schema [:map [:history [:vector :int]]]
        result-schema [:map [:total :int]]
        name (register! history-step {:state-schema state-schema :result-schema result-schema
                                      :result total})
        ctx (assoc *ctx* :read-model/batch-size 2)
        prepared (atom []) checked (atom []) original m/validator]
    (append! [{:owner "a" :delta 1} {:owner "a" :delta 2}
              {:owner "b" :delta 3} {:owner "b" :delta 4} {:owner "c" :delta 5}])
    (with-redefs [m/validator (fn [schema]
                               (let [form (m/form schema) valid? (original schema nil)]
                                 (swap! prepared conj form)
                                 (fn [value] (swap! checked conj form) (valid? value))))]
      (is (= {"a" {:total 3} "b" {:total 7} "c" {:total 5}} (results ctx name)))
      (is (= {state-schema 1 result-schema 1} (frequencies @prepared)))
      (is (= {state-schema 5 result-schema 3} (frequencies @checked)))
      (reset! prepared []) (reset! checked [])
      (is (= {:total 3} (rmp/value ctx name "a")))
      (is (empty? @prepared) "An already current model needs no validators")
      (is (empty? @checked)))))

(deftest later-catch-ups-resolve-current-schemas-and-preserve-failure-details
  (doseq [option [:schema :state-schema :result-schema]]
    (let [schema-name (keyword "keyed-test" (str (random-uuid)))
          ctx (assoc *ctx* :tenant-id (random-uuid))
          kind (if (= option :result-schema) :result :state)]
      (try
        (schemas/register! {schema-name [:int {:max 10}]})
        (let [name (register! (fn [_ event] (:delta event)) {option schema-name})]
          (append! ctx [{:owner "a" :delta 1}])
          (is (= 1 (rmp/value ctx name "a")))
          ;; Change the registry between operations to detect stale validators.
          (schemas/register! {schema-name [:int {:max 2}]})
          (append! ctx [{:owner "a" :delta 2} {:owner "b" :delta 3}])
          (let [before (stored-bytes)
                error (try (rmp/value ctx name "a") nil
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (= "Keyed-fold schema validation failed" (some-> error ex-message)))
            (is (= {:model name :kind kind} (select-keys (ex-data error) [:model :kind])))
            (is (= 3 (get-in (ex-data error) [:explanation :value])))
            (is (seq (get-in (ex-data error) [:explanation :errors])))
            (is (= before (stored-bytes)) "Invalid state or result rolls back the whole batch"))
          (schemas/register! {schema-name [:int {:max 10}]})
          (is (= {"a" 2 "b" 3} (results ctx name)) "Retry resolves the schema again"))
        (finally (swap! schemas/registry* dissoc schema-name))))))

(deftest initial-state-tenant-and-version-isolation
  (let [name (register! (fn [s e] (+ s (:delta e))) {:initial 10})
        other (assoc *ctx* :tenant-id (random-uuid))]
    (append! [{:owner "a" :delta 1}])
    (append! other [{:owner "a" :delta 2}])
    (is (= 11 (rmp/value *ctx* name "a")))
    (is (= 12 (rmp/value other name "a")))
    (rmp/register-read-model! name (fn [s e] (+ s (* 2 (:delta e))))
                              {:events #{:keyed/change} :key :owner :version 2 :initial 20})
    (is (= 22 (rmp/value *ctx* name "a")))
    (is (= 24 (rmp/value other name "a")))))

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

(deftest prepared-readers-reuse-addresses-without-sharing-results-or-lifetimes
  (let [name (register! history-step {:result total :indexes {:total :total}})
        second-model (register! history-step {:result total})
        ctx (assoc *ctx* :read-model/batch-size 1)
        other (assoc ctx :tenant-id (random-uuid))
        calls (atom []) original key/prefix]
    (append! [{:owner "a" :delta 1} {:owner "a" :delta 2} {:owner "b" :delta 4}])
    (append! other [{:owner "a" :delta 9}])
    (let [escaped
          (with-redefs [key/prefix (fn [ctx name version]
                                    (swap! calls conj [(:tenant-id ctx) name version])
                                    (original ctx name version))]
            (rmp/read-session [[ctx [name name second-model]] [other [name]] [ctx [name]]]
              (fn [[a b duplicate]]
                (is (= (repeat 10 [{:total 3} {:history [1 2]} {:total 9} {:total 3}])
                       (repeatedly 10 #(vector (rmp/value a name "a") (rmp/state a name "a")
                                               (rmp/value b name "a") (rmp/value duplicate name "a")))))
                (is (= {:total 3} (rmp/value a second-model "a")))
                (is (= [{:key "a" :value {:total 3}} {:key "b" :value {:total 4}}]
                       (into [] (rmp/values a name {:index :total}))))
                (is (= [{:key "b" :value {:total 4}}]
                       (:entries (rmp/page a name {:index :total :direction :desc :limit 1}))))
                (is (some? (:watermark (rmp/progress a name))))
                (doseq [changed [(assoc a :tenant-id (:tenant-id other))
                                 (assoc a :event-store nil)
                                 (assoc a :cache nil)]]
                  (is (thrown? clojure.lang.ExceptionInfo (rmp/value changed name "a"))))
                (is (thrown? clojure.lang.ExceptionInfo (rmp/value b second-model "a")))
                a)))]
      (is (= {[(:tenant-id ctx) name 1] 1
              [(:tenant-id ctx) second-model 1] 1
              [(:tenant-id other) name 1] 1
              [(:tenant-id ctx) :grain.read-query/shared-identities 1] 1
              [(:tenant-id other) :grain.read-query/shared-identities 1] 1}
             (frequencies @calls))
          "Address preparation is bounded by distinct tenant/model identities, including cold batches")
      (is (thrown? clojure.lang.ExceptionInfo (rmp/value escaped name "a"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Transaction aborted"
          (rmp/read-session ctx [name]
            (fn [prepared]
              (is (= :rejected @(future
                                 (try (rmp/value prepared name "a")
                                      (catch clojure.lang.ExceptionInfo _ :rejected)))))))))
    (append! [{:owner "a" :delta 5}])
    (is (= {:total 8} (rmp/value ctx name "a")) "The next session catches up; no result cache")
    (rmp/register-read-model! name history-step
                             {:events #{:keyed/change} :version 2 :key :owner
                              :result (fn [s] {:total (* 2 (:total (total s)))})})
    (is (= {:total 16} (rmp/value ctx name "a")))
    (is (= {:total 18} (rmp/value other name "a")))))


(deftest warm-keyed-reads-use-the-committed-head-without-reading-events
  (let [name (register! history-step {:result total})]
    (append! (repeat 300 {:owner "a" :delta 1}))
    (is (= {:total 300} (rmp/value *ctx* name "a")))
    (with-redefs [es/read (fn [& _] (throw (ex-info "Unexpected event payload read" {})))]
      (is (= {:total 300} (rmp/value *ctx* name "a")))
      (is (= {:total 300} (:value (first (:entries (rmp/page *ctx* name {})))))))
    (with-redefs [es/head (fn [& _] {:cognitect.anomalies/category :cognitect.anomalies/unavailable})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"committed event-store head"
                           (rmp/value *ctx* name "a"))))))

(deftest candidate-page-prefix-selection-preserves-global-order-and-clamping
  (let [rows (mapv (fn [i] {:owner (str i) :score (mod i 7)}) (range 203))
        name (register! (fn [_ e] {:score (:score e)}) {:indexes {:score :score}})
        candidate-ids (concat (map :owner (reverse rows)) ["absent" "0" "3"])]
    (append! rows)
    (rmp/read-session *ctx* [name]
      (fn [ctx]
        (doseq [direction [:asc :desc] computed? [false true] explicit? [false true]]
          (let [order-value (if computed? (comp - :score) :score)
                ordered (->> rows (filter #(odd? (:score %)))
                             (sort-by (fn [row] [(cond-> (order-value row) (= direction :desc) -)
                                                 (:owner row)]))
                             (mapv :owner))
                options (cond-> {:index :score :indexes [:score] :direction direction :limit 7
                                 :include? (fn [_ value] (odd? (:score value)))}
                          computed? (assoc :sort-value (fn [_ value] (order-value value))))
                request (fn [options]
                          (if explicit? (rmp/key-page ctx name candidate-ids options)
                              (rmp/key-page ctx name options)))]
            (doseq [page [1 3 13 99 Long/MAX_VALUE]]
              (let [result (request (assoc options :page page))
                    clamped (min page 13)
                    offset (* 7 (dec clamped))]
                (is (= (subvec ordered offset (min (count ordered) (+ offset 7))) (:keys result)))
                (is (= [87 13 clamped 203] (mapv result [:total :total-pages :page :sort-keys-read])))))
            (let [result (request (assoc options :page Long/MAX_VALUE :limit Long/MAX_VALUE))]
              (is (= ordered (:keys result)))
              (is (= [87 1 1] (mapv result [:total :total-pages :page]))))))))))
