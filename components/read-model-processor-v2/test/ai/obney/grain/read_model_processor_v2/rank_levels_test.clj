(ns ai.obney.grain.read-model-processor-v2.rank-levels-test
  "OPT-6: per-index rank levels sized by N and re-leveled online.
   Every check compares reads against an order computed from level-0 member
   bytes alone, so upper-level navigation (rank, nth, spans, span bitmaps,
   top-level totals) is verified independently of the structure under test."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            ;; :keyed/change event definition
            [ai.obney.grain.read-model-processor-v2.interface-test]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid])
  (:import [java.util Arrays]))

(def model :rank-levels-test/items)
(def members-model :rank-levels-test/members)
(def tenant (java.util.UUID. 72 1))
(def frozen-time (java.time.OffsetDateTime/parse "2026-10-16T00:00:00Z"))
(def d-shape {:indexes {:score :score :name :name} :filtered-indexes #{:score}})

(defn- cv [s]
  (or (get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) s)
      (throw (ex-info "No such processor var" {:var s}))))
(defn- det-hash [^bytes k] (clojure.lang.Murmur3/hashInt (Arrays/hashCode k)))
(defn- det-promotion-hash [_ _ _ ^bytes k] (det-hash k))

(defn- register! []
  (rmp/register-read-model! model merge
    {:events #{:keyed/change} :version 1 :route :inputs
     :include? (fn [v] (not (:deleted v)))
     :indexes (:indexes d-shape)
     :filtered-indexes (:filtered-indexes d-shape)})
  ;; Membership side of joined pages (every included owner).
  (rmp/register-read-model! members-model merge
    {:events #{:keyed/change} :version 1 :route :inputs
     :include? (fn [v] (not (:deleted v)))}))

(defn- with-cache
  "Fresh LMDB cache + in-memory event store (larger map than the shared fixture)."
  [f]
  (let [dir (str "/tmp/grain-rank-levels-test-" (random-uuid))
        store (es/start {:conn {:type :in-memory}})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir dir :db-name "folds" :map-size (* 512 1024 1024)}))
        registry (rmp/global-read-model-registry)]
    (try (f {:cache cache :event-store store :tenant-id tenant})
         (finally
           (reset! rmp/read-model-registry* registry)
           (kv/stop cache) (es/stop store)
           (doseq [^java.io.File file (reverse (file-seq (java.io.File. dir)))] (.delete file))))))

(defn- append! [ctx bodies]
  (doseq [chunk (partition-all 200 bodies)]
    (let [r (es/append (:event-store ctx)
                       {:tenant-id (:tenant-id ctx)
                        :events (mapv #(es/->event {:type :keyed/change :body %}) chunk)})]
      (when (:cognitect.anomalies/category r) (throw (ex-info "append failed" {:anomaly r}))))))

(defn- puts
  "Events inserting/updating owners ids, 50 inputs per event."
  [ids f]
  (mapv (fn [chunk] {:inputs (mapv (fn [i] [i (f i)]) chunk)}) (partition-all 50 ids)))

(defn- item [i] {:score (mod (* 7 i) 37) :name (format "n%07d" (mod (* 7919 i) 100003)) :deleted false})

;; ---------------------------------------------------------------------------
;; Reference reads from level-0 member order only
;; ---------------------------------------------------------------------------

(defn- member [index position id] ((cv 'keyed-index-member) d-shape index :asc position id))

(defn- desc-order
  "Descending order of the former stored descending copy (wave-2 integration:
   only ascending members are stored, OPT-5/OPT-7): values descending, equal
   values ascending by member suffix (id)."
  [index]
  (let [compact? ((cv 'keyed-compact-identity?) d-shape index)
        split (fn [^bytes m] (let [n (core/key-skip m 0 compact?)]
                               [(Arrays/copyOfRange m 0 (int n)) (Arrays/copyOfRange m (int n) (alength m))]))]
    (fn [a b]
      (let [[va sa] (split (first a)) [vb sb] (split (first b))
            c (Arrays/compareUnsigned ^bytes va ^bytes vb)]
        (if (zero? c) (Arrays/compareUnsigned ^bytes sa ^bytes sb) (- c))))))

(defn- expected-order [values index direction]
  (let [f (get-in d-shape [:indexes index])]
    (->> values
         (map (fn [{:keys [key value]}]
                (let [position (if (= index :identity) (str key) (f value))]
                  [(member index position key) key position])))
         (sort (if (= direction :desc)
                 (desc-order index)
                 (fn [a b] (Arrays/compareUnsigned ^bytes (first a) ^bytes (first b)))))
         vec)))

(defn- check-reads!
  "Pages (first, middle, last, a few more), index-rank and index-keys with an
   offset, filtered pages and joined pages against the level-0 reference."
  [ctx label]
  (let [values (vec (rmp/values ctx model))
        n (count values)
        candidates (set (map :key (take-nth 3 values)))]
    (doseq [index [:identity :score :name] direction [:asc :desc]
            :let [order (expected-order values index direction)
                  ids (mapv second order)
                  limit 25 pages (max 1 (quot (+ n (dec limit)) limit))]]
      (testing [label index direction]
        (doseq [page (distinct [1 2 (quot pages 3) (quot pages 2) (dec pages) pages])
                :when (pos? page)]
          (let [r (rmp/page ctx model {:index index :direction direction :page page :limit limit})]
            (is (= n (:total r)))
            (is (= (subvec ids (min n (* (dec page) limit)) (min n (* page limit)))
                   (mapv :key (:entries r))) [page])))
        (when (not= index :identity)
          (doseq [[_ id position] (take-nth (max 1 (quot n 23)) order)]
            (let [group (filterv #(= position (nth % 2)) order)
                  expected (count (take-while #(not= id (second %)) group))]
              (is (= {:rank expected :present? true}
                     (rmp/index-rank ctx model {:index index :equals position :direction direction} id)))
              (let [offset (quot (count group) 2)]
                (is (= (mapv second (take 10 (drop offset group)))
                       (vec (rmp/index-keys ctx model {:index index :equals position :direction direction
                                                       :offset offset :limit 10}))))))))
        (when (= index :score)
          (let [filtered (filterv candidates ids) fpages (max 1 (quot (+ (count filtered) 9) 10))]
            (doseq [page (distinct [1 (quot fpages 2) fpages]) :when (pos? page)]
              (let [r (rmp/filtered-key-page ctx model (vec candidates)
                                             {:index :score :direction direction :page page :limit 10})]
                (is (= (count filtered) (:total r)))
                (is (= (subvec filtered (min (count filtered) (* (dec page) 10)) (min (count filtered) (* page 10)))
                       (:keys r)) [:filtered page]))))
          (doseq [page [1 (max 1 (quot pages 2)) pages]]
            (let [r (rmp/joined-page ctx members-model model {:index :score :direction direction :page page :limit limit
                                                       :missing-value -1})]
              (is (= n (:total r)) [:joined page])
              (is (= (subvec ids (min n (* (dec page) limit)) (min n (* page limit))) (:keys r)) [:joined page]))))))
    n))

;; ---------------------------------------------------------------------------
;; Stored structure
;; ---------------------------------------------------------------------------

(defn- namespace-prefix [ctx] (get (core/namespace-resolve! (:cache ctx) [[tenant model 1]]) [tenant model 1]))

(defn- structure
  "{:n count :levels {index L} :rows {[index dir] {level [[member count]]}} :summaries {[index dir] #{levels}}}"
  [ctx]
  (let [b (namespace-prefix ctx)]
    (kv/transact (:cache ctx) :read
      (fn [tx]
        (let [n (or (some-> ((:get tx) (core/key-join b (byte-array [4]))) codec/decode) 0)]
          {:n n
           :levels (into {} (for [index [:identity :score :name]]
                              [index ((cv 'keyed-stored-levels) tx b d-shape index)]))
           :desc-rows (into {} (for [index [:identity :score :name]
                                     :let [p ((cv 'keyed-index-prefix) b d-shape index :asc)
                                           ;; p = b 05 ordinal dir(00) level(00); same with dir 01
                                           dp (doto (Arrays/copyOf ^bytes p (dec (alength ^bytes p)))
                                                (aset (- (alength ^bytes p) 2) (byte 1)))]]
                                 [index ((:reduce tx) {:prefix dp}
                                         (fn [n _] (inc n)) 0)]))
           :rows (into {} (for [index [:identity :score :name] direction [:asc]
                                :let [cfg ((cv 'keyed-index-config) b d-shape index direction)]]
                            [[index direction]
                             (into (sorted-map)
                                   (for [level (range 0 6)
                                         :let [rows ((:reduce tx) {:prefix (core/ranked-prefix cfg level)}
                                                     (fn [out {:keys [k v]}]
                                                       (let [m (core/ranked-unpack cfg k)]
                                                         (conj out [m (if (and (zero? level) (pos? (alength ^bytes m))) 1
                                                                          (core/ranked-decode-count v))])))
                                                     [])]
                                         :when (seq rows)]
                                     [level rows]))]))
           :summaries (into {} (for [direction [:asc]
                                     :let [cfg ((cv 'keyed-index-config) b d-shape :score direction)]]
                                 [[:score direction]
                                  (into (sorted-set)
                                        (for [level (range 16 22)
                                              :when ((:reduce tx) {:prefix (core/ranked-prefix cfg level)}
                                                     (fn [_ _] (reduced true)) false)]
                                          (- level 16)))]))})))))

(defn- check-structure!
  "Stored levels equal target(N) (or expected), only levels 0..L-1 hold rows,
   every level's counts sum to N, and upper keys follow the promotion rule."
  ([ctx label] (check-structure! ctx label nil))
  ([ctx label expected-levels]
   (let [{:keys [n levels rows summaries desc-rows]} (structure ctx)
         target (or expected-levels ((cv 'keyed-target-levels) n))]
     (testing label
       (doseq [[index c] desc-rows] (is (zero? c) [index :no-descending-rows]))
       (doseq [[index stored] levels] (is (= target stored) [index n]))
       (doseq [[[index direction] by-level] rows]
         (is (= (set (range target)) (set (keys by-level))) [index direction :levels-present])
         (doseq [[level entries] by-level]
           (is (= n (reduce + (map second entries))) [index direction level :count-sum])
           (is (zero? (alength ^bytes (ffirst entries))) [index direction level :sentinel-first])
           (when (pos? level)
             (doseq [[^bytes m _] (rest entries)]
               (is (zero? (bit-and (det-hash m) (dec (bit-shift-left 1 (* 4 level))))) [index direction level]))
             (is (= (set (map (comp vec first) (rest entries)))
                    (set (keep (fn [[^bytes m _]]
                                 (when (and (pos? (alength m))
                                            (zero? (bit-and (det-hash m) (dec (bit-shift-left 1 (* 4 level))))))
                                   (vec m)))
                               (get by-level 0))))
                 [index direction level :exactly-the-promoted-members]))))
       (doseq [[k present] summaries]
         ;; level-0 ordinals exist per member; upper sentinels keep a summary
         ;; (possibly empty) once their index has had members.
         (is (= (set (range (if (pos? n) 0 1) target)) (set present)) [k :span-summaries])))
     n)))

(defn- run-with [ctx f]
  (let [counter (atom 0)]
    (with-redefs-fn {(cv 'ranked-promotion-hash) det-promotion-hash
                     #'time/now (constantly frozen-time)
                     #'uuid/v7 #(java.util.UUID. 1152921504606875648 (swap! counter inc))}
      (fn [] (register!) (f ctx)))))

(def ^:private incremental {:read-model/batch-size 2 :read-model/deferred-build-threshold 0})
(def ^:private deferred {:read-model/batch-size 2 :read-model/deferred-build-threshold 16})

;; ---------------------------------------------------------------------------

(deftest target-levels-follow-the-member-count
  (let [t (cv 'keyed-target-levels)]
    (is (= [2 2 2 3 3 4 4 5 5 6 6 6]
           (mapv t [0 1 256 257 4096 4097 65536 65537 1048576 1048577 16777216 100000000])))))

(deftest levels-grow-and-shrink-online-with-exact-reads
  (with-cache
    (fn [base]
      (run-with (merge base incremental)
        (fn [ctx]
          (testing "growth 2 -> 3 -> 4 across batches of 100 owners"
            (doseq [[label from upto] [[:n-200 0 200] [:n-300 200 300] [:n-4000 300 4000] [:n-4200 4000 4200]]]
              (append! ctx (puts (range from upto) item))
              (rmp/progress ctx model)
              (is (= upto (check-structure! ctx label)))
              (when (#{:n-300 :n-4200} label) (check-reads! ctx label))))
          (testing "updates that move every index keep levels and reads exact"
            (append! ctx (puts (range 0 4200 3) #(assoc (item (+ % 17)) :name (format "u%07d" %))))
            (rmp/progress ctx model)
            (check-structure! ctx :moved)
            (check-reads! ctx :moved))
          (testing "deletes shrink 4 -> 3 -> 2"
            (append! ctx (puts (range 1000 4200) (constantly {:deleted true})))
            (rmp/progress ctx model)
            (is (= 1000 (check-structure! ctx :n-1000)))
            (check-reads! ctx :n-1000)
            (append! ctx (puts (range 100 1000) (constantly {:deleted true})))
            (rmp/progress ctx model)
            (is (= 100 (check-structure! ctx :n-100)))
            (check-reads! ctx :n-100))
          (testing "re-includes grow again; one batch may jump several levels"
            (append! ctx [{:inputs (mapv (fn [i] [i (item i)]) (range 100 5000))}])
            (rmp/progress ctx model)
            (is (= 5000 (check-structure! ctx :jump-2-4)))
            (check-reads! ctx :jump-2-4))
          (testing "delete everything: levels shrink to the minimum, reads empty"
            (append! ctx (puts (range 0 5000) (constantly {:deleted true})))
            (rmp/progress ctx model)
            (is (zero? (check-structure! ctx :empty)))
            (is (= [] (:entries (rmp/page ctx model {:index :score}))))))))))

(defn- model-rows [ctx]
  (let [b (namespace-prefix ctx)]
    (kv/transact (:cache ctx) :read
      #((:reduce %) {:prefix b}
        (fn [out {:keys [^bytes k v]}] (assoc out (vec (Arrays/copyOfRange k (alength b) (alength k))) (vec v)))
        (sorted-map-by (fn [a b] (Arrays/compareUnsigned (byte-array a) (byte-array b))))))))

(defn- build [opts history]
  (with-cache
    (fn [base]
      (run-with (merge base opts)
        (fn [ctx]
          (doseq [bodies history] (append! ctx bodies) (rmp/progress ctx model))
          {:rows (model-rows ctx) :structure (structure ctx) :n (check-reads! ctx :build)})))))

(deftest bulk-build-equals-incremental-structure-at-every-n
  ;; The deferred (bulk) build sizes levels from n; the incremental path grows
  ;; and shrinks online. Same final members => identical stored bytes.
  (doseq [[label history]
          {:insert-only-5000 [(puts (range 5000) item)]
           :growth-then-shrink [(concat (puts (range 5000) item)
                                        (puts (range 0 5000 2) #(assoc (item %) :score (mod % 5)))
                                        (puts (range 300 5000) (constantly {:deleted true}))
                                        (puts (range 400 700) item))]
           :below-first-threshold [(puts (range 250) item)]}]
    (testing label
      (let [reference (build incremental history)]
        (doseq [[variant opts] {:deferred deferred
                                :sorted-runs (assoc deferred :read-model/deferred-build-entries 500)}]
          (testing variant
            (let [candidate (build opts history)]
              (is (= (:n reference) (:n candidate)))
              (is (= (:levels (:structure reference)) (:levels (:structure candidate))))
              (is (= (:rows reference) (:rows candidate))
                  "Every model row (levels, towers, spans, span summaries, groups, ordinals) is identical"))))))))

(deftest reads-are-exact-beyond-16-to-the-l
  (with-cache
    (fn [base]
      (run-with (merge base incremental)
        (fn [ctx]
          (testing "levels pinned at 2 while N = 3000 (> 16^2): ~12x the expected top-level spans"
            (with-redefs-fn {(cv 'keyed-target-levels) (constantly 2)}
              (fn []
                (append! ctx (puts (range 3000) item))
                (rmp/progress ctx model)
                (is (= 3000 (check-structure! ctx :pinned-2 2)))
                (check-reads! ctx :pinned-2))))
          (testing "the next count change re-levels the stored structure to target(N)"
            (append! ctx (puts [3000] item))
            (rmp/progress ctx model)
            (is (= 3001 (check-structure! ctx :releveled)))
            (check-reads! ctx :releveled))
          (testing "over-provisioned levels (6 at N ~ 3000) read exactly and shrink"
            (with-redefs-fn {(cv 'keyed-target-levels) (constantly 6)}
              (fn []
                (append! ctx (puts [3001] item))
                (rmp/progress ctx model)
                (is (= 3002 (check-structure! ctx :pinned-6 6)))
                (check-reads! ctx :pinned-6)))
            (append! ctx (puts [3002] item))
            (rmp/progress ctx model)
            (is (= 3003 (check-structure! ctx :shrunk)))
            (check-reads! ctx :shrunk)))))))

(defn- all-bytes [ctx]
  (kv/transact (:cache ctx) :read
    #((:reduce %) {} (fn [out {:keys [k v]}] (assoc out (vec k) (vec v))) {})))

(deftest a-failed-relevel-rolls-back-the-whole-batch
  (with-cache
    (fn [base]
      (run-with (merge base incremental)
        (fn [ctx]
          (append! ctx (puts (range 250) item))
          (rmp/progress ctx model)
          (check-structure! ctx :before)
          (let [before (all-bytes ctx)
                calls (atom 0)
                build-level @(cv 'ranked-build-level!)]
            ;; One event, 20 new owners: N 250 -> 270 crosses 256 inside one batch.
            (append! ctx [{:inputs (mapv (fn [i] [i (item i)]) (range 250 270))}])
            (with-redefs-fn {(cv 'ranked-build-level!)
                             (fn [& args]
                               (when (= 3 (swap! calls inc)) (throw (ex-info "injected relevel failure" {})))
                               (apply build-level args))}
              #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected relevel failure"
                                     (rmp/progress ctx model))))
            (is (= 3 @calls) "two indexes grew before the failure")
            (is (= before (all-bytes ctx)) "Members, levels, metadata, counts and progress all rolled back")
            (is (= 270 (do (rmp/progress ctx model) (check-structure! ctx :after-retry))))
            (check-reads! ctx :after-retry)))))))

(deftest an-open-snapshot-never-sees-a-partial-relevel
  ;; LMDB readers see committed snapshots. A read transaction opened before a
  ;; growth commit keeps the old level count AND the old levels; after it, a
  ;; new snapshot sees both new. Navigation is checked inside each snapshot.
  (with-cache
    (fn [base]
      (run-with (merge base incremental)
        (fn [ctx]
          (append! ctx (puts (range 4000) item))
          (rmp/progress ctx model)
          (let [b (namespace-prefix ctx)
                navigate (fn [tx]
                           (let [cfg ((cv 'keyed-read-config) tx b d-shape ((cv 'keyed-index-config) b d-shape :name :asc) :name)
                                 members ((:reduce tx) {:prefix (core/ranked-prefix cfg 0)}
                                          (fn [out {:keys [k]}]
                                            (let [m (core/ranked-unpack cfg k)] (if (pos? (alength ^bytes m)) (conj out m) out)))
                                          [])]
                             {:levels (:levels cfg) :size (core/ranked-size tx cfg)
                              :nth-ok (every? (fn [i] (Arrays/equals ^bytes (nth members i) ^bytes (core/ranked-get-nth tx cfg i)))
                                              (range 0 (count members) 37))
                              :rank-ok (every? (fn [i] (= i (core/ranked-rank tx cfg (nth members i) true)))
                                               (range 0 (count members) 41))}))
                writer-done (promise)]
            (append! ctx (puts (range 4000 4200) item))
            (kv/transact (:cache ctx) :read
              (fn [tx]
                (let [before (navigate tx)]
                  (is (= {:levels 3 :size 4000 :nth-ok true :rank-ok true} before))
                  (future (try (rmp/progress ctx model) (deliver writer-done :ok)
                               (catch Throwable t (deliver writer-done t))))
                  (is (= :ok (deref writer-done 60000 :timeout)))
                  (is (= before (navigate tx)) "Same snapshot: old levels, old structure, still exact"))))
            (kv/transact (:cache ctx) :read
              (fn [tx] (is (= {:levels 4 :size 4200 :nth-ok true :rank-ok true} (navigate tx)))))
            (check-structure! ctx :after)))))))

(deftest cold-first-batch-fast-path-relevels-before-commit
  ;; Deferral off, one large first batch: the older cold builder appends at the
  ;; new index's starting levels, then the same transaction re-levels.
  (with-cache
    (fn [base]
      (run-with (merge base {:read-model/batch-size 1000 :read-model/deferred-build-threshold 0})
        (fn [ctx]
          (append! ctx (puts (range 5000) item))
          (rmp/progress ctx model)
          (is (= 5000 (check-structure! ctx :cold-fast-path)))
          (check-reads! ctx :cold-fast-path))))))
