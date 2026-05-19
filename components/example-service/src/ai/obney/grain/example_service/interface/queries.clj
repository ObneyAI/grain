(ns ai.obney.grain.example-service.interface.queries
  "Public interface for the example-service queries.

   With the macro system there is no registry map to re-export — loading
   this namespace loads core.queries, whose `defquery` forms register the
   handlers in the global query-processor registry. The base requires this
   namespace so the queries are registered at startup."
  (:require [ai.obney.grain.example-service.core.queries]))
