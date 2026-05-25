(ns ai.obney.grain.example-service.interface.read-models
  "Public interface for the example-service read models.

   Loading this namespace loads core.read-models, whose `defreadmodel`
   form registers the `:example/counters` reducer in the global
   read-model-processor-v2 registry. `root` projects that read model;
   commands and queries call `(read-models/root context)` so the
   call sites are unchanged from the legacy implementation."
  (:require [ai.obney.grain.example-service.core.read-models]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn root
  "Project the `:example/counters` read model from `context`.
   Requires `:event-store`, `:cache`, and `:tenant-id` in context."
  [context]
  (rmp/project context :example/counters))
