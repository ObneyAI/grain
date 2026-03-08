(ns ai.obney.grain.read-model-processor-v2.interface
  (:require [ai.obney.grain.read-model-processor-v2.core :as core]))

(defn p
  [{:keys [_event-store _tenant-id] :as context}
   {:keys [_f _query _name _version _scope] :as args}]
  (core/p context args))

;; ---------------------------------------------------------------------------
;; Read Model Registry
;; ---------------------------------------------------------------------------

(def read-model-registry* (atom {}))

(defn register-read-model!
  [rm-name reducer-fn opts]
  (swap! read-model-registry* assoc rm-name (merge {:reducer-fn reducer-fn} opts)))

(defn global-read-model-registry
  []
  @read-model-registry*)

(defmacro defreadmodel
  {:arglists '([ns-kw name opts? docstring? [state event] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        rm-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-read-model! ~rm-name (var ~var-name) ~opts)
       (var ~var-name))))

(defn project
  ([context rm-name] (project context rm-name nil))
  ([context rm-name scope]
   (let [entry (get @read-model-registry* rm-name)
         query (cond
                 (:queries scope) (:queries scope)
                 (:tags scope)    {:types (:events entry) :tags (:tags scope)}
                 :else            {:types (:events entry)})]
     (p context {:f       (:reducer-fn entry)
                 :query   query
                 :name    (name rm-name)
                 :version (:version entry 1)
                 :scope   scope}))))
