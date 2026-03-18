(ns ai.obney.grain.read-model-processor-v2.l1-cache
  "L1 in-process cache for deserialized read model state.

   Holds Clojure maps in JVM heap, keyed by cache-key string.
   LRU eviction keeps memory bounded. TTL-based revalidation
   allows sub-millisecond reads for rapid interactions (pagination,
   filter changes) while maintaining eventual consistency.

   This is the hot tier above LMDB (L2). L2 is the durable cache;
   L1 is the fast path that avoids fressian deserialization.")

(def ^:private default-max-entries 10000)

(def ^:private cache* (atom {}))

(defn get-entry
  "Retrieve an L1 cache entry. Updates :accessed-at for LRU tracking.
   Returns nil on miss."
  [cache-key]
  (when-let [entry (get @cache* cache-key)]
    (swap! cache* assoc-in [cache-key :accessed-at] (System/currentTimeMillis))
    entry))

(defn put-entry!
  "Store an entry in L1. Triggers LRU eviction if over capacity.
   Returns the number of entries evicted (0 if none)."
  [cache-key state watermark max-entries]
  (let [now (System/currentTimeMillis)
        max-entries (or max-entries default-max-entries)]
    (swap! cache* assoc cache-key {:state state
                                    :watermark watermark
                                    :accessed-at now
                                    :validated-at now})
    ;; LRU eviction
    (let [current-count (count @cache*)]
      (if (> current-count max-entries)
        (let [to-evict-count (- current-count max-entries)
              sorted-keys (->> @cache*
                               (sort-by (comp :accessed-at val))
                               (take to-evict-count)
                               (map first))]
          (swap! cache* #(reduce dissoc % sorted-keys))
          to-evict-count)
        0))))

(defn touch-validated!
  "Mark an L1 entry as freshly validated (event store confirmed no new events).
   Resets the TTL window without replacing the cached state."
  [cache-key]
  (swap! cache* assoc-in [cache-key :validated-at] (System/currentTimeMillis)))

(defn update-entry!
  "Update an existing L1 entry with new state and watermark after processing new events."
  [cache-key state watermark]
  (let [now (System/currentTimeMillis)]
    (swap! cache* assoc cache-key {:state state
                                    :watermark watermark
                                    :accessed-at now
                                    :validated-at now})))

(defn invalidate!
  "Remove a specific entry from L1."
  [cache-key]
  (swap! cache* dissoc cache-key))

(defn clear!
  "Remove all entries from L1."
  []
  (reset! cache* {}))

(defn stats
  "Return L1 cache statistics."
  []
  {:entries (count @cache*)})
