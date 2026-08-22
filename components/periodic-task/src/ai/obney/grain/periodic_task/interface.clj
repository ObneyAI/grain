(ns ai.obney.grain.periodic-task.interface
  (:require [ai.obney.grain.periodic-task.core :as core]))

(defn start [config]
  (core/start config))

(defn stop [periodic-task]
  (core/stop periodic-task))

;; Periodic trigger registry
(def periodic-trigger-registry* core/periodic-trigger-registry*)

(defn next-fire-at
  "Return the node-local Instant currently armed for a running trigger.
   Returns nil when the trigger is unknown, unstarted, stopped, or firing."
  [trigger-name]
  (core/next-fire-at trigger-name))

(defn register-periodic-trigger!
  [trigger-name handler-fn opts]
  (core/register-periodic-trigger! trigger-name handler-fn opts))

(defn ^:no-doc register-declared! [trigger-name handler-fn opts definition]
  (swap! periodic-trigger-registry*
         (fn [registry]
           (when-let [existing (get registry trigger-name)]
             (when (and definition (:definition/value existing)
                        (not= (:definition/value definition) (:definition/value existing)))
               (throw (ex-info "Conflicting periodic task definition"
                               {:periodic/name trigger-name :existing existing
                                :candidate definition}))))
           (assoc registry trigger-name
                  (merge {:handler-fn handler-fn} opts definition)))))

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
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))
        definition (when docstring
                     {:definition/description docstring
                      :definition/source {:ns (str *ns*) :file *file*
                                          :line (:line (meta &form))}
                      :definition/value
                      {:description docstring
                       :options (select-keys opts #{:schedule
                                                    :grain.event-model/produces})}})]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-declared! ~trigger-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))
