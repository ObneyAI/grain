(ns ai.obney.grain.kv-store-lmdb.core
  (:refer-clojure :exclude [get])
  (:require [ai.obney.grain.kv-store.interface.protocol :as p])
  (:import [org.lmdbjava DbiFlags Env]
           [java.nio ByteBuffer]
           [java.io File]
           [java.nio.charset StandardCharsets]))

(defn start
  [{{:keys [storage-dir db-name]} :config
    :as kv-store}]
  (let [file (File. storage-dir)
        _ (.mkdirs file)
        env (.. (Env/create)
                (setMapSize 10485760)
                (setMaxDbs 1)
                (open file nil))
        dbi (.openDbi env db-name (into-array DbiFlags [DbiFlags/MDB_CREATE]))]
    (assoc kv-store :env env :db dbi)))

(defn stop
  [{:keys [env db] :as _kv-store}]
  (.close db)
  (.close env))

(defn get!
  [{:keys [env db] :as _cache} {:keys [k]}]
  (with-open [txn (.txnRead env)]
    (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
          _ (.. k-buf (put k) flip)
          found (.get db txn k-buf)]
      (when found
        (str (.decode StandardCharsets/UTF_8 (.val txn)))))))

(defn put!
  [{:keys [env db] :as _cache} {:keys [k v]}]
  (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
        _ (.. k-buf (put k) flip)
        v-buf (ByteBuffer/allocateDirect (count v))
        _ (.. v-buf (put v) flip)]
    (.put db k-buf v-buf)))

(defrecord KV-Store-LMDB [config]
  p/KVStore
  (start [this] (start this))
  (stop [this] (stop this))
  (get! [this args] (get! this args))
  (put! [this args] (put! this args)))

