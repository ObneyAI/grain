(ns ai.obney.grain.kv-store-lmdb.interface
  "Loading this namespace registers the :lmdb backend with the
   kv-store protocol. Preferred usage is
   ai.obney.grain.kv-store.interface's start with
   {:type :lmdb :storage-dir ... :db-name ...}. ->KV-Store-LMDB remains
   exported for callers that construct the record directly."
  (:require [ai.obney.grain.kv-store-lmdb.core :as core]))

(def ->KV-Store-LMDB core/->KV-Store-LMDB)
