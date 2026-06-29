(ns ai.obney.grain.example-service.core.commands
  "The core commands namespace in a grain service component implements
   the command handlers using the `defcommand` macro. Each `defcommand`
   defines a handler function and registers it in the global command
   registry under `:<ns>/<name>` — no manual registry map is needed.

   Command handlers take a context that includes any necessary dependencies
   wired in the base for the service (`:event-store`, `:cache`, `:tenant-id`).
   A command-request-handler-v2 (HTTP) or a direct `process-command` call
   (REPL) looks the handler up in the registry. Commands either return a
   cognitect anomaly or a map that optionally has a `:command-result/events`
   key containing a sequence of valid events per the event-store event
   schema and optionally a `:command/result`.

   The `:authorized?` opt is required for the command to be reachable over
   HTTP via command-request-handler-v2."
  (:require [ai.obney.grain.example-service.interface.read-models :as read-models]
            [ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
            [ai.obney.grain.event-store-v3.interface :refer [->event]]
            [cognitect.anomalies :as anom]))

(defcommand :example create-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-created}
   :grain.event-model/reads #{:example/counters}}
  "Creates a new counter. Counter name must be unique."
  [context]
  (let [counter-name (get-in context [:command :name])
        counter-id (random-uuid)
        unique-counter-names (->> (read-models/root context)
                                  vals
                                  (map :counter/name)
                                  set)]
    (if (contains? unique-counter-names counter-name)
      {::anom/category ::anom/conflict
       ::anom/message (format "Counter with name '%s' already exists." counter-name)}
      {:command-result/events
       [(->event {:type :example/counter-created
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id
                         :name counter-name}})]})))

(defcommand :example increment-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-incremented}
   :grain.event-model/reads #{:example/counters}}
  "Increments an existing counter by 1."
  [{{:keys [counter-id]} :command :as context}]
  (let [state (read-models/root context)]
    (if (get state counter-id)
      {:command-result/events
       [(->event {:type :example/counter-incremented
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id}})]}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))

(defcommand :example decrement-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-decremented}
   :grain.event-model/reads #{:example/counters}}
  "Decrements an existing counter by 1."
  [{{:keys [counter-id]} :command :as context}]
  (let [state (read-models/root context)]
    (if (get state counter-id)
      {:command-result/events
       [(->event {:type :example/counter-decremented
                  :tags #{[:counter counter-id]}
                  :body {:counter-id counter-id}})]}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))

(defcommand :example calculate-average-counter-value
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/average-calculated}
   :grain.event-model/reads #{:example/counters}}
  "Calculates the average value of all initialized counters."
  [context]
  (let [state (->> (read-models/root context)
                   (filter (fn [[_ v]] (:counter/value v)))
                   (into {}))]
    {:command-result/events
     [(->event
       {:type :example/average-calculated
        :body {:value (/ (double (->> state
                                      vals
                                      (map :counter/value)
                                      (reduce + 0)))
                         (double (count state)))}})]}))
