(ns ai.obney.grain.control-plane.schemas
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.event-store-v3.interface :as event-store]))

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

(event-model/defeventmodel :grain.control
  {:description "Grain control-plane infrastructure."
   :events
   {:grain.control/node-heartbeat
    {:description "A control-plane node reported that it is alive."
     :schema [:map [:node/id :uuid] [:node/metadata {:optional true} [:map]]]}
    :grain.control/node-departed
    {:description "A control-plane node was declared departed."
     :schema [:map [:node/id :uuid]]}
    :grain.control/lease-acquired
    {:description "A node acquired a tenant lease."
     :schema [:map [:lease/node-id :uuid] [:lease/tenant-id :uuid]]}
    :grain.control/lease-released
    {:description "A node released a tenant lease."
     :schema [:map [:lease/node-id :uuid] [:lease/tenant-id :uuid]]}}
   :read-models
   {:grain.control/active-nodes
     {:description "Current live control-plane nodes."
     :consumes #{:grain.control/node-heartbeat :grain.control/node-departed}
     :schema [:map-of :uuid
              [:map
               [:last-heartbeat-at :int]
               [:last-heartbeat-id :uuid]
               [:metadata [:maybe [:map]]]]]
     :version 1}
    :grain.control/lease-ownership
    {:description "Current tenant lease ownership."
     :consumes #{:grain.control/lease-acquired :grain.control/lease-released}
     :schema [:map-of :uuid :uuid]
     :version 2}}})
