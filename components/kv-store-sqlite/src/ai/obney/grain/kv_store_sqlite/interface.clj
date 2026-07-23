(ns ai.obney.grain.kv-store-sqlite.interface
  "Loading this namespace registers the :sqlite backend with the
   kv-store protocol. Callers should use
   ai.obney.grain.kv-store.interface for the public API and
   pass {:type :sqlite :database-file ...} to start."
  (:require [ai.obney.grain.kv-store-sqlite.core]))
