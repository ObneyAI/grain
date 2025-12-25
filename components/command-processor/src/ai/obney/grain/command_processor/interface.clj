(ns ai.obney.grain.command-processor.interface
  (:require [ai.obney.grain.command-processor.core :as core]))

;; Global command registry atom
(def command-registry* (atom {}))

(defn register-command!
  "Registers a command handler in the global registry.
   command-name should be a qualified keyword (e.g., :example/create-counter)
   handler-fn should be a function that takes context and returns a command result.
   opts is an optional map of additional data (e.g., {:auth :admin-required})."
  [command-name handler-fn opts]
  (swap! command-registry* assoc command-name (merge {:handler-fn handler-fn} opts)))

(defn global-command-registry
  "Returns the current global command registry."
  []
  @command-registry*)

(defmacro defcommand
  "Defines a command handler for the write-side of CQRS.

   Commands are the ONLY path to state change. They validate business rules
   and generate events that are persisted to the event store.

   Syntax:
     (defcommand :ns name opts? docstring? [context] body...)

   Creates:
   - Function `ns-name` in current namespace
   - Registry entry `:ns/name`

   The context map contains :command (with :command/name, :command/id,
   :command/timestamp, plus command parameters), :event-store, and
   application-specific keys.

   Return {:command-result/events [...]} to persist events, optionally with
   {:command/result ...} as confirmation. Return a Cognitect anomaly on failure.

   Example:
     (defcommand :example create-counter
       \"Creates a new counter.\"
       [context]
       (let [id (random-uuid)
             counter-name (get-in context [:command :name])]
         {:command-result/events
          [(->event {:type :example/counter-created
                     :body {:counter-id id :name counter-name}})]
          :command/result {:counter-id id}}))

     (defcommand :example increment-counter
       [context]
       (let [id (get-in context [:command :counter-id])]
         (if (find-counter context id)
           {:command-result/events
            [(->event {:type :example/counter-incremented
                       :tags #{[:counter id]}
                       :body {:counter-id id}})]}
           {::anom/category ::anom/not-found})))

   See also: defquery, defschemas, process-command, ->event"
  {:arglists '([ns-kw name opts? docstring? [context] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        command-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-command! ~command-name (var ~var-name) ~opts)
       (var ~var-name))))

(defn process-command
  "Processes a command using the registry in context, falling back to global registry."
  [context]
  (core/process-command
    (if (:command-registry context)
      context
      (assoc context :command-registry @command-registry*))))
