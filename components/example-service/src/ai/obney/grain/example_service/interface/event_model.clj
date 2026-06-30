(ns ai.obney.grain.example-service.interface.event-model
  "The service-area-first event model for the :example area, registered with
   `defeventmodel`. Loading this namespace registers the model so the boot-guard
   (event-model-validator/verify-or-throw!) can reconcile it against the live
   runtime and refuse to start if they disagree.

   This is the worked example of MANDATING the model: the model enumerates every
   live block (full coverage), its produces/reads match the def-site declarations,
   and every command carries Given/When/Then — the strictest tier."
  (:require [ai.obney.grain.event-model.interface :refer [defeventmodel]]))

(defeventmodel :example
  {:description "Counter service area: create counters and track their values."

   :commands
   {:example/create-counter
    {:description "Creates a new counter. Counter name must be unique."
     :schema [:map [:name :string]]
     :reads #{:example/counters}
     :produces #{:example/counter-created}
     :given-when-thens [{:given "no counter named \"A\" exists"
                         :when  "create-counter with name \"A\""
                         :then  "a counter-created event is recorded for \"A\""}
                        {:given "a counter named \"A\" already exists"
                         :when  "create-counter with name \"A\""
                         :then  "the command is rejected as a conflict"}]}
    :example/increment-counter
    {:description "Increments an existing counter by 1."
     :schema [:map [:counter-id :uuid]]
     :reads #{:example/counters}
     :produces #{:example/counter-incremented}
     :given-when-thens [{:given "a counter exists"
                         :when  "increment-counter for its id"
                         :then  "a counter-incremented event is recorded"}
                        {:given "no counter with that id exists"
                         :when  "increment-counter for an unknown id"
                         :then  "the command is rejected as not-found"}]}
    :example/decrement-counter
    {:description "Decrements an existing counter by 1."
     :schema [:map [:counter-id :uuid]]
     :reads #{:example/counters}
     :produces #{:example/counter-decremented}
     :given-when-thens [{:given "a counter exists"
                         :when  "decrement-counter for its id"
                         :then  "a counter-decremented event is recorded"}
                        {:given "no counter with that id exists"
                         :when  "decrement-counter for an unknown id"
                         :then  "the command is rejected as not-found"}]}
    :example/calculate-average-counter-value
    {:description "Calculates the average value of all initialized counters."
     :schema [:map]
     :reads #{:example/counters}
     :produces #{:example/average-calculated}
     :given-when-thens [{:given "some counters have values"
                         :when  "calculate-average-counter-value runs"
                         :then  "an average-calculated event records the mean value"}]}}

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
                 :example/decrement-counter}}}

   :flows
   {:example/counter-lifecycle
    {:description "A user increments a counter; the projection updates and the screen re-reads it."
     :steps [{:from [:screen :example/dashboard]          :to [:command :example/increment-counter]}
             {:from [:command :example/increment-counter] :to [:event :example/counter-incremented]}
             {:from [:event :example/counter-incremented] :to [:read-model :example/counters]}
             {:from [:event :example/counter-incremented] :to [:todo-processor :example/calculate-average-counter-value]}
             {:from [:read-model :example/counters]        :to [:query :example/counters]}
             {:from [:query :example/counters]             :to [:screen :example/dashboard]}]}}})
