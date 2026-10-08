(ns ai.obney.grain.read-model-processor-v2.compact-cache-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.schema-util.interface :as schema])
  (:import [java.util UUID Arrays]))

(schema/register! {:compact-test/change [:map]})
(def ^:dynamic *context* nil)
(def ^:dynamic *cache-options* nil)
(def ^:dynamic *cache* nil)

(defn fixture [f]
  (let [directory (str "/private/tmp/grain-compact-test-" (random-uuid))
        _ (.mkdirs (io/file directory))
        store (es/start {:conn {:type :sqlite :database-file (str directory "/events.db")}})
        options {:storage-dir (str directory "/cache") :db-name "test" :map-size (* 128 1024 1024)}
        cache (atom (kv/start (lmdb/->KV-Store-LMDB options)))
        registry (rmp/global-read-model-registry)]
    (binding [*context* {:event-store store :tenant-id (UUID. 4 7)}
              *cache-options* options *cache* cache]
      (try (f)
           (finally
             (reset! rmp/read-model-registry* registry)
             (when @cache (kv/stop @cache))
             (es/stop store)
             (doseq [file (reverse (file-seq (io/file directory)))] (io/delete-file file true)))))))

(use-fixtures :each fixture)
(defn ctx [] (assoc *context* :cache @*cache*))
(defn reopen! []
  (kv/stop @*cache*)
  (reset! *cache* nil)
  (reset! *cache* (kv/start (lmdb/->KV-Store-LMDB *cache-options*))))
(defn append! [context rows]
  (es/append (:event-store context)
             {:tenant-id (:tenant-id context)
              :events (mapv #(es/->event {:type :compact-test/change :body %}) rows)}))
(defn register! [name options]
  (rmp/register-read-model! name (fn [_ e] (select-keys e [:owner :score :group :text :included? :ordered?]))
                           (merge {:events #{:compact-test/change} :version 1 :key :owner
                                   :include? :included? :indexes {:score :score :group :group}
                                   :searches {:text :text}} options))
  name)
(defn type-rank [x]
  (cond (boolean? x) (if x 3 2) (string? x) 16 (integer? x) 32
        (uuid? x) 48 (keyword? x) 56 (vector? x) 64))
(defn identity-compare [direction a b]
  (let [primary (compare (str a) (str b))]
    (if (zero? primary) (compare (type-rank a) (type-rank b))
        (if (= direction :desc) (- primary) primary))))
(defn ordered [rows index direction]
  (vec (sort (fn [a b]
               (let [value #(if (= index :identity) (str (:owner %)) (get % index))
                     c (compare (value a) (value b))]
                 (if (zero? c) (identity-compare :asc (:owner a) (:owner b))
                     (if (= direction :desc) (- c) c)))) rows)))
(defn page-ids [context model options]
  (mapv :key (:entries (rmp/page context model options))))
(defn snapshot []
  (kv/transact @*cache* :read #((:reduce %) {} (fn [m {:keys [k v]}] (assoc m (vec k) (vec v))) {})))

(deftest ordered-codec-preserves-all-utf16-units-and-boundaries
  (let [units (mapv #(str (char %)) (range 65536))
        encoded (mapv key/encode units)]
    (is (every? true? (map #(= %1 (key/decode %2)) units encoded)))
    (is (every? neg? (map #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) encoded (rest encoded))))
    (is (every? pos? (map #(Arrays/compareUnsigned ^bytes (key/descending %1) ^bytes (key/descending %2)) encoded (rest encoded)))))
  (doseq [a ["" "a" "a\u0000" "\u0000" "\u007f" "\u0080" "\u0101" "\ud800" "\udc00" "\ud800\udc00" "\ue000" "\uffff"]
          b ["" "a" "\u0000" "\u0101" "\uffff"]]
    (is (= [a [b :x] 17] (key/decode (key/encode [a [b :x] 17]))))
    (let [prefix (key/tuple-prefix [a]) value (key/encode [a b])]
      (is (= (vec prefix) (vec (take (alength ^bytes prefix) value))))))
  (is (= 511 (alength ^bytes (key/checked (byte-array 511)))))
  (is (= :grain.read-model/key-too-large
         (try (key/checked (byte-array 512)) nil
              (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
  (let [k (byte-array (repeat 511 (byte 42)))]
    (kv/put! @*cache* {:k k :v (byte-array [19])})
    (is (= [19] (vec (kv/get! @*cache* {:k k}))))))

(deftest previously-accepted-long-unicode-filtered-identities-still-fit
  (let [model (register! :compact-test/unicode {:filtered-indexes #{:identity}})
        owners [(apply str (repeat 60 "\u0101")) (apply str (repeat 60 "\u07ff"))
                (apply str (repeat 60 "\uffff"))]
        rows (mapv #(hash-map :owner % :score 1 :group ["a" 1] :text "alpha" :included? true) owners)]
    (append! (ctx) rows)
    (doseq [direction [:asc :desc]]
      (is (= (vec (sort #(identity-compare direction %1 %2) owners))
             (:keys (rmp/filtered-key-page (ctx) model owners {:direction direction :limit 10})))))
    (reopen!)
    (doseq [row rows] (is (= row (rmp/value (ctx) model (:owner row)))))
    (is (= (set owners) (set (rmp/search-keys (ctx) model {:index :text :query "alpha"}))))))

(deftest compact-codec-preserves-utf16-order-and-doubled-legacy-size-bound
  (let [encode (try (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.compact-key/encode)
                    (catch java.io.FileNotFoundException _ nil))]
    (is (some? encode))
    (when encode
      (let [decode (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.compact-key/decode)
            units (mapv #(str (char %)) (range 65536))
            encoded (mapv encode units)]
        (is (every? true? (map #(= %1 (decode %2)) units encoded)))
        (is (every? neg? (map #(Arrays/compareUnsigned ^bytes %1 ^bytes %2) encoded (rest encoded))))
        (is (every? true? (map #(<= (alength ^bytes %2) (* 2 (alength ^bytes (key/encode %1)))) units encoded)))
        (doseq [value ["" "a\u0000b" "\ud800" "\udc00" "\ud800\udc00"
                       ["a" ["\u0101" :x] 1] [false 9 (UUID. 1 2)]]]
          (is (= value (decode (encode value)))))))))

(deftest absent-identity-selection-at-physical-key-boundary-is-empty
  (let [encode (try (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.compact-key/encode)
                    (catch java.io.FileNotFoundException _ nil))]
    (is (some? encode))
    (when encode
      (let [model (register! :compact-test/empty-boundary {})
            index-prefix (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.keyed/index-prefix)
            overhead (alength ^bytes (index-prefix (byte-array 7) :identity :asc))
            exact (apply str (repeat (- 511 overhead 3) "x"))
            probes [exact (str exact "x") (apply str (repeat 200 "\u0101"))]]
        (is (= 511 (+ overhead (alength ^bytes (encode exact)))))
        (append! (ctx) [{:owner "a" :score 1 :group ["a" 1] :text "alpha" :included? true}])
        (rmp/read-session (ctx) [model]
          (fn [prepared]
            (doseq [direction [:asc :desc] probe probes]
              (let [options {:index :identity :equals probe :direction direction}]
                (is (= [] (page-ids prepared model (assoc options :limit 10))))
                (is (= [] (rmp/index-keys prepared model (assoc options :limit 10))))
                (is (= [] (into [] (rmp/values prepared model options))))
                (is (= {:rank 0 :present? false} (rmp/index-rank prepared model options "a")))))))))))

(deftest absent-unicode-probes-retain-one-read-session-and-snapshot
  (let [model (register! :compact-test/probes {:filtered-indexes #{:identity}})
        long-id (apply str (repeat 60 "\u0101"))
        rows [{:owner "a" :score 1 :group ["a" 1] :text "alpha" :included? true}]
        callbacks (atom 0)]
    (append! (ctx) rows)
    (rmp/read-session (ctx) [model]
      (fn [prepared]
        (swap! callbacks inc)
        (is (nil? (rmp/value prepared model long-id)))
        (doseq [direction [:asc :desc] group ["a" long-id "absent"]]
          (is (= {:rank (if (= group "a") 1 0) :present? false}
                 (rmp/index-rank prepared model {:index :identity :equals group :direction direction} long-id))))
        (is (= ["a"] (:keys (rmp/filtered-key-page prepared model [long-id "a"] {:limit 10}))))))
    (is (= 1 @callbacks))))

(deftest model-tenant-version-isolation-and-failed-first-materialization
  (let [model (register! :compact-test/isolation {})
        other (register! :compact-test/isolation-other {:result #(assoc % :variant :other)})
        alternate (assoc (ctx) :tenant-id (UUID. 4 8))
        row {:owner "same" :score 1 :group ["a" 1] :text "alpha" :included? true}]
    (append! (ctx) [row])
    (append! alternate [(assoc row :score 9)])
    (is (= 1 (:score (rmp/value (ctx) model "same"))))
    (is (= 9 (:score (rmp/value alternate model "same"))))
    (is (= :other (:variant (rmp/value (ctx) other "same"))))
    (register! model {:version 2 :result #(assoc % :variant :v2)})
    (is (= :v2 (:variant (rmp/value (ctx) model "same"))))
    (reopen!)
    (is (= :v2 (:variant (rmp/value (ctx) model "same"))))
    (is (= 9 (:score (rmp/value (assoc (ctx) :tenant-id (UUID. 4 8)) model "same"))))
    (register! model {})
    (is (= row (rmp/value (ctx) model "same")))
    (let [broken (register! :compact-test/abort {:result (fn [_] (throw (ex-info "expected failure" {})))})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expected failure" (rmp/value (ctx) broken "same")))
      (let [identity [(:tenant-id (ctx)) broken 1]
            resolver (try (requiring-resolve 'ai.obney.grain.read-model-processor-v2.core.namespace-registry/resolve!)
                          (catch java.io.FileNotFoundException _ nil))
            base (vec (if resolver (get (resolver @*cache* [identity]) identity)
                          (key/prefix (ctx) broken 1)))
            after (snapshot)]
        (is (empty? (filter (fn [[k _]] (= base (vec (take (count base) k)))) after)))
        (reopen!)
        (is (= after (snapshot)))
        (register! broken {})
        (is (= row (rmp/value (ctx) broken "same")))
        (when resolver
          (is (= base (vec (get (resolver @*cache* [identity]) identity))))
          (let [registry-records #(into {} (filter (fn [[k _]] (= [75 82 57] (vec (take 3 k))))) %)]
            (is (= (registry-records after) (registry-records (snapshot))))))))))

(def owners [false "false" 1 "1" 2 "2" 10 "10" :a ":a" [1] "[1]"
             #uuid "00000000-0000-0000-0000-000000000003" "00000000-0000-0000-0000-000000000003"
             "" "a" "aa" "a\u0000" "\u007f" "\u0080" "\u0101" "\ue000" "\uffff"])

(defn assert-view! [context model ordering rows]
  (let [included (filterv :included? (vals rows)) ids (mapv :owner included)]
    (doseq [index [:identity :score :group] direction [:asc :desc]]
      (let [expected (mapv :owner (ordered included index direction))]
        (is (= expected (page-ids context model {:index index :direction direction :limit 100})))
        (is (= expected (page-ids context :compact-test/plain {:index index :direction direction :limit 100})))
        (is (= expected (:keys (rmp/filtered-key-page context model (concat ids ids [:absent])
                                                    {:index index :direction direction :limit 100}))))))
    (doseq [direction [:asc :desc] group ["1" "false" ":a" "[1]" "absent" nil 1]
            probe [1 "1" 2 "2" false "false" :a ":a" [1] "[1]" "missing"]]
      (let [matches (filter #(= group (str %)) ids)]
        (is (= {:rank (count (filter #(neg? (identity-compare :asc % probe)) matches))
                :present? (boolean (some #(= probe %) matches))}
               (rmp/index-rank context model {:index :identity :equals group :direction direction} probe)))
        (is (= {:rank (count (filter #(neg? (identity-compare :asc % probe)) matches))
                :present? (boolean (some #(= probe %) matches))}
               (rmp/index-rank context :compact-test/plain {:index :identity :equals group :direction direction} probe)))))
    (doseq [direction [:asc :desc] prefix [["a"] ["b"] ["absent"]]]
      (let [matching (filterv #(= (first prefix) (first (:group %))) included)]
        (is (= (mapv :owner (ordered matching :group direction))
               (page-ids context model {:index :group :prefix prefix :direction direction :limit 100})))))
    (let [expected (filterv #(str/includes? (:text %) "alpha") included)
          found (rmp/search-keys context model {:index :text :query "alpha"})]
      (is (= (set (map :owner expected)) (set found)))
      (doseq [direction [:asc :desc]]
        (is (= (mapv :owner (ordered expected :score direction))
               (:keys (rmp/key-page context model found {:index :score :direction direction :limit 100}))))))
    (doseq [direction [:asc :desc]]
      (let [expected (mapv :owner (ordered (mapv #(assoc % :score (if (:ordered? %) (:score %) -99)) included)
                                            :score direction))]
        (is (= expected (:keys (rmp/joined-page context model ordering
                                                {:index :score :direction direction :missing-value -99 :limit 100}))))))))

(deftest mixed-identities-corrections-exclusion-and-reopen-have-literal-oracles
  (let [opts {:filtered-indexes #{:identity :score :group}}
        model (register! :compact-test/members opts)
        _ (register! :compact-test/plain {})
        ordering (register! :compact-test/ordering (assoc opts :include? :ordered?))
        rows (into {} (map-indexed (fn [i id] [id {:owner id :score (mod i 4)
                                                   :group [(if (even? i) "a" "b") (mod i 3)]
                                                   :text (if (odd? i) "alpha" "beta")
                                                   :included? true :ordered? (not (zero? (mod i 3)))}]) owners))]
    (append! (ctx) (mapv rows owners))
    (assert-view! (ctx) model ordering rows)
    (let [changed (reduce #(update %1 %2 assoc :included? false :ordered? false :text "beta" :score 99)
                          rows [1 "2" false])]
      (append! (ctx) (mapv changed [1 "2" false]))
      (assert-view! (ctx) model ordering changed)
      (reopen!)
      (assert-view! (ctx) model ordering changed)
      (let [restored (reduce #(update %1 %2 assoc :included? true :ordered? true :text "alpha" :score -5)
                            changed [1 "2" false])]
        (append! (ctx) (mapv restored [1 "2" false]))
        (assert-view! (ctx) model ordering restored)))))
