(ns ai.obney.grain.kv-store-lmdb.interface
  "Loading this namespace registers the :lmdb backend with the
   kv-store protocol. Callers should use
   ai.obney.grain.kv-store.interface for the public API and
   pass {:type :lmdb :storage-dir ... :db-name ...} to start."
  (:require [ai.obney.grain.kv-store-lmdb.core]))
