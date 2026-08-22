(ns ai.obney.grain.control-plane.schemas
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]))

(event-store/defevent :grain.control/node-heartbeat
  "A control-plane node reported that it is alive."
  {:schema [:map
            [:node/id :uuid]
            [:node/metadata {:optional true} [:map]]]
   :history {:retain-at-least "PT1H"
             :keep-latest-per {:tags #{:node}}}})

(event-store/defevent :grain.control/node-departed
  "A control-plane node was declared departed."
  {:schema [:map
            [:node/id :uuid]]})

(event-store/defevent :grain.control/lease-acquired
  "A control-plane node acquired responsibility for a tenant lease."
  {:schema [:map
            [:lease/node-id :uuid]
            [:lease/tenant-id :uuid]]})

(event-store/defevent :grain.control/lease-released
  "A control-plane node released responsibility for a tenant lease."
  {:schema [:map
            [:lease/node-id :uuid]
            [:lease/tenant-id :uuid]]})
