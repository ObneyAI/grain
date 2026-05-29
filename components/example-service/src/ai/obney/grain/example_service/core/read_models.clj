(ns ai.obney.grain.example-service.core.read-models
  "The core read-models namespace in a grain app is where projections are
   created from events. A read model is a pure reducer `(state, event) -> state`
   defined via the `defreadmodel` macro. The read-model-processor-v2 engine handles
   reading the subscribed events from the event store, two-tier caching, and
   incremental updates — the reducer never touches the event store directly.

   `defreadmodel` registers the reducer under `:<ns>/<name>`; project it
   with `(rmp/project context :example/counters)` (see
   interface.read-models/root). Bump `:version` to invalidate the cache."
  (:require [ai.obney.grain.read-model-processor-v2.interface :refer [defreadmodel]]))

(defreadmodel :example counters
  {:events #{:example/counter-created
             :example/counter-incremented
             :example/counter-decremented}
   :version 1}
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
