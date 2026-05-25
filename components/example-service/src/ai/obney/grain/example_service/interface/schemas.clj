(ns ai.obney.grain.example-service.interface.schemas
  "The schemas ns in a grain service component defines the schemas for commands, events, queries, etc.
   
   It uses the `defschemas` macro to register the schemas centrally for the rest of
   the system to use. 
   
   Schemas are validated in places such as the command-processor
   and event-store."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

;; The example app is single-tenant. Every event-store append/read, read
;; model projection, processor poll, and periodic trigger is scoped to this
;; fixed tenant id. (Must not be the control-plane tenant id
;; #uuid "00000000-0000-0000-0000-000000000001".)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def example-tenant-id #uuid "11111111-1111-1111-1111-111111111111")

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas commands
  {:example/create-counter
   [:map
    [:name :string]]

   :example/increment-counter
   [:map
    [:counter-id :uuid]]

   :example/decrement-counter
   [:map
    [:counter-id :uuid]]
   
   :example/calculate-average-counter-value
   [:map]})

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas events
  {:example/counter-created
   [:map
    [:counter-id :uuid]
    [:name :string]]

   :example/counter-incremented
   [:map
    [:counter-id :uuid]]

   :example/counter-decremented
   [:map
    [:counter-id :uuid]]
   
   :example/average-calculated
   [:map
    [:value :double]]})

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas queries
  {:example/counters
   [:map]
   :example/counter
   [:map
    [:counter-id :uuid]]})