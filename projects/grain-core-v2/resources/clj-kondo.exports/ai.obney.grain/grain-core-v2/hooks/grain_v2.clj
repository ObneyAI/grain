(ns hooks.grain-v2
  (:require [clj-kondo.hooks-api :as api]))

(defn- def-handler-macro
  [{:keys [node]} defined-by]
  (let [args (rest (:children node))
        ns-kw-node (first args)
        ns-name (when (api/keyword-node? ns-kw-node)
                  (name (api/sexpr ns-kw-node)))
        args (next args)
        fn-name-node (first args)
        fn-name (when (api/token-node? fn-name-node)
                  (name (api/sexpr fn-name-node)))
        args (next args)
        var-name (when (and ns-name fn-name)
                   (symbol (str ns-name "-" fn-name)))
        ?opts (when (and (first args) (api/map-node? (first args)))
                (first args))
        args (if ?opts (next args) args)
        ?docstring (when (and (first args) (string? (api/sexpr (first args))))
                     (first args))
        args (if ?docstring (next args) args)
        args-node (first args)
        body (next args)]
    (when var-name
      {:node (api/list-node
               (list*
                 (api/token-node 'defn)
                 (api/token-node var-name)
                 (concat
                   (when ?docstring [?docstring])
                   [args-node]
                   body)))
       :defined-by defined-by})))

(defn defcommand [ctx]
  (def-handler-macro ctx 'ai.obney.grain.command-processor-v2.interface/defcommand))

(defn defquery [ctx]
  (def-handler-macro ctx 'ai.obney.grain.query-processor.interface/defquery))

(defn defreadmodel [ctx]
  (def-handler-macro ctx 'ai.obney.grain.read-model-processor-v2.interface/defreadmodel))

(defn defprocessor [ctx]
  (def-handler-macro ctx 'ai.obney.grain.todo-processor-v2.interface/defprocessor))

(defn defperiodic [ctx]
  (def-handler-macro ctx 'ai.obney.grain.periodic-task.interface/defperiodic))

(defn defschemas
  [{:keys [node]}]
  (let [[_ name-node schema-map-node] (:children node)]
    (when (and name-node schema-map-node)
      {:node (api/list-node
               (list
                 (api/token-node 'def)
                 name-node
                 schema-map-node))
       :defined-by 'ai.obney.grain.schema-util.interface/defschemas})))
