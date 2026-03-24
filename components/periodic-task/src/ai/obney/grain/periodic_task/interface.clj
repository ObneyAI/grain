(ns ai.obney.grain.periodic-task.interface
  (:require [ai.obney.grain.periodic-task.core :as core]))

(defn start [config]
  (core/start config))

(defn stop [periodic-task]
  (core/stop periodic-task))

;; Periodic trigger registry
(def periodic-trigger-registry* core/periodic-trigger-registry*)

(defn register-periodic-trigger!
  [trigger-name handler-fn opts]
  (core/register-periodic-trigger! trigger-name handler-fn opts))

(defn start-periodic-triggers!
  [event-store-fns]
  (core/start-periodic-triggers! event-store-fns))

(defn stop-periodic-triggers!
  [triggers]
  (core/stop-periodic-triggers! triggers))

(defmacro defperiodic
  "Define and register a periodic trigger.

   On each schedule tick, the body is called for every tenant with
   (tenant-id, time). It should return {:result/events [...] :result/cas {...}}.
   The framework appends the events with the CAS predicate.

       (defperiodic :ns-kw name
         {:schedule {:cron \"0 0 * * *\"}}
         \"Optional docstring.\"
         [tenant-id time]
         {:result/events [(es/->event {...})]
          :result/cas {...}})

   Use a separate defprocessor to handle the trigger events."
  {:arglists '([ns-kw name opts? docstring? [tenant-id time] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        trigger-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-periodic-trigger! ~trigger-name (var ~var-name) ~opts)
       (var ~var-name))))
