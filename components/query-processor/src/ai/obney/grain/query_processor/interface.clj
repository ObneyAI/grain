(ns ai.obney.grain.query-processor.interface
  (:require [ai.obney.grain.query-processor.core :as core]))

;; Global query registry atom
(def query-registry* (atom {}))

(defn register-query!
  "Registers a query handler in the global registry.
   query-name should be a qualified keyword (e.g., :example/counters)
   handler-fn should be a function that takes context and returns a query result.
   opts is an optional map of additional data (e.g., {:auth :admin-required})."
  [query-name handler-fn opts]
  (swap! query-registry* assoc query-name (merge {:handler-fn handler-fn} opts)))

(defn global-query-registry
  "Returns the current global query registry."
  []
  @query-registry*)

(defmacro defquery
  "Defines a query handler and registers it in the global registry.

   Usage:
     (defquery :example counters
       {:auth :admin-required}  ; optional data map
       \"Optional docstring\"
       [context]
       ...body...)

   Defines a function named `example-counters` and registers it as :example/counters."
  {:arglists '([ns-kw name opts? docstring? [context] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        query-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-query! ~query-name (var ~var-name) ~opts)
       (var ~var-name))))

(defn process-query
  "Processes a query using the registry in context, falling back to global registry."
  [context]
  (core/process-query
    (if (:query-registry context)
      context
      (assoc context :query-registry @query-registry*))))