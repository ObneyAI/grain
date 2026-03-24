(ns ai.obney.grain.todo-processor-v2.interface
  (:require [ai.obney.grain.todo-processor-v2.core :as core]))

(def processor-registry* core/processor-registry*)

(defn register-processor!
  ([processor-name handler-fn opts]
   (core/register-processor! processor-name handler-fn opts))
  ([processor-name config]
   (core/register-processor! processor-name config)))

(defmacro defprocessor
  "Define and register a todo processor.

   Follows the same pattern as `defcommand`, `defquery`, and `defreadmodel`:

       (defprocessor :ns-kw name
         {:topics #{:ns/event-a :ns/event-b}}  ; event types to subscribe to
         \"Optional docstring.\"
         [context]
         ... handler body ...)

   The handler receives a context map with :event, :event-store, :tenant-id.
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
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-processor! ~proc-name (var ~var-name) ~opts)
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
  [config]
  (core/start-tenant-poller config))

(defn stop-tenant-poller
  [poller]
  (core/stop-tenant-poller poller))

