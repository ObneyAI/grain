(ns ai.obney.grain.kv-store-sqlite.interface
  "Loading this namespace registers the :sqlite backend with the
   kv-store protocol. Preferred usage is
   ai.obney.grain.kv-store.interface's start with
   {:type :sqlite :database-file ...}. ->KV-Store-Sqlite remains
   exported for callers that construct the record directly."
  (:require [ai.obney.grain.kv-store-sqlite.core :as core]))

(def ->KV-Store-Sqlite core/->KV-Store-Sqlite)
