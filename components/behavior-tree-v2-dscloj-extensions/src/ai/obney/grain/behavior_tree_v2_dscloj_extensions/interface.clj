(ns ai.obney.grain.behavior-tree-v2-dscloj-extensions.interface
  (:require [ai.obney.grain.behavior-tree-v2-dscloj-extensions.core :as core]))

(defn dscloj
  [{{:keys [_id _signature _operation]} :opts
    :keys [_st-memory]
    :as context}]
  (core/dscloj context))