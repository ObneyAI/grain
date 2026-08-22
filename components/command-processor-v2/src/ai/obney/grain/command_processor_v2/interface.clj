(ns ai.obney.grain.command-processor-v2.interface
  (:require [ai.obney.grain.command-processor-v2.core :as core]))

(def command-registry* (atom {}))

(defn ^:no-doc register-declared! [command-name handler-fn opts definition]
  (swap! command-registry*
         (fn [registry]
           (when-let [existing (get registry command-name)]
             (when (and definition (:definition/value existing)
                        (not= (:definition/value definition) (:definition/value existing)))
               (throw (ex-info "Conflicting command definition"
                               {:command/name command-name :existing existing
                                :candidate definition}))))
           (assoc registry command-name
                  (merge {:handler-fn handler-fn} opts definition)))))

(defn register-command!
  [command-name handler-fn opts]
  (swap! command-registry* assoc command-name (merge {:handler-fn handler-fn} opts)))

(defn global-command-registry
  []
  @command-registry*)

(defmacro defcommand
  {:arglists '([ns-kw name opts? docstring? [context] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        command-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))
        definition (when docstring
                     {:definition/description docstring
                      :definition/source {:ns (str *ns*) :file *file*
                                          :line (:line (meta &form))}
                      :definition/value
                      {:description docstring
                       :options (select-keys opts #{:grain.event-model/reads
                                                    :grain.event-model/produces})}})]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-declared! ~command-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))

(defn process-command
  [context]
  (core/process-command
    (if (:command-registry context)
      context
      (assoc context :command-registry @command-registry*))))
