(ns ai.obney.grain.control-plane.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas control-plane-schemas
  {:grain.control/node-heartbeat
   [:map
    [:node/id :uuid]
    [:node/metadata {:optional true} [:map]]]

   :grain.control/node-departed
   [:map
    [:node/id :uuid]]

   :grain.control/lease-acquired
   [:map
    [:lease/node-id :uuid]
    [:lease/tenant-id :uuid]
    [:lease/processor-name :keyword]]

   :grain.control/lease-released
   [:map
    [:lease/node-id :uuid]
    [:lease/tenant-id :uuid]
    [:lease/processor-name :keyword]]})
