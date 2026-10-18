(ns ai.obney.grain.read-model-processor-v2.interface.testing
  "Cache inspection and eviction for deterministic integration scenarios."
  (:require [ai.obney.grain.read-model-processor-v2.core :as core]))

(defn format-scoped-key
  "Return the cache key bytes for a model name, version, and cache scope."
  [name version scope]
  (core/format-scoped-key name version scope))

(defn l1-entry
  "Return the L1 entry for a cache-key string, updating its access timestamp.
   L1 keeps one entry per event store and cache; this returns the one used most
   recently."
  [cache-key]
  (core/l1-get-entry cache-key))

(defn evict-l1!
  "Evict a cache-key string from L1 for every event store and cache, without
   clearing other projections."
  [cache-key]
  (core/l1-invalidate! cache-key))
