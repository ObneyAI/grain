(ns ai.obney.grain.example-service.interface.event-model
  "The service-area-first event model for the :example area, registered with
   `defeventmodel`. Loading this namespace registers the model so the boot-guard
   (event-model-validator/verify-or-throw!) can reconcile it against the live
   runtime and refuse to start if they disagree.

   This is the worked example of MANDATING the model: the model enumerates every
   live block (full coverage), its produces/reads match the def-site declarations,
   and its behavioural boundaries trace to the companion Allium specification."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :example
  {:description "Counter service area: create counters and track their values."

   :commands
   {:example/create-counter
    {:description "Creates a new counter. Counter name must be unique."
     :schema [:map [:name :string]]
     :reads #{:example/counters}
     :produces #{:example/counter-created}
     :grain/allium [{:spec "components/example-service/example-service.allium"
                     :kind :rule :name "CreateCounter"}]}
    :example/increment-counter
    {:description "Increments an existing counter by 1."
     :schema [:map [:counter-id :uuid]]
     :reads #{:example/counters}
     :produces #{:example/counter-incremented}
     :grain/allium [{:spec "components/example-service/example-service.allium"
                     :kind :rule :name "IncrementCounter"}]}
    :example/decrement-counter
    {:description "Decrements an existing counter by 1."
     :schema [:map [:counter-id :uuid]]
     :reads #{:example/counters}
     :produces #{:example/counter-decremented}
     :grain/allium [{:spec "components/example-service/example-service.allium"
                     :kind :rule :name "DecrementCounter"}]}
    :example/calculate-average-counter-value
    {:description "Calculates the average value of all initialized counters."
     :schema [:map]
     :reads #{:example/counters}
     :produces #{:example/average-calculated}
     :grain/allium [{:spec "components/example-service/example-service.allium"
                     :kind :rule :name "CalculateAverage"}]}}

   :events
   {:example/counter-created     {:description "A counter was created."
                                  :schema [:map [:counter-id :uuid] [:name :string]]}
    :example/counter-incremented {:description "A counter was incremented."
                                  :schema [:map [:counter-id :uuid]]}
    :example/counter-decremented {:description "A counter was decremented."
                                  :schema [:map [:counter-id :uuid]]}
    :example/average-calculated  {:description "The average counter value was calculated."
                                  :schema [:map [:value :double]]}}

   :read-models
   {:example/counters
    {:description "All counters, projected from counter events."
     :consumes #{:example/counter-created
                 :example/counter-incremented
                 :example/counter-decremented}
     :schema [:map-of :uuid
              [:map
               [:counter/id :uuid]
               [:counter/name :string]
               [:counter/value {:optional true} :int]]]
     :version 1}}

   :queries
   {:example/counters {:description "Returns all counters."
                       :schema [:map]
                       :reads #{:example/counters}}
    :example/counter  {:description "Returns a single counter by id."
                       :schema [:map [:counter-id :uuid]]
                       :reads #{:example/counters}}}

   :todo-processors
   {:example/calculate-average-counter-value
    {:description "Recomputes the average counter value whenever a counter changes."
     :subscribes #{:example/counter-incremented :example/counter-decremented}
     :reads #{:example/counters}
     :produces #{:example/calculate-average-counter-value}}}

   :periodic-tasks
   {:example/example-periodic-task
    {:description "No-op heartbeat; runs every 30s per tenant."
     :schedule {:every 30 :duration :seconds}}}

   :screens
   {:example/dashboard
    {:description "Shows all counters and lets the user create and adjust them."
     :queries #{:example/counters}
     :commands #{:example/create-counter
                 :example/increment-counter
                 :example/decrement-counter}
     :grain/allium [{:spec "components/example-service/example-service.allium"
                     :kind :surface :name "CounterManagement"}]}}

   :flows
   {:example/counter-lifecycle
    {:description "A user increments a counter; the projection updates and the screen re-reads it."
     :steps [{:from [:screen :example/dashboard]          :to [:command :example/increment-counter]}
             {:from [:command :example/increment-counter] :to [:event :example/counter-incremented]}
             {:from [:event :example/counter-incremented] :to [:read-model :example/counters]}
             {:from [:read-model :example/counters]        :to [:query :example/counters]}
             {:from [:query :example/counters]             :to [:todo-processor :example/calculate-average-counter-value]}
             {:from [:query :example/counters]             :to [:screen :example/dashboard]}]}}})
