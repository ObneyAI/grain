(ns ai.obney.grain.behavior-tree-v2-dspy-extensions.interface
  (:require [ai.obney.grain.behavior-tree-v2-dspy-extensions.core :as core]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn dspy
  [{{:keys [_id _signature _operation]} :opts
    :keys [_st-memory]
    :as context}]
  (core/dspy context))


