(ns ai.obney.grain.read-model-processor-v2.core
  "Read-model materialization, transactional indexes and declarative queries."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u]
            [malli.core :as m])
  (:import [java.lang.ref WeakReference]
           [java.io ByteArrayOutputStream DataOutputStream EOFException]
           [java.nio ByteBuffer ByteOrder BufferUnderflowException]
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

(defn- key-write-physical-string! [^DataOutputStream out ^String value]
  (dotimes [i (.length value)]
    (let [c (int (.charAt value i))
          hi (bit-shift-right c 8)
          lo (bit-and 255 c)]
      (.writeByte out hi) (when (zero? hi) (.writeByte out 255))
      (.writeByte out lo) (when (zero? lo) (.writeByte out 255)))))

(defn- key-write-compact-string! [^DataOutputStream out ^String value]
  (dotimes [i (.length value)]
    (let [c (int (.charAt value i))]
      (cond
        (zero? c) (do (.writeByte out 0) (.writeByte out 255))
        (< c 128) (.writeByte out c)
        :else (do (.writeByte out 128) (.writeShort out c))))))

(defn- key-write-key! [^DataOutputStream out value write-string!]
  (cond
    (nil? value) (.writeByte out 1)
    (boolean? value) (.writeByte out (if value 3 2))
    (string? value)
    (do (.writeByte out 16)
        (write-string! out value)
        (.writeShort out 0))
    (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
    (do (.writeByte out 32) (.writeLong out (bit-xor (long value) Long/MIN_VALUE)))
    (uuid? value)
    (do (.writeByte out 48)
        (.writeLong out (.getMostSignificantBits ^UUID value))
        (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value)
    (do (.writeByte out 56) (key-write-key! out (str value) write-string!))
    (vector? value)
    (do (.writeByte out 64) (doseq [v value] (key-write-key! out v write-string!)) (.writeByte out 0))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn- key-encode-with ^bytes [write-string! value]
  (let [out (ByteArrayOutputStream.)]
    (key-write-key! (DataOutputStream. out) value write-string!)
    (.toByteArray out)))

(defn key-encode ^bytes [value]
  (key-encode-with key-write-physical-string! value))

(defn key-encode-compact ^bytes [value]
  (key-encode-with key-write-compact-string! value))

(defn- key-tuple-prefix-with ^bytes [encode components]
  (when-not (vector? components)
    (throw (ex-info "Tuple prefix must be a vector" {:prefix components})))
  (let [encoded (encode components)]
    (java.util.Arrays/copyOf encoded (dec (alength encoded)))))

(defn key-tuple-prefix
  "Encode complete leading vector components, omitting only the outer terminator."
  ^bytes [components]
  (key-tuple-prefix-with key-encode components))

(defn key-compact-tuple-prefix ^bytes [components]
  (key-tuple-prefix-with key-encode-compact components))

(defn- key-read-key! [^ByteBuffer in]
  (case (bit-and 255 (.get in))
    1 nil 2 false 3 true
    16 (let [out (StringBuilder.)]
         (loop []
           (let [b (bit-and 255 (.get in))]
             (cond
               (zero? b)
               (case (bit-and 255 (.get in))
                 0 (.toString out)
                 255 (do (.append out (char 0)) (recur))
                 (throw (ex-info "Invalid ordered string escape" {})))
               (< b 128) (do (.append out (char b)) (recur))
               (= b 128) (let [c (bit-and 65535 (.getShort in))]
                           (when (< c 128) (throw (ex-info "Noncanonical ordered character" {})))
                           (.append out (char c)) (recur))
               :else (throw (ex-info "Invalid ordered string byte" {}))))))
    32 (bit-xor (.getLong in) Long/MIN_VALUE)
    48 (UUID. (.getLong in) (.getLong in))
    56 (keyword (subs (key-read-key! in) 1))
    64 (loop [out []]
         (.mark in)
         (if (zero? (.get in)) out
           (do (.reset in) (recur (conj out (key-read-key! in))))))
    (throw (ex-info "Invalid ordered key type" {}))))

(defn key-decode-compact
  "Decode one compact ordered value, optionally within a metadata field."
  ([^bytes value] (key-decode-compact value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (try
     (key-read-key! (ByteBuffer/wrap value offset length))
     (catch BufferUnderflowException _ (throw (EOFException.))))))

(defn key-descending ^bytes [^bytes key]
  (byte-array (map #(unchecked-byte (bit-xor 255 (bit-and 255 %))) key)))

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
  (.digest (MessageDigest/getInstance "SHA-256") (key-encode identity)))

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
(defn ranked-random-hash [_] (.nextInt (ThreadLocalRandom/current)))
(defn ranked-config
  ([prefix] (ranked-config prefix ranked-random-hash 6))
  ([prefix hash-fn levels]
   (when-not (<= 2 levels 8)
     (throw (IllegalArgumentException. "Ranked index requires 2 to 8 levels")))
   {:prefix prefix :hash-fn hash-fn :levels levels}))
(defn ranked-prefix ^bytes [cfg level]
  (let [^bytes p (:prefix cfg) n (alength p) a (Arrays/copyOf p (inc n))]
    (aset-byte a n (byte level)) a))
(defn ranked-pack ^bytes [cfg level ^bytes key]
  (let [^bytes p (ranked-prefix cfg level) n (alength p) a (Arrays/copyOf p (+ n (alength key)))]
    (System/arraycopy key 0 a n (alength key)) (key-checked a)))
(defn ranked-unpack ^bytes [cfg ^bytes key]
  (Arrays/copyOfRange key (inc (alength ^bytes (:prefix cfg))) (alength key)))
(defn ranked-after ^bytes [^bytes k] (Arrays/copyOf k (inc (alength k))))
(defn ranked-encode-count ^bytes [n]
  (.array (doto (ByteBuffer/allocate 8) (.order ByteOrder/LITTLE_ENDIAN) (.putLong (long n)))))
(defn ranked-decode-count [^bytes v]
  (when v (.getLong (doto (ByteBuffer/wrap v) (.order ByteOrder/LITTLE_ENDIAN)))))
(defn ranked-check-key! [^bytes key]
  (when (zero? (alength key)) (throw (IllegalArgumentException. "Empty key not allowed"))))
(defn- ranked-entry-count [cfg level ^bytes k v]
  (if (and (zero? level) (> (alength k) (inc (alength ^bytes (:prefix cfg)))))
    1 (ranked-decode-count v)))
(defn ranked-count-key [tx cfg level key]
  (let [k (ranked-pack cfg level key)]
    (when-let [v ((:get tx) k)] (ranked-entry-count cfg level k v))))
(defn ranked-set-count! [tx cfg level key n] ((:put tx) (ranked-pack cfg level key) (ranked-encode-count n)))
(defn ranked-increment! [tx cfg level key delta]
  ;; LMDB permits one writer. All reads/updates use that same write transaction.
  (ranked-set-count! tx cfg level key (+ (or (ranked-count-key tx cfg level key) 0) delta)))

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
  (or ((:reduce tx) {:prefix (ranked-prefix cfg level)
                    :from (ranked-pack cfg level ranked-empty-key)
                    :to ((if or-equal? ranked-after identity) (ranked-pack cfg level key)) :reverse? true}
       (fn [_ {:keys [k]}] (reduced (ranked-unpack cfg k))) nil)
      (throw (ex-info "no key found on level" {:level level}))))

(defn ranked-count-range [tx cfg level begin end]
  ;; Upstream countRange; bounds are [begin,end).
  ((:reduce tx) (cond-> {:prefix (ranked-prefix cfg level)}
                 begin (assoc :from (ranked-pack cfg level begin))
                 end (assoc :to (ranked-pack cfg level end)))
   (fn [n {:keys [k v]}] (+ n (ranked-entry-count cfg level k v))) 0))

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
    (let [hash ((:hash-fn cfg) key)]
      (if (ranked-contains? tx cfg key) false
        (do
          ;; Same lowest-to-highest order as upstream's split dependencies.
          (dotimes [level (:levels cfg)]
            (cond
              (zero? level)
              (do ((:put tx) (ranked-pack cfg 0 key) value)
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
          true)))))

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
            (when count ((:delete tx) (ranked-pack cfg level key)))
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

(defn ranked-filtered-page
  "Experimental span navigation. Candidate ordinals belong to this model.
   Returns selected opaque level-zero payloads, never full model results."
  ([tx cfg candidates requested-page limit observe]
   (ranked-filtered-page tx cfg candidates requested-page limit observe []))
  ([tx cfg ^RoaringBitmap candidates requested-page limit observe ^java.util.List virtual]
  (let [counts (HashMap.) visits (volatile! 0) leaves (volatile! 0)
        remaining (volatile! 0) out (java.util.ArrayList.)
        top (dec (:levels cfg))]
    (letfn [(matches [level member]
              (if (and (zero? level) (zero? (alength ^bytes member))) 0
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
            (visit [level {:keys [k v]} end]
              (let [member (ranked-unpack cfg k) real (matches level member)
                    begin-virtual (ranked-virtual-bound virtual member)
                    end-virtual (ranked-virtual-bound virtual end)
                    n (+ real (- end-virtual begin-virtual))]
                (if (<= n @remaining)
                  (vswap! remaining - n)
                  (if (zero? level)
                    (do
                      ;; The real leaf starts this span; virtual members follow it.
                      (when (pos? real)
                        (if (pos? @remaining) (vswap! remaining dec) (.add out v)))
                      (let [start (+ begin-virtual @remaining)
                            stop (min end-virtual (+ start (- limit (.size out))))]
                        (doseq [i (range start stop)]
                          (.add out (.-payload ^RankedVirtualMember (.get virtual i))))
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
      (let [real-total (if (.isEmpty candidates) 0
                    ((:reduce tx) {:prefix (ranked-prefix cfg top)}
                     (fn [n {:keys [k]}]
                       (vswap! visits inc)
                       (+ n (matches top (ranked-unpack cfg k)))) 0))
            total (+ real-total (.size virtual))
            pages (max 1 (quot (+ total (dec limit)) limit))
            page (min requested-page pages)]
        (vreset! remaining (* (dec page) limit))
        (when (pos? total)
          (if (zero? real-total)
            (doseq [i (range @remaining (min total (+ @remaining limit)))]
              (.add out (.-payload ^RankedVirtualMember (.get virtual i))))
            (walk top ranked-empty-key nil)))
        {:payloads (vec out) :total total :total-pages pages :page page :page-size limit
         :rank-entries-examined @visits :membership-leaves-examined @leaves})))))

(defn ranked-get-nth [tx cfg ordinal]
  ;; Upstream NthLookup: at each level subtract spans until a span contains
  ;; the target, then descend from its key. No recursion per member.
  (when-not (neg? ordinal)
    (loop [level (dec (:levels cfg)) key ranked-empty-key remaining ordinal]
      (when (>= level 0)
        (let [found
              ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level key)}
               (fn [{:keys [remaining]} {:keys [k v]}]
                 (let [key (ranked-unpack cfg k) n (ranked-entry-count cfg level k v)]
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
      (let [found ((:reduce tx) {:prefix (ranked-prefix cfg level) :from (ranked-pack cfg level rank-key)
                               :to (ranked-after (ranked-pack cfg level key))}
                   (fn [{:keys [ordinal]} {:keys [k v]}]
                     (let [n (ranked-entry-count cfg level k v)] {:rank-key (ranked-unpack cfg k) :last-count n :ordinal (+ ordinal n)}))
                   {:rank-key rank-key :last-count 0 :ordinal ordinal})
            next-rank (- (:ordinal found) (:last-count found))]
        (cond
          (Arrays/equals ^bytes (:rank-key found) ^bytes key) next-rank
          (zero? level) (+ next-rank (if (and (not null-if-missing?) (pos? (:last-count found))) 1 0))
          :else (recur (dec level) (:rank-key found) next-rank))))))

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
  (let [k (text-search-term-key cfg 1 term)
        old (if-let [raw ((:get tx) k)] (sort-data-int-at raw 0) 0)
        next (+ old delta)]
    (when (neg? next) (throw (ex-info "Negative text posting count" {})))
    (if (zero? next) ((:delete tx) k) ((:put tx) k (text-search-number-bytes next)))))

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
(defn- keyed-index-config [b index direction]
  (ranked-config (key-join b (byte-array [5]) (key-encode index)
                           (byte-array [(if (= direction :desc) 1 0)]))))
(defn- keyed-index-prefix [b index direction]
  (ranked-prefix (keyed-index-config b index direction) 0))
(defn- keyed-membership-config [b d index direction]
  (assoc (keyed-index-config b index direction) :membership? (contains? (:filtered-indexes d) index)))
(defn- keyed-sort-bytes [value direction]
  (cond-> (key-encode value) (= direction :desc) key-descending))
(defn- keyed-compact-identity? [d index]
  (and (= :identity index) (not (contains? (:filtered-indexes d) :identity))))
(defn- keyed-index-sort-bytes [d index value direction]
  (if (keyed-compact-identity? d index)
    (cond-> (key-encode-compact value) (= direction :desc) key-descending)
    (keyed-sort-bytes value direction)))
(defn- keyed-index-member [d index direction value id]
  (if (keyed-compact-identity? d index)
    (key-join (keyed-index-sort-bytes d index value direction) (key-encode id))
    (key-join (keyed-sort-bytes value direction) (key-encode (str id)) (key-encode id))))
(defn- keyed-fits-prefix?
  "A selection prefix must leave space for a member suffix within LMDB's key limit."
  ([^bytes prefix] (< (alength prefix) 511))
  ([cfg ^bytes prefix]
   (< (+ (alength ^bytes (ranked-prefix cfg 0)) (alength prefix)) 511)))

(defn- keyed-update-group! [tx b index value delta]
  ;; Levels 0..5 are the RankedSet. Level 6 stores one count per sort value,
  ;; shared by both directions; no duplicated root membership collection.
  (let [k (key-checked (key-join (ranked-prefix (keyed-index-config b index :asc) 6) (key-encode value)))
        before (some-> ((:get tx) k) codec/decode :total)
        total (+ (or before 0) delta)]
    (when (neg? total) (throw (ex-info "Negative index group count" {:index index :value value})))
    (if (zero? total) ((:delete tx) k)
      ((:put tx) k (codec/encode {:value value :total total})))))

(defn- keyed-remove-index! [tx b d index direction position id]
  (when (ranked-remove! tx (keyed-membership-config b d index direction) (keyed-index-member d index direction position id))
    (when (= direction :asc) (keyed-update-group! tx b index position -1))))

(defn- keyed-add-index! [ctx tx b d index direction position id]
  (let [cfg (keyed-membership-config b d index direction)
        member (keyed-index-member d index direction position id)
        payload (codec/encode id)]
    (ranked-ensure-init! tx cfg)
    (when (ranked-add! tx cfg member payload
                       (when (:membership? cfg) (membership-ensure-ordinal! tx b id)))
      (when (= direction :asc) (keyed-update-group! tx b index position 1)))
    (keyed-observe! ctx :index-written (alength ^bytes payload))))

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

(defn- keyed-apply-batch! [ctx name d b validate-state! validate-result! events]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [tx (membership-transaction tx)
            identity-base (:ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix ctx)
            before-progress (keyed-read-meta tx (keyed-progress-key b))
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
            added (volatile! 0)]
        (doseq [[id state] states]
          (let [value ((:result d identity) state)
                _ (validate-result! value)
                result-k (keyed-address b 2 id)
                old (keyed-decode ctx :result-decoded ((:get tx) result-k))
                included? ((:include? d (constantly true)) value)
                indexes (when included?
                          (assoc (into {} (map (fn [[index f]] [index (f value)])) (:indexes d))
                                 :identity (str id)))]
            (keyed-observe! ctx :result-calculated 0)
            ;; State must advance even when its completion value is equal.
            (keyed-write-observed! ctx tx :state-written (keyed-address b 1 id) state)
            (vswap! added + (- (if included? 1 0) (if old 1 0)))
            (when (not= (boolean included?) (boolean old))
              (let [ordinal (membership-ensure-ordinal! tx identity-base id)
                    bits (membership-bitmap tx (keyed-included-key b))]
                (if included? (.add ^RoaringBitmap bits (int ordinal))
                    (.remove ^RoaringBitmap bits (int ordinal)))
                (membership-put! tx (keyed-included-key b) bits)))
            (if-not included?
              (when old
                (text-search-update! tx b (:searches d) id old nil)
                (doseq [[index position] (:indexes old) direction [:asc :desc]]
                  (keyed-remove-index! tx b d index direction position id))
                ((:delete tx) (keyed-address b 11 id))
                ((:delete tx) result-k))
              (when (or (nil? old) (not= (:value old) value))
                (text-search-update! tx b (:searches d) id old {:value value})
                (doseq [[index position] indexes
                        direction [:asc :desc]
                        :when (or (nil? old) (not= position (get-in old [:indexes index])))]
                  (when old
                    (keyed-remove-index! tx b d index direction (get-in old [:indexes index]) id))
                  (keyed-add-index! ctx tx b d index direction position id))
                ((:put tx) (keyed-address b 11 id) (sort-data-encode id (dissoc indexes :identity)))
                (keyed-write-observed! ctx tx :result-written result-k {:key id :value value :indexes indexes})))))
        (membership-flush! tx #(keyed-observe! ctx %1 %2))
        (when (seq events)
          (when-not (zero? @added) (keyed-write! tx (keyed-count-key b) (+ count-before @added)))
          (keyed-write! tx (keyed-progress-key b) (assoc before-progress :watermark (:event/id (peek events)))))))))

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

(defn- keyed-checkpoint! [ctx b through]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [p (keyed-read-meta tx (keyed-progress-key b)) old (:checked-through p)]
        (when (or (nil? old) (pos? (compare through old)))
          (keyed-write! tx (keyed-progress-key b) (assoc p :checked-through through)))))))

(defn- keyed-catch-up! [ctx name d b through identity]
  (let [progress (kv/transact (:cache ctx) :read #(keyed-read-meta % (keyed-progress-key b)))
        after (last (sort (keep #(get progress %) [:checked-through :watermark])))
        batch-size (:read-model/batch-size ctx 128)]
    (when-not (pos-int? batch-size)
      (throw (ex-info "Keyed-fold batch size must be positive" {:batch-size batch-size})))
    (when (and through (or (nil? after) (pos? (compare through after))))
      (let [source (:event-store ctx)
            stable? (es/stable-empty-intervals? source)
            proof-key (when stable? (empty-proof-key source identity d))]
        (when-not (and stable? (empty-proof-covers? source proof-key after through))
          (let [validate-state! (keyed-prepare-validation (:state-schema d) name :state)
                validate-result! (keyed-prepare-validation (:result-schema d) name :result)
                query (keyed-event-query ctx d after)
                accepted? (volatile! false)
                remaining (reduce
                            (fn [batch event]
                              (if (pos? (compare (:event/id event) through)) (reduced batch)
                                (do
                                  (when (and stable? (not @accepted?))
                                    (empty-proof-forget! proof-key))
                                  (vreset! accepted? true)
                                  (let [batch (conj batch event)]
                                    (if (= batch-size (count batch))
                                      (do (keyed-apply-batch! ctx name d b validate-state! validate-result! batch) [])
                                      batch)))))
                            [] (if (seq query) (es/read source query) []))]
            (when (seq remaining)
              (keyed-apply-batch! ctx name d b validate-state! validate-result! remaining))
            ;; Only a completed, ordered read with no accepted source events
            ;; can suppress a checkpoint. Route-empty events are not absence.
            (when-not (and stable? (not @accepted?)
                           (empty-proof-remember! source proof-key after through))
              (keyed-checkpoint! ctx b through))))))))

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
      (when (and (seq names) (not (pos-int? (:read-model/batch-size ctx 128))))
        (throw (ex-info "Keyed-fold batch size must be positive"
                        {:batch-size (:read-model/batch-size ctx 128)}))))
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
            (let [prefixes (namespace-resolve! cache @identity-order)
                  heads (reduce (fn [heads [ctx _ _]]
                                  (let [id (:tenant-id ctx)]
                                    (if (contains? heads id) heads (assoc heads id (keyed-head ctx))))) {} requests)
                  caught-up (volatile! #{})
                  prepared (mapv
                             (fn [[ctx shared-identity model-identities]]
                               (let [identity-prefix (get prefixes shared-identity)]
                                 (doseq [[name identity] model-identities
                                         :when (not (contains? @caught-up identity))]
                                   (keyed-catch-up! (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix identity-prefix)
                                              name (get registry name) (get prefixes identity)
                                              (get heads (:tenant-id ctx)) identity)
                                   (vswap! caught-up conj identity))
                               [ctx (select-keys registry (keys model-identities)) model-identities])) requests)]
              {:prefixes prefixes :prepared prepared}))]
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
  (keyed-with-reader ctx name d (fn [tx b _d _read] (keyed-read-meta tx (keyed-progress-key b)))))

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
          (let [result (ranked-filtered-page tx (keyed-membership-config b d index direction)
                                            candidates page limit #(keyed-observe! ctx %1 %2))]
            (-> result
                (dissoc :payloads)
                (assoc :keys (mapv codec/decode (:payloads result))
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
    (let [missing-prefix (keyed-sort-bytes missing-value direction)]
      (if-not (:ai.obney.grain.read-model-processor-v2.core.keyed/session ctx)
        (keyed-read-session [[ctx [membership-name name]]] {membership-name membership-d name d}
                      (fn [[prepared]] (keyed-joined-page prepared membership-name membership-d name d options)))
        (keyed-with-reader ctx membership-name membership-d
          (fn [tx membership-base _ membership-read]
            (keyed-with-reader ctx name d
              (fn [_ b _ _]
                (let [cfg (keyed-membership-config b d index direction)
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
                                                         (key-join missing-prefix (key-encode (str id)) (key-encode id))
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
                    (let [prefix (keyed-index-prefix membership-base :identity :asc)]
                      ((:reduce tx) {:prefix prefix :from (ranked-after prefix)}
                       (fn [_ {:keys [v]}]
                         (observe :join-membership-entry (alength ^bytes v))
                         (collect! (codec/decode v)) nil) nil)))
                  (let [result (ranked-filtered-page tx cfg candidates page limit observe virtual)]
                    (-> result (dissoc :payloads)
                        (assoc :keys (mapv codec/decode (:payloads result))
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

(defn- keyed-selection-prefix [d options]
  (let [compact? (keyed-compact-identity? d (:index options :identity))
        encoded (cond
                  (contains? options :equals) ((if compact? key-encode-compact key-encode) (:equals options))
                  (contains? options :prefix) ((if compact? key-compact-tuple-prefix key-tuple-prefix) (:prefix options)))]
    (cond-> encoded (and encoded (= :desc (:direction options))) key-descending)))

(defn- keyed-selection [b name d options]
  (keyed-validate-selection! name d options)
  (let [prefix (keyed-selection-prefix d options)]
    (cond-> (keyed-index-prefix b (:index options :identity) (:direction options :asc))
      prefix (key-join prefix))))

(defn- keyed-reduce-results [ctx name d tx b read options rf initial]
  (let [prefix (keyed-selection b name d options)]
    (if-not (keyed-fits-prefix? prefix) initial
      ((:reduce tx) {:prefix prefix :from (ranked-after prefix)}
     (fn [acc {:keys [v]}]
       (keyed-observe! ctx :index-entry 0)
       (let [id (codec/decode v)
             entry (keyed-decode ctx :result-decoded (read 2 id))]
         (rf acc (select-keys entry [:key :value])))) initial))))

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
  (reduce (fn [ranges [start end :as interval]]
            (if-let [[previous-start previous-end] (peek ranges)]
              (if (<= start previous-end)
                (conj (pop ranges) [previous-start (max end previous-end)])
                (conj ranges interval))
              [interval]))
          []
          (sort-by first
                   (keep (fn [prefix]
                           (when (or (nil? prefix) (keyed-fits-prefix? cfg prefix))
                             (let [start (if prefix (ranked-rank tx cfg prefix false) 0)
                                 end (if prefix
                                       (if-let [end (key-prefix-end prefix)]
                                         (ranked-rank tx cfg end false)
                                         (ranked-size tx cfg))
                                       total)]
                             (when (< start end) [start end])))) prefixes))))

(defn- keyed-read-page-ranges [ctx tx navigation-tx cfg read ranges offset limit]
  (loop [ranges (seq ranges) offset offset entries []]
    (if-let [[start end] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [stop (+ (count entries) (min (- size offset) (- limit (count entries))))
                member (ranked-get-nth navigation-tx cfg (+ start offset))
                entries ((:reduce tx) {:prefix (ranked-prefix cfg 0) :from (ranked-pack cfg 0 member)}
                         (fn [entries {:keys [v]}]
                           (keyed-observe! ctx :index-entry 0)
                           (let [id (codec/decode v)
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
        (let [cfg (keyed-index-config b (:index options :identity) (:direction options :asc))
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
              entries (keyed-read-page-ranges ctx tx navigation-tx cfg read ranges (* (dec page) limit) limit)]
          {:entries entries :total total :total-pages pages :page page :page-size limit
           :rows-examined (count entries) :rank-entries-examined @rank-examined
           :results-decoded (count entries)
           :progress (keyed-read-meta tx (keyed-progress-key b))})))))

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

(defn keyed-index-groups
  ([ctx name d index] (keyed-index-groups ctx name d index nil))
  ([ctx name d index values]
   (keyed-validate-selection! name d {:index index})
   (keyed-with-reader ctx name d
     (fn [tx b _ _read]
       (let [prefix (ranked-prefix (keyed-index-config b index :asc) 6)]
         (if (some? values)
           (keyed-reduce-distinct (fn [groups value]
                              (keyed-observe! ctx :index-group 0)
                              (if-let [group (keyed-read-meta tx (key-checked (key-join prefix (key-encode value))))]
                                (conj groups group) groups)) [] values)
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
        (let [cfg (keyed-index-config b (:index options :identity) (:direction options :asc))
              offset (:offset options 0) limit (:limit options 25)
              prefix (key-join (ranked-prefix cfg 0)
                               (keyed-index-sort-bytes d (:index options :identity) (:equals options) (:direction options :asc)))
              start (when (keyed-fits-prefix? prefix)
                      (if (zero? offset)
                      prefix
                      (let [navigation (keyed-navigation-reader ctx tx)
                            [first end] (keyed-group-bounds navigation cfg d (:index options :identity) (:direction options :asc) (:equals options))]
                        (when (< offset (- end first))
                          (ranked-pack cfg 0 (ranked-get-nth navigation cfg (+ first offset)))))))]
          (if-not start []
              ((:reduce tx) {:prefix prefix :from start}
               (fn [ids {:keys [v]}]
                 (keyed-observe! ctx :index-entry 0)
                 (let [ids (conj ids (codec/decode v))]
                   (if (= limit (count ids)) (reduced ids) ids))) [])))))))

(defn keyed-index-rank [ctx name d selection id]
  (keyed-validate-group! name d selection)
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (keyed-with-reader ctx name d
    (fn [tx b _ _read]
      (let [tx (keyed-navigation-reader ctx tx) direction (:direction selection :asc)
            index (:index selection :identity) value (:equals selection)
            cfg (keyed-index-config b index direction)]
        (if (and (keyed-compact-identity? d index) (not= value (str id)))
          (let [group-key (key-join (ranked-prefix (keyed-index-config b index :asc) 6) (key-encode value))
                group (when (<= (alength ^bytes group-key) 511) (keyed-read-meta tx group-key))]
            {:rank (if (and group (string? value) (pos? (compare (str id) value))) (:total group) 0)
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
