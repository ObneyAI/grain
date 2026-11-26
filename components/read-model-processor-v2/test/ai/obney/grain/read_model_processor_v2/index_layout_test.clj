(ns ai.obney.grain.read-model-processor-v2.index-layout-test
  "OPT-9 index layout (wave 2, no format version bump: caches are wiped on
   rollout): W7 old index positions from sort metadata, W6 no stored :identity
   groups, W8 empty level-zero payloads; plus the wave-2 integration
   conditions (group :value kept on decrement, sort-data shape check)."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(defn- cv [s]
  (or (get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) s)
      (throw (ex-info "No such processor var" {:var s}))))
(defn- det-promotion-hash [_ _ _ ^bytes k] (clojure.lang.Murmur3/hashInt (java.util.Arrays/hashCode k)))

(defn- typed
  "Value with every scalar's class, for exact (value + type) comparison."
  [v]
  (cond (vector? v) [:vector (mapv typed v)]
        (nil? v) [:nil]
        :else [(class v) v]))

(defn- fressian [v] (codec/decode (codec/encode v)))

(def ^:private odd-ids
  [(java.util.UUID. 1 2) (java.util.UUID. -1 0) 0 -1 Long/MIN_VALUE Long/MAX_VALUE (int 7) (short 3)
   "" "abc" "a\u0000b" "\u0000" "ü" "日本語" "😀" "ÿĀ￿" "ÿ"
   :a :ns/b (keyword "a/b" "c") (keyword "x y") (keyword "a/b")
   ["a" 1 :k nil true [2 "日"]] [] true false 5N (biginteger 7) 9223372036854775807N])

(def ^:private odd-values
  [nil true false -5 0 Long/MAX_VALUE Long/MIN_VALUE "" "x\u0000" "日本語" "😀" :k :ns/k
   [1 "a" [:b nil]] (int 3) (java.util.UUID. 3 4) [] [nil] "ÿ\u0000Ā"])

(deftest member-keys-decode-to-the-payload-value
  (let [member (cv 'keyed-index-member) member-id (cv 'keyed-member-id)
        entry-id (cv 'keyed-entry-id) payload (cv 'keyed-id-payload) exact? (cv 'keyed-id-exact?)
        prefix (byte-array [75 70 57 0 0 0 1 5 1 2 3 0 0])]
    (doseq [d [{:indexes {:v :v}} {:indexes {:v :v} :filtered-indexes #{:identity :v}}]
            id odd-ids
            [index value] (concat [[:identity (str id)]] (map (fn [v] [:v v]) odd-values))
            ;; Only ascending members are stored (OPT-5/OPT-7).
            direction [:asc]]
      (let [m (@member d index direction value id)
            k (byte-array (concat prefix m))
            p (@payload id)]
        (testing (pr-str [d id index value direction])
          (if (@exact? id)
            (do (is (zero? (alength ^bytes p)))
                (is (= (typed (fressian id)) (typed (@member-id d index direction k (alength prefix))))))
            (is (pos? (alength ^bytes p))))
          ;; What a reader returns is always what a Fressian payload returned.
          (is (= (typed (fressian id)) (typed (@entry-id d index direction k (alength prefix) p)))))))))

(deftest truncated-or-trailing-members-are-rejected
  (let [member (cv 'keyed-index-member) member-id (cv 'keyed-member-id)
        d {:indexes {:v :v}}
        m (@member d :v :asc "abc" (java.util.UUID. 1 2))]
    (is (thrown? clojure.lang.ExceptionInfo (@member-id d :v :asc (java.util.Arrays/copyOf ^bytes m (dec (alength ^bytes m))) 0)))
    (is (thrown? clojure.lang.ExceptionInfo (@member-id d :v :asc (byte-array (concat m [1])) 0)))))

(defn- odd-model! [filtered]
  (rmp/register-read-model! :index-layout-test/odd (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
    {:events #{:keyed/change} :version 1 :key :owner
     :include? #(not (:deleted %))
     :indexes {:score :score :name :name}
     :filtered-indexes filtered})
  :index-layout-test/odd)

(deftest readers-return-the-same-ids-as-fressian-payloads
  (doseq [filtered [#{:score} #{:score :identity}]]
    (fixture/keyed-fixture
      (fn []
        (let [ctx fixture/*keyed-ctx* model (odd-model! filtered)
              ids (vec (distinct odd-ids))
              expected (fn [ids] (mapv (comp typed fressian) ids))]
          (fixture/keyed-append! (map-indexed (fn [i id] {:owner id :score (mod i 5) :name (str "n" i)}) ids))
          (let [values (vec (rmp/values ctx model))
                stored-ids (mapv :key values)]
            (is (= (count ids) (count values)))
            (is (= (set (expected ids)) (set (map typed stored-ids))))
            (doseq [index [:identity :score :name] direction [:asc :desc]]
              (let [keys (mapv :key (:entries (rmp/page ctx model {:index index :direction direction :limit 1000})))]
                (is (= (count ids) (count keys)))
                (is (= (set (expected ids)) (set (map typed keys))))))
            (let [keys (rmp/index-keys ctx model {:index :score :equals 2 :limit 1000})]
              (is (= (set (expected (keep-indexed (fn [i id] (when (= 2 (mod i 5)) id)) ids))) (set (map typed keys)))))
            (doseq [index (sort filtered) direction [:asc :desc]]
              (let [page (rmp/filtered-key-page ctx model ids {:index index :direction direction :limit 1000})]
                (is (= (set (expected ids)) (set (map typed (:keys page))))))
              (let [members (do (rmp/register-read-model! :index-layout-test/members (fn [s _] s)
                                  {:events #{:keyed/change} :version 1 :key :owner})
                                :index-layout-test/members)
                    page (rmp/joined-page ctx members model {:index index :direction direction :limit 1000 :missing-value 0})]
                (is (= (set (expected ids)) (set (map typed (:keys page)))))))))))))

(defn- physical-order [values]
  (sort-by core/key-encode (fn [a b] (java.util.Arrays/compareUnsigned ^bytes a ^bytes b)) values))

(deftest identity-groups-are-derived-from-level-zero
  (doseq [filtered [#{} #{:identity}]]
    (fixture/keyed-fixture
      (fn []
        (let [ctx fixture/*keyed-ctx* model (odd-model! filtered)
              ;; 1 and "1" (and 7 / (int 7)) share (str id): one group of two
              ids [1 "1" 7 "7b" :a "b" "日本" "😀" "ÿ" (java.util.UUID. 1 2) "" "a\u0000"]]
          (fixture/keyed-append! (map (fn [id] {:owner id :score 1 :name "x"}) ids))
          (fixture/keyed-append! [{:owner "7b" :deleted true}])
          (let [live (remove #{"7b"} ids)
                expected (mapv (fn [[v n]] {:value v :total n})
                               (sort-by first (fn [a b] (java.util.Arrays/compareUnsigned ^bytes (core/key-encode a) ^bytes (core/key-encode b)))
                                        (frequencies (map str live))))]
            (is (= expected (rmp/index-groups ctx model :identity)))
            (is (= [{:value "1" :total 2} {:value "b" :total 1}]
                   (rmp/index-groups ctx model :identity ["1" "zz" 1 "b" "1" "7b"])))
            ;; no :identity group rows are stored (level 6 of the identity index)
            (let [b (get (core/namespace-resolve! (:cache ctx) [[(:tenant-id ctx) model 1]]) [(:tenant-id ctx) model 1])
                  groups (core/key-join b (byte-array [5]) (#'core/keyed-index-ordinal-bytes {:indexes {}} :identity) (byte-array [0 6]))]
              (is (zero? (kv/transact (:cache ctx) :read #((:reduce %) {:prefix groups} (fn [n _] (inc n)) 0)))))
            (when (empty? filtered)
              ;; compact identity rank of an id outside the group: the group's total when id sorts after it
              (is (= {:rank 2 :present? false} (rmp/index-rank ctx model {:index :identity :equals "1"} "2")))
              (is (= {:rank 0 :present? false} (rmp/index-rank ctx model {:index :identity :equals "1"} "0")))
              (is (= {:rank 0 :present? false} (rmp/index-rank ctx model {:index :identity :equals "zz"} "zzz")))
              (is (= {:rank 0 :present? true} (rmp/index-rank ctx model {:index :identity :equals "b"} "b"))))))))))

;; ---------------------------------------------------------------------------
;; I14: old positions from sort data are byte-equal to the former :indexes path
;; ---------------------------------------------------------------------------

(def ^:private i14-owners (vec (concat (range 30) (map #(str "s" %) (range 15)) (map #(java.util.UUID. 9 %) (range 15))
                                       [:kw :ns/kw (int 77) ["t" 1]])))
(def ^:private i14-values
  ;; Every type the index encoders accept. BigInt values are excluded: the
  ;; bulk build writes a group's :value from the compact round trip (Long),
  ;; the :indexes path from Fressian (BigInt); both are = (documented).
  [nil true false -3 0 1 (int 2) 99 Long/MAX_VALUE "" "alpha" "ünïcode" "日本語" "a\u0000b" "😀"
   :a :ns/b [1 "x"] [nil [true :k]] (java.util.UUID. 5 6)])

(defn- i14-history [seed n]
  (let [r (java.util.Random. seed) pick #(nth % (.nextInt r (count %)))]
    (vec (for [_ (range n)]
           {:inputs (vec (for [_ (range (inc (.nextInt r 3)))]
                           [(pick i14-owners)
                            (case (.nextInt r 9)
                              0 {:deleted true}
                              1 {:deleted false}
                              2 {:noise (.nextInt r 5)}
                              {:score (pick i14-values) :name (pick i14-values)
                               :pair [(pick [:a :b nil]) (pick i14-values)]})]))}))))

(defn- i14-rows [ctx model]
  (let [id [(:tenant-id ctx) model 1] shared [(:tenant-id ctx) :grain.read-query/shared-identities 1]
        prefixes (core/namespace-resolve! (:cache ctx) [id shared])
        rows (fn [^bytes p] (kv/transact (:cache ctx) :read
                              #((:reduce %) {:prefix p}
                                (fn [out {:keys [^bytes k v]}]
                                  (assoc out (vec (java.util.Arrays/copyOfRange k (alength p) (alength k))) (vec v)))
                                (sorted-map))))]
    {:model (rows (get prefixes id)) :shared (rows (get prefixes shared))}))

(defn- i14-run [opts reference?]
  (fixture/keyed-fixture
    (fn []
      (let [ctx (merge fixture/*keyed-ctx* {:tenant-id (java.util.UUID. 14 14)} opts)
            counter (atom 0) lookups (atom 0)
            shadow (java.util.HashMap.)
            enc @(cv 'sort-data-encode) old @(cv 'keyed-old-position)
            model :index-layout-test/i14]
        (with-redefs-fn (merge {(cv 'ranked-promotion-hash) det-promotion-hash
                                #'time/now (constantly (java.time.OffsetDateTime/parse "2026-10-16T00:00:00Z"))
                                #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))
                                (cv 'keyed-old-position) (fn [id index data field]
                                                           (swap! lookups inc)
                                                           (if reference?
                                                             (get (or (.get shadow (vec data))
                                                                      (throw (ex-info "no captured :indexes" {})))
                                                                  index)
                                                             (old id index data field)))}
                               (when reference?
                                 ;; INT stored these maps in the result and Fressian-decoded them for removal.
                                 {(cv 'sort-data-encode) (fn [id indexes]
                                                           (let [b (enc id indexes)]
                                                             (.put shadow (vec b) (fressian indexes))
                                                             b))}))
          (fn []
            (rmp/register-read-model! model merge
              {:events #{:keyed/change} :version 1 :route :inputs
               :include? (fn [v] (not (:deleted v)))
               :result #(dissoc % :noise)
               :indexes {:score :score :name :name :pair :pair}
               :filtered-indexes #{:score}
               :searches {:name #(when (string? (:name %)) (:name %))}})
            (doseq [h [(i14-history 3 120) (i14-history 4 80) (i14-history 5 120)]]
              (fixture/keyed-append! ctx h)
              (rmp/progress ctx model))
            {:rows (i14-rows ctx model) :lookups @lookups
             :values (vec (rmp/values ctx model))
             :groups (vec (rmp/index-groups ctx model :score))}))))))

(deftest i14-removal-via-sort-data-is-byte-equal-to-the-indexes-path
  (doseq [[label opts] {:incremental {:read-model/batch-size 8 :read-model/deferred-build-threshold 0}
                        :deferred {:read-model/batch-size 8 :read-model/deferred-build-threshold 16}}]
    (testing label
      (let [candidate (i14-run opts false) reference (i14-run opts true)]
        (is (pos? (:lookups candidate)))
        (is (= (:lookups candidate) (:lookups reference)))
        (is (seq (:values candidate)))
        (is (= (:values reference) (:values candidate)))
        (is (= (:groups reference) (:groups candidate)))
        (is (= (count (:model (:rows reference))) (count (:model (:rows candidate)))))
        (is (= (:rows reference) (:rows candidate)))))))

(deftest bulk-build-is-byte-identical-to-incremental-batches
  ;; OPT-4's deferred build (sort data scan, empty payloads, no identity
  ;; groups) against incremental batches, over odd ids and every value type.
  (let [incremental (i14-run {:read-model/batch-size 8 :read-model/deferred-build-threshold 0} false)]
    (doseq [[label opts] {:deferred {:read-model/batch-size 8 :read-model/deferred-build-threshold 16}
                          :sorted-runs {:read-model/batch-size 8 :read-model/deferred-build-threshold 16
                                        :read-model/deferred-build-entries 7}}]
      (testing label
        (let [bulk (i14-run opts false)]
          (is (= (:values incremental) (:values bulk)))
          (is (= (:rows incremental) (:rows bulk))))))))

;; ---------------------------------------------------------------------------
;; Stored layout: results without :indexes, level-zero members without payload
;; ---------------------------------------------------------------------------

(deftest results-carry-no-indexes-and-members-no-payload
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})]
        (fixture/keyed-append! (for [i (range 50)] {:owner (str "o" (mod i 7)) :delta i}))
        (let [results (fixture/keyed-results ctx model)
              identity [(:tenant-id ctx) model 1]
              b (vec (get (core/namespace-resolve! (:cache ctx) [identity]) identity))
              stored (fixture/keyed-stored-bytes)
              mine (filter (fn [[k _]] (and (< (count b) (count k)) (= b (subvec (vec k) 0 (count b))))) stored)
              results-raw (filter (fn [[k _]] (= 2 (nth k (count b)))) mine)
              l0 (filter (fn [[k _]] (let [k (vec k) n (count b)]
                                       (and (= 5 (nth k n))
                                            ;; level byte 0 right after b 05 ordinal(index) dir (OPT-8)
                                            (some (fn [index]
                                                    (let [p (vec (core/key-join (byte-array [5]) (#'core/keyed-index-ordinal-bytes {:indexes {:total :total}} index) (byte-array [0 0])))]
                                                      (and (< (+ n (count p)) (count k)) (= p (subvec k n (+ n (count p)))))))
                                                  [:identity :total]))))
                         mine)]
          (is (= 7 (count results)))
          (is (= [75 70 57] (subvec b 0 3)) "KF9 data prefix is kept (no version bump)")
          (is (seq results-raw))
          (is (every? (fn [[_ v]] (= #{:key :value} (set (keys (codec/decode (byte-array v)))))) results-raw))
          (is (= 14 (count l0)) "7 owners x (identity + total) level-zero members")
          (is (every? (fn [[_ v]] (zero? (count v))) l0) "string ids decode from the member: empty payloads"))))))

;; ---------------------------------------------------------------------------
;; Wave-2 integration conditions (OPT-9 audit C1, C2)
;; ---------------------------------------------------------------------------

(defn- typed-group [g] [(let [v (:value g)] (if (keyword? v) [:kw (namespace v) (name v)] [(class v) v])) (:total g)])

(deftest a-decrement-keeps-the-stored-group-value
  ;; C1: the removed position is decoded from compact sort data, which changes
  ;; some value forms; the surviving group keeps the extractor's stored value.
  (doseq [[label v] {:bigint 5N :weird-keyword (keyword "a/b" "c") :long 5}]
    (fixture/keyed-fixture
      (fn []
        (let [ctx (merge fixture/*keyed-ctx* {:read-model/batch-size 1 :read-model/deferred-build-threshold 0})
              model (keyword "index-layout-test" (str "c1-" (name label)))]
          (rmp/register-read-model! model (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
            {:events #{:keyed/change} :version 1 :key :owner
             :indexes {:a (fn [r] (if (= :v (:a r)) v (:a r)))}})
          (fixture/keyed-append! ctx (for [o (range 4)] {:owner (str "o" o) :a :v}))
          (rmp/progress ctx model)
          (let [before (mapv typed-group (rmp/index-groups ctx model :a))]
            (is (= [(typed-group {:value v :total 4})] before) label)
            (fixture/keyed-append! ctx [{:owner "o0" :a "other"}])
            (rmp/progress ctx model)
            (is (= #{(typed-group {:value v :total 3}) (typed-group {:value "other" :total 1})}
                   (set (map typed-group (rmp/index-groups ctx model :a))))
                [label :value-kept-after-decrement])
            (is (= ["o1" "o2" "o3"] (vec (rmp/index-keys ctx model {:index :a :equals v :limit 10}))))))))))

(deftest an-index-change-without-a-version-bump-fails-loudly
  ;; C2: old positions are read from sort data by field ordinal of the current
  ;; definition (renames keeping the count: index-names-test). Adding or removing an index without a version bump must raise
  ;; :grain.read-model/invalid-cache (never an index-out-of-bounds wedge, never
  ;; silently corrupted pages), and must leave the committed cache untouched.
  (doseq [change [:add-index :remove-index]]
    (fixture/keyed-fixture
      (fn []
        (let [ctx (merge fixture/*keyed-ctx* {:read-model/batch-size 4 :read-model/deferred-build-threshold 0})
              model (keyword "index-layout-test" (str "c2-" (name change)))
              register! (fn [indexes]
                          (rmp/register-read-model! model (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
                            {:events #{:keyed/change} :version 1 :key :owner
                             :indexes (into {} (map (fn [k] [k k])) indexes)}))]
          (register! [:b :c])
          (fixture/keyed-append! ctx (for [o (range 5)] {:owner (str "o" o) :b (str "b" o) :c (str "c" o)}))
          (rmp/progress ctx model)
          (let [pages-before (mapv #(mapv :key (:entries (rmp/page ctx model {:index % :limit 50}))) [:b :c])
                stored-before (fixture/keyed-stored-bytes)]
            (register! (case change :add-index [:a :b :c] :remove-index [:c]))
            (fixture/keyed-append! ctx [{:owner "o1" :a "a1x" :b "b1x" :c "c1x"} {:owner "o2" :b "b2x" :c "c2x"}])
            (dotimes [attempt 2]
              (let [e (try (rmp/progress ctx model) nil (catch clojure.lang.ExceptionInfo e e))]
                (is (some? e) [change attempt :throws])
                (is (= :grain.read-model/invalid-cache (:type (ex-data e))) [change attempt (some-> e .getMessage)])
                ;; OPT-10 (W2-C2): the progress record's index-name
                ;; fingerprint fails first; the sort-data field-count check
                ;; stays as a second guard (index-names-test).
                (is (= [[:b :c] (case change :add-index [:a :b :c] :remove-index [:c])]
                       ((juxt :stored-indexes :indexes) (ex-data e))) [change :index-names])))
            (is (= stored-before (fixture/keyed-stored-bytes)) [change :nothing-committed])
            ;; Back to the stored definition: catch-up succeeds and every page
            ;; holds each owner exactly once.
            (register! [:b :c])
            (is (= [5 5] (mapv count pages-before)))
            (doseq [index [:b :c]]
              (let [ks (mapv :key (:entries (rmp/page ctx model {:index index :limit 50})))]
                (is (= (sort ks) ["o0" "o1" "o2" "o3" "o4"]) [change index :no-duplicates-or-corruption])))))))))
