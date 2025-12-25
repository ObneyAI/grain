(ns hooks.dspy
  (:require [clj-kondo.hooks-api :as api]))

(defn- defsignature-hook
  "Hook for defsignature macro.
   Transforms (defsignature Name docstring? {:inputs {...} :outputs {...}})
   into (def Name ...) for linting purposes."
  [{:keys [node]} defined-by]
  (let [[_ name-node & rest-args] (:children node)
        ;; Skip optional docstring to get to the spec map
        spec-node (if (and (first rest-args)
                           (string? (api/sexpr (first rest-args))))
                    (second rest-args)
                    (first rest-args))]
    (when name-node
      {:node (api/list-node
               (list
                 (api/token-node 'def)
                 name-node
                 (or spec-node (api/token-node nil))))
       :defined-by defined-by})))

(defn defsignature [ctx]
  (defsignature-hook ctx 'ai.obney.grain.clj-dspy.interface/defsignature))

(defn defsignature-core [ctx]
  (defsignature-hook ctx 'ai.obney.grain.clj-dspy.core/defsignature))
