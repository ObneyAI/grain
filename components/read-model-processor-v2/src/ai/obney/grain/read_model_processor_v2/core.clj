(ns ai.obney.grain.read-model-processor-v2.core
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.read-model-processor-v2.l1-cache :as l1]
            [clojure.data.fressian :as fressian]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u])
  (:import [java.io ByteArrayInputStream]))

;; ---------------------------------------------------------------------------
;; Serialization
;; ---------------------------------------------------------------------------

(defn fressian-encode [data]
  (let [^java.nio.ByteBuffer buf (fressian/write data)
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn fressian-decode [bytes]
  (walk/postwalk
   (fn [x]
     (cond
       (instance? java.util.Set x) (set x)
       (and (instance? java.util.List x) (not (vector? x))) (vec x)
       :else x))
   (fressian/read (ByteArrayInputStream. bytes))))

;; ---------------------------------------------------------------------------
;; Cache key helpers
;; ---------------------------------------------------------------------------

(defn format-key
  [n v]
  (.getBytes (format "%s-%s" n v)))

(defn format-scoped-key [n v scope]
  (if scope
    (.getBytes (format "%s-%s-%s" n v (Integer/toHexString (hash scope))))
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
  "Cache key for a single partition: base-key:p<hash(pk)>"
  ^bytes [^bytes base-key partition-key]
  (suffix-key base-key (format ":p%s" (Integer/toHexString (hash partition-key)))))

;; index-cache-key removed — entity index eliminated in favor of
;; partition-fn operating on entity state + transient in-memory lookup

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

(defn- read-cache
  "Read from cache, handling both legacy monolithic and segmented formats."
  [cache base-key]
  (when-let [raw (kv/get! cache {:k base-key})]
    (let [decoded (fressian-decode raw)]
      (if (:segmented decoded)
        ;; Segmented: read all segments and merge
        (let [{:keys [segment-count watermark checksums]} decoded
              state (reduce
                     (fn [acc idx]
                       (if-let [seg-bytes (kv/get! cache {:k (segment-key base-key idx)})]
                         (merge acc (fressian-decode seg-bytes))
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

(defn- write-monolithic!
  "Write state as a single cache entry (legacy format)."
  [cache base-key state watermark]
  (kv/put! cache {:k base-key
                  :v (fressian-encode {:data state :watermark watermark})}))

(defn- write-segmented!
  "Write state as segmented cache entries with manifest."
  [cache base-key state watermark]
  (let [segments (split-into-segments state segment-count)
        entries (into []
                      (mapcat
                       (fn [[idx seg-data]]
                         [{:k (segment-key base-key idx)
                           :v (fressian-encode seg-data)}]))
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
                                  :v (fressian-encode manifest)})]
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
                                     :v (fressian-encode seg-data)}])))
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
                                          :v (fressian-encode manifest)})]
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
   partition-fn operates on entity state.
   Returns {:state partition-state :evictions {pk {eid entity}} :event-count n :new-watermark uuid}"
  [partition-key partition-state events f partition-fn entity-id-fn]
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
               new-entity (get temp-state eid)]
           (if (and new-entity (= partition-key (partition-fn new-entity)))
             ;; Entity belongs here — adopt
             (-> acc
                 (update :state assoc eid new-entity)
                 (update :event-count inc)
                 (assoc :new-watermark (:event/id event)))
             ;; Doesn't concern this partition — skip
             (-> acc
                 (update :event-count inc)
                 (assoc :new-watermark (:event/id event))))))))
   {:state partition-state
    :evictions {}
    :event-count 0}
   events))

(defn- read-partition-manifest
  "Read the partitioned manifest from cache. Returns nil if not found or not partitioned."
  [cache base-key]
  (when-let [raw (kv/get! cache {:k base-key})]
    (let [decoded (fressian-decode raw)]
      (when (:partitioned decoded)
        decoded))))

(defn- read-single-partition
  "Read one partition's cached state."
  [cache base-key partition-key]
  (when-let [raw (kv/get! cache {:k (partition-cache-key base-key partition-key)})]
    (fressian-decode raw)))

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

(defn- write-partitions!
  "Write changed partitions and manifest atomically."
  [cache base-key partitions changed-pks global-watermark all-partition-keys]
  (let [partition-entries (into []
                                (mapcat
                                 (fn [pk]
                                   [{:k (partition-cache-key base-key pk)
                                     :v (fressian-encode {:data (get partitions pk {})
                                                         :watermark global-watermark})}]))
                                changed-pks)
        manifest {:partitioned true
                  :global-watermark global-watermark
                  :partition-keys (set all-partition-keys)}
        manifest-entry {:k base-key
                        :v (fressian-encode manifest)}
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
                               :v (fressian-encode {:data merged :watermark global-watermark})}])))
                        evictions)]
      (kv/put-batch! cache {:entries entries}))))

(def ^:const default-l1-ttl-ms 0)
(def ^:const default-l1-max-entries 10000)

(declare p-partitioned)

(defn- p-partitioned-single
  "Single-partition read with L1 + L2 tiered caching."
  [{:keys [event-store cache] :as context}
   {:keys [f query partition-fn entity-id-fn partition-key name] :as args}
   cache-key manifest]
  (let [l1-key (str (String. ^bytes cache-key) ":pk" (hash partition-key))
        ttl-ms (or (:l1-ttl-ms args) default-l1-ttl-ms)
        max-entries (or (:l1-max-entries args) default-l1-max-entries)]
    (if-let [l1 (l1/get-entry l1-key)]
      ;; ── L1 hit ──
      (if (< (- (System/currentTimeMillis) (:validated-at l1)) ttl-ms)
        ;; Within TTL
        (u/trace ::l1-hit
                 [:metric/name "ReadModelL1Hit" :metric/resolution :high
                  :read-model/cache-tier "l1-hit" :read-model/name name]
                 (:state l1))
        ;; TTL expired — check events
        (let [all-events (into [] (es/read event-store (add-watermark query (:watermark l1))))
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
                     (let [creates-here?
                           (try
                             (let [temp (f {} event)
                                   new-ent (get temp eid)]
                               (and new-ent (= partition-key (partition-fn new-ent))))
                             (catch Exception _ false))]
                       (if creates-here?
                         (-> acc
                             (update :relevant conj event)
                             (update :known-eids conj eid))
                         acc)))))
               {:relevant [] :known-eids #{}}
               all-events)]
          (if (empty? relevant)
            (u/trace ::l1-revalidated
                     [:metric/name "ReadModelL1Revalidated" :metric/resolution :high
                      :read-model/cache-tier "l1-revalidated" :read-model/name name]
                     (do (l1/touch-validated! l1-key) (:state l1)))
            (let [{:keys [state evictions event-count new-watermark]}
                  (process-events-single-partition
                   partition-key (:state l1) relevant f partition-fn entity-id-fn)
                  wm (or new-watermark (:watermark l1))]
              (u/trace ::l1-stale
                       [:metric/name "ReadModelL1Stale" :metric/resolution :high
                        :read-model/cache-tier "l1-stale" :read-model/name name
                        :read-model/events-processed event-count]
                       (do (l1/update-entry! l1-key state wm)
                           (when (and manifest (seq evictions))
                             (write-partitions! cache cache-key
                                               {partition-key state} #{partition-key} wm
                                               (into (:partition-keys manifest)
                                                     (cons partition-key (keys evictions))))
                             (write-evictions! cache cache-key evictions wm))
                           state))))))

      ;; ── L1 miss ──
      (u/trace ::l1-miss
               [:metric/name "ReadModelL1Miss" :metric/resolution :high
                :read-model/cache-tier "l1-miss" :read-model/name name]
        (if (nil? manifest)
          ;; No L2 — full projection first, then read partition
          (do (p-partitioned context (dissoc args :partition-key))
              (let [cp (read-single-partition cache cache-key partition-key)
                    state (or (:data cp) {})
                    wm (:watermark cp)]
                (l1/put-entry! l1-key state wm max-entries)
                state))
          ;; L2 exists — read partition with incremental update
          (let [cp (read-single-partition cache cache-key partition-key)
                watermark (or (:watermark cp) (:global-watermark manifest))
                pstate (or (:data cp) {})
                events (into [] (es/read event-store (add-watermark query watermark)))]
            (if (empty? events)
              (do (l1/put-entry! l1-key pstate watermark max-entries) pstate)
              (let [{:keys [state evictions event-count new-watermark]}
                    (process-events-single-partition
                     partition-key pstate events f partition-fn entity-id-fn)
                    wm (or new-watermark watermark)]
                (when (seq evictions)
                  (write-partitions! cache cache-key
                                    {partition-key state} #{partition-key} wm
                                    (into (:partition-keys manifest)
                                          (cons partition-key (keys evictions))))
                  (write-evictions! cache cache-key evictions wm))
                (when (and (empty? evictions) (>= event-count 10))
                  (write-partitions! cache cache-key
                                    {partition-key state} #{partition-key} wm
                                    (into (:partition-keys manifest) [partition-key])))
                (l1/put-entry! l1-key state wm max-entries)
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
      (write-partitions! cache cache-key partitions
                        (if (nil? manifest) (set (keys partitions)) changed-pks)
                        (or new-watermark watermark)
                        (keys partitions)))
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
    (if-let [l1 (l1/get-entry l1-key)]
      (let [now (System/currentTimeMillis)]
        (if (< (- now (:validated-at l1)) ttl-ms)
          ;; L1 hit within TTL — no I/O at all
          (u/trace ::l1-hit
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
              (u/trace ::l1-revalidated
                       [:metric/name "ReadModelL1Revalidated" :metric/resolution :high
                        :read-model/cache-tier "l1-revalidated"]
                       (do (l1/touch-validated! l1-key)
                           (:state l1)))

              ;; New events applied — update L1 + L2
              (u/trace ::l1-stale
                       [:metric/name "ReadModelL1Stale" :metric/resolution :high
                        :read-model/cache-tier "l1-stale"
                        :read-model/events-processed event-count]
                       (do (l1/update-entry! l1-key state wm)
                           (when (>= event-count 10)
                             (write-cache! cache cache-key state wm))
                           state))))))

      ;; ── L1 miss — fall through to L2 ──
      (u/trace ::l1-miss
               [:metric/name "ReadModelL1Miss" :metric/resolution :high
                :read-model/cache-tier "l1-miss"]
               (if-let [cached (read-cache cache cache-key)]
                 ;; L2 hit
                 (u/trace ::l2-hit
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
                            (l1/put-entry! l1-key state wm max-entries)
                            state))

                 ;; L2 miss — full projection
                 (u/trace ::l2-miss
                          [:metric/name "ReadModelL2Miss" :metric/resolution :high
                           :read-model/cache-tier "l2-miss"]
                          (let [events (es/read event-store query)
                                {:keys [state _event-count new-watermark]} (process-events {} events f)]
                            (write-cache! cache cache-key state new-watermark)
                            ;; Populate L1
                            (l1/put-entry! l1-key state new-watermark max-entries)
                            state)))))))

(defn p
  [{:keys [event-store cache tenant-id] :as context}
   {:keys [f query name version scope partition-fn entity-id-fn] :as args}]
  (if (and partition-fn entity-id-fn)
    (p-partitioned context args)
    (p-unpartitioned context args)))
