(ns ai.obney.grain.example-service.core.periodic-tasks
  "The core periodic-tasks namespace defines scheduled triggers using the
   `defperiodic` macro. `defperiodic` registers the handler under
   `:<ns>/<name>`; the base runs all registered triggers via
   `periodic-task`'s `start-periodic-triggers!`.

   On each schedule tick the handler is called once per tenant with
   `[tenant-id time]` and returns `{:result/events [...] :result/cas {...}}`
   (or `{}` for a no-op). The framework appends any returned events with
   the optional CAS predicate.

   This example trigger is a no-op heartbeat: it logs and returns `{}`."
  (:require [ai.obney.grain.periodic-task.interface :refer [defperiodic]]
            [com.brunobonacci.mulog :as u]))

(defperiodic :example example-periodic-task
  {:schedule {:every 30 :duration :seconds}
   :grain.event-model/produces #{}}
  "Example periodic task. Runs every 30s per tenant; no-op heartbeat."
  [tenant-id _time]
  (u/log ::example :tenant-id tenant-id)
  {})
