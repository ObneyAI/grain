(ns ai.obney.grain.read-model-processor-v2.core
  "Read-model materialization, transactional indexes and declarative queries."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv]
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
           [org.roaringbitmap RoaringBitmap]))

;; ---------------------------------------------------------------------------
;; In-process cache
;; ---------------------------------------------------------------------------

(def ^:private l1-default-max-entries 10000)

(def ^:private l1-cache* (atom {}))

(defn l1-get-entry
  "Retrieve an L1 cache entry. Updates :accessed-at for LRU tracking.
   Returns nil on miss."
  [cache-key]
  (when-let [entry (get @l1-cache* cache-key)]
    (swap! l1-cache* assoc-in [cache-key :accessed-at] (System/currentTimeMillis))
    entry))

(defn l1-put-entry!
  "Store an entry in L1. Triggers LRU eviction if over capacity.
   Returns the number of entries evicted (0 if none)."
  [cache-key state watermark max-entries]
  (let [now (System/currentTimeMillis)
        max-entries (or max-entries l1-default-max-entries)]
    (swap! l1-cache* assoc cache-key {:state state
                                    :watermark watermark
                                    :accessed-at now
                                    :validated-at now})
    ;; LRU eviction
    (let [current-count (count @l1-cache*)]
      (if (> current-count max-entries)
        (let [to-evict-count (- current-count max-entries)
              sorted-keys (->> @l1-cache*
                               (sort-by (comp :accessed-at val))
                               (take to-evict-count)
                               (map first))]
          (swap! l1-cache* #(reduce dissoc % sorted-keys))
          to-evict-count)
        0))))

(defn l1-touch-validated!
  "Mark an L1 entry as freshly validated (event store confirmed no new events).
   Resets the TTL window without replacing the cached state."
  [cache-key]
  (swap! l1-cache* assoc-in [cache-key :validated-at] (System/currentTimeMillis)))

(defn l1-update-entry!
  "Update an existing L1 entry with new state and watermark after processing new events."
  [cache-key state watermark]
  (let [now (System/currentTimeMillis)]
    (swap! l1-cache* assoc cache-key {:state state
                                    :watermark watermark
                                    :accessed-at now
                                    :validated-at now})))

(defn l1-invalidate!
  "Remove a specific entry from L1."
  [cache-key]
  (swap! l1-cache* dissoc cache-key))

(defn l1-clear!
  "Remove all entries from L1."
  []
  (reset! l1-cache* {}))

(defn l1-stats
  "Return L1 cache statistics."
  []
  {:entries (count @l1-cache*)})

;; ---------------------------------------------------------------------------
;; Ordinary and partitioned models
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; Cache key helpers
;; ---------------------------------------------------------------------------

(defn- ordered-cache-values [encode key-fn values]
  ;; Encode each comparison key once. Maps and sets have no iteration order.
  (let [values (vec values)]
    (if (< (count values) 2) values
      (->> values
           (mapv #(vector (encode (key-fn %)) %))
           (sort (fn [[a] [b]] (Arrays/compareUnsigned ^bytes a ^bytes b)))
           (mapv second)))))

(defn- canonical-cache-value [value]
  ;; Query unions commonly share the same event-type set. Reuse its normalized
  ;; form within this call, without introducing a long-lived identity cache.
  (let [seen (IdentityHashMap.)
        encoded (IdentityHashMap.)]
    (letfn [(comparison-key [x]
              (or (.get encoded x)
                  (let [bytes (codec/encode x)]
                    (.put encoded x bytes)
                    bytes)))
            (form [x]
              (or (.get seen x)
                  (let [v (cond
                            (record? x) [:record (.getName (class x)) (ordered-cache-values comparison-key first (map (fn [[k v]] [(form k) (form v)]) x))]
                            (instance? java.util.Map x) [:map (ordered-cache-values comparison-key first (map (fn [[k v]] [(form k) (form v)]) x))]
                            (instance? java.util.Set x) [:set (ordered-cache-values comparison-key identity (map form x))]
                            (or (sequential? x) (instance? java.util.List x)) [:sequence (mapv form x)]
                            (integer? x) (if (<= Long/MIN_VALUE x Long/MAX_VALUE) (long x) (bigint x))
                            (instance? java.math.BigDecimal x) (.stripTrailingZeros ^java.math.BigDecimal x)
                            (instance? Float x) (double x)
                            :else x)]
                    (.put seen x v)
                    v)))]
      (form value))))

(defn- cache-fingerprint [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (codec/encode (canonical-cache-value value)))))

(defn format-key
  [n v]
  (.getBytes (format "%s-%s" n v)))

(defn format-scoped-key [n v scope]
  (if scope
    (.getBytes (format "%s-%s-s1-%s" n v (cache-fingerprint scope)))
    (format-key n v)))

(defn suffix-key
  "Append a string suffix to a base cache key."
  ^bytes [^bytes base-key ^String suffix]
  (let [suffix-bytes (.getBytes suffix)
        result (byte-array (+ (alength base-key) (alength suffix-bytes)))]
    (System/arraycopy base-key 0 result 0 (alength base-key))
    (System/arraycopy suffix-bytes 0 result (alength base-key) (alength suffix-bytes))
    result))

(defn- segment-key
  "Append :s<idx> suffix to a base cache key."
  ^bytes [^bytes base-key idx]
  (suffix-key base-key (format ":s%d" idx)))

(defn partition-cache-key
  "Cache key for a single partition, isolated by its full value fingerprint."
  ^bytes [^bytes base-key partition-key]
  (suffix-key base-key (str ":p1-" (cache-fingerprint partition-key))))

(defn- entity-index-cache-key
  "Cache key for the entity-to-partition index: base-key:eidx"
  ^bytes [^bytes base-key]
  (suffix-key base-key ":eidx"))

;; ---------------------------------------------------------------------------
;; Event query helpers
;; ---------------------------------------------------------------------------

(defn- add-watermark [query watermark]
  (if watermark
    (if (vector? query)
      (mapv #(assoc % :after watermark) query)
      (assoc query :after watermark))
    query))

(defn- inject-tenant-id [query tenant-id]
  (if (vector? query)
    (mapv #(assoc % :tenant-id tenant-id) query)
    (assoc query :tenant-id tenant-id)))

;; ---------------------------------------------------------------------------
;; Event processing
;; ---------------------------------------------------------------------------

(defn process-events
  [state events f]
  (reduce
   (fn [acc event]
     (-> acc
         (update :state f event)
         (update :event-count inc)
         (assoc :new-watermark (:event/id event))))
   {:state state
    :event-count 0}
   events))

;; ---------------------------------------------------------------------------
;; Segmented cache (hash-based, for large unpartitioned maps)
;; ---------------------------------------------------------------------------

(def ^:const segment-count 64)
(def ^:const segment-threshold 10000)

(defn- should-segment?
  "Returns true if state map has enough keys to warrant segmentation."
  [state]
  (> (count state) segment-threshold))

(defn- split-into-segments
  "Partition a map into N segments by top-level key hash."
  [state-map n]
  (persistent!
   (reduce-kv
    (fn [segments k v]
      (let [idx (mod (hash k) n)]
        (assoc! segments idx (assoc (get segments idx {}) k v))))
    (transient {})
    state-map)))

(defn- changed-segments
  "Find segment indices where state differs, using identical? for O(1) per-key comparison."
  [old-state new-state n]
  (let [all-keys (into (set (keys old-state)) (keys new-state))]
    (into #{}
          (comp
           (filter (fn [k]
                     (not (identical? (get old-state k) (get new-state k)))))
           (map (fn [k] (mod (hash k) n))))
          all-keys)))

;; ---------------------------------------------------------------------------
;; Cache read/write (unpartitioned)
;; ---------------------------------------------------------------------------

(defn- read-cache-snapshot
  [get-value base-key]
  (when-let [raw (get-value {:k base-key})]
    (let [decoded (codec/decode raw)]
      (if (:segmented decoded)
        ;; Segmented: read all segments and merge
        (let [{:keys [segment-count watermark checksums]} decoded
              state (reduce
                     (fn [acc idx]
                       (if-let [seg-bytes (get-value {:k (segment-key base-key idx)})]
                         (merge acc (codec/decode seg-bytes))
                         acc))
                     {}
                     (range segment-count))]
          {:data state
           :watermark watermark
           :segmented true
           :segment-count segment-count
           :checksums checksums})
        ;; Legacy monolithic format
        decoded))))

(defn- read-cache
  "Read state and watermark from one snapshot, including every segment."
  [cache base-key]
  (kv/read-snapshot cache #(read-cache-snapshot % base-key)))

(defn- write-monolithic!
  "Write state as a single cache entry (legacy format)."
  [cache base-key state watermark]
  (kv/put! cache {:k base-key
                  :v (codec/encode {:data state :watermark watermark})}))

(defn- write-segmented!
  "Write state as segmented cache entries with manifest."
  [cache base-key state watermark]
  (let [segments (split-into-segments state segment-count)
        entries (into []
                      (mapcat
                       (fn [[idx seg-data]]
                         [{:k (segment-key base-key idx)
                           :v (codec/encode seg-data)}]))
                      segments)
        checksums (into {}
                        (map (fn [{:keys [k v]}]
                               (let [k-str (String. ^bytes k)
                                     idx-str (subs k-str (inc (.lastIndexOf k-str ":")))
                                     idx (Long/parseLong (subs idx-str 1))]
                                 [idx (hash (vec v))])))
                        entries)
        manifest {:segmented true
                  :segment-count segment-count
                  :watermark watermark
                  :checksums checksums}
        all-entries (conj entries {:k base-key
                                  :v (codec/encode manifest)})]
    (kv/put-batch! cache {:entries all-entries})))

(defn- write-changed-segments!
  "Write only the segments that changed, plus updated manifest."
  [cache base-key old-checksums state watermark n changed-idxs]
  (let [segments (split-into-segments state n)
        changed-entries (into []
                              (mapcat
                               (fn [idx]
                                 (let [seg-data (get segments idx {})]
                                   [{:k (segment-key base-key idx)
                                     :v (codec/encode seg-data)}])))
                              changed-idxs)
        new-checksums (reduce
                       (fn [acc {:keys [k v]}]
                         (let [k-str (String. ^bytes k)
                               idx-str (subs k-str (inc (.lastIndexOf k-str ":")))
                               idx (Long/parseLong (subs idx-str 1))]
                           (assoc acc idx (hash (vec v)))))
                       old-checksums
                       changed-entries)
        manifest {:segmented true
                  :segment-count n
                  :watermark watermark
                  :checksums new-checksums}
        all-entries (conj changed-entries {:k base-key
                                          :v (codec/encode manifest)})]
    (kv/put-batch! cache {:entries all-entries})))

(defn- write-cache!
  "Write state to cache, choosing monolithic or segmented based on size."
  [cache base-key state watermark]
  (if (should-segment? state)
    (write-segmented! cache base-key state watermark)
    (write-monolithic! cache base-key state watermark)))

;; ---------------------------------------------------------------------------
;; Partitioned projections
;; ---------------------------------------------------------------------------

(defn- process-events-partitioned
  "Route events to partitions, apply reducer, detect cross-partition moves.
   partition-fn operates on entity state (not events).
   Uses a transient in-memory eid→pk lookup for O(1) partition finding.
   Returns {:partitions {pk → state} :changed-pks #{...} :event-count n :new-watermark uuid}"
  [partitions events f partition-fn entity-id-fn]
  (let [;; Build transient eid→pk lookup from existing partition state
        initial-lookup (reduce-kv
                        (fn [m pk state]
                          (reduce-kv (fn [m eid _] (assoc! m eid pk)) m state))
                        (transient {})
                        partitions)]
    (reduce
     (fn [acc event]
       (let [eid (entity-id-fn event)
             current-pk (get (:lookup acc) eid)]
         (if current-pk
           ;; Entity exists in a partition — apply reducer, check for move
           (let [old-state (get-in acc [:partitions current-pk])
                 new-state (f old-state event)
                 new-entity (get new-state eid)]
             (if new-entity
               (let [new-pk (partition-fn new-entity)]
                 (if (= new-pk current-pk)
                   ;; Same partition — just update state
                   (-> acc
                       (assoc-in [:partitions current-pk] new-state)
                       (update :changed-pks conj current-pk)
                       (update :event-count inc)
                       (assoc :new-watermark (:event/id event)))
                   ;; Cross-partition move
                   (-> acc
                       (assoc-in [:partitions current-pk] (dissoc new-state eid))
                       (update-in [:partitions new-pk] assoc eid new-entity)
                       (update :changed-pks conj current-pk)
                       (update :changed-pks conj new-pk)
                       (assoc-in [:lookup eid] new-pk)
                       (update :event-count inc)
                       (assoc :new-watermark (:event/id event)))))
               ;; Entity was removed by reducer (e.g., deleted)
               (-> acc
                   (assoc-in [:partitions current-pk] new-state)
                   (update :changed-pks conj current-pk)
                   (update :lookup dissoc eid)
                   (update :event-count inc)
                   (assoc :new-watermark (:event/id event)))))

           ;; Entity doesn't exist yet — new entity creation
           (let [temp-state (f {} event)
                 new-entity (get temp-state eid)]
             (if new-entity
               (let [pk (partition-fn new-entity)]
                 (-> acc
                     (update-in [:partitions pk] assoc eid new-entity)
                     (update :changed-pks conj pk)
                     (assoc-in [:lookup eid] pk)
                     (update :event-count inc)
                     (assoc :new-watermark (:event/id event))))
               ;; Reducer didn't produce an entity — skip
               (-> acc
                   (update :event-count inc)
                   (assoc :new-watermark (:event/id event))))))))
     {:partitions partitions
      :lookup (persistent! initial-lookup)
      :changed-pks #{}
      :event-count 0}
     events)))

(defn- process-events-single-partition
  "Process events for a single partition read. Only applies events relevant to this partition.
   partition-fn operates on entity state. find-entity-fn, when provided, is called with an
   entity-id to locate entities in other partitions — returns [source-pk entity] or nil.
   Returns {:state partition-state :evictions {pk {eid entity}} :source-removals {pk #{eids}}
            :event-count n :new-watermark uuid}"
  [partition-key partition-state events f partition-fn entity-id-fn find-entity-fn]
  (reduce
   (fn [acc event]
     (let [eid (entity-id-fn event)
           in-partition? (contains? (:state acc) eid)]
       (if in-partition?
         ;; Entity is here — apply reducer, check for move out
         (let [new-state (f (:state acc) event)
               new-entity (get new-state eid)]
           (if (and new-entity (not= partition-key (partition-fn new-entity)))
             ;; Entity moved OUT
             (-> acc
                 (assoc :state (dissoc new-state eid))
                 (assoc-in [:evictions (partition-fn new-entity) eid] new-entity)
                 (update :event-count inc)
                 (assoc :new-watermark (:event/id event)))
             ;; Still here
             (-> acc
                 (assoc :state new-state)
                 (update :event-count inc)
                 (assoc :new-watermark (:event/id event)))))

         ;; Entity not here — check if it's moving IN
         (let [temp-state (f {} event)
               new-entity (get temp-state eid)
               adopts-here? (and new-entity (= partition-key (partition-fn new-entity)))]
           (if adopts-here?
             ;; Heuristic matched — adopt
             (-> acc
                 (update :state assoc eid new-entity)
                 (update :event-count inc)
                 (assoc :new-watermark (:event/id event)))
             ;; Heuristic failed — try finding entity in another partition
             (if-let [[source-pk source-entity] (when find-entity-fn (find-entity-fn eid))]
               (let [new-state (f {eid source-entity} event)
                     moved-entity (get new-state eid)]
                 (if (and moved-entity (= partition-key (partition-fn moved-entity)))
                   ;; Entity moved/belongs here — adopt + remove from source if different
                   (cond-> acc
                     true (update :state assoc eid moved-entity)
                     (not= source-pk partition-key)
                     (update-in [:source-removals source-pk] (fnil conj #{}) eid)
                     true (update :event-count inc)
                     true (assoc :new-watermark (:event/id event)))
                   ;; Updated but didn't move here — skip
                   (-> acc
                       (update :event-count inc)
                       (assoc :new-watermark (:event/id event)))))
               ;; Not found anywhere — skip
               (-> acc
                   (update :event-count inc)
                   (assoc :new-watermark (:event/id event)))))))))
   {:state partition-state
    :evictions {}
    :source-removals {}
    :event-count 0}
   events))

(defn- read-partition-manifest
  "Read the partitioned manifest from cache. Returns nil if not found or not partitioned."
  [cache base-key]
  (when-let [raw (kv/get! cache {:k base-key})]
    (let [decoded (codec/decode raw)]
      (when (:partitioned decoded)
        decoded))))

(defn- read-single-partition
  "Read one partition's cached state."
  [cache base-key partition-key]
  (when-let [raw (kv/get! cache {:k (partition-cache-key base-key partition-key)})]
    (codec/decode raw)))

(defn- read-all-partitions
  "Read all partitions from cache, return {pk → state}."
  [cache base-key manifest]
  (reduce
   (fn [acc pk]
     (if-let [p (read-single-partition cache base-key pk)]
       (assoc acc pk (:data p))
       acc))
   {}
   (:partition-keys manifest)))

(defn- read-entity-index
  "Read the entity-to-partition index from cache. Returns {eid → pk} or nil."
  [cache base-key]
  (when-let [raw (kv/get! cache {:k (entity-index-cache-key base-key)})]
    (codec/decode raw)))

(defn- write-entity-index!
  "Write the entity-to-partition index to cache."
  [cache base-key index]
  (kv/put! cache {:k (entity-index-cache-key base-key)
                  :v (codec/encode index)}))

(defn- build-entity-index
  "Build {eid → pk} from a partitions map {pk → {eid → entity}}."
  [partitions]
  (reduce-kv
   (fn [idx pk state]
     (reduce-kv (fn [idx eid _] (assoc idx eid pk)) idx state))
   {} partitions))

(defn- find-entity-via-index
  "Look up an entity's partition from the index, then read it. Returns [source-pk entity] or nil.
   Includes entities already indexed to the current partition — they may be in L2 but not yet in L1."
  [cache base-key index _exclude-pk eid]
  (when-let [source-pk (get index eid)]
    (when-let [partition-data (:data (read-single-partition cache base-key source-pk))]
      (when-let [entity (get partition-data eid)]
        [source-pk entity]))))

(defn- write-partitions!
  "Write changed partitions and manifest atomically."
  [cache base-key partitions changed-pks global-watermark all-partition-keys]
  (let [partition-entries (into []
                                (mapcat
                                 (fn [pk]
                                   [{:k (partition-cache-key base-key pk)
                                     :v (codec/encode {:data (get partitions pk {})
                                                         :watermark global-watermark})}]))
                                changed-pks)
        manifest {:partitioned true
                  :global-watermark global-watermark
                  :partition-keys (set all-partition-keys)}
        manifest-entry {:k base-key
                        :v (codec/encode manifest)}
        all-entries (conj partition-entries manifest-entry)]
    (kv/put-batch! cache {:entries all-entries})))

(defn- write-evictions!
  "Write evicted entities to their destination partitions."
  [cache base-key evictions global-watermark]
  (when (seq evictions)
    (let [entries (into []
                        (mapcat
                         (fn [[pk entities]]
                           ;; Read existing partition, merge evicted entities
                           (let [existing (or (:data (read-single-partition cache base-key pk)) {})
                                 merged (merge existing entities)]
                             [{:k (partition-cache-key base-key pk)
                               :v (codec/encode {:data merged :watermark global-watermark})}])))
                        evictions)]
      (kv/put-batch! cache {:entries entries}))))

(defn- update-entity-index!
  "Update the entity index after single-partition processing.
   Adds all entities in current partition, handles evictions and source removals."
  [cache base-key partition-key state evictions source-removals]
  (let [idx (or (read-entity-index cache base-key) {})
        ;; Add all entities in current partition
        idx (reduce-kv (fn [idx eid _] (assoc idx eid partition-key)) idx state)
        ;; Map evicted entities to their destination partitions
        idx (reduce-kv
             (fn [idx pk entities]
               (reduce-kv (fn [idx eid _] (assoc idx eid pk)) idx entities))
             idx evictions)
        ;; Source removals moved INTO current partition (already in state above)
        ;; but remove stale source-pk mappings
        idx (reduce-kv
             (fn [idx _src-pk eids]
               (reduce (fn [idx eid] (assoc idx eid partition-key)) idx eids))
             idx source-removals)]
    (write-entity-index! cache base-key idx)))

(defn- write-source-removals!
  "Remove entities from their source partitions after cross-partition adoption."
  [cache base-key source-removals global-watermark]
  (when (seq source-removals)
    (let [entries (into []
                        (mapcat
                         (fn [[pk eids]]
                           (let [existing (or (:data (read-single-partition cache base-key pk)) {})
                                 cleaned (apply dissoc existing eids)]
                             [{:k (partition-cache-key base-key pk)
                               :v (codec/encode {:data cleaned :watermark global-watermark})}])))
                        source-removals)]
      (kv/put-batch! cache {:entries entries}))))

(def ^:const default-l1-ttl-ms 0)
(def ^:const default-l1-max-entries 10000)

(declare p-partitioned)

(defn- p-partitioned-single
  "Single-partition read with L1 + L2 tiered caching."
  [{:keys [event-store cache] :as context}
   {:keys [f query partition-fn entity-id-fn partition-key name] :as args}
   cache-key manifest]
  (let [l1-key (String. ^bytes (partition-cache-key cache-key partition-key))
        ttl-ms (or (:l1-ttl-ms args) default-l1-ttl-ms)
        max-entries (or (:l1-max-entries args) default-l1-max-entries)]
    (if-let [l1 (l1-get-entry l1-key)]
      ;; ── L1 hit ──
      (if (< (- (System/currentTimeMillis) (:validated-at l1)) ttl-ms)
        ;; Within TTL
        (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-hit
                 [:metric/name "ReadModelL1Hit" :metric/resolution :high
                  :read-model/cache-tier "l1-hit" :read-model/name name]
                 (:state l1))
        ;; TTL expired — check events
        (let [all-events (into [] (es/read event-store (add-watermark query (:watermark l1))))
              entity-index_ (delay (read-entity-index cache cache-key))
              find-entity-fn (when manifest
                               (fn [eid]
                                 (when-let [idx @entity-index_]
                                   (find-entity-via-index
                                    cache cache-key idx partition-key eid))))
              ;; Track entity-ids discovered as relevant during filtering
              ;; so that later events for the same entity are also included.
              {:keys [relevant]}
              (reduce
               (fn [{:keys [known-eids] :as acc} event]
                 (let [eid (entity-id-fn event)]
                   (if (or (contains? (:state l1) eid)
                           (contains? known-eids eid))
                     (-> acc
                         (update :relevant conj event)
                         (update :known-eids conj eid))
                     (let [relevant-here?
                           (try
                             (let [temp (f {} event)
                                   new-ent (get temp eid)]
                               (if (and new-ent (= partition-key (partition-fn new-ent)))
                                 true
                                 ;; Heuristic failed — check other partitions
                                 (when-let [[_ src-entity] (when find-entity-fn (find-entity-fn eid))]
                                   (let [new-ent (get (f {eid src-entity} event) eid)]
                                     (and new-ent (= partition-key (partition-fn new-ent)))))))
                             (catch Exception _ false))]
                       (if relevant-here?
                         (-> acc
                             (update :relevant conj event)
                             (update :known-eids conj eid))
                         acc)))))
               {:relevant [] :known-eids #{}}
               all-events)]
          (if (empty? relevant)
            (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-revalidated
                     [:metric/name "ReadModelL1Revalidated" :metric/resolution :high
                      :read-model/cache-tier "l1-revalidated" :read-model/name name]
                     (do (l1-touch-validated! l1-key) (:state l1)))
            (let [{:keys [state evictions source-removals event-count new-watermark]}
                  (process-events-single-partition
                   partition-key (:state l1) relevant f partition-fn entity-id-fn find-entity-fn)
                  wm (or new-watermark (:watermark l1))]
              (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-stale
                       [:metric/name "ReadModelL1Stale" :metric/resolution :high
                        :read-model/cache-tier "l1-stale" :read-model/name name
                        :read-model/events-processed event-count]
                       (do (l1-update-entry! l1-key state wm)
                           (when manifest
                             (when (or (seq evictions) (seq source-removals))
                               (write-evictions! cache cache-key evictions wm)
                               (write-source-removals! cache cache-key source-removals wm))
                             (when (pos? event-count)
                               (write-partitions! cache cache-key
                                                 {partition-key state} #{partition-key} wm
                                                 (into (:partition-keys manifest)
                                                       (cons partition-key (keys evictions))))
                               (update-entity-index! cache cache-key partition-key
                                                     state evictions source-removals)))
                           state))))))

      ;; ── L1 miss ──
      (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-miss
               [:metric/name "ReadModelL1Miss" :metric/resolution :high
                :read-model/cache-tier "l1-miss" :read-model/name name]
        (if (nil? manifest)
          ;; No L2 — full projection first, then read partition
          (do (p-partitioned context (dissoc args :partition-key))
              (let [cp (read-single-partition cache cache-key partition-key)
                    state (or (:data cp) {})
                    wm (:watermark cp)]
                (l1-put-entry! l1-key state wm max-entries)
                state))
          ;; L2 exists — read partition with incremental update
          (let [cp (read-single-partition cache cache-key partition-key)
                watermark (or (:watermark cp) (:global-watermark manifest))
                pstate (or (:data cp) {})
                events (into [] (es/read event-store (add-watermark query watermark)))
                entity-index_ (delay (read-entity-index cache cache-key))
                find-entity-fn (fn [eid]
                                 (when-let [idx @entity-index_]
                                   (find-entity-via-index
                                    cache cache-key idx partition-key eid)))]
                    (if (empty? events)
              (do (l1-put-entry! l1-key pstate watermark max-entries) pstate)
              (let [{:keys [state evictions source-removals event-count new-watermark]}
                    (process-events-single-partition
                     partition-key pstate events f partition-fn entity-id-fn find-entity-fn)
                    wm (or new-watermark watermark)]
                (when (or (seq evictions) (seq source-removals))
                  (write-evictions! cache cache-key evictions wm)
                  (write-source-removals! cache cache-key source-removals wm))
                ;; Always write partition + entity index to L2 when events are
                ;; processed. Entity index must stay consistent with L2 state
                ;; so cross-partition lookups can find entities.
                (when (pos? event-count)
                  (write-partitions! cache cache-key
                                    {partition-key state} #{partition-key} wm
                                    (into (:partition-keys manifest)
                                          (cons partition-key (keys evictions))))
                  (update-entity-index! cache cache-key partition-key
                                        state evictions source-removals))
                (l1-put-entry! l1-key state wm max-entries)
                state))))))))

(defn- p-partitioned-full
  "Full projection across all partitions."
  [{:keys [event-store cache]} {:keys [f query partition-fn entity-id-fn]} cache-key manifest]
  (let [partitions (if manifest (read-all-partitions cache cache-key manifest) {})
        watermark (:global-watermark manifest)
        events (if watermark
                 (es/read event-store (add-watermark query watermark))
                 (es/read event-store query))
        {:keys [partitions changed-pks event-count new-watermark]}
        (process-events-partitioned partitions events f partition-fn entity-id-fn)]
    (when (or (nil? manifest) (pos? event-count))
      (let [eidx (build-entity-index partitions)]
        (write-partitions! cache cache-key partitions
                          (if (nil? manifest) (set (keys partitions)) changed-pks)
                          (or new-watermark watermark)
                          (keys partitions))
        (write-entity-index! cache cache-key eidx)))
    (reduce merge {} (vals partitions))))

(defn- p-partitioned
  "Projection code path for partitioned read models."
  [{:keys [event-store cache tenant-id] :as context}
   {:keys [f query name version scope partition-fn entity-id-fn partition-key] :as args}]
  (let [cache-scope (not-empty (dissoc scope :partition-key))
        cache-key (format-scoped-key name version (if cache-scope [tenant-id cache-scope] tenant-id))
        query (inject-tenant-id query tenant-id)
        manifest (read-partition-manifest cache cache-key)]
    (if partition-key
      (p-partitioned-single context (assoc args :query query) cache-key manifest)
      (p-partitioned-full context (assoc args :query query) cache-key manifest))))

;; ---------------------------------------------------------------------------
;; Main projection function
;; ---------------------------------------------------------------------------

(defn- p-unpartitioned
  "Unpartitioned projection with L1 + L2 tiered caching."
  [{:keys [event-store cache tenant-id]}
   {:keys [f query name version scope l1-ttl-ms l1-max-entries]}]
  (u/with-context {:read-model/name name
                   :read-model/version version})
  (let [cache-key (format-scoped-key name version (if scope [tenant-id scope] tenant-id))
        l1-key (String. ^bytes cache-key)
        query (inject-tenant-id query tenant-id)
        ttl-ms (or l1-ttl-ms default-l1-ttl-ms)
        max-entries (or l1-max-entries default-l1-max-entries)]

    ;; ── L1 check ──
    (if-let [l1 (l1-get-entry l1-key)]
      (let [now (System/currentTimeMillis)]
        (if (< (- now (:validated-at l1)) ttl-ms)
          ;; L1 hit within TTL — no I/O at all
          (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-hit
                   [:metric/name "ReadModelL1Hit" :metric/resolution :high
                    :read-model/cache-tier "l1-hit"]
                   (:state l1))

          ;; L1 hit, TTL expired — process any new events directly from reducible
          (let [events (es/read event-store (add-watermark query (:watermark l1)))
                {:keys [state event-count new-watermark]}
                (process-events (:state l1) events f)
                wm (or new-watermark (:watermark l1))]
            (if (zero? event-count)
              ;; No new events — revalidate L1
              (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-revalidated
                       [:metric/name "ReadModelL1Revalidated" :metric/resolution :high
                        :read-model/cache-tier "l1-revalidated"]
                       (do (l1-touch-validated! l1-key)
                           (:state l1)))

              ;; New events applied — update L1 + L2
              (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-stale
                       [:metric/name "ReadModelL1Stale" :metric/resolution :high
                        :read-model/cache-tier "l1-stale"
                        :read-model/events-processed event-count]
                       (do (l1-update-entry! l1-key state wm)
                           (when (>= event-count 10)
                             (write-cache! cache cache-key state wm))
                           state))))))

      ;; ── L1 miss — fall through to L2 ──
      (u/trace :ai.obney.grain.read-model-processor-v2.core/l1-miss
               [:metric/name "ReadModelL1Miss" :metric/resolution :high
                :read-model/cache-tier "l1-miss"]
               (if-let [cached (read-cache cache cache-key)]
                 ;; L2 hit
                 (u/trace :ai.obney.grain.read-model-processor-v2.core/l2-hit
                          [:metric/name "ReadModelL2Hit" :metric/resolution :high
                           :read-model/cache-tier "l2-hit"]
                          (let [{:keys [data watermark segmented segment-count checksums]} cached
                                events (es/read event-store (add-watermark query watermark))
                                {:keys [state event-count new-watermark]} (process-events data events f)
                                wm (or new-watermark watermark)]
                            (when (>= event-count 10)
                              (if segmented
                                (let [changed (changed-segments data state segment-count)]
                                  (when (seq changed)
                                    (write-changed-segments! cache cache-key checksums state
                                                            wm segment-count changed)))
                                (write-cache! cache cache-key state wm)))
                            ;; Populate L1
                            (l1-put-entry! l1-key state wm max-entries)
                            state))

                 ;; L2 miss — full projection
                 (u/trace :ai.obney.grain.read-model-processor-v2.core/l2-miss
                          [:metric/name "ReadModelL2Miss" :metric/resolution :high
                           :read-model/cache-tier "l2-miss"]
                          (let [events (es/read event-store query)
                                {:keys [state _event-count new-watermark]} (process-events {} events f)]
                            (write-cache! cache cache-key state new-watermark)
                            ;; Populate L1
                            (l1-put-entry! l1-key state new-watermark max-entries)
                            state)))))))

(defn p
  [{:keys [event-store cache tenant-id] :as context}
   {:keys [f query name version scope partition-fn entity-id-fn] :as args}]
  (if (and partition-fn entity-id-fn)
    (p-partitioned context args)
    (p-unpartitioned context args)))

;; ---------------------------------------------------------------------------
;; Ordered key and metadata codecs
;; ---------------------------------------------------------------------------

(defn key-join ^bytes [& arrays]
  (let [out (byte-array (reduce (fn [size ^bytes a] (+ size (alength a))) 0 arrays))]
    (reduce (fn [offset ^bytes a]
              (System/arraycopy a 0 out offset (alength a))
              (+ offset (alength a)))
            0 arrays)
    out))

(definterface KeyOut
  (^void writeByte [^long v])
  (^void writeShort [^long v])
  (^void writeLong [^long v]))

;; Unsynchronized big-endian writer with DataOutputStream's byte layout.
(deftype KeyBuffer [^:unsynchronized-mutable ^bytes buf ^:unsynchronized-mutable ^long n]
  KeyOut
  (writeByte [_ v]
    (when (= n (alength buf)) (set! buf (Arrays/copyOf buf (* 2 (alength buf)))))
    (aset buf n (unchecked-byte v))
    (set! n (inc n)))
  (writeShort [this v]
    (.writeByte this (bit-shift-right v 8))
    (.writeByte this v))
  (writeLong [this v]
    (.writeByte this (unsigned-bit-shift-right v 56)) (.writeByte this (unsigned-bit-shift-right v 48))
    (.writeByte this (unsigned-bit-shift-right v 40)) (.writeByte this (unsigned-bit-shift-right v 32))
    (.writeByte this (unsigned-bit-shift-right v 24)) (.writeByte this (unsigned-bit-shift-right v 16))
    (.writeByte this (unsigned-bit-shift-right v 8)) (.writeByte this v))
  clojure.lang.IDeref
  (deref [_] (Arrays/copyOf buf n))
  java.io.Closeable
  (close [_] (set! n 0)
    ;; Do not retain a scratch buffer grown by one unusually large key.
    (when (> (alength buf) 1024) (set! buf (byte-array 128)))))

(def ^:private ^ThreadLocal key-buffers
  (ThreadLocal/withInitial (reify java.util.function.Supplier (get [_] (KeyBuffer. (byte-array 128) 0)))))

;; ---------------------------------------------------------------------------
;; OPT-8 (W3): one compact, order-preserving value encoding for every index
;; member, group key, address and sort-data field (key-encode and
;; key-encode-compact are the same function). Type tags keep the cross-type
;; order of the former (KF9 physical) encoding:
;;   01 nil | 02 false | 03 true | 10-12 string | 18-28 integer | 30 UUID |
;;   38-3A keyword | 40 vector (elements, then 00)
;; Strings compare by UTF-16 code unit, as before. T is 10 for a string and 38
;; for a keyword (its printed form without the leading ':'):
;;   first unit c:  none or c < 2 -> T, then the units in state B;
;;                  2 <= c < 256  -> T+1, c, then state A;
;;                  c >= 256      -> T+2, hi, lo, then state B.
;;   state A (after a unit < 256):  00 end | 01 c (c < 3) | c-1 (3 <= c < 256) |
;;                                  FF hi lo (c >= 256, then state B)
;;   state B (otherwise):           00 00 end | 00 01 c (c < 2) |
;;                                  00 c (2 <= c < 256, then state A) | hi lo (c >= 256)
;; In each state the code words are prefix-free and increase with the unit,
;; the end word is the smallest, and the state after a prefix depends only on
;; that prefix: byte order is string order and every encoding is
;; self-delimiting. Mixed-script aware: ASCII and Latin-1 cost 1 B per unit
;; after the first, other BMP units 2 B (3 B when entered from state A), and
;; no string is ever longer than in the former physical encoding (3 B per unit
;; below 256, 2 B otherwise, 2 B end; proof in the OPT-8 report).
;; Integers (any integer type within long range): 20 is zero; 20+n then the
;; n-byte big-endian value for a positive; 20-n then the one's complement of
;; the n-byte magnitude for a negative (n minimal, 1..8).

(def ^:private ^:const tag-string 0x10)
(def ^:private ^:const tag-integer 0x20)
(def ^:private ^:const tag-uuid 0x30)
(def ^:private ^:const tag-keyword 0x38)
(def ^:private ^:const tag-vector 0x40)

(defn- key-write-string!
  "Units of s from start, as one tagged value (tag base T)."
  [^KeyOut out ^String s ^long start ^long tag]
  (let [n (.length s)]
    (if (= start n)
      (do (.writeByte out tag) (.writeByte out 0) (.writeByte out 0))
      (let [c0 (long (int (.charAt s start)))
            a0 (cond (< c0 2) (do (.writeByte out tag) (.writeByte out 0) (.writeByte out 1) (.writeByte out c0) false)
                     (< c0 256) (do (.writeByte out (inc tag)) (.writeByte out c0) true)
                     :else (do (.writeByte out (+ tag 2)) (.writeShort out c0) false))]
        (loop [i (inc start) a? a0]
          (if (< i n)
            (let [c (long (int (.charAt s i)))]
              (if a?
                (cond (< c 3) (do (.writeByte out 1) (.writeByte out c) (recur (inc i) true))
                      (< c 256) (do (.writeByte out (dec c)) (recur (inc i) true))
                      :else (do (.writeByte out 255) (.writeShort out c) (recur (inc i) false)))
                (cond (< c 2) (do (.writeByte out 0) (.writeByte out 1) (.writeByte out c) (recur (inc i) false))
                      (< c 256) (do (.writeByte out 0) (.writeByte out c) (recur (inc i) true))
                      :else (do (.writeShort out c) (recur (inc i) false)))))
            (if a? (.writeByte out 0) (do (.writeByte out 0) (.writeByte out 0)))))))))

(defn- key-write-integer! [^KeyOut out ^long v]
  (if (zero? v)
    (.writeByte out tag-integer)
    (let [negative? (neg? v)
          u (if negative? (unchecked-negate v) v)
          n (quot (+ (- 64 (Long/numberOfLeadingZeros u)) 7) 8)
          x (if negative? (bit-not u) u)]
      (.writeByte out (if negative? (- tag-integer n) (+ tag-integer n)))
      (loop [k (dec n)]
        (when (>= k 0)
          (.writeByte out (unsigned-bit-shift-right x (* 8 k)))
          (recur (dec k)))))))

(defn- key-write-key! [^KeyOut out value]
  (cond
    (nil? value) (.writeByte out 1)
    (boolean? value) (.writeByte out (if value 3 2))
    (string? value) (key-write-string! out value 0 tag-string)
    (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE)) (key-write-integer! out (long value))
    (uuid? value)
    (do (.writeByte out tag-uuid)
        (.writeLong out (.getMostSignificantBits ^UUID value))
        (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value) (key-write-string! out (str value) 1 tag-keyword)
    (vector? value)
    (do (.writeByte out tag-vector) (reduce (fn [_ v] (key-write-key! out v)) nil value) (.writeByte out 0))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn key-encode ^bytes [value]
  ;; Encoding never re-enters itself through user code, so one scratch buffer
  ;; per thread is safe; close resets it even when the value is rejected.
  (with-open [^KeyBuffer out (.get key-buffers)]
    (key-write-key! out value)
    @out))

(defn key-encode-compact
  "Same as key-encode since OPT-8 (one encoding for keys and sort data)."
  ^bytes [value] (key-encode value))

;; The former physical encoding, kept only as the input of namespace registry
;; digests (namespace-digest), so registry addresses are unchanged by OPT-8.
(defn- key-write-legacy! [^KeyOut out value]
  (cond
    (nil? value) (.writeByte out 1)
    (boolean? value) (.writeByte out (if value 3 2))
    (string? value)
    (let [^String value value]
      (.writeByte out 16)
      (dotimes [i (.length value)]
        (let [c (int (.charAt value i)) hi (bit-shift-right c 8) lo (bit-and 255 c)]
          (.writeByte out hi) (when (zero? hi) (.writeByte out 255))
          (.writeByte out lo) (when (zero? lo) (.writeByte out 255))))
      (.writeShort out 0))
    (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
    (do (.writeByte out 32) (.writeLong out (bit-xor (long value) Long/MIN_VALUE)))
    (uuid? value)
    (do (.writeByte out 48)
        (.writeLong out (.getMostSignificantBits ^UUID value))
        (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value) (do (.writeByte out 56) (key-write-legacy! out (str value)))
    (vector? value)
    (do (.writeByte out 64) (reduce (fn [_ v] (key-write-legacy! out v)) nil value) (.writeByte out 0))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn- key-encode-legacy ^bytes [value]
  (with-open [^KeyBuffer out (.get key-buffers)]
    (key-write-legacy! out value)
    @out))

(defn key-tuple-prefix
  "Encode complete leading vector components, omitting only the outer terminator."
  ^bytes [components]
  (when-not (vector? components)
    (throw (ex-info "Tuple prefix must be a vector" {:prefix components})))
  (let [encoded (key-encode components)]
    (java.util.Arrays/copyOf encoded (dec (alength encoded)))))

(defn key-compact-tuple-prefix
  "Same as key-tuple-prefix since OPT-8."
  ^bytes [components] (key-tuple-prefix components))

;; The one decoder and the one skipper of this grammar (OPT-8 merges the former
;; key-read-key!/key-skip and the OPT-9 member decoder). Both read through an
;; XOR mask (255 for inverted, descending bytes; 0 otherwise) from a position
;; array pos = [index limit]; reading at or past limit is EOF.
(defn- member-byte ^long [^bytes a ^longs pos ^long mask]
  (let [i (aget pos 0)]
    (when (>= i (aget pos 1)) (throw (EOFException.)))
    (aset pos 0 (inc i))
    (bit-xor mask (bit-and 255 (aget a i)))))

(defn- invalid-member! []
  (throw (ex-info "Invalid ordered key encoding" {:type :grain.read-model/invalid-cache})))

(defn- member-string!
  "Read (out non-nil) or skip the units of a string value whose tag was T+t."
  [^bytes a ^longs pos mask t ^StringBuilder out]
  (let [mask (long mask) t (long t)]
    (loop [a? (case t
                0 false
                1 (let [c (member-byte a pos mask)]
                    (when (< c 2) (invalid-member!))
                    (when out (.append out (char c)))
                    true)
                2 (let [hi (member-byte a pos mask) lo (member-byte a pos mask)]
                    (when (zero? hi) (invalid-member!))
                    (when out (.append out (char (bit-or (bit-shift-left hi 8) lo))))
                    false)
                (invalid-member!))]
      (let [b (member-byte a pos mask)]
        (if a?
          (cond
            (zero? b) nil
            (= b 1) (let [c (member-byte a pos mask)]
                      (when (> c 2) (invalid-member!))
                      (when out (.append out (char c)))
                      (recur true))
            (= b 255) (let [hi (member-byte a pos mask) lo (member-byte a pos mask)]
                        (when (zero? hi) (invalid-member!))
                        (when out (.append out (char (bit-or (bit-shift-left hi 8) lo))))
                        (recur false))
            :else (do (when out (.append out (char (inc b)))) (recur true)))
          (if (zero? b)
            (let [e (member-byte a pos mask)]
              (cond
                (zero? e) nil
                (= e 1) (let [c (member-byte a pos mask)]
                          (when (> c 1) (invalid-member!))
                          (when out (.append out (char c)))
                          (recur false))
                :else (do (when out (.append out (char e))) (recur true))))
            (let [lo (member-byte a pos mask)]
              (when out (.append out (char (bit-or (bit-shift-left b 8) lo))))
              (recur false))))))))

(defn- member-read!
  "Read (build?) or skip one value at pos."
  [^bytes a ^longs pos mask build?]
  (let [mask (long mask)
        tag (member-byte a pos mask)]
    (cond
      (= tag 1) nil (= tag 2) false (= tag 3) true
      (and (>= tag 0x10) (<= tag 0x12)) (let [out (when build? (StringBuilder.))]
                                           (member-string! a pos mask (- tag 0x10) out)
                                           (when out (.toString out)))
      (and (>= tag 0x18) (<= tag 0x28))
      (let [n (Math/abs (- tag tag-integer))
            x (loop [i 0 x 0] (if (= i n) x (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))]
        (when build?
          (cond (= tag tag-integer) 0
                (> tag tag-integer) (if (neg? x) (invalid-member!) x)
                :else (unchecked-negate (if (= n 8) (bit-not x) (bit-and (bit-not x) (dec (bit-shift-left 1 (* 8 n)))))))))
      (= tag tag-uuid)
      (let [hi (loop [i 0 x 0] (if (= i 8) x (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))
            lo (loop [i 0 x 0] (if (= i 8) x (recur (inc i) (bit-or (bit-shift-left x 8) (member-byte a pos mask)))))]
        (when build? (UUID. hi lo)))
      (and (>= tag 0x38) (<= tag 0x3A)) (let [out (when build? (StringBuilder.))]
                                           (member-string! a pos mask (- tag tag-keyword) out)
                           (when out (keyword (.toString out))))
      (= tag tag-vector)
      (loop [out (when build? (transient []))]
        (let [i (aget pos 0)]
          (when (>= i (aget pos 1)) (throw (EOFException.)))
          (if (zero? (bit-xor mask (bit-and 255 (aget a i))))
            (do (aset pos 0 (inc i)) (some-> out persistent!))
            (let [v (member-read! a pos mask build?)] (recur (when out (conj! out v)))))))
      :else (invalid-member!))))

(defn key-decode-compact
  "Decode one ordered value, optionally a metadata field of exactly length bytes."
  ([^bytes value] (key-decode-compact value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (let [end (+ offset length)
         pos (doto (long-array 2) (aset 0 offset) (aset 1 end))
         decoded (member-read! value pos 0 true)]
     (when-not (= (aget pos 0) end) (invalid-member!))
     decoded)))

;; Skip one value from index i without decoding it; returns the index after
;; it. Same grammar as member-read!, on primitive indexes (hot read path: every
;; level-zero entry a page or slice returns skips its sort value; descending
;; reads split members into value and id suffix, OPT-5/OPT-7).
(defn- member-at ^long [^bytes a ^long i ^long mask] (bit-xor mask (bit-and 255 (aget a i))))

(defn- skip-member-string ^long [^bytes a ^long i ^long mask ^long t]
  ;; state: 1 = A, 0 = B
  (loop [i (long (case t 0 i 1 (inc i) 2 (+ i 2) (invalid-member!))) state (if (= t 1) 1 0)]
    (let [b (member-at a i mask)]
      (if (= state 1)
        (cond (zero? b) (inc i)
              (= b 1) (recur (+ i 2) 1)
              (= b 255) (recur (+ i 3) 0)
              :else (recur (inc i) 1))
        (if (zero? b)
          (let [e (member-at a (inc i) mask)]
            (cond (zero? e) (+ i 2)
                  (= e 1) (recur (+ i 3) 0)
                  :else (recur (+ i 2) 1)))
          (recur (+ i 2) 0))))))

(defn- skip-member-key ^long [^bytes a ^long i ^long mask]
  (let [tag (member-at a i mask)]
    (cond
      ;; two-argument comparisons only: 3-argument <= is a boxing varargs call
      (and (>= tag 1) (<= tag 3)) (inc i)
      (and (>= tag 0x10) (<= tag 0x12)) (skip-member-string a (inc i) mask (- tag 0x10))
      (and (>= tag 0x18) (<= tag 0x28)) (+ i 1 (Math/abs (- tag tag-integer)))
      (= tag tag-uuid) (+ i 17)
      (and (>= tag 0x38) (<= tag 0x3A)) (skip-member-string a (inc i) mask (- tag tag-keyword))
      (= tag tag-vector) (loop [i (inc i)]
                           (if (zero? (member-at a i mask)) (inc i) (recur (skip-member-key a i mask))))
      :else (long (invalid-member!)))))

(defn key-skip
  "Offset just past the ordered value encoded at offset i of a. (The compact?
   argument is ignored since OPT-8: there is one encoding.)"
  (^long [^bytes a ^long i] (skip-member-key a i 0))
  (^long [^bytes a ^long i _compact?] (skip-member-key a i 0)))

;; OPT-8: level-zero members end in the owner's id suffix
;;   key-encode((str id)) ++ tail
;; where tail is the id's type tag alone (10 string, 20 integer, 30 UUID,
;; 38 keyword, 01 nil, 02/03 boolean: the id is a function of (str id) and its
;; type) or, for a vector id, key-encode(id). Ties therefore still order by
;; (str id) then by the former typed encoding's first byte, exactly as the KF9
;; suffix key-encode((str id)) ++ key-encode(id) did, without its second copy.
(def ^:private id-tail-string (byte-array [tag-string]))
(def ^:private id-tail-integer (byte-array [tag-integer]))
(def ^:private id-tail-uuid (byte-array [tag-uuid]))
(def ^:private id-tail-keyword (byte-array [tag-keyword]))
(def ^:private id-tail-nil (byte-array [1]))
(def ^:private id-tail-false (byte-array [2]))
(def ^:private id-tail-true (byte-array [3]))

(defn key-id-tail
  "Type part of an owner's member suffix (shared arrays: never mutate)."
  ^bytes [id]
  (cond
    (string? id) id-tail-string
    (uuid? id) id-tail-uuid
    (and (integer? id) (<= Long/MIN_VALUE id Long/MAX_VALUE)) id-tail-integer
    (keyword? id) id-tail-keyword
    (nil? id) id-tail-nil
    (boolean? id) (if id id-tail-true id-tail-false)
    :else (key-encode id)))

(defn key-id-suffix
  "key-encode((str id)) ++ key-id-tail(id)."
  ^bytes [id]
  (let [s (key-encode (str id)) t (key-id-tail id)
        out (Arrays/copyOf s (+ (alength s) (alength t)))]
    (System/arraycopy t 0 out (alength s) (alength t))
    out))

;; Lowercase hex digit values by unit (0..256), -1 otherwise.
(def ^:private ^"[J" hex-values
  (let [a (long-array 257 -1)]
    (dotimes [i 10] (aset a (+ 48 i) (long i)))
    (dotimes [i 6] (aset a (+ 97 i) (long (+ 10 i))))
    a))

(defn- member-uuid-fast
  "The UUID whose canonical (str id) is encoded at i (tag 11, first unit,
   35 state-A units, 00), or nil when the bytes are not that shape."
  [^bytes a ^long i ^long mask]
  (when (and (<= (+ i 38) (alength a)) (= 0x11 (member-at a i mask)) (zero? (member-at a (+ i 37) mask)))
    (let [^longs hex hex-values]
      (loop [k 0 hi 0 lo 0]
        (if (= k 36)
          (UUID. hi lo)
          ;; unit k: the raw first unit, then state-A single bytes (unit - 1)
          (let [c (if (zero? k) (member-at a (inc i) mask) (inc (member-at a (+ i 1 k) mask)))]
            (if (or (= k 8) (= k 13) (= k 18) (= k 23))
              (when (= c 45) (recur (inc k) hi lo))
              (let [v (aget hex c)]
                (when-not (neg? v)
                  (if (< k 18)
                    (recur (inc k) (bit-or (bit-shift-left hi 4) v) lo)
                    (recur (inc k) hi (bit-or (bit-shift-left lo 4) v))))))))))))

(defn- member-read-id!
  "Read an owner id suffix at pos: key-encode((str id)), its string read
   through smask, then the ascending tail."
  [^bytes a ^longs pos ^long smask]
  (let [start (aget pos 0)
        ;; common case first: a canonical UUID string + UUID tail (39 bytes)
        fast (when (and (< (+ start 38) (aget pos 1)) (= tag-uuid (bit-and 255 (aget a (+ start 38)))))
               (member-uuid-fast a start smask))]
    (if fast
      (do (aset pos 0 (+ start 39)) fast)
      (let [end (skip-member-key a start smask)
            _ (when (> (inc end) (aget pos 1)) (throw (EOFException.)))
            tail (bit-and 255 (aget a end))]
        (if (= tail tag-uuid)
          (do (aset pos 0 (inc end))
              (or (member-uuid-fast a start smask)
                  (let [p (doto (long-array 2) (aset 0 start) (aset 1 end))
                        s (member-read! a p smask true)]
                    (when-not (string? s) (invalid-member!))
                    (try (UUID/fromString s) (catch IllegalArgumentException _ (invalid-member!))))))
          (let [s (member-read! a pos smask true)]
            (when-not (string? s) (invalid-member!))
            (case (long (member-byte a pos 0))
              0x10 s
              0x20 (try (Long/parseLong s) (catch NumberFormatException _ (invalid-member!)))
              0x38 (if (.startsWith ^String s ":") (keyword (subs s 1)) (invalid-member!))
              1 nil 2 false 3 true
              0x40 (do (aset pos 0 (dec (aget pos 0))) (member-read! a pos 0 true))
              (invalid-member!))))))))

(defn key-descending ^bytes [^bytes key]
  (let [n (alength key) out (byte-array n)]
    (dotimes [i n] (aset out i (unchecked-byte (bit-xor 255 (aget key i)))))
    out))

(defn key-prefix-end
  "Exclusive upper bound of a byte prefix, or nil when it has no upper bound."
  ^bytes [^bytes prefix]
  (loop [i (dec (alength prefix))]
    (when (>= i 0)
      (if (= 255 (bit-and 255 (aget prefix i))) (recur (dec i))
        (let [end (java.util.Arrays/copyOf prefix (inc i))]
          (aset-byte end i (unchecked-byte (inc (bit-and 255 (aget prefix i)))))
          end)))))

(defn key-checked ^bytes [^bytes key]
  (when (> (alength key) 511)
    (throw (ex-info "Keyed-fold key exceeds 511 bytes"
                    {:type :grain.read-model/key-too-large :bytes (alength key)})))
  key)

;; ---------------------------------------------------------------------------
;; Durable namespace reservations
;; ---------------------------------------------------------------------------

(def ^:dynamic ^:private namespace-registry-prefix (byte-array [75 82 57]))
(def ^:dynamic ^:private namespace-counter-key (key-join namespace-registry-prefix (byte-array [0])))
(def ^:dynamic ^:private namespace-data-prefix (byte-array [75 70 57]))
(def ^:private namespace-max-ordinal Integer/MAX_VALUE)

(defn- namespace-failure [message data]
  (throw (ex-info message (assoc data :type :grain.read-model/invalid-registry))))

(defn- namespace-valid-identity? [identity]
  (and (vector? identity) (contains? #{3 4} (count identity))
       (uuid? (nth identity 0)) (qualified-keyword? (nth identity 1))
       (pos-int? (nth identity 2))
       (or (= 3 (count identity))
           (let [scope (nth identity 3)]
             (and (vector? scope) (= 2 (count scope)) (= :event-scope (first scope))
                  (string? (second scope)) (boolean (re-matches #"[0-9a-f]+" (second scope))))))))

(defn- namespace-digest [identity]
  ;; OPT-8: digests keep the former physical encoding, so registry
  ;; addresses (and deferred-build marker addresses) are unchanged.
  (.digest (MessageDigest/getInstance "SHA-256") (key-encode-legacy identity)))

(defn- namespace-forward-key [identity]
  (key-join namespace-registry-prefix (byte-array [1]) (namespace-digest identity)))

(defn- namespace-ordinal-bytes [ordinal]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int ordinal)))))

(defn- namespace-reverse-key [ordinal]
  (key-join namespace-registry-prefix (byte-array [2]) (namespace-ordinal-bytes ordinal)))

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
    (let [value (:value entry) n (:next-id value)]
      (when-not (and (map? value) (= #{:next-id} (set (keys value)))
                     (integer? n) (<= 0 n namespace-max-ordinal))
        (namespace-failure "Invalid namespace registry counter" {}))
      n)))

(defn- namespace-lookup [tx identity next-id]
  (when-let [entry (namespace-read-value tx (namespace-forward-key identity))]
    (let [{stored :identity ordinal :ordinal :as value} (:value entry)]
      (when-not (and (map? value) (= #{:identity :ordinal} (set (keys value)))
                     (namespace-valid-identity? stored)
                     (= (count stored) (alength ^bytes namespace-registry-prefix)) (integer? ordinal)
                     (some? next-id) (<= 0 ordinal) (< ordinal next-id))
        (namespace-failure "Invalid namespace registry forward entry" {:identity identity}))
      (when-not (= identity stored)
        (throw (ex-info "Namespace identity digest collision"
                        {:type :grain.read-model/namespace-collision :identity identity})))
      (when-not (= value (:value (namespace-read-value tx (namespace-reverse-key ordinal))))
        (namespace-failure "Namespace registry inverse does not match" {:identity identity}))
      (namespace-address ordinal))))

(defn- namespace-resolve-known [tx identities]
  (let [next-id (namespace-counter tx)]
    (reduce (fn [out identity]
              (if-let [prefix (namespace-lookup tx identity next-id)]
                (assoc out identity prefix)
                (reduced nil))) {} identities)))

(defn- namespace-validate-registry! [tx next-id]
  (let [counts
        ((:reduce tx) {:prefix namespace-registry-prefix}
         (fn [counts {:keys [k]}]
           (when (<= (alength ^bytes k) (alength ^bytes namespace-registry-prefix))
             (namespace-failure "Invalid namespace registry address" {}))
           (let [kind (bit-and 255 (aget ^bytes k (alength ^bytes namespace-registry-prefix)))]
             (case kind
               0 (when-not (Arrays/equals ^bytes k ^bytes namespace-counter-key)
                   (namespace-failure "Invalid namespace counter address" {}))
               (1 2)
               (let [{stored :identity ordinal :ordinal :as value} (:value (namespace-read-value tx k))]
                 (when-not (and (map? value) (= #{:identity :ordinal} (set (keys value)))
                                (namespace-valid-identity? stored)
                                (= (count stored) (alength ^bytes namespace-registry-prefix))
                                (integer? ordinal) (<= 0 ordinal) (< ordinal next-id))
                   (namespace-failure "Invalid namespace registry record" {}))
                 (when-not (Arrays/equals ^bytes k ^bytes (if (= kind 1) (namespace-forward-key stored) (namespace-reverse-key ordinal)))
                   (namespace-failure "Namespace registry address does not match its record" {}))
                 (when-not (namespace-lookup tx stored next-id)
                   (namespace-failure "Namespace registry forward entry is missing" {:identity stored})))
               (namespace-failure "Unknown namespace registry entry" {}))
             (update counts kind inc))) {0 0 1 0 2 0})]
    (when-not (= {0 1 1 next-id 2 next-id} counts)
      (namespace-failure "Namespace registry counter does not cover its entries" {}))))

(defn- namespace-validate-allocation! [tx]
  (let [initial (namespace-counter tx)]
    (if (some? initial)
      (namespace-validate-registry! tx initial)
      (when ((:reduce tx) {:prefix namespace-registry-prefix}
             (fn [_ _] (reduced true)) false)
        (namespace-failure "Namespace registry counter is missing" {})))))

(defn- namespace-allocate-checked! [tx identities]
  (let [initial (namespace-counter tx)
        ;; This check depends on materialized data outside the registry prefix,
        ;; so it is deliberately not covered by the registry validation proof.
        _ (when (and (nil? initial)
                     ((:reduce tx) {:prefix namespace-data-prefix}
                      (fn [_ _] (reduced true)) false))
            (namespace-failure "Namespace registry is missing while materialized data remains" {}))]
    (first
     (reduce (fn [[out next-id] identity]
               (if-let [prefix (namespace-lookup tx identity next-id)]
                 [(assoc out identity prefix) next-id]
                 (do
                   (when (>= next-id namespace-max-ordinal)
                     (throw (ex-info "Namespace ordinal space exhausted"
                                     {:type :grain.read-model/namespace-exhausted})))
                   (when (or ((:get tx) (namespace-reverse-key next-id))
                             ((:reduce tx) {:prefix (namespace-address next-id)}
                              (fn [_ _] (reduced true)) false))
                     (namespace-failure "Namespace ordinal is already assigned" {:ordinal next-id}))
                   (let [entry (codec/encode {:identity identity :ordinal next-id})]
                     ((:put tx) (namespace-forward-key identity) entry)
                     ((:put tx) (namespace-reverse-key next-id) entry)
                     ((:put tx) namespace-counter-key (codec/encode {:next-id (inc next-id)}))
                     [(assoc out identity (namespace-address next-id)) (inc next-id)]))))
             [{} (or initial 0)] identities))))

(defn- namespace-allocate! [tx identities]
  (if-let [with-validation (:with-validation tx)]
    ;; Checked append preserves every existing mapping and adds one complete
    ;; forward/inverse/counter assignment atomically. Raw or unknown mutations
    ;; invalidate the backend's proof; unsupported stores validate each time.
    (with-validation namespace-registry-prefix namespace-validate-allocation!
      #(namespace-allocate-checked! tx identities))
    (do (namespace-validate-allocation! tx)
        (namespace-allocate-checked! tx identities))))

(defn namespace-resolve! [cache identities]
  (when-not (and cache (sequential? identities) (every? namespace-valid-identity? identities))
    (throw (ex-info "Expected cache and tenant/model/version namespace identities with optional event scope"
                    {:type :grain.read-model/invalid-namespace})))
  (let [groups (group-by count (distinct identities))
        resolve! (fn [identities]
                   (if (empty? identities) {}
                     (or (kv/transact cache :read #(namespace-resolve-known % identities))
                         (kv/transact cache :write #(namespace-allocate! % identities)))))
        ordinary (resolve! (get groups 3))
        ;; Scoped namespaces have their own address and registry prefixes. An
        ;; older reader can still reserve ordinary KF9 namespaces in this cache.
        scoped (when (seq (get groups 4))
                 (let [prefix (byte-array [75 82 83 57])]
                   (binding [namespace-registry-prefix prefix
                             namespace-counter-key (key-join prefix (byte-array [0]))
                             namespace-data-prefix (byte-array [75 70 83 57])]
                     (resolve! (get groups 4)))))]
    (merge ordinary scoped)))

;; ---------------------------------------------------------------------------
;; Shared identities and membership bitmaps
;; ---------------------------------------------------------------------------

(defn membership-number-bytes ^bytes [n]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int n)))))
(defn membership-number-value [^bytes raw] (.getInt (ByteBuffer/wrap raw)))
(defn- membership-slot [b kind suffix] (key-checked (key-join b (byte-array [kind]) suffix)))
(defn membership-ordinal [tx b id]
  (some-> ((:get tx) (membership-slot b 7 (key-encode id))) membership-number-value))
(defn membership-ensure-ordinal! [tx b id]
  (or (membership-ordinal tx b id)
      (let [counter (membership-slot b 9 (byte-array 0))
            next (or (some-> ((:get tx) counter) membership-number-value) 0)]
        (when (>= next Integer/MAX_VALUE)
          (throw (ex-info "Model ordinal space exhausted" {})))
        ((:put tx) (membership-slot b 7 (key-encode id)) (membership-number-bytes next))
        ((:put tx) (membership-slot b 8 (membership-number-bytes next)) (codec/encode id))
        ((:put tx) counter (membership-number-bytes (inc next)))
        next)))

(defn membership-decode ^RoaringBitmap [^bytes raw]
  (doto (RoaringBitmap.) (.deserialize (ByteBuffer/wrap raw))))
(defn membership-encode ^bytes [^RoaringBitmap bits]
  (let [out (ByteBuffer/allocate (.serializedSizeInBytes bits))]
    (.serialize bits out)
    (.array out)))
(defn membership-transaction [tx] (assoc tx :ai.obney.grain.read-model-processor-v2.core.span-membership/pending (HashMap.)))
(defn membership-bitmap ^RoaringBitmap [tx ^bytes k]
  (let [^HashMap pending (:ai.obney.grain.read-model-processor-v2.core.span-membership/pending tx) address (ByteBuffer/wrap k)]
    (or (when pending (.get pending address))
        (when-let [raw ((:get tx) k)] (membership-decode raw))
        (RoaringBitmap.))))
(defn membership-put! [tx ^bytes k bits]
  (if-let [^HashMap pending (:ai.obney.grain.read-model-processor-v2.core.span-membership/pending tx)]
    (.put pending (ByteBuffer/wrap k) bits)
    ((:put tx) k (membership-encode bits))))
(defn membership-delete! [tx ^bytes k]
  (when-let [^HashMap pending (:ai.obney.grain.read-model-processor-v2.core.span-membership/pending tx)] (.remove pending (ByteBuffer/wrap k)))
  ((:delete tx) k))
(defn membership-flush! [tx observe]
  (when-let [^HashMap pending (:ai.obney.grain.read-model-processor-v2.core.span-membership/pending tx)]
    (doseq [^java.util.Map$Entry entry (.entrySet pending)]
      (let [raw (membership-encode (.getValue entry))]
        ((:put tx) (.array ^ByteBuffer (.getKey entry)) raw)
        (observe :membership-written (alength raw))))))

;; ---------------------------------------------------------------------------
;; Counted ranked indexes
;; ---------------------------------------------------------------------------

;; Adapted from FoundationDB RankedSet.java, commit
;; 8a20d30f318e529fa30084b265563e64eb3956e1.
;; Copyright 2015-2018 Apple Inc. and the FoundationDB project authors.
;; Licensed under the Apache License, Version 2.0.
;; https://www.apache.org/licenses/LICENSE-2.0
;; Modifications: synchronous Grain KV operations, raw ordered byte-key packing,
;; transactional read/modify/write in place of FDB atomic ADD, unique sets only.
;; Grain extension: optional span membership summaries and filtered navigation.
;; Level-zero members hold opaque payloads with implicit count 1. Sentinels and
;; upper levels retain little-endian span counts. See resources/META-INF/licenses.


(def ranked-empty-key (byte-array 0))
;; Promotion: a member reaches level l iff the low 4l bits of its hash are zero
;; (probability 16^-l). Before OPT-3 the hash was ThreadLocalRandom, so equal
;; event streams produced different bytes. Promotion is now SipHash-2-4 of
;; (index, direction, member) under a 128-bit key. Keyed caches use a random key
;; generated once per cache and stored in it (keyed-promotion-key); callers that
;; choose ids or values therefore cannot grind levels. :read-model/promotion-key
;; pins a key (tests, benchmarks, byte-parity runs). No reader or remover
;; rehashes: levels are discovered from storage (ranked-remove!, rank, nth,
;; ranges), so caches built with random or differently keyed promotion stay
;; valid and can be mixed with new members.
(defn ranked-random-hash [_] (.nextInt (ThreadLocalRandom/current)))

(def ^:private ^"[J" ranked-default-promotion-key
  ;; Public key for standalone ranked configs only (tests, tools). Keyed indexes
  ;; never use it: their configs require a cache or pinned key.
  (long-array [0x677261696e2d726d 0x702d70726f6d6f74]))

(defmacro ^:private sip-round [[v0 v1 v2 v3] & body]
  `(let [~v0 (unchecked-add ~v0 ~v1) ~v1 (Long/rotateLeft ~v1 13) ~v1 (bit-xor ~v1 ~v0)
         ~v0 (Long/rotateLeft ~v0 32)
         ~v2 (unchecked-add ~v2 ~v3) ~v3 (Long/rotateLeft ~v3 16) ~v3 (bit-xor ~v3 ~v2)
         ~v0 (unchecked-add ~v0 ~v3) ~v3 (Long/rotateLeft ~v3 21) ~v3 (bit-xor ~v3 ~v0)
         ~v2 (unchecked-add ~v2 ~v1) ~v1 (Long/rotateLeft ~v1 17) ~v1 (bit-xor ~v1 ~v2)
         ~v2 (Long/rotateLeft ~v2 32)]
     ~@body))

;; SipHash-2-4 over the message a[off..] ++ b, read in place (no concatenation).
(defn- sip-byte ^long [^bytes a ^long off ^bytes b ^long i]
  (let [n (- (alength a) off)]
    (bit-and 0xff (if (< i n) (aget a (unchecked-int (+ off i))) (aget b (unchecked-int (- i n)))))))

(defn- sip-word ^long [^bytes a ^long off ^bytes b ^long i]
  ;; Little-endian 8 bytes at message offset i.
  (let [n (- (alength a) off)]
    (if (or (>= i n) (<= (+ i 8) n))
      (let [^bytes src (if (< i n) a b) j (unchecked-int (if (< i n) (+ off i) (- i n)))]
        (bit-or (bit-and 0xff (aget src j))
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 1))) 8)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 2))) 16)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 3))) 24)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 4))) 32)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 5))) 40)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 6))) 48)
                (bit-shift-left (bit-and 0xff (aget src (unchecked-add-int j 7))) 56)))
      (loop [k 7 w 0]
        (if (neg? k) w (recur (dec k) (bit-or (bit-shift-left w 8) (sip-byte a off b (+ i k)))))))))

(defn siphash24
  "SipHash-2-4 (Aumasson and Bernstein) under the 128-bit key [k0 k1] of a[off..] ++ b."
  ^long [^longs key ^bytes a ^long off ^bytes b]
  (let [len (+ (- (alength a) off) (alength b)) end (- len (rem len 8))
        k0 (aget key 0) k1 (aget key 1)]
    (loop [i 0
           v0 (bit-xor k0 0x736f6d6570736575) v1 (bit-xor k1 0x646f72616e646f6d)
           v2 (bit-xor k0 0x6c7967656e657261) v3 (bit-xor k1 0x7465646279746573)]
      (if (< i end)
        (let [m (sip-word a off b i) v3 (bit-xor v3 m)]
          (sip-round [v0 v1 v2 v3]
            (sip-round [v0 v1 v2 v3]
              (recur (+ i 8) (bit-xor v0 m) v1 v2 v3))))
        (let [m (loop [k (dec len) w (bit-shift-left len 56)]
                  (if (< k end) w
                    (recur (dec k) (bit-or w (bit-shift-left (sip-byte a off b k) (* 8 (- k end)))))))
              v3 (bit-xor v3 m)]
          (sip-round [v0 v1 v2 v3]
            (sip-round [v0 v1 v2 v3]
              (let [v0 (bit-xor v0 m) v2 (bit-xor v2 0xff)]
                (sip-round [v0 v1 v2 v3]
                  (sip-round [v0 v1 v2 v3]
                    (sip-round [v0 v1 v2 v3]
                      (sip-round [v0 v1 v2 v3]
                        (bit-xor v0 v1 v2 v3)))))))))))))

(defn ranked-promotion-hash
  "Promotion hash of member key under the 128-bit key k (long[2], or a delay
   of one, forced only here) and the index prefix from byte offset off (keyed
   indexes skip their namespace ordinal, an allocation-order artifact, so equal
   members level equally under one key)."
  [k ^bytes prefix off ^bytes key]
  (let [k (if (instance? clojure.lang.IDeref k) @k k)]
    (when-not k (throw (ex-info "Keyed ranked index written without a promotion key" {})))
    (siphash24 k prefix (long off) key)))

(defn ranked-config
  "Without hash-fn, members are hashed with ranked-promotion-hash: under the
   transaction's ::promotion-key when the config is :keyed? (required), else
   under the public default key."
  ([prefix] {:prefix prefix :levels 6 :hash-offset 0})
  ([prefix hash-fn levels]
   (when-not (<= 2 levels 8)
     (throw (IllegalArgumentException. "Ranked index requires 2 to 8 levels")))
   {:prefix prefix :hash-fn hash-fn :levels levels}))

(defn- ranked-hash [tx cfg ^bytes key]
  (if-let [f (:hash-fn cfg)] (f key)
    (ranked-promotion-hash (or (::promotion-key tx) (when-not (:keyed? cfg) ranked-default-promotion-key))
                           (:prefix cfg) (:hash-offset cfg 0) key)))

;; Per-cache promotion key. Stored at KP9 0xFF: "KP9" differs at byte 1 from
;; every other internal key space (KF9 KFS9 KR9 KRS9 KB9), and 0xFF never occurs
;; in UTF-8, so no legacy string cache key (format-key / suffix-key) can equal it.
(def ^:private promotion-key-address (byte-array [75 80 57 -1]))

(defn- promotion-key-longs ^longs [^bytes raw]
  ;; 16 bytes, k0 = bytes 0-7 and k1 = bytes 8-15 little-endian (SipHash reference).
  (when-not (= 16 (alength raw))
    (throw (ex-info "Promotion key must be 16 bytes" {:type :grain.read-model/invalid-promotion-key})))
  (let [bb (.order (ByteBuffer/wrap raw) ByteOrder/LITTLE_ENDIAN)]
    (long-array [(.getLong bb 0) (.getLong bb 8)])))

(defn pinned-promotion-key
  "Parse :read-model/promotion-key (16 bytes or 32 hex digits) into long[2].
   TEST/BENCHMARK ONLY: a pinned key always wins over the cache's stored key
   and is never persisted, so a pin left in production configuration makes
   promotion levels predictable again (grindable by writers of indexed values
   or ids). Production contexts must not set :read-model/promotion-key."
  ^longs [pinned]
  (promotion-key-longs
    (cond (bytes? pinned) pinned
          (and (string? pinned) (re-matches #"[0-9a-fA-F]{32}" pinned)) (.parseHex (HexFormat/of) pinned)
          :else (throw (ex-info "Promotion key must be 16 bytes or 32 hexadecimal digits"
                                {:type :grain.read-model/invalid-promotion-key})))))

(defn keyed-promotion-key
  "The cache's promotion key, read in the write transaction tx. The first
   writer of a cache without one (new, or built before OPT-3) generates it with
   SecureRandom and stores it in the same transaction; LMDB's single writer
   serializes first opens, so every process sees one committed key. Earlier
   members keep their stored levels; nothing rehashes. The key is stored in
   plain form in the cache: anyone who can read the LMDB file (or a backup)
   can grind levels, so treat cache files as trusted/sensitive."
  ^longs [tx]
  (promotion-key-longs
    (or ((:get tx) promotion-key-address)
        (let [raw (byte-array 16)]
          (.nextBytes (java.security.SecureRandom.) raw)
          ((:put tx) promotion-key-address raw)
          raw))))

(defn- with-promotion-key
  "tx with the cache's promotion key attached as a delay, read or created in tx
   only when a member is hashed. A pinned :read-model/promotion-key wins and is
   never persisted. Used by keyed-apply-batch! and by the deferred build's
   transactions (INT: OPT-3 x OPT-4), so a deferred build levels members under
   the same key as the incremental path."
  [ctx tx]
  (assoc tx ::promotion-key (delay (if-let [pinned (:read-model/promotion-key ctx)]
                                     (pinned-promotion-key pinned)
                                     (keyed-promotion-key tx)))))
(defn- ranked-level-prefix ^bytes [cfg level]
  (let [^bytes p (:prefix cfg) n (alength p) a (Arrays/copyOf p (inc n))]
    (aset a n (byte level)) a))
(defn ranked-prefix
  "Level prefix. Configs from a write session's cache share one read-only copy
   per level (callers never mutate prefixes)."
  ^bytes [cfg level]
  (if-let [^objects cached (::level-prefixes cfg)]
    (let [level (int level)]
      (or (aget cached level)
          (let [p (ranked-level-prefix cfg level)] (aset cached level p) p)))
    (ranked-level-prefix cfg level)))
(defn ranked-pack ^bytes [cfg level ^bytes key]
  ;; One allocation: prefix, level byte and member.
  (let [^bytes p (:prefix cfg) n (alength p) k (alength key) a (byte-array (+ n 1 k))]
    (System/arraycopy p 0 a 0 n)
    (aset a n (byte level))
    (System/arraycopy key 0 a (inc n) k)
    (key-checked a)))
(defn ranked-unpack ^bytes [cfg ^bytes key]
  (Arrays/copyOfRange key (inc (alength ^bytes (:prefix cfg))) (alength key)))
(defn ranked-after
  "The scan bound just after key k: the smallest key above k among storable
   keys (at most 511 bytes, LMDB's key limit), usable as an exclusive :to
   (\"up to and including k\") or an inclusive :from (\"strictly after k\").
   Below the limit that is k ++ 00. For a key of exactly 511 bytes k ++ 00
   would not fit the store's key buffer (FINAL, OPT-8 audit C1: a descending
   page crossing a 511-byte level-zero member threw BufferOverflowException);
   no storable key lies strictly between k and its prefix end (any key above
   k that starts with k is longer than 511 bytes; after the last non-FF byte
   k holds only FF bytes), so the prefix end of k is the same bound. Keyed
   keys start with a non-FF prefix byte, so it always exists."
  ^bytes [^bytes k]
  (if (< (alength k) 511)
    (Arrays/copyOf k (inc (alength k)))
    (or (key-prefix-end k)
        (throw (ex-info "No scan bound after an all-FF key" {:bytes (alength k)})))))
(defn ranked-encode-count ^bytes [n]
  (.array (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN) (.putLong (long n)))))
(defn ranked-decode-count [^bytes v]
  (when v (.getLong (doto (ByteBuffer/wrap v) (.order ByteOrder/LITTLE_ENDIAN)))))
(defn ranked-check-key! [^bytes key]
  (when (zero? (alength key)) (throw (IllegalArgumentException. "Empty key not allowed"))))
(defn- ranked-entry-count [cfg level ^bytes k v]
  (if (and (zero? level) (> (alength k) (inc (alength ^bytes (:prefix cfg)))))
    1 (ranked-decode-count v)))

;; Span-count transaction cache (OPT-10, W9). Upper-level (level >= 1) span
;; counts are read-modify-written on every member add/remove; within one
;; keyed batch transaction the same few top-level spans are rewritten many
;; times. With a cache installed (span-count-transaction, keyed-apply-batch!
;; only), ranked-increment! keeps the new count in memory (one LMDB get on the
;; span's first increment in the transaction, none after) and
;; span-count-flush! writes each changed span once. Invariants:
;; - Only VALUES are deferred. Every key insertion (ranked-set-count!) and
;;   deletion (ranked-delete-count!) still goes to LMDB immediately and drops
;;   the cached entry, so key-only scans (ranked-previous-key) see the exact
;;   key set, and a deleted span is never resurrected by the flush.
;; - Every reader of an upper-level count goes through the cache: point reads
;;   (ranked-count-key) and every count-summing scan (ranked-count-range,
;;   ranked-level-entries, ranked-get-nth, ranked-rank) via ranked-scan-count.
;; - keyed-apply-batch! flushes (and clears) the cache after all index work and
;;   before keyed-relevel!, membership-flush! and commit; the stored bytes are
;;   exactly those of writing every increment through (same final counts, same
;;   keys). Level-zero entries (members, sentinels) are never cached.
;; The same transaction also caches index group records (W9, level-6 rows;
;; keyed-update-group-at!) and text term counts (W10; text-search-change-count!),
;; which are read-modify-written per change and read by nothing else in a
;; write transaction; each changed row is written (or deleted) once per flush.
;; INVARIANT (OPT-10 audit C3): unlike span keys, a group row or term-count row
;; CREATED in a batch reaches LMDB only at a flush (its key is not inserted
;; immediately), so the "exact key set" guarantee holds for span counts only.
;; No code may scan the group (level 6) or term-count (text kind 1) prefixes
;; inside a batch transaction before the flush; today the only such scans (the
;; cold builders' groups-empty / keyed-empty-ranks? checks) run once at batch
;; start, before any cached change, and searches and group enumeration are
;; read paths.
;; Bounded (FINAL, OPT-10 audit C1): keyed-apply-batch! flushes all three
;; caches between owners whenever together they hold more than
;; count-cache-flush-entries entries (count-caches-bound!). A flush only writes
;; values and leaves LMDB holding exactly the rows writing every change through
;; would have left at that point, and later changes re-read them, so the
;; stored bytes do not depend on when (or how often) flushes happen.
;; Flushes write in key order. Measured (OPT-10 REPORT and audit): w5x touch-3
;; LMDB ops 92.9 -> 65.9 per event (thread CPU about -3% to -12%), I8 text
;; insert ops -54%.
(defn span-count-transaction
  "Install the batch transaction's count caches: upper-level span counts
   (here), index group counts (keyed-update-group-at!, group-count-flush!) and
   text term counts (text-search-change-count!, term-count-flush!)."
  [tx]
  (assoc tx ::span-counts (HashMap.) ::group-counts (HashMap.) ::term-counts (HashMap.)))
(defn- ranked-scan-count
  "Count of a scanned entry, through the transaction's span-count cache."
  [^HashMap cache cfg level ^bytes k v]
  (or (when (and cache (pos? (long level)))
        (when-let [^longs c (.get cache (ByteBuffer/wrap k))] (aget c 0)))
      (ranked-entry-count cfg level k v)))
(def ^:private count-cache-entry-order
  ;; Flushes write in key order: deterministic and B-tree friendly.
  (reify Comparator
    (compare [_ a b]
      (Arrays/compareUnsigned (.array ^ByteBuffer (.getKey ^java.util.Map$Entry a))
                              (.array ^ByteBuffer (.getKey ^java.util.Map$Entry b))))))
(defn- count-cache-entries
  "The cache's entries in key order."
  [^HashMap cache]
  (doto (ArrayList. (.entrySet cache)) (.sort count-cache-entry-order)))
(defn span-count-flush! [tx]
  (when-let [^HashMap cache (::span-counts tx)]
    (doseq [^java.util.Map$Entry e (count-cache-entries cache)]
      ((:put tx) (.array ^ByteBuffer (.getKey e)) (ranked-encode-count (aget ^longs (.getValue e) 0))))
    (.clear cache)))
(defn ranked-count-key [tx cfg level key]
  (let [k (ranked-pack cfg level key)
        ^HashMap cache (when (pos? (long level)) (::span-counts tx))]
    (or (when cache (when-let [^longs c (.get cache (ByteBuffer/wrap k))] (aget c 0)))
        (when-let [v ((:get tx) k)] (ranked-entry-count cfg level k v)))))
(defn ranked-set-count! [tx cfg level key n]
  (let [k (ranked-pack cfg level key)]
    (when-let [^HashMap cache (::span-counts tx)] (.remove cache (ByteBuffer/wrap k)))
    ((:put tx) k (ranked-encode-count n))))
(defn- ranked-delete-count! [tx cfg level key]
  (let [k (ranked-pack cfg level key)]
    (when-let [^HashMap cache (::span-counts tx)] (.remove cache (ByteBuffer/wrap k)))
    ((:delete tx) k)))
(defn ranked-increment! [tx cfg level key delta]
  ;; LMDB permits one writer. All reads/updates use that same write transaction.
  (let [k (ranked-pack cfg level key)
        ^HashMap cache (when (pos? (long level)) (::span-counts tx))]
    (if cache
      (let [address (ByteBuffer/wrap k)]
        (if-let [^longs c (.get cache address)]
          (aset c 0 (+ (aget c 0) (long delta)))
          (let [v ((:get tx) k)]
            (.put cache address (long-array [(+ (long (or (when v (ranked-entry-count cfg level k v)) 0)) (long delta))])))))
      (let [v ((:get tx) k)]
        ((:put tx) k (ranked-encode-count (+ (or (when v (ranked-entry-count cfg level k v)) 0) delta)))))))

(defn ranked-init! [tx cfg]
  ;; Upstream initLevels: preserve an already initialized sentinel.
  (dotimes [level (:levels cfg)]
    (when (nil? (ranked-count-key tx cfg level ranked-empty-key)) (ranked-set-count! tx cfg level ranked-empty-key 0))))
(defn ranked-ensure-init! [tx cfg]
  (when (nil? (ranked-count-key tx cfg 0 ranked-empty-key)) (ranked-init! tx cfg)))
(defn ranked-contains? [tx cfg key]
  (ranked-check-key! key) (pos? (or (ranked-count-key tx cfg 0 key) 0)))

(defn ranked-previous-key [tx cfg level key or-equal?]
  ;; Upstream getPreviousKey. Range conflicts are unnecessary under LMDB's
  ;; serialized writer; this read and its following mutation cannot interleave.
  ;; The level prefix is also the packed empty sentinel key.
  (or ((:reduce tx) {:prefix (ranked-prefix cfg level)
                    :from (ranked-prefix cfg level)
                    :to ((if or-equal? ranked-after identity) (ranked-pack cfg level key)) :reverse? true}
       (fn [_ {:keys [k]}] (reduced (ranked-unpack cfg k))) nil)
      (throw (ex-info "no key found on level" {:level level}))))

(defn ranked-count-range [tx cfg level begin end]
  ;; Upstream countRange; bounds are [begin,end).
  (let [cache (::span-counts tx)]
    ((:reduce tx) (cond-> {:prefix (ranked-prefix cfg level)}
                   begin (assoc :from (ranked-pack cfg level begin))
                   end (assoc :to (ranked-pack cfg level end)))
     (fn [n {:keys [k v]}] (+ n (ranked-scan-count cache cfg level k v))) 0)))

(defn ranked-membership-key [cfg level key] (ranked-pack cfg (+ 16 level) key))

(defn- ranked-range-members ^RoaringBitmap [tx cfg level begin end]
  ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level begin)
                :to (ranked-pack cfg level end)}
   (fn [^RoaringBitmap out {:keys [k]}]
     (let [member (ranked-unpack cfg k) address (ranked-membership-key cfg level member)]
       (if (zero? level)
         (when (pos? (alength ^bytes member))
           (.add out (int (membership-number-value ((:get tx) address)))))
         (.or out (membership-bitmap tx address)))) out)
   (RoaringBitmap.)))

(defn ranked-add!
  "Add a unique member, optionally storing its opaque payload at level zero.
   Duplicate adds leave the existing payload intact. Initialize before adding."
  ([tx cfg key] (ranked-add! tx cfg key (ranked-encode-count 1) nil))
  ([tx cfg key value] (ranked-add! tx cfg key value nil))
  ([tx cfg key value ordinal]
    (ranked-check-key! key)
    (when (and (:membership? cfg) (not (and (integer? ordinal) (<= 0 ordinal Integer/MAX_VALUE))))
      (throw (ex-info "Membership index requires a model-local ordinal" {})))
    (let [k0 (ranked-pack cfg 0 key)
          v0 ((:get tx) k0)]
      ;; Inline ranked-contains? (OPT-1), reusing the packed level-zero key for
      ;; the put. Hash only a new member (OPT-3): forcing the promotion-key
      ;; delay on a duplicate add would create a cache key for nothing.
      (if (pos? (or (when v0 (ranked-entry-count cfg 0 k0 v0)) 0)) false
        (let [hash (ranked-hash tx cfg key)]
        (do
          ;; Same lowest-to-highest order as upstream's split dependencies.
          (dotimes [level (:levels cfg)]
            (cond
              (zero? level)
              (do ((:put tx) k0 value)
                  (when (:membership? cfg)
                    ((:put tx) (ranked-membership-key cfg 0 key) (membership-number-bytes ordinal))))
              (not (zero? (bit-and hash (dec (bit-shift-left 1 (* 4 level))))))
              (let [prev (ranked-previous-key tx cfg level key false)]
                (ranked-increment! tx cfg level prev 1)
                (when (:membership? cfg)
                  (let [address (ranked-membership-key cfg level prev)
                        bits (membership-bitmap tx address)]
                    (.add bits (int ordinal))
                    (membership-put! tx address bits))))
              :else
              ;; addInsertLevelKey: split the preceding span at this key using
              ;; the already-updated next lower level.
              (let [prev (ranked-previous-key tx cfg level key false)
                    prev-count (ranked-count-key tx cfg level prev)
                    new-prev-count (ranked-count-range tx cfg (dec level) prev key)]
                (ranked-set-count! tx cfg level prev new-prev-count)
                (ranked-set-count! tx cfg level key (inc (- prev-count new-prev-count)))
                (when (:membership? cfg)
                  (let [address (ranked-membership-key cfg level prev)
                        right (membership-bitmap tx address)
                        left (ranked-range-members tx cfg (dec level) prev key)]
                    (.andNot right left)
                    (.add right (int ordinal))
                    (membership-put! tx address left)
                    (membership-put! tx (ranked-membership-key cfg level key) right))))))
          true))))))

(defn- ranked-append!
  "Insert a captured-hash member at the maximum of an initially empty index.
   Promoted keys split the last span: its old contents stay unchanged."
  [tx cfg key value ordinal hash previous]
  (if (ranked-contains? tx cfg key) false
    (do
      (dotimes [level (:levels cfg)]
        (cond
          (zero? level)
          (do ((:put tx) (ranked-pack cfg 0 key) value)
              (when (:membership? cfg)
                ((:put tx) (ranked-membership-key cfg 0 key) (membership-number-bytes ordinal))))
          (not (zero? (bit-and hash (dec (bit-shift-left 1 (* 4 level))))))
          (let [prev (aget ^objects previous level)]
            (ranked-increment! tx cfg level prev 1)
            (when (:membership? cfg)
              (let [address (ranked-membership-key cfg level prev)
                    bits (membership-bitmap tx address)]
                (.add bits (int ordinal))
                (membership-put! tx address bits))))
          :else
          (do
            (ranked-set-count! tx cfg level key 1)
            (when (:membership? cfg)
              (let [address (ranked-membership-key cfg level (aget ^objects previous level))]
                ;; A split persists even an empty sentinel summary upstream.
                (membership-put! tx address (membership-bitmap tx address))
                (membership-put! tx (ranked-membership-key cfg level key)
                                 (doto (RoaringBitmap.) (.add (int ordinal))))))
            (aset ^objects previous level key))))
      true)))

(defn ranked-remove! [tx cfg key]
  (ranked-check-key! key)
  (if-not (ranked-contains? tx cfg key) false
    (let [ordinal (when (:membership? cfg)
                    (membership-number-value ((:get tx) (ranked-membership-key cfg 0 key))))]
      (dotimes [level (:levels cfg)]
        (if (zero? level)
          (do ((:delete tx) (ranked-pack cfg 0 key))
              (when (:membership? cfg) ((:delete tx) (ranked-membership-key cfg 0 key))))
          ;; Upstream remove, unique-set branch. Inspect stored levels rather
          ;; than rehashing: deletion stays correct if hash config changes.
          (let [count (ranked-count-key tx cfg level key)
                prev (ranked-previous-key tx cfg level key false)]
            (when count (ranked-delete-count! tx cfg level key))
            (ranked-increment! tx cfg level prev (+ -1 (or count 0)))
            (when (:membership? cfg)
              (let [address (ranked-membership-key cfg level prev)
                    bits (membership-bitmap tx address)]
                (when count
                  (.or bits (membership-bitmap tx (ranked-membership-key cfg level key)))
                  (membership-delete! tx (ranked-membership-key cfg level key)))
                (.remove bits (int ordinal))
                (membership-put! tx address bits))))))
      true)))

(defn ranked-size [tx cfg]
  ;; Upstream size sums the highest level. It is not a separate total counter.
  (ranked-count-range tx cfg (dec (:levels cfg)) nil nil))

(defn ranked-members
  "Included model ordinals from the disjoint top spans, without result reads."
  ^RoaringBitmap [tx cfg observe]
  (let [level (dec (:levels cfg))]
    ((:reduce tx) {:prefix (ranked-prefix cfg level)}
     (fn [^RoaringBitmap out {:keys [k]}]
       (when-let [raw ((:get tx) (ranked-membership-key cfg level (ranked-unpack cfg k)))]
         (observe :membership-read (alength ^bytes raw))
         (.or out (membership-decode raw))) out)
     (RoaringBitmap.))))

(deftype RankedVirtualMember [^bytes member ^bytes payload])

(defn- ranked-virtual-bound [^java.util.List virtual ^bytes boundary]
  (if (nil? boundary) (.size virtual)
    (loop [lo 0 hi (.size virtual)]
      (if (= lo hi) lo
        (let [mid (quot (+ lo hi) 2)
              member (.-member ^RankedVirtualMember (.get virtual mid))]
          (if (neg? (Arrays/compareUnsigned member boundary))
            (recur (inc mid) hi) (recur lo mid)))))))

;; Descending order without a stored descending copy (OPT-5/OPT-7).
;; A stored descending copy ordered members by inverted value bytes followed by
;; the ascending id suffix: values descend, and members with equal values
;; (one "run") stay ascending by id. The same order is read from the ascending
;; structure: runs are visited from the top down and each run is emitted
;; ascending. A navigator exposes one item sequence (members, or the members of
;; a filtered page plus virtual ones) by ascending position:
;;   :total            item count
;;   :forward  [p n]   items at ascending positions [p, p+n)
;;   :reverse  [p n]   items at reverse positions [p, p+n) (position p is the
;;                     item at ascending position total-1-p), highest first
;;   :rank     [key]   number of items whose member sorts below key
;;   :run      [v n]   the first n items of the run with value bytes v (the
;;                     run is known to hold at least n items)
;; Items are object arrays [member payload] (filter navigators mark virtual
;; members with a third slot). Within one value run, members share their
;; leading value encoding, which is prefix-free, so a run is exactly the
;; members starting with its value bytes.
;; Read-cost trade-off (OPT-57 audit condition 3, accepted for admin list
;; views): unique-value descending pages cost the same as before, and real
;; list-view mixes were neutral (L5 replica 0.93-1.10x CPU). Descending reads
;; over LOW-CARDINALITY orderings pay more: an offset inside a long equal-value
;; run needs two rank walks plus a forward read (1-row page inside a 6k run
;; ~1.85x, 15 -> 35 LMDB ops; 25-row middle page ~1.34x); filtered descending
;; pages over such runs up to ~1.7-1.9x; joined descending pages ordered by a
;; 3-value index ~1.4x (CPU in span bitmap work, not I/O). Optional
;; refinements: take a run's end from its level-6 group :total instead of a
;; second rank walk; scan short in-run offsets instead of get-nth; share
;; cached span counts between the rank and walk passes.
(defn- ranked-run-value ^bytes [vlen ^objects item]
  (let [^bytes m (aget item 0)] (Arrays/copyOf m (int (vlen m)))))

(defn ranked-desc-items
  "Items at descending positions [offset, offset+n): runs of equal value in
   descending value order, each run ascending. Exactly the order of a stored
   descending copy. Cost: one reverse walk when offset starts a run; for long
   runs and offsets inside a run, also run bounds by rank and a forward walk."
  [nav vlen offset n]
  (let [total (long ((:total nav)))
        out (ArrayList.)
        value (partial ranked-run-value vlen)
        need #(- (long n) (.size out))
        ;; xs: reverse items starting at the top of a run; requested = how many
        ;; were asked for (fewer returned means the sequence ended there).
        from-top (fn [^java.util.List xs requested]
                   (let [cnt (.size xs) complete? (< cnt (long requested))]
                     (loop [i 0]
                       (when (and (< i cnt) (pos? (need)))
                         (let [^bytes v (value (.get xs i))
                               j (long (loop [j (inc i)]
                                         (if (and (< j cnt) (Arrays/equals v ^bytes (value (.get xs j)))) (recur (inc j)) j)))]
                           (if (or (< j cnt) complete?)
                             (do (loop [q (dec j)]
                                   (when (and (>= q i) (pos? (need))) (.add out (.get xs q)) (recur (dec q))))
                                 (recur j))
                             ;; The last run may continue below xs. It holds more
                             ;; items than are still needed (xs has need+1 items),
                             ;; so the page ends with that run's first items.
                             (.addAll out ((:run nav) v (need)))))))))]
    (when (and (pos? n) (< -1 offset total))
      (if (zero? offset)
        (from-top ((:reverse nav) 0 (inc n)) (inc n))
        ;; Look one item above offset to learn whether offset starts a run.
        (let [^java.util.List ys ((:reverse nav) (dec offset) (+ 2 n))]
          (if (or (< (.size ys) 2) (not (Arrays/equals ^bytes (value (.get ys 0)) ^bytes (value (.get ys 1)))))
            (from-top (.subList ys 1 (.size ys)) (inc n))
            ;; offset is inside a run [s, e) (ascending positions). Reverse
            ;; position offset is ascending position a, and c of the run's
            ;; items lie above a; descending position offset is the run's
            ;; item at ascending position s + c.
            (let [v (value (.get ys 1))
                  s (long ((:rank nav) v)) e (long ((:rank nav) (key-prefix-end v)))
                  a (- total 1 offset) c (- e 1 a)]
              (.addAll out ((:forward nav) (+ s c) (min (need) (- e s c))))
              (when (and (pos? (need)) (pos? s))
                (from-top ((:reverse nav) (- total s) (inc (need))) (inc (need)))))))))
    out))

(defn- ranked-filter-navigator
  "Navigator over the members of cfg whose model ordinal is in candidates, plus
   virtual members (sorted by member) placed in member order. Span match counts
   are cached across calls. Also returns :visits and :leaves counters."
  [tx cfg ^RoaringBitmap candidates observe ^java.util.List virtual]
  (let [counts (HashMap.) visits (volatile! 0) leaves (volatile! 0)
        top (dec (:levels cfg))
        matches (fn [level ^bytes member]
                  (if (and (zero? level) (zero? (alength member))) 0
                    (let [address (ranked-membership-key cfg level member)
                          lookup (ByteBuffer/wrap address)]
                      (or (.get counts lookup)
                          (let [raw ((:get tx) address)
                                _ (when raw (observe :membership-read (alength ^bytes raw)))
                                n (if (zero? level)
                                    (do (vswap! leaves inc)
                                        (if (and raw (.contains candidates (int (membership-number-value raw)))) 1 0))
                                    (if raw (RoaringBitmap/andCardinality candidates (membership-decode raw)) 0))]
                            (.put counts lookup n) n)))))
        vbound (fn [boundary] (ranked-virtual-bound virtual boundary))
        vitem (fn [i] (let [^RankedVirtualMember m (.get virtual (int i))]
                       ;; Third slot marks a virtual member (no ::leaf-payload).
                       (object-array [(.-member m) (.-payload m) true])))
        real-total (delay (if (.isEmpty candidates) 0
                            ((:reduce tx) {:prefix (ranked-prefix cfg top)}
                             (fn [n {:keys [k]}]
                               (vswap! visits inc)
                               (+ n (long (matches top (ranked-unpack cfg k))))) 0)))
        total #(+ (long @real-total) (.size virtual))
        forward
        (fn [skip limit]
          (let [remaining (volatile! skip) out (ArrayList.)]
            (letfn [(visit [level {:keys [k v]} end]
                      (let [member (ranked-unpack cfg k) real (matches level member)
                            begin-virtual (vbound member)
                            end-virtual (vbound end)
                            n (+ real (- end-virtual begin-virtual))]
                        (if (<= n @remaining)
                          (vswap! remaining - n)
                          (if (zero? level)
                            (do
                              ;; The real leaf starts this span; virtual members follow it.
                              (when (pos? real)
                                (if (pos? @remaining) (vswap! remaining dec) (.add out (object-array [member v]))))
                              (let [start (+ begin-virtual @remaining)
                                    stop (min end-virtual (+ start (- limit (.size out))))]
                                (doseq [i (range start stop)] (.add out (vitem i)))
                                (vreset! remaining 0)))
                            (walk (dec level) member end)))))
                    (walk [level begin end]
                      ;; Delay one entry to obtain its span's exclusive upper bound.
                      (let [last-entry
                            ((:reduce tx) (cond-> {:prefix (ranked-prefix cfg level)
                                                  :from (ranked-pack cfg level begin)}
                                           end (assoc :to (ranked-pack cfg level end)))
                             (fn [previous entry]
                               (vswap! visits inc)
                               (when previous (visit level previous (ranked-unpack cfg (:k entry))))
                               (if (= limit (.size out)) (reduced nil) entry)) nil)]
                        (when (and last-entry (< (.size out) limit)) (visit level last-entry end))))]
              (when (and (pos? limit) (< skip (total)))
                (if (zero? (long @real-total))
                  (doseq [i (range skip (min (total) (+ skip limit)))] (.add out (vitem i)))
                  (walk top ranked-empty-key nil))))
            out))
        reverse
        (fn [skip limit]
          (let [remaining (volatile! skip) out (ArrayList.)]
            (letfn [(visit [level ^bytes member v upper]
                      (let [real (long (matches level member))
                            bv (long (vbound member)) ev (long (vbound upper))
                            n (+ real (- ev bv))]
                        (if (<= n @remaining)
                          (vswap! remaining - n)
                          (if (zero? level)
                            ;; Highest first: the span's virtual members, then its leaf.
                            (let [r (long @remaining)]
                              (if (< r (- ev bv))
                                (do (loop [i (- ev 1 r)]
                                      (when (and (>= i bv) (< (.size out) limit)) (.add out (vitem i)) (recur (dec i))))
                                    (when (and (pos? real) (< (.size out) limit)) (.add out (object-array [member v]))))
                                (when (pos? real) (.add out (object-array [member v]))))
                              (vreset! remaining 0))
                            (walk (dec level) member upper)))))
                    (walk [level begin end]
                      ((:reduce tx) (cond-> {:prefix (ranked-prefix cfg level) :reverse? true
                                            :from (ranked-pack cfg level begin)}
                                     end (assoc :to (ranked-pack cfg level end)))
                       (fn [upper {:keys [k v]}]
                         (vswap! visits inc)
                         (let [member (ranked-unpack cfg k)]
                           (visit level member v upper)
                           (if (= limit (.size out)) (reduced nil) member))) end))]
              (when (and (pos? limit) (< skip (total)))
                (if (zero? (long @real-total))
                  (loop [i (- (total) 1 skip)]
                    (when (and (>= i 0) (< (.size out) limit)) (.add out (vitem i)) (recur (dec i))))
                  (walk top ranked-empty-key nil))))
            out))
        rank
        (fn [^bytes key]
          (+ (long (vbound key))
             (if (zero? (long @real-total)) 0
               (loop [level top begin ranked-empty-key acc 0]
                 (let [[sum ^bytes last-member]
                       ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level begin)
                                      :to (ranked-pack cfg level key)}
                        (fn [[sum ^bytes previous] {:keys [k]}]
                          (vswap! visits inc)
                          [(+ (long sum) (if previous (long (matches level previous)) 0)) (ranked-unpack cfg k)])
                        [0 nil])]
                   (cond
                     (nil? last-member) acc
                     (zero? level) (+ acc (long sum) (long (matches 0 last-member)))
                     :else (recur (dec level) last-member (+ acc (long sum)))))))))]
    {:total total :forward forward :reverse reverse :rank rank
     :run (fn [v n] (forward (rank v) n))
     :visits visits :leaves leaves}))

(defn ranked-filtered-page
  "Experimental span navigation. Candidate ordinals belong to this model.
   Returns selected opaque level-zero payloads, never full model results.
   With :desc and vlen (member -> length of its value encoding), the page is
   read in descending order from this ascending structure (ranked-desc-items)."
  ([tx cfg candidates requested-page limit observe]
   (ranked-filtered-page tx cfg candidates requested-page limit observe []))
  ([tx cfg candidates requested-page limit observe virtual]
   (ranked-filtered-page tx cfg candidates requested-page limit observe virtual :asc nil))
  ([tx cfg ^RoaringBitmap candidates requested-page limit observe ^java.util.List virtual direction vlen]
   (let [nav (ranked-filter-navigator tx cfg candidates observe virtual)
         total (long ((:total nav)))
         pages (max 1 (quot (+ total (dec limit)) limit))
         page (min requested-page pages)
         offset (* (dec page) limit)
         items (if (= direction :desc)
                 (ranked-desc-items nav vlen offset (min limit (- total offset)))
                 ((:forward nav) offset limit))]
     ;; OPT-9 W8: real leaves (2-element items) go through the config's
     ;; ::leaf-payload (owner id decoded from the member); virtual members
     ;; (3-element items, ranked-filter-navigator) keep their payload.
     {:payloads (let [f (::leaf-payload cfg)]
                  (mapv (fn [^objects item]
                          (if (and f (== 2 (alength item))) (f (aget item 0) (aget item 1)) (aget item 1)))
                        items))
      :total total :total-pages pages :page page :page-size limit
      :rank-entries-examined @(:visits nav) :membership-leaves-examined @(:leaves nav)})))

(defn ranked-get-nth [tx cfg ordinal]
  ;; Upstream NthLookup: at each level subtract spans until a span contains
  ;; the target, then descend from its key. No recursion per member.
  (when-not (neg? ordinal)
    (loop [level (dec (:levels cfg)) key ranked-empty-key remaining ordinal]
      (when (>= level 0)
        (let [cache (::span-counts tx)
              found
              ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level key)}
               (fn [{:keys [remaining]} {:keys [k v]}]
                 (let [key (ranked-unpack cfg k) n (ranked-scan-count cache cfg level k v)]
                   (cond
                     (and (zero? remaining) (pos? (alength ^bytes key))) (reduced {:done key})
                     (> n remaining) (reduced {:descend key :remaining remaining})
                     :else {:remaining (- remaining n)}))) {:remaining remaining})]
          (cond (:done found) (:done found)
                (:descend found) (recur (dec level) (:descend found) (:remaining found))
                :else nil))))))

(defn ranked-rank [tx cfg key null-if-missing?]
  ;; Upstream RankLookup, unique sets: sum an inclusive prefix on each level,
  ;; subtract the last span, and descend until the exact/insertion rank is known.
  (ranked-check-key! key)
  (when (or (not null-if-missing?) (ranked-contains? tx cfg key))
    (loop [level (dec (:levels cfg)) rank-key ranked-empty-key ordinal 0]
      (let [cache (::span-counts tx)
            found ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level rank-key)
                               :to (ranked-after (ranked-pack cfg level key))}
                   (fn [{:keys [ordinal]} {:keys [k v]}]
                     (let [n (ranked-scan-count cache cfg level k v)] {:rank-key (ranked-unpack cfg k) :last-count n :ordinal (+ ordinal n)}))
                   {:rank-key rank-key :last-count 0 :ordinal ordinal})
            next-rank (- (:ordinal found) (:last-count found))]
        (cond
          (Arrays/equals ^bytes (:rank-key found) ^bytes key) next-rank
          (zero? level) (+ next-rank (if (and (not null-if-missing?) (pos? (:last-count found))) 1 0))
          :else (recur (dec level) (:rank-key found) next-rank))))))

;; Online re-leveling (OPT-6). A RankedSet with L levels is a pure function of
;; its members, their promotion hashes and L: level l holds the sentinel plus
;; every level-(l-1) key whose hash has its low 4l bits zero, each with the
;; member count (and, for filtered indexes, the ordinal summary) of its span.
;; Adding a top level therefore needs only the current top level; dropping one
;; deletes it. Both run inside the caller's write transaction, so readers see
;; the old or the new level count with matching levels, never a mix.

(defn- ranked-level-entries
  "[member count] of every entry at level, sentinel first, collected before
   any write so no cursor is open while the caller writes."
  ^ArrayList [tx cfg level]
  (let [cache (::span-counts tx)]
    ((:reduce tx) {:prefix (ranked-prefix cfg level)}
     (fn [^ArrayList out {:keys [k v]}]
       (.add out [(ranked-unpack cfg k) (ranked-scan-count cache cfg level k v)]) out)
     (ArrayList.))))

(defn- ranked-build-level!
  "Write level (>= 1) from level-1: spans start at the sentinel and at every
   promoted level-(level-1) key; counts and span summaries are sums/unions of
   the level-(level-1) spans they cover. Same bytes as ranked-span-add! over
   the same members (every sentinel summary is written, even empty)."
  [tx cfg level]
  (let [below (dec level) membership? (:membership? cfg)
        mask (dec (bit-shift-left 1 (* 4 level)))
        entries (ranked-level-entries tx cfg below)
        flush! (fn [key n ^RoaringBitmap bits]
                 (ranked-set-count! tx cfg level key n)
                 (when membership?
                   ;; A union can yield a run container (a full 2^16 chunk)
                   ;; where element-wise adds keep a bitmap container; drop
                   ;; run compression so bytes match the bulk build's adds.
                   (.removeRunCompression bits)
                   (membership-put! tx (ranked-membership-key cfg level key) bits)))]
    (loop [i 0 key ranked-empty-key n 0 bits (when membership? (RoaringBitmap.))]
      (if (= i (.size entries))
        (flush! key n bits)
        (let [[^bytes member c] (.get entries i)
              promote? (and (pos? (alength member)) (zero? (bit-and (ranked-hash tx cfg member) mask)))
              part (when membership?
                     (if (zero? below)
                       (if (pos? (alength member))
                         (doto (RoaringBitmap.)
                           (.add (int (membership-number-value ((:get tx) (ranked-membership-key cfg 0 member))))))
                         (RoaringBitmap.))
                       (membership-bitmap tx (ranked-membership-key cfg below member))))]
          (if promote?
            (do (flush! key n bits)
                ;; Copy: part may be the level below's live pending bitmap.
                (recur (inc i) member (long c) (when membership? (doto (RoaringBitmap.) (.or ^RoaringBitmap part)))))
            (do (when membership? (.or ^RoaringBitmap bits ^RoaringBitmap part))
                (recur (inc i) key (+ n (long c)) bits))))))))

(defn- ranked-drop-level!
  "Delete every count and span summary stored at level (>= 1)."
  [tx cfg level]
  (doseq [[member _] (ranked-level-entries tx cfg level)]
    (ranked-delete-count! tx cfg level member)
    (when (:membership? cfg) (membership-delete! tx (ranked-membership-key cfg level member))))
  (when (:membership? cfg)
    ;; Summaries without a count row cannot exist; remove any defensively.
    (doseq [k ((:reduce tx) {:prefix (ranked-prefix cfg (+ 16 level))}
               (fn [^ArrayList out {:keys [k]}] (.add out k) out) (ArrayList.))]
      (membership-delete! tx k))))

(defn ranked-relevel!
  "Change an initialized index from (:levels cfg) to target levels in tx.
   Growth builds each new top level from the one below (hashing only members
   already promoted there, so towers stay nested under any hash history);
   shrinking drops the top levels. Returns cfg with :levels target."
  [tx cfg target]
  (let [current (long (:levels cfg)) target (long target)]
    (when-not (<= 2 target 6)
      (throw (IllegalArgumentException. "Ranked index requires 2 to 6 levels")))
    (cond
      (< current target) (loop [level current]
                           (when (< level target) (ranked-build-level! tx cfg level) (recur (inc level))))
      (> current target) (loop [level (dec current)]
                           (when (>= level target) (ranked-drop-level! tx cfg level) (recur (dec level)))))
    (assoc cfg :levels target)))

(defn- ranked-l0-scan
  "Up to n level-zero items [member payload] within packed bounds [from, to)
   (to nil = end of level zero), ascending or, with reverse?, descending."
  ^ArrayList [tx cfg ^bytes from ^bytes to n reverse?]
  (if-not (pos? (long n)) (ArrayList.)
    ((:reduce tx) (cond-> {:prefix (ranked-prefix cfg 0) :from from}
                    to (assoc :to to) reverse? (assoc :reverse? true))
     (fn [^ArrayList out {:keys [k v]}]
       (.add out (object-array [(ranked-unpack cfg k) v]))
       (if (= (long n) (.size out)) (reduced out) out))
     (ArrayList.))))

(defn ranked-range-navigator
  "Navigator (see ranked-desc-items) over the members within packed level-zero
   bounds [lo, hi) (hi nil = end of level zero) that hold ascending ranks
   [start, end). The bounds never split a run of equal values."
  [tx cfg ^bytes lo ^bytes hi start end]
  (let [start (long start) end (long end) size (- end start)
        at (fn [r] (ranked-pack cfg 0 (ranked-get-nth tx cfg r)))]
    {:total (constantly size)
     :forward (fn [p n] (if (and (pos? (long n)) (< (long p) size))
                          (ranked-l0-scan tx cfg (at (+ start (long p))) hi n false) (ArrayList.)))
     :reverse (fn [p n] (if (and (pos? (long n)) (< (long p) size))
                          (ranked-l0-scan tx cfg lo (if (zero? (long p)) hi (ranked-after (at (- end 1 (long p))))) n true)
                          (ArrayList.)))
     :rank (fn [key] (- (long (ranked-rank tx cfg key false)) start))
     ;; A run's first member is the first key at or above its value bytes.
     :run (fn [^bytes v n] (ranked-l0-scan tx cfg (ranked-pack cfg 0 v) hi n false))}))

(defn ranked-desc-reduce
  "Reduce the level-zero items [member payload] within packed bounds [lo, hi)
   in descending order (runs of equal value descending, each run ascending),
   holding at most chunk items: a run that does not fit is re-read forward
   from its first member. Reads at most about twice the items."
  [tx cfg vlen ^bytes lo ^bytes hi chunk rf init]
  (let [chunk (long chunk)
        value (partial ranked-run-value vlen)
        ;; kv :reduce unwraps one reduced; keep the caller's reduced visible.
        wrap (fn [acc item] (let [r (rf acc item)] (if (reduced? r) (reduced r) r)))]
    (loop [to hi acc init]
      (let [^ArrayList xs (ranked-l0-scan tx cfg lo to (inc chunk) true)
            cnt (.size xs) complete? (<= cnt chunk)
            [state x acc]
            (loop [i 0 acc acc]
              (if (>= i cnt) [:done nil acc]
                (let [^bytes v (value (.get xs i))
                      j (long (loop [j (inc i)]
                                (if (and (< j cnt) (Arrays/equals v ^bytes (value (.get xs j)))) (recur (inc j)) j)))]
                  (if (or (< j cnt) complete?)
                    (let [acc (loop [q (dec j) acc acc]
                                (if (< q i) acc
                                  (let [acc (rf acc (.get xs q))] (if (reduced? acc) acc (recur (dec q) acc)))))]
                      (if (reduced? acc) [:stop nil acc] (recur j acc)))
                    ;; The lowest run may continue below xs: stream all of it.
                    (let [g (ranked-pack cfg 0 v)
                          acc ((:reduce tx) {:prefix (ranked-prefix cfg 0) :from g
                                             :to (ranked-pack cfg 0 (key-prefix-end v))}
                               (fn [acc {:keys [k v]}] (wrap acc (object-array [(ranked-unpack cfg k) v]))) acc)]
                      (if (reduced? acc) [:stop nil acc] [:continue g acc]))))))]
        (case state
          :continue (recur x acc)
          :stop (unreduced acc)
          acc)))))

;; ---------------------------------------------------------------------------
;; Sort metadata
;; ---------------------------------------------------------------------------

(defn sort-data-int-at ^long [^bytes data ^long offset]
  (bit-or (bit-shift-left (bit-and 255 (aget data offset)) 24)
          (bit-shift-left (bit-and 255 (aget data (inc offset))) 16)
          (bit-shift-left (bit-and 255 (aget data (+ offset 2))) 8)
          (bit-and 255 (aget data (+ offset 3)))))

(defn sort-data-field [indexes index]
  (if (= :identity index) 0
    (+ 2 (.indexOf ^java.util.List (vec (sort (keys indexes))) index))))

(defn sort-data-encode ^bytes [id indexes]
  (let [fields (mapv key-encode-compact (concat [(str id) id] (map val (sort-by key indexes))))
        header (* 4 (inc (count fields)))
        size (+ header (reduce + (map #(alength ^bytes %) fields)))
        out (ByteBuffer/allocate size)]
    (loop [offset header fields (seq fields)]
      (.putInt out offset)
      (when-let [^bytes value (first fields)] (recur (+ offset (alength value)) (next fields))))
    (doseq [^bytes value fields] (.put out value))
    (.array out)))

(defn sort-data-value [^bytes data ^long field]
  (let [start (sort-data-int-at data (* 4 field)) end (sort-data-int-at data (* 4 (inc field)))]
    (key-decode-compact data start (- end start))))

(defn sort-data-values
  "Decode requested fields directly into a detached immutable map."
  [data indexes fields]
  (persistent!
   (reduce-kv (fn [out i index] (assoc! out index (sort-data-value data (nth fields i))))
              (transient {}) indexes)))

(defn sort-data-compare-field ^long [^bytes a ^bytes b ^long field]
  (Arrays/compareUnsigned a (sort-data-int-at a (* 4 field)) (sort-data-int-at a (* 4 (inc field)))
                          b (sort-data-int-at b (* 4 field)) (sort-data-int-at b (* 4 (inc field)))))

(defn sort-data-field= [^bytes a ^bytes b ^long field]
  (zero? (sort-data-compare-field a b field)))

(deftype SortDataEntry [id ^bytes data ^bytes computed])

(defn sort-data-comparator [field descending? computed?]
  (reify java.util.Comparator
    (compare [_ a b]
      (let [^SortDataEntry a a ^SortDataEntry b b
            c (if computed? (Arrays/compareUnsigned ^bytes (.-computed a) ^bytes (.-computed b))
                  (sort-data-compare-field (.-data a) (.-data b) field))]
        (if (zero? c)
          (let [tie (sort-data-compare-field (.-data a) (.-data b) 0)]
            (int (if (zero? tie) (sort-data-compare-field (.-data a) (.-data b) 1) tie)))
          (int (if descending? (- c) c)))))))

;; ---------------------------------------------------------------------------
;; Transactional text search
;; ---------------------------------------------------------------------------

(defn text-search-normalize [text]
  (when-not (or (nil? text) (string? text))
    (throw (ex-info "Search text extractor must return a string or nil" {})))
  (let [^String text (or text "")] (.toLowerCase text Locale/ROOT)))

(defn- text-search-number-bytes ^bytes [n] (.array (doto (ByteBuffer/allocate 4) (.putInt (int n)))))
(defn- text-search-slot [b kind suffix] (key-checked (key-join b (byte-array [kind]) suffix)))

(defn- text-search-config [b index] (text-search-slot b 6 (key-encode index)))
(defn- text-search-term-key [cfg kind term] (key-checked (key-join cfg (byte-array [kind]) (key-encode term))))
(defn- text-search-posting-key [prefix ordinal] (key-checked (key-join prefix (text-search-number-bytes ordinal))))

(defn- text-search-terms [^String text]
  (let [^ints points (.toArray (.codePoints text)) n (alength points)
        grams (reduce (fn [out i] (update out [:gram (String. points (int i) 3)] (fnil conj []) i))
                      {} (range (max 0 (- n 2))))
        terms (reduce (fn [out width]
                        (assoc out [:prefix (String. points 0 (int width))] [0]
                                   [:suffix (String. points (int (- n width)) (int width))] [(- n width)]))
                      grams (range 1 (inc (min 2 n))))]
    (into {} (for [[term positions] terms]
               (let [out (ByteBuffer/allocate (* 4 (inc (count positions))))]
                 (.putInt out n) (doseq [position positions] (.putInt out (int position)))
                 [term (.array out)])))))

(defn- text-search-change-count! [tx cfg term delta]
  (let [k (text-search-term-key cfg 1 term)]
    (if-let [^HashMap cache (::term-counts tx)]
      ;; OPT-10 W10: the batch's term-count cache holds [count stored-before?];
      ;; term-count-flush! writes each changed term count once.
      (let [address (ByteBuffer/wrap k)
            ^longs c (or (.get cache address)
                         (let [raw ((:get tx) k)
                               c (long-array [(if raw (sort-data-int-at raw 0) 0) (if raw 1 0)])]
                           (.put cache address c) c))
            next (+ (aget c 0) (long delta))]
        (when (neg? next) (throw (ex-info "Negative text posting count" {})))
        (aset c 0 next))
      (let [old (if-let [raw ((:get tx) k)] (sort-data-int-at raw 0) 0)
            next (+ old delta)]
        (when (neg? next) (throw (ex-info "Negative text posting count" {})))
        (if (zero? next) ((:delete tx) k) ((:put tx) k (text-search-number-bytes next)))))))

(defn- term-count-flush!
  "Write each term count changed in this transaction once (a delete when it
   reached zero and a row was stored before), as writing every change through
   would have left it. Term counts are read only by searches, never in a write
   transaction."
  [tx]
  (when-let [^HashMap cache (::term-counts tx)]
    (doseq [^java.util.Map$Entry e (count-cache-entries cache)
            :let [^longs c (.getValue e) k (.array ^ByteBuffer (.getKey e))]]
      (if (zero? (aget c 0))
        (when (== 1 (aget c 1)) ((:delete tx) k))
        ((:put tx) k (text-search-number-bytes (aget c 0)))))
    (.clear cache)))

(defn text-search-update!
  "Called only inside the fold's write transaction. before/after are included
   result entries or nil. Removed identities retain their ordinal for re-entry."
  [tx b searches id before after]
  (when (seq searches)
    (let [ordinal (membership-ensure-ordinal! tx b id)]
      (doseq [[index extract] searches
              :let [old (when before (text-search-normalize (extract (:value before))))
                    next (when after (text-search-normalize (extract (:value after))))]
              :when (not= old next)]
        (let [cfg (text-search-config b index) old (if old (text-search-terms old) {}) next (if next (text-search-terms next) {})]
          (doseq [[term _] old :when (not (contains? next term))]
            ((:delete tx) (text-search-posting-key (text-search-term-key cfg 0 term) ordinal))
            (text-search-change-count! tx cfg term -1))
          (doseq [[term ^bytes value] next]
            (when-not (Arrays/equals ^bytes (get old term) value)
              ((:put tx) (text-search-posting-key (text-search-term-key cfg 0 term) ordinal) value)
              (when-not (contains? old term) (text-search-change-count! tx cfg term 1)))))))))

(defn- text-search-has-position? [^bytes posting position]
  (loop [lo 1 hi (quot (alength posting) 4)]
    (if (>= lo hi) false
      (let [mid (quot (+ lo hi) 2) found (sort-data-int-at posting (* 4 mid))]
        (cond (= found position) true (< found position) (recur (inc mid) hi)
              :else (recur lo mid))))))

(defn- text-search-aligned? [^bytes pivot pivot-offset ^objects postings ^ints offsets query-length mode]
  (let [length (sort-data-int-at pivot 0)]
    (loop [at 4]
      (when (< at (alength pivot))
        (let [start (- (sort-data-int-at pivot at) pivot-offset)]
          (if (and (<= 0 start (- length query-length))
                   (case mode :prefix (zero? start) :suffix (= start (- length query-length)) true)
                   (loop [i 0]
                     (or (= i (alength postings))
                         (and (text-search-has-position? (aget postings i) (+ start (aget offsets i)))
                              (recur (inc i))))))
            true (recur (+ at 4))))))))

(defn- text-search-copy-ordinal! [^bytes source ^bytes target]
  (System/arraycopy source (- (alength source) 4) target (- (alength target) 4) 4))

(defn- text-search-read-postings! [tx ^bytes k v ^objects lookups ^objects postings]
  (loop [i 0]
    (if (= i (alength lookups)) true
      (let [lookup (aget lookups i)
            raw (if lookup
                  (do (text-search-copy-ordinal! k lookup) ((:get tx) lookup))
                  v)]
        (when raw
          (aset postings i raw)
          (recur (inc i)))))))

(defn text-search-search-keys [tx b searches {:keys [index query mode] :or {mode :contains} :as options} observe]
  (when-not (and (every? #{:index :query :mode} (keys options))
                 (contains? searches index) (string? query) (#{:contains :prefix :suffix} mode))
    (throw (ex-info "Invalid text search; supply a declared :index, string :query and optional :mode" {:options options})))
  (let [query (text-search-normalize query) ^ints points (.toArray (.codePoints ^String query)) n (alength points)]
    (when (< n (if (= mode :contains) 3 1))
      (throw (ex-info "Contains search needs three code points; boundary search needs a nonempty fragment"
                      {:type :grain.read-model/short-search :mode mode})))
    (let [cfg (text-search-config b index)
          query-terms (if (< n 3) [[[mode query] 0]]
                        (mapv (fn [offset] [[:gram (String. points (int offset) 3)] offset])
                              (distinct (concat (range 0 (- n 2) 3) [(- n 3)]))))
          candidates (mapv (fn [[term offset]]
                             {:term term :offset offset :prefix (text-search-term-key cfg 0 term)
                              :count (if-let [raw ((:get tx) (text-search-term-key cfg 1 term))] (sort-data-int-at raw 0) 0)}) query-terms)
          pivot (apply min-key :count candidates)
          ;; Private scratch space for this search's synchronous point reads.
          ;; Only decoded, detached identities leave this operation.
          lookups (object-array (map #(when (not= (:term %) (:term pivot))
                                        (text-search-posting-key (:prefix %) 0)) candidates))
          offsets (int-array (map :offset candidates))
          postings (object-array (count candidates))
          identity-key (text-search-slot b 8 (text-search-number-bytes 0))]
      (if (zero? (:count pivot)) []
        (persistent!
          ((:reduce tx) {:prefix (:prefix pivot)}
           (fn [out {:keys [^bytes k ^bytes v]}]
             (observe :search-candidate (alength v))
             (if (and (text-search-read-postings! tx k v lookups postings)
                      (text-search-aligned? v (:offset pivot) postings offsets n mode))
               (do (observe :search-match 0)
                   (text-search-copy-ordinal! k identity-key)
                   (conj! out (codec/decode ((:get tx) identity-key)))) out))
           (transient [])))))))

;; ---------------------------------------------------------------------------
;; Keyed model materialization and reads
;; ---------------------------------------------------------------------------

(defn keyed? [definition]
  (or (contains? definition :key) (contains? definition :route)))

(defn keyed-validate! [name opts]
  (when-not (map? opts)
    (throw (ex-info "Read model options must be a map" {:model name})))
  (let [keyed-options #{:events :version :key :route :initial :result :include? :indexes
                        :searches :filtered-indexes :state-schema :result-schema}
        ordinary-options #{:events :version :schema :partition-fn :entity-id-fn :l1-ttl-ms}
        keyed? (boolean (keyed? opts))
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
                        {:type :grain.read-model/invalid :model name :options invalid :keyed? keyed?
                         :unknown-options unknown :inapplicable-options inapplicable
                         :supported-options supported :hint hint})))))
  (when (keyed? opts)
    (when-not (and (qualified-keyword? name) (pos-int? (:version opts 1))
                   (set? (:events opts)) (seq (:events opts))
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
                   (every? (fn [[k f]] (and (keyword? k) (not= :identity k) (ifn? f)))
                           (:indexes opts)))
      (throw (ex-info "Invalid keyed-fold declaration" {:model name})))
    (doseq [schema (keep opts [:state-schema :result-schema])] (m/schema schema))))

(defn- keyed-observe! [context operation size]
  (when-let [f (:read-model/observer context)] (f {:operation operation :bytes size})))

(defn- keyed-decode [ctx operation bytes]
  (when bytes
    (keyed-observe! ctx operation (alength ^bytes bytes))
    (codec/decode bytes)))

(defn- keyed-validate-scope! [scope]
  (let [query? (fn [q]
                 (and (map? q) (every? #{:tags :types} (keys q))
                      (or (not (contains? q :types))
                          (and (set? (:types q)) (every? keyword? (:types q))))
                      (or (not (contains? q :tags))
                          (and (set? (:tags q))
                               (every? #(and (vector? %) (= 2 (count %))
                                             (keyword? (first %)) (uuid? (second %))) (:tags q))))))]
    (when-not (or (nil? scope)
                  (and (map? scope)
                       (or (and (= #{:tags} (set (keys scope))) (query? scope))
                           (and (= #{:queries} (set (keys scope)))
                                (or (query? (:queries scope))
                                    (and (vector? (:queries scope)) (every? query? (:queries scope))))))))
      (throw (ex-info "Keyed event scope requires {:tags #{[tag UUID] ...}} or {:queries [{:tags ... :types ...} ...]}"
                      {:type :grain.read-model/invalid-scope :scope scope
                       :hint "Scope selects events before folding. Tenant, progress, ordering and limits belong to the read operation, not its event scope."}))))
  scope)

(defn keyed-scoped-context [ctx scope]
  (keyed-validate-scope! scope)
  (if scope (assoc ctx :read-model/scope scope) (dissoc ctx :read-model/scope)))

(defn- keyed-scope-identity [scope]
  (when scope
    [:event-scope (.formatHex (HexFormat/of)
                    (codec/encode (canonical-cache-value scope)))]))

(defn- keyed-namespace-identity
  ([ctx name d]
   (keyed-namespace-identity ctx name d (keyed-scope-identity (:read-model/scope ctx))))
  ([ctx name d scope-identity]
   (cond-> [(:tenant-id ctx) name (:version d 1)]
     scope-identity (conj scope-identity))))

(defn- keyed-event-query [ctx d after]
  (let [scope (:read-model/scope ctx)
        queries (cond
                  (nil? scope) [{}]
                  (contains? scope :tags) [scope]
                  (map? (:queries scope)) [(:queries scope)]
                  :else (:queries scope))
        queries (into []
                  (keep (fn [q]
                          (let [types (if (contains? q :types)
                                        (into #{} (filter (:events d)) (:types q)) (:events d))]
                            ;; Empty selectors must never turn into an unfiltered store read.
                            (when (and (seq types) (or (not (contains? q :tags)) (seq (:tags q))))
                              (cond-> (assoc q :tenant-id (:tenant-id ctx) :types types)
                                after (assoc :after after)))))) queries)]
    (if (= 1 (count queries)) (first queries) queries)))

(defn- keyed-address [b kind id] (key-checked (key-join b (byte-array [kind]) (key-encode id))))
(defn- keyed-progress-key [b] (key-join b (byte-array [0])))
(defn- keyed-count-key [b] (key-join b (byte-array [4])))
(defn- keyed-included-key [b] (key-join b (byte-array [12])))
;; OPT-8: an index is addressed by its ordinal in the model, not by its
;; encoded name: :identity is 0 and the declared indexes are 1, 2, ... in
;; sorted name order (the order of their sort-data fields, keyed-index-fields:
;; ordinal = field - 1). Ordinals below 255 take one byte; larger ones FF hi lo
;; (prefix-free). Ordinals are positional, not stored: renaming or adding an
;; index without a :version bump re-addresses indexes, as it already re-maps
;; sort-data fields (see the OPT-8 report; an index-name fingerprint check
;; belongs to OPT-10).
(defn- keyed-index-ordinal ^long [d index]
  (if (= :identity index)
    0
    (let [indexes (:indexes d)]
      (when-not (contains? indexes index)
        (throw (ex-info "Unknown keyed index" {:index index})))
      (inc (long (reduce-kv (fn [n k _] (if (neg? (compare k index)) (inc (long n)) n)) 0 indexes))))))

(defn- keyed-ordinal-code ^bytes [^long o]
  (if (< o 255)
    (byte-array [(unchecked-byte o)])
    (let [x (- o 255)]
      (when (> x 0xFFFF) (throw (ex-info "Too many keyed indexes" {:ordinal o})))
      (byte-array [-1 (unchecked-byte (bit-shift-right x 8)) (unchecked-byte x)]))))

;; {index -> ordinal code} per :indexes map (read paths build configs per
;; call); codes are shared and never mutated (key-join copies them). Bounded:
;; definitions are few, the cache is dropped if it ever grows past 4096.
(def ^:private ^java.util.concurrent.ConcurrentHashMap keyed-ordinal-codes
  (java.util.concurrent.ConcurrentHashMap.))

(defn- keyed-index-ordinal-bytes ^bytes [d index]
  (let [indexes (:indexes d {})
        ^java.util.Map codes
        (or (.get keyed-ordinal-codes indexes)
            (let [m (HashMap.)]
              (.put m :identity (keyed-ordinal-code 0))
              (doseq [k (keys indexes)] (.put m k (keyed-ordinal-code (keyed-index-ordinal d k))))
              (when (> (.size keyed-ordinal-codes) 4096) (.clear keyed-ordinal-codes))
              (.put keyed-ordinal-codes indexes m)
              m))]
    (or (.get codes index) (throw (ex-info "Unknown keyed index" {:index index})))))

(defn- keyed-index-config [b d index direction]
  ;; Promotion hashes (index ordinal, direction, member) under the cache key,
  ;; not the namespace ordinal.
  (assoc (ranked-config (key-join b (byte-array [5]) (keyed-index-ordinal-bytes d index)
                                  (byte-array [(if (= direction :desc) 1 0)])))
         :hash-offset (alength ^bytes b) :keyed? true))
;; OPT-5/OPT-7: only the ascending structure of an index is stored
;; (direction byte 0); descending orders are read from it (ranked-desc-items,
;; ranked-desc-reduce). The direction parameters below are always :asc.
(defn- keyed-index-prefix [b d index direction]
  (ranked-prefix (keyed-index-config b d index direction) 0))
(defn- keyed-membership-config [b d index direction]
  (assoc (keyed-index-config b d index direction) :membership? (contains? (:filtered-indexes d) index)))

;; Per-index rank levels (OPT-6). Every included result has exactly one
;; member in every index and direction, so each index of a namespace holds N =
;; the namespace's included count (kind 4) members. The level count is chosen
;; from N, the smallest L in [2, 6] with N <= 16^L (expected top-level spans
;; <= 16), and stored once per index (both directions, and independent of
;; whether a descending copy exists) at kind 13:
;;   b ++ [13] ++ ordinal(index)  ->  [L]  (one byte, 2..6; OPT-8: the index
;;   ordinal, keyed-index-ordinal-bytes, in place of key-encode(index))
;; Readers and writers use the stored L; correctness never depends on L
;; matching N (any L in range is a valid RankedSet), only cost does. Writers
;; re-level every index in the transaction that changes N (keyed-relevel!),
;; and the deferred build writes L = target(n) directly, so both produce the
;; same structure for the same members. An index without a kind-13 row holds
;; no members (it is created with its first member at keyed-min-levels).
;; Six is the ceiling because level byte 6 holds group counts; beyond 16^6
;; members the top level grows linearly (reads stay exact).
(def ^:private keyed-min-levels 2)
(def ^:private keyed-max-levels 6)

(defn- keyed-target-levels
  "Rank levels for an index of n members."
  [n]
  (loop [levels keyed-min-levels capacity 256]
    (if (or (>= levels keyed-max-levels) (<= n capacity)) levels
      (recur (inc levels) (* 16 capacity)))))

(defn- keyed-levels-key [b d index] (key-checked (key-join b (byte-array [13]) (keyed-index-ordinal-bytes d index))))

(defn- keyed-stored-levels
  "Stored level count of index in namespace b, or nil when it has none."
  [tx b d index]
  (when-let [^bytes raw ((:get tx) (keyed-levels-key b d index))]
    (let [levels (when (= 1 (alength raw)) (long (aget raw 0)))]
      (when-not (and levels (<= keyed-min-levels levels keyed-max-levels))
        (throw (ex-info "Invalid stored rank levels" {:type :grain.read-model/invalid-levels :index index})))
      levels)))

(defn- keyed-write-levels! [tx b d index levels]
  ((:put tx) (keyed-levels-key b d index) (byte-array [(byte levels)])))

(defn- keyed-read-config
  "Ranked config of a stored index for reading in tx: the stored levels."
  [tx b d cfg index]
  (assoc cfg :levels (or (keyed-stored-levels tx b d index) keyed-min-levels)))

;; Write-session caches. One keyed-apply-batch! transaction maintains one
;; namespace b with one definition d, so each [index direction] config (and its
;; encoded index name and level prefixes) is built once per transaction.
;; Level-0 sentinels are never deleted, so one successful ensure-init per
;; transaction and config suffices. (keyed-relevel!, OPT-6, may delete
;; upper-level sentinels when an index shrinks; it runs after all index work of
;; the batch and drops that index's session configs.)
(defn- keyed-write-session [tx b]
  (assoc tx ::keyed-session-base b ::keyed-session-configs (HashMap.)))

(defn- keyed-session-config [tx b d index direction]
  ;; ::levels is shared by both directions of an index: a volatile holding the
  ;; stored level count (nil while the index has no members), read once.
  (let [^HashMap configs (::keyed-session-configs tx)]
    (if (and configs (identical? b (::keyed-session-base tx)))
      (let [^objects pair (or (.get configs index)
                              (let [pair (object-array 3)] (.put configs index pair) pair))
            slot (if (= direction :desc) 1 0)
            levels (or (aget pair 2) (aset pair 2 (volatile! (keyed-stored-levels tx b d index))))]
        (or (aget pair slot)
            (let [cfg (assoc (keyed-membership-config b d index direction)
                             :levels (or @levels keyed-min-levels)
                             ::levels levels ::levels-key (keyed-levels-key b d index)
                             ::level-prefixes (object-array 24)
                             ::initialized (volatile! false))]
              (aset pair slot cfg)
              cfg)))
      (let [stored (keyed-stored-levels tx b d index)]
        (assoc (keyed-membership-config b d index direction)
               :levels (or stored keyed-min-levels)
               ::levels (volatile! stored) ::levels-key (keyed-levels-key b d index))))))

(defn- keyed-ensure-init! [tx cfg]
  ;; A new index starts at (:levels cfg) (keyed-min-levels) and records it in
  ;; the same transaction as its sentinels.
  (let [init! (fn []
                (when (nil? (ranked-count-key tx cfg 0 ranked-empty-key))
                  (ranked-init! tx cfg)
                  (when-let [k (::levels-key cfg)]
                    ((:put tx) k (byte-array [(byte (:levels cfg))]))
                    (vreset! (::levels cfg) (:levels cfg)))))]
    (if-let [initialized (::initialized cfg)]
      (when-not @initialized
        (init!)
        (vreset! initialized true))
      (init!))))

(defn- keyed-relevel!
  "After a write that leaves n included results in namespace b, move every
   initialized index to (keyed-target-levels n) levels and record it. Only the
   ascending structure is stored (OPT-5/OPT-7), so only it is re-leveled. Runs
   inside the batch transaction, before membership summaries are flushed;
   session configs of re-leveled indexes are dropped."
  [tx b d n]
  (let [target (keyed-target-levels n)]
    (doseq [index (conj (vec (keys (:indexes d))) :identity)]
      (let [asc (keyed-session-config tx b d index :asc)
            stored @(::levels asc)]
        (when (and stored (not= stored target))
          (ranked-relevel! tx asc target)
          (keyed-write-levels! tx b d index target)
          (some-> ^HashMap (::keyed-session-configs tx) (.remove index)))))))

;; OPT-9 W8: a level-zero member's value is empty when its id decodes
;; from the member key to exactly the value a Fressian payload would have
;; returned (same value and type): UUID, String, Boolean, Long/Integer/Short
;; (Fressian also returns Long), interned-equal keywords, and vectors of these.
;; Any other id (BigInt/BigInteger, keywords whose printed form re-reads
;; differently, ...) keeps the Fressian payload, so readers return exactly
;; what they returned before.
(def ^:private empty-payload (byte-array 0))
(defn- keyed-id-exact? [id]
  (cond
    (or (uuid? id) (string? id) (boolean? id) (nil? id)) true
    (or (instance? Long id) (instance? Integer id) (instance? Short id)) true
    (keyword? id) (identical? id (keyword (subs (str id) 1)))
    (vector? id) (every? keyed-id-exact? id)
    :else false))
(defn- keyed-id-payload ^bytes [id]
  (if (keyed-id-exact? id) empty-payload (codec/encode id)))

(definterface IKeyedOwner
  (id [])
  (^bytes idKey [])
  (^bytes memberSuffix [])
  (^bytes payload []))

;; Per-owner encodings shared by every index and direction of one update.
;; Computed on first use; the returned arrays are never mutated.
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
    ;; OPT-8: key-encode((str id)) ++ type tail (key-id-suffix).
    (when (nil? suffix) (set! suffix (key-id-suffix owner-id)))
    suffix)
  (payload [_]
    (when (nil? encoded) (set! encoded (keyed-id-payload owner-id)))
    encoded))

(defn- keyed-owner ^KeyedOwner [id] (KeyedOwner. id nil nil nil))
(defn- keyed-sort-value?
  "True when value has exactly one ordered encoding among values it equals:
   the types key-write-key! accepts, with vectors (never other sequentials)."
  [value]
  (or (nil? value) (boolean? value) (string? value) (uuid? value) (keyword? value)
      (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
      (and (vector? value) (every? keyed-sort-value? value))))
(defn- keyed-owner-address [b kind ^KeyedOwner owner]
  (key-checked (key-join b (byte-array [kind]) (.idKey owner))))
(defn- keyed-sort-bytes [value direction]
  ;; OPT-5/OPT-7: ascending bytes only (direction is always :asc).
  (assert (= :asc direction))
  (key-encode value))
;; Member layouts (OPT-8; one value encoding, so the former compact/physical
;; distinction is only a layout one now):
;;   :identity (unfiltered)  key-encode(value) ++ key-id-tail(id), value = (str id),
;;                           i.e. exactly the owner's id suffix
;;   any other index         key-encode(value) ++ key-encode((str id)) ++ key-id-tail(id)
;; (:identity in :filtered-indexes uses the second form, as before.)
(defn- keyed-compact-identity? [d index]
  (and (= :identity index) (not (contains? (:filtered-indexes d) :identity))))
(defn- keyed-index-sort-bytes [d index value direction]
  (keyed-sort-bytes value direction))
(defn- keyed-owner-member [d index direction value ^KeyedOwner owner]
  (if (keyed-compact-identity? d index)
    (key-join (keyed-sort-bytes value direction) (key-id-tail (.id owner)))
    (key-join (keyed-sort-bytes value direction) (.memberSuffix owner))))
(defn- keyed-index-member [d index direction value id]
  (keyed-owner-member d index direction value (keyed-owner id)))

(defn- keyed-member-id
  "OPT-9 W8 / OPT-8: the owner id at the end of a level-zero member that
   starts at offset in k. Unfiltered :identity members are the id suffix
   itself (its (str id) is the sort value, inverted when descending); other
   members skip the sort value (inverted when descending) first. The suffix
   is read by member-read-id!: (str id), then the type tail.
   Since OPT-5/OPT-7 only ascending members are stored, so every caller
   passes :asc; the descending mask path is kept for completeness only."
  [d index direction ^bytes k offset]
  (let [mask (if (= direction :desc) 255 0)]
    (try
      (let [identity? (keyed-compact-identity? d index)
            start (if identity? (long offset) (skip-member-key k offset mask))
            pos (doto (long-array 2) (aset 0 start) (aset 1 (alength k)))
            id (member-read-id! k pos (if identity? mask 0))]
        (when-not (= (aget pos 0) (alength k))
          (throw (ex-info "Trailing bytes in index member" {:type :grain.read-model/invalid-cache})))
        id)
      (catch EOFException _
        (throw (ex-info "Truncated index member" {:type :grain.read-model/invalid-cache})))
      (catch IndexOutOfBoundsException _
        (throw (ex-info "Truncated index member" {:type :grain.read-model/invalid-cache}))))))

(defn- keyed-entry-id
  "Owner id of a level-zero entry: its Fressian payload when one was kept,
   else decoded from the member key."
  [d index direction ^bytes k offset ^bytes v]
  (if (pos? (alength v)) (codec/decode v) (keyed-member-id d index direction k offset)))
(defn- keyed-fits-prefix?
  "A selection prefix must leave space for a member suffix within LMDB's key limit."
  ([^bytes prefix] (< (alength prefix) 511))
  ([cfg ^bytes prefix]
   (< (+ (alength ^bytes (ranked-prefix cfg 0)) (alength prefix)) 511)))

;; Descending reads (OPT-5/OPT-7): read from the ascending structure with runs of
;; equal value re-reversed (ranked-desc-items / ranked-desc-reduce), so ties
;; stay ascending by id, exactly as in the KF9 stored descending copy.
(defn- keyed-value-length
  "Member -> length of its leading value encoding, for index of d."
  [_d _index]
  (fn [^bytes member] (key-skip member 0)))

(defn- keyed-update-group-at! [tx ^bytes group-prefix index value delta]
  ;; Levels 0..5 are the RankedSet. Level 6 stores one count per sort value,
  ;; shared by both directions; no duplicated root membership collection.
  ;; A decrement keeps the stored :value (OPT-9 audit C1): since OPT-9 W7 the
  ;; removed value is decoded from compact sort data, which can change its
  ;; form (BigInt -> Long, (keyword "a/b" "c") -> :a/b/c); an increment
  ;; writes the extractor's own value, as before.
  (let [k (key-checked (key-join group-prefix (key-encode value)))]
    (if-let [^HashMap cache (::group-counts tx)]
      ;; OPT-10 W9: the batch's group-count cache holds the current record (nil
      ;; when the group is gone) and whether a row was stored before this
      ;; transaction; group-count-flush! writes the final record once.
      (let [address (ByteBuffer/wrap k)
            ^objects slot (or (.get cache address)
                              (let [raw ((:get tx) k)
                                    slot (object-array [(some-> raw codec/decode) (some? raw)])]
                                (.put cache address slot) slot))
            record (aget slot 0)
            total (+ (long (or (:total record) 0)) (long delta))]
        (when (neg? total) (throw (ex-info "Negative index group count" {:index index :value value})))
        (aset slot 0 (when-not (zero? total)
                       {:value (if (and record (neg? (long delta))) (:value record) value) :total total})))
      (let [record (some-> ((:get tx) k) codec/decode)
            before (:total record)
            total (+ (or before 0) delta)]
        (when (neg? total) (throw (ex-info "Negative index group count" {:index index :value value})))
        (if (zero? total) ((:delete tx) k)
          ((:put tx) k (codec/encode {:value (if (and record (neg? (long delta))) (:value record) value)
                                      :total total})))))))

(defn- group-count-flush!
  "Write each group changed in this transaction once: its final record, or a
   delete when it reached zero and a row was stored before the transaction.
   Stored bytes equal writing every change through: the final record is the
   last change's record (a decrement keeps the record's :value, an increment
   writes the extractor value, as in the uncached path; index values are
   key-encodable scalars and vectors, whose Fressian round trip re-encodes to
   the same bytes, so keeping the in-memory value equals keeping the decoded
   one)."
  [tx]
  (when-let [^HashMap cache (::group-counts tx)]
    (doseq [^java.util.Map$Entry e (count-cache-entries cache)
            :let [^objects slot (.getValue e) k (.array ^ByteBuffer (.getKey e))]]
      (if-let [record (aget slot 0)]
        ((:put tx) k (codec/encode record))
        (when (aget slot 1) ((:delete tx) k))))
    (.clear cache)))

(def ^:private count-cache-flush-entries
  ;; Entry bound of a batch transaction's count caches (OPT-10 audit C1). An
  ;; entry costs about 200 B of heap (measured: 8.01 M text-term entries held
  ;; 1,609 MB live), so 65,536 entries are about 13 MB. Steady-state batches stay
  ;; far below it (w5x touch-3: a few hundred entries per 128-event batch); a
  ;; batch of long high-entropy texts (each 1,000-character random CJK text adds
  ;; about 1,000 term entries) flushes every ~65 owners instead of holding
  ;; gigabytes. Not a tuning option: tests redefine it to force flushes.
  65536)

(defn- count-caches-bound!
  "Flush the batch transaction's span-count, group-count and term-count caches
   when together they hold more than count-cache-flush-entries entries. Called
   between owners in keyed-apply-batch!; stored bytes are unchanged (see the
   span-count cache comment). Because the check runs only between owners, one
   owner's changes are never split across a flush, and the peak cache size is
   the larger of the bound and a single owner's entries (e.g. one 100,000-
   character random text adds ~100,000 term entries, ~19 MB)."
  [tx]
  (when-let [^HashMap spans (::span-counts tx)]
    (when (> (+ (.size spans) (.size ^HashMap (::group-counts tx)) (.size ^HashMap (::term-counts tx)))
             (long count-cache-flush-entries))
      (span-count-flush! tx)
      (group-count-flush! tx)
      (term-count-flush! tx))))

(defn- keyed-update-group!
  ;; OPT-8: the former 5-arity (no definition) is gone: index addresses are
  ;; ordinals of the definition. The rmp-stress harness self-test calls this
  ;; 6-arity (outside a write session it reads the stored levels directly).
  [tx b d index value delta]
  (keyed-update-group-at! tx (ranked-prefix (keyed-session-config tx b d index :asc) 6) index value delta))

;; OPT-9 W6: group counts (level 6) are kept, on the ascending
;; config, for every declared index: keyed-index-groups is public on any index
;; and the query engine enumerates equality-index groups (:related-groups,
;; search joins), so none of them is provably never enumerated. The :identity
;; index has no stored groups: its group for a value is the set of members
;; whose (str id) equals it, counted from level zero where needed
;; (keyed-identity-groups, keyed-index-rank).
(defn- keyed-grouped? [index direction]
  (and (= direction :asc) (not= :identity index)))

(defn- keyed-remove-index! [tx b d index direction position ^KeyedOwner owner]
  (when (ranked-remove! tx (keyed-session-config tx b d index direction) (keyed-owner-member d index direction position owner))
    (when (keyed-grouped? index direction) (keyed-update-group! tx b d index position -1))))

(defn- keyed-add-index! [ctx tx b d index direction position ^KeyedOwner owner]
  (let [cfg (keyed-session-config tx b d index direction)
        member (keyed-owner-member d index direction position owner)
        payload (.payload owner)]
    (keyed-ensure-init! tx cfg)
    (when (ranked-add! tx cfg member payload
                       (when (:membership? cfg) (membership-ensure-ordinal! tx b (.id owner))))
      (when (keyed-grouped? index direction) (keyed-update-group! tx b d index position 1)))
    (keyed-observe! ctx :index-written (alength ^bytes payload))))

(defn- keyed-empty-ranks? [tx cfg]
  ;; Eligibility is not proof: inspect every persisted rank and span summary.
  (let [zero-count (ranked-encode-count 0)
        empty-level? (fn [level membership?]
                       ((:reduce tx) {:prefix (ranked-prefix cfg level)}
                        (fn [_ {:keys [k v]}]
                          (if (and (= (alength ^bytes k) (alength ^bytes (ranked-prefix cfg level)))
                                   (if membership?
                                     (try (.isEmpty (membership-decode v))
                                          (catch Exception _ false))
                                     (Arrays/equals ^bytes zero-count ^bytes v)))
                            true (reduced false))) true))]
    (and (every? #(empty-level? % false) (range (:levels cfg)))
         (or (not (:membership? cfg))
             (every? #(empty-level? (+ 16 %) true) (range (:levels cfg)))))))

(defn- keyed-cold-index-builders [ctx tx b d before-progress states]
  (when (and (nil? (:watermark before-progress))
             (nil? (:read-model/observer ctx)) (<= 32 (count states))
             ;; Raw or uncertain persisted results cannot join a deferred build.
             ((:reduce tx) {:prefix (key-join b (byte-array [2]))}
              (fn [_ _] (reduced false)) true))
    (let [builders (HashMap.)]
      ;; Check all directions before group counts or queued additions change.
      (doseq [index (conj (vec (keys (:indexes d))) :identity)
              :let [groups-empty? ((:reduce tx)
                                    {:prefix (ranked-prefix (keyed-session-config tx b d index :asc) 6)}
                                    (fn [_ _] (reduced false)) true)]
              direction [:asc]
              :let [cfg (keyed-session-config tx b d index direction)]
              :when (and groups-empty? (keyed-empty-ranks? tx cfg))]
        (.put builders [index direction] {:cfg cfg :entries (ArrayList.) :initialized? (volatile! false)}))
      builders)))

(defn- keyed-enqueue-index! [ctx tx b d builders index direction position ^KeyedOwner owner]
  (if-let [{:keys [cfg entries initialized?]} (when builders (.get ^HashMap builders [index direction]))]
    (let [member (keyed-owner-member d index direction position owner)
          payload (.payload owner)]
      (when-not @initialized? (keyed-ensure-init! tx cfg) (vreset! initialized? true))
      ;; Reserve ordinals and consume random hashes in original insertion order.
      (let [ordinal (when (:membership? cfg) (membership-ensure-ordinal! tx b (.id owner)))
            _ (ranked-check-key! member)
            _ (when (and (:membership? cfg)
                         (not (and (integer? ordinal) (<= 0 ordinal Integer/MAX_VALUE))))
                (throw (ex-info "Membership index requires a model-local ordinal" {})))
            hash (ranked-hash tx cfg member)]
        ;; Fail oversized physical keys before validating a later owner's result.
        (ranked-pack cfg 0 member)
        ;; Final states have one owner per key; each member includes its typed
        ;; key, making these initially empty index additions distinct.
        (.add ^ArrayList entries [member payload ordinal hash])
        (when (keyed-grouped? index direction) (keyed-update-group! tx b d index position 1))))
    (keyed-add-index! ctx tx b d index direction position owner)))

(defn- keyed-build-cold-indexes! [tx builders]
  (when builders
    (doseq [{:keys [cfg entries]} (.values ^HashMap builders)]
      (.sort ^ArrayList entries
             (reify Comparator
               (compare [_ a b] (Arrays/compareUnsigned ^bytes (nth a 0) ^bytes (nth b 0)))))
      (let [previous (object-array (repeat (:levels cfg) ranked-empty-key))]
        (doseq [[member payload ordinal hash] entries]
          (ranked-append! tx cfg member payload ordinal hash previous))))))

;; OPT-9 W7: old index positions come from the owner's sort metadata
;; (kind 11), never from the result row and never by re-running extractors.
(defn- keyed-index-fields
  "[[index sort-data-field] ...] for the non-identity indexes, in field order."
  [d]
  (into [] (map-indexed (fn [i index] [index (+ 2 i)])) (sort (keys (:indexes d)))))

;; Index-layout fingerprint (OPT-10, W2 audit conditions C1/C2; FINAL: OPT-10
;; audit C2 = OPT-8 audit C3). Since OPT-9 (W7) old index positions are read
;; from kind 11 by sort-data field ordinal, and since OPT-8 index addresses
;; (kind 5 configs, kind 13 levels) are index ordinals: the ordinal of an index
;; is its rank among the model's SORTED non-identity index names. A definition
;; change that keeps the index count but changes a name (e.g. {:a :x :b :y} ->
;; {:c :x :b :y}: :b moves from field 3 to field 2) passes the field-count
;; check in keyed-sort-data! and would make an UNRENAMED index read another
;; index's data. The stored layout also depends on two more definition fields:
;; - :filtered-indexes: membership configs and span bitmaps exist only for the
;;   filtered indexes, model ordinals only when some index is filtered (or a
;;   search exists), and the :identity member layout itself depends on whether
;;   :identity is filtered (keyed-compact-identity?). A change gave silent
;;   wrong pages, untyped NPE wedges or member decoder errors.
;; - :searches names: postings and term counts live under key-encode(search
;;   name), and only owners folded since the search existed have postings, so
;;   an added or renamed search silently missed old owners.
;; No other definition field changes stored layout: :events, :key/:route,
;; :initial, :result, :include? and the schemas only change content (like an
;; extractor change under the same index name, they cannot be detected and
;; need a :version bump by contract); :version is part of the namespace
;; identity. So the progress record (kind 0), which every batch and every
;; catch-up already reads and decodes, stores under :index-layout the compact
;; ordered key encoding of [sorted index names, sorted :filtered-indexes,
;; sorted search names] (exact and injective: one byte array, decodable for
;; the error), and any mismatch with the current definition throws
;; :grain.read-model/invalid-cache before anything is read or written.
;; Checked: at the start of every catch-up (so reads and writes of the model
;; namespace, also with no new events), on a resumed deferred build's staged
;; progress, and inside every batch transaction (model and staging
;; namespaces; the staged progress written by the deferred build carries it
;; and is published with the build). It is independent of the index count, so
;; removing a model's last index is caught too. This is a contract check for
;; definitions changed without a :version bump, not a cache-format guard.
(defn- keyed-index-layout-value
  "[sorted index names, sorted :filtered-indexes, sorted search names] of d."
  [d]
  [(vec (sort (keys (:indexes d)))) (vec (sort (:filtered-indexes d))) (vec (sort (keys (:searches d))))])

(def ^:private ^java.util.concurrent.ConcurrentHashMap keyed-index-layouts
  ;; definition -> its fingerprint. Lock-free reads (OPT-10 audit C4: it is
  ;; looked up on every read session); registered definitions are the same
  ;; map instance on every call (identity hit, cached map hash). Bounded:
  ;; definitions are few, the cache is dropped if it ever grows past 4096.
  (java.util.concurrent.ConcurrentHashMap.))

(defn- keyed-index-layout
  "The fingerprint stored in the progress record: key-encode-compact of
   keyed-index-layout-value."
  ^bytes [d]
  (or (.get keyed-index-layouts d)
      (let [fingerprint (key-encode-compact (keyed-index-layout-value d))]
        (when (> (.size keyed-index-layouts) 4096) (.clear keyed-index-layouts))
        (.put keyed-index-layouts d fingerprint)
        fingerprint)))

(defn- keyed-check-index-layout!
  "Throws :grain.read-model/invalid-cache unless the stored progress record
   (nil for a namespace never written) holds exactly d's index-layout
   fingerprint. Allocation free on the matching path."
  [progress d]
  (when progress
    (let [stored (:index-layout progress)]
      (when-not (and (bytes? stored) (Arrays/equals ^bytes stored (keyed-index-layout d)))
        (let [[indexes filtered searches] (keyed-index-layout-value d)
              [stored-indexes stored-filtered stored-searches :as decoded]
              (when (bytes? stored) (try (key-decode-compact stored) (catch Exception _ nil)))]
          (throw (ex-info "Keyed model indexes, filtered indexes or searches differ from the ones this cache was built with; the definition changed without a version bump"
                          {:type :grain.read-model/invalid-cache
                           :stored-indexes (if (vector? decoded) stored-indexes stored)
                           :indexes indexes
                           :stored-filtered-indexes stored-filtered :filtered-indexes filtered
                           :stored-searches stored-searches :searches searches})))))))

(defn- keyed-public-progress
  "The progress record as the read API returns it (without :index-layout)."
  [progress]
  (some-> progress (dissoc :index-layout)))

(defn- keyed-sort-data!
  "Stored sort data (kind 11) of an existing result. Old index positions are
   read by field ordinal of the current definition: the ordinal of an index is
   its rank among the SORTED non-identity index names. The stored field count
   must equal 2 + the model's non-identity index count (OPT-9 audit C2), so an
   index added or removed without a model version bump fails loudly here
   instead of wedging catch-up. The field count alone does NOT catch a change
   that keeps the count but changes a name: such a rename (or any other
   name->field remap) would make OTHER, unrenamed indexes read their old
   position from the wrong field and corrupt them (W2 audit C1). That case is
   caught earlier by the index-name fingerprint in the progress record
   (keyed-check-index-layout!); this count check stays as a second guard."
  ^bytes [tx ^bytes sort-k id ^long field-count]
  (let [^bytes data (or ((:get tx) sort-k)
                        (throw (ex-info "Keyed sort metadata is missing for a stored result"
                                        {:type :grain.read-model/invalid-cache :id id})))
        stored (when (>= (alength data) 4) (dec (quot (sort-data-int-at data 0) 4)))]
    (when-not (= stored field-count)
      (throw (ex-info "Keyed sort metadata does not match the model's indexes; index definitions changed without a version bump"
                      {:type :grain.read-model/invalid-cache :id id
                       :stored-fields stored :expected-fields field-count})))
    data))

(defn- keyed-old-position
  "The stored value of index at sort-data field. key-decode-compact
   round-trips every value the index encoders accept to a value with the same
   ordered encoding, so the member and group key it yields are the ones that
   were written (I14 proves the resulting bytes equal the :indexes path)."
  [_id _index ^bytes old-data field]
  (sort-data-value old-data field))

(defn- keyed-read-meta [tx k] (some-> ((:get tx) k) codec/decode))
(defn- keyed-write! [tx k v] ((:put tx) k (codec/encode v)))
(defn- keyed-write-observed! [ctx tx operation k value]
  (let [bytes (codec/encode value)]
    ((:put tx) k bytes)
    (keyed-observe! ctx operation (alength ^bytes bytes))))
(defn- keyed-prepare-validation [schema name kind]
  (if schema
    (let [schema (m/schema schema)
          valid? (m/validator schema)]
      (fn [value]
        (when-not (valid? value)
          (throw (ex-info "Keyed-fold schema validation failed"
                          {:model name :kind kind :explanation (m/explain schema value)})))))
    (constantly nil)))

;; ---------------------------------------------------------------------------
;; Deferred cold builds
;; ---------------------------------------------------------------------------
;; A cold catch-up with a large backlog folds states and results into a
;; private staging namespace without index work, builds every index structure
;; once from the final stored results, then publishes by exchanging the
;; staging and model registry records in one transaction. Readers resolve the
;; model identity, so they see either the untouched cold namespace or the
;; complete build, never a partial one; older processors never resolve the
;; staging identity. Each model identity has one staging identity (bounded
;; registry growth) and one KB9 marker naming the owning build, process and a
;; heartbeat. A live foreign build is awaited; a crashed, failed or expired one
;; is taken over and its fold resumed from the staging watermark.
;;
;; Documented costs (work, never correctness; OPT-4 audit):
;; - A single source page slower than max-stall + lease in the middle of a fold
;;   (after the owner has progressed, generation 0) causes one spurious
;;   takeover: the taker resumes the staged fold and redoes the build, and the
;;   old owner is superseded. A second consecutive stall doubles the next
;;   owner's max-stall.
;; - A third process's long write transaction can delay the owner's heartbeat
;;   transaction long enough for a waiter to win the write lock and take over a
;;   healthy owner, with the same cost.
;; - A failure inside the build (after the fold), including transient ones
;;   such as MapFull, writes the failure record and disables deferral for the
;;   identity until it is warm; fold-phase failures keep the staged progress.
;; - Replaced-namespace collection infers quiescence (two equal fingerprints
;;   deferred-replaced-gc-ms apart); an older-version fold stalled for over an
;;   hour between two batches could still be wiped under it.
;; - Since INT, staged folds use the adaptive catch-up batch policy (OPT-2), so
;;   one staged batch transaction holds up to :max-events events; the
;;   heartbeat is stamped at the end of every such transaction.

(def ^:private deferred-marker-prefix (byte-array [75 66 57]))
(def ^:private deferred-default-threshold 1024)
(def ^:private deferred-default-entries 1000000)
(def ^:private deferred-default-lease-ms 30000)
(def ^:private deferred-default-max-stall-ms 60000)
(def ^:private deferred-default-max-stall-cap-ms (* 10 60 1000))
(def ^:private deferred-replaced-gc-ms (* 60 60 1000))
(def ^:private deferred-chunk 4096)

(defn- with-namespace-space [identity f]
  ;; Scoped identities live in their own registry and data prefixes.
  (if (= 4 (count identity))
    (let [prefix (byte-array [75 82 83 57])]
      (binding [namespace-registry-prefix prefix
                namespace-counter-key (key-join prefix (byte-array [0]))
                namespace-data-prefix (byte-array [75 70 83 57])]
        (f)))
    (f)))

(defn- namespace-prefix-of [tx identity]
  (with-namespace-space identity #(get (namespace-resolve-known tx [identity]) identity)))

(defn- namespace-ordinal-of [tx identity]
  (let [next-id (namespace-counter tx)]
    (when (and (some? next-id) (namespace-lookup tx identity next-id))
      (:ordinal (:value (namespace-read-value tx (namespace-forward-key identity)))))))

(defn- namespace-swap-checked! [tx a b]
  (let [oa (namespace-ordinal-of tx a) ob (namespace-ordinal-of tx b)]
    (when-not (and (some? oa) (some? ob) (not= oa ob))
      (namespace-failure "Cannot exchange unregistered namespaces" {}))
    (let [ra (codec/encode {:identity a :ordinal ob})
          rb (codec/encode {:identity b :ordinal oa})]
      ((:put tx) (namespace-forward-key a) ra)
      ((:put tx) (namespace-reverse-key ob) ra)
      ((:put tx) (namespace-forward-key b) rb)
      ((:put tx) (namespace-reverse-key oa) rb)
      (namespace-address ob))))

(defn- namespace-swap!
  "Exchange two registered identities' ordinals. The registry stays a bijection
   under an unchanged counter, so every reader validates it as before."
  [tx a b]
  (with-namespace-space a
    #(if-let [with-validation (:with-validation tx)]
       (with-validation namespace-registry-prefix namespace-validate-allocation!
         (fn [] (namespace-swap-checked! tx a b)))
       (do (namespace-validate-allocation! tx)
           (namespace-swap-checked! tx a b)))))

(defn- deferred-staging-identity [identity]
  ;; One staging identity per model identity: at most two registry records.
  (assoc identity 1 (keyword "grain.read-model.staging"
                             (str "s" (.formatHex (HexFormat/of) (namespace-digest identity))))
                  2 1))
(defn- deferred-slot
  "KB9 + digest(identity) + kind: 1 build marker, 2 failed build, 3 replaced namespace."
  [identity kind]
  (key-join deferred-marker-prefix (namespace-digest identity) (byte-array [kind])))

(defn- deferred-start-ms [^java.lang.ProcessHandle h]
  (some-> ^java.util.Optional (.startInstant (.info h)) (.orElse nil) (#(.toEpochMilli ^java.time.Instant %))))
(def ^:private deferred-self
  (delay (let [h (java.lang.ProcessHandle/current)] {:pid (.pid h) :started (deferred-start-ms h)})))

(defn- deferred-live?
  "A marker owned by another running process with a fresh heartbeat. LMDB
   environments are host-local, so process liveness is checked directly."
  [{:keys [pid started at]} lease-ms]
  (boolean
    (and pid at (not= pid (:pid @deferred-self))
         (< (- (System/currentTimeMillis) at) lease-ms)
         (let [^java.util.Optional o (java.lang.ProcessHandle/of (long pid))]
           (and (.isPresent o)
                (let [^java.lang.ProcessHandle h (.get o)]
                  (and (.isAlive h) (= started (deferred-start-ms h)))))))))

(defn- deferred-marker [tx identity] (some-> ((:get tx) (deferred-slot identity 1)) codec/decode))

(defn- deferred-guard!
  "Ownership check and heartbeat, inside every staging transaction."
  [tx staging]
  (let [m (some-> ((:get tx) (:marker staging)) codec/decode)]
    (when-not (= (:build staging) (:build m))
      (throw (ex-info "Deferred cold build was superseded" {:type ::deferred-superseded})))
    ((:put tx) (:marker staging) (codec/encode (assoc m :at (System/currentTimeMillis))))))

(defn- deferred-stamp!
  "Refresh the heartbeat at the end of a long staging transaction (after its
   guard), so that a waiter never sees a heartbeat older than the transaction.
   folded? marks real progress: the takeover generation resets to zero."
  ([tx staging] (deferred-stamp! tx staging false))
  ([tx staging folded?]
   (when-let [m (some-> ((:get tx) (:marker staging)) codec/decode)]
     (when (= (:build staging) (:build m))
       ((:put tx) (:marker staging)
                  (codec/encode (cond-> (assoc m :at (System/currentTimeMillis))
                                  folded? (assoc :progressed true :generation 0))))))))

(defn- deferred-max-stall-ms
  "Effective max-stall for an owner claimed at takeover generation g: the base
   doubles per consecutive takeover without progress, up to the cap. A slow
   first event (e.g. a slow resume query) therefore converges once the
   effective stall exceeds its latency, while a hung owner is still taken over
   within cap + lease.

   Worst case (OPT-4 audit): after at least four consecutive unprogressed
   takeovers (slow first events or crash loops; 60 s doubled four times
   exceeds the cap) a genuinely hung owner is taken over only after
   cap + lease (default 10 min + 30 s, about 10.5 min; lowered from 30 min
   at wave-2 integration).
   Meanwhile every waiting process holds its JVM's cache lock in
   deferred-await!, so all keyed reads in that JVM (every tenant and model)
   stall for that time. The baseline has no cross-process waiting: each JVM
   holds its lock only for its own (slower, incremental) build. Lower
   :read-model/deferred-build-max-stall-cap-ms to bound this further."
  [ctx generation]
  (let [base (long (:read-model/deferred-build-max-stall-ms ctx deferred-default-max-stall-ms))
        cap (long (:read-model/deferred-build-max-stall-cap-ms ctx deferred-default-max-stall-cap-ms))]
    (max base (min cap (* base (bit-shift-left 1 (min 30 (long generation))))))))

(defn- deferred-progress!
  "Record that the owning build thread made progress (an event read, a batch,
   a chunk or a sort). The heartbeat only extends the lease while it is recent."
  [staging]
  (when-let [^java.util.concurrent.atomic.AtomicLong p (:progress staging)]
    (.set p (System/currentTimeMillis))))

(defn- deferred-heartbeat!
  "Keep an owned build's lease alive between staging transactions (source
   reads, sorting) from a daemon thread. Returns a stop function. The thread
   stops by itself once the marker names another build."
  [ctx staging lease-ms]
  (let [running (volatile! true)
        ^java.util.concurrent.atomic.AtomicLong progress (:progress staging)
        ^java.util.concurrent.atomic.AtomicLong generation (:generation staging)
        recent? #(or (nil? progress)
                     (< (- (System/currentTimeMillis) (.get progress))
                        (deferred-max-stall-ms ctx (if generation (.get generation) 0))))
        interval (max 5 (quot (long lease-ms) 3))
        t (doto (Thread.
                  ^Runnable
                  (fn []
                    (try
                      (loop []
                        (Thread/sleep interval)
                        (when (and @running
                                   ;; A stuck build thread stops extending its lease, so
                                   ;; another process can take over after max-stall + lease.
                                   (or (not (recent?))
                                       (kv/transact (:cache ctx) :write
                                     (fn [tx]
                                       (let [m (some-> ((:get tx) (:marker staging)) codec/decode)]
                                         (when (and @running (= (:build staging) (:build m)) (:pid m))
                                           ((:put tx) (:marker staging)
                                                      (codec/encode (assoc m :at (System/currentTimeMillis))))
                                           true))))))
                          (recur)))
                      (catch InterruptedException _ nil)
                      (catch Throwable _ nil)))
                  "grain-deferred-build-heartbeat")
            (.setDaemon true)
            (.start))]
    (fn [] (vreset! running false) (.interrupt t) (.join t 1000))))

(defn- keyed-wipe!
  "Delete every row under prefix, in bounded chunks within this transaction."
  [tx ^bytes prefix]
  (loop []
    (let [^ArrayList ks ((:reduce tx) {:prefix prefix}
                         (fn [^ArrayList out {:keys [k]}]
                           (.add out k)
                           (if (= deferred-chunk (.size out)) (reduced out) out))
                         (ArrayList.))]
      (when-not (.isEmpty ks)
        (doseq [k ks] ((:delete tx) k))
        (recur)))))

(defn- deferred-lease-ms [ctx] (:read-model/deferred-build-lease-ms ctx deferred-default-lease-ms))

(defn- deferred-claim!
  "Claim identity's staging build. Returns the build, :wait while another live
   process owns it, :restart when identity no longer resolves to the cold
   namespace b the caller read (another build published meanwhile), or nil
   when a failed build disabled deferral for identity. A stale marker's fold
   is resumed, never discarded."
  [ctx identity b]
  (let [staging (deferred-staging-identity identity)
        build (str (random-uuid))]
    (kv/transact (:cache ctx) :write
      (fn [tx]
        (let [m (deferred-marker tx identity)
              current (namespace-prefix-of tx identity)]
          (cond
            ;; Re-validate under this write transaction: after a publish the
            ;; staging identity owns the replaced namespace, which must never
            ;; be taken (or wiped) as a new staging area.
            (not (and current (Arrays/equals ^bytes current ^bytes b)
                      (nil? (:watermark (keyed-read-meta tx (keyed-progress-key b))))))
            :restart
            (and m (deferred-live? m (deferred-lease-ms ctx))) :wait
            ((:get tx) (deferred-slot identity 2)) nil
            :else
            (let [prefix (get (with-namespace-space staging #(namespace-allocate! tx [staging])) staging)
                  ;; Taking over an owner whose lease lapsed before it folded
                  ;; anything doubles the next owner's max-stall (bounded).
                  ;; Only this owning claim writes the generation.
                  generation (if (and m (:pid m) (not (:progressed m)))
                               (min 30 (inc (long (:generation m 0))))
                               0)]
              (when-not m (keyed-wipe! tx prefix))
              ((:put tx) (deferred-slot identity 1)
                         (codec/encode (merge {:identity identity :staging staging :build build
                                               :at (System/currentTimeMillis)
                                               :generation generation :progressed false}
                                              @deferred-self)))
              {:identity staging :prefix prefix :marker (deferred-slot identity 1)
               :build build :resumed? (some? m)
               :progress (java.util.concurrent.atomic.AtomicLong. (System/currentTimeMillis))
               :generation (java.util.concurrent.atomic.AtomicLong. generation)})))))))

(defn- deferred-release!
  "Keep the staged fold for the next attempt, which may be in any process."
  [ctx staging]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [m (some-> ((:get tx) (:marker staging)) codec/decode)]
        (when (= (:build m) (:build staging))
          ((:put tx) (:marker staging) (codec/encode (assoc m :pid nil :at nil))))))))

(defn- deferred-abandon! [ctx identity staging failed?]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      ;; Only the owner wipes, and only the namespace the staging identity
      ;; still resolves to (an owned marker implies no publish happened).
      (when (and (= (:build staging) (:build (deferred-marker tx identity)))
                 (Arrays/equals ^bytes (namespace-prefix-of tx (:identity staging)) ^bytes (:prefix staging)))
        (keyed-wipe! tx (:prefix staging))
        ((:delete tx) (:marker staging))
        ;; A deterministic build failure would recur on every retry; later
        ;; catch-ups use incremental batches.
        (when failed?
          ((:put tx) (deferred-slot identity 2) (codec/encode {:identity identity})))))))

(defn- deferred-await!
  "Wait while another live process builds identity (bounded by its lease)."
  [ctx identity]
  (loop []
    (when (kv/transact (:cache ctx) :read
            #(some-> (deferred-marker % identity) (deferred-live? (deferred-lease-ms ctx))))
      (Thread/sleep 50)
      (recur))))

(defn- deferred-fingerprint
  "Activity fingerprint of a namespace: its progress record (every batch of any
   processor version rewrites it) and whether any row exists."
  [tx ^bytes prefix]
  [(some-> ((:get tx) (keyed-progress-key prefix)) (#(.formatHex (HexFormat/of) ^bytes %)))
   ((:reduce tx) {:prefix prefix} (fn [_ _] (reduced true)) false)])

(defn- deferred-clean-stale!
  "Warm namespaces with work: stop and wipe a non-live build, drop a failure
   record, and collect a replaced namespace only once it is quiescent: its
   fingerprint must be unchanged across two observations at least
   deferred-replaced-gc-ms apart (any fold activity restarts the clock). An
   absent KB9 scope costs one seek."
  [ctx identity]
  (let [cache (:cache ctx)
        scope (key-join deferred-marker-prefix (namespace-digest identity))
        now (System/currentTimeMillis)
        plan (fn [tx]
               (let [m (deferred-marker tx identity)
                     live? (and m (deferred-live? m (deferred-lease-ms ctx)))
                     replaced (some-> ((:get tx) (deferred-slot identity 3)) codec/decode)
                     staging-prefix (namespace-prefix-of tx (deferred-staging-identity identity))
                     fp (when (and replaced staging-prefix (not live?)) (deferred-fingerprint tx staging-prefix))
                     observed? (and replaced (contains? replaced :observed-at))]
                 {:staging-prefix staging-prefix
                  :stale-marker? (and m (not live?))
                  :failure? (some? ((:get tx) (deferred-slot identity 2)))
                  :collect? (and fp observed? (= fp (:fingerprint replaced))
                                 (>= (- now (:observed-at replaced)) deferred-replaced-gc-ms))
                  :observe (when (and fp (not (and observed? (= fp (:fingerprint replaced)))))
                             (assoc replaced :fingerprint fp :observed-at now))}))]
    (when (kv/transact cache :read #((:reduce %) {:prefix scope} (fn [_ _] (reduced true)) false))
      (let [{:keys [stale-marker? failure? collect? observe]} (kv/transact cache :read plan)]
        (when (or stale-marker? failure? collect? observe)
          (kv/transact cache :write
            (fn [tx]
              (let [{:keys [staging-prefix stale-marker? failure? collect? observe]} (plan tx)]
                (when (and staging-prefix (or stale-marker? collect?))
                  (keyed-wipe! tx staging-prefix))
                (when stale-marker? ((:delete tx) (deferred-slot identity 1)))
                (when failure? ((:delete tx) (deferred-slot identity 2)))
                (cond collect? ((:delete tx) (deferred-slot identity 3))
                      observe ((:put tx) (deferred-slot identity 3) (codec/encode observe)))))))))))

(defn- deferred-scan!
  "Visit every row under prefix. Rows are read in chunks so that the visitor's
   writes never interleave with an open cursor."
  [tx ^bytes prefix f]
  (loop [from nil]
    (let [^ArrayList chunk ((:reduce tx) (cond-> {:prefix prefix} from (assoc :from from))
                            (fn [^ArrayList out e]
                              (.add out e)
                              (if (= deferred-chunk (.size out)) (reduced out) out))
                            (ArrayList.))]
      (doseq [e chunk] (f e))
      (when (= deferred-chunk (.size chunk))
        (recur (ranked-after (:k (.get chunk (dec deferred-chunk)))))))))

(defn- deferred-chunks!
  "Process rows under prefix in chunks of at most size rows, each in its own
   guarded write transaction (the staging namespace is private until publish)."
  [ctx staging ^bytes prefix size f]
  (loop [from nil]
    (when-let [next (kv/transact (:cache ctx) :write
                      (fn [tx]
                        (deferred-guard! tx staging)
                        (let [tx (with-promotion-key ctx tx)
                              ^ArrayList rows ((:reduce tx) (cond-> {:prefix prefix} from (assoc :from from))
                                               (fn [^ArrayList out e]
                                                 (.add out e)
                                                 (if (= size (.size out)) (reduced out) out))
                                               (ArrayList.))]
                          (f tx rows)
                          (deferred-stamp! tx staging)
                          (when (= size (.size rows)) (ranked-after (:k (.get rows (dec size))))))))]
      (deferred-progress! staging)
      (recur next))))

;; Span construction over members in ascending order: level-zero members are
;; written by the caller; every upper-level span is written once, when it closes.
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
  (let [levels (int (:levels cfg)) membership? (:membership? cfg)
        hash (ranked-hash tx cfg member)]
    (loop [level 1]
      (when (< level levels)
        (if (zero? (bit-and hash (dec (bit-shift-left 1 (* 4 level)))))
          (do (ranked-span-flush! tx cfg spans level)
              (aset keys level member)
              (aset counts level 1)
              (when membership?
                (aset bits level (doto (RoaringBitmap.) (.add (int ordinal))))))
          (do (aset counts level (inc (aget counts level)))
              (when membership? (.add ^RoaringBitmap (aget bits level) (int ordinal)))))
        (recur (inc level))))))

(defn- ranked-span-finish! [tx cfg spans]
  ;; Every touched sentinel keeps a summary, even an empty one.
  (loop [level 1] (when (< level (:levels cfg)) (ranked-span-flush! tx cfg spans level) (recur (inc level)))))

(defn- ranked-bulk-load!
  "Write sorted, distinct [member payload ordinal] entries into an empty index.
   Persists the same members, towers, span counts and span summaries as
   ranked-append! of each member in order, but writes every span once."
  [tx cfg ^ArrayList entries]
  (let [spans (ranked-spans cfg)]
    (ranked-init! tx cfg)
    (doseq [^objects entry entries]
      (let [^bytes member (aget entry 0) ordinal (aget entry 2)]
        ((:put tx) (ranked-pack cfg 0 member) (aget entry 1))
        (when (:membership? cfg)
          ((:put tx) (ranked-membership-key cfg 0 member) (membership-number-bytes ordinal)))
        (ranked-span-add! tx cfg spans member ordinal)))
    (ranked-span-finish! tx cfg spans)))

(def ^:private deferred-member-order
  (reify Comparator
    (compare [_ a b] (Arrays/compareUnsigned ^bytes (aget ^objects a 0) ^bytes (aget ^objects b 0)))))

(defn- deferred-entry [d id indexes ordinal payload [index direction]]
  (let [position (get indexes index)]
    (object-array [(keyed-index-member d index direction position id) payload ordinal position
                   (when (keyed-grouped? index direction) (key-encode position))])))

(defn- deferred-sort! [index ^ArrayList entries]
  (.sort entries deferred-member-order)
  (dotimes [i (dec (.size entries))]
    (when (zero? (Arrays/compareUnsigned ^bytes (aget ^objects (.get entries i) 0)
                                         ^bytes (aget ^objects (.get entries (inc i)) 0)))
      (throw (ex-info "Duplicate deferred index member" {:index index})))))

(defn- deferred-write-groups!
  "Group counts from ascending, value-ordered entries (equal values form one
   run). merge? adds to counts written by earlier sorted runs.
   Since OPT-9 W7 the group :value is decoded from the staged compact sort
   data (kind 11), not taken from the extractor (OPT-9 audit C1, accepted and
   documented): key bytes, order, totals and lookups are identical, but a few
   value forms change: BigInt/BigInteger values come back as Long (= to the
   original), and a keyword whose printed form does not re-read identically,
   e.g. (keyword \"a/b\" \"c\"), comes back as (keyword \"a\" \"b/c\") (not =).
   index-groups therefore returns the decoded form after a bulk build."
  [tx b d index ^ArrayList entries merge?]
  (let [prefix (ranked-prefix (keyed-index-config b d index :asc) 6)
        n (.size entries)]
    (loop [i 0]
      (when (< i n)
        (let [^objects first-entry (.get entries i) ^bytes value-key (aget first-entry 4)
              end (loop [j (inc i)]
                    (if (and (< j n) (Arrays/equals value-key ^bytes (aget ^objects (.get entries j) 4)))
                      (recur (inc j)) j))
              k (key-checked (key-join prefix value-key))
              before (if merge? (or (some-> ((:get tx) k) codec/decode :total) 0) 0)]
          ((:put tx) k (codec/encode {:value (aget first-entry 3) :total (+ before (- end i))}))
          (recur end))))))

(defn- deferred-build!
  "Build every index, group count and text posting from the staged results.
   :read-model/deferred-build-entries bounds the index entries held in memory:
   below it, several (index, direction) units share one sorted pass; above it,
   each unit is built from sorted runs of that size and one ordered pass over
   level zero. Text postings are written in chunks of the same size."
  [ctx d staging]
  (let [cache (:cache ctx) sb (:prefix staging)
        searches (:searches d)
        ordinals? (or (seq searches) (seq (:filtered-indexes d)))
        units (vec (for [index (conj (vec (sort (keys (:indexes d)))) :identity)
                         direction [:asc]]
                     [index direction]))
        {:keys [n touched?]}
        (kv/transact cache :read
          (fn [tx] {:n (or (keyed-read-meta tx (keyed-count-key sb)) 0)
                    ;; Incremental folds initialize every index on the first
                    ;; inclusion, which also writes the included bitmap.
                    :touched? (some? ((:get tx) (keyed-included-key sb)))}))
        budget (max 1 (long (:read-model/deferred-build-entries ctx deferred-default-entries)))
        ;; OPT-6: every index is built directly at the level count the
        ;; incremental path keeps for n members (keyed-relevel!).
        levels (keyed-target-levels n)
        unit-config (fn [index direction] (assoc (keyed-membership-config sb d index direction) :levels levels))
        results (key-join sb (byte-array [2]))
        index-fields (keyed-index-fields d)
        ;; OPT-9 W7: staged results no longer carry :indexes; index
        ;; values come from the owner's staged sort data (kind 11, written by
        ;; the staging fold with every result), the identity value from its
        ;; field 0 (the stored (str id)). The id is the result row's own :key,
        ;; as the incremental path's owner id, so payload decisions (W8) and
        ;; member suffixes match it exactly.
        row (fn [tx ^bytes k v indexes?]
              (let [{id :key value :value} (codec/decode v)]
                [id value
                 (when indexes?
                   (let [sort-k (doto (Arrays/copyOf k (alength k)) (aset (alength ^bytes sb) (byte 11)))
                         data (keyed-sort-data! tx sort-k id (+ 2 (count index-fields)))]
                     (into {:identity (sort-data-value data 0)}
                           (map (fn [[index field]] [index (keyed-old-position id index data field)]))
                           index-fields)))
                 (when ordinals?
                   (or (membership-ordinal tx sb id)
                       (throw (ex-info "Staged result has no model ordinal" {:id id}))))]))]
    (when touched?
      ;; A resumed build may hold index and text rows of an interrupted build.
      (kv/transact cache :write
        (fn [tx]
          (deferred-guard! tx staging)
          (keyed-wipe! tx (key-join sb (byte-array [5])))
          (keyed-wipe! tx (key-join sb (byte-array [6])))))
      (when (seq searches)
        (let [terms (mapv (fn [[index extract]] [(text-search-config sb index) extract (HashMap.)]) searches)]
          (deferred-chunks! ctx staging results budget
            (fn [tx rows]
              (doseq [{:keys [k v]} rows
                      :let [[_ value _ ordinal] (row tx k v false)]
                      [cfg extract ^HashMap counts] terms
                      [term posting] (text-search-terms (text-search-normalize (extract value)))]
                ((:put tx) (text-search-posting-key (text-search-term-key cfg 0 term) ordinal) posting)
                (.put counts term (inc (long (or (.get counts term) 0)))))))
          (kv/transact cache :write
            (fn [tx]
              (deferred-guard! tx staging)
              (doseq [[cfg _ ^HashMap counts] terms
                      ^java.util.Map$Entry entry (.entrySet counts)]
                ((:put tx) (text-search-term-key cfg 1 (.getKey entry))
                           (text-search-number-bytes (.getValue entry))))))))
      (if (<= n budget)
        (doseq [pass (partition-all (max 1 (quot budget (max 1 n))) units)]
          (kv/transact cache :write
            (fn [tx]
              (deferred-guard! tx staging)
              (let [tx (with-promotion-key ctx tx)
                    lists (mapv (fn [unit] [unit (ArrayList. (int n))]) pass)]
                (deferred-scan! tx results
                  (fn [{:keys [k v]}]
                    (let [[id _ indexes ordinal] (row tx k v true) payload (keyed-id-payload id)]
                      (doseq [[unit ^ArrayList out] lists]
                        (.add out (deferred-entry d id indexes ordinal payload unit))))))
                (doseq [[[index direction] ^ArrayList entries] lists]
                  (deferred-sort! index entries)
                  (ranked-bulk-load! tx (unit-config index direction) entries)
                  (keyed-write-levels! tx sb d index levels)
                  (when (keyed-grouped? index direction) (deferred-write-groups! tx sb d index entries false))
                  (.clear entries))
                (deferred-stamp! tx staging))))
          (deferred-progress! staging))
        (doseq [[index direction :as unit] units
                :let [cfg (unit-config index direction)
                      spans (ranked-spans cfg)]]
          (kv/transact cache :write (fn [tx] (deferred-guard! tx staging) (ranked-init! tx cfg)
                                      (keyed-write-levels! tx sb d index levels)))
          ;; Sorted runs of at most budget members, written at level zero.
          (deferred-chunks! ctx staging results budget
            (fn [tx ^ArrayList rows]
              (let [entries (ArrayList. (.size rows))]
                (doseq [{:keys [k v]} rows :let [[id _ indexes ordinal] (row tx k v true)]]
                  (.add entries (deferred-entry d id indexes ordinal (keyed-id-payload id) unit)))
                (deferred-sort! index entries)
                (doseq [^objects e entries]
                  ((:put tx) (ranked-pack cfg 0 (aget e 0)) (aget e 1))
                  (when (:membership? cfg)
                    ((:put tx) (ranked-membership-key cfg 0 (aget e 0)) (membership-number-bytes (aget e 2)))))
                (when (keyed-grouped? index direction) (deferred-write-groups! tx sb d index entries true)))))
          ;; One ordered pass over level zero closes every upper-level span.
          (deferred-chunks! ctx staging (ranked-prefix cfg 0) budget
            (fn [tx rows]
              (doseq [{:keys [k]} rows
                      :let [member (ranked-unpack cfg k)]
                      :when (pos? (alength ^bytes member))]
                (ranked-span-add! tx cfg spans member
                                  (when (:membership? cfg)
                                    (membership-number-value ((:get tx) (ranked-membership-key cfg 0 member))))))))
          (kv/transact cache :write
            (fn [tx] (deferred-guard! tx staging) (ranked-span-finish! tx cfg spans)))
          (deferred-progress! staging))))))

(defn- deferred-publish!
  "Atomically publish a completed staging namespace as identity's namespace,
   provided that namespace is still the cold one this build started from.
   Returns the published prefix, or nil when another writer advanced it."
  [ctx identity b staging]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (deferred-guard! tx staging)
      (let [progress (keyed-read-meta tx (keyed-progress-key b))
            current (namespace-prefix-of tx identity)
            empty-kind? (fn [kind] ((:reduce tx) {:prefix (key-join b (byte-array [kind]))}
                                    (fn [_ _] (reduced false)) true))]
        (when (and current (Arrays/equals ^bytes current ^bytes b)
                   (nil? (:watermark progress)) (empty-kind? 1) (empty-kind? 2))
          (let [published (namespace-swap! tx identity (:identity staging))]
            ((:delete tx) (:marker staging))
            ;; The replaced cold namespace now belongs to the staging identity.
            (keyed-wipe! tx b)
            ;; An older writer that resolved b before this swap may still fold
            ;; into it for its own session; that copy is collected later.
            ((:put tx) (deferred-slot identity 3) (codec/encode {:at (System/currentTimeMillis)}))
            published))))))

(defn- keyed-resolve [ctx identity]
  (kv/transact (:cache ctx) :read #(namespace-prefix-of % identity)))

(defn- keyed-apply-batch!
  ([ctx name d b validate-state! validate-result! events]
   (keyed-apply-batch! ctx name d b validate-state! validate-result! events nil))
  ([ctx name d b validate-state! validate-result! events through]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [;; Logical bytes this batch writes (keys + values) feed the
            ;; catch-up batch policy; they never affect what is written.
            written (long-array 1)
            put (:put tx)
            tx (assoc tx :put (fn [^bytes k ^bytes v]
                                (aset written 0 (+ (aget written 0) (alength k) (alength v)))
                                (put k v)))
            ;; INT (OPT-1 + OPT-2): the counting :put is installed first so the
            ;; per-txn keyed write session and membership flushes both see it.
            tx (keyed-write-session (membership-transaction tx) b)
            ;; OPT-10 W9: upper-level span counts are written once per batch.
            tx (span-count-transaction tx)
            ;; OPT-3: per-cache promotion key, read or created in this txn
            ;; (through the counting :put) only when a member is inserted.
            tx (with-promotion-key ctx tx)
            identity-base (:ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix ctx)
            deferred (::deferred ctx)
            _ (when deferred (deferred-guard! tx deferred))
            before-progress (keyed-read-meta tx (keyed-progress-key b))
            ;; OPT-10 (W2-C2): index names must match the stored fingerprint.
            _ (keyed-check-index-layout! before-progress d)
            events (filterv #(or (nil? (:watermark before-progress))
                                (pos? (compare (:event/id %) (:watermark before-progress)))) events)
            apply-input (fn [states id input]
                          (when (nil? id)
                            (throw (ex-info "Keyed-fold event has no owner"
                                            {:model name :event-id (:event/id input)
                                             :event-type (:event/type input)})))
                          (let [k (keyed-address b 1 id)
                                state (if (contains? states id) (get states id)
                                        (if-let [raw ((:get tx) k)]
                                          (keyed-decode ctx :state-decoded raw) (:initial d {})))
                                next ((:reducer-fn d) state input)]
                            (validate-state! next)
                            (keyed-observe! ctx :event-applied 0)
                            (assoc states id next)))
            states (reduce
                    (fn [states event]
                      (if-let [route (:route d)]
                        (let [inputs (route event)]
                          (when-not (sequential? inputs)
                            (throw (ex-info "Route must return an ordered sequence of [owner input] pairs"
                                            {:model name :event-id (:event/id event)})))
                          (reduce (fn [states entry]
                                    (when-not (and (vector? entry) (= 2 (count entry)))
                                      (throw (ex-info "Invalid routed [owner input] pair"
                                                      {:model name :event-id (:event/id event)})))
                                    (apply-input states (nth entry 0) (nth entry 1)))
                                  states inputs))
                        (apply-input states ((:key d) event) event))) {} events)
            count-before (or (keyed-read-meta tx (keyed-count-key b)) 0)
            added (volatile! 0)
            builders (when-not deferred (keyed-cold-index-builders ctx tx b d before-progress states))
            ordinals? (or (seq (:searches d)) (seq (:filtered-indexes d)))
            ;; OPT-9 W7: sort-data field order of the non-identity indexes.
            index-fields (keyed-index-fields d)]
        (doseq [[id state] states]
          ;; FINAL (OPT-10 audit C1): keep the batch's count caches bounded.
          (count-caches-bound! tx)
          (let [owner (keyed-owner id)
                value ((:result d identity) state)
                _ (validate-result! value)
                result-k (keyed-owner-address b 2 owner)
                sort-k (keyed-owner-address b 11 owner)
                old (keyed-decode ctx :result-decoded ((:get tx) result-k))
                included? ((:include? d (constantly true)) value)
                ;; Non-identity index values. The identity index value is always
                ;; (str id) and never changes for an owner.
                indexes (when included?
                          (into {} (map (fn [[index f]] [index (f value)])) (:indexes d)))]
            (keyed-observe! ctx :result-calculated 0)
            ;; State must advance even when its completion value is equal.
            (keyed-write-observed! ctx tx :state-written (keyed-owner-address b 1 owner) state)
            (vswap! added + (- (if included? 1 0) (if old 1 0)))
            (when (not= (boolean included?) (boolean old))
              (let [ordinal (membership-ensure-ordinal! tx identity-base id)
                    bits (membership-bitmap tx (keyed-included-key b))]
                (if included? (.add ^RoaringBitmap bits (int ordinal))
                    (.remove ^RoaringBitmap bits (int ordinal)))
                (membership-put! tx (keyed-included-key b) bits)))
            (if-not included?
              (when old
                ;; A staging namespace holds no index entries until its build
                ;; (OPT-4); index removal uses the write-session owner (OPT-1).
                (when-not deferred
                  (text-search-update! tx b (:searches d) id old nil)
                  ;; OPT-9 W7: old positions come from the owner's sort
                  ;; data, which holds every non-identity index value.
                  (let [old-data (when (seq index-fields) (keyed-sort-data! tx sort-k id (+ 2 (count index-fields))))]
                    (doseq [direction [:asc]]
                      (keyed-remove-index! tx b d :identity direction (str id) owner))
                    (doseq [[index field] index-fields
                            :let [position (keyed-old-position id index old-data field)]
                            direction [:asc]]
                      (keyed-remove-index! tx b d index direction position owner))))
                ((:delete tx) sort-k)
                ((:delete tx) result-k))
              (when (or (nil? old) (not= (:value old) value))
                (let [;; Sort metadata is a pure function of id and index values
                      ;; and is written with every result. An owner with no
                      ;; non-identity index has constant sort data.
                      new-data (when (or (nil? old) (seq index-fields))
                                 (sort-data-encode id indexes))
                      ;; OPT-9 W7: the stored sort data of an existing
                      ;; owner gives its old index positions. Byte-equal fields
                      ;; mean byte-equal members (since OPT-8 fields and member
                      ;; values use the same injective encoding, key-write-key!).
                      ;; Cost (OPT-9 audit C3): this is one extra kind-11 get on
                      ;; EVERY value-changing update of an existing result when
                      ;; the model has a non-identity index, also when no
                      ;; indexed field changed (INT compared the decoded
                      ;; :indexes for free); e.g. I1 :touch-1 on [:is :ik]
                      ;; 4.03 -> 5.03 ops/event, no visible time cost. Index
                      ;; extractors are deliberately not re-run to avoid it.
                      old-data (when (and old (seq index-fields)) (keyed-sort-data! tx sort-k id (+ 2 (count index-fields))))]
                  (if deferred
                    ;; Reserve model ordinals at the same point as the
                    ;; incremental path, so assignments are identical.
                    (when ordinals? (membership-ensure-ordinal! tx b id))
                    (do
                      (text-search-update! tx b (:searches d) id old {:value value})
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
                          (keyed-enqueue-index! ctx tx b d builders index direction position owner)))))
                  ;; INVARIANT (OPT-1 W12b, OPT-9 W7): kind 11 is written only
                  ;; here, in the same transaction and branch as the kind-2
                  ;; result, and deleted only together with it, so an existing
                  ;; result always has current sort data. Its put is skipped
                  ;; when the new bytes equal the stored ones (or the model has
                  ;; no non-identity index, so they cannot change). Every writer
                  ;; of kind 2 (incremental or a deferred build's staging fold,
                  ;; OPT-4) goes through this code; since OPT-9 (W7) the old index
                  ;; positions, the deferred build's index entries and the
                  ;; removal on exclusion are all read from kind 11, so a future
                  ;; writer of kind 2 must also write kind 11. Likewise the
                  ;; write-session ensure-init skip (W12a) assumes nothing in
                  ;; this transaction deletes a level-0 sentinel of an index
                  ;; that is still in use (the deferred build's kind 5/6 wipe
                  ;; runs in its own guarded transactions, never here). Since
                  ;; OPT-6, keyed-relevel! does delete upper-level sentinels in
                  ;; this transaction when an index shrinks; that is safe: it
                  ;; runs after all index work of the batch, never deletes
                  ;; level 0, and drops the index's session configs, so no
                  ;; stale ::initialized flag or level count is reused.
                  (when-not (and old (or (empty? index-fields) (Arrays/equals ^bytes old-data ^bytes new-data)))
                    ((:put tx) sort-k new-data)))
                ;; OPT-9 W7: results no longer repeat the index values.
                (keyed-write-observed! ctx tx :result-written result-k {:key id :value value})))))
        (keyed-build-cold-indexes! tx builders)
        ;; OPT-10 W9/W10: all index and text work of the batch is done; write
        ;; every changed span count, group record and term count once
        ;; (re-leveling and later reads go to LMDB directly).
        (span-count-flush! tx)
        (group-count-flush! tx)
        (term-count-flush! tx)
        ;; OPT-6: N changed, so every index moves to the level count for the
        ;; new N in this same transaction (staging namespaces hold no index
        ;; rows; the deferred build sizes them at build time).
        (when (and (not deferred) (seq events) (not (zero? @added)))
          (keyed-relevel! tx b d (+ count-before @added)))
        (membership-flush! tx #(keyed-observe! ctx %1 %2))
        (when deferred (deferred-stamp! tx deferred (boolean (seq events))))
        (when (seq events)
          (when-not (zero? @added) (keyed-write! tx (keyed-count-key b) (+ count-before @added))))
        ;; Only the final partial batch receives a captured head, after its
        ;; ordered source read completed. Preserve progress from concurrent
        ;; writers even when they already applied every event in this tail.
        (let [advance-through? (and through
                                   (or (nil? (:checked-through before-progress))
                                       (pos? (compare through (:checked-through before-progress)))))
              progress (cond-> before-progress
                         ;; A checked non-nil record already holds the names.
                         (nil? before-progress) (assoc :index-layout (keyed-index-layout d))
                         (seq events) (assoc :watermark (:event/id (peek events)))
                         advance-through? (assoc :checked-through through))]
          (when (or (seq events) advance-through?)
            (keyed-write! tx (keyed-progress-key b) progress)))
        {:events (count events) :bytes (aget written 0)})))))

(defn- keyed-head [ctx]
  (let [result (es/head (:event-store ctx) (:tenant-id ctx))]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info "Cannot read the committed event-store head" result)))
    result))

;; This is event-source absence evidence, not projection freshness. Owners are
;; weak, descriptors contain only validated immutable identity values, and a
;; full working set retains ordinary durable checkpointing for overflow scopes.
(def ^:private empty-proof-max-entries 256)
(def ^:private empty-proof-max-bytes (* 16 1024 1024))
(def ^:private empty-proof-max-entry-bytes (* 256 1024))
(def ^:private empty-proof-prune-every 256)
(def ^:private empty-proofs* (atom {:entries {} :bytes 0}))

(defn- empty-proof-key [source identity d]
  [(System/identityHashCode source) identity (:events d)])

(defn- empty-proof-size [[_ identity types]]
  ;; Two bytes per character conservatively accounts for UTF-16 strings;
  ;; fixed object/collection overhead is charged per entry and event type.
  (+ 1024 (* 2 (count (str (nth identity 1))))
     (if-let [scope (nth identity 3 nil)] (* 2 (count (second scope))) 0)
     (* 128 (count types))
     (reduce + 0 (map #(* 2 (count (str %))) types))))

(defn- empty-proof-forget! [key]
  (locking empty-proofs*
    (when-let [proof (get-in @empty-proofs* [:entries key])]
      (swap! empty-proofs* #(-> % (update :entries dissoc key)
                               (update :bytes - (:bytes proof)))))))

(defn- empty-proof-covers? [source key after through]
  (locking empty-proofs*
    (when-let [proof (get-in @empty-proofs* [:entries key])]
      (if-not (identical? source (.get ^WeakReference (:source proof)))
        (do (empty-proof-forget! key) false)
        (and (or (nil? (:after proof))
                 (and after (not (neg? (compare after (:after proof))))))
             (not (pos? (compare through (:through proof)))))))))

(defn- prune-empty-proofs [state]
  (reduce-kv (fn [out key proof]
               (if (.get ^WeakReference (:source proof)) out
                 (-> out (update :entries dissoc key) (update :bytes - (:bytes proof)))))
             state (:entries state)))

(defn- empty-proof-remember! [source key after through]
  (let [size (empty-proof-size key)]
    (when (<= size empty-proof-max-entry-bytes)
      (locking empty-proofs*
        (let [state @empty-proofs*
              old (get-in state [:entries key])
              state (if (and (nil? old)
                             (or (>= (count (:entries state)) empty-proof-max-entries)
                                 (> (+ (:bytes state) size) empty-proof-max-bytes)))
                      ;; Cleanup is amortized across capacity misses, never
                      ;; repeated for every overflow student or reset by hits.
                      (if (pos? (:prune-countdown state 0))
                        (update state :prune-countdown dec)
                        (assoc (prune-empty-proofs state) :prune-countdown (dec empty-proof-prune-every)))
                      state)
              old (get-in state [:entries key])
              bytes (+ (- (:bytes state) (:bytes old 0)) size)]
          (if (and (or old (< (count (:entries state)) empty-proof-max-entries))
                   (<= bytes empty-proof-max-bytes))
            (let [through (if (and old (identical? source (.get ^WeakReference (:source old)))
                                   (= after (:after old))
                                   (pos? (compare (:through old) through)))
                            (:through old) through)]
              (reset! empty-proofs*
                      (assoc state
                             :entries (assoc (:entries state) key
                                             {:source (WeakReference. source) :after after
                                              :through through :bytes size})
                             :bytes bytes))
              true)
            (do (reset! empty-proofs* state) false)))))))

(defn- keyed-checkpoint! [ctx d b through]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (when-let [staging (::deferred ctx)] (deferred-guard! tx staging))
      (let [p (keyed-read-meta tx (keyed-progress-key b)) old (:checked-through p)]
        (keyed-check-index-layout! p d)
        (when (or (nil? old) (pos? (compare through old)))
          (keyed-write! tx (keyed-progress-key b)
                        (assoc (or p {:index-layout (keyed-index-layout d)}) :checked-through through)))))))

(def ^:private default-catch-up-policy
  ;; :min-events is the first batch of a warm catch-up, so a near-head
  ;; catch-up commits exactly as a fixed 128-event batch did. A replay that
  ;; keeps filling batches doubles toward :max-events while each committed
  ;; batch wrote at most :max-bytes (logical key + value bytes). Above that the
  ;; size holds, and only a batch over four times :max-bytes halves it (never
  ;; below :min-events). Bytes are not assumed proportional to events: one
  ;; large coalesced state costs the same in a small batch as in a big one.
  ;; A never-built namespace has no history to be near, so it starts at
  ;; :cold-events.
  ;;
  ;; Input: a batch is also cut once the estimated in-memory size of its
  ;; accumulated source events reaches :max-input-bytes, so large payloads
  ;; never hold more than that in one batch.
  ;;
  ;; Store space (stores that report it): large batches need unallocated map
  ;; space because a write transaction cannot reuse the pages it frees, and a
  ;; full, fragmented LMDB file cannot place large contiguous values. Once
  ;; the file passes :max-map-fill of the map, or the unallocated tail is under
  ;; four times the last batch's written bytes, the rest of the catch-up uses
  ;; :min-events. A batch that still fails with a full map is retried in
  ;; halves down to :min-events, and later batches stay at the size that fit.
  ;;
  ;; Operational notes (OPT-2 audit): the speed-up needs the LMDB file to stay
  ;; at most :max-map-fill (half) of :map-size; size :map-size at about twice
  ;; the expected file, since high-water never shrinks and past that point
  ;; every catch-up runs at :min-events (today's speed). A cache already wedged
  ;; by fragmentation at a full map recovers with `mdb_copy -c` or a larger
  ;; map. The input estimate (keyed-input-size) charges opaque scalars 16 B, so
  ;; numeric-heavy payloads (BigDecimal/BigInteger) can hold up to ~4.5x
  ;; :max-input-bytes before the cut. Memory per batch is roughly batch events
  ;; x input event size; :read-model/catch-up {:max-events ..} or
  ;; :read-model/batch-size is the escape hatch for large-payload streams.
  {:min-events 128 :cold-events 1024 :max-events 8192 :max-bytes (* 32 1024 1024)
   :max-input-bytes (* 32 1024 1024) :max-map-fill 0.5})

(def ^:private catch-up-policy-keys (set (keys default-catch-up-policy)))

(defn- keyed-batch-policy
  "Catch-up batch policy. :read-model/catch-up overrides the adaptive defaults
   and is always validated; an explicit :read-model/batch-size then wins and
   keeps every batch at exactly that size with no other bound. Batch size
   never changes results, only commit granularity."
  [ctx]
  (let [overrides (:read-model/catch-up ctx)
        _ (when-not (or (nil? overrides) (map? overrides))
            (throw (ex-info "Keyed-fold catch-up policy must be a map" {:catch-up overrides})))
        unknown (remove catch-up-policy-keys (keys overrides))
        _ (when (seq unknown)
            (throw (ex-info "Keyed-fold catch-up policy has unknown keys"
                            {:unknown (vec unknown) :allowed (sort catch-up-policy-keys)})))
        {:keys [min-events cold-events max-events max-bytes max-input-bytes max-map-fill] :as policy}
        (merge default-catch-up-policy overrides)]
    (when-not (and (pos-int? min-events) (pos-int? cold-events) (pos-int? max-events)
                   (<= min-events cold-events max-events)
                   (or (nil? max-bytes) (pos-int? max-bytes))
                   (or (nil? max-input-bytes) (pos-int? max-input-bytes))
                   (or (nil? max-map-fill) (and (number? max-map-fill) (< 0 max-map-fill) (<= max-map-fill 1))))
      (throw (ex-info "Keyed-fold catch-up policy requires positive integer :min-events <= :cold-events <= :max-events, positive or nil :max-bytes and :max-input-bytes, and :max-map-fill in (0, 1] or nil"
                      {:catch-up policy})))
    (if-some [batch-size (:read-model/batch-size ctx)]
      (do (when-not (pos-int? batch-size)
            (throw (ex-info "Keyed-fold batch size must be positive" {:batch-size batch-size})))
          {:min-events batch-size :cold-events batch-size :max-events batch-size
           :max-bytes nil :max-input-bytes nil :max-map-fill nil})
      policy)))

(defn- keyed-input-size
  "Rough in-memory size of a source event (UTF-16 strings, small per-object
   overhead); only used to bound how much input one batch holds."
  ^long [x]
  (cond (string? x) (+ 40 (* 2 (.length ^String x)))
        (map? x) (reduce-kv (fn [^long n k v] (+ n 32 (keyed-input-size k) (keyed-input-size v))) 48 x)
        (bytes? x) (+ 16 (alength ^bytes x))
        (coll? x) (reduce (fn [^long n v] (+ n 8 (keyed-input-size v))) 48 x)
        :else 16))

(defn- keyed-store-space
  "{:map-bytes :high-water-bytes} when the store reports it, else nil."
  [ctx]
  (kv/transact (:cache ctx) :read #(when-let [space (:space %)] (space))))

(defn- keyed-next-batch-limit
  "Next batch size after a full batch, from the bytes the last one wrote and,
   when known, the store's remaining map space."
  [{:keys [min-events max-events max-bytes max-map-fill]} limit applied space]
  (let [bytes (:bytes applied)
        tail (when space (- (:map-bytes space) (:high-water-bytes space)))]
    (cond (and space max-map-fill
               (or (> (:high-water-bytes space) (* max-map-fill (:map-bytes space)))
                   (< tail (* 4 (or bytes 0)))))
          min-events
          (or (nil? max-bytes) (nil? bytes) (<= bytes max-bytes)) (min max-events (* 2 limit))
          (> bytes (* 4 max-bytes)) (max min-events (quot limit 2))
          :else limit)))

(defn- keyed-map-full? [t]
  ;; Matched by class name: if lmdbjava ever renames Env$MapFullException,
  ;; backoff silently stops and MapFull propagates as before this policy.
  (loop [t t]
    (cond (nil? t) false
          (= "org.lmdbjava.Env$MapFullException" (.getName (class t))) true
          :else (recur (.getCause ^Throwable t)))))

(defn- keyed-apply-with-backoff!
  "Apply one batch; if the store's map is full, the aborted batch is applied
   again as two halves (recursively, down to min-events). Each applied part is
   its own transaction carrying its own watermark, and only the last part
   carries through. on-backoff receives the part size that is attempted."
  [ctx name d b validate-state! validate-result! events through min-events on-backoff]
  (try
    (if through
      (keyed-apply-batch! ctx name d b validate-state! validate-result! events through)
      (keyed-apply-batch! ctx name d b validate-state! validate-result! events))
    (catch Throwable t
      (if (and (keyed-map-full? t) (> (count events) min-events))
        (let [half (quot (inc (count events)) 2)
              _ (on-backoff half)
              first-part (keyed-apply-with-backoff! ctx name d b validate-state! validate-result!
                                                    (subvec events 0 half) nil min-events on-backoff)
              second-part (keyed-apply-with-backoff! ctx name d b validate-state! validate-result!
                                                     (subvec events half) through min-events on-backoff)]
          {:events (count events) :bytes (max (:bytes first-part 0) (:bytes second-part 0)) :backed-off true})
        (throw t)))))

(defn- keyed-deferrable? [ctx progress identity]
  (let [threshold (:read-model/deferred-build-threshold ctx deferred-default-threshold)]
    (boolean (and identity (nil? (:watermark progress)) (nil? (:read-model/observer ctx))
                  (pos-int? threshold) (not (::no-deferral ctx))))))

(defn- keyed-catch-up-step!
  "One catch-up attempt. Returns the model's data prefix, or [::restart ctx]
   when the identity must be re-resolved (another build published, a live
   build was awaited, or publish was refused).

   Batches follow the adaptive catch-up policy (keyed-batch-policy) whether
   they fold into the model namespace or into a deferred build's staging
   namespace: every batch is cut at the current limit or input budget, and
   each full batch's written bytes and the store's space set the next limit.
   A backlog that has reached neither the deferral threshold nor the input
   budget (:max-input-bytes) is buffered and
   later cut by exactly the same procedure, so its batch boundaries equal a
   non-deferring catch-up's."
  [ctx name d b through identity]
  (let [progress (kv/transact (:cache ctx) :read #(keyed-read-meta % (keyed-progress-key b)))
        ;; OPT-10 (W2-C2): fail before any work, also when nothing is pending.
        _ (keyed-check-index-layout! progress d)
        after (last (sort (keep #(get progress %) [:checked-through :watermark])))
        ;; Validated before any work, also when nothing is pending.
        policy (keyed-batch-policy ctx)
        restart (fn [ctx] [::restart ctx])]
    (if-not (and through (or (nil? after) (pos? (compare through after))))
      b
      (let [source (:event-store ctx)
            stable? (es/stable-empty-intervals? source)
            proof-key (when stable? (empty-proof-key source identity d))]
        (if (and stable? (empty-proof-covers? source proof-key after through))
          b
          (let [deferrable? (keyed-deferrable? ctx progress identity)
                ;; An unpublished build of this cold namespace is resumed (or
                ;; awaited while its owner is alive) before reading events.
                pending? (and deferrable?
                              (some? (kv/transact (:cache ctx) :read #(deferred-marker % identity))))
                claimed (when pending? (deferred-claim! ctx identity b))]
            (case claimed
              :wait (do (deferred-await! ctx identity) (restart ctx))
              :restart (restart ctx)
              (let [validate-state! (keyed-prepare-validation (:state-schema d) name :state)
                    validate-result! (keyed-prepare-validation (:result-schema d) name :result)
                    staged (when claimed
                             (kv/transact (:cache ctx) :read #(keyed-read-meta % (keyed-progress-key (:prefix claimed)))))
                    read-after (last (sort (remove nil? [after (:checked-through staged) (:watermark staged)])))
                    query (keyed-event-query ctx d read-after)
                    accepted? (volatile! false)
                    threshold (when deferrable? (:read-model/deferred-build-threshold ctx deferred-default-threshold))
                    ;; Undecided until the backlog reaches the threshold; smaller
                    ;; cold backlogs are applied exactly as without deferral.
                    decided? (volatile! (or (not deferrable?) pending?))
                    buffered (volatile! [])
                    staging (volatile! claimed)
                    beat (volatile! nil)
                    own! (fn [s] (vreset! staging s)
                           (when s (vreset! beat (deferred-heartbeat! ctx s (deferred-lease-ms ctx)))))
                    min-events (:min-events policy)
                    max-input (:max-input-bytes policy)
                    adaptive? (not= min-events (:max-events policy))
                    ;; Every batch still commits its states, results, indexes and
                    ;; watermark in one transaction; only its size adapts. The
                    ;; model namespace's own progress decides the cold start.
                    limit (volatile! (if (nil? after) (:cold-events policy) min-events))
                    ;; A size that hit a full map is never exceeded again here.
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
                               ;; The tail batch ends this catch-up: no next size.
                               (when-not through
                                 (vreset! limit (min @ceiling
                                                     (keyed-next-batch-limit
                                                       policy @limit applied
                                                       (when (and adaptive? (:max-map-fill policy)) (keyed-store-space ctx))))))
                               applied))
                    ;; Add one event to the open batch; commit it once it reaches
                    ;; the limit or the input budget. Returns the open batch.
                    accumulate (fn [batch event]
                                 (let [batch (conj batch event)
                                       in (if max-input (vswap! input + (keyed-input-size event)) 0)]
                                   (if (or (<= @limit (count batch)) (and max-input (>= in max-input)))
                                     (do (apply! batch nil) [])
                                     batch)))
                    ;; Cut buffered events exactly as if they had been accumulated
                    ;; while reading; returns the open (partial) batch.
                    drain! (fn [events] (vreset! input 0) (reduce accumulate [] events))]
                (when claimed (own! claimed))
                (try
                  ;; OPT-10 (W2-C2): a resumed build's staged rows were folded
                  ;; under the staged index names. Checked inside the try, so a
                  ;; mismatch releases the claim (the staged fold is kept).
                  (keyed-check-index-layout! staged d)
                  (let [remaining (reduce
                                    (fn [batch event]
                                      (if (pos? (compare (:event/id event) through)) (reduced batch)
                                        (do
                                          (when (and stable? (not @accepted?))
                                            (empty-proof-forget! proof-key))
                                          (vreset! accepted? true)
                                          (deferred-progress! @staging)
                                          (if-not @decided?
                                            (let [batch (conj batch event)
                                                  ;; The undecided buffer also respects the input
                                                  ;; budget (INT audit condition 1): a backlog that
                                                  ;; reaches :max-input-bytes is large, so it is
                                                  ;; deferred now, and drain! then cuts its batches at
                                                  ;; the budget. drain! resets the counter, so input is
                                                  ;; never double-counted. Without a budget (explicit
                                                  ;; :read-model/batch-size) only the threshold decides.
                                                  in (if max-input (vswap! input + (keyed-input-size event)) 0)]
                                              (if (and (< (count batch) threshold)
                                                       (not (and max-input (>= in max-input))))
                                                (do (vreset! buffered batch) batch)
                                                (let [c (deferred-claim! ctx identity b)]
                                                  (when (= :wait c)
                                                    (throw (ex-info "Another process is building this model"
                                                                    {:type ::deferred-await})))
                                                  (when (= :restart c)
                                                    (throw (ex-info "Another build published this model"
                                                                    {:type ::deferred-restart})))
                                                  (vreset! decided? true) (vreset! buffered nil)
                                                  (own! c)
                                                  (drain! batch))))
                                            (accumulate batch event)))))
                                    [] (if (seq query) (es/read source query) []))
                        remaining (if @decided? remaining
                                    (do (vreset! decided? true) (vreset! buffered nil) (drain! remaining)))]
                    (when (seq remaining)
                      (apply! remaining through))
                    ;; Only a completed, ordered read with no accepted source events
                    ;; can suppress a checkpoint. Route-empty events are not absence.
                    (if-let [s @staging]
                      (keyed-checkpoint! (assoc ctx ::deferred s) d (:prefix s) through)
                      (when-not (or (seq remaining)
                                    (and stable? (not @accepted?)
                                         (empty-proof-remember! source proof-key after through)))
                        (keyed-checkpoint! ctx d b through)))
                    (if-let [s @staging]
                      (do (try (deferred-build! ctx d s)
                               (catch Throwable t
                                 (when-not (= ::deferred-superseded (:type (ex-data t)))
                                   (try (deferred-abandon! ctx identity s true)
                                        (catch Throwable cleanup (.addSuppressed t cleanup))))
                                 (throw t)))
                          ;; Publishing is the last step before the read snapshot.
                          (or (deferred-publish! ctx identity b s)
                              (do (deferred-abandon! ctx identity s false)
                                  (restart (assoc ctx ::no-deferral true)))))
                      (do
                        ;; Housekeeping only after a successful warm catch-up that
                        ;; applied events: a failing batch leaves the cache exactly
                        ;; as it was, and a read with no new events writes nothing.
                        (when (and @accepted? (not deferrable?) (:watermark progress) identity)
                          (deferred-clean-stale! ctx identity))
                        b)))
                  (catch Throwable e
                    (let [s @staging]
                      (cond
                        (= ::deferred-await (:type (ex-data e)))
                        (do (deferred-await! ctx identity) (restart ctx))

                        (= ::deferred-restart (:type (ex-data e)))
                        (restart ctx)

                        (nil? s)
                        (do
                          ;; Like ordinary batches, batches that were complete
                          ;; before a source failure stay committed.
                          (when-not @decided?
                            (try (drain! @buffered) (catch Throwable x (.addSuppressed e x))))
                          (throw e))

                        ;; Another process took over this build: read what it publishes.
                        (= ::deferred-superseded (:type (ex-data e)))
                        (restart ctx)

                        :else
                        (do
                          ;; Keep the staged fold: the next attempt resumes from
                          ;; its watermark, so a failing source still converges.
                          (some-> @beat (apply []))
                          (try (deferred-release! ctx s) (catch Throwable x (.addSuppressed e x)))
                          (throw e)))))
                  (finally (some-> @beat (apply []))))))))))))

(defn- keyed-catch-up!
  "Apply events through the captured head. Returns the model's data prefix,
   which differs from b only after a deferred cold build was published.
   Restarts (after awaiting or being superseded by another build) loop here
   with a re-resolved prefix, never recursing."
  [ctx name d b through identity]
  (loop [ctx ctx b b]
    (let [r (keyed-catch-up-step! ctx name d b through identity)]
      (if (and (vector? r) (= ::restart (nth r 0 nil)))
        (let [ctx (nth r 1)]
          (recur ctx (or (keyed-resolve ctx identity) b)))
        r))))

(defn- keyed-identity-of [ctx] [(:tenant-id ctx) (:read-model/scope ctx)])

(defn keyed-session-definition [ctx name]
  (when-let [s (:ai.obney.grain.read-model-processor-v2.core.keyed/session ctx)]
    (when-not (and (= (:identity s) (keyed-identity-of ctx))
                   (identical? (:cache s) (:cache ctx))
                   (identical? (:event-store s) (:event-store ctx)))
      (throw (ex-info "Read-session context identity was changed" {:model name})))
    (or (get (:definitions s) name)
        (throw (ex-info "Model was not prepared in this read session" {:model name})))))

(defn- keyed-prepare-reader [tx b d]
  ;; Both point reads and traversals use the same prepared address space.
  ;; The transaction still enforces callback/thread lifetime on every access.
  (let [read (fn [kind id] ((:get tx) (keyed-address b kind id)))]
    (fn [f] (f tx b d read))))

(defn keyed-read-session
  "Prepare explicit model namespaces and catch up before one finite read snapshot."
  [requests registry f]
  (when-not (seq requests) (throw (ex-info "Read session requires at least one request" {})))
  (let [cache (:cache (ffirst requests))
        event-store (:event-store (ffirst requests))]
    (when-not cache (throw (ex-info "Read session requires :cache" {})))
    (doseq [[ctx names] requests]
      (keyed-validate-scope! (:read-model/scope ctx))
      (when (:ai.obney.grain.read-model-processor-v2.core.keyed/session ctx)
        (throw (ex-info "Cannot open a nested keyed-fold read session" {})))
      (when-not (identical? cache (:cache ctx))
        (throw (ex-info "Read-session contexts must share one store" {})))
      (when-not (identical? event-store (:event-store ctx))
        (throw (ex-info "Read-session contexts must share one event store" {})))
      (doseq [name names]
        (when-not (keyed? (get registry name))
          (throw (ex-info "Expected a registered keyed fold" {:model name}))))
      ;; Duplicate requests still validate their own configuration even when
      ;; their namespace's catch-up is shared with an earlier request.
      (when (seq names) (keyed-batch-policy ctx)))
    (let [;; Reuse only immutable address preparation within this session. Heads,
          ;; registry validation and the snapshot are still acquired on every
          ;; session; nothing survives into a later request or cache reopen.
          scope-identities (into {} (map (fn [scope] [scope (keyed-scope-identity scope)]))
                                 (distinct (map (comp :read-model/scope first) requests)))
          identities (volatile! {})
          identity-order (volatile! [])
          identity-for (fn [ctx name d]
                         (let [k [(:tenant-id ctx) name (:version d 1) (:read-model/scope ctx)]]
                           (or (get @identities k)
                               (let [identity (keyed-namespace-identity ctx name d
                                                (get scope-identities (:read-model/scope ctx)))]
                                 (vswap! identities assoc k identity)
                                 (vswap! identity-order conj identity)
                                 identity))))
          requests (mapv (fn [[ctx names]]
                           [ctx (identity-for ctx :grain.read-query/shared-identities {:version 1})
                            (into {} (map (fn [name] [name (identity-for ctx name (get registry name))]))
                                  (distinct names))]) requests)
          {:keys [prefixes prepared]}
          (locking cache
            (let [resolved (namespace-resolve! cache @identity-order)
                  ;; A published deferred build replaces a model's prefix.
                  prefixes (volatile! resolved)
                  heads (reduce (fn [heads [ctx _ _]]
                                  (let [id (:tenant-id ctx)]
                                    (if (contains? heads id) heads (assoc heads id (keyed-head ctx))))) {} requests)
                  caught-up (volatile! #{})
                  prepared (mapv
                             (fn [[ctx shared-identity model-identities]]
                               (let [identity-prefix (get @prefixes shared-identity)]
                                 (doseq [[name identity] model-identities
                                         :when (not (contains? @caught-up identity))]
                                   (let [b (get @prefixes identity)
                                         current (keyed-catch-up! (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix identity-prefix)
                                                                  name (get registry name) b
                                                                  (get heads (:tenant-id ctx)) identity)]
                                     (vswap! prefixes assoc identity (or current b)))
                                   (vswap! caught-up conj identity))
                               [ctx (select-keys registry (keys model-identities)) model-identities])) requests)]
              {:prefixes @prefixes :prepared prepared}))]
      (kv/transact cache :read
        (fn [tx]
          (f (mapv (fn [[ctx definitions model-identities]]
                     (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/session
                            {:cache cache :event-store event-store :identity (keyed-identity-of ctx)
                             :definitions definitions
                             :readers (into {} (map (fn [[name d]]
                                                      [name (keyed-prepare-reader tx
                                                              (get prefixes (get model-identities name)) d)]))
                                            definitions)})) prepared)))))))

(defn- keyed-with-reader [ctx name d f]
  (if-let [s (:ai.obney.grain.read-model-processor-v2.core.keyed/session ctx)]
    (do (keyed-session-definition ctx name)
        ((get (:readers s) name) f))
    (keyed-read-session [[ctx [name]]] {name d}
                  (fn [[prepared]] ((get-in prepared [:ai.obney.grain.read-model-processor-v2.core.keyed/session :readers name]) f)))))

(defn keyed-point [ctx name d id kind not-found]
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (keyed-with-reader ctx name d
    (fn [_tx _b _d read]
      (if-let [raw (read (if (= kind :state) 1 2) id)]
        (let [decoded (keyed-decode ctx (if (= kind :state) :state-decoded :result-decoded) raw)]
          (if (= kind :state) decoded (:value decoded)))
        not-found))))

(defn keyed-same-members? [ctx a ad b bd]
  ;; The shared ordinal namespace is tenant scoped; these are exact sets,
  ;; not cardinality or hash-based guesses. Called within a prepared session.
  (keyed-with-reader ctx a ad
    (fn [tx ab _ _]
      (keyed-with-reader ctx b bd
        (fn [_ bb _ _]
          (let [left ((:get tx) (keyed-included-key ab))
                right ((:get tx) (keyed-included-key bb))]
            (keyed-observe! ctx :identity-membership-read (+ (if left (alength ^bytes left) 0)
                                                     (if right (alength ^bytes right) 0)))
            (= (if left (membership-decode left) (RoaringBitmap.))
               (if right (membership-decode right) (RoaringBitmap.)))))))))

(defn keyed-progress [ctx name d]
  (keyed-with-reader ctx name d (fn [tx b _d _read] (keyed-public-progress (keyed-read-meta tx (keyed-progress-key b))))))

(defn keyed-search-keys [ctx name d options]
  (keyed-with-reader ctx name d
    (fn [tx b _ _read]
      (text-search-search-keys tx b (:searches d) options #(keyed-observe! ctx %1 %2)))))

(defn- keyed-reduce-distinct [rf initial items]
  (let [seen (volatile! (transient #{}))]
    (reduce (fn [acc item]
              (if (contains? @seen item) acc
                (do (vreset! seen (conj! @seen item))
                    (rf acc item))))
            initial items)))

(defn keyed-index-values [ctx name d indexes ids]
  (when-not (and (vector? indexes) (every? #(or (= :identity %) (contains? (:indexes d) %)) indexes))
    (throw (ex-info "Expected a vector of declared index names" {:model name :indexes indexes})))
  (let [fields (mapv #(sort-data-field (:indexes d) %) indexes)]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf initial]
        (keyed-with-reader ctx name d
          (fn [tx b _ read]
            (let [step (fn [acc raw]
                         (keyed-observe! ctx :sort-key-read (alength ^bytes raw))
                         (rf acc {:key (sort-data-value raw 1)
                                  :value (sort-data-values raw indexes fields)}))]
              (if (some? ids)
                (keyed-reduce-distinct (fn [acc id] (if-let [raw (read 11 id)] (step acc raw) acc)) initial ids)
                ((:reduce tx) {:prefix (key-join b (byte-array [11]))}
                 (fn [acc {:keys [v]}] (step acc v)) initial)))))))))

(defn keyed-key-page [ctx name d keys options]
  (let [{:keys [index indexes direction page limit sort-value include?]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :indexes :direction :page :limit :sort-value :include?} (clojure.core/keys options))
                   (or (= index :identity) (contains? (:indexes d) index))
                   (or (not (contains? options :indexes))
                       (and (or sort-value include?) (vector? indexes)
                            (every? #(or (= :identity %) (contains? (:indexes d) %)) indexes)))
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit)
                   (or (nil? sort-value) (ifn? sort-value))
                   (or (nil? include?) (ifn? include?)))
      (throw (ex-info "Invalid matching-key page" {:model name :options options})))
    (keyed-with-reader ctx name d
      (fn [tx b _d read]
        (let [field (sort-data-field (:indexes d) index)
              fields (mapv #(sort-data-field (:indexes d) %) indexes)
              comparator (sort-data-comparator field (= direction :desc) (some? sort-value))
              ;; Keep the worst retained entry at the front, so later candidates
              ;; outside the requested prefix can be discarded immediately.
              entries (java.util.PriorityQueue. 11 (.reversed ^java.util.Comparator comparator))
              wanted (long (min Long/MAX_VALUE (*' page limit)))
              ;; Inline aset keeps these counters primitive; aset-long boxes
              ;; its value argument on each candidate.
              ^longs counts (long-array 2)
              collect! (fn [id raw]
                         (keyed-observe! ctx :sort-key-read (alength ^bytes raw))
                         (aset counts 0 (inc (aget counts 0)))
                         (let [values (when (or include? sort-value)
                                        (if (some? indexes)
                                          (sort-data-values raw indexes fields)
                                          (sort-data-value raw field)))]
                           (when (or (nil? include?) (include? id values))
                             (aset counts 1 (inc (aget counts 1)))
                             (let [entry (ai.obney.grain.read_model_processor_v2.core.SortDataEntry.
                                          id raw (when sort-value (key-encode-compact (sort-value id values))))]
                               (if (< (.size entries) wanted)
                                 (.add entries entry)
                                 (when (neg? (.compare ^java.util.Comparator comparator entry (.peek entries)))
                                   (.poll entries)
                                   (.add entries entry)))))))]
          (if (= :ai.obney.grain.read-model-processor-v2.core.keyed/all keys)
            ((:reduce tx) {:prefix (key-join b (byte-array [11]))}
             (fn [_ {:keys [v]}] (collect! (sort-data-value v 1) v)) nil)
            (let [seen (java.util.HashSet.)]
              (doseq [id keys :when (.add seen id)]
                (when-let [raw (read 11 id)] (collect! id raw)))))
          (let [^objects ordered (.toArray entries)
                _ (Arrays/sort ordered ^java.util.Comparator comparator)
                total (aget counts 1) pages (max 1 (quot (+' total (dec limit)) limit))
                page (min page pages) offset (* (dec page) limit)]
            {:keys (mapv #(.-id ^ai.obney.grain.read_model_processor_v2.core.SortDataEntry (aget ordered %))
                         (range offset (min total (+ offset limit))))
             :total total :total-pages pages :page page :page-size limit
             :sort-keys-read (aget counts 0)}))))))

(defn- keyed-leaf-config
  "OPT-9 W8: ranked-filtered-page returns owner ids for real leaves."
  [cfg d index direction]
  (assoc cfg ::leaf-payload (fn [^bytes member ^bytes v] (keyed-entry-id d index direction member 0 v))))

(defn- keyed-page-key
  "Real leaves arrive as decoded ids (keyed-leaf-config); virtual joined-page
   members keep their Fressian payload. Ids are never byte arrays."
  [p]
  (if (bytes? p) (codec/decode p) p))

(defn keyed-filtered-key-page [ctx name d keys options]
  (let [{:keys [index direction page limit]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :direction :page :limit} (clojure.core/keys options))
                   (contains? (:filtered-indexes d) index)
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit))
      (throw (ex-info "Ranked key page requires an opted-in stored index" {:model name :options options})))
    (keyed-with-reader ctx name d
      (fn [tx b _d _read]
        (let [candidates (RoaringBitmap.) examined (volatile! 0)]
          (doseq [id keys]
            (vswap! examined inc)
            (let [ordinal (membership-ordinal tx b id)]
              (keyed-observe! ctx :candidate-ordinal-read (if (some? ordinal) 4 0))
              (when (some? ordinal) (.add candidates (int ordinal)))))
          (let [result (ranked-filtered-page tx (keyed-leaf-config (keyed-read-config tx b d (keyed-membership-config b d index :asc) index)
                                                                   d index :asc)
                                            candidates page limit #(keyed-observe! ctx %1 %2) []
                                            direction (keyed-value-length d index))]
            (-> result
                (dissoc :payloads)
                (assoc :keys (mapv keyed-page-key (:payloads result))
                       :candidate-keys-read @examined :sort-keys-read 0))))))))

(defn keyed-joined-page [ctx membership-name membership-d name d options]
  (let [{:keys [index direction page limit missing-value]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options)
                   (every? #{:index :direction :page :limit :missing-value :keys} (clojure.core/keys options))
                   (contains? options :missing-value) (contains? (:filtered-indexes d) index)
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit))
      (throw (ex-info "Joined page requires a ranked index and an explicit missing value"
                      {:model name :options options})))
    (let [missing-prefix (keyed-sort-bytes missing-value :asc)]
      (if-not (:ai.obney.grain.read-model-processor-v2.core.keyed/session ctx)
        (keyed-read-session [[ctx [membership-name name]]] {membership-name membership-d name d}
                      (fn [[prepared]] (keyed-joined-page prepared membership-name membership-d name d options)))
        (keyed-with-reader ctx membership-name membership-d
          (fn [tx membership-base _ membership-read]
            (keyed-with-reader ctx name d
              (fn [_ b _ _]
                (let [cfg (keyed-leaf-config (keyed-read-config tx b d (keyed-membership-config b d index :asc) index)
                                             d index :asc)
                      observe #(keyed-observe! ctx %1 %2)
                      live (ranked-members tx cfg observe)
                      candidates (RoaringBitmap.) virtual (ArrayList.)
                      examined (volatile! 0)
                      collect! (fn [id]
                                 (vswap! examined inc)
                                 (let [ordinal (membership-ordinal tx b id)]
                                   (observe :candidate-ordinal-read (if (some? ordinal) 4 0))
                                   (if (and (some? ordinal) (.contains live (int ordinal)))
                                     (.add candidates (int ordinal))
                                     (do (observe :join-default-key 0)
                                         (.add virtual (RankedVirtualMember.
                                                         (key-join missing-prefix (key-id-suffix id))
                                                         (codec/encode id)))))))]
                  (if (contains? options :keys)
                    (let [seen (HashSet.)]
                      (doseq [id (:keys options) :when (.add seen id)]
                        (let [raw (membership-read 11 id)]
                          (observe :join-membership-probe (if raw (alength ^bytes raw) 0))
                          (when raw (collect! id))))
                      ;; Only absent/excluded ordering keys need this tie sort.
                      (.sort virtual
                             (fn [^RankedVirtualMember a ^RankedVirtualMember b]
                               (Arrays/compareUnsigned (.-member a) (.-member b)))))
                    ;; Identity order supplies default ties directly, even for desc.
                    (let [prefix (keyed-index-prefix membership-base membership-d :identity :asc)
                          offset (alength ^bytes prefix)]
                      ((:reduce tx) {:prefix prefix :from (ranked-after prefix)}
                       (fn [_ {:keys [k v]}]
                         (observe :join-membership-entry (alength ^bytes v))
                         (collect! (keyed-entry-id membership-d :identity :asc k offset v)) nil) nil)))
                  (let [result (ranked-filtered-page tx cfg candidates page limit observe virtual
                                                     direction (keyed-value-length d index))]
                    (-> result (dissoc :payloads)
                        (assoc :keys (mapv keyed-page-key (:payloads result))
                               :membership-keys-examined @examined
                               :defaulted-keys (.size virtual) :sort-keys-read 0))))))))))))

(defn- keyed-validate-selection! [name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix} (keys options))
                 (or (not (contains? options :prefix))
                     (and (vector? (:prefix options)) (not (contains? options :equals)))))
    (throw (ex-info "Result selection accepts :index, :direction and either :equals or a vector :prefix"
                    {:options options})))
  (let [index (:index options :identity) direction (:direction options :asc)]
    (when-not (and (#{:asc :desc} direction)
                   (or (= :identity index) (contains? (:indexes d) index)))
      (throw (ex-info "Unknown result index or direction" {:model name :options options})))
    nil))

(defn- keyed-selection-prefix
  "Encoded selection value or tuple prefix (ascending bytes in both directions)."
  [_d options]
  (cond
    (contains? options :equals) (key-encode (:equals options))
    (contains? options :prefix) (key-tuple-prefix (:prefix options))))

(defn- keyed-selection [b name d options]
  (keyed-validate-selection! name d options)
  (let [index (:index options :identity)
        prefix (keyed-selection-prefix d options)]
    (cond-> (keyed-index-prefix b d index :asc)
      prefix (key-join prefix))))

(defn- keyed-reduce-results [ctx name d tx b read options rf initial]
  (let [prefix (keyed-selection b name d options)
        index (:index options :identity)
        ;; Members are read from the ascending structure in both directions
        ;; (OPT-5/OPT-7); ids decode from the member (OPT-9 W8).
        offset (alength ^bytes (keyed-index-prefix b d index :asc))
        step (fn [acc id]
               (keyed-observe! ctx :index-entry 0)
               (let [entry (keyed-decode ctx :result-decoded (read 2 id))]
                 (rf acc (select-keys entry [:key :value]))))]
    (cond
      (not (keyed-fits-prefix? prefix)) initial
      (= :desc (:direction options :asc))
      ;; prefix is the ascending level-zero prefix plus the selection bytes;
      ;; with no selection, start after the level-zero sentinel.
      (let [cfg (keyed-index-config b d index :asc)
            selected? (some? (keyed-selection-prefix d options))]
        (ranked-desc-reduce tx cfg (keyed-value-length d index)
                            (if selected? prefix (ranked-after prefix))
                            (when selected? (key-prefix-end prefix))
                            256 (fn [acc ^objects item]
                                  (step acc (keyed-entry-id d index :asc (aget item 0) 0 (aget item 1))))
                            initial))
      :else
      ((:reduce tx) {:prefix prefix :from (ranked-after prefix)}
       (fn [acc {:keys [k v]}] (step acc (keyed-entry-id d index :asc k offset v))) initial))))

(defn keyed-values [ctx name d options]
  ;; No resource is opened until reduction. A session-bound reducible must be
  ;; consumed during its session; storage operations enforce that lifetime.
  (keyed-validate-selection! name d options)
  (reify clojure.lang.IReduceInit
    (reduce [_ rf initial]
      (keyed-with-reader ctx name d
        (fn [tx b d read] (keyed-reduce-results ctx name d tx b read options rf initial))))))

(defn- keyed-page-prefixes [name d options]
  (let [selection-options (select-keys options [:index :direction :equals :prefix])]
    (keyed-validate-selection! name d selection-options)
    (if (contains? options :prefixes)
      (do
        (when-not (and (vector? (:prefixes options))
                       (every? vector? (:prefixes options))
                       (not-any? #(contains? options %) [:equals :prefix]))
          (throw (ex-info "Page prefixes must be vectors and cannot be combined with :equals or :prefix"
                          {:options options})))
        (mapv #(keyed-selection-prefix d (assoc selection-options :prefix %)) (distinct (:prefixes options))))
      [(keyed-selection-prefix d selection-options)])))

(defn- keyed-page-ranges [tx cfg prefixes total]
  ;; Rank intervals are already in index order, including descending indexes.
  ;; Merging overlapping intervals makes duplicate/nested prefixes a true union.
  ;; Each interval also carries packed level-zero key bounds [lo, hi) (hi nil
  ;; = end of level zero) for descending reads from the ascending structure;
  ;; intervals that merge are adjacent in rank, so the union of their key
  ;; ranges holds no other member.
  (let [p0 (ranked-prefix cfg 0)
        key-min (fn [^bytes a ^bytes b] (if (neg? (Arrays/compareUnsigned a b)) a b))
        key-max (fn [^bytes a ^bytes b] (if (or (nil? a) (nil? b)) nil (if (neg? (Arrays/compareUnsigned a b)) b a)))]
    (reduce (fn [ranges [start end lo hi :as interval]]
              (if-let [[previous-start previous-end previous-lo previous-hi] (peek ranges)]
                (if (<= start previous-end)
                  (conj (pop ranges) [previous-start (max end previous-end)
                                      (key-min lo previous-lo) (key-max hi previous-hi)])
                  (conj ranges interval))
                [interval]))
            []
            (sort-by first
                     (keep (fn [prefix]
                             (when (or (nil? prefix) (keyed-fits-prefix? cfg prefix))
                               (let [start (if prefix (ranked-rank tx cfg prefix false) 0)
                                     prefix-end (when prefix (key-prefix-end prefix))
                                     end (if prefix
                                           (if prefix-end
                                             (ranked-rank tx cfg prefix-end false)
                                             (ranked-size tx cfg))
                                           total)]
                                 (when (< start end)
                                   [start end
                                    (if prefix (key-join p0 prefix) (ranked-after p0))
                                    (when prefix-end (key-join p0 prefix-end))])))) prefixes)))))

(defn- keyed-read-desc-page-ranges
  "Descending page from ascending rank intervals: intervals from the top, each
   read by ranked-desc-items (runs of equal value re-reversed)."
  [ctx tx navigation-tx cfg vlen read ranges offset limit id-of]
  (loop [ranges (seq (reverse ranges)) offset offset entries []]
    (if-let [[start end lo hi] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [items (ranked-desc-items (ranked-range-navigator navigation-tx cfg lo hi start end)
                                         vlen offset (min (- size offset) (- limit (count entries))))
                entries (reduce (fn [entries ^objects item]
                                  (keyed-observe! ctx :index-entry 0)
                                  (let [id (id-of (aget item 0) (aget item 1))
                                        entry (keyed-decode ctx :result-decoded (read 2 id))]
                                    (conj entries (select-keys entry [:key :value]))))
                                entries items)]
            (if (= limit (count entries)) entries
              (recur (next ranges) 0 entries)))))
      entries)))

(defn- keyed-read-page-ranges [ctx tx navigation-tx cfg read ranges offset limit id-of]
  (loop [ranges (seq ranges) offset offset entries []]
    (if-let [[start end] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [stop (+ (count entries) (min (- size offset) (- limit (count entries))))
                member (ranked-get-nth navigation-tx cfg (+ start offset))
                entries ((:reduce tx) {:prefix (ranked-prefix cfg 0) :from (ranked-pack cfg 0 member)}
                         (fn [entries {:keys [k v]}]
                           (keyed-observe! ctx :index-entry 0)
                           (let [id (id-of k v)
                                 entry (keyed-decode ctx :result-decoded (read 2 id))
                                 entries (conj entries (select-keys entry [:key :value]))]
                             (if (= stop (count entries)) (reduced entries) entries))) entries)]
            (if (= limit (count entries)) entries
              (recur (next ranges) 0 entries)))))
      entries)))

(defn keyed-page [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix :prefixes :page :limit} (keys options))
                 (pos-int? (:page options 1)) (pos-int? (:limit options 25)))
    (throw (ex-info "Invalid keyed-fold page" {:options options})))
  (let [prefixes (keyed-page-prefixes name d options)]
    (keyed-with-reader ctx name d
      (fn [tx b d read]
        (let [index (:index options :identity)
              direction (:direction options :asc)
              cfg (keyed-read-config tx b d (keyed-index-config b d index :asc) index)
              rank-examined (volatile! 0)
              navigation-tx (assoc tx :reduce
                                   (fn [bounds rf initial]
                                     ((:reduce tx) bounds
                                      (fn [acc entry]
                                        (keyed-observe! ctx :rank-entry 0)
                                        (vswap! rank-examined inc)
                                        (rf acc entry)) initial)))
              ranges (keyed-page-ranges navigation-tx cfg prefixes (or (keyed-read-meta tx (keyed-count-key b)) 0))
              total (reduce (fn [total [start end]] (+ total (- end start))) 0 ranges)
              limit (:limit options 25)
              pages (max 1 (quot (+ total (dec limit)) limit))
              page (min (:page options 1) pages)
              member-offset (alength ^bytes (ranked-prefix cfg 0))
              entries (if (= :desc direction)
                        (keyed-read-desc-page-ranges ctx tx navigation-tx cfg (keyed-value-length d index)
                                                     read ranges (* (dec page) limit) limit
                                                     #(keyed-entry-id d index :asc %1 0 %2))
                        (keyed-read-page-ranges ctx tx navigation-tx cfg read ranges (* (dec page) limit) limit
                                                #(keyed-entry-id d index :asc %1 member-offset %2)))]
          {:entries entries :total total :total-pages pages :page page :page-size limit
           :rows-examined (count entries) :rank-entries-examined @rank-examined
           :results-decoded (count entries)
           :progress (keyed-public-progress (keyed-read-meta tx (keyed-progress-key b)))})))))

(defn- keyed-navigation-reader [ctx tx]
  (assoc tx :reduce
         (fn [bounds rf initial]
           ((:reduce tx) bounds
            (fn [acc entry] (keyed-observe! ctx :rank-entry 0) (rf acc entry)) initial))))

(defn- keyed-group-bounds [tx cfg d index direction value]
  (let [prefix (keyed-index-sort-bytes d index value direction)
        first (ranked-rank tx cfg prefix false)
        end (if-let [end (key-prefix-end prefix)]
              (ranked-rank tx cfg end false) (ranked-size tx cfg))]
    [first end]))

(defn- keyed-identity-count
  "OPT-9 W6: members of the :identity index whose value (str id) equals
   value; what its stored group :total was."
  [tx b d value]
  (let [p (key-join (keyed-index-prefix b d :identity :asc) (keyed-index-sort-bytes d :identity value :asc))]
    (if (keyed-fits-prefix? p) ((:reduce tx) {:prefix p} (fn [n _] (inc n)) 0) 0)))

(defn- keyed-identity-groups
  "OPT-9 W6: :identity groups derived from level zero, equal to the
   {:value :total} records KF9 stored: same values, totals and order (member
   values order strings as group keys do, and members of one value are
   contiguous)."
  [ctx tx b d values]
  (let [group-prefix (ranked-prefix (keyed-index-config b d :identity :asc) 6)]
    (if (some? values)
      (keyed-reduce-distinct (fn [groups value]
                               (keyed-observe! ctx :index-group 0)
                               ;; Same oversized-key failure as a stored group lookup.
                               (key-checked (key-join group-prefix (key-encode value)))
                               (let [n (keyed-identity-count tx b d value)]
                                 (if (pos? n) (conj groups {:value value :total n}) groups))) [] values)
      (let [prefix (keyed-index-prefix b d :identity :asc)
            offset (alength ^bytes prefix)
            close (fn [groups ^objects open]
                    (if (aget open 0)
                      (do (keyed-observe! ctx :index-group 0)
                          (conj groups {:value (aget open 0) :total (aget open 1)}))
                      groups))
            open (object-array 2)]
        (close ((:reduce tx) {:prefix prefix :from (ranked-after prefix)}
                (fn [groups {:keys [^bytes k]}]
                  (let [value (member-read! k (doto (long-array 2) (aset 0 (long offset)) (aset 1 (alength k))) 0 true)]
                    (if (= value (aget open 0))
                      (do (aset open 1 (num (inc (long (aget open 1))))) groups)
                      (let [groups (close groups open)]
                        (aset open 0 value) (aset open 1 (num 1))
                        groups)))) [])
               open)))))

(defn keyed-index-groups
  ([ctx name d index] (keyed-index-groups ctx name d index nil))
  ([ctx name d index values]
   (keyed-validate-selection! name d {:index index})
   (keyed-with-reader ctx name d
     (fn [tx b _ _read]
       (let [prefix (ranked-prefix (keyed-index-config b d index :asc) 6)]
         (cond
           (= :identity index) (keyed-identity-groups ctx tx b d values)
           (some? values)
           (keyed-reduce-distinct (fn [groups value]
                              (keyed-observe! ctx :index-group 0)
                              (if-let [group (keyed-read-meta tx (key-checked (key-join prefix (key-encode value))))]
                                (conj groups group) groups)) [] values)
           :else
           ((:reduce tx) {:prefix prefix}
            (fn [groups {:keys [v]}]
              (keyed-observe! ctx :index-group 0)
              (conj groups (codec/decode v))) [])))))))

(defn- keyed-validate-group! [name d selection]
  (keyed-validate-selection! name d selection)
  (when-not (contains? selection :equals)
    (throw (ex-info "Index composition requires an equality group" {:model name :selection selection}))))

(defn keyed-index-keys [ctx name d options]
  (let [selection (select-keys options [:index :direction :equals])]
    (when-not (and (map? options)
                   (every? #{:index :direction :equals :offset :limit} (keys options))
                   (nat-int? (:offset options 0)) (pos-int? (:limit options 25)))
      (throw (ex-info "Invalid index identity slice" {:options options})))
    (keyed-validate-group! name d selection)
    (keyed-with-reader ctx name d
      (fn [tx b _ _read]
        ;; One equal-value group is in the same (ascending id) order in both
        ;; directions, so it is always read from the ascending structure.
        (let [cfg (keyed-read-config tx b d (keyed-index-config b d (:index options :identity) :asc)
                                     (:index options :identity))
              offset (:offset options 0) limit (:limit options 25)
              prefix (key-join (ranked-prefix cfg 0)
                               (keyed-index-sort-bytes d (:index options :identity) (:equals options) :asc))
              start (when (keyed-fits-prefix? prefix)
                      (if (zero? offset)
                      prefix
                      (let [navigation (keyed-navigation-reader ctx tx)
                            [first end] (keyed-group-bounds navigation cfg d (:index options :identity) :asc (:equals options))]
                        (when (< offset (- end first))
                          (ranked-pack cfg 0 (ranked-get-nth navigation cfg (+ first offset)))))))]
          (if-not start []
              (let [index (:index options :identity) direction :asc
                    member-offset (alength ^bytes (ranked-prefix cfg 0))]
              ((:reduce tx) {:prefix prefix :from start}
               (fn [ids {:keys [k v]}]
                 (keyed-observe! ctx :index-entry 0)
                 (let [ids (conj ids (keyed-entry-id d index direction k member-offset v))]
                   (if (= limit (count ids)) (reduced ids) ids))) []))))))))

(defn keyed-index-rank [ctx name d selection id]
  (keyed-validate-group! name d selection)
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (keyed-with-reader ctx name d
    (fn [raw-tx b _ _read]
      ;; Ranks within one equal-value group are direction independent
      ;; (OPT-5/OPT-7: read from the ascending structure).
      (let [tx (keyed-navigation-reader ctx raw-tx) direction :asc
            index (:index selection :identity) value (:equals selection)
            cfg (keyed-read-config tx b d (keyed-index-config b d index direction) index)]
        (if (and (keyed-compact-identity? d index) (not= value (str id)))
          ;; OPT-9 W6: the :identity group's total is counted from
          ;; level zero (no stored group); only needed when id sorts after it.
          (let [group-key (key-join (ranked-prefix (keyed-index-config b d index :asc) 6) (key-encode value))]
            {:rank (if (and (<= (alength ^bytes group-key) 511) (string? value) (pos? (compare (str id) value)))
                     (keyed-identity-count raw-tx b d value) 0)
             :present? false})
          (let [member (keyed-index-member d index direction value id)
                first (ranked-rank tx cfg (keyed-index-sort-bytes d index value direction) false)]
            {:rank (- (ranked-rank tx cfg member false) first)
             :present? (ranked-contains? tx cfg member)}))))))

;; ---------------------------------------------------------------------------
;; Declarative read queries
;; ---------------------------------------------------------------------------

(defn query-fail [message data]
  (throw (ex-info message (assoc data :type :grain.read-query/invalid))))

(defn- query-unsupported! [message data hint]
  (throw (ex-info (str message ". " hint)
                  (assoc data :type :grain.read-query/unsupported :hint hint))))

(defn query-normalize [value]
  (cond (nil? value) ""
        (string? value) (.toLowerCase ^String value Locale/ROOT)
        (keyword? value) (name value)
        :else value))

(defn query-order-key [fields]
  (when-not (and (vector? fields) (seq fields) (every? keyword? fields))
    (query-fail "Order fields must be a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result]
               (let [values (mapv #(query-normalize (get result %)) fields)]
                 (if (= 1 (count values)) (first values) values)))
    {:ai.obney.grain.read-model-processor-v2.core.read-query/order fields}))

(defn query-search-text [fields]
  (when-not (and (vector? fields) (seq fields) (every? keyword? fields))
    (query-fail "Search text requires a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result] (str/join " " (map #(get result %) fields)))
    {:ai.obney.grain.read-model-processor-v2.core.read-query/text {:fields fields}}))

(defn query-field-ref [expression]
  (when (qualified-keyword? expression)
    [(keyword (namespace expression)) (keyword (name expression))]))

(defn- query-parse-definition [definition registry]
  (when-not (and (map? definition) (every? #{:from :related :search :orders} (keys definition))
                 (map? (:from definition)) (= 1 (count (:from definition))))
    (query-fail "Read query requires one root and only from/related/search/orders" {:definition definition}))
  (let [[root model] (first (:from definition))
        related (:related definition {})
        aliases (set (cons root (keys related)))
        orders (:orders definition)
        search (:search definition [])]
    (when-not (and (map? related) (every? #(and (simple-keyword? %) (not= :key %)) aliases)
                   (not (contains? related root)) (map? orders) (seq orders)
                   (every? simple-keyword? (keys orders)) (vector? search))
      (query-fail "Invalid read-query bindings or orders" {:definition definition}))
    (letfn [(references [expr]
              (if-let [[alias _] (query-field-ref expr)]
                (do (when-not (aliases alias) (query-fail "Unknown result alias" {:expression expr})) #{alias})
                (if (vector? expr)
                  (case (first expr)
                    (:id :value) (do (when-not (and (= 2 (count expr)) (aliases (second expr)))
                                        (query-fail "Unknown result binding" {:expression expr})) #{(second expr)})
                    :param (do (when-not (and (= 2 (count expr)) (keyword? (second expr)))
                                 (query-fail "Invalid parameter reference" {:expression expr})) #{})
                    :call (do (when-not (and (<= 2 (count expr)) (fn? (second expr)))
                                (query-fail "Calculation requires a function" {:expression expr}))
                              (into #{} (mapcat references) (drop 2 expr)))
                    (query-fail "Unknown read-query expression" {:expression expr}))
                  (query-fail "Expected a field reference or expression" {:expression expr}))))]
      (doseq [[alias relation] related]
        (when-not (and (map? relation) (contains? relation :key)
                       (every? #{:model :key :missing :validate} (keys relation))
                       (or (nil? (:validate relation)) (fn? (:validate relation)))
                       (let [missing (:missing relation :error)]
                         (or (= :error missing)
                             (and (vector? missing) (= 2 (count missing)) (= :default (first missing))))))
          (query-fail "Invalid related-result declaration" {:alias alias :relation relation}))
        (when-not (or (query-field-ref (:key relation))
                      (and (vector? (:key relation)) (= :id (first (:key relation)))))
          (query-fail "Relationship keys must reference an instance ID or field" {:alias alias}))
        (references (:key relation)))
      (doseq [field search]
        (when-not (query-field-ref field) (query-fail "Search requires result-field references" {:field field}))
        (references field))
      (doseq [[order expressions] orders]
        (when-not (and (vector? expressions) (seq expressions))
          (query-fail "Ordering requires a nonempty expression vector" {:order order}))
        (doseq [expr expressions] (references expr)))
      (let [bindings (assoc related root {:model model})
            models (into {} (map (fn [[alias {:keys [model]}]]
                                   (let [d (get registry model)]
                                    (when-not (or (:key d) (:route d)) (query-fail "Expected registered keyed model" {:alias alias :model model}))
                                     [model d]))) bindings)
            sorted (loop [done [root] remaining related]
                     (if (empty? remaining) done
                         (let [ready (filterv #(every? (set done) (references (:key (val %)))) remaining)]
                           (when (empty? ready) (query-fail "Cyclic related-result definitions" {:aliases (keys remaining)}))
                           (recur (into done (map key) ready) (apply dissoc remaining (map key ready))))))]
        {:definition definition :root root :model model :bindings bindings
         :aliases sorted :models models :orders orders :search search}))))

(defn query-subscriptions [plan]
  (into {} (map (fn [[model d]] [model (:version d 1)])) (:models plan)))

(defn query-resolve-value [api ctx {:keys [bindings]} alias id ^HashMap shared]
  (let [{:keys [model missing validate] :or {missing :error}} (bindings alias)
        address [alias id]]
    (if (.containsKey shared address) (.get shared address)
        (let [absent (Object.)
              found (if (nil? id) absent ((:value api) ctx model id absent))
              value (if (identical? absent found)
                      (if (= :error missing)
                        (do (when validate (validate id nil))
                            (throw (ex-info "Required related result is missing"
                                            {:type :grain.read-query/missing :alias alias :model model :key id})))
                        (second missing)) found)]
          (when validate (validate id value))
          (.put shared address value)
          value))))

(defn query-row-reader [api ctx plan params ^HashMap shared id & [seed]]
  (let [local (HashMap.) root (:root plan)]
    (letfn [(binding-key [alias]
              (if (= root alias) id (evaluate (get-in plan [:bindings alias :key]))))
            (result [alias]
              (if (.containsKey local alias) (.get local alias)
                  (let [relation (get-in plan [:bindings alias])
                        value (if (contains? seed alias)
                                (let [value (get seed alias)]
                                  (when-let [validate (:validate relation)]
                                    (validate (binding-key alias) value))
                                  value)
                                ;; Share only non-identity relationships (catalog etc.).
                                (let [cache (if (or (= alias root)
                                                    (and (vector? (:key relation)) (= :id (first (:key relation)))))
                                              (HashMap.) shared)]
                                  (query-resolve-value api ctx plan alias (binding-key alias) cache)))]
                    (.put local alias value) value)))
            (evaluate [expr]
              (if-let [[alias field] (query-field-ref expr)] (get (result alias) field)
                      (case (first expr)
                        :id (binding-key (second expr))
                        :value (result (second expr))
                        :param (if (contains? params (second expr)) (get params (second expr))
                                   (query-fail "Missing calculation parameter" {:parameter (second expr)}))
                        :call (apply (second expr) (map evaluate (drop 2 expr))))))]
      {:evaluate evaluate
       :row (fn [] (into {:key id} (map (fn [alias] [alias (result alias)])) (:aliases plan)))})))

(defn- query-order-index [plan alias fields]
  (some (fn [[index extract]] (when (= fields (:ai.obney.grain.read-model-processor-v2.core.read-query/order (meta extract))) index))
        (get-in plan [:models (get-in plan [:bindings alias :model]) :indexes])))

(defn- query-equality-index [plan alias field]
  ;; A keyword extractor means exact field equality, with no normalization.
  (some (fn [[index extract]] (when (= field extract) index))
        (get-in plan [:models (get-in plan [:bindings alias :model]) :indexes])))

(defn- query-same-key? [plan alias]
  (or (= alias (:root plan)) (= [:id (:root plan)] (get-in plan [:bindings alias :key]))))

(defn- query-group-source [plan alias]
  (when-let [[parent field] (query-field-ref (get-in plan [:bindings alias :key]))]
    (when (query-same-key? plan parent)
      (when-let [index (query-equality-index plan parent field)]
        {:parent parent :model (get-in plan [:bindings parent :model]) :index index}))))

(defn- query-observe [ctx event]
  (when-let [f (:read-model/observer ctx)] (f (assoc event :bytes 0))))

(defn- query-groups [api ctx plan alias shared]
  (when-let [source (query-group-source plan alias)]
    (let [entries ((:index-groups api) ctx (:model source) (:index source))]
      (doseq [{:keys [value]} entries] (query-resolve-value api ctx plan alias value shared))
      (query-observe ctx {:operation :related-groups :count (count entries)})
      (assoc source :entries entries))))

(defn- query-union-start [groups offset]
  (loop [bounds (mapv #(assoc % :lo 0 :hi (:total %)) groups)]
    (let [{:keys [lo hi slice]} (apply max-key #(- (:hi %) (:lo %)) bounds)]
      (when-not (< lo hi) (query-fail "Group rank search lost its target" {:offset offset}))
      (let [pivot (first (slice (+ lo (quot (- hi lo) 2)) 1))
            ranks (mapv #((:rank %) pivot) bounds)
            before (reduce + (map :rank ranks))]
        (cond (= before offset) pivot
              (< before offset) (recur (mapv (fn [g r] (assoc g :lo (+ (:rank r) (if (:present? r) 1 0)))) bounds ranks))
              :else (recur (mapv (fn [g r] (assoc g :hi (:rank r))) bounds ranks)))))))

(defn- query-union-page [groups offset limit]
  (if (= 1 (count groups)) ((:slice (first groups)) offset limit)
      (let [pivot (query-union-start groups offset)
            heap (PriorityQueue. 11 (reify Comparator (compare [_ a b] (compare (str (:id a)) (str (:id b))))))
            offer! (fn [group offset]
                     (when (< offset (:total group))
                       (.add heap {:id (first ((:slice group) offset 1)) :group group :offset offset})))]
        (doseq [g groups] (offer! g (:rank ((:rank g) pivot))))
        (loop [out []]
          (if (or (= limit (count out)) (.isEmpty heap)) out
              (let [{:keys [id group offset]} (.poll heap)]
                (when (< (inc (count out)) limit) (offer! group (inc offset)))
                (recur (conj out id))))))))

(defn query-page-info [total {:keys [page limit]}]
  (let [pages (max 1 (quot (+ total (dec limit)) limit))]
    {:total total :total-pages pages :page (min page pages) :page-size limit}))

(defn- query-grouped-page [api ctx plan alias field shared options]
  (when-let [{:keys [entries model index]} (query-groups api ctx plan alias shared)]
    (when ((:same-members? api) ctx (:model plan) model)
      (let [buckets (->> entries
                         (group-by #(query-normalize (get (query-resolve-value api ctx plan alias (:value %) shared) field)))
                         (sort-by key (if (= :desc (:direction options)) #(compare %2 %1) compare)))
            info (query-page-info (reduce + 0 (map :total entries)) options)
            make-group (fn [{:keys [value total]}]
                         (let [selection {:index index :equals value}]
                           {:total total
                            :slice (fn [offset limit] ((:index-keys api) ctx model (assoc selection :offset offset :limit limit)))
                            :rank (fn [id] ((:index-rank api) ctx model selection id))}))
            ids (loop [buckets (seq buckets) offset (* (dec (:page info)) (:limit options))
                       remaining (:limit options) out []]
                  (if (or (nil? buckets) (zero? remaining)) out
                      (let [members (val (first buckets)) total (reduce + (map :total members))]
                        (if (>= offset total) (recur (next buckets) (- offset total) remaining out)
                            (let [n (min remaining (- total offset))]
                              (recur (next buckets) 0 (- remaining n)
                                     (into out (query-union-page (mapv make-group members) offset n))))))))]
        (assoc info :keys ids :strategy :related-groups :related-groups (count entries))))))

(defn- query-text-segment [plan fields]
  (let [alias (first (query-field-ref (first fields)))
        names (mapv (comp second query-field-ref) fields)
        model (get-in plan [:bindings alias :model])
        segment (some (fn [[index extract]]
                        (when (= {:fields names} (:ai.obney.grain.read-model-processor-v2.core.read-query/text (meta extract)))
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
      {:index-model model :suggested-extractor field
       :hint (str "Declare an exact equality index on " model ", for example "
                  (pr-str {:indexes {:relation field}})
                  ". Use the raw keyword extractor to preserve IDs. Bump :version when adding the index.")}
      {:hint (str "Relationship " alias " uses " (pr-str expression)
                  ". Indexed related search and field ordering require a field lookup from the root "
                  (:root plan) " or a binding with the same instance ID. Change that relationship or exclude its fields.")})))

(defn- query-search-segments [plan]
  (let [parts (mapv vec (partition-by (comp first query-field-ref) (:search plan)))
        aliases (mapv (comp first query-field-ref first) parts)]
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
                    "Place the root's search fields first; related-only search is not supported."))
    (let [segments (mapv #(query-text-segment plan %) parts)
          related (second segments)
          alias (:alias related)
          missing (get-in plan [:bindings alias :missing])]
      (when (and related (vector? missing) (some? (second missing)))
        (query-unsupported! (str "Search relationship " alias " on model " (:model related)
                           " has a non-nil missing default " (pr-str missing))
                      {:path [:related alias :missing] :alias alias :model (:model related)
                       :fields (second parts) :missing missing}
                      "Default text is absent from the stored search index. Remove these related fields from :search, or use :error or [:default nil] if the domain permits."))
      (when (and related (nil? (query-group-source plan alias)))
        (let [{:keys [hint] :as guidance} (query-equality-guidance plan alias)]
          (query-unsupported! (str "Search relationship " alias " on model " (:model related)
                             " requires indexed equality membership")
                        (merge guidance {:path [:related alias :key] :alias alias :model (:model related)
                                         :fields (second parts) :expression (get-in plan [:bindings alias :key])})
                        hint)))
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
                     {:index-model model :suggested-extractor extractor
                      :hint (str "Declare an entry under :indexes on " model " using " (pr-str extractor)
                                 ". A raw keyword or arbitrary function does not supply order-field metadata. Bump :version when changing stored extraction.")})
                   (and alias (= 1 (count fields))) (query-equality-guidance plan alias)
                   :else {:hint "Use fields from one supported indexed binding, or an explicit :call calculation. A calculation can evaluate every candidate."})]
    (query-unsupported! (str "Unsupported field ordering " order " for " (pr-str expressions)
                       (when model (str " on model " model)))
                  (merge guidance {:path [:orders order] :order order :alias alias :model model
                                   :fields expressions :expressions expressions :reason reason})
                  (:hint guidance))))

(defn query-compile-definition [definition registry]
  (let [plan (query-parse-definition definition registry)
        segments (when (seq (:search plan)) (query-search-segments plan))]
    (doseq [[order expressions] (:orders plan) :when (not (query-computed-order? expressions))]
      (let [refs (mapv query-field-ref expressions)
            alias (ffirst refs)
            fields (mapv second refs)]
        (when-not (and (every? some? refs) (every? #(= alias (first %)) refs)
                       (or (and (query-same-key? plan alias) (query-order-index plan alias fields))
                           (and (= 1 (count fields)) (query-group-source plan alias))))
          (query-unsupported-order plan order expressions :missing-index-support))))
    (assoc plan :search-segments segments)))

(defn- query-matching-keys [api ctx plan text shared]
  (let [[root related] (:search-segments plan)]
    (when-not root
      (throw (ex-info "This read query does not declare search fields" {:type :grain.read-query/unsupported})))
    (let [source (when related (query-groups api ctx plan (:alias related) shared))
          totals (into {} (map (juxt :value :total)) (:entries source))
          members (memoize (fn [group]
                             (if-let [total (get totals group)]
                               ((:index-keys api) ctx (:model source)
                                                  {:index (:index source) :equals group :limit total}) [])))
          search (fn [segment text mode]
                   ((:search-keys api) ctx (:model segment)
                                       {:index (:index segment) :query text :mode mode}))
          join (fn [keys] (into #{} (mapcat members) keys))
          found (HashSet. ^java.util.Collection (search root text :contains))]
      (when related
        (.addAll found ^java.util.Collection (join (search related text :contains)))
        (let [^ints points (.toArray (.codePoints ^String text))]
          (doseq [split (range 1 (alength points)) :when (= 32 (aget points (dec split)))
                  :let [left (String. points 0 (int (dec split)))
                        right (String. points (int split) (int (- (alength points) split)))
                        related-hits (join (search related right :prefix))]
                  :when (seq related-hits)
                  id (search root left :suffix) :when (contains? related-hits id)]
            (.add found id))))
      found)))

(defn- query-scan-keys [api ctx plan]
  (into [] (map :key) ((:values api) ctx (:model plan))))

(defn- query-computed-page [api ctx plan expressions keys shared options]
  ;; ALLIUM-OPEN: computed ordering still requires evaluating all candidates.
  ;; Keep the cost explicit; declaring a function does not make it indexable.
  (let [keys (or keys (query-scan-keys api ctx plan))
        value (fn [id _]
                (let [evaluate (:evaluate (query-row-reader api ctx plan (:params options) shared id))]
                  (mapv #(query-normalize (evaluate %)) expressions)))]
    (assoc ((:key-page api) ctx (:model plan) keys
                            (assoc (select-keys options [:page :limit :direction]) :index :identity :sort-value value))
           :strategy :computed :candidates-examined (count keys))))

(defn- query-ordered-page [api ctx plan keys shared options]
  (let [expressions (get (:orders plan) (:order options))
        refs (mapv query-field-ref expressions)
        aliases (set (map first refs))
        alias (when (and (every? some? refs) (= 1 (count aliases))) (first aliases))
        fields (mapv second refs)
        model (get-in plan [:bindings alias :model])
        index (when alias (query-order-index plan alias fields))
        ranked? (contains? (get-in plan [:models model :filtered-indexes]) index)
        selection (assoc (select-keys options [:page :limit :direction]) :index index)
        root? (= alias (:root plan))
        same? (and index (query-same-key? plan alias)
                   (or root? ((:same-members? api) ctx (:model plan) model)))]
    (cond
      (and index same?)
      (if keys
        (assoc ((if ranked? (:filtered-key-page api) (:key-page api)) ctx model keys selection)
               :strategy (if ranked? :ranked-matches :indexed-matches))
        (let [page ((:page api) ctx model selection)]
          (assoc (dissoc page :entries) :keys (mapv :key (:entries page)) :strategy :indexed
                 :seeds (into {} (map (fn [{:keys [key value]}] [key {alias value}])) (:entries page)))))

      (and (nil? keys) alias (= 1 (count fields)) (query-group-source plan alias))
      (or (query-grouped-page api ctx plan alias (first fields) shared options)
          (query-unsupported-order plan (:order options) expressions :different-membership))

      (and keys alias (= 1 (count fields)) (query-group-source plan alias))
      (let [{:keys [model index]} (query-group-source plan alias)]
        (if ((:same-members? api) ctx (:model plan) model)
          (assoc ((:key-page api) ctx model keys
                                  (assoc (select-keys options [:page :limit :direction]) :index index
                                         :sort-value (fn [_ related-id]
                                                       (query-normalize (get (query-resolve-value api ctx plan alias related-id shared)
                                                                       (first fields))))))
                 :strategy :related-matches)
          (query-unsupported-order plan (:order options) expressions :different-membership)))

      (and index ranked? (query-same-key? plan alias)
           (vector? (get-in plan [:bindings alias :missing]))
           (= :default (first (get-in plan [:bindings alias :missing]))))
      (let [extract (get-in plan [:models model :indexes index])
            default (second (get-in plan [:bindings alias :missing]))]
        (assoc ((:joined-page api) ctx (:model plan) model
                                   (cond-> (assoc selection :missing-value (extract default)) keys (assoc :keys keys)))
               :strategy :joined))

      (query-computed-order? expressions) (query-computed-page api ctx plan expressions keys shared options)

      :else (query-unsupported-order plan (:order options) expressions :different-membership))))

(defn query-execute [api ctx plan request]
  (when-not (and (map? request) (every? #{:params :search :order :direction :page :limit} (keys request)))
    (query-fail "Unknown read-query request option" {:request request}))
  (let [options (merge {:params {} :search "" :direction :asc :page 1 :limit 25} request)
        _ (when-not (and (map? (:params options)) (string? (:search options))
                         (contains? (:orders plan) (:order options)) (#{:asc :desc} (:direction options))
                         (pos-int? (:page options)) (pos-int? (:limit options)))
            (query-fail "Invalid read-query page, ordering or parameters" {:request request}))
        text (query-normalize (str/trim (:search options)))
        options (assoc options :search text)
        run (fn [prepared]
              (let [shared (HashMap.)
                    keys (when (seq text) (query-matching-keys api prepared plan text shared))
                    selected (query-ordered-page api prepared plan keys shared options)
                    rows (mapv (fn [id] ((:row (query-row-reader api prepared plan (:params options) shared id
                                                           (get-in selected [:seeds id]))))) (:keys selected))]
                (query-observe prepared {:operation :read-query-plan :strategy (:strategy selected)
                                   :candidates-examined (:candidates-examined selected 0)})
                (assoc (select-keys selected [:total :total-pages :page :page-size])
                       :rows rows :work (dissoc selected :keys :seeds :total :total-pages :page :page-size))))]
    (if (and (seq text) (< (.codePointCount ^String text 0 (count text)) 3))
      {:rows [] :total 0 :total-pages 1 :page 1 :page-size (:limit options)
       :search-error "Enter at least 3 characters to search." :work {:strategy :unsupported-short-search}}
      ((:read-session api) ctx (keys (:models plan)) run))))
