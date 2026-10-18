(ns ai.obney.grain.read-model-processor-v2.deferred-cold-build-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid]))

(def model :deferred-cold-build/items)
(def tenant (java.util.UUID. 71 1))
(def frozen-time (java.time.OffsetDateTime/parse "2026-10-15T00:00:00Z"))
(defn- cv [s]
  (or (get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) s)
      (throw (ex-info "No such processor var" {:var s}))))
(defn- det-hash [^bytes k] (clojure.lang.Murmur3/hashInt (java.util.Arrays/hashCode k)))
;; INT (OPT-3): promotion goes through ranked-promotion-hash [key prefix offset
;; member]; redefining it never forces (or stores) the per-cache key.
(defn- det-promotion-hash [_ _ _ ^bytes k] (det-hash k))

(def ^:private words ["alpha" "bravo" "charlie" "delta" "echo" "foxtrot" "ünïcode" "日本語テキスト" "x"])
(def ^:private owners
  (vec (concat (range 40) (map #(str "s" %) (range 20)) (map #(java.util.UUID. 9 %) (range 20)))))

(defn- bodies
  "Seeded fan-out history with updates, unchanged updates, deletes and re-includes."
  [seed n]
  (let [r (java.util.Random. seed) pick #(nth % (.nextInt r (count %)))]
    (vec (for [_ (range n)]
           {:inputs (vec (for [_ (range (inc (.nextInt r 3)))]
                           [(pick owners)
                            (case (.nextInt r 8)
                              0 {:deleted true}
                              1 {:deleted false}
                              {:score (when (pos? (.nextInt r 6)) (.nextInt r 9))
                               :name (str (pick words) " " (pick words))
                               :group (pick [:a :b :c nil])})]))}))))

(defn- register! []
  (rmp/register-read-model! model merge
    {:events #{:keyed/change} :version 1 :route :inputs
     :include? (fn [v] (not (:deleted v)))
     :indexes {:score :score :name :name :pair (fn [v] [(:group v) (:score v)])}
     :filtered-indexes #{:score}
     :searches {:name :name}}))

(defn- hex [b] (.formatHex (java.util.HexFormat/of) (byte-array b)))

(defn- rows-under [cache ^bytes prefix]
  (kv/transact cache :read
    #((:reduce %) {:prefix prefix}
      (fn [out {:keys [^bytes k v]}]
        (assoc out (hex (java.util.Arrays/copyOfRange k (alength prefix) (alength k))) (hex v)))
      (sorted-map))))

(defn- namespace-rows
  "Raw rows of the model and shared-identity namespaces, with their prefixes removed."
  [ctx]
  (let [model-id [tenant model 1] shared-id [tenant :grain.read-query/shared-identities 1]
        prefixes (core/namespace-resolve! (:cache ctx) [model-id shared-id])]
    {:model (rows-under (:cache ctx) (get prefixes model-id))
     :shared (rows-under (:cache ctx) (get prefixes shared-id))}))

(defn- stray-rows
  "Rows owned by no resolvable model/shared namespace (ordinary or scoped), no
   registry record, and no pending replaced-namespace record (KB9 kind 3, which
   is collected later by design)."
  ([ctx] (stray-rows ctx [[tenant model 1] [tenant :grain.read-query/shared-identities 1]]))
  ([ctx identities]
   (let [owned (set (map vec (vals (core/namespace-resolve! (:cache ctx) identities))))
         all (fixture/keyed-stored-bytes)
         head (fn [k n] (vec (take n k)))]
     (into (sorted-map)
           (keep (fn [[k _]]
                   (cond (= (head k 3) [75 82 57]) nil
                         (= (head k 4) [75 82 83 57]) nil
                         (and (= (head k 3) [75 70 57]) (contains? owned (head k 7))) nil
                         (and (= (head k 4) [75 70 83 57]) (contains? owned (head k 8))) nil
                         (and (= (head k 3) [75 66 57]) (= 3 (nth k 35 nil))) nil
                         ;; INT (OPT-3): the per-cache promotion key at KP9 0xFF.
                         (= (head k 3) [75 80 57]) nil
                         :else [(hex k) (head k 4)])))
           all))))

(defn- reads [ctx]
  (let [ids (mapv :key (rmp/values ctx model))]
    {:values (vec (rmp/values ctx model))
     :progress (dissoc (rmp/progress ctx model) :checked-through)
     :pages (vec (for [index [:identity :score :name :pair] direction [:asc :desc] page [1 2 3]]
                   (mapv :key (:entries (rmp/page ctx model {:index index :direction direction
                                                             :page page :limit 7})))))
     :prefix (mapv :key (:entries (rmp/page ctx model {:index :pair :prefix [:a] :limit 100})))
     :groups (vec (rmp/index-groups ctx model :score))
     :pair-groups (vec (rmp/index-groups ctx model :pair))
     :equals (rmp/index-keys ctx model {:index :score :equals 3 :limit 100})
     :rank (rmp/index-rank ctx model {:index :score :equals 3} 5)
     :search (sort-by str (rmp/search-keys ctx model {:index :name :query "lph"}))
     :search-prefix (sort-by str (rmp/search-keys ctx model {:index :name :query "日" :mode :prefix}))
     :filtered (vec (for [direction [:asc :desc]]
                      (:keys (rmp/filtered-key-page ctx model (take 30 (reverse ids))
                                                    {:index :score :direction direction :limit 10}))))}))

(defn- run
  "Build in a fresh cache with frozen event ids and the deterministic promotion
   hash. hooks: :before (ctx) runs after appending, :after (ctx) after the first read."
  ([opts] (run opts {}))
  ([opts {:keys [before redefs history] :or {history [(bodies 7 300)]}}]
   (fixture/keyed-fixture
     (fn []
       (let [counter (atom 0)
             ctx (merge (assoc fixture/*keyed-ctx* :tenant-id tenant) opts)]
         (with-redefs-fn (merge {(cv 'ranked-promotion-hash) det-promotion-hash
                                 #'time/now (constantly frozen-time)
                                 #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
                                redefs)
           (fn []
             (register!)
             (doseq [[i h] (map-indexed vector history)]
               (fixture/keyed-append! ctx h)
               (when (and before (zero? i)) (before ctx))
               (rmp/progress ctx model))
             (let [r (reads ctx)]
               {:reads r :rows (namespace-rows ctx) :stray (stray-rows ctx)}))))))))

(def ^:private incremental {:read-model/batch-size 8 :read-model/deferred-build-threshold 0})
(def ^:private deferred {:read-model/batch-size 8 :read-model/deferred-build-threshold 16})

(deftest deferred-build-is-byte-identical-to-incremental-batches
  (let [reference (run incremental)]
    (is (seq (get-in reference [:reads :values])))
    (is (seq (get-in reference [:reads :search])))
    (doseq [[label opts] {:deferred deferred
                          :one-unit-per-pass (assoc deferred :read-model/deferred-build-entries 100)
                          :sorted-runs-below-n (assoc deferred :read-model/deferred-build-entries 7)
                          :threshold-not-reached (assoc deferred :read-model/deferred-build-threshold 100000)}]
      (testing label
        (let [candidate (run opts)]
          (is (= (:reads reference) (:reads candidate)))
          (is (= (get-in reference [:rows :shared]) (get-in candidate [:rows :shared])))
          (is (= (get-in reference [:rows :model]) (get-in candidate [:rows :model]))
              "Every model row, incl. ordinals, towers, spans, groups and postings, is identical")
          (is (empty? (:stray candidate)) "No staging rows, markers or replaced namespaces remain"))))))

(deftest later-incremental-writers-continue-a-published-build
  ;; Later batches use the unchanged incremental path, as an older processor would.
  (let [history [(bodies 7 300) (bodies 8 60) (bodies 9 40)]
        reference (run incremental {:history history})
        candidate (run deferred {:history history})]
    (is (= (:reads reference) (:reads candidate)))
    (is (= (:rows reference) (:rows candidate)))
    (is (empty? (:stray candidate)))))

(deftest registry-stays-valid-after-publish
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 8 :read-model/deferred-build-threshold 16)]
        (register!)
        (fixture/keyed-append! ctx (bodies 7 120))
        (is (seq (vec (rmp/values ctx model))))
        ;; Asserts bijection, counter coverage and address agreement.
        (is (seq (fixture/keyed-materialized-bytes)))))))

(deftest killed-deferred-builds-are-invisible-and-recover
  (let [reference (run incremental)
        apply-batch @(cv 'keyed-apply-batch!)
        span-finish @(cv 'ranked-span-finish!)
        publish @(cv 'deferred-publish!)
        points {:during-fold
                (fn [died] (let [calls (atom 0)]
                             {(cv 'keyed-apply-batch!)
                              (fn [ctx & args]
                                (let [out (apply apply-batch ctx args)]
                                  (when (and (:ai.obney.grain.read-model-processor-v2.core/deferred ctx)
                                             (= 5 (swap! calls inc)))
                                    (throw died))
                                  out))}))
                :during-index-passes
                (fn [died] (let [calls (atom 0)]
                             {(cv 'ranked-span-finish!)
                              (fn [& args] (when (= 3 (swap! calls inc)) (throw died)) (apply span-finish args))}))
                :before-publish
                (fn [died] {(cv 'deferred-publish!) (fn [& _] (throw died))})
                :after-publish-commit
                (fn [died] {(cv 'deferred-publish!) (fn [& args] (apply publish args) (throw died))})}]
    (doseq [[label inject] points]
      (testing label
        (let [seen (atom nil)
              recovered (run (assoc deferred :read-model/deferred-build-entries 1)
                             {:before (fn [ctx]
                                        (is (thrown? Error
                                              (with-redefs-fn (merge {(cv 'deferred-abandon!) (fn [& _] nil)
                                                                      (cv 'deferred-release!) (fn [& _] nil)}
                                                                     (inject (Error. "simulated kill -9")))
                                                #(rmp/progress ctx model))))
                                        (reset! seen (with-redefs-fn {(cv 'keyed-catch-up!) (fn [& _] nil)}
                                                       #(vec (rmp/values ctx model)))))})]
          (if (= label :after-publish-commit)
            (is (= (get-in reference [:reads :values]) @seen) "A committed publish is complete")
            (is (= [] @seen) "Readers see the untouched cold namespace, never a partial build"))
          (is (= (:reads reference) (:reads recovered)))
          (is (= (:rows reference) (:rows recovered)))
          (is (empty? (:stray recovered)) "Recovery wipes the crashed staging namespace and marker"))))))

(deftest superseded-or-overtaken-builds-fall-back-to-incremental
  (let [reference (run incremental)]
    (testing "a concurrent builder discarded this build's marker"
      (let [apply-batch @(cv 'keyed-apply-batch!) calls (atom 0)
            candidate (run deferred
                           {:redefs {(cv 'keyed-apply-batch!)
                                     (fn [ctx name d b vs vr events & more]
                                       (let [out (apply apply-batch ctx name d b vs vr events more)]
                                         (when-let [s (:ai.obney.grain.read-model-processor-v2.core/deferred ctx)]
                                           (when (= 3 (swap! calls inc))
                                             (kv/transact (:cache ctx) :write #((:delete %) (:marker s)))))
                                         out))}})]
        (is (= (:reads reference) (:reads candidate)))
        (is (= (:rows reference) (:rows candidate)))
        (is (empty? (:stray candidate)))))
    (testing "another writer advanced the cold namespace before publish"
      (let [build @(cv 'deferred-build!) catch-up @(cv 'keyed-catch-up!)
            candidate (run deferred
                           {:redefs {(cv 'deferred-build!)
                                     (fn [ctx d staging]
                                       (build ctx d staging)
                                       ;; An older processor's own incremental build.
                                       (let [identity [tenant model 1]
                                             b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)]
                                         (catch-up (assoc ctx :ai.obney.grain.read-model-processor-v2.core/no-deferral true)
                                                   model d b (@(cv 'keyed-head) ctx) identity)))}})]
        (is (= (:reads reference) (:reads candidate)))
        (is (empty? (:stray candidate)))))))

(deftest failing-fold-keeps-staged-progress-like-incremental-batches
  (let [poison {:inputs [[3 {:score 1 :name "poison" :group :a}]]}
        attempt (fn [opts]
                  (fixture/keyed-fixture
                    (fn []
                      (let [ctx (merge (assoc fixture/*keyed-ctx* :tenant-id tenant) opts) counter (atom 0)
                            apply-batch @(cv 'keyed-apply-batch!) calls (atom [])]
                        (with-redefs-fn {#'time/now (constantly frozen-time)
                                         #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
                          (fn []
                            (rmp/register-read-model! model merge
                              {:events #{:keyed/change} :version 1 :route :inputs
                               :include? (fn [v] (not (:deleted v)))
                               :indexes {:score :score} :searches {:name :name}
                               :result-schema [:fn #(not= "poison" (:name %))]})
                            (fixture/keyed-append! ctx (conj (bodies 7 300) poison))
                            (let [first-failure (try (rmp/progress ctx model) nil
                                                     (catch clojure.lang.ExceptionInfo e (ex-message e)))
                                  second-failure (with-redefs-fn {(cv 'keyed-apply-batch!)
                                                                  (fn [& args] (swap! calls conj (count (nth args 6)))
                                                                    (apply apply-batch args))}
                                                   #(try (rmp/progress ctx model) nil
                                                         (catch clojure.lang.ExceptionInfo e (ex-message e))))]
                              {:failures [first-failure second-failure] :second-attempt-batches @calls})))))))
        baseline (attempt incremental)
        candidate (attempt deferred)]
    (is (every? some? (:failures candidate)))
    (is (= (:failures baseline) (:failures candidate)) "Same failure on every attempt")
    (is (= [5] (:second-attempt-batches baseline)))
    (is (= (:second-attempt-batches baseline) (:second-attempt-batches candidate))
        "The retry resumes the staged fold at its watermark, re-applying only the failing batch")))

(deftest concurrent-readers-block-until-the-build-is-published
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 8 :read-model/deferred-build-threshold 16)
            build @(cv 'deferred-build!)
            entered (promise) published (atom nil)]
        (register!)
        (fixture/keyed-append! ctx (bodies 7 300))
        (with-redefs-fn {(cv 'deferred-build!)
                         (fn [& args] (deliver entered true) (Thread/sleep 300) (apply build args))
                         (cv 'deferred-publish!)
                         (let [publish @(cv 'deferred-publish!)]
                           (fn [& args] (let [out (apply publish args)] (reset! published (System/nanoTime)) out)))}
          (fn []
            (let [builder (future (vec (rmp/values ctx model)))
                  _ (deref entered 10000 nil)
                  reader (future (let [v (vec (rmp/values ctx model))] [(System/nanoTime) v]))
                  [read-at seen] @reader]
              (is (= @builder seen) "The concurrent reader saw the complete published build")
              (is (some? @published))
              (is (> read-at @published) "The reader completed only after publish"))))))))

(deftest scoped-namespaces-use-deferred-builds
  (let [tag (java.util.UUID. 5 5)
        go (fn [opts]
             (fixture/keyed-fixture
               (fn []
                 (let [ctx (merge (assoc fixture/*keyed-ctx* :tenant-id tenant) opts)
                       scoped (rmp/scoped-context ctx {:tags #{[:shard tag]}})]
                   (register!)
                   (es/append (:event-store ctx)
                              {:tenant-id tenant
                               :events (vec (map-indexed
                                              (fn [i body] (es/->event {:type :keyed/change :body body
                                                                       :tags (if (even? i) #{[:shard tag]} #{})}))
                                              (bodies 11 300)))})
                   [(vec (rmp/values scoped model))
                    (vec (rmp/search-keys scoped model {:index :name :query "lph"}))
                    (mapv :key (:entries (rmp/page scoped model {:index :pair :direction :desc :limit 50})))
                    (vec (rmp/values ctx model))
                    (stray-rows ctx)]))))
        a (with-redefs [core/ranked-promotion-hash det-promotion-hash] (go incremental))
        b (with-redefs [core/ranked-promotion-hash det-promotion-hash] (go deferred))]
    (is (seq (first a)))
    (is (= (pop a) (pop b)))))

(deftest source-failure-during-a-staged-fold-retries-deferral
  (let [reference (run incremental)
        read-events es/read
        deferred-starts (atom 0)
        start @(cv 'deferred-claim!)
        candidate (run deferred
                       {:redefs {(cv 'deferred-claim!) (fn [& args] (swap! deferred-starts inc) (apply start args))}
                        :before (fn [ctx]
                                  (with-redefs [es/read (fn [& args]
                                                          (let [events (into [] (apply read-events args))]
                                                            (concat (take 200 events)
                                                                    (lazy-seq (throw (ex-info "source failed" {}))))))]
                                    (is (thrown-with-msg? Exception #"source failed" (rmp/progress ctx model))))
                                  (is (= [] (with-redefs-fn {(cv 'keyed-catch-up!) (fn [& _] nil)}
                                              #(vec (rmp/values ctx model))))
                                      "Nothing from the abandoned staging build is visible"))})]
    (is (= 2 @deferred-starts) "The retry resumes the staged build; no failure record was left")
    (is (= (:reads reference) (:reads candidate)))
    (is (= (:rows reference) (:rows candidate)))
    (is (empty? (:stray candidate)))))

(defn- registry-records [ctx]
  (count (filter #(let [h (vec (take 3 (key %)))] (or (= h [75 82 57]) (= (vec (take 4 (key %))) [75 82 83 57])))
                 (fixture/keyed-stored-bytes))))

(defn- as-another-process
  "Run f as a different (live) process would: another pid, none of this
   process's in-process build ownership."
  [f]
  (let [parent (.get (.parent (java.lang.ProcessHandle/current)))]
    (with-redefs-fn {(cv 'deferred-self) (delay {:pid (.pid ^java.lang.ProcessHandle parent)
                                                 :started (@(cv 'deferred-start-ms) parent)})
                     (cv 'deferred-owners*) (atom {})}
      f)))

(defn- publish-during-build
  "Redefinition of deferred-build! that first runs a complete, independent
   catch-up of the same identity (another process/build that publishes; it
   judges this build's lease expired and takes it over)."
  [identity-of]
  (let [build @(cv 'deferred-build!) catch-up @(cv 'keyed-catch-up!) nested (atom 0)]
    {(cv 'deferred-build!)
     (fn [ctx d staging]
       (when (= 1 (swap! nested inc))
         (let [identity (identity-of ctx)
               b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)]
           (as-another-process
             #(catch-up (assoc ctx :read-model/deferred-build-lease-ms 0) model d b (@(cv 'keyed-head) ctx) identity))))
       (build ctx d staging))}))

(deftest another-build-publishing-during-ours-leaves-no-copy
  (testing "ordinary namespace"
    (let [reference (run incremental)
          candidate (run deferred {:redefs (publish-during-build (fn [_] [tenant model 1]))})]
      (is (= (:reads reference) (:reads candidate)))
      (is (= (:rows reference) (:rows candidate)))
      (is (empty? (:stray candidate)) "The superseded build re-resolves instead of folding into the replaced namespace")))
  (testing "scoped namespace"
    (let [tag (java.util.UUID. 5 6)
          scope {:tags #{[:shard tag]}}
          scope-id (@(cv 'keyed-scope-identity) scope)
          go (fn [opts redefs]
               (fixture/keyed-fixture
                 (fn []
                   (with-redefs-fn (merge {(cv 'ranked-promotion-hash) det-promotion-hash} redefs)
                     (fn []
                       (let [ctx (merge (assoc fixture/*keyed-ctx* :tenant-id tenant) opts)
                             scoped (rmp/scoped-context ctx scope)]
                         (register!)
                         (es/append (:event-store ctx)
                                    {:tenant-id tenant
                                     :events (mapv #(es/->event {:type :keyed/change :body % :tags #{[:shard tag]}})
                                                   (bodies 13 300))})
                         [(vec (rmp/values scoped model))
                          (sort-by str (rmp/search-keys scoped model {:index :name :query "lph"}))
                          (stray-rows ctx [[tenant model 1 scope-id]
                                           [tenant :grain.read-query/shared-identities 1 scope-id]])]))))))
          [va sa _] (go incremental {})
          [vb sb stray] (go deferred (publish-during-build (fn [_] [tenant model 1 scope-id])))]
      (is (seq va))
      (is (= [va sa] [vb sb]))
      (is (empty? stray)))))

(deftest replaced-namespace-copies-are-collected
  ;; An older writer that resolved the cold namespace just before publish folds
  ;; into it for its own session; that copy is collected once it is idle.
  (doseq [[label gc-ms expect-empty?] [[:after-delay 0 true] [:before-delay (* 60 60 1000) false]]]
    (testing label
      (let [orphan-rows (atom 0)
            candidate
            (run deferred
                 ;; Two warm catch-ups with work after the orphan: the first observes
                 ;; its fingerprint, the second (unchanged, delay elapsed) collects.
                 {:history [(bodies 7 300) (bodies 8 20) (bodies 9 20)]
                  :redefs {(cv 'deferred-replaced-gc-ms) gc-ms}
                  :before (fn [ctx]
                            (rmp/progress ctx model)
                            (let [staging (@(cv 'deferred-staging-identity) [tenant model 1])
                                  replaced (get (core/namespace-resolve! (:cache ctx) [staging]) staging)]
                              ;; Simulated older-writer fold into the replaced namespace.
                              (kv/transact (:cache ctx) :write
                                (fn [tx] (dotimes [i 25]
                                           ((:put tx) (byte-array (concat replaced [1 i])) (byte-array [1])))))
                              (reset! orphan-rows 25)))})]
        (is (= 25 @orphan-rows))
        (if expect-empty?
          (is (empty? (:stray candidate)) "Collected once observed unchanged across the delay")
          (is (= 25 (count (:stray candidate))) "Kept while an older session may still be folding"))))))

(deftest flaky-source-converges-with-bounded-registry
  (let [reference (run incremental)
        read-events es/read
        sizes (atom []) failures (atom 0)
        candidate
        (run deferred
             {:before (fn [ctx]
                        (loop []
                          (swap! sizes conj (registry-records ctx))
                          (when (and (< @failures 10)
                                     (try (with-redefs [es/read (fn [& args]
                                                                  (let [events (into [] (apply read-events args))]
                                                                    ;; every read delivers at most 120 events
                                                                    (if (> (count events) 120)
                                                                      (concat (take 120 events)
                                                                              (lazy-seq (throw (ex-info "source failed" {}))))
                                                                      events)))]
                                            (rmp/progress ctx model)
                                            false)
                                          (catch Exception _ true)))
                            (swap! failures inc)
                            (recur))))})]
    (is (= 2 @failures) "300 events at 120 per read: two failed reads, then the resumed build completes")
    ;; Counter + model, shared and one staging identity (forward + reverse each):
    ;; constant across retries instead of +2 records per attempt.
    (is (= [0 7 7] @sizes) "Registry records before each attempt")
    (is (= (:reads reference) (:reads candidate)))
    (is (empty? (:stray candidate)))))

(deftest collection-never-wipes-a-namespace-that-is-still-being-folded
  ;; An older-style writer resolved the cold namespace before publish and folds
  ;; into it afterwards. GC runs (delay 0) twice during that fold: the progress
  ;; fingerprint changes between observations, so nothing is collected and the
  ;; older session reads a complete fold. Once it is idle, it is collected.
  (fixture/keyed-fixture
    (fn []
      (let [counter (atom 0)
            ctx (assoc fixture/*keyed-ctx* :tenant-id tenant :read-model/batch-size 8
                       :read-model/deferred-build-threshold 16)
            identity [tenant model 1]
            shared [tenant :grain.read-query/shared-identities 1]]
        (with-redefs-fn {#'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))
                         (cv 'deferred-replaced-gc-ms) 0}
          (fn []
            (register!)
            (fixture/keyed-append! ctx (bodies 7 300))
            (let [b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)
                  head (@(cv 'keyed-head) ctx)
                  d (get (rmp/global-read-model-registry) model)
                  _ (rmp/progress ctx model)
                  truth (count (vec (rmp/values ctx model)))
                  apply-batch @(cv 'keyed-apply-batch!) n (atom 0)
                  warm-read! (fn [] (fixture/keyed-append! ctx (bodies (+ 20 @n) 3))
                               (with-redefs-fn {(cv 'keyed-apply-batch!) apply-batch}
                                 #(rmp/progress ctx model)))]
              (with-redefs-fn {(cv 'keyed-apply-batch!)
                               (fn [& args]
                                 (let [out (apply apply-batch args)]
                                   (when (#{3 6} (swap! n inc)) (warm-read!))
                                   out))}
                #(@(cv 'keyed-catch-up!)
                  (assoc ctx :ai.obney.grain.read-model-processor-v2.core/no-deferral true
                         :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix
                         (get (core/namespace-resolve! (:cache ctx) [shared]) shared))
                  model d b head identity))
              (is (= truth (kv/transact (:cache ctx) :read
                             #((:reduce %) {:prefix (core/key-join b (byte-array [2]))} (fn [c _] (inc c)) 0)))
                  "The older session's namespace holds a complete fold")
              (dotimes [_ 2] (warm-read!))
              (is (empty? (stray-rows ctx)) "Collected after two unchanged observations"))))))))

(deftest heartbeat-keeps-the-lease-fresh-across-long-steps
  (fixture/keyed-fixture
    (fn []
      (let [lease 150
            ctx (assoc fixture/*keyed-ctx* :tenant-id tenant :read-model/batch-size 8
                       :read-model/deferred-build-threshold 16 :read-model/deferred-build-lease-ms lease)
            build @(cv 'deferred-build!) ages (atom [])]
        (register!)
        (fixture/keyed-append! ctx (bodies 7 300))
        (with-redefs-fn {(cv 'deferred-build!)
                         (fn [c d staging]
                           ;; A step ten times longer than the lease, outside any transaction.
                           (dotimes [_ 30]
                             (Thread/sleep 50)
                             (swap! ages conj (- (System/currentTimeMillis)
                                                 (:at (kv/transact (:cache c) :read
                                                        #(@(cv 'deferred-marker) % [tenant model 1]))))))
                           (build c d staging))}
          #(is (seq (vec (rmp/values ctx model)))))
        (is (= 30 (count @ages)))
        (is (every? #(< % lease) @ages) (str "Heartbeat ages stay below the lease: max " (apply max @ages)))))))

(deftest late-claim-after-another-publish-never-takes-the-replaced-namespace
  ;; P2 reads cold progress, another build publishes, an older-style writer
  ;; starts folding into the replaced namespace b, then P2 claims: the claim
  ;; must re-validate b under its transaction and restart instead.
  (fixture/keyed-fixture
    (fn []
      (let [counter (atom 0)
            ctx (assoc fixture/*keyed-ctx* :tenant-id tenant :read-model/batch-size 8
                       :read-model/deferred-build-threshold 16)
            identity [tenant model 1]
            shared [tenant :grain.read-query/shared-identities 1]
            rows-under (fn [^bytes p] (kv/transact (:cache ctx) :read #((:reduce %) {:prefix p} (fn [c _] (inc c)) 0)))]
        (with-redefs-fn {#'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))
                         (cv 'ranked-promotion-hash) det-promotion-hash}
          (fn []
            (register!)
            (fixture/keyed-append! ctx (bodies 7 300))
            (let [b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)
                  head (@(cv 'keyed-head) ctx)
                  d (get (rmp/global-read-model-registry) model)
                  p2ctx (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix
                               (get (core/namespace-resolve! (:cache ctx) [shared]) shared))
                  read-events es/read fired (atom false) older-rows (atom nil) claims (atom [])
                  claim @(cv 'deferred-claim!)]
              (with-redefs-fn
                {#'es/read (fn [& args]
                             (when (compare-and-set! fired false true)
                               (with-redefs-fn {#'es/read read-events} #(rmp/progress ctx model))
                               (@(cv 'keyed-apply-batch!) p2ctx model d b (constantly nil) (constantly nil)
                                (vec (take 8 (into [] (read-events (:event-store ctx) {:tenant-id tenant :types #{:keyed/change}})))))
                               (reset! older-rows (rows-under b)))
                             (apply read-events args))
                 (cv 'deferred-claim!) (fn [& args] (let [c (apply claim args)] (swap! claims conj (if (map? c) :claimed c)) c))}
                #(let [r (@(cv 'keyed-catch-up!) p2ctx model d b head identity)]
                   (is (not (java.util.Arrays/equals ^bytes r ^bytes b)) "P2 reads the published namespace")))
              (is (pos? @older-rows))
              (is (= @older-rows (rows-under b)) "The older writer's rows in the replaced namespace survive")
              ;; P1 (the publishing build) claims first; P2's late claim restarts.
              (is (= [:claimed :restart] @claims) "The late claim restarts instead of taking the replaced namespace")
              (is (= (count (vec (rmp/values ctx model)))
                     (count (filter (fn [[id v]] (not (:deleted v)))
                                    (reduce (fn [m {:keys [inputs]}] (reduce (fn [m [o in]] (update m o merge in)) m inputs))
                                            {} (bodies 7 300)))))))))))))

(deftest stalled-owner-loses-its-lease-and-cannot-publish-after-takeover
  (fixture/keyed-fixture
    (fn []
      (let [lease 150 stall 300
            ctx (assoc fixture/*keyed-ctx* :tenant-id tenant :read-model/batch-size 8
                       :read-model/deferred-build-threshold 16 :read-model/deferred-build-lease-ms lease
                       :read-model/deferred-build-max-stall-ms stall)
            identity [tenant model 1]
            build @(cv 'deferred-build!) publish @(cv 'deferred-publish!) claim @(cv 'deferred-claim!)
            entered (promise) wake (promise) first-build (atom nil) published-builds (atom [])
            marker-age #(- (System/currentTimeMillis)
                           (:at (kv/transact (:cache ctx) :read (fn [tx] (@(cv 'deferred-marker) tx identity)))))]
        (register!)
        (fixture/keyed-append! ctx (bodies 7 300))
        (with-redefs-fn {(cv 'deferred-build!)
                         (fn [c d staging]
                           (when (compare-and-set! first-build nil (:build staging))
                             ;; The build thread is stuck: no progress, no transactions.
                             (deliver entered staging) @wake)
                           (build c d staging))
                         (cv 'deferred-publish!)
                         (fn [c i b staging] (let [p (publish c i b staging)]
                                               (when p (swap! published-builds conj (:build staging))) p))}
          (fn []
            (let [owner (future (vec (rmp/values ctx model)))
                  staging (deref entered 10000 nil)
                  b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)]
              (Thread/sleep (+ stall (* 3 lease)))
              (is (> (marker-age) lease) "With no progress the heartbeat stops and the lease lapses")
              ;; Another process takes over: its claim finds the lease expired. (A
              ;; thread of this process would instead see the in-process owner
              ;; as live and wait.)
              (let [taken (as-another-process #(claim ctx identity b))]
                (is (map? taken))
                (is (:resumed? taken) "The taker resumes the staged fold")
                (deliver wake true)
                (let [values @owner]
                  (is (seq values))
                  (is (not-any? #{(:build staging)} @published-builds)
                      "The stalled owner never publishes under its old ownership")
                  (is (= 1 (count @published-builds)))
                  (is (empty? (stray-rows ctx))))))))))))

(deftest takeover-generation-doubles-resets-and-is-capped
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :tenant-id tenant :read-model/deferred-build-max-stall-ms 1000
                       :read-model/deferred-build-max-stall-cap-ms 6000)
            identity [tenant model 1]
            claim* @(cv 'deferred-claim!) stamp @(cv 'deferred-stamp!) stall @(cv 'deferred-max-stall-ms)
            ;; Each claim is an attempt of this process that then ends without
            ;; releasing its marker (as a dead build thread would), so the next
            ;; claim finds a same-process marker that is no longer live.
            claim (fn [ctx identity b]
                    (let [c (claim* ctx identity b)]
                      (when (map? c) (@(cv 'deferred-disown!) ctx identity (:build c)))
                      c))
            marker #(kv/transact (:cache ctx) :read (fn [tx] (@(cv 'deferred-marker) tx identity)))]
        (register!)
        (fixture/keyed-append! ctx (bodies 7 50))
        (let [b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)
              ;; A same-process marker whose build is no longer owned in-process is
              ;; stale, so each claim is a takeover of an owner that never folded anything.
              gens (vec (for [_ (range 6)] (do (claim ctx identity b) (:generation (marker)))))]
          (is (= [0 1 2 3 4 5] gens) "Each takeover without progress raises the generation")
          (is (= [1000 2000 4000 6000 6000] (mapv #(stall ctx %) [0 1 2 3 30])) "Doubling, capped")
          (is (= 60000 (stall {} 0)) "Default base max-stall")
          (is (= (* 10 60 1000) (stall {} 30)) "Default cap (10 min since wave-2 integration)")
          (is (= [480000 600000] (mapv #(stall {} %) [3 4])) "Default doubling reaches the cap at generation 4")
          (testing "a folded batch resets the generation"
            (let [s (claim ctx identity b)]
              (is (= 6 (:generation (marker))))
              (kv/transact (:cache ctx) :write #(stamp % s true))
              (is (= [0 true] ((juxt :generation :progressed) (marker))))
              (claim ctx identity b)
              (is (= 0 (:generation (marker))) "Taking over an owner that made progress starts at the base")))
          (testing "a superseded owner cannot reset or stamp the new owner's marker"
            (let [old (claim ctx identity b) _ (claim ctx identity b) before (marker)]
              ;; old took over the reset (unprogressed) owner: 1; the new owner: 2.
              (is (= 2 (:generation before)))
              (kv/transact (:cache ctx) :write #(stamp % old true))
              (is (= before (marker)))
              (is (thrown? clojure.lang.ExceptionInfo
                           (kv/transact (:cache ctx) :write #(@(cv 'deferred-guard!) % old))))))
          (testing "a released (failed) build is resumed at the base generation"
            (let [s (claim ctx identity b)]
              (@(cv 'deferred-release!) ctx s)
              (claim ctx identity b)
              (is (= 0 (:generation (marker))))))
          (testing "waiting never changes the generation"
            (let [m (marker)]
              (@(cv 'deferred-await!) ctx identity)
              (is (= m (marker))))))))))
