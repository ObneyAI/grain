(ns hooks.grain-datastar
  (:require [clj-kondo.hooks-api :as api]))

(defn with-signal-scope
  [{:keys [node]}]
  (let [[_ scope-node & body] (:children node)]
    {:node (api/list-node
            (list*
             (api/token-node 'do)
             scope-node
             body))}))

(defn with-signals
  [{:keys [node]}]
  (let [[_ bindings-node & body] (:children node)
        bindings (api/sexpr bindings-node)
        signal-bindings (if (vector? bindings)
                          (mapcat (fn [[sym _opts]]
                                    [(vary-meta (api/token-node sym)
                                                assoc
                                                :clj-kondo/ignore
                                                [:unused-binding])
                                     (api/token-node 'nil)])
                                  (partition 2 bindings))
                          [])]
    {:node (api/list-node
            (list*
             (api/token-node 'let)
             (api/vector-node signal-bindings)
             body))}))

(defn defcomponent
  [{:keys [node]}]
  (let [[_ name-node args-node & body] (:children node)]
    {:node (api/list-node
            (list*
             (api/token-node 'defn)
             name-node
             args-node
             body))
     :defined-by 'ai.obney.grain.datastar.ui/defcomponent}))
