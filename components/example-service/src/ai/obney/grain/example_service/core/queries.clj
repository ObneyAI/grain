(ns ai.obney.grain.example-service.core.queries
  "The core queries namespace in a grain service component implements
   the query handlers using the `defquery` macro. Each `defquery` defines
   a handler function and registers it in the global query registry under
   `:<ns>/<name>` — no manual registry map is needed.

   Query handlers take a context that includes any necessary dependencies
   wired in the base for the service (`:event-store`, `:cache`,
   `:tenant-id`). A query-request-handler (HTTP) or a direct `process-query`
   call (REPL) looks the handler up in the registry. Queries either return
   a cognitect anomaly or a map with a `:query/result`.

   The `:authorized?` opt is required for the query to be reachable over
   HTTP via query-request-handler."
  (:require [ai.obney.grain.example-service.interface.read-models :as read-models]
            [ai.obney.grain.query-processor.interface :refer [defquery]]
            [cognitect.anomalies :as anom]))

(defquery :example counters
  {:authorized? (constantly true)
   :grain.event-model/reads #{:example/counters}}
  "Returns all counters."
  [context]
  (let [counters (->> (read-models/root context)
                      vals)]
    {:query/result counters}))

(defquery :example counter
  {:authorized? (constantly true)
   :grain.event-model/reads #{:example/counters}}
  "Returns a single counter by id."
  [{{:keys [counter-id]} :query :as context}]
  (let [counter (-> (read-models/root context)
                    (get counter-id))]
    (if counter
      {:query/result counter}
      {::anom/category ::anom/not-found
       ::anom/message (format "Counter with ID '%s' not found." counter-id)})))
