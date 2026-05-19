(ns ai.obney.grain.example-service.interface.todo-processors
  "Public interface for the example-service todo processors.

   With the macro system there is nothing to re-export — loading this
   namespace loads core.todo-processors, whose `defprocessor` form
   registers the handler in the global todo-processor-v2 registry. The
   base requires this namespace so the processor is registered and picked
   up by the tenant poller at startup."
  (:require [ai.obney.grain.example-service.core.todo-processors]))
