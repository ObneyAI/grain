(ns ai.obney.grain.read-model-processor-v2.interface.testing
  "Cache inspection and eviction for deterministic integration scenarios."
  (:require [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.l1-cache :as l1]))

(defn format-scoped-key
  "Return the cache key bytes for a model name, version, and cache scope."
  [name version scope]
  (core/format-scoped-key name version scope))

(defn l1-entry
  "Return an L1 entry by cache-key string, updating its access timestamp."
  [cache-key]
  (l1/get-entry cache-key))

(defn evict-l1!
  "Evict one L1 entry by cache-key string without clearing other projections."
  [cache-key]
  (l1/invalidate! cache-key))
