(ns ai.obney.grain.read-model-processor-v2.core
  "Read-model materialization, transactional indexes and declarative queries."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.read-model-processor-v2.core.keyed :as-alias keyed]
            [ai.obney.grain.read-model-processor-v2.telemetry :as telemetry]
            [ai.obney.grain.read-model-processor-v2.core.read-query :as-alias read-query]
            [ai.obney.grain.read-model-processor-v2.core.span-membership :as-alias span-membership]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m])
  (:import [java.lang.ref WeakReference]
           [java.io EOFException]
           [java.nio ByteBuffer ByteOrder]
           [java.security MessageDigest]
           [java.util Arrays ArrayList Comparator HashMap HashSet HexFormat
            IdentityHashMap Locale PriorityQueue UUID]
           [java.util.concurrent ThreadLocalRandom]
           [java.util.concurrent.atomic AtomicLong]
           [org.roaringbitmap RoaringBitmap]))

(def ^:const default-l1-ttl-ms 0)
(def ^:const default-l1-max-entries 10000)

(def ^:private l1-cache* (atom {}))

(defn- l1-owner [{:keys [event-store cache]}]
  (bit-or (bit-shift-left (long (System/identityHashCode event-store)) 32)
          (bit-and (long (System/identityHashCode cache)) 0xffffffff)))

(defn- weak-ref [x]
  (when (some? x) (WeakReference. x)))

(defn- referent [ref]
  (when ref (.get ^WeakReference ref)))

(defn- collected? [ref]
  (and (some? ref) (nil? (.get ^WeakReference ref))))

(defn- l1-owned-by? [{[event-store-ref cache-ref] :owner} {:keys [event-store cache]}]
  (and (identical? event-store (referent event-store-ref))
       (identical? cache (referent cache-ref))))

(defn- l1-owner-collected? [{owner-refs :owner}]
  (boolean (some collected? owner-refs)))

(defn- last-access ^long [entry]
  (.get ^AtomicLong (:last-access entry)))

(defn- l1-touch! [entry]
  (.set ^AtomicLong (:last-access entry) (System/currentTimeMillis)))

(defn- l1-owned-entry [context cache-key]
  (when-let [entry (get (get @l1-cache* cache-key) (l1-owner context))]
    (when (l1-owned-by? entry context)
      (l1-touch! entry)
      entry)))

(defn l1-get-entry [cache-key]
  (let [owners (vals (get @l1-cache* cache-key))]
    (when (seq owners)
      (let [entry (apply max-key last-access owners)]
        (l1-touch! entry)
        entry))))

(defn- l1-fresh-entry [{:keys [event-store cache]} state watermark]
  (let [now (System/currentTimeMillis)]
    {:state state :watermark watermark :last-access (AtomicLong. now) :validated-at now
     :owner [(weak-ref event-store) (weak-ref cache)]}))

(defn- l1-store [entries context cache-key state watermark]
  (let [live-owners (into {} (remove (comp l1-owner-collected? val)) (get entries cache-key))]
    (assoc entries cache-key
           (assoc live-owners (l1-owner context) (l1-fresh-entry context state watermark)))))

(defn- most-recent-access [owners]
  (reduce max 0 (map last-access (vals owners))))

(defn- evict-least-recently-used! [max-entries]
  (let [key-count (count @l1-cache*)]
    (if (> key-count max-entries)
      (let [eviction-count (- key-count max-entries)
            oldest-keys (->> @l1-cache*
                             (mapv (fn [[cache-key owners]] [cache-key (most-recent-access owners)]))
                             (sort-by second)
                             (take eviction-count)
                             (map first))]
        (swap! l1-cache* #(reduce dissoc % oldest-keys))
        eviction-count)
      0)))

(defn l1-put-entry! [context cache-key state watermark max-entries]
  (swap! l1-cache* l1-store context cache-key state watermark)
  (evict-least-recently-used! (or max-entries default-l1-max-entries)))

(defn l1-touch-validated! [context cache-key]
  (let [owner (l1-owner context)
        now (System/currentTimeMillis)]
    (swap! l1-cache* (fn [entries]
                       (let [owners (get entries cache-key)]
                         (if-let [entry (get owners owner)]
                           (assoc entries cache-key (assoc owners owner (assoc entry :validated-at now)))
                           entries))))))

(defn l1-update-entry! [context cache-key state watermark]
  (swap! l1-cache* l1-store context cache-key state watermark))

(defn l1-invalidate! [cache-key]
  (swap! l1-cache* dissoc cache-key))

(defn l1-clear! []
  (reset! l1-cache* {}))

(defn l1-stats []
  {:entries (reduce + 0 (map count (vals @l1-cache*)))})

(defn- ordered-cache-values [encode key-fn values]
  (let [values (vec values)]
    (if (< (count values) 2)
      values
      (->> values
           (mapv #(vector (encode (key-fn %)) %))
           (sort (fn [[a] [b]] (Arrays/compareUnsigned ^bytes a ^bytes b)))
           (mapv second)))))

(defn- canonical-cache-value [value]
  (let [canonical-forms (IdentityHashMap.)
        encoded-forms (IdentityHashMap.)]
    (letfn [(order-key [x]
              (or (.get encoded-forms x)
                  (let [bytes (codec/encode x)]
                    (.put encoded-forms x bytes)
                    bytes)))
            (canonical-entries [m]
              (ordered-cache-values order-key first (map (fn [[k v]] [(canonical k) (canonical v)]) m)))
            (canonical [x]
              (or (.get canonical-forms x)
                  (let [form (cond
                               (record? x) [:record (.getName (class x)) (canonical-entries x)]
                               (instance? java.util.Map x) [:map (canonical-entries x)]
                               (instance? java.util.Set x) [:set (ordered-cache-values order-key identity (map canonical x))]
                               (or (sequential? x) (instance? java.util.List x)) [:sequence (mapv canonical x)]
                               (integer? x) (if (<= Long/MIN_VALUE x Long/MAX_VALUE) (long x) (bigint x))
                               (instance? java.math.BigDecimal x) (.stripTrailingZeros ^java.math.BigDecimal x)
                               (instance? Float x) (double x)
                               :else x)]
                    (.put canonical-forms x form)
                    form)))]
      (canonical value))))

(defn- cache-fingerprint [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (codec/encode (canonical-cache-value value)))))

(defn format-key [n v]
  (.getBytes (format "%s-%s" n v)))

(defn format-scoped-key [n v scope]
  (if scope
    (.getBytes (format "%s-%s-s1-%s" n v (cache-fingerprint scope)))
    (format-key n v)))

(defn suffix-key
  ^bytes [^bytes base-key ^String suffix]
  (let [suffix-bytes (.getBytes suffix)
        result (byte-array (+ (alength base-key) (alength suffix-bytes)))]
    (System/arraycopy base-key 0 result 0 (alength base-key))
    (System/arraycopy suffix-bytes 0 result (alength base-key) (alength suffix-bytes))
    result))

(defn- segment-key
  ^bytes [^bytes base-key idx]
  (suffix-key base-key (format ":s%d" idx)))

(defn partition-cache-key
  ^bytes [^bytes base-key partition-key]
  (suffix-key base-key (str ":p1-" (cache-fingerprint partition-key))))

(defn- entity-index-cache-key
  ^bytes [^bytes base-key]
  (suffix-key base-key ":eidx"))

(defn- latest-id
  "The latest of the given event ids, ignoring nils; nil when none."
  [& ids]
  (last (sort (remove nil? ids))))

(defn- storage-version
  "The version slot of a model's storage identity: :start-after is folded in."
  [d]
  (if-let [sa (:start-after d)] (str (:version d 1) "-after-" sa) (:version d 1)))

(defn- add-watermark [query watermark]
  (letfn [(after [q] (if-let [a (latest-id (:after q) watermark)] (assoc q :after a) q))]
    (cond
      (nil? watermark) query
      (vector? query) (mapv after query)
      :else (after query))))

(defn- inject-tenant-id [query tenant-id]
  (if (vector? query)
    (mapv #(assoc % :tenant-id tenant-id) query)
    (assoc query :tenant-id tenant-id)))

(defn process-events [state events f]
  (reduce
   (fn [acc event]
     (-> acc
         (update :state f event)
         (update :event-count inc)
         (assoc :new-watermark (:event/id event))))
   {:state state
    :event-count 0}
   events))

(def ^:const segment-count 64)
(def ^:const segment-threshold 10000)
(def ^:private ^:const l2-write-back-min-events 10)

(defn- should-segment? [state]
  (> (count state) segment-threshold))

(defn- split-into-segments [state-map n]
  (persistent!
   (reduce-kv
    (fn [segments k v]
      (let [idx (mod (hash k) n)]
        (assoc! segments idx (assoc (get segments idx {}) k v))))
    (transient {})
    state-map)))

(defn- changed-segments [old-state new-state n]
  (into #{}
        (comp (remove (fn [k] (identical? (get old-state k) (get new-state k))))
              (map (fn [k] (mod (hash k) n))))
        (into (set (keys old-state)) (keys new-state))))

(defn- read-cache-snapshot [get-value base-key]
  (when-let [raw (get-value {:k base-key})]
    (let [decoded (codec/decode raw)]
      (if-not (:segmented decoded)
        decoded
        (let [{:keys [segment-count watermark checksums]} decoded]
          {:data (reduce (fn [state idx]
                           (if-let [segment (get-value {:k (segment-key base-key idx)})]
                             (merge state (codec/decode segment))
                             state))
                         {}
                         (range segment-count))
           :watermark watermark
           :segmented true
           :segment-count segment-count
           :checksums checksums})))))

(defn- read-cache [cache base-key]
  (kv/read-snapshot cache #(read-cache-snapshot % base-key)))

(defn- write-monolithic! [cache base-key state watermark]
  (kv/put! cache {:k base-key
                  :v (codec/encode {:data state :watermark watermark})}))

(defn- write-segments! [cache base-key segments idxs checksums segment-count watermark]
  (let [entries (mapv (fn [idx] {:k (segment-key base-key idx)
                                 :v (codec/encode (get segments idx {}))})
                      idxs)
        checksums (reduce (fn [checksums [idx {:keys [v]}]] (assoc checksums idx (hash (vec v))))
                          checksums
                          (map vector idxs entries))
        manifest {:segmented true
                  :segment-count segment-count
                  :watermark watermark
                  :checksums checksums}]
    (kv/put-batch! cache {:entries (conj entries {:k base-key :v (codec/encode manifest)})})))

(defn- write-segmented! [cache base-key state watermark]
  (let [segments (split-into-segments state segment-count)]
    (write-segments! cache base-key segments (keys segments) {} segment-count watermark)))

(defn- write-changed-segments! [cache base-key old-checksums state watermark n changed-idxs]
  (write-segments! cache base-key (split-into-segments state n) changed-idxs old-checksums n watermark))

(defn- write-cache! [cache base-key state watermark]
  (if (should-segment? state)
    (write-segmented! cache base-key state watermark)
    (write-monolithic! cache base-key state watermark)))

(defn- count-folded-event [acc event]
  (-> acc
      (update :event-count inc)
      (assoc :new-watermark (:event/id event))))

(defn- entity-partition-lookup [partitions]
  (persistent!
   (reduce-kv (fn [lookup pk state] (reduce-kv (fn [lookup eid _] (assoc! lookup eid pk)) lookup state))
              (transient {})
              partitions)))

(defn- apply-to-known-entity [acc f partition-fn event eid current-pk]
  (let [new-state (f (get-in acc [:partitions current-pk]) event)
        new-entity (get new-state eid)
        new-pk (when new-entity (partition-fn new-entity))]
    (cond
      (not new-entity)
      (-> acc
          (assoc-in [:partitions current-pk] new-state)
          (update :changed-pks conj current-pk)
          (update :lookup dissoc eid))

      (= new-pk current-pk)
      (-> acc
          (assoc-in [:partitions current-pk] new-state)
          (update :changed-pks conj current-pk))

      :else
      (-> acc
          (assoc-in [:partitions current-pk] (dissoc new-state eid))
          (update-in [:partitions new-pk] assoc eid new-entity)
          (update :changed-pks conj current-pk)
          (update :changed-pks conj new-pk)
          (assoc-in [:lookup eid] new-pk)))))

(defn- apply-to-new-entity [acc f partition-fn event eid]
  (if-let [new-entity (get (f {} event) eid)]
    (let [pk (partition-fn new-entity)]
      (-> acc
          (update-in [:partitions pk] assoc eid new-entity)
          (update :changed-pks conj pk)
          (assoc-in [:lookup eid] pk)))
    acc))

(defn- process-events-partitioned [partitions events f partition-fn entity-id-fn]
  (reduce
   (fn [acc event]
     (let [eid (entity-id-fn event)
           current-pk (get (:lookup acc) eid)]
       (count-folded-event
        (if current-pk
          (apply-to-known-entity acc f partition-fn event eid current-pk)
          (apply-to-new-entity acc f partition-fn event eid))
        event)))
   {:partitions partitions
    :lookup (entity-partition-lookup partitions)
    :changed-pks #{}
    :event-count 0}
   events))

(defn- apply-to-resident-entity [acc f partition-fn partition-key event eid]
  (let [new-state (f (:state acc) event)
        new-entity (get new-state eid)
        new-pk (when new-entity (partition-fn new-entity))]
    (if (and new-entity (not= partition-key new-pk))
      (-> acc
          (assoc :state (dissoc new-state eid))
          (assoc-in [:evictions new-pk eid] new-entity))
      (assoc acc :state new-state))))

(defn- adopt-arriving-entity [acc f partition-fn partition-key find-entity event eid]
  (let [new-entity (get (f {} event) eid)]
    (if (and new-entity (= partition-key (partition-fn new-entity)))
      (update acc :state assoc eid new-entity)
      (if-let [[source-pk source-entity] (when find-entity (find-entity eid))]
        (let [moved-entity (get (f {eid source-entity} event) eid)]
          (if (and moved-entity (= partition-key (partition-fn moved-entity)))
            (cond-> (update acc :state assoc eid moved-entity)
              (not= source-pk partition-key)
              (update-in [:source-removals source-pk] (fnil conj #{}) eid))
            acc))
        acc))))

(defn- process-events-single-partition
  [partition-key partition-state events f partition-fn entity-id-fn find-entity]
  (reduce
   (fn [acc event]
     (let [eid (entity-id-fn event)]
       (count-folded-event
        (if (contains? (:state acc) eid)
          (apply-to-resident-entity acc f partition-fn partition-key event eid)
          (adopt-arriving-entity acc f partition-fn partition-key find-entity event eid))
        event)))
   {:state partition-state
    :evictions {}
    :source-removals {}
    :event-count 0}
   events))

(defn- read-partition-manifest [cache base-key]
  (when-let [raw (kv/get! cache {:k base-key})]
    (let [decoded (codec/decode raw)]
      (when (:partitioned decoded)
        decoded))))

(defn- read-single-partition [cache base-key partition-key]
  (when-let [raw (kv/get! cache {:k (partition-cache-key base-key partition-key)})]
    (codec/decode raw)))

(defn- read-all-partitions [cache base-key manifest]
  (reduce (fn [partitions pk]
            (if-let [partition (read-single-partition cache base-key pk)]
              (assoc partitions pk (:data partition))
              partitions))
          {}
          (:partition-keys manifest)))

(defn- read-entity-index [cache base-key]
  (when-let [raw (kv/get! cache {:k (entity-index-cache-key base-key)})]
    (codec/decode raw)))

(defn- write-entity-index! [cache base-key index]
  (kv/put! cache {:k (entity-index-cache-key base-key)
                  :v (codec/encode index)}))

(defn- build-entity-index [partitions]
  (reduce-kv (fn [index pk state] (reduce-kv (fn [index eid _] (assoc index eid pk)) index state))
             {}
             partitions))

(defn- find-entity-via-index [cache base-key index eid]
  (when-let [source-pk (get index eid)]
    (when-let [partition-data (:data (read-single-partition cache base-key source-pk))]
      (when-let [entity (get partition-data eid)]
        [source-pk entity]))))

(defn- entity-finder [cache base-key]
  (let [entity-index (delay (read-entity-index cache base-key))]
    (fn [eid]
      (when-let [index @entity-index]
        (find-entity-via-index cache base-key index eid)))))

(defn- write-partitions! [cache base-key partitions changed-pks global-watermark all-partition-keys]
  (let [partition-entries (mapv (fn [pk]
                                  {:k (partition-cache-key base-key pk)
                                   :v (codec/encode {:data (get partitions pk {})
                                                     :watermark global-watermark})})
                                changed-pks)
        manifest {:partitioned true
                  :global-watermark global-watermark
                  :partition-keys (set all-partition-keys)}]
    (kv/put-batch! cache {:entries (conj partition-entries {:k base-key :v (codec/encode manifest)})})))

(defn- revise-cached-partitions! [cache base-key changes global-watermark revise]
  (when (seq changes)
    (kv/put-batch! cache
                   {:entries (mapv (fn [[pk change]]
                                     (let [existing (or (:data (read-single-partition cache base-key pk)) {})]
                                       {:k (partition-cache-key base-key pk)
                                        :v (codec/encode {:data (revise existing change)
                                                          :watermark global-watermark})}))
                                   changes)})))

(defn- write-evictions! [cache base-key evictions global-watermark]
  (revise-cached-partitions! cache base-key evictions global-watermark merge))

(defn- write-source-removals! [cache base-key source-removals global-watermark]
  (revise-cached-partitions! cache base-key source-removals global-watermark #(apply dissoc %1 %2)))

(defn- update-entity-index! [cache base-key partition-key state evictions source-removals]
  (let [index (or (read-entity-index cache base-key) {})
        index (reduce-kv (fn [index eid _] (assoc index eid partition-key)) index state)
        index (reduce-kv (fn [index pk entities]
                           (reduce-kv (fn [index eid _] (assoc index eid pk)) index entities))
                         index
                         evictions)
        index (reduce-kv (fn [index _ eids]
                           (reduce (fn [index eid] (assoc index eid partition-key)) index eids))
                         index
                         source-removals)]
    (write-entity-index! cache base-key index)))

(defn- write-partition-update!
  [cache base-key manifest partition-key {:keys [state evictions source-removals event-count]} watermark]
  (when (or (seq evictions) (seq source-removals))
    (write-evictions! cache base-key evictions watermark)
    (write-source-removals! cache base-key source-removals watermark))
  (when (pos? event-count)
    (write-partitions! cache base-key {partition-key state} #{partition-key} watermark
                       (into (:partition-keys manifest) (cons partition-key (keys evictions))))
    (update-entity-index! cache base-key partition-key state evictions source-removals)))

(defn- relevant-to-partition? [f partition-fn partition-key find-entity eid event]
  (try
    (let [new-entity (get (f {} event) eid)]
      (if (and new-entity (= partition-key (partition-fn new-entity)))
        true
        (when-let [[_ source-entity] (when find-entity (find-entity eid))]
          (let [moved-entity (get (f {eid source-entity} event) eid)]
            (and moved-entity (= partition-key (partition-fn moved-entity)))))))
    (catch Exception _ false)))

(defn- partition-relevant-events [partition-state events entity-id-fn relevant?]
  (:relevant
   (reduce (fn [{:keys [known-eids] :as acc} event]
             (let [eid (entity-id-fn event)]
               (if (or (contains? partition-state eid)
                       (contains? known-eids eid)
                       (relevant? eid event))
                 (-> acc
                     (update :relevant conj event)
                     (update :known-eids conj eid))
                 acc)))
           {:relevant [] :known-eids #{}}
           events)))

(declare p-partitioned)

(defn- p-partitioned-single
  [{:keys [event-store cache] :as context}
   {:keys [f query partition-fn entity-id-fn partition-key name l1-ttl-ms l1-max-entries] :as args}
   cache-key manifest]
  (let [l1-key (String. ^bytes (partition-cache-key cache-key partition-key))
        ttl-ms (or l1-ttl-ms default-l1-ttl-ms)
        max-entries (or l1-max-entries default-l1-max-entries)
        fold (fn [state events find-entity]
               (process-events-single-partition partition-key state events f partition-fn entity-id-fn find-entity))]
    (if-let [l1 (l1-owned-entry context l1-key)]
      (if (< (- (System/currentTimeMillis) (:validated-at l1)) ttl-ms)
        (u/trace ::l1-hit
          [:metric/name "ReadModelL1Hit" :metric/resolution :high
           :read-model/cache-tier "l1-hit" :read-model/name name]
          (:state l1))
        (let [started (System/nanoTime)
              events (into [] (es/read event-store (add-watermark query (:watermark l1))))
              find-entity (when manifest (entity-finder cache cache-key))
              relevant (partition-relevant-events
                        (:state l1) events entity-id-fn
                        (fn [eid event] (relevant-to-partition? f partition-fn partition-key find-entity eid event)))]
          (if (empty? relevant)
            (try (do (l1-touch-validated! context l1-key)
                  (:state l1))
              (finally (u/log ::l1-revalidated :metric/name "ReadModelL1Revalidated" :metric/resolution :high
               :read-model/cache-tier "l1-revalidated" :read-model/name name
                 :mulog/duration (- (System/nanoTime) started))))
            (let [{:keys [state event-count new-watermark] :as folded} (fold (:state l1) relevant find-entity)
                  watermark (or new-watermark (:watermark l1))]
              (try (do (l1-update-entry! context l1-key state watermark)
                    (when manifest
                      (write-partition-update! cache cache-key manifest partition-key folded watermark))
                    state)
              (finally (u/log ::l1-stale :metric/name "ReadModelL1Stale" :metric/resolution :high
                 :read-model/cache-tier "l1-stale" :read-model/name name
                 :read-model/events-processed event-count
                 :mulog/duration (- (System/nanoTime) started))))))))
      (u/trace ::l1-miss
        [:metric/name "ReadModelL1Miss" :metric/resolution :high
         :read-model/cache-tier "l1-miss" :read-model/name name]
        (if (nil? manifest)
          (do (p-partitioned context (dissoc args :partition-key))
              (let [cached (read-single-partition cache cache-key partition-key)
                    state (or (:data cached) {})]
                (l1-put-entry! context l1-key state (:watermark cached) max-entries)
                state))
          (let [cached (read-single-partition cache cache-key partition-key)
                cached-watermark (or (:watermark cached) (:global-watermark manifest))
                cached-state (or (:data cached) {})
                events (into [] (es/read event-store (add-watermark query cached-watermark)))]
            (if (empty? events)
              (do (l1-put-entry! context l1-key cached-state cached-watermark max-entries)
                  cached-state)
              (let [{:keys [state new-watermark] :as folded}
                    (fold cached-state events (entity-finder cache cache-key))
                    watermark (or new-watermark cached-watermark)]
                (write-partition-update! cache cache-key manifest partition-key folded watermark)
                (l1-put-entry! context l1-key state watermark max-entries)
                state))))))))

(defn- p-partitioned-full
  [{:keys [event-store cache]} {:keys [f query partition-fn entity-id-fn]} cache-key manifest]
  (u/trace ::partitioned-full
    [:metric/name (if manifest "ReadModelL2Hit" "ReadModelL2Miss")
     :metric/resolution :high
     :read-model/cache-tier (if manifest "l2-hit" "l2-miss")]
      (let [partitions (if manifest (read-all-partitions cache cache-key manifest) {})
        watermark (:global-watermark manifest)
        events (if watermark
                 (es/read event-store (add-watermark query watermark))
                 (es/read event-store query))
        {:keys [partitions changed-pks event-count new-watermark]}
        (process-events-partitioned partitions events f partition-fn entity-id-fn)]
    (when (or (nil? manifest) (pos? event-count))
      (let [entity-index (build-entity-index partitions)]
        (write-partitions! cache cache-key partitions
                           (if (nil? manifest) (set (keys partitions)) changed-pks)
                           (or new-watermark watermark)
                           (keys partitions))
        (write-entity-index! cache cache-key entity-index)))
    (reduce merge {} (vals partitions)))))

(defn- p-partitioned
  [{:keys [cache tenant-id] :as context}
   {:keys [query name version scope partition-key] :as args}]
  (let [cache-scope (not-empty (dissoc scope :partition-key))
        cache-key (format-scoped-key name (storage-version args) (if cache-scope [tenant-id cache-scope] tenant-id))
        args (assoc args :query (inject-tenant-id query tenant-id))
        manifest (read-partition-manifest cache cache-key)]
    (if partition-key
      (p-partitioned-single context args cache-key manifest)
      (p-partitioned-full context args cache-key manifest))))

(defn- write-back-l2! [cache cache-key cached state watermark]
  (let [{:keys [data segmented segment-count checksums]} cached]
    (if segmented
      (let [changed (changed-segments data state segment-count)]
        (when (seq changed)
          (write-changed-segments! cache cache-key checksums state watermark segment-count changed)))
      (write-cache! cache cache-key state watermark))))

(defn- p-unpartitioned
  [{:keys [event-store cache tenant-id] :as context}
   {:keys [f query name version scope l1-ttl-ms l1-max-entries] :as args}]
  (u/with-context {:read-model/name name
                   :read-model/version version}
  (let [cache-key (format-scoped-key name (storage-version args) (if scope [tenant-id scope] tenant-id))
        l1-key (String. ^bytes cache-key)
        query (inject-tenant-id query tenant-id)
        ttl-ms (or l1-ttl-ms default-l1-ttl-ms)
        max-entries (or l1-max-entries default-l1-max-entries)]
    (if-let [l1 (l1-owned-entry context l1-key)]
      (if (< (- (System/currentTimeMillis) (:validated-at l1)) ttl-ms)
        (u/trace ::l1-hit
          [:metric/name "ReadModelL1Hit" :metric/resolution :high
           :read-model/cache-tier "l1-hit"]
          (:state l1))
        (let [started (System/nanoTime)
              events (es/read event-store (add-watermark query (:watermark l1)))
              {:keys [state event-count new-watermark]} (process-events (:state l1) events f)
              watermark (or new-watermark (:watermark l1))]
          (if (zero? event-count)
            (try (do (l1-touch-validated! context l1-key)
                  (:state l1))
              (finally (u/log ::l1-revalidated :metric/name "ReadModelL1Revalidated" :metric/resolution :high
               :read-model/cache-tier "l1-revalidated"
                 :mulog/duration (- (System/nanoTime) started))))
            (try (do (l1-update-entry! context l1-key state watermark)
                  (when (>= event-count l2-write-back-min-events)
                    (write-cache! cache cache-key state watermark))
                  state)
              (finally (u/log ::l1-stale :metric/name "ReadModelL1Stale" :metric/resolution :high
               :read-model/cache-tier "l1-stale"
               :read-model/events-processed event-count
                 :mulog/duration (- (System/nanoTime) started)))))))
      (u/trace ::l1-miss
        [:metric/name "ReadModelL1Miss" :metric/resolution :high
         :read-model/cache-tier "l1-miss"]
        (if-let [cached (read-cache cache cache-key)]
          (u/trace ::l2-hit
            [:metric/name "ReadModelL2Hit" :metric/resolution :high
             :read-model/cache-tier "l2-hit"]
            (let [events (es/read event-store (add-watermark query (:watermark cached)))
                  {:keys [state event-count new-watermark]} (process-events (:data cached) events f)
                  watermark (or new-watermark (:watermark cached))]
              (when (>= event-count l2-write-back-min-events)
                (write-back-l2! cache cache-key cached state watermark))
              (l1-put-entry! context l1-key state watermark max-entries)
              state))
          (u/trace ::l2-miss
            [:metric/name "ReadModelL2Miss" :metric/resolution :high
             :read-model/cache-tier "l2-miss"]
            (let [events (es/read event-store query)
                  {:keys [state new-watermark]} (process-events {} events f)]
              (write-cache! cache cache-key state new-watermark)
              (l1-put-entry! context l1-key state new-watermark max-entries)
              state))))))))

(defn p [context {:keys [partition-fn entity-id-fn start-after] :as args}]
  (u/with-context {:read-model/name (:name args) :read-model/version (:version args)}
    (let [args (cond-> args start-after (update :query add-watermark start-after))]
    (if (and partition-fn entity-id-fn)
    (p-partitioned context args)
    (p-unpartitioned context args)))))

(defn key-join ^bytes [& arrays]
  (let [out (byte-array (reduce (fn [size ^bytes a] (+ size (alength a))) 0 arrays))]
    (reduce (fn [offset ^bytes a]
              (System/arraycopy a 0 out offset (alength a))
              (+ offset (alength a)))
            0
            arrays)
    out))

(definterface KeyOut
  (^void writeByte [^long v])
  (^void writeShort [^long v])
  (^void writeLong [^long v]))

(def ^:private ^:const key-buffer-initial-bytes 128)
(def ^:private ^:const key-buffer-retained-max-bytes 1024)

(deftype KeyBuffer [^:unsynchronized-mutable ^bytes buf ^:unsynchronized-mutable ^long n]
  KeyOut
  (writeByte [_ v]
    (when (= n (alength buf))
      (set! buf (Arrays/copyOf buf (* 2 (alength buf)))))
    (aset buf n (unchecked-byte v))
    (set! n (inc n)))
  (writeShort [this v]
    (.writeByte this (bit-shift-right v 8))
    (.writeByte this v))
  (writeLong [this v]
    (.writeByte this (unsigned-bit-shift-right v 56))
    (.writeByte this (unsigned-bit-shift-right v 48))
    (.writeByte this (unsigned-bit-shift-right v 40))
    (.writeByte this (unsigned-bit-shift-right v 32))
    (.writeByte this (unsigned-bit-shift-right v 24))
    (.writeByte this (unsigned-bit-shift-right v 16))
    (.writeByte this (unsigned-bit-shift-right v 8))
    (.writeByte this v))
  clojure.lang.IDeref
  (deref [_] (Arrays/copyOf buf n))
  java.io.Closeable
  (close [_]
    (set! n 0)
    (when (> (alength buf) key-buffer-retained-max-bytes)
      (set! buf (byte-array key-buffer-initial-bytes)))))

(def ^:private ^ThreadLocal per-thread-key-buffers
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] (KeyBuffer. (byte-array key-buffer-initial-bytes) 0)))))

(def ^:private ^:const tag-end 0x00)
(def ^:private ^:const tag-nil 0x01)
(def ^:private ^:const tag-false 0x02)
(def ^:private ^:const tag-true 0x03)
(def ^:private ^:const tag-string 0x10)
(def ^:private ^:const tag-string-last 0x12)
(def ^:private ^:const tag-integer-min 0x18)
(def ^:private ^:const tag-integer 0x20)
(def ^:private ^:const tag-integer-max 0x28)
(def ^:private ^:const tag-uuid 0x30)
(def ^:private ^:const tag-keyword 0x38)
(def ^:private ^:const tag-keyword-last 0x3A)
(def ^:private ^:const tag-vector 0x40)

(defn- key-write-string!
  [^KeyOut out ^String s ^long start ^long tag]
  (let [n (.length s)]
    (if (= start n)
      (do (.writeByte out tag) (.writeByte out 0) (.writeByte out 0))
      (let [first-unit (long (int (.charAt s start)))
            narrow? (cond
                      (< first-unit 2)
                      (do (.writeByte out tag) (.writeByte out 0) (.writeByte out 1) (.writeByte out first-unit)
                          false)

                      (< first-unit 256)
                      (do (.writeByte out (inc tag)) (.writeByte out first-unit)
                          true)

                      :else
                      (do (.writeByte out (+ tag 2)) (.writeShort out first-unit)
                          false))]
        (loop [i (inc start) narrow? narrow?]
          (if (< i n)
            (let [unit (long (int (.charAt s i)))]
              (if narrow?
                (cond
                  (< unit 3) (do (.writeByte out 1) (.writeByte out unit) (recur (inc i) true))
                  (< unit 256) (do (.writeByte out (dec unit)) (recur (inc i) true))
                  :else (do (.writeByte out 255) (.writeShort out unit) (recur (inc i) false)))
                (cond
                  (< unit 2) (do (.writeByte out 0) (.writeByte out 1) (.writeByte out unit) (recur (inc i) false))
                  (< unit 256) (do (.writeByte out 0) (.writeByte out unit) (recur (inc i) true))
                  :else (do (.writeShort out unit) (recur (inc i) false)))))
            (if narrow?
              (.writeByte out 0)
              (do (.writeByte out 0) (.writeByte out 0)))))))))

(defn- key-write-integer! [^KeyOut out ^long v]
  (if (zero? v)
    (.writeByte out tag-integer)
    (let [negative? (neg? v)
          magnitude (if negative? (unchecked-negate v) v)
          byte-count (quot (+ (- 64 (Long/numberOfLeadingZeros magnitude)) 7) 8)
          stored (if negative? (bit-not magnitude) magnitude)]
      (.writeByte out (if negative? (- tag-integer byte-count) (+ tag-integer byte-count)))
      (loop [k (dec byte-count)]
        (when (>= k 0)
          (.writeByte out (unsigned-bit-shift-right stored (* 8 k)))
          (recur (dec k)))))))

(definline ^:private long-range-integer? [value]
  `(let [value# ~value]
     (and (integer? value#) (<= Long/MIN_VALUE value# Long/MAX_VALUE))))

(defn- key-write-key! [^KeyOut out value]
  (cond
    (nil? value) (.writeByte out tag-nil)
    (boolean? value) (.writeByte out (if value tag-true tag-false))
    (string? value) (key-write-string! out value 0 tag-string)
    (long-range-integer? value) (key-write-integer! out (long value))
    (uuid? value) (do (.writeByte out tag-uuid)
                      (.writeLong out (.getMostSignificantBits ^UUID value))
                      (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value) (key-write-string! out (str value) 1 tag-keyword)
    (vector? value) (do (.writeByte out tag-vector)
                        (reduce (fn [_ v] (key-write-key! out v)) nil value)
                        (.writeByte out tag-end))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn key-encode ^bytes [value]
  (with-open [^KeyBuffer out (.get per-thread-key-buffers)]
    (key-write-key! out value)
    @out))

(defn key-encode-compact ^bytes [value]
  (key-encode value))

(defn- key-write-legacy! [^KeyOut out value]
  (cond
    (nil? value) (.writeByte out tag-nil)
    (boolean? value) (.writeByte out (if value tag-true tag-false))
    (string? value) (let [^String value value]
                      (.writeByte out tag-string)
                      (dotimes [i (.length value)]
                        (let [unit (int (.charAt value i))
                              hi (bit-shift-right unit 8)
                              lo (bit-and 255 unit)]
                          (.writeByte out hi)
                          (when (zero? hi) (.writeByte out 255))
                          (.writeByte out lo)
                          (when (zero? lo) (.writeByte out 255))))
                      (.writeShort out 0))
    (long-range-integer? value) (do (.writeByte out tag-integer)
                                    (.writeLong out (bit-xor (long value) Long/MIN_VALUE)))
    (uuid? value) (do (.writeByte out tag-uuid)
                      (.writeLong out (.getMostSignificantBits ^UUID value))
                      (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value) (do (.writeByte out tag-keyword)
                         (key-write-legacy! out (str value)))
    (vector? value) (do (.writeByte out tag-vector)
                        (reduce (fn [_ v] (key-write-legacy! out v)) nil value)
                        (.writeByte out tag-end))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn- key-encode-legacy ^bytes [value]
  (with-open [^KeyBuffer out (.get per-thread-key-buffers)]
    (key-write-legacy! out value)
    @out))

(defn key-tuple-prefix ^bytes [components]
  (when-not (vector? components)
    (throw (ex-info "Tuple prefix must be a vector" {:prefix components})))
  (let [encoded (key-encode components)
        without-vector-end (dec (alength encoded))]
    (Arrays/copyOf encoded without-vector-end)))

(defn- member-byte ^long [^bytes a ^longs pos ^long mask]
  (let [i (aget pos 0)]
    (when (>= i (aget pos 1)) (throw (EOFException.)))
    (aset pos 0 (inc i))
    (bit-xor mask (bit-and 255 (aget a i)))))

(defn- invalid-member! []
  (throw (ex-info "Invalid ordered key encoding" {:type :grain.read-model/invalid-cache})))

(defn- member-string!
  [^bytes a ^longs pos mask tag-offset ^StringBuilder out]
  (let [mask (long mask)
        tag-offset (long tag-offset)]
    (loop [narrow? (case tag-offset
                     0 false
                     1 (let [unit (member-byte a pos mask)]
                         (when (< unit 2) (invalid-member!))
                         (when out (.append out (char unit)))
                         true)
                     2 (let [hi (member-byte a pos mask)
                             lo (member-byte a pos mask)]
                         (when (zero? hi) (invalid-member!))
                         (when out (.append out (char (bit-or (bit-shift-left hi 8) lo))))
                         false)
                     (invalid-member!))]
      (let [b (member-byte a pos mask)]
        (if narrow?
          (cond
            (zero? b) nil
            (= b 1) (let [unit (member-byte a pos mask)]
                      (when (> unit 2) (invalid-member!))
                      (when out (.append out (char unit)))
                      (recur true))
            (= b 255) (let [hi (member-byte a pos mask)
                            lo (member-byte a pos mask)]
                        (when (zero? hi) (invalid-member!))
                        (when out (.append out (char (bit-or (bit-shift-left hi 8) lo))))
                        (recur false))
            :else (do (when out (.append out (char (inc b))))
                      (recur true)))
          (if (zero? b)
            (let [escaped (member-byte a pos mask)]
              (cond
                (zero? escaped) nil
                (= escaped 1) (let [unit (member-byte a pos mask)]
                                (when (> unit 1) (invalid-member!))
                                (when out (.append out (char unit)))
                                (recur false))
                :else (do (when out (.append out (char escaped)))
                          (recur true))))
            (let [lo (member-byte a pos mask)]
              (when out (.append out (char (bit-or (bit-shift-left b 8) lo))))
              (recur false))))))))

(defn- member-read!
  [^bytes a ^longs pos mask build?]
  (let [mask (long mask)
        tag (member-byte a pos mask)]
    (cond
      (= tag tag-nil) nil
      (= tag tag-false) false
      (= tag tag-true) true

      (and (>= tag tag-string) (<= tag tag-string-last))
      (let [out (when build? (StringBuilder.))]
        (member-string! a pos mask (- tag tag-string) out)
        (when out (.toString out)))

      (and (>= tag tag-integer-min) (<= tag tag-integer-max))
      (let [byte-count (Math/abs (- tag tag-integer))
            stored (loop [i 0 x 0]
                     (if (= i byte-count)
                       x
                       (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))]
        (when build?
          (cond
            (= tag tag-integer) 0
            (> tag tag-integer) (if (neg? stored) (invalid-member!) stored)
            :else (unchecked-negate (if (= byte-count 8)
                                      (bit-not stored)
                                      (bit-and (bit-not stored) (dec (bit-shift-left 1 (* 8 byte-count)))))))))

      (= tag tag-uuid)
      (let [hi (loop [i 0 x 0] (if (= i 8) x (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))
            lo (loop [i 0 x 0] (if (= i 8) x (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))]
        (when build? (UUID. hi lo)))

      (and (>= tag tag-keyword) (<= tag tag-keyword-last))
      (let [out (when build? (StringBuilder.))]
        (member-string! a pos mask (- tag tag-keyword) out)
        (when out (keyword (.toString out))))

      (= tag tag-vector)
      (loop [out (when build? (transient []))]
        (let [i (aget pos 0)]
          (when (>= i (aget pos 1)) (throw (EOFException.)))
          (if (zero? (bit-xor mask (bit-and 255 (aget a i))))
            (do (aset pos 0 (inc i))
                (some-> out persistent!))
            (let [v (member-read! a pos mask build?)]
              (recur (when out (conj! out v)))))))

      :else (invalid-member!))))

(defn key-decode-compact
  ([^bytes value] (key-decode-compact value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (let [end (+ offset length)
         pos (doto (long-array 2) (aset 0 offset) (aset 1 end))
         decoded (member-read! value pos 0 true)]
     (when-not (= (aget pos 0) end) (invalid-member!))
     decoded)))

(defn- member-at ^long [^bytes a ^long i ^long mask]
  (bit-xor mask (bit-and 255 (aget a i))))

(defn- skip-member-string ^long [^bytes a ^long i ^long mask ^long tag-offset]
  (loop [i (long (case tag-offset 0 i 1 (inc i) 2 (+ i 2) (invalid-member!)))
         narrow (if (= tag-offset 1) 1 0)]
    (let [b (member-at a i mask)]
      (if (= narrow 1)
        (cond
          (zero? b) (inc i)
          (= b 1) (recur (+ i 2) 1)
          (= b 255) (recur (+ i 3) 0)
          :else (recur (inc i) 1))
        (if (zero? b)
          (let [escaped (member-at a (inc i) mask)]
            (cond
              (zero? escaped) (+ i 2)
              (= escaped 1) (recur (+ i 3) 0)
              :else (recur (+ i 2) 1)))
          (recur (+ i 2) 0))))))

(defn- skip-member-key ^long [^bytes a ^long i ^long mask]
  (let [tag (member-at a i mask)]
    (cond
      (and (>= tag tag-nil) (<= tag tag-true)) (inc i)
      (and (>= tag tag-string) (<= tag tag-string-last)) (skip-member-string a (inc i) mask (- tag tag-string))
      (and (>= tag tag-integer-min) (<= tag tag-integer-max)) (+ i 1 (Math/abs (- tag tag-integer)))
      (= tag tag-uuid) (+ i 17)
      (and (>= tag tag-keyword) (<= tag tag-keyword-last)) (skip-member-string a (inc i) mask (- tag tag-keyword))
      (= tag tag-vector) (loop [i (inc i)]
                           (if (zero? (member-at a i mask))
                             (inc i)
                             (recur (skip-member-key a i mask))))
      :else (long (invalid-member!)))))

(defn key-skip
  (^long [^bytes a ^long i] (skip-member-key a i 0))
  (^long [^bytes a ^long i _compact?] (skip-member-key a i 0)))

(def ^:private id-tail-string (byte-array [tag-string]))
(def ^:private id-tail-integer (byte-array [tag-integer]))
(def ^:private id-tail-uuid (byte-array [tag-uuid]))
(def ^:private id-tail-keyword (byte-array [tag-keyword]))
(def ^:private id-tail-nil (byte-array [tag-nil]))
(def ^:private id-tail-false (byte-array [tag-false]))
(def ^:private id-tail-true (byte-array [tag-true]))

(defn key-id-tail ^bytes [id]
  (cond
    (string? id) id-tail-string
    (uuid? id) id-tail-uuid
    (long-range-integer? id) id-tail-integer
    (keyword? id) id-tail-keyword
    (nil? id) id-tail-nil
    (boolean? id) (if id id-tail-true id-tail-false)
    :else (key-encode id)))

(defn key-id-suffix ^bytes [id]
  (let [id-string (key-encode (str id))
        tail (key-id-tail id)
        out (Arrays/copyOf id-string (+ (alength id-string) (alength tail)))]
    (System/arraycopy tail 0 out (alength id-string) (alength tail))
    out))

(def ^:private ^"[J" lowercase-hex-digit-values
  (let [values (long-array 257 -1)]
    (dotimes [i 10] (aset values (+ (int \0) i) (long i)))
    (dotimes [i 6] (aset values (+ (int \a) i) (long (+ 10 i))))
    values))

(def ^:private ^:const canonical-uuid-units 36)
(def ^:private ^:const ascii-dash 45)
(def ^:private ^:const encoded-uuid-string-bytes 38)
(def ^:private ^:const encoded-uuid-string-end (dec encoded-uuid-string-bytes))
(def ^:private ^:const uuid-string-first-narrow-tag (inc tag-string))

(defn- member-uuid-fast
  [^bytes a ^long i ^long mask]
  (when (and (<= (+ i encoded-uuid-string-bytes) (alength a))
             (= uuid-string-first-narrow-tag (member-at a i mask))
             (zero? (member-at a (+ i encoded-uuid-string-end) mask)))
    (let [^longs hex lowercase-hex-digit-values]
      (loop [k 0 hi 0 lo 0]
        (if (= k canonical-uuid-units)
          (UUID. hi lo)
          (let [unit (if (zero? k)
                       (member-at a (inc i) mask)
                       (inc (member-at a (+ i 1 k) mask)))]
            (if (or (= k 8) (= k 13) (= k 18) (= k 23))
              (when (= unit ascii-dash)
                (recur (inc k) hi lo))
              (let [digit (aget hex unit)]
                (when-not (neg? digit)
                  (if (< k 18)
                    (recur (inc k) (bit-or (bit-shift-left hi 4) digit) lo)
                    (recur (inc k) hi (bit-or (bit-shift-left lo 4) digit))))))))))))

(defn- member-read-id!
  [^bytes a ^longs pos ^long string-mask]
  (let [start (aget pos 0)
        uuid-tail-at (+ start encoded-uuid-string-bytes)
        fast (when (and (< uuid-tail-at (aget pos 1))
                        (= tag-uuid (bit-and 255 (aget a uuid-tail-at))))
               (member-uuid-fast a start string-mask))]
    (if fast
      (do (aset pos 0 (inc uuid-tail-at))
          fast)
      (let [string-end (skip-member-key a start string-mask)
            _ (when (> (inc string-end) (aget pos 1)) (throw (EOFException.)))
            tail (bit-and 255 (aget a string-end))]
        (if (= tail tag-uuid)
          (do (aset pos 0 (inc string-end))
              (or (member-uuid-fast a start string-mask)
                  (let [string-pos (doto (long-array 2) (aset 0 start) (aset 1 string-end))
                        s (member-read! a string-pos string-mask true)]
                    (when-not (string? s) (invalid-member!))
                    (try (UUID/fromString s)
                         (catch IllegalArgumentException _ (invalid-member!))))))
          (let [s (member-read! a pos string-mask true)]
            (when-not (string? s) (invalid-member!))
            (case (long (member-byte a pos 0))
              0x10 s
              0x20 (try (Long/parseLong s)
                        (catch NumberFormatException _ (invalid-member!)))
              0x38 (if (.startsWith ^String s ":")
                     (keyword (subs s 1))
                     (invalid-member!))
              1 nil
              2 false
              3 true
              0x40 (do (aset pos 0 (dec (aget pos 0)))
                       (member-read! a pos 0 true))
              (invalid-member!))))))))

(defn key-descending ^bytes [^bytes key]
  (let [n (alength key)
        out (byte-array n)]
    (dotimes [i n] (aset out i (unchecked-byte (bit-xor 255 (aget key i)))))
    out))

(defn key-prefix-end ^bytes [^bytes prefix]
  (loop [i (dec (alength prefix))]
    (when (>= i 0)
      (if (= 255 (bit-and 255 (aget prefix i)))
        (recur (dec i))
        (let [end (Arrays/copyOf prefix (inc i))]
          (aset-byte end i (unchecked-byte (inc (bit-and 255 (aget prefix i)))))
          end)))))

(def ^:private ^:const max-key-bytes 511)

(defn key-checked ^bytes [^bytes key]
  (when (> (alength key) max-key-bytes)
    (throw (ex-info "Keyed-fold key exceeds 511 bytes"
                    {:type :grain.read-model/key-too-large :bytes (alength key)})))
  key)

(def ^:private ordinary-registry-prefix (byte-array (map int "KR9")))
(def ^:private ordinary-data-prefix (byte-array (map int "KF9")))
(def ^:private scoped-registry-prefix (byte-array (map int "KRS9")))
(def ^:private scoped-data-prefix (byte-array (map int "KFS9")))
(def ^:private ^:const registry-kind-counter 0)
(def ^:private ^:const registry-kind-forward 1)
(def ^:private ^:const registry-kind-reverse 2)

(def ^:dynamic ^:private namespace-registry-prefix ordinary-registry-prefix)
(def ^:dynamic ^:private namespace-counter-key (key-join ordinary-registry-prefix (byte-array [registry-kind-counter])))
(def ^:dynamic ^:private namespace-data-prefix ordinary-data-prefix)
(def ^:private namespace-max-ordinal Integer/MAX_VALUE)

(defn- with-scoped-namespace-space [f]
  (binding [namespace-registry-prefix scoped-registry-prefix
            namespace-counter-key (key-join scoped-registry-prefix (byte-array [registry-kind-counter]))
            namespace-data-prefix scoped-data-prefix]
    (f)))

(defn- scoped-identity? [identity]
  (= 4 (count identity)))

(defn- with-namespace-space [identity f]
  (if (scoped-identity? identity)
    (with-scoped-namespace-space f)
    (f)))

(defn- namespace-failure [message data]
  (throw (ex-info message (assoc data :type :grain.read-model/invalid-registry))))

(defn- event-scope-identity? [scope]
  (and (vector? scope)
       (= 2 (count scope))
       (= :event-scope (first scope))
       (string? (second scope))
       (boolean (re-matches #"[0-9a-f]+" (second scope)))))

(defn- namespace-valid-identity? [identity]
  (and (vector? identity)
       (contains? #{3 4} (count identity))
       (uuid? (nth identity 0))
       (qualified-keyword? (nth identity 1))
       (or (pos-int? (nth identity 2)) (string? (nth identity 2)))
       (or (= 3 (count identity))
           (event-scope-identity? (nth identity 3)))))

(defn- namespace-digest [identity]
  (.digest (MessageDigest/getInstance "SHA-256") (key-encode-legacy identity)))

(defn- namespace-forward-key [identity]
  (key-join namespace-registry-prefix (byte-array [registry-kind-forward]) (namespace-digest identity)))

(defn- namespace-ordinal-bytes [ordinal]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int ordinal)))))

(defn- namespace-reverse-key [ordinal]
  (key-join namespace-registry-prefix (byte-array [registry-kind-reverse]) (namespace-ordinal-bytes ordinal)))

(defn- namespace-address [ordinal]
  (key-join namespace-data-prefix (namespace-ordinal-bytes ordinal)))

(defn- namespace-read-value [tx k]
  (when-let [raw ((:get tx) k)]
    (try
      {:value (codec/decode raw)}
      (catch Exception e
        (throw (ex-info "Cannot decode namespace registry entry"
                        {:type :grain.read-model/invalid-registry} e))))))

(defn- namespace-counter [tx]
  (when-let [entry (namespace-read-value tx namespace-counter-key)]
    (let [value (:value entry)
          next-id (:next-id value)]
      (when-not (and (map? value)
                     (= #{:next-id} (set (keys value)))
                     (integer? next-id)
                     (<= 0 next-id namespace-max-ordinal))
        (namespace-failure "Invalid namespace registry counter" {}))
      next-id)))

(defn- registry-record-valid? [{stored :identity ordinal :ordinal :as record} next-id]
  (and (map? record)
       (= #{:identity :ordinal} (set (keys record)))
       (namespace-valid-identity? stored)
       (= (count stored) (alength ^bytes namespace-registry-prefix))
       (integer? ordinal)
       (some? next-id)
       (<= 0 ordinal)
       (< ordinal next-id)))

(defn- namespace-lookup [tx identity next-id]
  (when-let [entry (namespace-read-value tx (namespace-forward-key identity))]
    (let [{stored :identity ordinal :ordinal :as record} (:value entry)]
      (when-not (registry-record-valid? record next-id)
        (namespace-failure "Invalid namespace registry forward entry" {:identity identity}))
      (when-not (= identity stored)
        (throw (ex-info "Namespace identity digest collision"
                        {:type :grain.read-model/namespace-collision :identity identity})))
      (when-not (= record (:value (namespace-read-value tx (namespace-reverse-key ordinal))))
        (namespace-failure "Namespace registry inverse does not match" {:identity identity}))
      (namespace-address ordinal))))

(defn- namespace-resolve-known [tx identities]
  (let [next-id (namespace-counter tx)]
    (reduce (fn [prefixes identity]
              (if-let [prefix (namespace-lookup tx identity next-id)]
                (assoc prefixes identity prefix)
                (reduced nil)))
            {}
            identities)))

(defn- scan [tx options rf init]
  ((:reduce tx) options rf init))

(defn- prefix-empty? [tx prefix]
  (not (scan tx {:prefix prefix} (fn [_ _] (reduced true)) false)))

(defn- validate-registry-record! [tx next-id ^bytes k kind]
  (let [{stored :identity ordinal :ordinal :as record} (:value (namespace-read-value tx k))]
    (when-not (registry-record-valid? record next-id)
      (namespace-failure "Invalid namespace registry record" {}))
    (let [^bytes expected-address (if (= kind registry-kind-forward)
                                    (namespace-forward-key stored)
                                    (namespace-reverse-key ordinal))]
      (when-not (Arrays/equals k expected-address)
        (namespace-failure "Namespace registry address does not match its record" {})))
    (when-not (namespace-lookup tx stored next-id)
      (namespace-failure "Namespace registry forward entry is missing" {:identity stored}))))

(defn- validate-registry-entry! [tx next-id ^bytes k]
  (when (<= (alength k) (alength ^bytes namespace-registry-prefix))
    (namespace-failure "Invalid namespace registry address" {}))
  (let [kind (bit-and 255 (aget k (alength ^bytes namespace-registry-prefix)))]
    (cond
      (= kind registry-kind-counter)
      (when-not (Arrays/equals k ^bytes namespace-counter-key)
        (namespace-failure "Invalid namespace counter address" {}))

      (or (= kind registry-kind-forward) (= kind registry-kind-reverse))
      (validate-registry-record! tx next-id k kind)

      :else
      (namespace-failure "Unknown namespace registry entry" {}))
    kind))

(defn- namespace-validate-registry! [tx next-id]
  (let [entries-by-kind (scan tx {:prefix namespace-registry-prefix}
                              (fn [counts {:keys [k]}]
                                (update counts (validate-registry-entry! tx next-id k) inc))
                              {registry-kind-counter 0 registry-kind-forward 0 registry-kind-reverse 0})]
    (when-not (= {registry-kind-counter 1 registry-kind-forward next-id registry-kind-reverse next-id}
                 entries-by-kind)
      (namespace-failure "Namespace registry counter does not cover its entries" {}))))

(defn- namespace-validate-allocation! [tx]
  (let [next-id (namespace-counter tx)]
    (cond
      (some? next-id) (namespace-validate-registry! tx next-id)
      (not (prefix-empty? tx namespace-registry-prefix)) (namespace-failure "Namespace registry counter is missing" {}))))

(defn- assign-ordinal! [tx identity ordinal]
  (when (>= ordinal namespace-max-ordinal)
    (throw (ex-info "Namespace ordinal space exhausted"
                    {:type :grain.read-model/namespace-exhausted})))
  (when (or ((:get tx) (namespace-reverse-key ordinal))
            (not (prefix-empty? tx (namespace-address ordinal))))
    (namespace-failure "Namespace ordinal is already assigned" {:ordinal ordinal}))
  (let [record (codec/encode {:identity identity :ordinal ordinal})]
    ((:put tx) (namespace-forward-key identity) record)
    ((:put tx) (namespace-reverse-key ordinal) record)
    ((:put tx) namespace-counter-key (codec/encode {:next-id (inc ordinal)}))
    (namespace-address ordinal)))

(defn- namespace-allocate-checked! [tx identities]
  (let [next-id (namespace-counter tx)]
    (when (and (nil? next-id) (not (prefix-empty? tx namespace-data-prefix)))
      (namespace-failure "Namespace registry is missing while materialized data remains" {}))
    (first
     (reduce (fn [[prefixes next-id] identity]
               (if-let [prefix (namespace-lookup tx identity next-id)]
                 [(assoc prefixes identity prefix) next-id]
                 [(assoc prefixes identity (assign-ordinal! tx identity next-id)) (inc next-id)]))
             [{} (or next-id 0)]
             identities))))

(defn- with-validated-registry [tx update!]
  (if-let [with-validation (:with-validation tx)]
    (with-validation namespace-registry-prefix namespace-validate-allocation! update!)
    (do (namespace-validate-allocation! tx)
        (update!))))

(defn- namespace-allocate! [tx identities]
  (with-validated-registry tx #(namespace-allocate-checked! tx identities)))

(defn- namespace-check-identities! [cache identities]
  (when-not (and cache (sequential? identities) (every? namespace-valid-identity? identities))
    (throw (ex-info "Expected cache and tenant/model/version namespace identities with optional event scope"
                    {:type :grain.read-model/invalid-namespace}))))

(defn namespace-resolve! [cache identities]
  (namespace-check-identities! cache identities)
  (let [{ordinary 3 scoped 4} (group-by count (distinct identities))
        resolve! (fn [identities]
                   (if (empty? identities)
                     {}
                     (or (kv/transact cache :read #(namespace-resolve-known % identities))
                         (kv/transact cache :write #(namespace-allocate! % identities)))))]
    (merge (resolve! ordinary)
           (when (seq scoped)
             (with-scoped-namespace-space #(resolve! scoped))))))

(def ^:private ^:const kind-progress 0)
(def ^:private ^:const kind-state 1)
(def ^:private ^:const kind-result 2)
(def ^:private ^:const kind-included-count 4)
(def ^:private ^:const kind-index 5)
(def ^:private ^:const kind-text-search 6)
(def ^:private ^:const kind-ordinal-by-id 7)
(def ^:private ^:const kind-id-by-ordinal 8)
(def ^:private ^:const kind-ordinal-counter 9)
(def ^:private ^:const kind-sort-data 11)
(def ^:private ^:const kind-included-members 12)
(def ^:private ^:const kind-index-levels 13)

(defn membership-number-bytes ^bytes [n]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int n)))))

(defn membership-number-value [^bytes raw]
  (.getInt (ByteBuffer/wrap raw)))

(defn- namespace-slot [b kind suffix]
  (key-checked (key-join b (byte-array [kind]) suffix)))

(defn membership-ordinal [tx b id]
  (some-> ((:get tx) (namespace-slot b kind-ordinal-by-id (key-encode id)))
          membership-number-value))

(defn membership-ensure-ordinal! [tx b id]
  (or (membership-ordinal tx b id)
      (let [counter (namespace-slot b kind-ordinal-counter (byte-array 0))
            ordinal (or (some-> ((:get tx) counter) membership-number-value) 0)]
        (when (>= ordinal Integer/MAX_VALUE)
          (throw (ex-info "Model ordinal space exhausted" {})))
        ((:put tx) (namespace-slot b kind-ordinal-by-id (key-encode id)) (membership-number-bytes ordinal))
        ((:put tx) (namespace-slot b kind-id-by-ordinal (membership-number-bytes ordinal)) (codec/encode id))
        ((:put tx) counter (membership-number-bytes (inc ordinal)))
        ordinal)))

(defn membership-decode ^RoaringBitmap [^bytes raw]
  (doto (RoaringBitmap.) (.deserialize (ByteBuffer/wrap raw))))

(defn membership-encode ^bytes [^RoaringBitmap bits]
  (let [out (ByteBuffer/allocate (.serializedSizeInBytes bits))]
    (.serialize bits out)
    (.array out)))

(defn membership-transaction [tx]
  (assoc tx ::span-membership/pending (HashMap.)))

(defn membership-bitmap ^RoaringBitmap [tx ^bytes k]
  (let [^HashMap pending (::span-membership/pending tx)
        address (ByteBuffer/wrap k)]
    (or (when pending (.get pending address))
        (when-let [raw ((:get tx) k)] (membership-decode raw))
        (RoaringBitmap.))))

(defn membership-put! [tx ^bytes k bits]
  (if-let [^HashMap pending (::span-membership/pending tx)]
    (.put pending (ByteBuffer/wrap k) bits)
    ((:put tx) k (membership-encode bits))))

(defn membership-delete! [tx ^bytes k]
  (when-let [^HashMap pending (::span-membership/pending tx)]
    (.remove pending (ByteBuffer/wrap k)))
  ((:delete tx) k))

(defn membership-flush! [tx observe]
  (when-let [^HashMap pending (::span-membership/pending tx)]
    (doseq [^java.util.Map$Entry entry (.entrySet pending)]
      (let [raw (membership-encode (.getValue entry))]
        ((:put tx) (.array ^ByteBuffer (.getKey entry)) raw)
        (observe :membership-written (alength raw))))))

;; Adapted from FoundationDB RankedSet.java, commit 8a20d30f318e529fa30084b265563e64eb3956e1.
;; Copyright 2015-2018 Apple Inc. and the FoundationDB project authors.
;; Licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
;; Modified for Grain; see resources/META-INF/licenses.

(def ranked-empty-key (byte-array 0))

(def ^:private ^:const ranked-default-levels 6)
(def ^:private ^:const membership-level-offset 16)

(defn ranked-random-hash [_]
  (.nextInt (ThreadLocalRandom/current)))

(def ^:private ^"[J" ranked-public-promotion-key
  (long-array [0x677261696e2d726d 0x702d70726f6d6f74]))

(defmacro ^:private sip-round [[v0 v1 v2 v3] & body]
  `(let [~v0 (unchecked-add ~v0 ~v1) ~v1 (Long/rotateLeft ~v1 13) ~v1 (bit-xor ~v1 ~v0)
         ~v0 (Long/rotateLeft ~v0 32)
         ~v2 (unchecked-add ~v2 ~v3) ~v3 (Long/rotateLeft ~v3 16) ~v3 (bit-xor ~v3 ~v2)
         ~v0 (unchecked-add ~v0 ~v3) ~v3 (Long/rotateLeft ~v3 21) ~v3 (bit-xor ~v3 ~v0)
         ~v2 (unchecked-add ~v2 ~v1) ~v1 (Long/rotateLeft ~v1 17) ~v1 (bit-xor ~v1 ~v2)
         ~v2 (Long/rotateLeft ~v2 32)]
     ~@body))

(defn- sip-byte ^long [^bytes a ^long off ^bytes b ^long i]
  (let [n (- (alength a) off)]
    (bit-and 0xff (if (< i n)
                    (aget a (unchecked-int (+ off i)))
                    (aget b (unchecked-int (- i n)))))))

(defn- sip-little-endian-word ^long [^bytes a ^long off ^bytes b ^long i]
  (let [n (- (alength a) off)]
    (if (or (>= i n) (<= (+ i 8) n))
      (let [^bytes src (if (< i n) a b)
            j (unchecked-int (if (< i n) (+ off i) (- i n)))]
        (bit-or (bit-and 0xff (aget src j))
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 1))) 8)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 2))) 16)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 3))) 24)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 4))) 32)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 5))) 40)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 6))) 48)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 7))) 56)))
      (loop [k 7 word 0]
        (if (neg? k)
          word
          (recur (dec k) (bit-or (bit-shift-left word 8) (sip-byte a off b (+ i k)))))))))

(defn siphash24
  ^long [^longs key ^bytes a ^long off ^bytes b]
  (let [len (+ (- (alength a) off) (alength b))
        whole-words-end (- len (rem len 8))
        k0 (aget key 0)
        k1 (aget key 1)]
    (loop [i 0
           v0 (bit-xor k0 0x736f6d6570736575)
           v1 (bit-xor k1 0x646f72616e646f6d)
           v2 (bit-xor k0 0x6c7967656e657261)
           v3 (bit-xor k1 0x7465646279746573)]
      (if (< i whole-words-end)
        (let [m (sip-little-endian-word a off b i)
              v3 (bit-xor v3 m)]
          (sip-round [v0 v1 v2 v3]
                     (sip-round [v0 v1 v2 v3]
                                (recur (+ i 8) (bit-xor v0 m) v1 v2 v3))))
        (let [m (loop [k (dec len) word (bit-shift-left len 56)]
                  (if (< k whole-words-end)
                    word
                    (recur (dec k) (bit-or word (bit-shift-left (sip-byte a off b k) (* 8 (- k whole-words-end)))))))
              v3 (bit-xor v3 m)]
          (sip-round [v0 v1 v2 v3]
                     (sip-round [v0 v1 v2 v3]
                                (let [v0 (bit-xor v0 m)
                                      v2 (bit-xor v2 0xff)]
                                  (sip-round [v0 v1 v2 v3]
                                             (sip-round [v0 v1 v2 v3]
                                                        (sip-round [v0 v1 v2 v3]
                                                                   (sip-round [v0 v1 v2 v3]
                                                                              (bit-xor v0 v1 v2 v3)))))))))))))

(defn ranked-promotion-hash [k ^bytes prefix off ^bytes key]
  (let [k (if (instance? clojure.lang.IDeref k) @k k)]
    (when-not k
      (throw (ex-info "Keyed ranked index written without a promotion key" {})))
    (siphash24 k prefix (long off) key)))

(defn ranked-config
  ([prefix]
   {:prefix prefix :levels ranked-default-levels :hash-offset 0})
  ([prefix hash-fn levels]
   (when-not (<= 2 levels 8)
     (throw (IllegalArgumentException. "Ranked index requires 2 to 8 levels")))
   {:prefix prefix :hash-fn hash-fn :levels levels}))

(defn- ranked-hash [tx cfg ^bytes key]
  (if-let [hash-fn (:hash-fn cfg)]
    (hash-fn key)
    (ranked-promotion-hash (or (::promotion-key tx)
                               (when-not (:keyed? cfg) ranked-public-promotion-key))
                           (:prefix cfg)
                           (:hash-offset cfg 0)
                           key)))

(defn- promoted-to-level? [^long hash ^long level]
  (zero? (bit-and hash (dec (bit-shift-left 1 (* 4 level))))))

(def ^:private promotion-key-address (byte-array [(int \K) (int \P) (int \9) -1]))

(defn- promotion-key-longs ^longs [^bytes raw]
  (when-not (= 16 (alength raw))
    (throw (ex-info "Promotion key must be 16 bytes" {:type :grain.read-model/invalid-promotion-key})))
  (let [little-endian (.order (ByteBuffer/wrap raw) ByteOrder/LITTLE_ENDIAN)]
    (long-array [(.getLong little-endian 0) (.getLong little-endian 8)])))

(defn pinned-promotion-key ^longs [pinned]
  (promotion-key-longs
   (cond
     (bytes? pinned) pinned
     (and (string? pinned) (re-matches #"[0-9a-fA-F]{32}" pinned)) (.parseHex (HexFormat/of) pinned)
     :else (throw (ex-info "Promotion key must be 16 bytes or 32 hexadecimal digits"
                           {:type :grain.read-model/invalid-promotion-key})))))

(defn keyed-promotion-key ^longs [tx]
  (promotion-key-longs
   (or ((:get tx) promotion-key-address)
       (let [raw (byte-array 16)]
         (.nextBytes (java.security.SecureRandom.) raw)
         ((:put tx) promotion-key-address raw)
         raw))))

(defn- with-promotion-key [ctx tx]
  (assoc tx ::promotion-key (delay (if-let [pinned (:read-model/promotion-key ctx)]
                                     (pinned-promotion-key pinned)
                                     (keyed-promotion-key tx)))))

(defn- ranked-level-prefix ^bytes [cfg level]
  (let [^bytes prefix (:prefix cfg)
        n (alength prefix)
        level-prefix (Arrays/copyOf prefix (inc n))]
    (aset level-prefix n (byte level))
    level-prefix))

(defn ranked-prefix ^bytes [cfg level]
  (if-let [^objects shared-level-prefixes (::level-prefixes cfg)]
    (let [level (int level)]
      (or (aget shared-level-prefixes level)
          (let [level-prefix (ranked-level-prefix cfg level)]
            (aset shared-level-prefixes level level-prefix)
            level-prefix)))
    (ranked-level-prefix cfg level)))

(defn ranked-pack ^bytes [cfg level ^bytes key]
  (let [^bytes prefix (:prefix cfg)
        n (alength prefix)
        k (alength key)
        packed (byte-array (+ n 1 k))]
    (System/arraycopy prefix 0 packed 0 n)
    (aset packed n (byte level))
    (System/arraycopy key 0 packed (inc n) k)
    (key-checked packed)))

(defn ranked-unpack ^bytes [cfg ^bytes key]
  (Arrays/copyOfRange key (inc (alength ^bytes (:prefix cfg))) (alength key)))

(defn ranked-after ^bytes [^bytes k]
  (if (< (alength k) max-key-bytes)
    (Arrays/copyOf k (inc (alength k)))
    (or (key-prefix-end k)
        (throw (ex-info "No scan bound after an all-FF key" {:bytes (alength k)})))))

(defn ranked-encode-count ^bytes [n]
  (.array (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN) (.putLong (long n)))))

(defn ranked-decode-count [^bytes v]
  (when v
    (.getLong (doto (ByteBuffer/wrap v) (.order ByteOrder/LITTLE_ENDIAN)))))

(defn ranked-check-key! [^bytes key]
  (when (zero? (alength key))
    (throw (IllegalArgumentException. "Empty key not allowed"))))

(defn- ranked-entry-count [cfg level ^bytes k v]
  (let [level-zero-member? (and (zero? level) (> (alength k) (inc (alength ^bytes (:prefix cfg)))))]
    (if level-zero-member?
      1
      (ranked-decode-count v))))

(defn span-count-transaction [tx]
  (assoc tx ::span-counts (HashMap.) ::group-counts (HashMap.) ::term-counts (HashMap.)))

(defn- cached-span-count [^HashMap span-counts level ^bytes k]
  (when (and span-counts (pos? (long level)))
    (when-let [^longs count (.get span-counts (ByteBuffer/wrap k))]
      (aget count 0))))

(defn- ranked-scan-count [^HashMap span-counts cfg level ^bytes k v]
  (or (when (and span-counts (pos? (long level)))
        (when-let [^longs count (.get span-counts (ByteBuffer/wrap k))]
          (aget count 0)))
      (ranked-entry-count cfg level k v)))

(def ^:private count-cache-key-order
  (reify Comparator
    (compare [_ a b]
      (Arrays/compareUnsigned (.array ^ByteBuffer (.getKey ^java.util.Map$Entry a))
                              (.array ^ByteBuffer (.getKey ^java.util.Map$Entry b))))))

(defn- count-cache-entries [^HashMap cache]
  (doto (ArrayList. (.entrySet cache)) (.sort count-cache-key-order)))

(defn span-count-flush! [tx]
  (when-let [^HashMap span-counts (::span-counts tx)]
    (doseq [^java.util.Map$Entry entry (count-cache-entries span-counts)]
      ((:put tx) (.array ^ByteBuffer (.getKey entry)) (ranked-encode-count (aget ^longs (.getValue entry) 0))))
    (.clear span-counts)))

(defn- upper-level-span-counts ^HashMap [tx level]
  (when (pos? (long level)) (::span-counts tx)))

(defn ranked-count-key [tx cfg level key]
  (let [k (ranked-pack cfg level key)]
    (or (cached-span-count (upper-level-span-counts tx level) level k)
        (when-let [v ((:get tx) k)] (ranked-entry-count cfg level k v)))))

(defn- forget-cached-span-count! [tx ^bytes k]
  (when-let [^HashMap span-counts (::span-counts tx)]
    (.remove span-counts (ByteBuffer/wrap k))))

(defn ranked-set-count! [tx cfg level key n]
  (let [k (ranked-pack cfg level key)]
    (forget-cached-span-count! tx k)
    ((:put tx) k (ranked-encode-count n))))

(defn- ranked-delete-count! [tx cfg level key]
  (let [k (ranked-pack cfg level key)]
    (forget-cached-span-count! tx k)
    ((:delete tx) k)))

(defn- stored-entry-count [tx cfg level ^bytes k]
  (let [v ((:get tx) k)]
    (or (when v (ranked-entry-count cfg level k v)) 0)))

(defn ranked-increment! [tx cfg level key delta]
  (let [k (ranked-pack cfg level key)]
    (if-let [span-counts (upper-level-span-counts tx level)]
      (let [address (ByteBuffer/wrap k)]
        (if-let [^longs count (.get span-counts address)]
          (aset count 0 (+ (aget count 0) (long delta)))
          (.put span-counts address (long-array [(+ (long (stored-entry-count tx cfg level k)) (long delta))]))))
      ((:put tx) k (ranked-encode-count (+ (stored-entry-count tx cfg level k) delta))))))

(defn ranked-init! [tx cfg]
  (dotimes [level (:levels cfg)]
    (when (nil? (ranked-count-key tx cfg level ranked-empty-key))
      (ranked-set-count! tx cfg level ranked-empty-key 0))))

(defn ranked-contains? [tx cfg key]
  (ranked-check-key! key)
  (pos? (or (ranked-count-key tx cfg 0 key) 0)))

(defn ranked-previous-key [tx cfg level key or-equal?]
  (let [level-prefix (ranked-prefix cfg level)
        packed (ranked-pack cfg level key)]
    (or (scan tx {:prefix level-prefix
                  :from level-prefix
                  :to (if or-equal? (ranked-after packed) packed)
                  :reverse? true}
              (fn [_ {:keys [k]}] (reduced (ranked-unpack cfg k)))
              nil)
        (throw (ex-info "no key found on level" {:level level})))))

(defn ranked-count-range [tx cfg level begin end]
  (let [span-counts (::span-counts tx)]
    (scan tx (cond-> {:prefix (ranked-prefix cfg level)}
               begin (assoc :from (ranked-pack cfg level begin))
               end (assoc :to (ranked-pack cfg level end)))
          (fn [n {:keys [k v]}] (+ n (ranked-scan-count span-counts cfg level k v)))
          0)))

(defn ranked-membership-key [cfg level key]
  (ranked-pack cfg (+ membership-level-offset level) key))

(defn- ranked-range-members ^RoaringBitmap [tx cfg level begin end]
  (scan tx {:prefix (ranked-prefix cfg level)
            :from (ranked-pack cfg level begin)
            :to (ranked-pack cfg level end)}
        (fn [^RoaringBitmap members {:keys [k]}]
          (let [member (ranked-unpack cfg k)
                address (ranked-membership-key cfg level member)]
            (if (zero? level)
              (when (pos? (alength ^bytes member))
                (.add members (int (membership-number-value ((:get tx) address)))))
              (.or members (membership-bitmap tx address))))
          members)
        (RoaringBitmap.)))

(defn- valid-membership-ordinal? [ordinal]
  (and (integer? ordinal) (<= 0 ordinal Integer/MAX_VALUE)))

(defn- check-membership-ordinal! [cfg ordinal]
  (when (and (:membership? cfg) (not (valid-membership-ordinal? ordinal)))
    (throw (ex-info "Membership index requires a model-local ordinal" {}))))

(defn- put-level-zero-member! [tx cfg ^bytes packed-member key value ordinal]
  ((:put tx) packed-member value)
  (when (:membership? cfg)
    ((:put tx) (ranked-membership-key cfg 0 key) (membership-number-bytes ordinal))))

(defn- grow-span! [tx cfg level span-key ordinal]
  (ranked-increment! tx cfg level span-key 1)
  (when (:membership? cfg)
    (let [address (ranked-membership-key cfg level span-key)
          members (membership-bitmap tx address)]
      (.add members (int ordinal))
      (membership-put! tx address members))))

(defn- split-span-at! [tx cfg level key ordinal]
  (let [previous (ranked-previous-key tx cfg level key false)
        previous-count (ranked-count-key tx cfg level previous)
        left-count (ranked-count-range tx cfg (dec level) previous key)]
    (ranked-set-count! tx cfg level previous left-count)
    (ranked-set-count! tx cfg level key (inc (- previous-count left-count)))
    (when (:membership? cfg)
      (let [previous-address (ranked-membership-key cfg level previous)
            right (membership-bitmap tx previous-address)
            left (ranked-range-members tx cfg (dec level) previous key)]
        (.andNot right left)
        (.add right (int ordinal))
        (membership-put! tx previous-address left)
        (membership-put! tx (ranked-membership-key cfg level key) right)))))

(defn ranked-add!
  ([tx cfg key] (ranked-add! tx cfg key (ranked-encode-count 1) nil))
  ([tx cfg key value] (ranked-add! tx cfg key value nil))
  ([tx cfg key value ordinal]
   (ranked-check-key! key)
   (check-membership-ordinal! cfg ordinal)
   (let [packed-member (ranked-pack cfg 0 key)
         stored ((:get tx) packed-member)]
     (if (pos? (or (when stored (ranked-entry-count cfg 0 packed-member stored)) 0))
       false
       (let [hash (ranked-hash tx cfg key)]
         (dotimes [level (:levels cfg)]
           (cond
             (zero? level) (put-level-zero-member! tx cfg packed-member key value ordinal)
             (promoted-to-level? hash level) (split-span-at! tx cfg level key ordinal)
             :else (grow-span! tx cfg level (ranked-previous-key tx cfg level key false) ordinal)))
         true)))))

(defn- start-span-at-end! [tx cfg level key ordinal ^objects last-span-keys]
  (ranked-set-count! tx cfg level key 1)
  (when (:membership? cfg)
    (let [previous-address (ranked-membership-key cfg level (aget last-span-keys level))]
      (membership-put! tx previous-address (membership-bitmap tx previous-address))
      (membership-put! tx (ranked-membership-key cfg level key) (doto (RoaringBitmap.) (.add (int ordinal))))))
  (aset last-span-keys level key))

(defn- ranked-append! [tx cfg key value ordinal hash last-span-keys]
  (if (ranked-contains? tx cfg key)
    false
    (do
      (dotimes [level (:levels cfg)]
        (cond
          (zero? level) (put-level-zero-member! tx cfg (ranked-pack cfg 0 key) key value ordinal)
          (promoted-to-level? hash level) (start-span-at-end! tx cfg level key ordinal last-span-keys)
          :else (grow-span! tx cfg level (aget ^objects last-span-keys level) ordinal)))
      true)))

(defn- merge-span-into-previous! [tx cfg level key ordinal]
  (let [span-count (ranked-count-key tx cfg level key)
        previous (ranked-previous-key tx cfg level key false)]
    (when span-count
      (ranked-delete-count! tx cfg level key))
    (ranked-increment! tx cfg level previous (+ -1 (or span-count 0)))
    (when (:membership? cfg)
      (let [previous-address (ranked-membership-key cfg level previous)
            members (membership-bitmap tx previous-address)]
        (when span-count
          (.or members (membership-bitmap tx (ranked-membership-key cfg level key)))
          (membership-delete! tx (ranked-membership-key cfg level key)))
        (.remove members (int ordinal))
        (membership-put! tx previous-address members)))))

(defn ranked-remove! [tx cfg key]
  (ranked-check-key! key)
  (if-not (ranked-contains? tx cfg key)
    false
    (let [ordinal (when (:membership? cfg)
                    (membership-number-value ((:get tx) (ranked-membership-key cfg 0 key))))]
      (dotimes [level (:levels cfg)]
        (if (zero? level)
          (do ((:delete tx) (ranked-pack cfg 0 key))
              (when (:membership? cfg)
                ((:delete tx) (ranked-membership-key cfg 0 key))))
          (merge-span-into-previous! tx cfg level key ordinal)))
      true)))

(defn ranked-size [tx cfg]
  (ranked-count-range tx cfg (dec (:levels cfg)) nil nil))

(defn ranked-members ^RoaringBitmap [tx cfg observe]
  (let [top-level (dec (:levels cfg))]
    (scan tx {:prefix (ranked-prefix cfg top-level)}
          (fn [^RoaringBitmap members {:keys [k]}]
            (when-let [raw ((:get tx) (ranked-membership-key cfg top-level (ranked-unpack cfg k)))]
              (observe :membership-read (alength ^bytes raw))
              (.or members (membership-decode raw)))
            members)
          (RoaringBitmap.))))

(deftype RankedVirtualMember [^bytes member ^bytes payload])

(defn- ranked-virtual-bound [^java.util.List virtual ^bytes boundary]
  (if (nil? boundary)
    (.size virtual)
    (loop [lo 0 hi (.size virtual)]
      (if (= lo hi)
        lo
        (let [mid (quot (+ lo hi) 2)
              ^bytes member (.-member ^RankedVirtualMember (.get virtual mid))]
          (if (neg? (Arrays/compareUnsigned member boundary))
            (recur (inc mid) hi)
            (recur lo mid)))))))

(defn- ranked-run-value ^bytes [value-length ^objects item]
  (let [^bytes member (aget item 0)]
    (Arrays/copyOf member (int (value-length member)))))

(defn- run-end ^long [^java.util.List items ^long start run-value]
  (let [n (.size items)
        ^bytes value (run-value (.get items start))]
    (loop [j (inc start)]
      (if (and (< j n) (Arrays/equals value ^bytes (run-value (.get items j))))
        (recur (inc j))
        j))))

(defn ranked-desc-items [nav value-length offset n]
  (let [total (long ((:total nav)))
        out (ArrayList.)
        run-value (partial ranked-run-value value-length)
        still-needed #(- (long n) (.size out))
        emit-runs-from-top!
        (fn [^java.util.List items-from-top requested]
          (let [item-count (.size items-from-top)
                sequence-ended? (< item-count (long requested))]
            (loop [i 0]
              (when (and (< i item-count) (pos? (still-needed)))
                (let [end (run-end items-from-top i run-value)]
                  (if (or (< end item-count) sequence-ended?)
                    (do (loop [q (dec end)]
                          (when (and (>= q i) (pos? (still-needed)))
                            (.add out (.get items-from-top q))
                            (recur (dec q))))
                        (recur end))
                    (.addAll out ((:run nav) (run-value (.get items-from-top i)) (still-needed)))))))))]
    (when (and (pos? n) (< -1 offset total))
      (if (zero? offset)
        (emit-runs-from-top! ((:reverse nav) 0 (inc n)) (inc n))
        (let [^java.util.List from-item-above ((:reverse nav) (dec offset) (+ 2 n))]
          (if (or (< (.size from-item-above) 2)
                  (not (Arrays/equals ^bytes (run-value (.get from-item-above 0))
                                      ^bytes (run-value (.get from-item-above 1)))))
            (emit-runs-from-top! (.subList from-item-above 1 (.size from-item-above)) (inc n))
            (let [value (run-value (.get from-item-above 1))
                  run-start (long ((:rank nav) value))
                  run-stop (long ((:rank nav) (key-prefix-end value)))
                  ascending-position (- total 1 offset)
                  run-items-above (- run-stop 1 ascending-position)]
              (.addAll out ((:forward nav) (+ run-start run-items-above)
                                           (min (still-needed) (- run-stop run-start run-items-above))))
              (when (and (pos? (still-needed)) (pos? run-start))
                (emit-runs-from-top! ((:reverse nav) (- total run-start) (inc (still-needed)))
                                     (inc (still-needed)))))))))
    out))

(defn- ranked-filter-navigator
  [tx cfg ^RoaringBitmap candidates observe ^java.util.List virtual]
  (let [match-counts (HashMap.)
        visits (volatile! 0)
        leaves (volatile! 0)
        top-level (dec (:levels cfg))
        span-matches
        (fn [level ^bytes member]
          (if (and (zero? level) (zero? (alength member)))
            0
            (let [address (ranked-membership-key cfg level member)
                  lookup (ByteBuffer/wrap address)]
              (or (.get match-counts lookup)
                  (let [raw ((:get tx) address)
                        _ (when raw (observe :membership-read (alength ^bytes raw)))
                        n (if (zero? level)
                            (do (vswap! leaves inc)
                                (if (and raw (.contains candidates (int (membership-number-value raw)))) 1 0))
                            (if raw (RoaringBitmap/andCardinality candidates (membership-decode raw)) 0))]
                    (.put match-counts lookup n)
                    n)))))
        virtual-bound (fn [boundary] (ranked-virtual-bound virtual boundary))
        virtual-item (fn [i]
                       (let [^RankedVirtualMember m (.get virtual (int i))]
                         (object-array [(.-member m) (.-payload m) true])))
        real-total (delay (if (.isEmpty candidates)
                            0
                            (scan tx {:prefix (ranked-prefix cfg top-level)}
                                  (fn [n {:keys [k]}]
                                    (vswap! visits inc)
                                    (+ n (long (span-matches top-level (ranked-unpack cfg k)))))
                                  0)))
        total #(+ (long @real-total) (.size virtual))
        forward
        (fn [skip limit]
          (let [remaining (volatile! skip)
                out (ArrayList.)]
            (letfn [(visit [level {:keys [k v]} end]
                      (let [member (ranked-unpack cfg k)
                            real (span-matches level member)
                            begin-virtual (virtual-bound member)
                            end-virtual (virtual-bound end)
                            n (+ real (- end-virtual begin-virtual))]
                        (cond
                          (<= n @remaining) (vswap! remaining - n)
                          (pos? level) (walk (dec level) member end)
                          :else (do (when (pos? real)
                                      (if (pos? @remaining)
                                        (vswap! remaining dec)
                                        (.add out (object-array [member v]))))
                                    (let [start (+ begin-virtual @remaining)
                                          stop (min end-virtual (+ start (- limit (.size out))))]
                                      (doseq [i (range start stop)] (.add out (virtual-item i)))
                                      (vreset! remaining 0))))))
                    (walk [level begin end]
                      (let [last-entry (scan tx (cond-> {:prefix (ranked-prefix cfg level)
                                                         :from (ranked-pack cfg level begin)}
                                                  end (assoc :to (ranked-pack cfg level end)))
                                             (fn [previous entry]
                                               (vswap! visits inc)
                                               (when previous
                                                 (visit level previous (ranked-unpack cfg (:k entry))))
                                               (if (= limit (.size out)) (reduced nil) entry))
                                             nil)]
                        (when (and last-entry (< (.size out) limit))
                          (visit level last-entry end))))]
              (when (and (pos? limit) (< skip (total)))
                (if (zero? (long @real-total))
                  (doseq [i (range skip (min (total) (+ skip limit)))] (.add out (virtual-item i)))
                  (walk top-level ranked-empty-key nil))))
            out))
        reverse
        (fn [skip limit]
          (let [remaining (volatile! skip)
                out (ArrayList.)]
            (letfn [(visit [level ^bytes member v upper]
                      (let [real (long (span-matches level member))
                            begin-virtual (long (virtual-bound member))
                            end-virtual (long (virtual-bound upper))
                            n (+ real (- end-virtual begin-virtual))]
                        (cond
                          (<= n @remaining) (vswap! remaining - n)
                          (pos? level) (walk (dec level) member upper)
                          :else (let [skipped (long @remaining)]
                                  (if (< skipped (- end-virtual begin-virtual))
                                    (do (loop [i (- end-virtual 1 skipped)]
                                          (when (and (>= i begin-virtual) (< (.size out) limit))
                                            (.add out (virtual-item i))
                                            (recur (dec i))))
                                        (when (and (pos? real) (< (.size out) limit))
                                          (.add out (object-array [member v]))))
                                    (when (pos? real)
                                      (.add out (object-array [member v]))))
                                  (vreset! remaining 0)))))
                    (walk [level begin end]
                      (scan tx (cond-> {:prefix (ranked-prefix cfg level)
                                        :reverse? true
                                        :from (ranked-pack cfg level begin)}
                                 end (assoc :to (ranked-pack cfg level end)))
                            (fn [upper {:keys [k v]}]
                              (vswap! visits inc)
                              (let [member (ranked-unpack cfg k)]
                                (visit level member v upper)
                                (if (= limit (.size out)) (reduced nil) member)))
                            end))]
              (when (and (pos? limit) (< skip (total)))
                (if (zero? (long @real-total))
                  (loop [i (- (total) 1 skip)]
                    (when (and (>= i 0) (< (.size out) limit))
                      (.add out (virtual-item i))
                      (recur (dec i))))
                  (walk top-level ranked-empty-key nil))))
            out))
        rank
        (fn [^bytes key]
          (+ (long (virtual-bound key))
             (if (zero? (long @real-total))
               0
               (loop [level top-level begin ranked-empty-key acc 0]
                 (let [[sum ^bytes last-member]
                       (scan tx {:prefix (ranked-prefix cfg level)
                                 :from (ranked-pack cfg level begin)
                                 :to (ranked-pack cfg level key)}
                             (fn [[sum ^bytes previous] {:keys [k]}]
                               (vswap! visits inc)
                               [(+ (long sum) (if previous (long (span-matches level previous)) 0))
                                (ranked-unpack cfg k)])
                             [0 nil])]
                   (cond
                     (nil? last-member) acc
                     (zero? level) (+ acc (long sum) (long (span-matches 0 last-member)))
                     :else (recur (dec level) last-member (+ acc (long sum)))))))))]
    {:total total
     :forward forward
     :reverse reverse
     :rank rank
     :run (fn [v n] (forward (rank v) n))
     :visits visits
     :leaves leaves}))

(defn- page-count ^long [^long total ^long limit]
  (max 1 (quot (+ total (dec limit)) limit)))

(defn- real-leaf-item? [^objects item]
  (== 2 (alength item)))

(defn ranked-filtered-page
  ([tx cfg candidates requested-page limit observe]
   (ranked-filtered-page tx cfg candidates requested-page limit observe []))
  ([tx cfg candidates requested-page limit observe virtual]
   (ranked-filtered-page tx cfg candidates requested-page limit observe virtual :asc nil))
  ([tx cfg ^RoaringBitmap candidates requested-page limit observe ^java.util.List virtual direction value-length]
   (let [nav (ranked-filter-navigator tx cfg candidates observe virtual)
         total (long ((:total nav)))
         pages (page-count total limit)
         page (min requested-page pages)
         offset (* (dec page) limit)
         items (if (= direction :desc)
                 (ranked-desc-items nav value-length offset (min limit (- total offset)))
                 ((:forward nav) offset limit))
         leaf-payload (::leaf-payload cfg)]
     {:payloads (mapv (fn [^objects item]
                        (if (and leaf-payload (real-leaf-item? item))
                          (leaf-payload (aget item 0) (aget item 1))
                          (aget item 1)))
                      items)
      :total total
      :total-pages pages
      :page page
      :page-size limit
      :rank-entries-examined @(:visits nav)
      :membership-leaves-examined @(:leaves nav)})))

(defn ranked-get-nth [tx cfg ordinal]
  (when-not (neg? ordinal)
    (loop [level (dec (:levels cfg)) key ranked-empty-key remaining ordinal]
      (when (>= level 0)
        (let [span-counts (::span-counts tx)
              found (scan tx {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level key)}
                          (fn [{:keys [remaining]} {:keys [k v]}]
                            (let [key (ranked-unpack cfg k)
                                  n (ranked-scan-count span-counts cfg level k v)]
                              (cond
                                (and (zero? remaining) (pos? (alength ^bytes key))) (reduced {:done key})
                                (> n remaining) (reduced {:descend key :remaining remaining})
                                :else {:remaining (- remaining n)})))
                          {:remaining remaining})]
          (cond
            (:done found) (:done found)
            (:descend found) (recur (dec level) (:descend found) (:remaining found))
            :else nil))))))

(defn ranked-rank [tx cfg key null-if-missing?]
  (ranked-check-key! key)
  (when (or (not null-if-missing?) (ranked-contains? tx cfg key))
    (loop [level (dec (:levels cfg)) rank-key ranked-empty-key ordinal 0]
      (let [span-counts (::span-counts tx)
            found (scan tx {:prefix (ranked-prefix cfg level)
                            :from (ranked-pack cfg level rank-key)
                            :to (ranked-after (ranked-pack cfg level key))}
                        (fn [{:keys [ordinal]} {:keys [k v]}]
                          (let [n (ranked-scan-count span-counts cfg level k v)]
                            {:rank-key (ranked-unpack cfg k) :last-count n :ordinal (+ ordinal n)}))
                        {:rank-key rank-key :last-count 0 :ordinal ordinal})
            next-rank (- (:ordinal found) (:last-count found))]
        (cond
          (Arrays/equals ^bytes (:rank-key found) ^bytes key) next-rank
          (zero? level) (+ next-rank (if (and (not null-if-missing?) (pos? (:last-count found))) 1 0))
          :else (recur (dec level) (:rank-key found) next-rank))))))

(defn- ranked-level-entries ^ArrayList [tx cfg level]
  (let [span-counts (::span-counts tx)]
    (scan tx {:prefix (ranked-prefix cfg level)}
          (fn [^ArrayList entries {:keys [k v]}]
            (.add entries [(ranked-unpack cfg k) (ranked-scan-count span-counts cfg level k v)])
            entries)
          (ArrayList.))))

(defn- level-zero-member-bitmap ^RoaringBitmap [tx cfg ^bytes member]
  (if (pos? (alength member))
    (doto (RoaringBitmap.)
      (.add (int (membership-number-value ((:get tx) (ranked-membership-key cfg 0 member))))))
    (RoaringBitmap.)))

(defn- ranked-build-level! [tx cfg level]
  (let [below (dec level)
        membership? (:membership? cfg)
        entries (ranked-level-entries tx cfg below)
        write-span! (fn [key n ^RoaringBitmap members]
                      (ranked-set-count! tx cfg level key n)
                      (when membership?
                        (.removeRunCompression members)
                        (membership-put! tx (ranked-membership-key cfg level key) members)))]
    (loop [i 0 span-key ranked-empty-key span-count 0 span-members (when membership? (RoaringBitmap.))]
      (if (= i (.size entries))
        (write-span! span-key span-count span-members)
        (let [[^bytes member member-count] (.get entries i)
              promoted? (and (pos? (alength member))
                             (promoted-to-level? (ranked-hash tx cfg member) level))
              member-members (when membership?
                               (if (zero? below)
                                 (level-zero-member-bitmap tx cfg member)
                                 (membership-bitmap tx (ranked-membership-key cfg below member))))]
          (if promoted?
            (do (write-span! span-key span-count span-members)
                (recur (inc i) member (long member-count)
                       (when membership? (doto (RoaringBitmap.) (.or ^RoaringBitmap member-members)))))
            (do (when membership? (.or ^RoaringBitmap span-members ^RoaringBitmap member-members))
                (recur (inc i) span-key (+ span-count (long member-count)) span-members))))))))

(defn- ranked-drop-level! [tx cfg level]
  (doseq [[member _] (ranked-level-entries tx cfg level)]
    (ranked-delete-count! tx cfg level member)
    (when (:membership? cfg)
      (membership-delete! tx (ranked-membership-key cfg level member))))
  (when (:membership? cfg)
    (doseq [k (scan tx {:prefix (ranked-prefix cfg (+ membership-level-offset level))}
                    (fn [^ArrayList ks {:keys [k]}] (.add ks k) ks)
                    (ArrayList.))]
      (membership-delete! tx k))))

(defn ranked-relevel! [tx cfg target]
  (let [current (long (:levels cfg))
        target (long target)]
    (when-not (<= 2 target 6)
      (throw (IllegalArgumentException. "Ranked index requires 2 to 6 levels")))
    (cond
      (< current target) (loop [level current]
                           (when (< level target)
                             (ranked-build-level! tx cfg level)
                             (recur (inc level))))
      (> current target) (loop [level (dec current)]
                           (when (>= level target)
                             (ranked-drop-level! tx cfg level)
                             (recur (dec level)))))
    (assoc cfg :levels target)))

(defn- ranked-l0-scan ^ArrayList [tx cfg ^bytes from ^bytes to n reverse?]
  (if-not (pos? (long n))
    (ArrayList.)
    (scan tx (cond-> {:prefix (ranked-prefix cfg 0) :from from}
               to (assoc :to to)
               reverse? (assoc :reverse? true))
          (fn [^ArrayList items {:keys [k v]}]
            (.add items (object-array [(ranked-unpack cfg k) v]))
            (if (= (long n) (.size items)) (reduced items) items))
          (ArrayList.))))

(defn ranked-range-navigator [tx cfg ^bytes lo ^bytes hi start end]
  (let [start (long start)
        end (long end)
        size (- end start)
        packed-member-at-rank (fn [rank] (ranked-pack cfg 0 (ranked-get-nth tx cfg rank)))
        in-range? (fn [position n] (and (pos? (long n)) (< (long position) size)))]
    {:total (constantly size)
     :forward (fn [position n]
                (if (in-range? position n)
                  (ranked-l0-scan tx cfg (packed-member-at-rank (+ start (long position))) hi n false)
                  (ArrayList.)))
     :reverse (fn [position n]
                (if (in-range? position n)
                  (ranked-l0-scan tx cfg lo
                                  (if (zero? (long position))
                                    hi
                                    (ranked-after (packed-member-at-rank (- end 1 (long position)))))
                                  n true)
                  (ArrayList.)))
     :rank (fn [key] (- (long (ranked-rank tx cfg key false)) start))
     :run (fn [^bytes value n] (ranked-l0-scan tx cfg (ranked-pack cfg 0 value) hi n false))}))

(defn- preserve-reduced [rf]
  (fn [acc item]
    (let [result (rf acc item)]
      (if (reduced? result) (reduced result) result))))

(defn ranked-desc-reduce [tx cfg value-length ^bytes lo ^bytes hi chunk rf init]
  (let [chunk (long chunk)
        run-value (partial ranked-run-value value-length)
        rf-inside-scan (preserve-reduced rf)
        reduce-run-descending (fn [^ArrayList items start end acc]
                                (loop [q (dec (long end)) acc acc]
                                  (if (< q (long start))
                                    acc
                                    (let [acc (rf acc (.get items q))]
                                      (if (reduced? acc) acc (recur (dec q) acc))))))
        reduce-whole-run (fn [^bytes run-start-key ^bytes value acc]
                           (scan tx {:prefix (ranked-prefix cfg 0)
                                     :from run-start-key
                                     :to (ranked-pack cfg 0 (key-prefix-end value))}
                                 (fn [acc {:keys [k v]}]
                                   (rf-inside-scan acc (object-array [(ranked-unpack cfg k) v])))
                                 acc))]
    (loop [to hi acc init]
      (let [^ArrayList items (ranked-l0-scan tx cfg lo to (inc chunk) true)
            item-count (.size items)
            chunk-complete? (<= item-count chunk)
            [state next-to acc]
            (loop [i 0 acc acc]
              (if (>= i item-count)
                [:done nil acc]
                (let [end (run-end items i run-value)]
                  (if (or (< end item-count) chunk-complete?)
                    (let [acc (reduce-run-descending items i end acc)]
                      (if (reduced? acc) [:stop nil acc] (recur end acc)))
                    (let [value (run-value (.get items i))
                          run-start-key (ranked-pack cfg 0 value)
                          acc (reduce-whole-run run-start-key value acc)]
                      (if (reduced? acc)
                        [:stop nil acc]
                        [:continue run-start-key acc]))))))]
        (case state
          :continue (recur next-to acc)
          :stop (unreduced acc)
          acc)))))

(defn- ranked-spans [cfg]
  (let [levels (int (:levels cfg))]
    {:keys (object-array (repeat levels ranked-empty-key))
     :counts (long-array levels)
     :bits (object-array (repeatedly levels #(RoaringBitmap.)))}))

(defn- ranked-span-flush! [tx cfg {:keys [^objects keys ^longs counts ^objects bits]} level]
  (ranked-set-count! tx cfg level (aget keys level) (aget counts level))
  (when (:membership? cfg)
    ((:put tx) (ranked-membership-key cfg level (aget keys level)) (membership-encode (aget bits level)))))

(defn- ranked-span-add! [tx cfg {:keys [^objects keys ^longs counts ^objects bits] :as spans} ^bytes member ordinal]
  (let [levels (int (:levels cfg))
        membership? (:membership? cfg)
        hash (ranked-hash tx cfg member)]
    (loop [level 1]
      (when (< level levels)
        (if (promoted-to-level? hash level)
          (do (ranked-span-flush! tx cfg spans level)
              (aset keys level member)
              (aset counts level 1)
              (when membership?
                (aset bits level (doto (RoaringBitmap.) (.add (int ordinal))))))
          (do (aset counts level (inc (aget counts level)))
              (when membership?
                (.add ^RoaringBitmap (aget bits level) (int ordinal)))))
        (recur (inc level))))))

(defn- ranked-span-finish! [tx cfg spans]
  (loop [level 1]
    (when (< level (:levels cfg))
      (ranked-span-flush! tx cfg spans level)
      (recur (inc level)))))

(defn- ranked-bulk-load! [tx cfg ^ArrayList entries]
  (let [spans (ranked-spans cfg)]
    (ranked-init! tx cfg)
    (doseq [^objects entry entries]
      (let [^bytes member (aget entry 0)
            ordinal (aget entry 2)]
        ((:put tx) (ranked-pack cfg 0 member) (aget entry 1))
        (when (:membership? cfg)
          ((:put tx) (ranked-membership-key cfg 0 member) (membership-number-bytes ordinal)))
        (ranked-span-add! tx cfg spans member ordinal)))
    (ranked-span-finish! tx cfg spans)))

(defn sort-data-int-at ^long [^bytes data ^long offset]
  (bit-or (bit-shift-left (bit-and 255 (aget data offset)) 24)
          (bit-shift-left (bit-and 255 (aget data (inc offset))) 16)
          (bit-shift-left (bit-and 255 (aget data (+ offset 2))) 8)
          (bit-and 255 (aget data (+ offset 3)))))

(def ^:private ^:const sort-data-identity-string-field 0)
(def ^:private ^:const sort-data-id-field 1)
(def ^:private ^:const sort-data-first-index-field 2)

(defn sort-data-field [indexes index]
  (if (= :identity index)
    sort-data-identity-string-field
    (+ sort-data-first-index-field (.indexOf ^java.util.List (vec (sort (keys indexes))) index))))

(defn sort-data-encode ^bytes [id indexes]
  (let [fields (mapv key-encode-compact (concat [(str id) id] (map val (sort-by key indexes))))
        header-bytes (* 4 (inc (count fields)))
        out (ByteBuffer/allocate (+ header-bytes (reduce + (map #(alength ^bytes %) fields))))]
    (loop [offset header-bytes fields (seq fields)]
      (.putInt out offset)
      (when-let [^bytes value (first fields)]
        (recur (+ offset (alength value)) (next fields))))
    (doseq [^bytes value fields]
      (.put out value))
    (.array out)))

(defn sort-data-value [^bytes data ^long field]
  (let [start (sort-data-int-at data (* 4 field))
        end (sort-data-int-at data (* 4 (inc field)))]
    (key-decode-compact data start (- end start))))

(defn sort-data-values [data indexes fields]
  (persistent!
   (reduce-kv (fn [values i index] (assoc! values index (sort-data-value data (nth fields i))))
              (transient {})
              indexes)))

(defn sort-data-compare-field ^long [^bytes a ^bytes b ^long field]
  (Arrays/compareUnsigned a (sort-data-int-at a (* 4 field)) (sort-data-int-at a (* 4 (inc field)))
                          b (sort-data-int-at b (* 4 field)) (sort-data-int-at b (* 4 (inc field)))))

(defn sort-data-field= [^bytes a ^bytes b ^long field]
  (zero? (sort-data-compare-field a b field)))

(deftype SortDataEntry [id ^bytes data ^bytes computed])

(defn sort-data-comparator [field descending? computed?]
  (reify java.util.Comparator
    (compare [_ a b]
      (let [^SortDataEntry a a
            ^SortDataEntry b b
            c (if computed?
                (Arrays/compareUnsigned ^bytes (.-computed a) ^bytes (.-computed b))
                (sort-data-compare-field (.-data a) (.-data b) field))]
        (if (zero? c)
          (let [tie (sort-data-compare-field (.-data a) (.-data b) sort-data-identity-string-field)]
            (int (if (zero? tie)
                   (sort-data-compare-field (.-data a) (.-data b) sort-data-id-field)
                   tie)))
          (int (if descending? (- c) c)))))))

(def ^:private ^:const term-kind-postings 0)
(def ^:private ^:const term-kind-count 1)

(defn text-search-normalize [text]
  (when-not (or (nil? text) (string? text))
    (throw (ex-info "Search text extractor must return a string or nil" {})))
  (let [^String text (or text "")]
    (.toLowerCase text Locale/ROOT)))

(defn- text-search-config [b index]
  (namespace-slot b kind-text-search (key-encode index)))

(defn- text-search-term-key [cfg kind term]
  (key-checked (key-join cfg (byte-array [kind]) (key-encode term))))

(defn- text-search-posting-key [prefix ordinal]
  (key-checked (key-join prefix (membership-number-bytes ordinal))))

(defn- text-search-terms [^String text]
  (let [^ints points (.toArray (.codePoints text))
        n (alength points)
        grams (reduce (fn [terms i] (update terms [:gram (String. points (int i) 3)] (fnil conj []) i))
                      {}
                      (range (max 0 (- n 2))))
        terms (reduce (fn [terms width]
                        (assoc terms
                               [:prefix (String. points 0 (int width))] [0]
                               [:suffix (String. points (int (- n width)) (int width))] [(- n width)]))
                      grams
                      (range 1 (inc (min 2 n))))]
    (into {} (for [[term positions] terms]
               (let [posting (ByteBuffer/allocate (* 4 (inc (count positions))))]
                 (.putInt posting n)
                 (doseq [position positions]
                   (.putInt posting (int position)))
                 [term (.array posting)])))))

(defn- check-term-count! [^long n]
  (when (neg? n)
    (throw (ex-info "Negative text posting count" {}))))

(defn- text-search-change-count! [tx cfg term delta]
  (let [k (text-search-term-key cfg term-kind-count term)]
    (if-let [^HashMap term-counts (::term-counts tx)]
      (let [address (ByteBuffer/wrap k)
            ^longs count-and-stored (or (.get term-counts address)
                                        (let [raw ((:get tx) k)
                                              entry (long-array [(if raw (sort-data-int-at raw 0) 0) (if raw 1 0)])]
                                          (.put term-counts address entry)
                                          entry))
            updated (+ (aget count-and-stored 0) (long delta))]
        (check-term-count! updated)
        (aset count-and-stored 0 updated))
      (let [stored (if-let [raw ((:get tx) k)] (sort-data-int-at raw 0) 0)
            updated (+ stored delta)]
        (check-term-count! updated)
        (if (zero? updated)
          ((:delete tx) k)
          ((:put tx) k (membership-number-bytes updated)))))))

(defn- term-count-flush! [tx]
  (when-let [^HashMap term-counts (::term-counts tx)]
    (doseq [^java.util.Map$Entry entry (count-cache-entries term-counts)
            :let [^longs count-and-stored (.getValue entry)
                  k (.array ^ByteBuffer (.getKey entry))
                  term-count (aget count-and-stored 0)
                  stored-before? (== 1 (aget count-and-stored 1))]]
      (if (zero? term-count)
        (when stored-before? ((:delete tx) k))
        ((:put tx) k (membership-number-bytes term-count))))
    (.clear term-counts)))

(defn- text-search-changes [searches before after]
  (into []
        (keep (fn [[index extract]]
                (let [old-text (when before (text-search-normalize (extract (:value before))))
                      new-text (when after (text-search-normalize (extract (:value after))))]
                  (when (not= old-text new-text)
                    [index
                     (if old-text (text-search-terms old-text) {})
                     (if new-text (text-search-terms new-text) {})]))))
        searches))

(defn- text-search-apply! [tx b searches id changes]
  (when (seq searches)
    (let [ordinal (membership-ensure-ordinal! tx b id)
          posting-key (fn [cfg term] (text-search-posting-key (text-search-term-key cfg term-kind-postings term) ordinal))]
      (doseq [[index old-terms new-terms] changes]
        (let [cfg (text-search-config b index)]
          (doseq [[term _] old-terms
                  :when (not (contains? new-terms term))]
            ((:delete tx) (posting-key cfg term))
            (text-search-change-count! tx cfg term -1))
          (doseq [[term ^bytes posting] new-terms
                  :when (not (Arrays/equals ^bytes (get old-terms term) posting))]
            ((:put tx) (posting-key cfg term) posting)
            (when-not (contains? old-terms term)
              (text-search-change-count! tx cfg term 1))))))))

(defn- text-search-has-position? [^bytes posting position]
  (loop [lo 1 hi (quot (alength posting) 4)]
    (if (>= lo hi)
      false
      (let [mid (quot (+ lo hi) 2)
            found (sort-data-int-at posting (* 4 mid))]
        (cond
          (= found position) true
          (< found position) (recur (inc mid) hi)
          :else (recur lo mid))))))

(defn- text-search-aligned? [^bytes pivot pivot-offset ^objects postings ^ints offsets query-length mode]
  (let [text-length (sort-data-int-at pivot 0)]
    (loop [at 4]
      (when (< at (alength pivot))
        (let [start (- (sort-data-int-at pivot at) pivot-offset)]
          (if (and (<= 0 start (- text-length query-length))
                   (case mode
                     :prefix (zero? start)
                     :suffix (= start (- text-length query-length))
                     true)
                   (loop [i 0]
                     (or (= i (alength postings))
                         (and (text-search-has-position? (aget postings i) (+ start (aget offsets i)))
                              (recur (inc i))))))
            true
            (recur (+ at 4))))))))

(defn- text-search-copy-ordinal! [^bytes source ^bytes target]
  (System/arraycopy source (- (alength source) 4) target (- (alength target) 4) 4))

(defn- text-search-read-postings! [tx ^bytes k v ^objects lookups ^objects postings]
  (loop [i 0]
    (if (= i (alength lookups))
      true
      (let [lookup (aget lookups i)
            raw (if lookup
                  (do (text-search-copy-ordinal! k lookup)
                      ((:get tx) lookup))
                  v)]
        (when raw
          (aset postings i raw)
          (recur (inc i)))))))

(defn- validate-text-search! [searches {:keys [index query] :as options} mode]
  (when-not (and (every? #{:index :query :mode} (keys options))
                 (contains? searches index)
                 (string? query)
                 (#{:contains :prefix :suffix} mode))
    (throw (ex-info "Invalid text search; supply a declared :index, string :query and optional :mode"
                    {:options options}))))

(defn- query-terms-with-offsets [mode ^String query ^ints points]
  (let [n (alength points)]
    (if (< n 3)
      [[[mode query] 0]]
      (mapv (fn [offset] [[:gram (String. points (int offset) 3)] offset])
            (distinct (concat (range 0 (- n 2) 3) [(- n 3)]))))))

(defn text-search-search-keys [tx b searches {:keys [index query mode] :or {mode :contains} :as options} observe]
  (validate-text-search! searches options mode)
  (let [query (text-search-normalize query)
        ^ints points (.toArray (.codePoints ^String query))
        n (alength points)]
    (when (< n (if (= mode :contains) 3 1))
      (throw (ex-info "Contains search needs three code points; boundary search needs a nonempty fragment"
                      {:type :grain.read-model/short-search :mode mode})))
    (let [cfg (text-search-config b index)
          candidates (mapv (fn [[term offset]]
                             {:term term
                              :offset offset
                              :prefix (text-search-term-key cfg term-kind-postings term)
                              :count (if-let [raw ((:get tx) (text-search-term-key cfg term-kind-count term))]
                                       (sort-data-int-at raw 0)
                                       0)})
                           (query-terms-with-offsets mode query points))
          pivot (apply min-key :count candidates)
          posting-lookups (object-array (map #(when (not= (:term %) (:term pivot))
                                                (text-search-posting-key (:prefix %) 0))
                                             candidates))
          offsets (int-array (map :offset candidates))
          postings (object-array (count candidates))
          id-lookup (namespace-slot b kind-id-by-ordinal (membership-number-bytes 0))]
      (if (zero? (:count pivot))
        []
        (persistent!
         (scan tx {:prefix (:prefix pivot)}
               (fn [ids {:keys [^bytes k ^bytes v]}]
                 (observe :search-candidate (alength v))
                 (if (and (text-search-read-postings! tx k v posting-lookups postings)
                          (text-search-aligned? v (:offset pivot) postings offsets n mode))
                   (do (observe :search-match 0)
                       (text-search-copy-ordinal! k id-lookup)
                       (conj! ids (codec/decode ((:get tx) id-lookup))))
                   ids))
               (transient [])))))))

(defn keyed? [definition]
  (or (contains? definition :key) (contains? definition :route)))

(def ^:private keyed-options
  #{:events :version :key :route :initial :result :include? :indexes
    :searches :filtered-indexes :state-schema :result-schema :start-after})

(def ^:private ordinary-options
  #{:events :version :schema :partition-fn :entity-id-fn :l1-ttl-ms :start-after})

(defn- check-supported-options! [name opts]
  (let [keyed? (boolean (keyed? opts))
        supported (if keyed? keyed-options ordinary-options)
        invalid (into #{} (remove supported) (keys opts))]
    (when (seq invalid)
      (let [unknown (into #{} (remove (into keyed-options ordinary-options)) invalid)
            inapplicable (into #{} (remove unknown) invalid)
            supported (vec (sort supported))
            hint (str (when (seq inapplicable)
                        (str (pr-str (vec (sort inapplicable)))
                             (if keyed?
                               " apply only to ordinary models read with project; remove them from this keyed model. "
                               " require a keyed model; remove them or supply exactly one of :key or :route. ")))
                      "Supported options: " (pr-str supported) ".")]
        (throw (ex-info (str "Read model " name " has unknown or inapplicable options "
                             (pr-str (vec (sort-by pr-str invalid))) ". " hint)
                        {:type :grain.read-model/invalid
                         :model name
                         :options invalid
                         :keyed? keyed?
                         :unknown-options unknown
                         :inapplicable-options inapplicable
                         :supported-options supported
                         :hint hint}))))))

(defn- valid-keyed-declaration? [name opts]
  (and (qualified-keyword? name)
       (pos-int? (:version opts 1))
       (set? (:events opts))
       (seq (:events opts))
       (every? qualified-keyword? (:events opts))
       (not= (contains? opts :key) (contains? opts :route))
       (ifn? (if (contains? opts :route) (:route opts) (:key opts)))
       (ifn? (:result opts identity))
       (ifn? (:include? opts (constantly true)))
       (map? (:indexes opts {}))
       (map? (:searches opts {}))
       (set? (:filtered-indexes opts #{}))
       (every? #(or (= :identity %) (contains? (:indexes opts) %)) (:filtered-indexes opts))
       (every? (fn [[k f]] (and (keyword? k) (ifn? f))) (:searches opts))
       (every? (fn [[k f]] (and (keyword? k) (not= :identity k) (ifn? f))) (:indexes opts))))

(defn keyed-validate! [name opts]
  (when-not (map? opts)
    (throw (ex-info "Read model options must be a map" {:model name})))
  (check-supported-options! name opts)
  (when (and (contains? opts :start-after)
             (not (and (uuid? (:start-after opts)) (= 7 (.version ^java.util.UUID (:start-after opts))))))
    (throw (ex-info (str "Read model " name " has an invalid :start-after " (pr-str (:start-after opts))
                         ". It must be an event id (UUID v7).")
                    {:type :grain.read-model/invalid
                     :model name
                     :options #{:start-after}
                     :start-after (:start-after opts)})))
  (when (keyed? opts)
    (when-not (valid-keyed-declaration? name opts)
      (throw (ex-info "Invalid keyed-fold declaration" {:model name})))
    (doseq [schema (keep opts [:state-schema :result-schema])]
      (m/schema schema))))

(defn- keyed-observe! [context operation size]
  (when-let [observer (:read-model/observer context)]
    (observer {:operation operation :bytes size})))

(defn- keyed-decode [ctx operation bytes]
  (when bytes
    (keyed-observe! ctx operation (alength ^bytes bytes))
    (codec/decode bytes)))

(defn- tag-selector? [tag]
  (and (vector? tag) (= 2 (count tag)) (keyword? (first tag)) (uuid? (second tag))))

(defn- event-scope-query? [query]
  (and (map? query)
       (every? #{:tags :types} (keys query))
       (or (not (contains? query :types))
           (and (set? (:types query)) (every? keyword? (:types query))))
       (or (not (contains? query :tags))
           (and (set? (:tags query)) (every? tag-selector? (:tags query))))))

(defn- valid-event-scope? [scope]
  (or (nil? scope)
      (and (map? scope)
           (or (and (= #{:tags} (set (keys scope)))
                    (event-scope-query? scope))
               (and (= #{:queries} (set (keys scope)))
                    (or (event-scope-query? (:queries scope))
                        (and (vector? (:queries scope))
                             (every? event-scope-query? (:queries scope)))))))))

(defn- keyed-validate-scope! [scope]
  (when-not (valid-event-scope? scope)
    (throw (ex-info "Keyed event scope requires {:tags #{[tag UUID] ...}} or {:queries [{:tags ... :types ...} ...]}"
                    {:type :grain.read-model/invalid-scope
                     :scope scope
                     :hint "Scope selects events before folding. Tenant, progress, ordering and limits belong to the read operation, not its event scope."})))
  scope)

(defn keyed-scoped-context [ctx scope]
  (keyed-validate-scope! scope)
  (if scope
    (assoc ctx :read-model/scope scope)
    (dissoc ctx :read-model/scope)))

(defn- keyed-scope-identity [scope]
  (when scope
    [:event-scope (.formatHex (HexFormat/of) (codec/encode (canonical-cache-value scope)))]))

(defn- keyed-namespace-identity
  ([ctx name d]
   (keyed-namespace-identity ctx name d (keyed-scope-identity (:read-model/scope ctx))))
  ([ctx name d scope-identity]
   (cond-> [(:tenant-id ctx) name (storage-version d)]
     scope-identity (conj scope-identity))))

(defn- scope-queries [scope]
  (cond
    (nil? scope) [{}]
    (contains? scope :tags) [scope]
    (map? (:queries scope)) [(:queries scope)]
    :else (:queries scope)))

(defn- keyed-event-query [ctx d after]
  (let [after (latest-id (:start-after d) after)
        store-queries (into []
                            (keep (fn [query]
                                    (let [types (if (contains? query :types)
                                                  (into #{} (filter (:events d)) (:types query))
                                                  (:events d))
                                          selects-nothing? (or (empty? types)
                                                               (and (contains? query :tags) (empty? (:tags query))))]
                                      (when-not selects-nothing?
                                        (cond-> (assoc query :tenant-id (:tenant-id ctx) :types types)
                                          after (assoc :after after))))))
                            (scope-queries (:read-model/scope ctx)))]
    (if (= 1 (count store-queries))
      (first store-queries)
      store-queries)))

(defn- keyed-address [b kind id]
  (key-checked (key-join b (byte-array [kind]) (key-encode id))))

(defn- keyed-progress-key [b]
  (key-join b (byte-array [kind-progress])))

(defn- keyed-count-key [b]
  (key-join b (byte-array [kind-included-count])))

(defn- keyed-included-key [b]
  (key-join b (byte-array [kind-included-members])))

(defn- keyed-kind-prefix [b kind]
  (key-join b (byte-array [kind])))

(defn- keyed-index-ordinal ^long [d index]
  (if (= :identity index)
    0
    (let [indexes (:indexes d)]
      (when-not (contains? indexes index)
        (throw (ex-info "Unknown keyed index" {:index index})))
      (inc (long (reduce-kv (fn [n k _] (if (neg? (compare k index)) (inc (long n)) n)) 0 indexes))))))

(def ^:private ^:const one-byte-ordinal-limit 255)

(defn- keyed-ordinal-code ^bytes [^long ordinal]
  (if (< ordinal one-byte-ordinal-limit)
    (byte-array [(unchecked-byte ordinal)])
    (let [excess (- ordinal one-byte-ordinal-limit)]
      (when (> excess 0xFFFF)
        (throw (ex-info "Too many keyed indexes" {:ordinal ordinal})))
      (byte-array [-1 (unchecked-byte (bit-shift-right excess 8)) (unchecked-byte excess)]))))

(def ^:private ^:const bounded-cache-max-entries 4096)

(def ^:private ^java.util.concurrent.ConcurrentHashMap ordinal-codes-by-indexes
  (java.util.concurrent.ConcurrentHashMap.))

(defn- ordinal-codes ^java.util.Map [d indexes]
  (let [codes (HashMap.)]
    (.put codes :identity (keyed-ordinal-code 0))
    (doseq [index (keys indexes)]
      (.put codes index (keyed-ordinal-code (keyed-index-ordinal d index))))
    codes))

(defn- keyed-index-ordinal-bytes ^bytes [d index]
  (let [indexes (:indexes d {})
        ^java.util.Map codes (or (.get ordinal-codes-by-indexes indexes)
                                 (let [codes (ordinal-codes d indexes)]
                                   (when (> (.size ordinal-codes-by-indexes) bounded-cache-max-entries)
                                     (.clear ordinal-codes-by-indexes))
                                   (.put ordinal-codes-by-indexes indexes codes)
                                   codes))]
    (or (.get codes index)
        (throw (ex-info "Unknown keyed index" {:index index})))))

(defn- keyed-index-config [b d index direction]
  (assoc (ranked-config (key-join b
                                  (byte-array [kind-index])
                                  (keyed-index-ordinal-bytes d index)
                                  (byte-array [(if (= direction :desc) 1 0)])))
         :hash-offset (alength ^bytes b)
         :keyed? true))

(defn- keyed-index-prefix [b d index direction]
  (ranked-prefix (keyed-index-config b d index direction) 0))

(defn- keyed-membership-config [b d index direction]
  (assoc (keyed-index-config b d index direction)
         :membership? (contains? (:filtered-indexes d) index)))

(def ^:private ^:const group-count-level 6)
(def ^:private keyed-min-levels 2)
(def ^:private keyed-max-levels 6)

(defn- keyed-target-levels [n]
  (loop [levels keyed-min-levels capacity 256]
    (if (or (>= levels keyed-max-levels) (<= n capacity))
      levels
      (recur (inc levels) (* 16 capacity)))))

(defn- keyed-levels-key [b d index]
  (key-checked (key-join b (byte-array [kind-index-levels]) (keyed-index-ordinal-bytes d index))))

(defn- keyed-stored-levels [tx b d index]
  (when-let [^bytes raw ((:get tx) (keyed-levels-key b d index))]
    (let [levels (when (= 1 (alength raw)) (long (aget raw 0)))]
      (when-not (and levels (<= keyed-min-levels levels keyed-max-levels))
        (throw (ex-info "Invalid stored rank levels" {:type :grain.read-model/invalid-levels :index index})))
      levels)))

(defn- keyed-write-levels! [tx b d index levels]
  ((:put tx) (keyed-levels-key b d index) (byte-array [(byte levels)])))

(defn- keyed-read-config [tx b d cfg index]
  (assoc cfg :levels (or (keyed-stored-levels tx b d index) keyed-min-levels)))

(def ^:private ^:const level-prefix-slots 24)
(def ^:private ^:const session-slot-ascending 0)
(def ^:private ^:const session-slot-descending 1)
(def ^:private ^:const session-slot-stored-levels 2)

(defn- keyed-write-session [tx b]
  (assoc tx ::keyed-session-base b ::keyed-session-configs (HashMap.)))

(defn- session-index-slots ^objects [^HashMap configs index]
  (or (.get configs index)
      (let [slots (object-array 3)]
        (.put configs index slots)
        slots)))

(defn- keyed-session-config [tx b d index direction]
  (let [^HashMap configs (::keyed-session-configs tx)]
    (if (and configs (identical? b (::keyed-session-base tx)))
      (let [slots (session-index-slots configs index)
            slot (if (= direction :desc) session-slot-descending session-slot-ascending)
            stored-levels (or (aget slots session-slot-stored-levels)
                              (aset slots session-slot-stored-levels (volatile! (keyed-stored-levels tx b d index))))]
        (or (aget slots slot)
            (let [cfg (assoc (keyed-membership-config b d index direction)
                             :levels (or @stored-levels keyed-min-levels)
                             ::levels stored-levels
                             ::levels-key (keyed-levels-key b d index)
                             ::level-prefixes (object-array level-prefix-slots)
                             ::initialized (volatile! false))]
              (aset slots slot cfg)
              cfg)))
      (let [stored (keyed-stored-levels tx b d index)]
        (assoc (keyed-membership-config b d index direction)
               :levels (or stored keyed-min-levels)
               ::levels (volatile! stored)
               ::levels-key (keyed-levels-key b d index))))))

(defn- initialize-index! [tx cfg]
  (when (nil? (ranked-count-key tx cfg 0 ranked-empty-key))
    (ranked-init! tx cfg)
    (when-let [levels-key (::levels-key cfg)]
      ((:put tx) levels-key (byte-array [(byte (:levels cfg))]))
      (vreset! (::levels cfg) (:levels cfg)))))

(defn- keyed-ensure-init! [tx cfg]
  (if-let [initialized (::initialized cfg)]
    (when-not @initialized
      (initialize-index! tx cfg)
      (vreset! initialized true))
    (initialize-index! tx cfg)))

(defn- index-names [d]
  (conj (vec (keys (:indexes d))) :identity))

(defn- keyed-relevel! [tx b d n]
  (let [target (keyed-target-levels n)]
    (doseq [index (index-names d)]
      (let [ascending (keyed-session-config tx b d index :asc)
            stored @(::levels ascending)]
        (when (and stored (not= stored target))
          (ranked-relevel! tx ascending target)
          (keyed-write-levels! tx b d index target)
          (some-> ^HashMap (::keyed-session-configs tx) (.remove index)))))))

(def ^:private empty-payload (byte-array 0))

(defn- keyed-id-exact? [id]
  (cond
    (or (uuid? id) (string? id) (boolean? id) (nil? id)) true
    (or (instance? Long id) (instance? Integer id) (instance? Short id)) true
    (keyword? id) (identical? id (keyword (subs (str id) 1)))
    (vector? id) (every? keyed-id-exact? id)
    :else false))

(defn- keyed-id-payload ^bytes [id]
  (if (keyed-id-exact? id)
    empty-payload
    (codec/encode id)))

(definterface IKeyedOwner
  (id [])
  (^bytes idKey [])
  (^bytes memberSuffix [])
  (^bytes payload []))

(deftype KeyedOwner [owner-id
                     ^:unsynchronized-mutable ^bytes id-key
                     ^:unsynchronized-mutable ^bytes suffix
                     ^:unsynchronized-mutable ^bytes encoded]
  IKeyedOwner
  (id [_] owner-id)
  (idKey [_]
    (when (nil? id-key) (set! id-key (key-encode owner-id)))
    id-key)
  (memberSuffix [_]
    (when (nil? suffix) (set! suffix (key-id-suffix owner-id)))
    suffix)
  (payload [_]
    (when (nil? encoded) (set! encoded (keyed-id-payload owner-id)))
    encoded))

(defn- keyed-owner ^KeyedOwner [id]
  (KeyedOwner. id nil nil nil))

(defn- keyed-owner-address [b kind ^KeyedOwner owner]
  (key-checked (key-join b (byte-array [kind]) (.idKey owner))))

(defn- keyed-sort-bytes [value direction]
  (assert (= :asc direction))
  (key-encode value))

(defn- keyed-compact-identity? [d index]
  (and (= :identity index) (not (contains? (:filtered-indexes d) :identity))))

(defn- keyed-owner-member [d index direction value ^KeyedOwner owner]
  (if (keyed-compact-identity? d index)
    (key-join (keyed-sort-bytes value direction) (key-id-tail (.id owner)))
    (key-join (keyed-sort-bytes value direction) (.memberSuffix owner))))

(defn- keyed-index-member [d index direction value id]
  (keyed-owner-member d index direction value (keyed-owner id)))

(defn- invalid-index-member! [message]
  (throw (ex-info message {:type :grain.read-model/invalid-cache})))

(defn- keyed-member-id [d index direction ^bytes k offset]
  (let [mask (if (= direction :desc) 255 0)]
    (try
      (let [compact-identity? (keyed-compact-identity? d index)
            id-start (if compact-identity? (long offset) (skip-member-key k offset mask))
            pos (doto (long-array 2) (aset 0 id-start) (aset 1 (alength k)))
            id (member-read-id! k pos (if compact-identity? mask 0))]
        (when-not (= (aget pos 0) (alength k))
          (invalid-index-member! "Trailing bytes in index member"))
        id)
      (catch EOFException _
        (invalid-index-member! "Truncated index member"))
      (catch IndexOutOfBoundsException _
        (invalid-index-member! "Truncated index member")))))

(defn- keyed-entry-id [d index direction ^bytes k offset ^bytes v]
  (if (pos? (alength v))
    (codec/decode v)
    (keyed-member-id d index direction k offset)))

(defn- keyed-fits-prefix?
  ([^bytes prefix]
   (< (alength prefix) max-key-bytes))
  ([cfg ^bytes prefix]
   (< (+ (alength ^bytes (ranked-prefix cfg 0)) (alength prefix)) max-key-bytes)))

(defn- member-value-length [^bytes member]
  (key-skip member 0))

(defn- group-record-after [record value delta ^long total]
  (when-not (zero? total)
    {:value (if (and record (neg? (long delta))) (:value record) value)
     :total total}))

(defn- check-group-total! [^long total index value]
  (when (neg? total)
    (throw (ex-info "Negative index group count" {:index index :value value}))))

(defn- keyed-update-group-at! [tx ^bytes group-prefix index value delta]
  (let [k (key-checked (key-join group-prefix (key-encode value)))]
    (if-let [^HashMap group-counts (::group-counts tx)]
      (let [address (ByteBuffer/wrap k)
            ^objects record-and-stored (or (.get group-counts address)
                                           (let [raw ((:get tx) k)
                                                 entry (object-array [(some-> raw codec/decode) (some? raw)])]
                                             (.put group-counts address entry)
                                             entry))
            record (aget record-and-stored 0)
            total (+ (long (or (:total record) 0)) (long delta))]
        (check-group-total! total index value)
        (aset record-and-stored 0 (group-record-after record value delta total)))
      (let [record (some-> ((:get tx) k) codec/decode)
            total (+ (or (:total record) 0) delta)]
        (check-group-total! total index value)
        (if-let [updated (group-record-after record value delta total)]
          ((:put tx) k (codec/encode updated))
          ((:delete tx) k))))))

(defn- group-count-flush! [tx]
  (when-let [^HashMap group-counts (::group-counts tx)]
    (doseq [^java.util.Map$Entry entry (count-cache-entries group-counts)
            :let [^objects record-and-stored (.getValue entry)
                  k (.array ^ByteBuffer (.getKey entry))
                  stored-before? (aget record-and-stored 1)]]
      (if-let [record (aget record-and-stored 0)]
        ((:put tx) k (codec/encode record))
        (when stored-before? ((:delete tx) k))))
    (.clear group-counts)))

(def ^:private count-cache-flush-entries 65536)

(defn- count-caches-bound! [tx]
  (when-let [^HashMap span-counts (::span-counts tx)]
    (when (> (+ (.size span-counts)
                (.size ^HashMap (::group-counts tx))
                (.size ^HashMap (::term-counts tx)))
             (long count-cache-flush-entries))
      (span-count-flush! tx)
      (group-count-flush! tx)
      (term-count-flush! tx))))

(defn- keyed-update-group! [tx b d index value delta]
  (keyed-update-group-at! tx (ranked-prefix (keyed-session-config tx b d index :asc) group-count-level) index value delta))

(defn- keyed-grouped? [index direction]
  (and (= direction :asc) (not= :identity index)))

(defn- keyed-remove-index! [tx b d index direction position ^KeyedOwner owner]
  (when (ranked-remove! tx (keyed-session-config tx b d index direction)
                        (keyed-owner-member d index direction position owner))
    (when (keyed-grouped? index direction)
      (keyed-update-group! tx b d index position -1))))

(defn- keyed-add-index! [ctx tx b d index direction position ^KeyedOwner owner]
  (let [cfg (keyed-session-config tx b d index direction)
        member (keyed-owner-member d index direction position owner)
        payload (.payload owner)]
    (keyed-ensure-init! tx cfg)
    (when (ranked-add! tx cfg member payload
                       (when (:membership? cfg) (membership-ensure-ordinal! tx b (.id owner))))
      (when (keyed-grouped? index direction)
        (keyed-update-group! tx b d index position 1)))
    (keyed-observe! ctx :index-written (alength ^bytes payload))))

(defn- keyed-empty-ranks? [tx cfg]
  (let [zero-count (ranked-encode-count 0)
        only-empty-sentinels? (fn [level membership?]
                                (scan tx {:prefix (ranked-prefix cfg level)}
                                      (fn [_ {:keys [k v]}]
                                        (if (and (= (alength ^bytes k) (alength ^bytes (ranked-prefix cfg level)))
                                                 (if membership?
                                                   (try (.isEmpty (membership-decode v))
                                                        (catch Exception _ false))
                                                   (Arrays/equals ^bytes zero-count ^bytes v)))
                                          true
                                          (reduced false)))
                                      true))]
    (and (every? #(only-empty-sentinels? % false) (range (:levels cfg)))
         (or (not (:membership? cfg))
             (every? #(only-empty-sentinels? (+ membership-level-offset %) true) (range (:levels cfg)))))))

(def ^:private ^:const cold-build-min-owners 32)

(defn- keyed-cold-index-builders [ctx tx b d before-progress states]
  (when (and (nil? (:watermark before-progress))
             (nil? (:read-model/observer ctx))
             (<= cold-build-min-owners (count states))
             (prefix-empty? tx (keyed-kind-prefix b kind-result)))
    (let [builders (HashMap.)]
      (doseq [index (index-names d)
              :let [groups-empty? (prefix-empty? tx (ranked-prefix (keyed-session-config tx b d index :asc) group-count-level))]
              direction [:asc]
              :let [cfg (keyed-session-config tx b d index direction)]
              :when (and groups-empty? (keyed-empty-ranks? tx cfg))]
        (.put builders [index direction] {:cfg cfg :entries (ArrayList.) :initialized? (volatile! false)}))
      builders)))

(defn- keyed-enqueue-index! [ctx tx b d builders index direction position ^KeyedOwner owner]
  (if-let [{:keys [cfg entries initialized?]} (when builders (.get ^HashMap builders [index direction]))]
    (let [member (keyed-owner-member d index direction position owner)
          payload (.payload owner)]
      (when-not @initialized?
        (keyed-ensure-init! tx cfg)
        (vreset! initialized? true))
      (let [ordinal (when (:membership? cfg) (membership-ensure-ordinal! tx b (.id owner)))
            _ (ranked-check-key! member)
            _ (check-membership-ordinal! cfg ordinal)
            hash (ranked-hash tx cfg member)]
        (ranked-pack cfg 0 member)
        (.add ^ArrayList entries [member payload ordinal hash])
        (when (keyed-grouped? index direction)
          (keyed-update-group! tx b d index position 1))))
    (keyed-add-index! ctx tx b d index direction position owner)))

(defn- keyed-build-cold-indexes! [tx builders]
  (when builders
    (doseq [{:keys [cfg entries]} (.values ^HashMap builders)]
      (.sort ^ArrayList entries
             (reify Comparator
               (compare [_ a b] (Arrays/compareUnsigned ^bytes (nth a 0) ^bytes (nth b 0)))))
      (let [last-span-keys (object-array (repeat (:levels cfg) ranked-empty-key))]
        (doseq [[member payload ordinal hash] entries]
          (ranked-append! tx cfg member payload ordinal hash last-span-keys))))))

(defn- keyed-index-fields [d]
  (into [] (map-indexed (fn [i index] [index (+ sort-data-first-index-field i)])) (sort (keys (:indexes d)))))

(defn- keyed-index-layout-value [d]
  [(vec (sort (keys (:indexes d))))
   (vec (sort (:filtered-indexes d)))
   (vec (sort (keys (:searches d))))])

(def ^:private ^java.util.concurrent.ConcurrentHashMap index-layouts-by-definition
  (java.util.concurrent.ConcurrentHashMap.))

(defn- keyed-index-layout ^bytes [d]
  (or (.get index-layouts-by-definition d)
      (let [fingerprint (key-encode-compact (keyed-index-layout-value d))]
        (when (> (.size index-layouts-by-definition) bounded-cache-max-entries)
          (.clear index-layouts-by-definition))
        (.put index-layouts-by-definition d fingerprint)
        fingerprint)))

(defn- keyed-check-index-layout! [progress d]
  (when progress
    (let [stored (:index-layout progress)]
      (when-not (and (bytes? stored) (Arrays/equals ^bytes stored (keyed-index-layout d)))
        (let [[indexes filtered searches] (keyed-index-layout-value d)
              [stored-indexes stored-filtered stored-searches :as decoded]
              (when (bytes? stored)
                (try (key-decode-compact stored) (catch Exception _ nil)))]
          (throw (ex-info "Keyed model indexes, filtered indexes or searches differ from the ones this cache was built with; the definition changed without a version bump"
                          {:type :grain.read-model/invalid-cache
                           :stored-indexes (if (vector? decoded) stored-indexes stored)
                           :indexes indexes
                           :stored-filtered-indexes stored-filtered
                           :filtered-indexes filtered
                           :stored-searches stored-searches
                           :searches searches})))))))

(defn- keyed-public-progress [progress]
  (some-> progress (dissoc :index-layout)))

(defn- keyed-sort-data! ^bytes [tx ^bytes sort-k id ^long field-count]
  (let [^bytes data (or ((:get tx) sort-k)
                        (throw (ex-info "Keyed sort metadata is missing for a stored result"
                                        {:type :grain.read-model/invalid-cache :id id})))
        stored-field-count (when (>= (alength data) 4)
                             (dec (quot (sort-data-int-at data 0) 4)))]
    (when-not (= stored-field-count field-count)
      (throw (ex-info "Keyed sort metadata does not match the model's indexes; index definitions changed without a version bump"
                      {:type :grain.read-model/invalid-cache
                       :id id
                       :stored-fields stored-field-count
                       :expected-fields field-count})))
    data))

(defn- sort-data-field-count [index-fields]
  (+ sort-data-first-index-field (count index-fields)))

(defn- keyed-old-position [_id _index ^bytes old-data field]
  (sort-data-value old-data field))

(defn- keyed-read-meta [tx k]
  (some-> ((:get tx) k) codec/decode))

(defn- keyed-write! [tx k v]
  ((:put tx) k (codec/encode v)))

(defn- keyed-put-observed! [ctx tx operation k ^bytes bytes]
  ((:put tx) k bytes)
  (keyed-observe! ctx operation (alength bytes)))

(defn- keyed-prepare-validation [schema name kind]
  (if schema
    (let [schema (m/schema schema)
          valid? (m/validator schema)]
      (fn [value]
        (when-not (valid? value)
          (throw (ex-info "Keyed-fold schema validation failed"
                          {:model name :kind kind :explanation (m/explain schema value)})))))
    (constantly nil)))

(def ^:private deferred-marker-prefix (byte-array (map int "KB9")))
(def ^:private ^:const marker-kind-build 1)
(def ^:private ^:const marker-kind-failed 2)
(def ^:private ^:const marker-kind-replaced 3)
(def ^:private deferred-default-threshold 1024)
(def ^:private deferred-default-entries 1000000)
(def ^:private deferred-default-lease-ms 30000)
(def ^:private deferred-default-max-stall-ms 60000)
(def ^:private deferred-default-max-stall-cap-ms (* 10 60 1000))
(def ^:private deferred-replaced-gc-ms (* 60 60 1000))
(def ^:private deferred-chunk 4096)
(def ^:private ^:const max-takeover-generation 30)

(defn- namespace-prefix-of [tx identity]
  (with-namespace-space identity #(get (namespace-resolve-known tx [identity]) identity)))

(defn- namespace-ordinal-of [tx identity]
  (let [next-id (namespace-counter tx)]
    (when (and (some? next-id) (namespace-lookup tx identity next-id))
      (:ordinal (:value (namespace-read-value tx (namespace-forward-key identity)))))))

(defn- namespace-swap-checked! [tx a b]
  (let [ordinal-a (namespace-ordinal-of tx a)
        ordinal-b (namespace-ordinal-of tx b)]
    (when-not (and (some? ordinal-a) (some? ordinal-b) (not= ordinal-a ordinal-b))
      (namespace-failure "Cannot exchange unregistered namespaces" {}))
    (let [record-a (codec/encode {:identity a :ordinal ordinal-b})
          record-b (codec/encode {:identity b :ordinal ordinal-a})]
      ((:put tx) (namespace-forward-key a) record-a)
      ((:put tx) (namespace-reverse-key ordinal-b) record-a)
      ((:put tx) (namespace-forward-key b) record-b)
      ((:put tx) (namespace-reverse-key ordinal-a) record-b)
      (namespace-address ordinal-b))))

(defn- namespace-swap! [tx a b]
  (with-namespace-space a
    #(with-validated-registry tx (fn [] (namespace-swap-checked! tx a b)))))

(defn- deferred-staging-identity [identity]
  (assoc identity
         1 (keyword "grain.read-model.staging" (str "s" (.formatHex (HexFormat/of) (namespace-digest identity))))
         2 1))

(defn- deferred-slot [identity kind]
  (key-join deferred-marker-prefix (namespace-digest identity) (byte-array [kind])))

(defn- deferred-start-ms [^java.lang.ProcessHandle h]
  (some-> ^java.util.Optional (.startInstant (.info h))
          (.orElse nil)
          (#(.toEpochMilli ^java.time.Instant %))))

(def ^:private deferred-self
  (delay (let [h (java.lang.ProcessHandle/current)]
           {:pid (.pid h) :started (deferred-start-ms h)})))

(def ^:private deferred-owners* (atom {}))

(defn- deferred-owner-key [ctx identity]
  (let [cache (:cache ctx)]
    [(or (:env cache) cache) identity]))

(defn- deferred-own! [ctx identity build]
  (let [k (deferred-owner-key ctx identity)
        [_ owners] (swap-vals! deferred-owners* #(if (contains? % k) % (assoc % k build)))]
    (= build (get owners k))))

(defn- deferred-disown! [ctx identity build]
  (let [k (deferred-owner-key ctx identity)]
    (swap! deferred-owners* #(if (= build (get % k)) (dissoc % k) %))
    nil))

(defn- deferred-owned?
  ([ctx identity]
   (contains? @deferred-owners* (deferred-owner-key ctx identity)))
  ([ctx identity build]
   (boolean (and build (= build (get @deferred-owners* (deferred-owner-key ctx identity)))))))

(defn- same-running-process? [pid started]
  (let [^java.util.Optional handle (java.lang.ProcessHandle/of (long pid))]
    (and (.isPresent handle)
         (let [^java.lang.ProcessHandle process (.get handle)]
           (and (.isAlive process) (= started (deferred-start-ms process)))))))

(defn- deferred-live? [ctx identity {:keys [pid started at build]} lease-ms]
  (boolean
   (if (and pid (= pid (:pid @deferred-self)))
     (deferred-owned? ctx identity build)
     (and pid
          at
          (< (- (System/currentTimeMillis) at) lease-ms)
          (same-running-process? pid started)))))

(defn- deferred-marker [tx identity]
  (some-> ((:get tx) (deferred-slot identity marker-kind-build)) codec/decode))

(defn- staging-marker [tx staging]
  (some-> ((:get tx) (:marker staging)) codec/decode))

(defn- heartbeat [marker]
  (codec/encode (assoc marker :at (System/currentTimeMillis))))

(defn- deferred-guard! [tx staging]
  (let [marker (staging-marker tx staging)]
    (when-not (= (:build staging) (:build marker))
      (throw (ex-info "Deferred cold build was superseded" {:type ::deferred-superseded})))
    ((:put tx) (:marker staging) (heartbeat marker))))

(defn- deferred-stamp!
  ([tx staging] (deferred-stamp! tx staging false))
  ([tx staging folded?]
   (when-let [marker (staging-marker tx staging)]
     (when (= (:build staging) (:build marker))
       ((:put tx) (:marker staging)
                  (codec/encode (cond-> (assoc marker :at (System/currentTimeMillis))
                                  folded? (assoc :progressed true :generation 0))))))))

(defn- deferred-max-stall-ms [ctx generation]
  (let [base (long (:read-model/deferred-build-max-stall-ms ctx deferred-default-max-stall-ms))
        cap (long (:read-model/deferred-build-max-stall-cap-ms ctx deferred-default-max-stall-cap-ms))]
    (max base (min cap (* base (bit-shift-left 1 (min max-takeover-generation (long generation))))))))

(defn- deferred-progress! [staging]
  (when-let [^java.util.concurrent.atomic.AtomicLong progress (:progress staging)]
    (.set progress (System/currentTimeMillis))))

(defn- refresh-owned-heartbeat! [ctx staging running]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [marker (staging-marker tx staging)]
        (when (and @running (= (:build staging) (:build marker)) (:pid marker))
          ((:put tx) (:marker staging) (heartbeat marker))
          true)))))

(defn- deferred-heartbeat! [ctx staging lease-ms]
  (let [running (volatile! true)
        ^java.util.concurrent.atomic.AtomicLong progress (:progress staging)
        ^java.util.concurrent.atomic.AtomicLong generation (:generation staging)
        progressed-recently? #(or (nil? progress)
                                  (< (- (System/currentTimeMillis) (.get progress))
                                     (deferred-max-stall-ms ctx (if generation (.get generation) 0))))
        interval (max 5 (quot (long lease-ms) 3))
        beat-until-stopped (fn []
                             (try
                               (loop []
                                 (Thread/sleep interval)
                                 (when (and @running
                                            (or (not (progressed-recently?))
                                                (refresh-owned-heartbeat! ctx staging running)))
                                   (recur)))
                               (catch InterruptedException _ nil)
                               (catch Throwable _ nil)))
        thread (doto (Thread. ^Runnable beat-until-stopped "grain-deferred-build-heartbeat")
                 (.setDaemon true)
                 (.start))]
    (fn []
      (vreset! running false)
      (.interrupt thread)
      (.join thread 1000))))

(defn- read-rows ^ArrayList [tx ^bytes prefix from limit]
  (scan tx (cond-> {:prefix prefix} from (assoc :from from))
        (fn [^ArrayList rows row]
          (.add rows row)
          (if (= limit (.size rows)) (reduced rows) rows))
        (ArrayList.)))

(defn- key-after-last-row [^ArrayList rows]
  (ranked-after (:k (.get rows (dec (.size rows))))))

(defn- keyed-wipe! [tx ^bytes prefix]
  (loop []
    (let [^ArrayList ks (scan tx {:prefix prefix}
                              (fn [^ArrayList ks {:keys [k]}]
                                (.add ks k)
                                (if (= deferred-chunk (.size ks)) (reduced ks) ks))
                              (ArrayList.))]
      (when-not (.isEmpty ks)
        (doseq [k ks]
          ((:delete tx) k))
        (recur)))))

(defn- deferred-lease-ms [ctx]
  (:read-model/deferred-build-lease-ms ctx deferred-default-lease-ms))

(defn- still-cold-namespace? [tx identity b]
  (let [current (namespace-prefix-of tx identity)]
    (and current
         (Arrays/equals ^bytes current ^bytes b)
         (nil? (:watermark (keyed-read-meta tx (keyed-progress-key b)))))))

(defn- next-takeover-generation [marker]
  (if (and marker (:pid marker) (not (:progressed marker)))
    (min max-takeover-generation (inc (long (:generation marker 0))))
    0))

(defn- claim-staging! [tx identity staging build marker]
  (let [prefix (get (with-namespace-space staging #(namespace-allocate! tx [staging])) staging)
        generation (next-takeover-generation marker)]
    (when-not marker
      (keyed-wipe! tx prefix))
    ((:put tx) (deferred-slot identity marker-kind-build)
               (codec/encode (merge {:identity identity
                                     :staging staging
                                     :build build
                                     :at (System/currentTimeMillis)
                                     :generation generation
                                     :progressed false}
                                    @deferred-self)))
    {:identity staging
     :prefix prefix
     :marker (deferred-slot identity marker-kind-build)
     :build build
     :resumed? (some? marker)
     :progress (java.util.concurrent.atomic.AtomicLong. (System/currentTimeMillis))
     :generation (java.util.concurrent.atomic.AtomicLong. generation)}))

(defn- deferred-claim! [ctx identity b]
  (let [staging (deferred-staging-identity identity)
        build (str (random-uuid))]
    (if-not (deferred-own! ctx identity build)
      :wait
      (let [claimed (try
                      (kv/transact (:cache ctx) :write
                        (fn [tx]
                          (let [marker (deferred-marker tx identity)]
                            (cond
                              (not (still-cold-namespace? tx identity b)) :restart
                              (and marker (deferred-live? ctx identity marker (deferred-lease-ms ctx))) :wait
                              ((:get tx) (deferred-slot identity marker-kind-failed)) nil
                              :else (claim-staging! tx identity staging build marker)))))
                      (catch Throwable t
                        (deferred-disown! ctx identity build)
                        (throw t)))]
        (when-not (map? claimed)
          (deferred-disown! ctx identity build))
        claimed))))

(defn- deferred-release! [ctx staging]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [marker (staging-marker tx staging)]
        (when (= (:build marker) (:build staging))
          ((:put tx) (:marker staging) (codec/encode (assoc marker :pid nil :at nil))))))))

(defn- deferred-abandon! [ctx identity staging failed?]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (when (and (= (:build staging) (:build (deferred-marker tx identity)))
                 (Arrays/equals ^bytes (namespace-prefix-of tx (:identity staging)) ^bytes (:prefix staging)))
        (keyed-wipe! tx (:prefix staging))
        ((:delete tx) (:marker staging))
        (when failed?
          ((:put tx) (deferred-slot identity marker-kind-failed) (codec/encode {:identity identity})))))))

(defn- deferred-await! [ctx identity]
  (telemetry/owned! "ReadModelWaitDuration" nil
    (assoc (telemetry/dimensions ctx (::metric-model ctx) "keyed") :read-model-wait-kind "cold-build")
    (fn []
    (loop []
    (when (or (deferred-owned? ctx identity)
              (kv/transact (:cache ctx) :read
                #(when-let [marker (deferred-marker % identity)]
                   (deferred-live? ctx identity marker (deferred-lease-ms ctx)))))
      (Thread/sleep 50)
      (recur))))))

(defn- deferred-fingerprint [tx ^bytes prefix]
  [(some-> ((:get tx) (keyed-progress-key prefix)) (#(.formatHex (HexFormat/of) ^bytes %)))
   (not (prefix-empty? tx prefix))])

(defn- stale-build-cleanup-plan [ctx identity now tx]
  (let [marker (deferred-marker tx identity)
        live? (and marker (deferred-live? ctx identity marker (deferred-lease-ms ctx)))
        replaced (some-> ((:get tx) (deferred-slot identity marker-kind-replaced)) codec/decode)
        staging-prefix (namespace-prefix-of tx (deferred-staging-identity identity))
        fingerprint (when (and replaced staging-prefix (not live?))
                      (deferred-fingerprint tx staging-prefix))
        observed? (and replaced (contains? replaced :observed-at))
        unchanged? (and observed? (= fingerprint (:fingerprint replaced)))]
    {:staging-prefix staging-prefix
     :stale-marker? (and marker (not live?))
     :failure? (some? ((:get tx) (deferred-slot identity marker-kind-failed)))
     :collect? (and fingerprint
                    unchanged?
                    (>= (- now (:observed-at replaced)) deferred-replaced-gc-ms))
     :observe (when (and fingerprint (not unchanged?))
                (assoc replaced :fingerprint fingerprint :observed-at now))}))

(defn- deferred-clean-stale! [ctx identity]
  (let [cache (:cache ctx)
        marker-scope (key-join deferred-marker-prefix (namespace-digest identity))
        plan (partial stale-build-cleanup-plan ctx identity (System/currentTimeMillis))]
    (when-not (kv/transact cache :read #(prefix-empty? % marker-scope))
      (let [{:keys [stale-marker? failure? collect? observe]} (kv/transact cache :read plan)]
        (when (or stale-marker? failure? collect? observe)
          (kv/transact cache :write
            (fn [tx]
              (let [{:keys [staging-prefix stale-marker? failure? collect? observe]} (plan tx)]
                (when (and staging-prefix (or stale-marker? collect?))
                  (keyed-wipe! tx staging-prefix))
                (when stale-marker?
                  ((:delete tx) (deferred-slot identity marker-kind-build)))
                (when failure?
                  ((:delete tx) (deferred-slot identity marker-kind-failed)))
                (cond
                  collect? ((:delete tx) (deferred-slot identity marker-kind-replaced))
                  observe ((:put tx) (deferred-slot identity marker-kind-replaced) (codec/encode observe)))))))))))

(defn- deferred-scan! [tx ^bytes prefix f]
  (loop [from nil]
    (let [rows (read-rows tx prefix from deferred-chunk)]
      (doseq [row rows]
        (f row))
      (when (= deferred-chunk (.size rows))
        (recur (key-after-last-row rows))))))

(defn- deferred-read [ctx staging f]
  (try
    (kv/transact (:cache ctx) :read f)
    (catch Throwable t
      (kv/transact (:cache ctx) :write #(deferred-guard! % staging))
      (throw t))))

(defn- guarded-staging-write! [ctx staging write!]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (deferred-guard! tx staging)
      (write! tx))))

(defn- deferred-chunks! [ctx staging ^bytes prefix size prepare write]
  (loop [from nil]
    (let [[prepared next-from]
          (deferred-read ctx staging
            (fn [tx]
              (let [rows (read-rows tx prefix from size)]
                [(prepare tx rows)
                 (when (= size (.size rows)) (key-after-last-row rows))])))]
      (guarded-staging-write! ctx staging
        (fn [tx]
          (write (with-promotion-key ctx tx) prepared)
          (deferred-stamp! tx staging)))
      (when next-from
        (deferred-progress! staging)
        (recur next-from)))))

(def ^:private deferred-member-order
  (reify Comparator
    (compare [_ a b]
      (Arrays/compareUnsigned ^bytes (aget ^objects a 0) ^bytes (aget ^objects b 0)))))

(def ^:private ^:const entry-member 0)
(def ^:private ^:const entry-payload 1)
(def ^:private ^:const entry-ordinal 2)
(def ^:private ^:const entry-position 3)
(def ^:private ^:const entry-group-key 4)

(defn- deferred-entry [d id indexes ordinal payload [index direction]]
  (let [position (get indexes index)]
    (object-array [(keyed-index-member d index direction position id)
                   payload
                   ordinal
                   position
                   (when (keyed-grouped? index direction) (key-encode position))])))

(defn- deferred-sort! [index ^ArrayList entries]
  (.sort entries deferred-member-order)
  (dotimes [i (dec (.size entries))]
    (when (zero? (Arrays/compareUnsigned ^bytes (aget ^objects (.get entries i) entry-member)
                                         ^bytes (aget ^objects (.get entries (inc i)) entry-member)))
      (throw (ex-info "Duplicate deferred index member" {:index index})))))

(defn- deferred-write-groups! [tx b d index ^ArrayList entries merge?]
  (let [group-prefix (ranked-prefix (keyed-index-config b d index :asc) group-count-level)
        n (.size entries)
        group-key-at (fn [i] (aget ^objects (.get entries i) entry-group-key))]
    (loop [i 0]
      (when (< i n)
        (let [^objects first-entry (.get entries i)
              ^bytes group-key (aget first-entry entry-group-key)
              group-end (loop [j (inc i)]
                          (if (and (< j n) (Arrays/equals group-key ^bytes (group-key-at j)))
                            (recur (inc j))
                            j))
              k (key-checked (key-join group-prefix group-key))
              stored-total (if merge? (or (some-> ((:get tx) k) codec/decode :total) 0) 0)]
          ((:put tx) k (codec/encode {:value (aget first-entry entry-position)
                                      :total (+ stored-total (- group-end i))}))
          (recur group-end))))))

(defn- staged-row [staging-prefix index-fields ordinals? tx ^bytes k v with-indexes?]
  (let [{id :key value :value} (codec/decode v)]
    [id
     value
     (when with-indexes?
       (let [sort-k (doto (Arrays/copyOf k (alength k))
                      (aset (alength ^bytes staging-prefix) (byte kind-sort-data)))
             data (keyed-sort-data! tx sort-k id (sort-data-field-count index-fields))]
         (into {:identity (sort-data-value data sort-data-identity-string-field)}
               (map (fn [[index field]] [index (keyed-old-position id index data field)]))
               index-fields)))
     (when ordinals?
       (or (membership-ordinal tx staging-prefix id)
           (throw (ex-info "Staged result has no model ordinal" {:id id}))))]))

(defn- deferred-build-postings! [{:keys [ctx staging staging-prefix searches results budget row]}]
  (let [terms (mapv (fn [[index extract]] [(text-search-config staging-prefix index) extract (HashMap.)]) searches)]
    (deferred-chunks! ctx staging results budget
      (fn [tx rows]
        (let [postings (ArrayList.)]
          (doseq [{:keys [k v]} rows
                  :let [[_ value _ ordinal] (row tx k v false)]
                  [cfg extract ^HashMap term-counts] terms
                  [term posting] (text-search-terms (text-search-normalize (extract value)))]
            (.add postings [(text-search-posting-key (text-search-term-key cfg term-kind-postings term) ordinal)
                            posting])
            (.put term-counts term (inc (long (or (.get term-counts term) 0)))))
          postings))
      (fn [tx postings]
        (doseq [[k posting] postings]
          ((:put tx) k posting))))
    (guarded-staging-write! ctx staging
      (fn [tx]
        (doseq [[cfg _ ^HashMap term-counts] terms
                ^java.util.Map$Entry entry (.entrySet term-counts)]
          ((:put tx) (text-search-term-key cfg term-kind-count (.getKey entry))
                     (membership-number-bytes (.getValue entry))))))))

(defn- deferred-build-in-memory! [{:keys [ctx d staging staging-prefix units results n budget levels unit-config row]}]
  (doseq [pass (partition-all (max 1 (quot budget (max 1 n))) units)]
    (let [sorted-entries (deferred-read ctx staging
                           (fn [tx]
                             (let [unit-entries (mapv (fn [unit] [unit (ArrayList. (int n))]) pass)]
                               (deferred-scan! tx results
                                               (fn [{:keys [k v]}]
                                                 (let [[id _ indexes ordinal] (row tx k v true)
                                                       payload (keyed-id-payload id)]
                                                   (doseq [[unit ^ArrayList entries] unit-entries]
                                                     (.add entries (deferred-entry d id indexes ordinal payload unit))))))
                               (doseq [[[index] entries] unit-entries]
                                 (deferred-sort! index entries))
                               unit-entries)))]
      (guarded-staging-write! ctx staging
        (fn [tx]
          (let [tx (with-promotion-key ctx tx)]
            (doseq [[[index direction] ^ArrayList entries] sorted-entries]
              (ranked-bulk-load! tx (unit-config index direction) entries)
              (keyed-write-levels! tx staging-prefix d index levels)
              (when (keyed-grouped? index direction)
                (deferred-write-groups! tx staging-prefix d index entries false))
              (.clear entries))
            (deferred-stamp! tx staging)))))
    (deferred-progress! staging)))

(defn- deferred-build-from-sorted-runs! [{:keys [ctx d staging staging-prefix units results budget levels unit-config row]}]
  (doseq [[index direction :as unit] units
          :let [cfg (unit-config index direction)
                spans (ranked-spans cfg)]]
    (guarded-staging-write! ctx staging
      (fn [tx]
        (ranked-init! tx cfg)
        (keyed-write-levels! tx staging-prefix d index levels)))
    (deferred-chunks! ctx staging results budget
      (fn [tx ^ArrayList rows]
        (let [entries (ArrayList. (.size rows))]
          (doseq [{:keys [k v]} rows
                  :let [[id _ indexes ordinal] (row tx k v true)]]
            (.add entries (deferred-entry d id indexes ordinal (keyed-id-payload id) unit)))
          (deferred-sort! index entries)
          entries))
      (fn [tx ^ArrayList entries]
        (doseq [^objects entry entries]
          ((:put tx) (ranked-pack cfg 0 (aget entry entry-member)) (aget entry entry-payload))
          (when (:membership? cfg)
            ((:put tx) (ranked-membership-key cfg 0 (aget entry entry-member))
                       (membership-number-bytes (aget entry entry-ordinal)))))
        (when (keyed-grouped? index direction)
          (deferred-write-groups! tx staging-prefix d index entries true))))
    (deferred-chunks! ctx staging (ranked-prefix cfg 0) budget
      (fn [tx rows]
        (into []
              (keep (fn [{:keys [k]}]
                      (let [member (ranked-unpack cfg k)]
                        (when (pos? (alength ^bytes member))
                          [member (when (:membership? cfg)
                                    (membership-number-value ((:get tx) (ranked-membership-key cfg 0 member))))]))))
              rows))
      (fn [tx members]
        (doseq [[member ordinal] members]
          (ranked-span-add! tx cfg spans member ordinal))))
    (guarded-staging-write! ctx staging #(ranked-span-finish! % cfg spans))
    (deferred-progress! staging)))

(defn- deferred-build! [ctx d staging]
  (let [cache (:cache ctx)
        staging-prefix (:prefix staging)
        searches (:searches d)
        index-fields (keyed-index-fields d)
        ordinals? (or (seq searches) (seq (:filtered-indexes d)))
        {:keys [n touched?]} (kv/transact cache :read
                               (fn [tx]
                                 {:n (or (keyed-read-meta tx (keyed-count-key staging-prefix)) 0)
                                  :touched? (some? ((:get tx) (keyed-included-key staging-prefix)))}))
        levels (keyed-target-levels n)
        build {:ctx ctx
               :d d
               :staging staging
               :staging-prefix staging-prefix
               :searches searches
               :units (vec (for [index (conj (vec (sort (keys (:indexes d)))) :identity)
                                 direction [:asc]]
                             [index direction]))
               :results (keyed-kind-prefix staging-prefix kind-result)
               :n n
               :budget (max 1 (long (:read-model/deferred-build-entries ctx deferred-default-entries)))
               :levels levels
               :unit-config (fn [index direction]
                              (assoc (keyed-membership-config staging-prefix d index direction) :levels levels))
               :row (fn [tx k v with-indexes?] (staged-row staging-prefix index-fields ordinals? tx k v with-indexes?))}]
    (when touched?
      (guarded-staging-write! ctx staging
        (fn [tx]
          (keyed-wipe! tx (keyed-kind-prefix staging-prefix kind-index))
          (keyed-wipe! tx (keyed-kind-prefix staging-prefix kind-text-search))))
      (when (seq searches)
        (deferred-build-postings! build))
      (if (<= n (:budget build))
        (deferred-build-in-memory! build)
        (deferred-build-from-sorted-runs! build)))))

(defn- deferred-publish! [ctx identity b staging]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (deferred-guard! tx staging)
      (let [progress (keyed-read-meta tx (keyed-progress-key b))
            current (namespace-prefix-of tx identity)]
        (when (and current
                   (Arrays/equals ^bytes current ^bytes b)
                   (nil? (:watermark progress))
                   (prefix-empty? tx (keyed-kind-prefix b kind-state))
                   (prefix-empty? tx (keyed-kind-prefix b kind-result)))
          (let [published (namespace-swap! tx identity (:identity staging))]
            ((:delete tx) (:marker staging))
            (keyed-wipe! tx b)
            ((:put tx) (deferred-slot identity marker-kind-replaced) (codec/encode {:at (System/currentTimeMillis)}))
            published))))))

(defn- keyed-resolve [ctx identity]
  (kv/transact (:cache ctx) :read #(namespace-prefix-of % identity)))

(defn- events-after-watermark [progress events]
  (let [watermark (:watermark progress)]
    (filterv #(or (nil? watermark) (pos? (compare (:event/id %) watermark))) events)))

(defn- fold-owner-input [ctx name d b validate-state! tx states id input]
  (when (nil? id)
    (throw (ex-info "Keyed-fold event has no owner"
                    {:model name :event-id (:event/id input) :event-type (:event/type input)})))
  (let [state (if (contains? states id)
                (get states id)
                (if-let [raw ((:get tx) (keyed-address b kind-state id))]
                  (keyed-decode ctx :state-decoded raw)
                  (:initial d {})))
        next-state ((:reducer-fn d) state input)]
    (validate-state! next-state)
    (keyed-observe! ctx :event-applied 0)
    (assoc states id next-state)))

(defn- routed-inputs [name route event]
  (let [inputs (route event)]
    (when-not (sequential? inputs)
      (throw (ex-info "Route must return an ordered sequence of [owner input] pairs"
                      {:model name :event-id (:event/id event)})))
    inputs))

(defn- check-routed-pair! [name event entry]
  (when-not (and (vector? entry) (= 2 (count entry)))
    (throw (ex-info "Invalid routed [owner input] pair" {:model name :event-id (:event/id event)}))))

(defn- keyed-fold-batch [ctx name d b validate-state! before-progress events tx]
  (let [events (events-after-watermark before-progress events)
        fold-input (fn [states id input] (fold-owner-input ctx name d b validate-state! tx states id input))]
    [events
     (reduce (fn [states event]
               (if-let [route (:route d)]
                 (reduce (fn [states entry]
                           (check-routed-pair! name event entry)
                           (fold-input states (nth entry 0) (nth entry 1)))
                         states
                         (routed-inputs name route event))
                 (fold-input states ((:key d) event) event)))
             {}
             events)]))

(defn- keyed-prepare-owner [ctx d b deferred validate-result! index-fields tx id state]
  (let [owner (keyed-owner id)
        value ((:result d identity) state)
        _ (validate-result! value)
        result-k (keyed-owner-address b kind-result owner)
        sort-k (keyed-owner-address b kind-sort-data owner)
        old (keyed-decode ctx :result-decoded ((:get tx) result-k))
        included? ((:include? d (constantly true)) value)
        indexes (when included?
                  (into {} (map (fn [[index f]] [index (f value)])) (:indexes d)))
        _ (keyed-observe! ctx :result-calculated 0)
        searches (when-not deferred (:searches d))
        stored-sort-data #(keyed-sort-data! tx sort-k id (sort-data-field-count index-fields))
        prepared {:owner owner
                  :old old
                  :included? included?
                  :indexes indexes
                  :result-k result-k
                  :sort-k sort-k
                  :state (codec/encode state)}]
    (cond
      (not included?)
      (cond-> prepared
        (and old (not deferred)) (assoc :text (text-search-changes searches old nil)
                                        :old-data (when (seq index-fields) (stored-sort-data))))

      (or (nil? old) (not= (:value old) value))
      (assoc prepared
             :changed? true
             :new-data (when (or (nil? old) (seq index-fields)) (sort-data-encode id indexes))
             :old-data (when (and old (seq index-fields)) (stored-sort-data))
             :text (text-search-changes searches old {:value value})
             :result (codec/encode {:key id :value value}))

      :else prepared)))

(defn- distinct-owner-addresses? [owners]
  (= (count owners)
     (count (into #{} (map #(ByteBuffer/wrap (.idKey ^KeyedOwner (:owner %)))) owners))))

(defn- keyed-prepare-batch [ctx name d b validate-state! validate-result! events owners? tx]
  (let [raw ((:get tx) (keyed-progress-key b))
        before-progress (some-> raw codec/decode)
        _ (keyed-check-index-layout! before-progress d)
        [events states] (keyed-fold-batch ctx name d b validate-state! before-progress events tx)
        index-fields (keyed-index-fields d)]
    {:raw raw
     :progress before-progress
     :events events
     :states states
     :owners (when owners?
               (let [owners (mapv (fn [[id state]]
                                    (keyed-prepare-owner ctx d b (::deferred ctx) validate-result! index-fields tx id state))
                                  states)]
                 (when (distinct-owner-addresses? owners)
                   owners)))}))

(defn- counting-written-bytes [tx ^longs written]
  (let [put (:put tx)]
    (assoc tx :put (fn [^bytes k ^bytes v]
                     (aset written 0 (+ (aget written 0) (alength k) (alength v)))
                     (put k v)))))

(defn- batch-transaction [ctx tx b ^longs written]
  (as-> tx tx
    (counting-written-bytes tx written)
    (keyed-write-session (membership-transaction tx) b)
    (span-count-transaction tx)
    (with-promotion-key ctx tx)))

(defn- update-included-members! [tx identity-base b id included?]
  (let [ordinal (membership-ensure-ordinal! tx identity-base id)
        included-key (keyed-included-key b)
        ^RoaringBitmap members (membership-bitmap tx included-key)]
    (if included?
      (.add members (int ordinal))
      (.remove members (int ordinal)))
    (membership-put! tx included-key members)))

(defn- remove-excluded-owner! [tx b d index-fields id {:keys [owner old-data text]}]
  (text-search-apply! tx b (:searches d) id text)
  (doseq [direction [:asc]]
    (keyed-remove-index! tx b d :identity direction (str id) owner))
  (doseq [[index field] index-fields
          :let [position (keyed-old-position id index old-data field)]
          direction [:asc]]
    (keyed-remove-index! tx b d index direction position owner)))

(defn- index-changed-owner! [ctx tx b d builders index-fields id {:keys [owner old indexes old-data new-data text]}]
  (text-search-apply! tx b (:searches d) id text)
  (if (nil? old)
    (doseq [[index position] (assoc indexes :identity (str id))
            direction [:asc]]
      (keyed-enqueue-index! ctx tx b d builders index direction position owner))
    (doseq [[index field] index-fields
            :when (not (sort-data-field= old-data new-data field))
            :let [old-position (keyed-old-position id index old-data field)
                  position (get indexes index)]
            direction [:asc]]
      (keyed-remove-index! tx b d index direction old-position owner)
      (keyed-enqueue-index! ctx tx b d builders index direction position owner))))

(defn- sort-data-unchanged? [index-fields {:keys [old old-data ^bytes new-data]}]
  (and old (or (empty? index-fields) (Arrays/equals ^bytes old-data new-data))))

(defn- write-owner! [ctx tx b d deferred builders ordinals? index-fields identity-base id prepared]
  (let [{:keys [owner old included? result-k sort-k changed? new-data]} prepared]
    (keyed-put-observed! ctx tx :state-written (keyed-owner-address b kind-state owner) (:state prepared))
    (when (not= (boolean included?) (boolean old))
      (update-included-members! tx identity-base b id included?))
    (if-not included?
      (when old
        (when-not deferred
          (remove-excluded-owner! tx b d index-fields id prepared))
        ((:delete tx) sort-k)
        ((:delete tx) result-k))
      (when changed?
        (if deferred
          (when ordinals?
            (membership-ensure-ordinal! tx b id))
          (index-changed-owner! ctx tx b d builders index-fields id prepared))
        (when-not (sort-data-unchanged? index-fields prepared)
          ((:put tx) sort-k new-data))
        (keyed-put-observed! ctx tx :result-written result-k (:result prepared))))))

(defn- included-delta [{:keys [included? old]}]
  (- (if included? 1 0) (if old 1 0)))

(defn- advance-progress! [tx d b before-progress events through]
  (let [advance-through? (and through
                              (or (nil? (:checked-through before-progress))
                                  (pos? (compare through (:checked-through before-progress)))))
        progress (cond-> before-progress
                   (nil? before-progress) (assoc :index-layout (keyed-index-layout d))
                   (seq events) (assoc :watermark (:event/id (peek events)))
                   advance-through? (assoc :checked-through through))]
    (when (or (seq events) advance-through?)
      (keyed-write! tx (keyed-progress-key b) progress))))

(defn- prepared-batch-current? [tx b prepared]
  (and prepared
       (Arrays/equals ^bytes ((:get tx) (keyed-progress-key b)) ^bytes (:raw prepared))))

(defn- keyed-write-batch! [ctx name d b validate-state! validate-result! events through prepared]
  (let [result (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [written (long-array 1)
            tx (batch-transaction ctx tx b written)
            identity-base (::keyed/shared-identity-prefix ctx)
            deferred (::deferred ctx)
            _ (when deferred (deferred-guard! tx deferred))
            {before-progress :progress :keys [events states owners]}
            (if (prepared-batch-current? tx b prepared)
              prepared
              (keyed-prepare-batch ctx name d b validate-state! validate-result! events false tx))
            count-before (or (keyed-read-meta tx (keyed-count-key b)) 0)
            added (volatile! 0)
            builders (when-not deferred (keyed-cold-index-builders ctx tx b d before-progress states))
            ordinals? (or (seq (:searches d)) (seq (:filtered-indexes d)))
            index-fields (keyed-index-fields d)]
        (doseq [[i [id state]] (map-indexed vector states)]
          (count-caches-bound! tx)
          (let [prepared-owner (if owners
                                 (nth owners i)
                                 (keyed-prepare-owner ctx d b deferred validate-result! index-fields tx id state))]
            (vswap! added + (included-delta prepared-owner))
            (write-owner! ctx tx b d deferred builders ordinals? index-fields identity-base id prepared-owner)))
        (keyed-build-cold-indexes! tx builders)
        (span-count-flush! tx)
        (group-count-flush! tx)
        (term-count-flush! tx)
        (when (and (not deferred) (seq events) (not (zero? @added)))
          (keyed-relevel! tx b d (+ count-before @added)))
        (membership-flush! tx #(keyed-observe! ctx %1 %2))
        (when deferred
          (deferred-stamp! tx deferred (boolean (seq events))))
        (when (and (seq events) (not (zero? @added)))
          (keyed-write! tx (keyed-count-key b) (+ count-before @added)))
        (advance-progress! tx d b before-progress events through)
        {:events (count events) :bytes (aget written 0)})))]
    (let [dims (assoc (telemetry/dimensions ctx name "keyed")
                      :read-model-target (if (::deferred ctx) "staging" "live"))]
      (telemetry/counter! "ReadModelCommittedEvents" (:events result) dims)
      (telemetry/counter! "ReadModelCommittedBatches" 1 dims)
      (telemetry/counter! "ReadModelEncodedBytesWritten" (:bytes result) dims))
    result))

(defn- keyed-interrupt? [t]
  (loop [^Throwable t t]
    (cond
      (nil? t) false
      (or (instance? InterruptedException t)
          (instance? java.io.InterruptedIOException t)
          (instance? java.nio.channels.ClosedByInterruptException t)) true
      :else (recur (.getCause t)))))

(defn- prepare-batch-outside-write [ctx name d b validate-state! validate-result! events]
  (when-not (:read-model/observer ctx)
    (try
      (kv/transact (:cache ctx) :read
        #(keyed-prepare-batch ctx name d b validate-state! validate-result! events true %))
      (catch Exception e
        (when (or (keyed-interrupt? e) (.isInterrupted (Thread/currentThread)))
          (throw e))
        nil))))

(defn- keyed-apply-batch!
  ([ctx name d b validate-state! validate-result! events]
   (keyed-apply-batch! ctx name d b validate-state! validate-result! events nil))
  ([ctx name d b validate-state! validate-result! events through]
   (keyed-write-batch! ctx name d b validate-state! validate-result! events through
                       (prepare-batch-outside-write ctx name d b validate-state! validate-result! events))))

(defn- keyed-head [ctx]
  (let [result (es/head (:event-store ctx) (:tenant-id ctx))]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info "Cannot read the committed event-store head" result)))
    result))

(def ^:private empty-proof-max-entries 256)
(def ^:private empty-proof-max-bytes (* 16 1024 1024))
(def ^:private empty-proof-max-entry-bytes (* 256 1024))
(def ^:private empty-proof-prune-every 256)
(def ^:private empty-proofs* (atom {:entries {} :bytes 0}))

(defn- empty-proof-key [source identity d]
  [(System/identityHashCode source) identity (:events d)])

(defn- empty-proof-size [[_ identity types]]
  (+ 1024
     (* 2 (count (str (nth identity 1))))
     (if-let [scope (nth identity 3 nil)] (* 2 (count (second scope))) 0)
     (* 128 (count types))
     (reduce + 0 (map #(* 2 (count (str %))) types))))

(defn- empty-proof-forget! [key]
  (locking empty-proofs*
    (when-let [proof (get-in @empty-proofs* [:entries key])]
      (swap! empty-proofs* #(-> %
                                (update :entries dissoc key)
                                (update :bytes - (:bytes proof)))))))

(defn- proof-source [proof]
  (.get ^WeakReference (:source proof)))

(defn- empty-proof-covers? [source key after through]
  (locking empty-proofs*
    (when-let [proof (get-in @empty-proofs* [:entries key])]
      (if-not (identical? source (proof-source proof))
        (do (empty-proof-forget! key)
            false)
        (and (or (nil? (:after proof))
                 (and after (not (neg? (compare after (:after proof))))))
             (not (pos? (compare through (:through proof)))))))))

(defn- prune-empty-proofs [state]
  (reduce-kv (fn [state key proof]
               (if (proof-source proof)
                 state
                 (-> state
                     (update :entries dissoc key)
                     (update :bytes - (:bytes proof)))))
             state
             (:entries state)))

(defn- make-room-for-proof [state size]
  (cond
    (not (or (>= (count (:entries state)) empty-proof-max-entries)
             (> (+ (:bytes state) size) empty-proof-max-bytes)))
    state

    (pos? (:prune-countdown state 0))
    (update state :prune-countdown dec)

    :else
    (assoc (prune-empty-proofs state) :prune-countdown (dec empty-proof-prune-every))))

(defn- remembered-through [old source after through]
  (if (and old
           (identical? source (proof-source old))
           (= after (:after old))
           (pos? (compare (:through old) through)))
    (:through old)
    through))

(defn- empty-proof-remember! [source key after through]
  (let [size (empty-proof-size key)]
    (when (<= size empty-proof-max-entry-bytes)
      (locking empty-proofs*
        (let [state @empty-proofs*
              state (if (nil? (get-in state [:entries key]))
                      (make-room-for-proof state size)
                      state)
              old (get-in state [:entries key])
              bytes (+ (- (:bytes state) (:bytes old 0)) size)]
          (if (and (or old (< (count (:entries state)) empty-proof-max-entries))
                   (<= bytes empty-proof-max-bytes))
            (do (reset! empty-proofs*
                        (assoc state
                               :entries (assoc (:entries state) key
                                               {:source (WeakReference. source)
                                                :after after
                                                :through (remembered-through old source after through)
                                                :bytes size})
                               :bytes bytes))
                true)
            (do (reset! empty-proofs* state)
                false)))))))

(defn- keyed-checkpoint! [ctx d b through]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (when-let [staging (::deferred ctx)]
        (deferred-guard! tx staging))
      (let [progress (keyed-read-meta tx (keyed-progress-key b))
            checked-through (:checked-through progress)]
        (keyed-check-index-layout! progress d)
        (when (or (nil? checked-through) (pos? (compare through checked-through)))
          (keyed-write! tx (keyed-progress-key b)
                        (assoc (or progress {:index-layout (keyed-index-layout d)}) :checked-through through)))))))

(def ^:private default-catch-up-policy
  {:min-events 128
   :cold-events 1024
   :max-events 8192
   :max-bytes (* 32 1024 1024)
   :max-input-bytes (* 32 1024 1024)
   :max-map-fill 0.5})

(def ^:private catch-up-policy-keys (set (keys default-catch-up-policy)))

(defn- valid-catch-up-policy? [{:keys [min-events cold-events max-events max-bytes max-input-bytes max-map-fill]}]
  (and (pos-int? min-events)
       (pos-int? cold-events)
       (pos-int? max-events)
       (<= min-events cold-events max-events)
       (or (nil? max-bytes) (pos-int? max-bytes))
       (or (nil? max-input-bytes) (pos-int? max-input-bytes))
       (or (nil? max-map-fill) (and (number? max-map-fill) (< 0 max-map-fill) (<= max-map-fill 1)))))

(defn- fixed-batch-policy [batch-size]
  (when-not (pos-int? batch-size)
    (throw (ex-info "Keyed-fold batch size must be positive" {:batch-size batch-size})))
  {:min-events batch-size
   :cold-events batch-size
   :max-events batch-size
   :max-bytes nil
   :max-input-bytes nil
   :max-map-fill nil})

(defn- keyed-batch-policy [ctx]
  (let [overrides (:read-model/catch-up ctx)
        _ (when-not (or (nil? overrides) (map? overrides))
            (throw (ex-info "Keyed-fold catch-up policy must be a map" {:catch-up overrides})))
        unknown (remove catch-up-policy-keys (keys overrides))
        _ (when (seq unknown)
            (throw (ex-info "Keyed-fold catch-up policy has unknown keys"
                            {:unknown (vec unknown) :allowed (sort catch-up-policy-keys)})))
        policy (merge default-catch-up-policy overrides)]
    (when-not (valid-catch-up-policy? policy)
      (throw (ex-info "Keyed-fold catch-up policy requires positive integer :min-events <= :cold-events <= :max-events, positive or nil :max-bytes and :max-input-bytes, and :max-map-fill in (0, 1] or nil"
                      {:catch-up policy})))
    (if-some [batch-size (:read-model/batch-size ctx)]
      (fixed-batch-policy batch-size)
      policy)))

(defn- keyed-input-size ^long [x]
  (cond
    (string? x) (+ 40 (* 2 (.length ^String x)))
    (map? x) (reduce-kv (fn [^long n k v] (+ n 32 (keyed-input-size k) (keyed-input-size v))) 48 x)
    (bytes? x) (+ 16 (alength ^bytes x))
    (coll? x) (reduce (fn [^long n v] (+ n 8 (keyed-input-size v))) 48 x)
    :else 16))

(defn- keyed-store-space [ctx]
  (kv/transact (:cache ctx) :read #(when-let [space (:space %)] (space))))

(defn- keyed-next-batch-limit [{:keys [min-events max-events max-bytes max-map-fill]} limit applied space]
  (let [bytes (:bytes applied)
        unallocated-tail (when space (- (:map-bytes space) (:high-water-bytes space)))]
    (cond
      (and space
           max-map-fill
           (or (> (:high-water-bytes space) (* max-map-fill (:map-bytes space)))
               (< unallocated-tail (* 4 (or bytes 0)))))
      min-events

      (or (nil? max-bytes) (nil? bytes) (<= bytes max-bytes))
      (min max-events (* 2 limit))

      (> bytes (* 4 max-bytes))
      (max min-events (quot limit 2))

      :else
      limit)))

(defn- keyed-map-full? [t]
  (loop [t t]
    (cond
      (nil? t) false
      (= "org.lmdbjava.Env$MapFullException" (.getName (class t))) true
      :else (recur (.getCause ^Throwable t)))))

(defn- keyed-apply-with-backoff!
  [ctx name d b validate-state! validate-result! events through min-events on-backoff]
  (try
    (if through
      (keyed-apply-batch! ctx name d b validate-state! validate-result! events through)
      (keyed-apply-batch! ctx name d b validate-state! validate-result! events))
    (catch Throwable t
      (if (and (keyed-map-full? t) (> (count events) min-events))
        (let [half (quot (inc (count events)) 2)
              _ (telemetry/counter! "ReadModelBatchBackoff" 1
                                   (assoc (telemetry/dimensions ctx name "keyed")
                                          :read-model-target (if (::deferred ctx) "staging" "live")))
              _ (on-backoff half)
              first-part (keyed-apply-with-backoff! ctx name d b validate-state! validate-result!
                                                    (subvec events 0 half) nil min-events on-backoff)
              second-part (keyed-apply-with-backoff! ctx name d b validate-state! validate-result!
                                                     (subvec events half) through min-events on-backoff)]
          {:events (count events)
           :bytes (max (:bytes first-part 0) (:bytes second-part 0))
           :backed-off true})
        (throw t)))))

(defn- keyed-deferrable? [ctx progress identity]
  (let [threshold (:read-model/deferred-build-threshold ctx deferred-default-threshold)]
    (boolean (and identity
                  (nil? (:watermark progress))
                  (nil? (:read-model/observer ctx))
                  (pos-int? threshold)
                  (not (::no-deferral ctx))))))

(defn- progress-after [progress]
  (latest-id (:checked-through progress) (:watermark progress)))

(defn- behind-head? [after through]
  (and through (or (nil? after) (pos? (compare through after)))))

(defn- restart [ctx]
  [::restart ctx])

(defn- restart? [result]
  (and (vector? result) (= ::restart (nth result 0 nil))))

(defn- catch-up-batcher
  [ctx name d b policy cold? validate-state! validate-result! staging]
  (let [min-events (:min-events policy)
        max-input (:max-input-bytes policy)
        adaptive? (not= min-events (:max-events policy))
        limit (volatile! (if cold? (:cold-events policy) min-events))
        ceiling (volatile! (:max-events policy))
        input (volatile! 0)
        on-backoff (fn [n] (vreset! ceiling (max min-events n)))
        apply! (fn [events through]
                 (let [s @staging
                       applied (if s
                                 (keyed-apply-with-backoff! (assoc ctx ::deferred s) name d (:prefix s)
                                                            validate-state! validate-result!
                                                            events through min-events on-backoff)
                                 (keyed-apply-with-backoff! ctx name d b validate-state! validate-result!
                                                            events through min-events on-backoff))]
                   (when s
                     (some-> ^java.util.concurrent.atomic.AtomicLong (:generation s) (.set 0))
                     (deferred-progress! s))
                   (vreset! input 0)
                   (when-not through
                     (vreset! limit (min @ceiling
                                         (keyed-next-batch-limit
                                          policy @limit applied
                                          (when (and adaptive? (:max-map-fill policy)) (keyed-store-space ctx))))))
                   applied))
        add-input! (fn [event]
                     (if max-input (vswap! input + (keyed-input-size event)) 0))
        over-input-budget? (fn [input-bytes]
                             (and max-input (>= input-bytes max-input)))
        accumulate (fn [batch event]
                     (let [batch (conj batch event)
                           input-bytes (add-input! event)]
                       (if (or (<= @limit (count batch)) (over-input-budget? input-bytes))
                         (do (apply! batch nil)
                             [])
                         batch)))]
    {:apply! apply!
     :accumulate accumulate
     :add-input! add-input!
     :over-input-budget? over-input-budget?
     :drain! (fn [events]
               (vreset! input 0)
               (reduce accumulate [] events))}))

(defn- buffer-or-defer! [{:keys [ctx identity b threshold decided? buffered own! batcher]} batch event]
  (let [{:keys [add-input! over-input-budget? drain!]} batcher
        batch (conj batch event)
        input-bytes (add-input! event)]
    (if (and (< (count batch) threshold) (not (over-input-budget? input-bytes)))
      (do (vreset! buffered batch)
          batch)
      (let [claim (deferred-claim! ctx identity b)]
        (when (= :wait claim)
          (throw (ex-info "Another process is building this model" {:type ::deferred-await})))
        (when (= :restart claim)
          (throw (ex-info "Another build published this model" {:type ::deferred-restart})))
        (vreset! decided? true)
        (vreset! buffered nil)
        (own! claim)
        (drain! batch)))))

(defn- fold-source-events! [{:keys [source query through stable? proof-key accepted? decided? staging batcher] :as run}]
  (let [{:keys [accumulate drain!]} batcher
        remaining (reduce (fn [batch event]
                            (if (pos? (compare (:event/id event) through))
                              (reduced batch)
                              (do (when (and stable? (not @accepted?))
                                    (empty-proof-forget! proof-key))
                                  (vreset! accepted? true)
                                  (deferred-progress! @staging)
                                  (if @decided?
                                    (accumulate batch event)
                                    (buffer-or-defer! run batch event)))))
                          []
                          (if (seq query) (es/read source query) []))]
    (if @decided?
      remaining
      (do (vreset! decided? true)
          (vreset! (:buffered run) nil)
          (drain! remaining)))))

(defn- build-and-publish! [ctx d identity b staging]
  (try
    (deferred-build! ctx d staging)
    (catch Throwable t
      (when-not (= ::deferred-superseded (:type (ex-data t)))
        (try (deferred-abandon! ctx identity staging true)
             (catch Throwable cleanup (.addSuppressed t cleanup))))
      (throw t)))
  (or (deferred-publish! ctx identity b staging)
      (do (deferred-abandon! ctx identity staging false)
          (restart (assoc ctx ::no-deferral true)))))

(defn- finish-catch-up! [{:keys [ctx d b identity progress after through source stable? proof-key
                                 accepted? deferrable? staging batcher]} remaining]
  (when (seq remaining)
    ((:apply! batcher) remaining through))
  (if-let [s @staging]
    (keyed-checkpoint! (assoc ctx ::deferred s) d (:prefix s) through)
    (when-not (or (seq remaining)
                  (and stable? (not @accepted?) (empty-proof-remember! source proof-key after through)))
      (keyed-checkpoint! ctx d b through)))
  (if-let [s @staging]
    (build-and-publish! ctx d identity b s)
    (do (when (and @accepted? (not deferrable?) (:watermark progress) identity)
          (deferred-clean-stale! ctx identity))
        b)))

(defn- recover-from-catch-up-failure! [{:keys [ctx identity decided? buffered staging beat batcher]} ^Throwable e]
  (let [s @staging
        failure-type (:type (ex-data e))]
    (cond
      (= ::deferred-await failure-type)
      (do (deferred-await! ctx identity)
          (restart ctx))

      (= ::deferred-restart failure-type)
      (restart ctx)

      (nil? s)
      (do (when-not @decided?
            (try ((:drain! batcher) @buffered)
                 (catch Throwable x (.addSuppressed e x))))
          (throw e))

      (= ::deferred-superseded failure-type)
      (restart ctx)

      :else
      (do (some-> @beat (apply []))
          (try (deferred-release! ctx s)
               (catch Throwable x (.addSuppressed e x)))
          (throw e)))))

(defn- catch-up-from-source! [{:keys [ctx name d b identity after policy deferrable? pending? claimed] :as step}]
  (let [validate-state! (keyed-prepare-validation (:state-schema d) name :state)
        validate-result! (keyed-prepare-validation (:result-schema d) name :result)
        staged (when claimed
                 (kv/transact (:cache ctx) :read #(keyed-read-meta % (keyed-progress-key (:prefix claimed)))))
        read-after (latest-id after (:checked-through staged) (:watermark staged))
        staging (volatile! claimed)
        beat (volatile! nil)
        run (assoc step
                   :query (keyed-event-query ctx d read-after)
                   :accepted? (volatile! false)
                   :threshold (when deferrable? (:read-model/deferred-build-threshold ctx deferred-default-threshold))
                   :decided? (volatile! (or (not deferrable?) pending?))
                   :buffered (volatile! [])
                   :staging staging
                   :beat beat
                   :own! (fn [s]
                           (vreset! staging s)
                           (when s
                             (telemetry/cold-start!)
                             (vreset! beat (deferred-heartbeat! ctx s (deferred-lease-ms ctx)))))
                   :batcher (catch-up-batcher ctx name d b policy (nil? after)
                                              validate-state! validate-result! staging))]
    (when claimed
      ((:own! run) claimed))
    (try
      (keyed-check-index-layout! staged d)
      (let [result (finish-catch-up! run (fold-source-events! run))]
        (when (restart? result) (telemetry/cold-result! "superseded"))
        result)
      (catch Throwable e
        (let [result (recover-from-catch-up-failure! run e)]
          (when (restart? result) (telemetry/cold-result! "superseded"))
          result))
      (finally
        (some-> @beat (apply []))
        (some->> @staging :build (deferred-disown! ctx identity))))))

(defn- keyed-catch-up-step! [ctx name d b through identity]
  (let [progress (kv/transact (:cache ctx) :read #(keyed-read-meta % (keyed-progress-key b)))
        _ (keyed-check-index-layout! progress d)
        after (progress-after progress)
        policy (keyed-batch-policy ctx)]
    (if-not (behind-head? after through)
      b
      (let [source (:event-store ctx)
            stable? (es/stable-empty-intervals? source)
            proof-key (when stable? (empty-proof-key source identity d))]
        (if (and stable? (empty-proof-covers? source proof-key after through))
          b
          (let [deferrable? (keyed-deferrable? ctx progress identity)
                pending? (and deferrable?
                              (some? (kv/transact (:cache ctx) :read #(deferred-marker % identity))))
                claimed (when pending? (deferred-claim! ctx identity b))]
            (case claimed
              :wait (do (deferred-await! ctx identity)
                        (restart ctx))
              :restart (restart ctx)
              (try
                (telemetry/cold-owned! (telemetry/dimensions ctx name "keyed") (map? claimed)
                  (fn []
                  (catch-up-from-source! {:ctx ctx :name name :d d :b b :identity identity
                                        :progress progress :after after :through through
                                        :policy policy :source source :stable? stable? :proof-key proof-key
                                        :deferrable? deferrable? :pending? pending? :claimed claimed})))
                (finally
                  (some->> claimed :build (deferred-disown! ctx identity)))))))))))

(defn- keyed-catch-up! [ctx name d b through identity]
  (telemetry/owned! "ReadModelCatchUpDuration" "ReadModelCatchUpFailed"
    (telemetry/dimensions ctx name "keyed")
    (fn []
    (loop [ctx (assoc ctx ::metric-model name) b b]
    (let [result (keyed-catch-up-step! ctx name d b through identity)]
      (if (restart? result)
        (let [ctx (nth result 1)]
          (recur ctx (or (keyed-resolve ctx identity) b)))
        result))))))

(defn- keyed-identity-of [ctx]
  [(:tenant-id ctx) (:read-model/scope ctx)])

(defn keyed-session-definition [ctx name]
  (when-let [session (::keyed/session ctx)]
    (when-not (and (= (:identity session) (keyed-identity-of ctx))
                   (identical? (:cache session) (:cache ctx))
                   (identical? (:event-store session) (:event-store ctx)))
      (throw (ex-info "Read-session context identity was changed" {:model name})))
    (or (get (:definitions session) name)
        (throw (ex-info "Model was not prepared in this read session" {:model name})))))

(defn- keyed-prepare-reader [tx b d]
  (let [read (fn [kind id] ((:get tx) (keyed-address b kind id)))]
    (fn [f] (f tx b d read))))

(defn- namespace-resolve-in [tx identities]
  (let [{ordinary 3 scoped 4} (group-by count identities)]
    (when-let [ordinary (if (seq ordinary) (namespace-resolve-known tx ordinary) {})]
      (when-let [scoped (if (seq scoped)
                          (with-namespace-space (first scoped) #(namespace-resolve-known tx scoped))
                          {})]
        (merge ordinary scoped)))))

(defn- keyed-fresh? [tx ctx d identity b through]
  (let [progress (keyed-read-meta tx (keyed-progress-key b))
        _ (keyed-check-index-layout! progress d)
        after (progress-after progress)]
    (boolean
     (or (not (behind-head? after through))
         (let [source (:event-store ctx)]
           (and (es/stable-empty-intervals? source)
                (empty-proof-covers? source (empty-proof-key source identity d) after through)))))))

(def ^:private keyed-catch-ups* (atom {}))

(def ^:private keyed-catch-up-wait-ms 10000)

(def ^:private keyed-catch-up-foreign-failures 3)

(defn- prefixes-needing-catch-up [ctx d identity shared-identity through]
  (kv/transact (:cache ctx) :read
    (fn [tx]
      (let [prefixes (namespace-resolve-in tx [identity shared-identity])
            b (get prefixes identity)]
        (when-not (and b (keyed-fresh? tx ctx d identity b through))
          [b (get prefixes shared-identity)])))))

(defn- run-as-single-flight! [flight-key flight catch-up!]
  (let [succeeded? (volatile! false)]
    (try
      (let [result (catch-up!)]
        (vreset! succeeded? true)
        result)
      (finally
        (swap! keyed-catch-ups* #(if (identical? flight (get % flight-key)) (dissoc % flight-key) %))
        (deliver flight (if @succeeded? ::done ::failed))))))

(defn- keyed-catch-up-single! [ctx name d identity shared-identity through]
  (let [cache (:cache ctx)
        flight-key [(or (:env cache) cache) identity]
        catch-up! (fn [b shared]
                    {identity (or (keyed-catch-up! (assoc ctx ::keyed/shared-identity-prefix shared)
                                                   name d b through identity)
                                  b)})]
    (loop [foreign-failures 0]
      (when-let [[b shared] (prefixes-needing-catch-up ctx d identity shared-identity through)]
        (if (>= foreign-failures keyed-catch-up-foreign-failures)
          (catch-up! b shared)
          (let [flight (promise)
                [_ running] (swap-vals! keyed-catch-ups* #(if (contains? % flight-key) % (assoc % flight-key flight)))
                running-flight (get running flight-key)]
            (if (identical? flight running-flight)
              (run-as-single-flight! flight-key flight #(catch-up! b shared))
              (recur (cond-> foreign-failures
                       (identical? ::failed (telemetry/owned! "ReadModelWaitDuration" nil
                         (assoc (telemetry/dimensions ctx name "keyed") :read-model-wait-kind "catch-up")
                         #(deref running-flight keyed-catch-up-wait-ms nil))) inc)))))))))

(defn- validate-session-request! [cache event-store registry [ctx names]]
  (keyed-validate-scope! (:read-model/scope ctx))
  (when (::keyed/session ctx)
    (throw (ex-info "Cannot open a nested keyed-fold read session" {})))
  (when-not (identical? cache (:cache ctx))
    (throw (ex-info "Read-session contexts must share one store" {})))
  (when-not (identical? event-store (:event-store ctx))
    (throw (ex-info "Read-session contexts must share one event store" {})))
  (doseq [name names]
    (when-not (keyed? (get registry name))
      (throw (ex-info "Expected a registered keyed fold" {:model name}))))
  (when (seq names)
    (keyed-batch-policy ctx)))

(defn- requests-with-namespace-identities [requests registry]
  (let [scope-identities (into {}
                               (map (fn [scope] [scope (keyed-scope-identity scope)]))
                               (distinct (map (comp :read-model/scope first) requests)))
        identities (volatile! {})
        identity-order (volatile! [])
        identity-for (fn [ctx name d]
                       (let [k [(:tenant-id ctx) name (storage-version d) (:read-model/scope ctx)]]
                         (or (get @identities k)
                             (let [identity (keyed-namespace-identity ctx name d
                                                                      (get scope-identities (:read-model/scope ctx)))]
                               (vswap! identities assoc k identity)
                               (vswap! identity-order conj identity)
                               identity))))
        requests (mapv (fn [[ctx names]]
                         [ctx
                          (identity-for ctx :grain.read-query/shared-identities {:version 1})
                          (into {}
                                (map (fn [name] [name (identity-for ctx name (get registry name))]))
                                (distinct names))])
                       requests)]
    [requests @identity-order]))

(defn- capture-heads [requests]
  (reduce (fn [heads [ctx _ _]]
            (let [tenant-id (:tenant-id ctx)]
              (if (contains? heads tenant-id)
                heads
                (assoc heads tenant-id (keyed-head ctx)))))
          {}
          requests))

(defn- session-context [ctx tx cache event-store prefixes definitions model-identities]
  (assoc ctx ::keyed/session
         {:cache cache
          :event-store event-store
          :identity (keyed-identity-of ctx)
          :definitions definitions
          :readers (into {}
                         (map (fn [[name d]]
                                [name (keyed-prepare-reader tx (get prefixes (get model-identities name)) d)]))
                         definitions)}))

(defn- model-fresh? [tx registry heads prefixes verified ctx [name identity]]
  (let [b (get prefixes identity)]
    (or (when-let [^bytes verified-prefix (get verified identity)]
          (Arrays/equals verified-prefix ^bytes b))
        (keyed-fresh? tx ctx (get registry name) identity b (get heads (:tenant-id ctx))))))

(defn- catch-up-session-models [registry requests heads verified]
  (first
   (reduce (fn [acc [ctx shared-identity model-identities]]
             (reduce (fn [[verified caught-up :as acc] [name identity]]
                       (if (contains? caught-up identity)
                         acc
                         [(merge verified
                                 (keyed-catch-up-single! ctx name (get registry name) identity
                                                         shared-identity (get heads (:tenant-id ctx))))
                          (conj caught-up identity)]))
                     acc
                     model-identities))
           [verified #{}]
           requests)))

(defn keyed-read-session [requests registry f]
  (when-not (seq requests)
    (throw (ex-info "Read session requires at least one request" {})))
  (let [cache (:cache (ffirst requests))
        event-store (:event-store (ffirst requests))]
    (when-not cache
      (throw (ex-info "Read session requires :cache" {})))
    (doseq [request requests]
      (validate-session-request! cache event-store registry request))
    (let [[requests identity-order] (requests-with-namespace-identities requests registry)
          _ (namespace-check-identities! cache identity-order)
          heads (capture-heads requests)
          prepared (mapv (fn [[ctx _ model-identities]]
                           [ctx (select-keys registry (keys model-identities)) model-identities])
                         requests)
          serve-if-fresh (fn [verified]
                           (kv/transact cache :read
                             (fn [tx]
                               (let [prefixes (namespace-resolve-in tx identity-order)
                                     request-fresh? (fn [[ctx _ model-identities]]
                                                      (every? #(model-fresh? tx registry heads prefixes verified ctx %)
                                                              model-identities))]
                                 (if-not (and prefixes (every? request-fresh? requests))
                                   ::stale
                                   (f (mapv (fn [[ctx definitions model-identities]]
                                              (session-context ctx tx cache event-store prefixes definitions model-identities))
                                            prepared)))))))]
      (loop [verified {}]
        (let [result (serve-if-fresh verified)]
          (if-not (identical? ::stale result)
            result
            (do (namespace-resolve! cache identity-order)
                (recur (catch-up-session-models registry requests heads verified)))))))))

(defn- keyed-with-reader [ctx name d f]
  (if-let [session (::keyed/session ctx)]
    (do (keyed-session-definition ctx name)
        ((get (:readers session) name) f))
    (keyed-read-session [[ctx [name]]] {name d}
                        (fn [[prepared]] ((get-in prepared [::keyed/session :readers name]) f)))))

(defn- observer-of [ctx]
  #(keyed-observe! ctx %1 %2))

(defn- check-instance-key! [name id]
  (when (nil? id)
    (throw (ex-info "Instance key cannot be nil" {:model name}))))

(defn keyed-point [ctx name d id kind not-found]
  (check-instance-key! name id)
  (keyed-with-reader ctx name d
    (fn [_tx _b _d read]
      (let [state? (= kind :state)]
        (if-let [raw (read (if state? kind-state kind-result) id)]
          (let [decoded (keyed-decode ctx (if state? :state-decoded :result-decoded) raw)]
            (if state? decoded (:value decoded)))
          not-found)))))

(defn- included-members-of [raw]
  (if raw (membership-decode raw) (RoaringBitmap.)))

(defn keyed-same-members? [ctx a ad b bd]
  (keyed-with-reader ctx a ad
    (fn [tx a-prefix _ _]
      (keyed-with-reader ctx b bd
        (fn [_ b-prefix _ _]
          (let [left ((:get tx) (keyed-included-key a-prefix))
                right ((:get tx) (keyed-included-key b-prefix))]
            (keyed-observe! ctx :identity-membership-read (+ (if left (alength ^bytes left) 0)
                                                             (if right (alength ^bytes right) 0)))
            (= (included-members-of left) (included-members-of right))))))))

(defn keyed-progress [ctx name d]
  (keyed-with-reader ctx name d
    (fn [tx b _d _read]
      (keyed-public-progress (keyed-read-meta tx (keyed-progress-key b))))))

(defn keyed-search-keys [ctx name d options]
  (keyed-with-reader ctx name d
    (fn [tx b _ _read]
      (text-search-search-keys tx b (:searches d) options (observer-of ctx)))))

(defn- keyed-reduce-distinct [rf initial items]
  (let [seen (volatile! (transient #{}))]
    (reduce (fn [acc item]
              (if (contains? @seen item)
                acc
                (do (vreset! seen (conj! @seen item))
                    (rf acc item))))
            initial
            items)))

(defn- declared-index? [d index]
  (or (= :identity index) (contains? (:indexes d) index)))

(defn keyed-index-values [ctx name d indexes ids]
  (when-not (and (vector? indexes) (every? (partial declared-index? d) indexes))
    (throw (ex-info "Expected a vector of declared index names" {:model name :indexes indexes})))
  (let [fields (mapv #(sort-data-field (:indexes d) %) indexes)]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf initial]
        (keyed-with-reader ctx name d
          (fn [tx b _ read]
            (let [step (fn [acc raw]
                         (keyed-observe! ctx :sort-key-read (alength ^bytes raw))
                         (rf acc {:key (sort-data-value raw sort-data-id-field)
                                  :value (sort-data-values raw indexes fields)}))]
              (if (some? ids)
                (keyed-reduce-distinct (fn [acc id]
                                         (if-let [raw (read kind-sort-data id)]
                                           (step acc raw)
                                           acc))
                                       initial
                                       ids)
                (scan tx {:prefix (keyed-kind-prefix b kind-sort-data)}
                      (fn [acc {:keys [v]}] (step acc v))
                      initial)))))))))

(defn- valid-key-page-options? [d options {:keys [index indexes direction page limit sort-value include?]}]
  (and (map? options)
       (every? #{:index :indexes :direction :page :limit :sort-value :include?} (keys options))
       (declared-index? d index)
       (or (not (contains? options :indexes))
           (and (or sort-value include?)
                (vector? indexes)
                (every? (partial declared-index? d) indexes)))
       (#{:asc :desc} direction)
       (pos-int? page)
       (pos-int? limit)
       (or (nil? sort-value) (ifn? sort-value))
       (or (nil? include?) (ifn? include?))))

(defn keyed-key-page [ctx name d keys options]
  (let [{:keys [index indexes direction page limit sort-value include?]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (valid-key-page-options? d options {:index index :indexes indexes :direction direction :page page
                                                  :limit limit :sort-value sort-value :include? include?})
      (throw (ex-info "Invalid matching-key page" {:model name :options options})))
    (keyed-with-reader ctx name d
      (fn [tx b _d read]
        (let [field (sort-data-field (:indexes d) index)
              fields (mapv #(sort-data-field (:indexes d) %) indexes)
              ^java.util.Comparator comparator (sort-data-comparator field (= direction :desc) (some? sort-value))
              worst-first (java.util.PriorityQueue. 11 (.reversed comparator))
              wanted (long (min Long/MAX_VALUE (*' page limit)))
              ^longs counts (long-array 2)
              read-count-slot 0
              accepted-count-slot 1
              collect! (fn [id raw]
                         (keyed-observe! ctx :sort-key-read (alength ^bytes raw))
                         (aset counts read-count-slot (inc (aget counts read-count-slot)))
                         (let [values (when (or include? sort-value)
                                        (if (some? indexes)
                                          (sort-data-values raw indexes fields)
                                          (sort-data-value raw field)))]
                           (when (or (nil? include?) (include? id values))
                             (aset counts accepted-count-slot (inc (aget counts accepted-count-slot)))
                             (let [entry (SortDataEntry. id raw (when sort-value (key-encode-compact (sort-value id values))))]
                               (if (< (.size worst-first) wanted)
                                 (.add worst-first entry)
                                 (when (neg? (.compare comparator entry (.peek worst-first)))
                                   (.poll worst-first)
                                   (.add worst-first entry)))))))]
          (if (= ::keyed/all keys)
            (scan tx {:prefix (keyed-kind-prefix b kind-sort-data)}
                  (fn [_ {:keys [v]}] (collect! (sort-data-value v sort-data-id-field) v))
                  nil)
            (let [seen (HashSet.)]
              (doseq [id keys
                      :when (.add seen id)]
                (when-let [raw (read kind-sort-data id)]
                  (collect! id raw)))))
          (let [^objects ordered (.toArray worst-first)
                _ (Arrays/sort ordered comparator)
                total (aget counts accepted-count-slot)
                pages (max 1 (quot (+' total (dec limit)) limit))
                page (min page pages)
                offset (* (dec page) limit)]
            {:keys (mapv #(.-id ^SortDataEntry (aget ordered %))
                         (range offset (min total (+ offset limit))))
             :total total
             :total-pages pages
             :page page
             :page-size limit
             :sort-keys-read (aget counts read-count-slot)}))))))

(defn- keyed-leaf-config [cfg d index direction]
  (assoc cfg ::leaf-payload (fn [^bytes member ^bytes v] (keyed-entry-id d index direction member 0 v))))

(defn- keyed-page-key [payload]
  (if (bytes? payload)
    (codec/decode payload)
    payload))

(defn- filtered-read-config [tx b d index]
  (keyed-leaf-config (keyed-read-config tx b d (keyed-membership-config b d index :asc) index) d index :asc))

(defn- page-keys [result]
  (-> result
      (dissoc :payloads)
      (assoc :keys (mapv keyed-page-key (:payloads result)))))

(defn- valid-page-request? [{:keys [direction page limit]}]
  (and (#{:asc :desc} direction) (pos-int? page) (pos-int? limit)))

(defn keyed-filtered-key-page [ctx name d keys options]
  (let [{:keys [index direction page limit] :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options)
                   (every? #{:index :direction :page :limit} (clojure.core/keys options))
                   (contains? (:filtered-indexes d) index)
                   (valid-page-request? {:direction direction :page page :limit limit}))
      (throw (ex-info "Ranked key page requires an opted-in stored index" {:model name :options options})))
    (keyed-with-reader ctx name d
      (fn [tx b _d _read]
        (let [candidates (RoaringBitmap.)
              examined (volatile! 0)]
          (doseq [id keys]
            (vswap! examined inc)
            (let [ordinal (membership-ordinal tx b id)]
              (keyed-observe! ctx :candidate-ordinal-read (if (some? ordinal) 4 0))
              (when (some? ordinal)
                (.add candidates (int ordinal)))))
          (-> (ranked-filtered-page tx (filtered-read-config tx b d index) candidates page limit (observer-of ctx) []
                                    direction member-value-length)
              page-keys
              (assoc :candidate-keys-read @examined :sort-keys-read 0)))))))

(defn- sort-virtual-members! [^ArrayList virtual]
  (.sort virtual
         (fn [^RankedVirtualMember a ^RankedVirtualMember b]
           (Arrays/compareUnsigned ^bytes (.-member a) ^bytes (.-member b)))))

(defn- joined-page-in-session [ctx membership-name membership-d name d options missing-prefix]
  (let [{:keys [index direction page limit] :or {index :identity direction :asc page 1 limit 25}} options]
    (keyed-with-reader ctx membership-name membership-d
      (fn [tx membership-prefix _ membership-read]
        (keyed-with-reader ctx name d
          (fn [_ b _ _]
            (let [cfg (filtered-read-config tx b d index)
                  observe (observer-of ctx)
                  live (ranked-members tx cfg observe)
                  candidates (RoaringBitmap.)
                  virtual (ArrayList.)
                  examined (volatile! 0)
                  collect! (fn [id]
                             (vswap! examined inc)
                             (let [ordinal (membership-ordinal tx b id)]
                               (observe :candidate-ordinal-read (if (some? ordinal) 4 0))
                               (if (and (some? ordinal) (.contains live (int ordinal)))
                                 (.add candidates (int ordinal))
                                 (do (observe :join-default-key 0)
                                     (.add virtual (RankedVirtualMember. (key-join missing-prefix (key-id-suffix id))
                                                                         (codec/encode id)))))))]
              (if (contains? options :keys)
                (let [seen (HashSet.)]
                  (doseq [id (:keys options)
                          :when (.add seen id)]
                    (let [raw (membership-read kind-sort-data id)]
                      (observe :join-membership-probe (if raw (alength ^bytes raw) 0))
                      (when raw (collect! id))))
                  (sort-virtual-members! virtual))
                (let [identity-prefix (keyed-index-prefix membership-prefix membership-d :identity :asc)
                      offset (alength ^bytes identity-prefix)]
                  (scan tx {:prefix identity-prefix :from (ranked-after identity-prefix)}
                        (fn [_ {:keys [k v]}]
                          (observe :join-membership-entry (alength ^bytes v))
                          (collect! (keyed-entry-id membership-d :identity :asc k offset v))
                          nil)
                        nil)))
              (-> (ranked-filtered-page tx cfg candidates page limit observe virtual direction member-value-length)
                  page-keys
                  (assoc :membership-keys-examined @examined
                         :defaulted-keys (.size virtual)
                         :sort-keys-read 0)))))))))

(defn keyed-joined-page [ctx membership-name membership-d name d options]
  (let [{:keys [index direction page limit] :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options)
                   (every? #{:index :direction :page :limit :missing-value :keys} (clojure.core/keys options))
                   (contains? options :missing-value)
                   (contains? (:filtered-indexes d) index)
                   (valid-page-request? {:direction direction :page page :limit limit}))
      (throw (ex-info "Joined page requires a ranked index and an explicit missing value"
                      {:model name :options options})))
    (let [missing-prefix (keyed-sort-bytes (:missing-value options) :asc)]
      (if (::keyed/session ctx)
        (joined-page-in-session ctx membership-name membership-d name d options missing-prefix)
        (keyed-read-session [[ctx [membership-name name]]] {membership-name membership-d name d}
                            (fn [[prepared]] (keyed-joined-page prepared membership-name membership-d name d options)))))))

(defn- keyed-validate-selection! [name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix} (keys options))
                 (or (not (contains? options :prefix))
                     (and (vector? (:prefix options)) (not (contains? options :equals)))))
    (throw (ex-info "Result selection accepts :index, :direction and either :equals or a vector :prefix"
                    {:options options})))
  (when-not (and (#{:asc :desc} (:direction options :asc))
                 (declared-index? d (:index options :identity)))
    (throw (ex-info "Unknown result index or direction" {:model name :options options})))
  nil)

(defn- keyed-selection-prefix [options]
  (cond
    (contains? options :equals) (key-encode (:equals options))
    (contains? options :prefix) (key-tuple-prefix (:prefix options))))

(defn- keyed-selection [b name d options]
  (keyed-validate-selection! name d options)
  (let [selection-prefix (keyed-selection-prefix options)]
    (cond-> (keyed-index-prefix b d (:index options :identity) :asc)
      selection-prefix (key-join selection-prefix))))

(defn- detached-result [ctx read id]
  (select-keys (keyed-decode ctx :result-decoded (read kind-result id)) [:key :value]))

(defn- keyed-reduce-results [ctx name d tx b read options rf initial]
  (let [prefix (keyed-selection b name d options)
        index (:index options :identity)
        offset (alength ^bytes (keyed-index-prefix b d index :asc))
        step (fn [acc id]
               (keyed-observe! ctx :index-entry 0)
               (rf acc (detached-result ctx read id)))]
    (cond
      (not (keyed-fits-prefix? prefix))
      initial

      (= :desc (:direction options :asc))
      (let [cfg (keyed-index-config b d index :asc)
            selected? (some? (keyed-selection-prefix options))]
        (ranked-desc-reduce tx cfg member-value-length
                            (if selected? prefix (ranked-after prefix))
                            (when selected? (key-prefix-end prefix))
                            256
                            (fn [acc ^objects item]
                              (step acc (keyed-entry-id d index :asc (aget item 0) 0 (aget item 1))))
                            initial))

      :else
      (scan tx {:prefix prefix :from (ranked-after prefix)}
            (fn [acc {:keys [k v]}] (step acc (keyed-entry-id d index :asc k offset v)))
            initial))))

(defn keyed-values [ctx name d options]
  (keyed-validate-selection! name d options)
  (reify clojure.lang.IReduceInit
    (reduce [_ rf initial]
      (keyed-with-reader ctx name d
        (fn [tx b d read]
          (keyed-reduce-results ctx name d tx b read options rf initial))))))

(defn- keyed-page-prefixes [name d options]
  (let [selection-options (select-keys options [:index :direction :equals :prefix])]
    (keyed-validate-selection! name d selection-options)
    (if-not (contains? options :prefixes)
      [(keyed-selection-prefix selection-options)]
      (do (when-not (and (vector? (:prefixes options))
                         (every? vector? (:prefixes options))
                         (not-any? #(contains? options %) [:equals :prefix]))
            (throw (ex-info "Page prefixes must be vectors and cannot be combined with :equals or :prefix"
                            {:options options})))
          (mapv #(keyed-selection-prefix (assoc selection-options :prefix %))
                (distinct (:prefixes options)))))))

(defn- lower-key [^bytes a ^bytes b]
  (if (neg? (Arrays/compareUnsigned a b)) a b))

(defn- upper-key-or-unbounded [^bytes a ^bytes b]
  (when (and a b)
    (if (neg? (Arrays/compareUnsigned a b)) b a)))

(defn- prefix-rank-interval [tx cfg ^bytes level-zero-prefix total prefix]
  (when (or (nil? prefix) (keyed-fits-prefix? cfg prefix))
    (let [start (if prefix (ranked-rank tx cfg prefix false) 0)
          prefix-end (when prefix (key-prefix-end prefix))
          end (cond
                (nil? prefix) total
                prefix-end (ranked-rank tx cfg prefix-end false)
                :else (ranked-size tx cfg))]
      (when (< start end)
        [start
         end
         (if prefix (key-join level-zero-prefix prefix) (ranked-after level-zero-prefix))
         (when prefix-end (key-join level-zero-prefix prefix-end))]))))

(defn- keyed-page-ranges [tx cfg prefixes total]
  (reduce (fn [ranges [start end lo hi :as interval]]
            (if-let [[previous-start previous-end previous-lo previous-hi] (peek ranges)]
              (if (<= start previous-end)
                (conj (pop ranges) [previous-start
                                    (max end previous-end)
                                    (lower-key lo previous-lo)
                                    (upper-key-or-unbounded hi previous-hi)])
                (conj ranges interval))
              [interval]))
          []
          (sort-by first (let [level-zero-prefix (ranked-prefix cfg 0)] (keep #(prefix-rank-interval tx cfg level-zero-prefix total %) prefixes)))))

(defn- keyed-read-desc-page-ranges [ctx navigation-tx cfg value-length read ranges offset limit id-of]
  (loop [ranges (seq (reverse ranges)) offset offset entries []]
    (if-let [[start end lo hi] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [items (ranked-desc-items (ranked-range-navigator navigation-tx cfg lo hi start end)
                                         value-length offset (min (- size offset) (- limit (count entries))))
                entries (reduce (fn [entries ^objects item]
                                  (keyed-observe! ctx :index-entry 0)
                                  (conj entries (detached-result ctx read (id-of (aget item 0) (aget item 1)))))
                                entries
                                items)]
            (if (= limit (count entries))
              entries
              (recur (next ranges) 0 entries)))))
      entries)))

(defn- keyed-read-page-ranges [ctx tx navigation-tx cfg read ranges offset limit id-of]
  (loop [ranges (seq ranges) offset offset entries []]
    (if-let [[start end] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [stop (+ (count entries) (min (- size offset) (- limit (count entries))))
                first-member (ranked-get-nth navigation-tx cfg (+ start offset))
                entries (scan tx {:prefix (ranked-prefix cfg 0) :from (ranked-pack cfg 0 first-member)}
                              (fn [entries {:keys [k v]}]
                                (keyed-observe! ctx :index-entry 0)
                                (let [entries (conj entries (detached-result ctx read (id-of k v)))]
                                  (if (= stop (count entries)) (reduced entries) entries)))
                              entries)]
            (if (= limit (count entries))
              entries
              (recur (next ranges) 0 entries)))))
      entries)))

(defn- keyed-navigation-reader
  ([ctx tx]
   (keyed-navigation-reader ctx tx nil))
  ([ctx tx visited-count]
   (assoc tx :reduce
          (fn [bounds rf initial]
            ((:reduce tx) bounds
                          (fn [acc entry]
                            (keyed-observe! ctx :rank-entry 0)
                            (when visited-count (vswap! visited-count inc))
                            (rf acc entry))
                          initial)))))

(defn keyed-page [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix :prefixes :page :limit} (keys options))
                 (pos-int? (:page options 1))
                 (pos-int? (:limit options 25)))
    (throw (ex-info "Invalid keyed-fold page" {:options options})))
  (let [prefixes (keyed-page-prefixes name d options)]
    (keyed-with-reader ctx name d
      (fn [tx b d read]
        (let [index (:index options :identity)
              cfg (keyed-read-config tx b d (keyed-index-config b d index :asc) index)
              rank-examined (volatile! 0)
              navigation-tx (keyed-navigation-reader ctx tx rank-examined)
              ranges (keyed-page-ranges navigation-tx cfg prefixes (or (keyed-read-meta tx (keyed-count-key b)) 0))
              total (reduce (fn [total [start end]] (+ total (- end start))) 0 ranges)
              limit (:limit options 25)
              pages (max 1 (quot (+ total (dec limit)) limit))
              page (min (:page options 1) pages)
              offset (* (dec page) limit)
              member-offset (alength ^bytes (ranked-prefix cfg 0))
              entries (if (= :desc (:direction options :asc))
                        (keyed-read-desc-page-ranges ctx navigation-tx cfg member-value-length read ranges offset limit
                                                     #(keyed-entry-id d index :asc %1 0 %2))
                        (keyed-read-page-ranges ctx tx navigation-tx cfg read ranges offset limit
                                                #(keyed-entry-id d index :asc %1 member-offset %2)))]
          {:entries entries
           :total total
           :total-pages pages
           :page page
           :page-size limit
           :rows-examined (count entries)
           :rank-entries-examined @rank-examined
           :results-decoded (count entries)
           :progress (keyed-public-progress (keyed-read-meta tx (keyed-progress-key b)))})))))

(defn- keyed-group-bounds [tx cfg value direction]
  (let [prefix (keyed-sort-bytes value direction)
        start (ranked-rank tx cfg prefix false)
        end (if-let [prefix-end (key-prefix-end prefix)]
              (ranked-rank tx cfg prefix-end false)
              (ranked-size tx cfg))]
    [start end]))

(defn- keyed-identity-count [tx b d value]
  (let [prefix (key-join (keyed-index-prefix b d :identity :asc) (keyed-sort-bytes value :asc))]
    (if (keyed-fits-prefix? prefix)
      (scan tx {:prefix prefix} (fn [n _] (inc n)) 0)
      0)))

(defn- identity-group-prefix [b d]
  (ranked-prefix (keyed-index-config b d :identity :asc) group-count-level))

(defn- identity-groups-of-values [ctx tx b d values]
  (let [group-prefix (identity-group-prefix b d)]
    (keyed-reduce-distinct (fn [groups value]
                             (keyed-observe! ctx :index-group 0)
                             (key-checked (key-join group-prefix (key-encode value)))
                             (let [n (keyed-identity-count tx b d value)]
                               (if (pos? n)
                                 (conj groups {:value value :total n})
                                 groups)))
                           []
                           values)))

(defn- all-identity-groups [ctx tx b d]
  (let [prefix (keyed-index-prefix b d :identity :asc)
        offset (alength ^bytes prefix)
        open-group (object-array 2)
        open-value 0
        open-total 1
        close (fn [groups]
                (if (aget open-group open-value)
                  (do (keyed-observe! ctx :index-group 0)
                      (conj groups {:value (aget open-group open-value) :total (aget open-group open-total)}))
                  groups))]
    (close (scan tx {:prefix prefix :from (ranked-after prefix)}
                 (fn [groups {:keys [^bytes k]}]
                   (let [value (member-read! k (doto (long-array 2) (aset 0 (long offset)) (aset 1 (alength k))) 0 true)]
                     (if (= value (aget open-group open-value))
                       (do (aset open-group open-total (num (inc (long (aget open-group open-total)))))
                           groups)
                       (let [groups (close groups)]
                         (aset open-group open-value value)
                         (aset open-group open-total (num 1))
                         groups))))
                 []))))

(defn- keyed-identity-groups [ctx tx b d values]
  (if (some? values)
    (identity-groups-of-values ctx tx b d values)
    (all-identity-groups ctx tx b d)))

(defn keyed-index-groups
  ([ctx name d index]
   (keyed-index-groups ctx name d index nil))
  ([ctx name d index values]
   (keyed-validate-selection! name d {:index index})
   (keyed-with-reader ctx name d
     (fn [tx b _ _read]
       (let [group-prefix (ranked-prefix (keyed-index-config b d index :asc) group-count-level)]
         (cond
           (= :identity index)
           (keyed-identity-groups ctx tx b d values)

           (some? values)
           (keyed-reduce-distinct (fn [groups value]
                                    (keyed-observe! ctx :index-group 0)
                                    (if-let [group (keyed-read-meta tx (key-checked (key-join group-prefix (key-encode value))))]
                                      (conj groups group)
                                      groups))
                                  []
                                  values)

           :else
           (scan tx {:prefix group-prefix}
                 (fn [groups {:keys [v]}]
                   (keyed-observe! ctx :index-group 0)
                   (conj groups (codec/decode v)))
                 [])))))))

(defn- keyed-validate-group! [name d selection]
  (keyed-validate-selection! name d selection)
  (when-not (contains? selection :equals)
    (throw (ex-info "Index composition requires an equality group" {:model name :selection selection}))))

(defn keyed-index-keys [ctx name d options]
  (let [selection (select-keys options [:index :direction :equals])]
    (when-not (and (map? options)
                   (every? #{:index :direction :equals :offset :limit} (keys options))
                   (nat-int? (:offset options 0))
                   (pos-int? (:limit options 25)))
      (throw (ex-info "Invalid index identity slice" {:options options})))
    (keyed-validate-group! name d selection)
    (keyed-with-reader ctx name d
      (fn [tx b _ _read]
        (let [index (:index options :identity)
              value (:equals options)
              cfg (keyed-read-config tx b d (keyed-index-config b d index :asc) index)
              offset (:offset options 0)
              limit (:limit options 25)
              level-zero-prefix (ranked-prefix cfg 0)
              group-prefix (key-join level-zero-prefix (keyed-sort-bytes value :asc))
              start (when (keyed-fits-prefix? group-prefix)
                      (if (zero? offset)
                        group-prefix
                        (let [navigation (keyed-navigation-reader ctx tx)
                              [group-start group-end] (keyed-group-bounds navigation cfg value :asc)]
                          (when (< offset (- group-end group-start))
                            (ranked-pack cfg 0 (ranked-get-nth navigation cfg (+ group-start offset)))))))]
          (if-not start
            []
            (let [member-offset (alength ^bytes level-zero-prefix)]
              (scan tx {:prefix group-prefix :from start}
                    (fn [ids {:keys [k v]}]
                      (keyed-observe! ctx :index-entry 0)
                      (let [ids (conj ids (keyed-entry-id d index :asc k member-offset v))]
                        (if (= limit (count ids)) (reduced ids) ids)))
                    []))))))))

(defn keyed-index-rank [ctx name d selection id]
  (keyed-validate-group! name d selection)
  (check-instance-key! name id)
  (keyed-with-reader ctx name d
    (fn [raw-tx b _ _read]
      (let [tx (keyed-navigation-reader ctx raw-tx)
            index (:index selection :identity)
            value (:equals selection)
            cfg (keyed-read-config tx b d (keyed-index-config b d index :asc) index)]
        (if (and (keyed-compact-identity? d index) (not= value (str id)))
          {:rank (if (and (<= (alength (key-join (identity-group-prefix b d) (key-encode value))) max-key-bytes)
                          (string? value)
                          (pos? (compare (str id) value)))
                   (keyed-identity-count raw-tx b d value)
                   0)
           :present? false}
          (let [member (keyed-index-member d index :asc value id)
                group-start (ranked-rank tx cfg (keyed-sort-bytes value :asc) false)]
            {:rank (- (ranked-rank tx cfg member false) group-start)
             :present? (ranked-contains? tx cfg member)}))))))

(defn query-fail [message data]
  (throw (ex-info message (assoc data :type :grain.read-query/invalid))))

(defn- query-unsupported! [message data hint]
  (throw (ex-info (str message ". " hint)
                  (assoc data :type :grain.read-query/unsupported :hint hint))))

(defn query-normalize [value]
  (cond
    (nil? value) ""
    (string? value) (.toLowerCase ^String value Locale/ROOT)
    (keyword? value) (name value)
    :else value))

(defn- keyword-fields? [fields]
  (and (vector? fields) (seq fields) (every? keyword? fields)))

(defn query-order-key [fields]
  (when-not (keyword-fields? fields)
    (query-fail "Order fields must be a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result]
               (let [values (mapv #(query-normalize (get result %)) fields)]
                 (if (= 1 (count values)) (first values) values)))
    {::read-query/order fields}))

(defn query-search-text [fields]
  (when-not (keyword-fields? fields)
    (query-fail "Search text requires a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result] (str/join " " (map #(get result %) fields)))
    {::read-query/text {:fields fields}}))

(defn query-field-ref [expression]
  (when (qualified-keyword? expression)
    [(keyword (namespace expression)) (keyword (name expression))]))

(defn- expression-aliases [aliases expr]
  (if-let [[alias _] (query-field-ref expr)]
    (do (when-not (aliases alias)
          (query-fail "Unknown result alias" {:expression expr}))
        #{alias})
    (if-not (vector? expr)
      (query-fail "Expected a field reference or expression" {:expression expr})
      (case (first expr)
        (:id :value) (do (when-not (and (= 2 (count expr)) (aliases (second expr)))
                           (query-fail "Unknown result binding" {:expression expr}))
                         #{(second expr)})
        :param (do (when-not (and (= 2 (count expr)) (keyword? (second expr)))
                     (query-fail "Invalid parameter reference" {:expression expr}))
                   #{})
        :call (do (when-not (and (<= 2 (count expr)) (fn? (second expr)))
                    (query-fail "Calculation requires a function" {:expression expr}))
                  (into #{} (mapcat #(expression-aliases aliases %)) (drop 2 expr)))
        (query-fail "Unknown read-query expression" {:expression expr})))))

(defn- valid-relation? [relation]
  (and (map? relation)
       (contains? relation :key)
       (every? #{:model :key :missing :validate} (keys relation))
       (or (nil? (:validate relation)) (fn? (:validate relation)))
       (let [missing (:missing relation :error)]
         (or (= :error missing)
             (and (vector? missing) (= 2 (count missing)) (= :default (first missing)))))))

(defn- id-binding? [key-expression]
  (and (vector? key-expression) (= :id (first key-expression))))

(defn- validate-relation! [references alias relation]
  (when-not (valid-relation? relation)
    (query-fail "Invalid related-result declaration" {:alias alias :relation relation}))
  (when-not (or (query-field-ref (:key relation)) (id-binding? (:key relation)))
    (query-fail "Relationship keys must reference an instance ID or field" {:alias alias}))
  (references (:key relation)))

(defn- dependency-ordered-aliases [references root related]
  (loop [ordered [root] remaining related]
    (if (empty? remaining)
      ordered
      (let [ready (filterv #(every? (set ordered) (references (:key (val %)))) remaining)]
        (when (empty? ready)
          (query-fail "Cyclic related-result definitions" {:aliases (keys remaining)}))
        (recur (into ordered (map key) ready) (apply dissoc remaining (map key ready)))))))

(defn- binding-models [registry bindings]
  (into {}
        (map (fn [[alias {:keys [model]}]]
               (let [d (get registry model)]
                 (when-not (or (:key d) (:route d))
                   (query-fail "Expected registered keyed model" {:alias alias :model model}))
                 [model d])))
        bindings))

(defn- query-parse-definition [definition registry]
  (when-not (and (map? definition)
                 (every? #{:from :related :search :orders} (keys definition))
                 (map? (:from definition))
                 (= 1 (count (:from definition))))
    (query-fail "Read query requires one root and only from/related/search/orders" {:definition definition}))
  (let [[root model] (first (:from definition))
        related (:related definition {})
        aliases (set (cons root (keys related)))
        orders (:orders definition)
        search (:search definition [])
        references (partial expression-aliases aliases)]
    (when-not (and (map? related)
                   (every? #(and (simple-keyword? %) (not= :key %)) aliases)
                   (not (contains? related root))
                   (map? orders)
                   (seq orders)
                   (every? simple-keyword? (keys orders))
                   (vector? search))
      (query-fail "Invalid read-query bindings or orders" {:definition definition}))
    (doseq [[alias relation] related]
      (validate-relation! references alias relation))
    (doseq [field search]
      (when-not (query-field-ref field)
        (query-fail "Search requires result-field references" {:field field}))
      (references field))
    (doseq [[order expressions] orders]
      (when-not (and (vector? expressions) (seq expressions))
        (query-fail "Ordering requires a nonempty expression vector" {:order order}))
      (doseq [expr expressions]
        (references expr)))
    (let [bindings (assoc related root {:model model})
          models (binding-models registry bindings)
          ordered-aliases (dependency-ordered-aliases references root related)]
      {:definition definition
       :root root
       :model model
       :bindings bindings
       :aliases ordered-aliases
       :models models
       :orders orders
       :search search})))

(defn query-subscriptions [plan]
  (into {} (map (fn [[model d]] [model (:version d 1)])) (:models plan)))

(defn query-resolve-value [api ctx {:keys [bindings]} alias id ^HashMap shared]
  (let [{:keys [model missing validate] :or {missing :error}} (bindings alias)
        address [alias id]]
    (if (.containsKey shared address)
      (.get shared address)
      (let [absent (Object.)
            found (if (nil? id) absent ((:value api) ctx model id absent))
            value (cond
                    (not (identical? absent found)) found
                    (= :error missing) (do (when validate (validate id nil))
                                           (throw (ex-info "Required related result is missing"
                                                           {:type :grain.read-query/missing
                                                            :alias alias
                                                            :model model
                                                            :key id})))
                    :else (second missing))]
        (when validate (validate id value))
        (.put shared address value)
        value))))

(defn- shares-root-identity? [plan alias]
  (or (= alias (:root plan))
      (id-binding? (get-in plan [:bindings alias :key]))))

(defn query-row-reader [api ctx plan params ^HashMap shared id & [seed]]
  (let [row-values (HashMap.)
        root (:root plan)]
    (letfn [(binding-key [alias]
              (if (= root alias)
                id
                (evaluate (get-in plan [:bindings alias :key]))))
            (seeded-result [alias relation]
              (let [value (get seed alias)]
                (when-let [validate (:validate relation)]
                  (validate (binding-key alias) value))
                value))
            (result [alias]
              (if (.containsKey row-values alias)
                (.get row-values alias)
                (let [relation (get-in plan [:bindings alias])
                      value (if (contains? seed alias)
                              (seeded-result alias relation)
                              (let [cache (if (shares-root-identity? plan alias) (HashMap.) shared)]
                                (query-resolve-value api ctx plan alias (binding-key alias) cache)))]
                  (.put row-values alias value)
                  value)))
            (evaluate [expr]
              (if-let [[alias field] (query-field-ref expr)]
                (get (result alias) field)
                (case (first expr)
                  :id (binding-key (second expr))
                  :value (result (second expr))
                  :param (if (contains? params (second expr))
                           (get params (second expr))
                           (query-fail "Missing calculation parameter" {:parameter (second expr)}))
                  :call (apply telemetry/consumer-call (second expr) (mapv evaluate (drop 2 expr))))))]
      {:evaluate evaluate
       :row (fn [] (into {:key id} (map (fn [alias] [alias (result alias)])) (:aliases plan)))})))

(defn- binding-indexes [plan alias]
  (get-in plan [:models (get-in plan [:bindings alias :model]) :indexes]))

(defn- query-order-index [plan alias fields]
  (some (fn [[index extract]] (when (= fields (::read-query/order (meta extract))) index))
        (binding-indexes plan alias)))

(defn- query-equality-index [plan alias field]
  (some (fn [[index extract]] (when (= field extract) index))
        (binding-indexes plan alias)))

(defn- query-same-key? [plan alias]
  (or (= alias (:root plan))
      (= [:id (:root plan)] (get-in plan [:bindings alias :key]))))

(defn- query-group-source [plan alias]
  (when-let [[parent field] (query-field-ref (get-in plan [:bindings alias :key]))]
    (when (query-same-key? plan parent)
      (when-let [index (query-equality-index plan parent field)]
        {:parent parent :model (get-in plan [:bindings parent :model]) :index index}))))

(defn- query-observe [ctx event]
  (when-let [observer (:read-model/observer ctx)]
    (observer (assoc event :bytes 0))))

(defn- query-groups [api ctx plan alias shared]
  (when-let [source (query-group-source plan alias)]
    (let [entries ((:index-groups api) ctx (:model source) (:index source))]
      (doseq [{:keys [value]} entries]
        (query-resolve-value api ctx plan alias value shared))
      (query-observe ctx {:operation :related-groups :count (count entries)})
      (assoc source :entries entries))))

(defn- query-union-start [groups offset]
  (loop [bounds (mapv #(assoc % :lo 0 :hi (:total %)) groups)]
    (let [{:keys [lo hi slice]} (apply max-key #(- (:hi %) (:lo %)) bounds)]
      (when-not (< lo hi)
        (query-fail "Group rank search lost its target" {:offset offset}))
      (let [pivot (first (slice (+ lo (quot (- hi lo) 2)) 1))
            ranks (mapv #((:rank %) pivot) bounds)
            ids-before-pivot (reduce + (map :rank ranks))]
        (cond
          (= ids-before-pivot offset) pivot
          (< ids-before-pivot offset) (recur (mapv (fn [group rank] (assoc group :lo (+ (:rank rank) (if (:present? rank) 1 0))))
                                                   bounds ranks))
          :else (recur (mapv (fn [group rank] (assoc group :hi (:rank rank))) bounds ranks)))))))

(def ^:private ascending-id-string-order
  (reify Comparator
    (compare [_ a b] (compare (str (:id a)) (str (:id b))))))

(defn- query-union-page [groups offset limit]
  (if (= 1 (count groups))
    ((:slice (first groups)) offset limit)
    (let [pivot (query-union-start groups offset)
          heap (PriorityQueue. 11 ascending-id-string-order)
          offer! (fn [group offset]
                   (when (< offset (:total group))
                     (.add heap {:id (first ((:slice group) offset 1)) :group group :offset offset})))]
      (doseq [group groups]
        (offer! group (:rank ((:rank group) pivot))))
      (loop [ids []]
        (if (or (= limit (count ids)) (.isEmpty heap))
          ids
          (let [{:keys [id group offset]} (.poll heap)]
            (when (< (inc (count ids)) limit)
              (offer! group (inc offset)))
            (recur (conj ids id))))))))

(defn query-page-info [total {:keys [page limit]}]
  (let [pages (max 1 (quot (+ total (dec limit)) limit))]
    {:total total :total-pages pages :page (min page pages) :page-size limit}))

(defn- equality-group [api ctx model index {:keys [value total]}]
  (let [selection {:index index :equals value}]
    {:total total
     :slice (fn [offset limit] ((:index-keys api) ctx model (assoc selection :offset offset :limit limit)))
     :rank (fn [id] ((:index-rank api) ctx model selection id))}))

(defn- page-across-buckets [api ctx model index buckets offset limit]
  (loop [buckets (seq buckets) offset offset remaining limit ids []]
    (if (or (nil? buckets) (zero? remaining))
      ids
      (let [members (val (first buckets))
            bucket-total (reduce + (map :total members))]
        (if (>= offset bucket-total)
          (recur (next buckets) (- offset bucket-total) remaining ids)
          (let [n (min remaining (- bucket-total offset))]
            (recur (next buckets) 0 (- remaining n)
                   (into ids (query-union-page (mapv #(equality-group api ctx model index %) members) offset n)))))))))

(defn- query-grouped-page [api ctx plan alias field shared options]
  (when-let [{:keys [entries model index]} (query-groups api ctx plan alias shared)]
    (when ((:same-members? api) ctx (:model plan) model)
      (let [buckets (->> entries
                         (group-by #(query-normalize (get (query-resolve-value api ctx plan alias (:value %) shared) field)))
                         (sort-by key (if (= :desc (:direction options)) #(compare %2 %1) compare)))
            info (query-page-info (reduce + 0 (map :total entries)) options)
            ids (page-across-buckets api ctx model index buckets
                                     (* (dec (:page info)) (:limit options))
                                     (:limit options))]
        (assoc info :keys ids :strategy :related-groups :related-groups (count entries))))))

(defn- query-text-segment [plan fields]
  (let [alias (first (query-field-ref (first fields)))
        names (mapv (comp second query-field-ref) fields)
        model (get-in plan [:bindings alias :model])
        segment (some (fn [[index extract]]
                        (when (= {:fields names} (::read-query/text (meta extract)))
                          {:alias alias :model model :index index}))
                      (get-in plan [:models model :searches]))]
    (when-not segment
      (let [extractor (list 'rmp/search-text names)]
        (query-unsupported! (str "Search fields " (pr-str fields) " on model " model
                                 " need a matching search-text extractor")
                            {:path [:search] :alias alias :model model :fields fields
                             :suggested-extractor extractor}
                            (str "Declare an entry under :searches using " (pr-str extractor)
                                 ". The fields and their order must match. Bump :version when changing stored extraction."))))
    segment))

(defn- query-equality-guidance [plan alias]
  (let [expression (get-in plan [:bindings alias :key])
        [parent field] (query-field-ref expression)
        model (get-in plan [:bindings parent :model])]
    (if (and parent (query-same-key? plan parent))
      {:index-model model
       :suggested-extractor field
       :hint (str "Declare an exact equality index on " model ", for example "
                  (pr-str {:indexes {:relation field}})
                  ". Use the raw keyword extractor to preserve IDs. Bump :version when adding the index.")}
      {:hint (str "Relationship " alias " uses " (pr-str expression)
                  ". Indexed related search and field ordering require a field lookup from the root "
                  (:root plan) " or a binding with the same instance ID. Change that relationship or exclude its fields.")})))

(defn- check-search-segment-shape! [plan parts aliases]
  (when-not (<= 1 (count parts) 2)
    (query-unsupported! (str "Search has unsupported alias segments " (pr-str aliases))
                        {:path [:search] :model (:model plan) :fields (:search plan)
                         :aliases aliases :segments (count parts)}
                        (str "Keep fields for root " (:root plan)
                             " together and first, optionally followed by fields from one related binding.")))
  (when-not (= (:root plan) (first aliases))
    (query-unsupported! (str "Indexed search must start with root text from " (:root plan)
                             ", but starts with " (first aliases))
                        {:path [:search] :model (:model plan) :fields (:search plan)
                         :root (:root plan) :aliases aliases}
                        "Place the root's search fields first; related-only search is not supported.")))

(defn- check-related-search-segment! [plan related-fields related]
  (let [alias (:alias related)
        missing (get-in plan [:bindings alias :missing])]
    (when (and (vector? missing) (some? (second missing)))
      (query-unsupported! (str "Search relationship " alias " on model " (:model related)
                               " has a non-nil missing default " (pr-str missing))
                          {:path [:related alias :missing] :alias alias :model (:model related)
                           :fields related-fields :missing missing}
                          "Default text is absent from the stored search index. Remove these related fields from :search, or use :error or [:default nil] if the domain permits."))
    (when (nil? (query-group-source plan alias))
      (let [{:keys [hint] :as guidance} (query-equality-guidance plan alias)]
        (query-unsupported! (str "Search relationship " alias " on model " (:model related)
                                 " requires indexed equality membership")
                            (merge guidance {:path [:related alias :key] :alias alias :model (:model related)
                                             :fields related-fields :expression (get-in plan [:bindings alias :key])})
                            hint)))))

(defn- query-search-segments [plan]
  (let [parts (mapv vec (partition-by (comp first query-field-ref) (:search plan)))
        aliases (mapv (comp first query-field-ref first) parts)]
    (check-search-segment-shape! plan parts aliases)
    (let [segments (mapv #(query-text-segment plan %) parts)]
      (when-let [related (second segments)]
        (check-related-search-segment! plan (second parts) related))
      segments)))

(defn- query-computed-order? [expressions]
  (boolean (some #(and (vector? %) (= :call (first %))) expressions)))

(defn- query-unsupported-order [plan order expressions reason]
  (let [refs (mapv query-field-ref expressions)
        alias (when (and (every? some? refs) (apply = (map first refs))) (ffirst refs))
        model (get-in plan [:bindings alias :model])
        fields (mapv second refs)
        guidance (cond
                   (= reason :different-membership)
                   {:hint "The index source must contain exactly the root's included IDs. Restore membership agreement, or use an explicit :call calculation, which can evaluate every candidate."}

                   (and alias (query-same-key? plan alias))
                   (let [extractor (list 'rmp/order-key fields)]
                     {:index-model model
                      :suggested-extractor extractor
                      :hint (str "Declare an entry under :indexes on " model " using " (pr-str extractor)
                                 ". A raw keyword or arbitrary function does not supply order-field metadata. Bump :version when changing stored extraction.")})

                   (and alias (= 1 (count fields)))
                   (query-equality-guidance plan alias)

                   :else
                   {:hint "Use fields from one supported indexed binding, or an explicit :call calculation. A calculation can evaluate every candidate."})]
    (query-unsupported! (str "Unsupported field ordering " order " for " (pr-str expressions)
                             (when model (str " on model " model)))
                        (merge guidance {:path [:orders order] :order order :alias alias :model model
                                         :fields expressions :expressions expressions :reason reason})
                        (:hint guidance))))

(defn- supported-field-order? [plan expressions]
  (let [refs (mapv query-field-ref expressions)
        alias (ffirst refs)
        fields (mapv second refs)]
    (and (every? some? refs)
         (every? #(= alias (first %)) refs)
         (or (and (query-same-key? plan alias) (query-order-index plan alias fields))
             (and (= 1 (count fields)) (query-group-source plan alias))))))

(defn query-compile-definition [definition registry]
  (let [plan (query-parse-definition definition registry)
        segments (when (seq (:search plan)) (query-search-segments plan))]
    (doseq [[order expressions] (:orders plan)
            :when (not (query-computed-order? expressions))]
      (when-not (supported-field-order? plan expressions)
        (query-unsupported-order plan order expressions :missing-index-support)))
    (assoc plan :search-segments segments)))

(def ^:private ^:const ascii-space 32)

(defn- query-matching-keys [api ctx plan text shared]
  (let [[root related] (:search-segments plan)]
    (when-not root
      (throw (ex-info "This read query does not declare search fields" {:type :grain.read-query/unsupported})))
    (let [source (when related (query-groups api ctx plan (:alias related) shared))
          totals (into {} (map (juxt :value :total)) (:entries source))
          group-members (memoize (fn [group]
                                   (if-let [total (get totals group)]
                                     ((:index-keys api) ctx (:model source) {:index (:index source) :equals group :limit total})
                                     [])))
          search (fn [segment text mode]
                   ((:search-keys api) ctx (:model segment) {:index (:index segment) :query text :mode mode}))
          root-ids-of-groups (fn [groups] (into #{} (mapcat group-members) groups))
          found (HashSet. ^java.util.Collection (search root text :contains))]
      (when related
        (.addAll found ^java.util.Collection (root-ids-of-groups (search related text :contains)))
        (let [^ints points (.toArray (.codePoints ^String text))]
          (doseq [split (range 1 (alength points))
                  :when (= ascii-space (aget points (dec split)))
                  :let [root-text (String. points 0 (int (dec split)))
                        related-text (String. points (int split) (int (- (alength points) split)))
                        related-hits (root-ids-of-groups (search related related-text :prefix))]
                  :when (seq related-hits)
                  id (search root root-text :suffix)
                  :when (contains? related-hits id)]
            (.add found id))))
      found)))

(defn- query-scan-keys [api ctx plan]
  (into [] (map :key) ((:values api) ctx (:model plan))))

(defn- query-computed-page [api ctx plan expressions keys shared options]
  (let [keys (or keys (query-scan-keys api ctx plan))
        computed-order (fn [id _]
                         (let [evaluate (:evaluate (query-row-reader api ctx plan (:params options) shared id))]
                           (mapv #(query-normalize (evaluate %)) expressions)))]
    (assoc ((:key-page api) ctx (:model plan) keys
                            (assoc (select-keys options [:page :limit :direction]) :index :identity :sort-value computed-order))
           :strategy :computed
           :candidates-examined (count keys))))

(defn- page-request [options]
  (select-keys options [:page :limit :direction]))

(defn- indexed-order-page [api ctx model keys ranked? selection alias]
  (if keys
    (assoc ((if ranked? (:filtered-key-page api) (:key-page api)) ctx model keys selection)
           :strategy (if ranked? :ranked-matches :indexed-matches))
    (let [page ((:page api) ctx model selection)]
      (assoc (dissoc page :entries)
             :keys (mapv :key (:entries page))
             :strategy :indexed
             :seeds (into {} (map (fn [{:keys [key value]}] [key {alias value}])) (:entries page))))))

(defn- related-matches-page [api ctx plan alias field keys shared options expressions]
  (let [{:keys [model index]} (query-group-source plan alias)]
    (if ((:same-members? api) ctx (:model plan) model)
      (assoc ((:key-page api) ctx model keys
                              (assoc (page-request options)
                                     :index index
                                     :sort-value (fn [_ related-id]
                                                   (query-normalize (get (query-resolve-value api ctx plan alias related-id shared)
                                                                         field)))))
             :strategy :related-matches)
      (query-unsupported-order plan (:order options) expressions :different-membership))))

(defn- defaulted-join? [plan alias index ranked?]
  (let [missing (get-in plan [:bindings alias :missing])]
    (and index ranked? (query-same-key? plan alias)
         (vector? missing)
         (= :default (first missing)))))

(defn- joined-order-page [api ctx plan model alias index keys selection]
  (let [extract (get-in plan [:models model :indexes index])
        default (second (get-in plan [:bindings alias :missing]))]
    (assoc ((:joined-page api) ctx (:model plan) model
                               (cond-> (assoc selection :missing-value (extract default))
                                 keys (assoc :keys keys)))
           :strategy :joined)))

(defn- query-ordered-page [api ctx plan keys shared options]
  (let [expressions (get (:orders plan) (:order options))
        refs (mapv query-field-ref expressions)
        aliases (set (map first refs))
        alias (when (and (every? some? refs) (= 1 (count aliases))) (first aliases))
        fields (mapv second refs)
        single-field? (= 1 (count fields))
        model (get-in plan [:bindings alias :model])
        index (when alias (query-order-index plan alias fields))
        ranked? (contains? (get-in plan [:models model :filtered-indexes]) index)
        selection (assoc (page-request options) :index index)
        same-members? (and index
                           (query-same-key? plan alias)
                           (or (= alias (:root plan)) ((:same-members? api) ctx (:model plan) model)))]
    (cond
      (and index same-members?)
      (indexed-order-page api ctx model keys ranked? selection alias)

      (and (nil? keys) alias single-field? (query-group-source plan alias))
      (or (query-grouped-page api ctx plan alias (first fields) shared options)
          (query-unsupported-order plan (:order options) expressions :different-membership))

      (and keys alias single-field? (query-group-source plan alias))
      (related-matches-page api ctx plan alias (first fields) keys shared options expressions)

      (defaulted-join? plan alias index ranked?)
      (joined-order-page api ctx plan model alias index keys selection)

      (query-computed-order? expressions)
      (query-computed-page api ctx plan expressions keys shared options)

      :else
      (query-unsupported-order plan (:order options) expressions :different-membership))))

(def ^:private query-request-defaults
  {:params {} :search "" :direction :asc :page 1 :limit 25})

(defn- valid-query-options? [plan options]
  (and (map? (:params options))
       (string? (:search options))
       (contains? (:orders plan) (:order options))
       (#{:asc :desc} (:direction options))
       (pos-int? (:page options))
       (pos-int? (:limit options))))

(defn- run-query [api plan options text prepared]
  (let [shared (HashMap.)
        keys (when (seq text) (query-matching-keys api prepared plan text shared))
        selected (query-ordered-page api prepared plan keys shared options)
        rows (mapv (fn [id]
                     ((:row (query-row-reader api prepared plan (:params options) shared id (get-in selected [:seeds id])))))
                   (:keys selected))]
    (query-observe prepared {:operation :read-query-plan
                             :strategy (:strategy selected)
                             :candidates-examined (:candidates-examined selected 0)})
    (assoc (select-keys selected [:total :total-pages :page :page-size])
           :rows rows
           :work (dissoc selected :keys :seeds :total :total-pages :page :page-size))))

(defn- short-search? [^String text]
  (and (seq text) (< (.codePointCount text 0 (count text)) 3)))

(defn query-execute [api ctx plan request]
  (when-not (and (map? request) (every? #{:params :search :order :direction :page :limit} (keys request)))
    (query-fail "Unknown read-query request option" {:request request}))
  (let [options (merge query-request-defaults request)
        _ (when-not (valid-query-options? plan options)
            (query-fail "Invalid read-query page, ordering or parameters" {:request request}))
        text (query-normalize (str/trim (:search options)))
        options (assoc options :search text)]
    (if (short-search? text)
      {:rows []
       :total 0
       :total-pages 1
       :page 1
       :page-size (:limit options)
       :search-error "Enter at least 3 characters to search."
       :work {:strategy :unsupported-short-search}}
      ((:read-session api) ctx (keys (:models plan)) #(run-query api plan options text %)))))
