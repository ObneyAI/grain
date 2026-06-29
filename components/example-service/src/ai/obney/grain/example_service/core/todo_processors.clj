(ns ai.obney.grain.example-service.core.todo-processors
  "The core todo-processors namespace defines async event processors using
   the `defprocessor` macro. `defprocessor` registers the handler under
   `:<ns>/<name>` in the global processor registry; the base runs it via
   `todo-processor-v2`'s standalone tenant poller (no control plane needed).

   The handler receives a context with `:event`, `:event-store`,
   `:tenant-id` (plus anything merged from the poller's `:context`, e.g.
   `:cache`). It subscribes to events via the `:topics` opt and processes
   one event at a time. It must return `{:result/events [...]}`,
   `{:result/effect (fn [] ...)}`, or `{}`.

   This processor recomputes the average counter value by delegating to the
   `:example/calculate-average-counter-value` command (which appends the
   `:example/average-calculated` event itself), then returns `{}` so the
   poller simply checkpoints the handled trigger event."
  (:require [ai.obney.grain.todo-processor-v2.interface :refer [defprocessor]]
            [ai.obney.grain.command-processor-v2.interface :as command-processor]
            [ai.obney.grain.time.interface :as time]))

(defprocessor :example calculate-average-counter-value
  {:topics #{:example/counter-incremented :example/counter-decremented}
   :grain.event-model/produces #{:example/calculate-average-counter-value}}
  "Recomputes the average counter value whenever a counter changes."
  [context]
  (command-processor/process-command
   (assoc context
          :command
          {:command/id (random-uuid)
           :command/timestamp (time/now)
           :command/name :example/calculate-average-counter-value}))
  {})
