(ns hooks.grain
  (:require [clj-kondo.hooks-api :as api]))

(defn- def-handler-macro
  "Common hook logic for defcommand/defquery macros.
   Transforms (defcommand :ns name opts? docstring? [args] body)
   into (defn ns-name docstring? [args] body) for linting purposes."
  [{:keys [node]} defined-by]
  (let [args (rest (:children node))
        ;; First arg is the namespace keyword
        ns-kw-node (first args)
        ns-name (when (api/keyword-node? ns-kw-node)
                  (name (api/sexpr ns-kw-node)))
        args (next args)
        ;; Second arg is the function name symbol
        fn-name-node (first args)
        fn-name (when (api/token-node? fn-name-node)
                  (name (api/sexpr fn-name-node)))
        args (next args)
        ;; Build prefixed var name
        var-name (when (and ns-name fn-name)
                   (symbol (str ns-name "-" fn-name)))
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
    (when var-name
      (let [new-node (api/list-node
                       (list*
                         (api/token-node 'defn)
                         (api/token-node var-name)
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

