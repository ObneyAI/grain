(ns ai.obney.grain.read-model-processor-v2.ranked-membership-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.keyed-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.core.ranked-set :as ranked]
            [ai.obney.grain.read-model-processor-v2.core.span-membership :as membership]
            [ai.obney.grain.read-model-processor-v2.core.instance-ids :as ids]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb])
  (:import [java.util Random Arrays]
           [org.roaringbitmap RoaringBitmap]))

(use-fixtures :each fixture/fixture)
(def shape [:keys :total :total-pages :page :page-size])
(defn step [_ e] (select-keys e [:name :score :included?]))
(def options {:include? :included? :indexes {:name :name :score :score}
              :searches {:text :name} :filtered-indexes #{:name :score :identity}})

(defn assert-pages [ctx model keys]
  (rmp/read-session ctx [model]
    (fn [ctx]
      (doseq [index [:identity :name :score] direction [:asc :desc] page [1 2 7 999]]
        (let [opts {:index index :direction direction :page page :limit 7}]
          (is (= (select-keys (rmp/key-page ctx model keys opts) shape)
                 (select-keys (rmp/filtered-key-page ctx model keys opts) shape))))))))

(deftest composed-candidates-mutations-restart-and-isolation
  (let [model (fixture/register! step options)
        ;; Deliberately different membership/ordinal allocation in another model.
        other (fixture/register! step (assoc options :include? #(and (:included? %) (odd? (:score %)))))
        events (mapv (fn [i] {:owner (str i) :name (str "name-" (mod i 13))
                              :score (- i 100) :included? (not (zero? (mod i 7)))}) (range 180))
        keys (mapv :owner events)]
    (fixture/append! events)
    (doseq [candidates [[] ["absent"] keys (take-nth 9 keys) (concat keys keys ["absent"])]]
      (assert-pages fixture/*ctx* model candidates))
    (let [candidates (rmp/read-session fixture/*ctx* [model other]
                       #(rmp/search-keys % other {:index :text :query "name-1"}))]
      (assert-pages fixture/*ctx* model candidates))
    (fixture/append! (mapv #(assoc % :name "changed" :score 0 :included? (not (:included? %))) (take 60 events)))
    (assert-pages fixture/*ctx* model keys)
    (fixture/append! (take 60 events))
    (assert-pages fixture/*ctx* model keys)
    (let [observed (atom []) ctx (assoc fixture/*ctx* :read-model/observer #(swap! observed conj %))]
      (rmp/filtered-key-page ctx model keys {:index :name :page 10})
      (is (not-any? #(#{:result-decoded :state-decoded :sort-key-read} (:operation %)) @observed)))
    (kv/stop (:cache fixture/*ctx*))
    (let [cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir fixture/*directory* :db-name "folds"
                                               :map-size (* 64 1024 1024)}))]
      (reset! fixture/*cache-handle* cache)
      (assert-pages (assoc fixture/*ctx* :cache cache) model keys)
      (is (= 0 (:total (rmp/filtered-key-page (assoc fixture/*ctx* :cache cache :tenant-id (random-uuid))
                                            model keys {:index :name})))))))

(deftest validation-and-atomic-rollback
  (is (thrown? Exception (fixture/register! step (assoc options :filtered-indexes #{:missing}))))
  (let [plain (fixture/register! step (dissoc options :filtered-indexes))
        model (fixture/register! step (assoc options :result #(if (= "fail" (:name %))
                                                               (throw (ex-info "fail" {})) %)))]
    (is (thrown? Exception (rmp/filtered-key-page fixture/*ctx* plain [] {:index :name})))
    (is (thrown? Exception (rmp/filtered-key-page fixture/*ctx* model [] {:index :name :sort-value identity})))
    (fixture/append! [{:owner "a" :name "before" :score 1 :included? true}])
    (rmp/filtered-key-page fixture/*ctx* model ["a"] {:index :name})
    (let [before (fixture/stored-bytes)]
      (fixture/append! [{:owner "b" :name "good" :score 2 :included? true}
                       {:owner "c" :name "fail" :score 3 :included? true}])
      (is (thrown? Exception (rmp/filtered-key-page fixture/*ctx* model ["a" "b" "c"] {:index :name})))
      (is (= before (fixture/stored-bytes))))))

(deftest membership-without-text-index-and-with-nil-results
  (let [model (fixture/register! (fn [_ _] nil) {:filtered-indexes #{:identity}})]
    (fixture/append! [{:owner :b} {:owner :a}])
    (is (nil? (rmp/value fixture/*ctx* model :a ::missing)))
    (is (= {:keys [:b] :total 2 :total-pages 2 :page 2 :page-size 1}
           (select-keys (rmp/filtered-key-page fixture/*ctx* model [:b :missing :a :a]
                                             {:page 2 :limit 1}) shape)))))

(deftest span-splits-merges-and-random-mutations
  (let [rng (Random. 713) cfg (assoc (ranked/config (byte-array [99])
                                                  (fn [_] (nth [0 16 256 1 1 1] (.nextInt rng 6))) 6)
                                    :membership? true)
        store (:cache fixture/*ctx*) present (atom #{})
        candidates (doto (RoaringBitmap.) (.add (long 0) (long 160)))
        check! (fn []
                 (kv/transact store :read
                   (fn [tx]
                     (is (= (count @present) (ranked/size tx cfg)))
                     (doseq [level (range 1 6)]
                       (let [entries ((:reduce tx) {:prefix (ranked/prefix cfg level)}
                                      (fn [out {:keys [k]}] (conj out (ranked/unpack cfg k))) [])]
                         (doseq [[begin end] (map vector entries (concat (rest entries) [nil]))]
                           (let [expected (set (filter (fn [i]
                                                        (let [k (ids/number-bytes i)]
                                                          (and (not (neg? (Arrays/compareUnsigned k begin)))
                                                               (or (nil? end) (neg? (Arrays/compareUnsigned k end)))))) @present))
                                 bits (membership/bitmap tx (ranked/membership-key cfg level begin))]
                             (is (= expected (set (.toArray bits))))))))
                     (doseq [page [1 3 30]]
                       (let [result (ranked/filtered-page tx cfg candidates page 7 (fn [& _]))
                             ordered (vec (sort @present))
                             offset (* (dec (:page result)) 7)]
                         (is (= (count ordered) (:total result)))
                         (is (= (subvec ordered offset (min (count ordered) (+ offset 7)))
                                (mapv ids/number-value (:payloads result)))))))))]
    (kv/transact store :write #(ranked/init! % cfg))
    (dotimes [_ 16]
      (kv/transact store :write
        (fn [tx]
          (let [tx (membership/transaction tx)]
            (dotimes [_ 40]
              (let [id (.nextInt rng 160) key (ids/number-bytes id)]
                (if (.nextBoolean rng)
                  (do (ranked/add! tx cfg key key id) (swap! present conj id))
                  (do (ranked/remove! tx cfg key) (swap! present disj id)))))
            (membership/flush! tx (fn [& _])))))
      (check!))
    (is (thrown? Exception
          (kv/transact store :write
            (fn [tx]
              (let [tx (membership/transaction tx)]
                (doseq [id @present] (ranked/remove! tx cfg (ids/number-bytes id)))
                (membership/flush! tx (fn [& _]))
                (throw (ex-info "rollback" {})))))))
    (check!)))

(deftest snapshot-and-replay-equivalence
  (let [model (fixture/register! step options)
        ctx fixture/*ctx* keys (mapv str (range 40))
        events (mapv (fn [id] {:owner id :name "same" :score 0 :included? true}) keys)
        page-options {:index :name :page 2 :limit 10 :direction :desc}]
    (fixture/append! events)
    (rmp/read-session ctx [model]
      (fn [prepared]
        (let [before (rmp/filtered-key-page prepared model keys page-options)
              writer (future
                       (fixture/append! ctx (mapv #(assoc % :included? false) (take 30 events)))
                       (rmp/progress ctx model))]
          (is (not= ::timeout (deref writer 20000 ::timeout)))
          (is (= before (rmp/filtered-key-page prepared model keys page-options))))))
    (let [current (rmp/filtered-key-page ctx model keys page-options)
          replay (fixture/register! step options)]
      (is (= 10 (:total current)))
      (is (= (select-keys current shape)
             (select-keys (rmp/filtered-key-page (assoc ctx :read-model/batch-size 1) replay keys page-options) shape))))))
