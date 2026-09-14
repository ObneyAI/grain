(ns ai.obney.grain.todo-processor-v2.interface
  (:require [ai.obney.grain.todo-processor-v2.core :as core]))

(def processor-registry* core/processor-registry*)

(defn register-processor!
  ([processor-name handler-fn opts]
   (core/register-processor! processor-name handler-fn opts))
  ([processor-name config]
   (core/register-processor! processor-name config)))

(defn ^:no-doc register-declared! [processor-name handler-fn opts definition]
  (swap! processor-registry*
         (fn [registry]
           (when-let [existing (get registry processor-name)]
             (when (and definition (:definition/value existing)
                        (not= (:definition/value definition) (:definition/value existing)))
               (throw (ex-info "Conflicting todo processor definition"
                               {:processor/name processor-name :existing existing
                                :candidate definition}))))
           (assoc registry processor-name
                  (merge {:handler-fn handler-fn} opts definition)))))

(defmacro defprocessor
  "Define and register a todo processor.

   Follows the same pattern as `defcommand`, `defquery`, and `defreadmodel`:

       (defprocessor :ns-kw name
         {:topics #{:ns/event-a :ns/event-b}}  ; event types to subscribe to
         \"Optional docstring.\"
         [context]
         ... handler body ...)

   The handler receives a context map with :event, :event-store, :tenant-id and,
   when run by a lease-fenced coalesced poller, :lease-owned?. The optional
   :lease-owned? value is a zero-argument live ownership check scoped to the
   current tenant and processor; it returns false if ownership cannot be read.
   It must return one of:
     {:result/events [...]}                  — pure result, batch checkpointed
     {:result/effect fn :result/checkpoint :after/:before}  — side effect
     {}                                      — no-op
   The processor is registered under `:<ns-kw>/<name>` and started automatically
   by the control plane when a tenant is assigned to this node."
  {:arglists '([ns-kw name opts? docstring? [context] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        proc-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))
        definition (when docstring
                     {:definition/description docstring
                      :definition/source {:ns (str *ns*) :file *file*
                                          :line (:line (meta &form))}
                      :definition/value
                      {:description docstring
                       :options (select-keys opts #{:topics :grain.event-model/reads
                                                    :grain.event-model/produces})}})]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-declared! ~proc-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))

(defn start
  [config]
  (core/start config))

(defn stop
  [todo-processor]
  (core/stop todo-processor))

(defn start-polling
  [config]
  (core/start-polling config))

(defn stop-polling
  [polling-processor]
  (core/stop-polling polling-processor))

(defn start-tenant-poller
  "Start the coalesced tenant poller.

   The optional two-argument :lease-check-fn remains the ownership source. When
   present, each handler context receives a reserved zero-argument
   :lease-owned? capability bound to that event's tenant and processor. Without
   a lease source the capability is absent, including from caller app context."
  [config]
  (core/start-tenant-poller config))

(defn stop-tenant-poller
  [poller]
  (core/stop-tenant-poller poller))
