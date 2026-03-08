(ns ai.obney.grain.command-processor-v2.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas schemas
  {::command-id :uuid
   ::command-name :qualified-keyword
   ::command-timestamp :time/offset-date-time
   ::command [:map
              [:command/name ::command-name]
              [:command/id ::command-id]
              [:command/timestamp ::command-timestamp]]})
