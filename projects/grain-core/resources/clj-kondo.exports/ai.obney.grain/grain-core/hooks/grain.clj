(ns hooks.grain
  (:require [clj-kondo.hooks-api :as api]))

(defn- def-handler-macro
  "Common hook logic for defcommand/defquery macros.
   Transforms (defcommand :ns/name opts? docstring? [args] body)
   into (defn ^:clj-kondo/ignore name docstring? [args] body) for linting purposes."
  [{:keys [node]} defined-by]
  (let [args (rest (:children node))
        ;; First arg is the keyword name
        kw-node (first args)
        kw-name (when (api/keyword-node? kw-node)
                  (-> kw-node api/sexpr name symbol))
        args (next args)
        ;; Optional opts map
        ?opts (when (and (first args) (api/map-node? (first args)))
                (first args))
        args (if ?opts (next args) args)
        ;; Optional docstring
        ?docstring (when (and (first args) (string? (api/sexpr (first args))))
                     (first args))
        args (if ?docstring (next args) args)
        ;; Args vector and body
        args-node (first args)
        body (next args)]
    (when kw-name
      (let [new-node (api/list-node
                       (list*
                         (api/token-node 'defn)
                         (api/token-node kw-name)
                         (concat
                           (when ?docstring [?docstring])
                           [args-node]
                           body)))]
        {:node new-node
         :defined-by defined-by}))))

(defn defcommand [ctx]
  (def-handler-macro ctx 'ai.obney.grain.command-processor.interface/defcommand))

(defn defquery [ctx]
  (def-handler-macro ctx 'ai.obney.grain.query-processor.interface/defquery))

(defn defschemas
  "Hook for defschemas macro.
   Transforms (defschemas name schema-map) into (def name schema-map) for linting."
  [{:keys [node]}]
  (let [[_ name-node schema-map-node] (:children node)]
    (when (and name-node schema-map-node)
      {:node (api/list-node
               (list
                 (api/token-node 'def)
                 name-node
                 schema-map-node))
       :defined-by 'ai.obney.grain.schema-util.interface/defschemas})))
