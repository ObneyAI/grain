(ns ai.obney.grain.control-plane.read-models
  (:require [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]
            [ai.obney.grain.control-plane.schemas]))

(defreadmodel :grain.control active-nodes
  {:events #{:grain.control/node-heartbeat :grain.control/node-departed}
   :version 1
   :l1-ttl-ms 0}
  [state event]
  (case (:event/type event)
    :grain.control/node-heartbeat
    (assoc state (:node/id event)
           {:last-heartbeat-at (.toEpochMilli (.toInstant (:event/timestamp event)))
            :last-heartbeat-id (:event/id event)
            :metadata (:node/metadata event)})

    :grain.control/node-departed
    (dissoc state (:node/id event))

    state))

(defreadmodel :grain.control lease-ownership
  {:events #{:grain.control/lease-acquired :grain.control/lease-released}
   :version 2
   :l1-ttl-ms 0}
  [state event]
  (let [tid (:lease/tenant-id event)]
    (case (:event/type event)
      :grain.control/lease-acquired
      (assoc state tid (:lease/node-id event))

      :grain.control/lease-released
      (dissoc state tid)

      state)))
