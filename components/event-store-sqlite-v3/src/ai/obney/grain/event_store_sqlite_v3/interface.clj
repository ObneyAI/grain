(ns ai.obney.grain.event-store-sqlite-v3.interface
  "Loading this namespace registers the :sqlite backend with the
   event-store-v3 protocol. Callers should use
   `ai.obney.grain.event-store-v3.interface` for the public API and
   pass `{:conn {:type :sqlite :database-file ...}}` to start."
  (:require [ai.obney.grain.event-store-sqlite-v3.core]))
