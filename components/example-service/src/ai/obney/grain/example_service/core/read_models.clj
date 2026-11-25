(ns ai.obney.grain.example-service.core.read-models
  "The example service maintains :example/counters as a map keyed by counter ID.
   Its commands and queries access the map through interface.read-models/root."
  (:require [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]))

(defreadmodel :example counters
  {:events #{:example/counter-created
             :example/counter-incremented
             :example/counter-decremented}
   :version 1
   :schema [:map-of :uuid
            [:map
             [:counter/id :uuid]
             [:counter/name :string]
             [:counter/value {:optional true} :int]]]}
  "Projects counters by ID from their lifecycle events."
  [state event]
  (case (:event/type event)
    :example/counter-created
    (assoc state (:counter-id event)
           {:counter/id (:counter-id event)
            :counter/name (:name event)})

    :example/counter-incremented
    (update state (:counter-id event) update :counter/value (fnil inc 0))

    :example/counter-decremented
    (update state (:counter-id event) update :counter/value (fnil dec 0))

    ;; Unrecognized event — leave state unchanged.
    state))
