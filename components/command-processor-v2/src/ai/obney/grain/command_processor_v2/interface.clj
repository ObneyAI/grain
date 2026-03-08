(ns ai.obney.grain.command-processor-v2.interface
  (:require [ai.obney.grain.command-processor-v2.core :as core]))

(def command-registry* (atom {}))

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
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-command! ~command-name (var ~var-name) ~opts)
       (var ~var-name))))

(defn process-command
  [context]
  (core/process-command
    (if (:command-registry context)
      context
      (assoc context :command-registry @command-registry*))))
