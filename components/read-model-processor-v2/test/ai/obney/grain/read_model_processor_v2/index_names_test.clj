(ns ai.obney.grain.read-model-processor-v2.index-names-test
  "OPT-10 (W2 audit C1/C2), FINAL (OPT-10 audit C2 = OPT-8 audit C3): the
   progress record stores the model's index layout (sorted non-identity index
   names, sorted :filtered-indexes, sorted search names), and any change of it
   without a :version bump fails loudly with :grain.read-model/invalid-cache,
   for incremental and deferred builds, with and without new events, including
   renames that keep the index count, the removal of a model's last index,
   filtered-index changes (also :identity, whose member layout depends on it)
   and search additions, removals and renames. Restoring the stored definition
   then reads exactly what a fresh build reads."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def ^:private model :index-names-test/items)
(def ^:private incremental {:read-model/batch-size 4 :read-model/deferred-build-threshold 0})
(def ^:private deferred {:read-model/batch-size 4 :read-model/deferred-build-threshold 8})

(def ^:private extractors {:a :x :a2 :x :b :y :c :x :d :y})

(defn- spec
  "names (a vector of index names) or {:names [...] :filtered #{...} :searches {...}}."
  [x]
  (if (map? x) (merge {:filtered #{} :searches {}} x) {:names x :filtered #{} :searches {}}))

(defn- register! [x]
  (let [{:keys [names filtered searches]} (spec x)]
    (rmp/register-read-model! model (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
      (cond-> {:events #{:keyed/change} :version 1 :key :owner
               :include? (fn [v] (not (:deleted v)))
               :indexes (into {} (map (fn [k] [k (extractors k)])) names)}
        (seq filtered) (assoc :filtered-indexes filtered)
        (seq searches) (assoc :searches searches)))))

(defn- layout [x]
  (let [{:keys [names filtered searches]} (spec x)]
    [(vec (sort names)) (vec (sort filtered)) (vec (sort (keys searches)))]))

(defn- history-1 [] (for [o (range 40)] {:owner o :x (str "x" (mod o 5)) :y (mod o 4)}))
(defn- history-2 []
  (concat (for [o (range 0 40 2)] {:owner o :x (str "w" (mod o 3)) :y (+ 10 (mod o 4))})
          [{:owner 7 :deleted true} {:owner 41 :x "new" :y 99}]))

(defn- reads [ctx x]
  (let [{:keys [names filtered searches]} (spec x)]
  {:values (vec (rmp/values ctx model))
   :pages (vec (for [index (conj (vec names) :identity) direction [:asc :desc]]
                 (mapv :key (:entries (rmp/page ctx model {:index index :direction direction :limit 100})))))
   :groups (vec (for [index names] (vec (rmp/index-groups ctx model index))))
   :keys (vec (for [index names
                    {:keys [value]} (into [] (rmp/index-groups ctx model index))]
                (vec (rmp/index-keys ctx model {:index index :equals value :limit 100}))))
   :index-values (into [] (rmp/index-values ctx model (vec names) (range 42)))
   :filtered (vec (for [index (sort filtered) direction [:asc :desc]]
                    (:keys (rmp/filtered-key-page ctx model (range 45) {:index index :direction direction :limit 100}))))
   :searches (vec (for [index (sort (keys searches)) query ["x1" "w2" "new" "x"]]
                    (sort (rmp/search-keys ctx model {:index index :query query :mode :prefix}))))}))

(defn- invalid-cache [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- raw-progress [ctx]
  (let [prefix (get (core/namespace-resolve! (:cache ctx) [[(:tenant-id ctx) model 1]]) [(:tenant-id ctx) model 1])]
    (kv/transact (:cache ctx) :read
      #(some-> ((:get %) (core/key-join prefix (byte-array [0]))) codec/decode))))

(defn- scenario
  "Build with `before` names, change to `after` without a version bump, check
   every attempt fails loudly and commits nothing, then restore `before` and
   compare all reads to a fresh build of the same history."
  [opts before after]
  (let [fresh (fixture/keyed-fixture
                (fn []
                  (let [ctx (merge fixture/*keyed-ctx* opts)]
                    (register! before)
                    (fixture/keyed-append! ctx (history-1))
                    (rmp/progress ctx model)
                    (fixture/keyed-append! ctx (history-2))
                    (reads ctx before))))]
    (fixture/keyed-fixture
      (fn []
        (let [ctx (merge fixture/*keyed-ctx* opts)]
          (register! before)
          (fixture/keyed-append! ctx (history-1))
          (rmp/progress ctx model)
          (is (= (layout before) (core/key-decode-compact (:index-layout (raw-progress ctx)))) [before :stored-layout])
          (is (not (contains? (rmp/progress ctx model) :index-layout)) "API progress is unchanged")
          (let [stored-before (fixture/keyed-stored-bytes)]
            (register! after)
            ;; No new events: reads must fail too (they would read a wrong field).
            (let [e (invalid-cache #(rmp/page ctx model {:limit 5}))]
              (is (= :grain.read-model/invalid-cache (:type e)) [before after :read-without-events])
              (is (= [(layout before) (layout after)]
                     [((juxt :stored-indexes :stored-filtered-indexes :stored-searches) e)
                      ((juxt :indexes :filtered-indexes :searches) e)])))
            (fixture/keyed-append! ctx (history-2))
            (dotimes [attempt 2]
              (doseq [[label f] {:progress #(rmp/progress ctx model)
                                 :values #(vec (rmp/values ctx model))
                                 :page #(rmp/page ctx model {:index (or (first (:names (spec after))) :identity) :limit 5})}]
                (is (= :grain.read-model/invalid-cache (:type (invalid-cache f))) [before after attempt label])))
            (is (= stored-before (fixture/keyed-stored-bytes)) [before after :nothing-committed])
            (register! before)
            (is (= fresh (reads ctx before)) [before after :restored-reads-equal-fresh-build])))))))

(def ^:private changes
  ;; [before after]
  [[[:a :b] [:a :b :c]]   ; add
   [[:a :b] [:b]]         ; remove (count changes: also caught by C2)
   [[:a :b] [:a2 :b]]     ; rename, same count, same field order
   [[:a :b] [:b :c]]      ; rename, same count, :b moves from field 3 to 2 (W2 audit §5)
   [[:a :b] [:b :a2]]     ; same as above with a different spelling
   [[:a :b] [:c :d]]      ; rename both
   [[:a] []]              ; remove the model's last index (W2 audit C3)
   [[] [:a]]])            ; add to an index-less model

(def ^:private layout-changes
  ;; FINAL (OPT-10 audit C2): filtered-index and search changes, the OPT-10
  ;; auditor's cases (aud10_test / aud10b_test) with :n -> :a, :k -> :b.
  [[{:names [:a :b]} {:names [:a :b] :filtered #{:identity}}]           ; #{} -> #{:identity}
   [{:names [:a :b] :filtered #{:identity}} {:names [:a :b]}]           ; #{:identity} -> #{}
   [{:names [:a :b]} {:names [:a :b] :filtered #{:a}}]                  ; #{} -> #{:n}
   [{:names [:a :b] :filtered #{:a}} {:names [:a :b] :filtered #{:b}}]  ; #{:n} -> #{:k}
   [{:names [:a :b] :filtered #{:a}} {:names [:a :b]}]                  ; #{:n} -> #{}
   [{:names [:a :b]} {:names [:a :b] :searches {:w :x}}]                ; add a search
   [{:names [:a :b] :searches {:w :x}} {:names [:a :b] :searches {:w2 :x}}] ; rename a search
   [{:names [:a :b] :searches {:w :x}} {:names [:a :b]}]                ; remove a search
   [{:names [] :searches {:w :x}} {:names [] :searches {:w :x} :filtered #{:identity}}]])

(deftest filtered-index-and-search-changes-without-a-version-bump-fail-loudly
  (doseq [[label opts] {:incremental incremental :deferred deferred}
          [before after] layout-changes]
    (testing [label before after]
      (scenario opts before after))))

(deftest index-name-changes-without-a-version-bump-fail-loudly
  (doseq [[label opts] {:incremental incremental :deferred deferred}
          [before after] changes]
    (testing [label before after]
      (scenario opts before after))))

(deftest an-unchanged-definition-is-accepted-everywhere
  ;; Re-registering the same names (in any map order) never trips the check.
  (fixture/keyed-fixture
    (fn []
      (let [ctx (merge fixture/*keyed-ctx* incremental)]
        (register! [:a :b :c])
        (fixture/keyed-append! ctx (history-1))
        (rmp/progress ctx model)
        (rmp/register-read-model! model (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
          {:events #{:keyed/change} :version 1 :key :owner
           :include? (fn [v] (not (:deleted v)))
           :indexes (array-map :c :x :b :y :a :x)})
        (fixture/keyed-append! ctx (history-2))
        (is (= 40 (count (into [] (rmp/values ctx model)))))
        (is (= [[:a :b :c] [] []] (core/key-decode-compact (:index-layout (raw-progress ctx)))))))))

(deftest a-version-bump-starts-a-new-namespace-with-the-new-names
  (fixture/keyed-fixture
    (fn []
      (let [ctx (merge fixture/*keyed-ctx* incremental)]
        (register! [:a :b])
        (fixture/keyed-append! ctx (history-1))
        (rmp/progress ctx model)
        (rmp/register-read-model! model (fn [s e] (merge s (dissoc e :event/type :event/id :event/timestamp :event/tags)))
          {:events #{:keyed/change} :version 2 :key :owner :indexes {:b :y :c :x}})
        (is (= 40 (count (into [] (rmp/values ctx model)))))
        (doseq [index [:b :c]]
          (is (= (range 40) (sort (map :key (:entries (rmp/page ctx model {:index index :limit 100})))))
              [index "every owner present once"]))))))

(deftest a-resumed-deferred-build-checks-the-staged-index-names
  ;; A failed source keeps the staged fold (OPT-4); a renamed definition must
  ;; not resume it (its staged sort data uses the old field order).
  (let [fresh (fixture/keyed-fixture
                (fn []
                  (let [ctx (merge fixture/*keyed-ctx* deferred)]
                    (register! [:a :b])
                    (fixture/keyed-append! ctx (concat (history-1) (history-2)))
                    (reads ctx [:a :b]))))]
    (fixture/keyed-fixture
      (fn []
        (let [ctx (merge fixture/*keyed-ctx* deferred)
              read-events es/read]
          (register! [:a :b])
          (fixture/keyed-append! ctx (concat (history-1) (history-2)))
          (with-redefs [es/read (fn [& args]
                                  (let [events (into [] (apply read-events args))]
                                    (concat (take 30 events) (lazy-seq (throw (ex-info "source failed" {}))))))]
            (is (thrown-with-msg? Exception #"source failed" (rmp/progress ctx model))))
          (is (nil? (raw-progress ctx)) "the model namespace is still cold; only staging holds progress")
          (register! [:b :c])
          (dotimes [attempt 2]
            (let [e (invalid-cache #(rmp/progress ctx model))]
              (is (= :grain.read-model/invalid-cache (:type e)) [attempt])
              (is (= [[:a :b] [:b :c]] ((juxt :stored-indexes :indexes) e)) [attempt])
              (is (= [[] [] [] []] ((juxt :stored-filtered-indexes :filtered-indexes :stored-searches :searches) e)) [attempt])
              (is (nil? (raw-progress ctx)) [attempt "nothing published"])))
          (register! [:a :b])
          (is (= fresh (reads ctx [:a :b])) "the restored definition resumes and publishes the same build"))))))

(deftest the-sort-data-field-count-check-remains-a-second-guard
  (fixture/keyed-fixture
    (fn []
      (let [sort-data! @(get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) 'keyed-sort-data!)
            data (core/sort-data-encode 1 {:a "x" :b 2})
            tx {:get (constantly data)}]
        (is (identical? data (sort-data! tx (byte-array 1) 1 4)))
        (doseq [n [3 5]]
          (let [e (try (sort-data! tx (byte-array 1) 1 n) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
            (is (= [:grain.read-model/invalid-cache 4 n] ((juxt :type :stored-fields :expected-fields) e)))))))))
