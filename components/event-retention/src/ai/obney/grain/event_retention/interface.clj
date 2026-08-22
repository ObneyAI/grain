(ns ai.obney.grain.event-retention.interface
  (:require [ai.obney.grain.event-retention.core :as core]))

(defn administration
  "Create the operator/worker capability. Keep this value out of ordinary
   application contexts that only need the EventStore interface."
  ([event-store] (administration event-store {}))
  ([event-store options]
   (if (map? options)
     (assoc options :event-store event-store)
     ;; Backward-compatible shorthand for the original second argument.
     {:event-store event-store :consumer-assessment options})))

(def assess core/assess)
(def activate! core/activate!)
(def deactivate! core/deactivate!)
(def active-activation core/active-activation)
(def estimate core/estimate)
(def compact! core/compact!)
(def status core/status)
(def verify-at-boot! core/verify-at-boot!)
