(ns ai.obney.grain.kv-store-lmdb.core
  (:refer-clojure :exclude [get])
  (:require [ai.obney.grain.kv-store.interface.protocol :as p])
  (:import [org.lmdbjava Cursor Dbi DbiFlags Env PutFlags Txn]
           [java.nio ByteBuffer]
           [java.io File]))

(def default-map-size
  "Default LMDB map size: 10 MB"
  10485760)

(def default-max-readers
  "Default LMDB max concurrent reader slots. Each unique thread that opens a
   read transaction claims a slot that persists until the thread dies AND
   readerCheck() is called. Applications using futures or thread pools
   (e.g., ORC node execution, GEPA optimization) create many short-lived
   threads. The lmdbjava default of 126 is too low for these workloads."
  1024)

(defn start
  [{{:keys [storage-dir db-name map-size max-readers]} :config
    :as kv-store}]
  (let [file (File. storage-dir)
        _ (.mkdirs file)
        env (.. (Env/create)
                (setMapSize (long (or map-size default-map-size)))
                (setMaxReaders (int (or max-readers default-max-readers)))
                (setMaxDbs 1)
                (open file nil))
        dbi (.openDbi env db-name (into-array DbiFlags [DbiFlags/MDB_CREATE]))]
    (assoc kv-store :env env :db dbi)))

(defn stop
  [{:keys [env db] :as _kv-store}]
  (.close db)
  (.close env))

(defn- read-value
  "Copy a value out of an existing LMDB read transaction."
  [^Env env ^Dbi db ^Txn txn ^bytes k]
  (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
        _ (.. k-buf (put k) flip)
        found (.get db txn k-buf)]
    (when found
      (let [^ByteBuffer val-buf (.val txn)
            arr (byte-array (.remaining val-buf))]
        (.get val-buf arr)
        arr))))

(defn- open-read-transaction ^Txn [^Env env]
  (try
    (.txnRead env)
    (catch org.lmdbjava.Env$ReadersFullException _
      (.readerCheck env)
      (.txnRead env))))

(defn read-snapshot
  [{:keys [env db]} f]
  (with-open [txn (open-read-transaction env)]
    (f (fn [{:keys [k]}] (read-value env db txn k)))))

(defn get!
  "Read a value from LMDB. On ReadersFullException, reclaims stale reader
   slots from dead threads via readerCheck() and retries once."
  [cache args]
  (read-snapshot cache (fn [get-value] (get-value args))))

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

(defn- buffer ^ByteBuffer [^bytes value]
  (doto (ByteBuffer/allocateDirect (alength value)) (.put value) (.flip)))

(defn- copy-buffer [^ByteBuffer value]
  (let [view (.duplicate value) out (byte-array (.remaining view))]
    (.get view out)
    out))

(defn- byte-compare [^bytes a ^bytes b]
  (java.util.Arrays/compareUnsigned a b))

(defn- prefix-end [^bytes prefix]
  (when prefix
    (loop [i (dec (alength prefix))]
      (when (>= i 0)
        (if (= 255 (bit-and 255 (aget prefix i)))
          (recur (dec i))
          (let [out (java.util.Arrays/copyOf prefix (inc i))]
            (aset-byte out i (unchecked-byte (inc (bit-and 255 (aget prefix i)))))
            out))))))

(defn- scan [^Dbi db ^Txn txn {:keys [prefix from to reverse?] :as options} rf initial check!]
  (when-not (every? #{:prefix :from :to :reverse?} (keys options))
    (throw (ex-info "Unknown transaction range option" {:options options})))
  (let [prefix (when (seq prefix) prefix)
        from (when (seq from) from)
        lower (cond (nil? prefix) from (nil? from) prefix
                    (pos? (byte-compare prefix from)) prefix :else from)
        end (prefix-end prefix)
        upper (cond (nil? end) to (nil? to) end
                    (neg? (byte-compare end to)) end :else to)]
    (if (or (and upper (zero? (alength ^bytes upper)))
            (and lower upper (not (neg? (byte-compare lower upper)))))
      initial
      (with-open [^Cursor cursor (.openCursor db txn)]
      (let [found (if reverse?
                    (if upper
                      (if (.get cursor (buffer upper) org.lmdbjava.GetOp/MDB_SET_RANGE)
                        (.prev cursor) (.last cursor))
                      (.last cursor))
                    (if lower (.get cursor (buffer lower) org.lmdbjava.GetOp/MDB_SET_RANGE)
                        (.first cursor)))]
        (loop [found found acc initial]
          (check! false)
          (if-not found acc
            (let [k (copy-buffer (.key cursor))]
              (if (or (and lower (neg? (byte-compare k lower)))
                      (and upper (not (neg? (byte-compare k upper)))))
                acc
                (let [next (rf acc {:k k :v (copy-buffer (.val cursor))})]
                  (if (reduced? next) @next
                      (recur (if reverse? (.prev cursor) (.next cursor)) next))))))))))))

(def ^:private ^ThreadLocal transactions (ThreadLocal.))

(defn transact [{:keys [^Env env ^Dbi db]} mode f]
  (when-not (#{:read :write} mode)
    (throw (ex-info "Transaction mode must be :read or :write" {:mode mode})))
  (let [previous (.get transactions)]
    (when (contains? previous env)
      (throw (ex-info "Nested transactions on the same store are unsupported" {})))
    (.set transactions (conj (or previous #{}) env))
    (try
      (let [active (volatile! true) owner (Thread/currentThread) failure (atom nil)
            check! (fn [write?]
                     (when-not (and @active (identical? owner (Thread/currentThread)))
                       (throw (ex-info "Transaction operation escaped its callback or thread" {})))
                     (when (and write? (= mode :read))
                       (throw (ex-info "Read transaction cannot write" {}))))
            invoke (fn [write? operation]
                     (try (check! write?) (operation)
                          (catch Throwable e (compare-and-set! failure nil e) (throw e))))]
        (with-open [^Txn txn (if (= mode :write) (.txnWrite env) (open-read-transaction env))]
          (try
            (let [result (f {:get (fn [k] (invoke false #(read-value env db txn k)))
                             :put (fn [k v]
                                    (invoke true #(.put db txn (buffer k) (buffer v) (into-array PutFlags []))))
                             :delete (fn [k] (invoke true #(.delete db txn (buffer k))))
                             :reduce (fn [options rf initial]
                                       (invoke false #(scan db txn options rf initial check!)))})]
              (when-let [cause @failure]
                (throw (ex-info "Transaction aborted after an operation failed" {} cause)))
              (when (= mode :write) (.commit txn))
              result)
            (finally (vreset! active false)))))
      (finally
        (if previous (.set transactions previous) (.remove transactions))))))

(defrecord KV-Store-LMDB [config]
  p/KVStore
  (start [this] (start this))
  (stop [this] (stop this))
  (get! [this args] (get! this args))
  (read-snapshot [this f] (read-snapshot this f))
  (put! [this args] (put! this args))
  (put-batch! [this args] (put-batch! this args))
  p/TransactionalStore
  (transact [this mode f] (transact this mode f)))
