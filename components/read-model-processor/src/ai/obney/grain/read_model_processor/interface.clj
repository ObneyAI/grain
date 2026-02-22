(ns ai.obney.grain.read-model-processor.interface
  (:require [ai.obney.grain.read-model-processor.core :as core]))

(defn p
  "Project a Read Model.

   The Read Model Processor transparently manages
   incremental snapshotting and caching.

   args keys: :f, :query, :name, :version, :scope (optional).
   When :scope is provided it is hashed into the cache key so that
   differently-scoped projections are cached independently."
  [{:keys [_event-store] :as context}
   {:keys [_f _query _name _version _scope] :as args}]
  (core/p context args))

;; ---------------------------------------------------------------------------
;; Read Model Registry
;; ---------------------------------------------------------------------------

(def read-model-registry* (atom {}))

(defn register-read-model!
  "Registers a read model in the global registry.
   rm-name should be a qualified keyword (e.g., :crm/contacts).
   reducer-fn is [state event] -> state.
   opts may contain :events (set of event type keywords) and :version (int)."
  [rm-name reducer-fn opts]
  (swap! read-model-registry* assoc rm-name (merge {:reducer-fn reducer-fn} opts)))

(defn global-read-model-registry
  "Returns the current global read model registry."
  []
  @read-model-registry*)

(defmacro defreadmodel
  "Defines a read model reducer and registers it.

   Syntax:
     (defreadmodel :ns name opts? docstring? [state event] body...)

   Creates:
   - Function `ns-name` (the per-event reducer, [state event] -> state)
   - Registry entry :ns/name with {:reducer-fn fn, :events #{...}, :version int}

   Example:
     (defreadmodel :crm contacts
       {:events #{:crm/contact-created :crm/contact-field-set}
        :version 1}
       [state event]
       (contacts* state event))"
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
  "Project a read model by registry name. Looks up reducer, events, version from registry.

   Optional scope map scopes both the event query and the cache key:
     :tags    — set of tags merged into the default {:types ...} query
     :queries — full override, vector of query maps passed directly to es/read

   The entire scope map is hashed for cache key derivation."
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
