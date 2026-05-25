(ns ai.obney.grain.example-service.interface.commands
  "Public interface for the example-service commands.

   With the macro system there is no registry map to re-export — loading
   this namespace loads core.commands, whose `defcommand` forms register
   the handlers in the global command-processor-v2 registry. The base
   requires this namespace so the commands are registered at startup."
  (:require [ai.obney.grain.example-service.core.commands]))
