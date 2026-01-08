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
  "Defines a query handler for the read-side of CQRS.

   Queries read from projections/read-models and return data. They never
   generate events or cause state changes.

   Syntax:
     (defquery :ns name opts? docstring? [context] body...)

   Creates:
   - Function `ns-name` in current namespace
   - Registry entry `:ns/name`

   The context map contains :query (with :query/name, :query/id, :query/timestamp,
   plus any query parameters), :event-store, and application-specific keys.

   Return {:query/result ...} on success, or a Cognitect anomaly on failure.

   Example:
     (defquery :example counters
       {:authorized? (constantly true)}
       \"Returns all counters.\"
       [context]
       {:query/result (read-models/counters context)})

     (defquery :example counter-by-id
       {:authorized? (constantly true)}
       [context]
       (let [id (get-in context [:query :counter-id])]
         (if-let [counter (find-counter context id)]
           {:query/result counter}
           {::anom/category ::anom/not-found})))

   See also: defcommand, defschemas, process-query"
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