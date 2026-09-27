(ns ai.obney.grain.read-model-processor-v2.text-search-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [ai.obney.grain.read-model-processor-v2.keyed-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.text-search :as search]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(use-fixtures :each fixture/fixture)
(def opts {:searches {:text :text} :indexes {:score :score} :include? :included?})
(defn step [_ event] (select-keys event [:text :score :included?]))
(defn find-keys [ctx model q mode]
  (set (rmp/search-keys ctx model {:index :text :query q :mode mode})))

(deftest exact-positions-boundaries-and-compact-ordering
  (let [model (fixture/register! step opts)
        texts ["Anna Jones anna@example.com Dental" "aana Annan" "aaaaa aaa"
               "😀Ana İΣ Σ 😀𐐀" "abc---def abcdef" "abc bcabc" "" "a" "ab"]
        rows (mapv (fn [i text] {:owner (str i) :text text :score (mod i 3) :included? true}) (range) texts)
        cases [[:contains "anna"] [:contains "aana"] [:contains "aaaaaa"] [:contains "aaaaa"]
               [:contains "abcdef"] [:contains "abcabc"] [:contains " abc"] [:contains "com den"]
               [:contains "Σ 😀"] [:contains "😀ana"] [:contains "İΣ "]
               [:prefix "a"] [:prefix "ab"] [:prefix "ann"] [:prefix "😀"]
               [:suffix "a"] [:suffix "ab"] [:suffix "abc"] [:suffix "😀𐐀"]]]
    (fixture/append! rows)
    (doseq [[mode q] cases]
      (let [matches (case mode :prefix str/starts-with? :suffix str/ends-with? str/includes?)
            expected (filter #(matches (search/normalize (:text %)) (search/normalize q)) rows)]
        (is (= (set (map :owner expected)) (find-keys fixture/*ctx* model q mode)))
        (doseq [direction [:asc :desc] page [1 2 999]]
          (let [ordered (sort (fn [a b] (let [c (compare (:score a) (:score b))]
                                         (if (zero? c) (compare (:owner a) (:owner b))
                                           (if (= :desc direction) (- c) c)))) expected)
                pages (max 1 (quot (+ (count ordered) 1) 2))
                actual (rmp/key-page fixture/*ctx* model (mapcat #(repeat 2 (:owner %)) expected)
                                     {:index :score :direction direction :page page :limit 2})]
            (is (= (count expected) (:total actual)))
            (is (= (mapv :owner (take 2 (drop (* 2 (dec (min page pages))) ordered))) (:keys actual)))))))
    (doseq [q ["" "a" "ab" "😀a"]]
      (is (thrown? clojure.lang.ExceptionInfo (find-keys fixture/*ctx* model q :contains))))
    (let [observed (atom []) ctx (assoc fixture/*ctx* :read-model/observer #(swap! observed conj %))]
      (rmp/read-session ctx [model]
        (fn [ctx]
          (let [ids (rmp/search-keys ctx model {:index :text :query "abc"})
                page (rmp/key-page ctx model (conj ids "absent")
                                   {:index :score :sort-value (fn [_ v] (- v))})]
            (is (= ["5" "4"] (:keys page))))))
      (is (not-any? #(#{:result-decoded :state-decoded :event-applied} (:operation %)) @observed)))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/search-keys fixture/*ctx* model {:index :absent :query "abc"})))
    (is (thrown? clojure.lang.ExceptionInfo (rmp/key-page fixture/*ctx* model ["1"] {:index :absent})))))

(deftest update-exclusion-reentry-restart-and-isolation
  (let [model (fixture/register! step opts)
        other (assoc fixture/*ctx* :tenant-id (random-uuid))]
    (fixture/append! [{:owner "a" :text "old alpha" :score 1 :included? true}])
    (fixture/append! other [{:owner "a" :text "other beta" :score 2 :included? true}])
    (is (= #{"a"} (find-keys fixture/*ctx* model "alpha" :contains)))
    (is (empty? (find-keys fixture/*ctx* model "beta" :contains)))
    (is (= #{"a"} (find-keys other model "beta" :contains)))
    (fixture/append! [{:owner "a" :text "new beta" :score 2 :included? true}])
    (is (empty? (find-keys fixture/*ctx* model "alpha" :contains)))
    (is (= #{"a"} (find-keys fixture/*ctx* model "beta" :contains)))
    (fixture/append! [{:owner "a" :text "new beta" :score 2 :included? false}])
    (is (empty? (find-keys fixture/*ctx* model "beta" :contains)))
    (is (= 0 (:total (rmp/key-page fixture/*ctx* model ["a"] {}))))
    (fixture/append! [{:owner "a" :text "new beta" :score 2 :included? true}])
    (is (= #{"a"} (find-keys fixture/*ctx* model "beta" :contains)))
    (kv/stop @fixture/*cache-handle*) (reset! fixture/*cache-handle* nil)
    (let [cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir fixture/*directory* :db-name "folds"
                                               :map-size (* 64 1024 1024)}))
          ctx (assoc fixture/*ctx* :cache cache)]
      (reset! fixture/*cache-handle* cache)
      (is (= #{"a"} (find-keys ctx model "beta" :contains)))
      (is (= ["a"] (:keys (rmp/key-page ctx model ["a"] {}))))
      (let [unchanged (binding [fixture/*ctx* ctx] (fixture/stored-bytes))]
        (find-keys ctx model "beta" :contains)
        (is (= unchanged (binding [fixture/*ctx* ctx] (fixture/stored-bytes))))))))

(deftest failed-indexing-rolls-back-state-search-and-progress
  (let [model (fixture/register! step opts)]
    (fixture/append! [{:owner "a" :text "old alpha" :score 1 :included? true}])
    (find-keys fixture/*ctx* model "alpha" :contains)
    (let [before (fixture/stored-bytes)]
      (fixture/append! [{:owner "a" :text "new beta" :score 2 :included? true}
                       {:owner "b" :text 123 :score 3 :included? true}])
      (is (thrown? clojure.lang.ExceptionInfo (find-keys fixture/*ctx* model "beta" :contains)))
      (is (= before (fixture/stored-bytes)))
      (fixture/append! [{:owner "b" :text "valid beta" :score 3 :included? true}])
      (is (= #{"a" "b"} (find-keys fixture/*ctx* model "beta" :contains)))
      (is (empty? (find-keys fixture/*ctx* model "alpha" :contains))))))

(deftest ordered-values-round-trip
  (doseq [value [nil false true "" "a" "a\u0000b" "😀𐐀" (str (char 0xD800))
                 Long/MIN_VALUE -1 0 Long/MAX_VALUE :name :namespaced/name (random-uuid)
                 [] [nil "A" :x 42] [["nested"]]]]
    (is (= value (key/decode (key/encode value))))))

(deftest search-and-ordering-share-the-read-session-snapshot
  (let [model (fixture/register! step opts)
        entered (promise) advanced (promise)]
    (fixture/append! [{:owner "a" :text "alpha" :score 1 :included? true}
                      {:owner "b" :text "alpha" :score 2 :included? true}])
    (let [reader (future
                   (rmp/read-session fixture/*ctx* [model]
                     (fn [ctx]
                       (let [ids (rmp/search-keys ctx model {:index :text :query "alpha"})]
                         (deliver entered true)
                         (deref advanced 10000 ::timeout)
                         [(find-keys ctx model "alpha" :contains)
                          (:keys (rmp/key-page ctx model ids {:index :score}))]))))]
      (try
        (is (= true (deref entered 10000 ::timeout)))
        (fixture/append! [{:owner "a" :text "beta" :score 3 :included? true}])
        (is (= #{"b"} (find-keys fixture/*ctx* model "alpha" :contains)))
        (is (= ["b" "a"] (:keys (rmp/key-page fixture/*ctx* model ["a" "b"] {:index :score}))))
        (deliver advanced true)
        (is (= [#{"a" "b"} ["a" "b"]] (deref reader 10000 ::timeout)))
        (finally (deliver advanced true))))))
