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
  "Defines a command handler and registers it in the global registry.

   Usage:
     (defcommand :example create-counter
       {:auth :admin-required}  ; optional data map
       \"Optional docstring\"
       [context]
       ...body...)

   Defines a function named `example-create-counter` and registers it as :example/create-counter."
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
