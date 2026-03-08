(ns ai.obney.grain.command-request-handler-v2.interface
  (:require [ai.obney.grain.command-request-handler-v2.core :as core]))

(defn routes
  [config]
  (core/routes config))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn handle-command
  [config command]
  (core/handle-command config command))
