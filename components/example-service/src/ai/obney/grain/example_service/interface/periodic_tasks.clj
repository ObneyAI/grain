(ns ai.obney.grain.example-service.interface.periodic-tasks
  "Public interface for the example-service periodic tasks.

   With the macro system there is nothing to re-export — loading this
   namespace loads core.periodic-tasks, whose `defperiodic` form registers
   the trigger in the global periodic-task registry. The base requires
   this namespace so the trigger is registered and started by
   `start-periodic-triggers!` at startup."
  (:require [ai.obney.grain.example-service.core.periodic-tasks]))
