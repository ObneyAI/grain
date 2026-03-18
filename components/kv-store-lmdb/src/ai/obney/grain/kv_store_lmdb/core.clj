(ns ai.obney.grain.kv-store-lmdb.core
  (:refer-clojure :exclude [get])
  (:require [ai.obney.grain.kv-store.interface.protocol :as p])
  (:import [org.lmdbjava DbiFlags Env PutFlags]
           [java.nio ByteBuffer]
           [java.io File]))

(def default-map-size
  "Default LMDB map size: 10 MB"
  10485760)

(defn start
  [{{:keys [storage-dir db-name map-size]} :config
    :as kv-store}]
  (let [file (File. storage-dir)
        _ (.mkdirs file)
        env (.. (Env/create)
                (setMapSize (long (or map-size default-map-size)))
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
        (let [val-buf (.val txn)
              arr (byte-array (.remaining val-buf))]
          (.get val-buf arr)
          arr)))))

(defn put!
  [{:keys [env db] :as _cache} {:keys [k v]}]
  (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
        _ (.. k-buf (put k) flip)
        v-buf (ByteBuffer/allocateDirect (count v))
        _ (.. v-buf (put v) flip)]
    (.put db k-buf v-buf)))

(defn put-batch!
  [{:keys [env db] :as _cache} {:keys [entries]}]
  (with-open [txn (.txnWrite env)]
    (doseq [{:keys [k v]} entries]
      (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
            _ (.. k-buf (put k) flip)
            v-buf (ByteBuffer/allocateDirect (count v))
            _ (.. v-buf (put v) flip)]
        (.put db txn k-buf v-buf (into-array org.lmdbjava.PutFlags []))))
    (.commit txn)))

(defrecord KV-Store-LMDB [config]
  p/KVStore
  (start [this] (start this))
  (stop [this] (stop this))
  (get! [this args] (get! this args))
  (put! [this args] (put! this args))
  (put-batch! [this args] (put-batch! this args)))

