(ns ai.obney.grain.control-plane.events
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.schemas]))

(def control-plane-tenant-id
  #uuid "00000000-0000-0000-0000-000000000001")

(defn ->heartbeat [node-id metadata]
  (es/->event {:type :grain.control/node-heartbeat
               :tags #{[:node node-id]}
               :body {:node/id node-id
                      :node/metadata metadata}}))

(defn ->node-departed [node-id]
  (es/->event {:type :grain.control/node-departed
               :tags #{[:node node-id]}
               :body {:node/id node-id}}))

(defn ->lease-acquired [node-id tenant-id processor-name]
  (let [lease-key (hash [tenant-id processor-name])]
    (es/->event {:type :grain.control/lease-acquired
                 :tags #{[:lease (java.util.UUID. (long lease-key) 0)]}
                 :body {:lease/node-id node-id
                        :lease/tenant-id tenant-id
                        :lease/processor-name processor-name}})))

(defn ->lease-released [node-id tenant-id processor-name]
  (let [lease-key (hash [tenant-id processor-name])]
    (es/->event {:type :grain.control/lease-released
                 :tags #{[:lease (java.util.UUID. (long lease-key) 0)]}
                 :body {:lease/node-id node-id
                        :lease/tenant-id tenant-id
                        :lease/processor-name processor-name}})))
