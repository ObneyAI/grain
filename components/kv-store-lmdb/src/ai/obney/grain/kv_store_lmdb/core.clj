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
    (assoc kv-store :env env :db dbi ::validation-state (atom {:revision nil :proofs []}))))

(defn stop
  [{:keys [env db] :as _kv-store}]
  (.close db)
  (.close env))

(def ^:private no-put-flags (into-array PutFlags []))
(def ^:private max-retained-value-size (* 64 1024))

(defn- fill-buffer ^ByteBuffer [^ByteBuffer buffer ^bytes value]
  (doto buffer (.clear) (.put value) (.flip)))

(defn- value-buffer-writer
  "Scratch storage for one write transaction; oversized values are not retained."
  []
  (let [retained (volatile! nil)]
    (fn [^bytes value]
      (let [size (alength value)
            ^ByteBuffer previous @retained
            buffer (if (and previous (<= size (.capacity previous)))
                     previous
                     (let [capacity (if (> size max-retained-value-size)
                                      size
                                      (min max-retained-value-size
                                           (max size (* 2 (if previous (.capacity previous) 1)))))
                           buffer (ByteBuffer/allocateDirect capacity)]
                       (when (<= capacity max-retained-value-size)
                         (vreset! retained buffer))
                       buffer))]
        (fill-buffer buffer value)))))

(defn- read-value
  "Copy a value out of an existing LMDB read transaction."
  [^Dbi db ^Txn txn ^ByteBuffer k-buf ^bytes k]
  (let [found (.get db txn (fill-buffer k-buf k))]
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
  [{:keys [^Env env db]} f]
  (with-open [txn (open-read-transaction env)]
    (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
          owner (Thread/currentThread) active (volatile! true)]
      (try
        (f (fn [{:keys [k]}]
             (when-not (and @active (identical? owner (Thread/currentThread)))
               (throw (ex-info "Read snapshot escaped its callback or thread" {})))
             (read-value db txn k-buf k)))
        (finally (vreset! active false))))))

(defn get!
  "Read a value from LMDB. On ReadersFullException, reclaims stale reader
   slots from dead threads via readerCheck() and retries once."
  [cache args]
  (read-snapshot cache (fn [get-value] (get-value args))))

(declare transact)

(defn put!
  [cache {:keys [k v]}]
  ;; Ordinary public writes participate in the same commit provenance and
  ;; prefix-proof invalidation as explicit transactional operations.
  (transact cache :write #((:put %) k v)))

(defn put-batch!
  [cache {:keys [entries]}]
  (transact cache :write
    (fn [tx]
      (doseq [{:keys [k v]} entries]
        ((:put tx) k v)))))

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

(defn- with-scan-cursor [^Dbi db ^Txn txn spare f]
  (let [^Cursor cursor (if-let [cursor (when spare @spare)]
                        (do (vreset! spare nil) cursor)
                        (.openCursor db txn))]
    (try
      (f cursor)
      (finally
        (if (and spare (nil? @spare))
          (vreset! spare cursor)
          (.close cursor))))))

(defn- close-spare-cursor! [spare]
  (when-let [^Cursor cursor @spare]
    (vreset! spare nil)
    (.close cursor)))

(defn- scan [^Dbi db ^Txn txn ^ByteBuffer k-buf spare {:keys [prefix from to reverse?] :as options} rf initial check!]
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
      (with-scan-cursor db txn spare
        (fn [^Cursor cursor]
          (let [found (if reverse?
                        (if upper
                          (if (.get cursor (fill-buffer k-buf upper) org.lmdbjava.GetOp/MDB_SET_RANGE)
                            (.prev cursor) (.last cursor))
                          (.last cursor))
                        (if lower (.get cursor (fill-buffer k-buf lower) org.lmdbjava.GetOp/MDB_SET_RANGE)
                            (.first cursor)))]
            (loop [found found acc initial]
              (check! false)
              (if-not found acc
                ;; OPT-10: copy the key, then check bounds with the intrinsic
                ;; Arrays/compareUnsigned (as before OPT-1). In-range keys are
                ;; copied anyway; only the one terminating key per scan is an
                ;; extra copy. Comparing on the cursor's direct buffer, byte by
                ;; byte (OPT-1) or with ByteBuffer/mismatch, was measured slower
                ;; per scanned entry: index-keys of 100 ids on S desk/tickets
                ;; 53.5 us (per-byte) / 48.2 us (mismatch) vs 38.5 us (this).
                (let [k (copy-buffer (.key cursor))]
                  (if (or (and lower (neg? (byte-compare k lower)))
                          (and upper (not (neg? (byte-compare k upper)))))
                    acc
                    (let [next (rf acc {:k k :v (copy-buffer (.val cursor))})]
                      (if (reduced? next) @next
                          (recur (if reverse? (.prev cursor) (.next cursor)) next)))))))))))))

(def ^:private ^ThreadLocal transactions (ThreadLocal.))
(def ^:private max-validation-proofs 16)

(defn- overlapping-prefix? [prefix ^bytes key]
  ;; Treat writes at a parent prefix conservatively as overlapping too.
  (let [n (min (count prefix) (alength key))]
    (loop [i 0]
      (or (= i n)
          (and (= (nth prefix i) (aget key i)) (recur (inc i)))))))

(defn- invalidate-proofs! [proofs key]
  ;; Copy only when some proof actually overlaps; most writes touch none.
  (when proofs
    (let [current @proofs]
      (when (some (fn [[_ prefix]] (overlapping-prefix? prefix key)) current)
        (vreset! proofs (into [] (remove (fn [[_ prefix]] (overlapping-prefix? prefix key))) current))))))

(defn- same-validation? [[validator prefix] other-validator other-prefix]
  (and (identical? validator other-validator) (= prefix other-prefix)))

(defn transact [{:keys [^Env env ^Dbi db] :as cache} mode f]
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
          (let [spare (volatile! nil)
                validation-state (when (= mode :write) (::validation-state cache))
                write-id (when validation-state (.getId txn))
                base-id (when write-id (dec write-id))
                before (when validation-state @validation-state)
                proofs (when validation-state
                         (volatile! (if (= base-id (:revision before)) (:proofs before) [])))
                modified? (volatile! false)]
            (try
              (let [k-buf (ByteBuffer/allocateDirect (.getMaxKeySize env))
                    value-buffer (when (= mode :write) (value-buffer-writer))
                    operations {:get (fn [k] (invoke false #(read-value db txn k-buf k)))
                                :put (fn [k v]
                                       (invoke true #(do
                                                       (invalidate-proofs! proofs k)
                                                       (let [result (.put db txn (fill-buffer k-buf k) (value-buffer v) no-put-flags)]
                                                         (vreset! modified? true)
                                                         result))))
                                :delete (fn [k]
                                          (invoke true #(do
                                                          (invalidate-proofs! proofs k)
                                                          (let [deleted? (.delete db txn (fill-buffer k-buf k))]
                                                            (when deleted? (vreset! modified? true))
                                                            deleted?))))
                                :reduce (fn [options rf initial]
                                          (invoke false #(scan db txn k-buf spare options rf initial check!)))
                                ;; Map capacity vs the data file's last allocated page
                                ;; (high-water, including free pages), as committed.
                                :space (fn []
                                         (invoke false #(let [info (.info env)]
                                                          {:map-bytes (.mapSize info)
                                                           :high-water-bytes (* (inc (.lastPageNumber info))
                                                                                (long (.pageSize (.stat env))))})))}
                    operations (cond-> operations
                                 proofs
                                 (assoc :with-validation
                                        (fn [prefix validator update!]
                                          (invoke true
                                            (fn []
                                              (when-not (and (bytes? prefix) (pos? (alength ^bytes prefix))
                                                             (<= (alength ^bytes prefix) (.getMaxKeySize env))
                                                             (fn? validator) (fn? update!))
                                                (throw (ex-info "Expected byte prefix, validator and preserving update" {})))
                                              (let [prefix (vec prefix)]
                                                (when-not (some #(same-validation? % validator prefix) @proofs)
                                                  (validator operations))
                                                (let [result (update!)]
                                                  ;; The caller promises its update preserves the checked
                                                  ;; invariant. Subsequent ordinary writes invalidate it.
                                                  (vswap! proofs #(vec (take-last max-validation-proofs
                                                                         (conj (into [] (remove
                                                                                          (fn [proof] (same-validation? proof validator prefix))) %)
                                                                               [validator prefix]))))
                                                  result)))))))
                    result (f operations)]
                (when-let [cause @failure]
                  (throw (ex-info "Transaction aborted after an operation failed" {} cause)))
                (when (= mode :write)
                  ;; Write cursors must close while their transaction is ready;
                  ;; LMDB releases them at commit. Sequential scans reuse one
                  ;; idle cursor, and nested scans still have separate loans.
                  (close-spare-cursor! spare)
                  (.commit txn)
                  (when validation-state
                    ;; Use this transaction's ID, never env.info's potentially
                    ;; newer foreign commit. Empty commits retain the base ID.
                    ;; A racing older publisher cannot replace newer evidence.
                    (let [revision (if @modified? write-id base-id)]
                      (swap! validation-state
                             (fn [current]
                               (if (and (:revision current) (> (:revision current) revision)) current
                                 {:revision revision :proofs @proofs}))))))
                result)
              (finally
                (vreset! active false)
                (close-spare-cursor! spare))))))
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
