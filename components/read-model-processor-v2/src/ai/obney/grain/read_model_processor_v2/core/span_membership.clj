(ns ai.obney.grain.read-model-processor-v2.core.span-membership
  "Shared model-local ordinals for text postings and rank-span membership bitmaps.
   Mutable bitmap values never leave a transaction.
   A write batch coalesces repeated changes to the same span before serialization."
  (:require [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.fressian-util.interface :as codec])
  (:import [java.nio ByteBuffer]
           [java.util HashMap]
           [org.roaringbitmap RoaringBitmap]))

(defn number-bytes ^bytes [n]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int n)))))
(defn number-value [^bytes raw] (.getInt (ByteBuffer/wrap raw)))
(defn- slot [b kind suffix] (key/checked (key/join b (byte-array [kind]) suffix)))
(defn ordinal [tx b id]
  (some-> ((:get tx) (slot b 7 (key/encode id))) number-value))
(defn ensure-ordinal! [tx b id]
  (or (ordinal tx b id)
      (let [counter (slot b 9 (byte-array 0))
            next (or (some-> ((:get tx) counter) number-value) 0)]
        (when (>= next Integer/MAX_VALUE)
          (throw (ex-info "Model ordinal space exhausted" {})))
        ((:put tx) (slot b 7 (key/encode id)) (number-bytes next))
        ((:put tx) (slot b 8 (number-bytes next)) (codec/encode id))
        ((:put tx) counter (number-bytes (inc next)))
        next)))

(defn decode ^RoaringBitmap [^bytes raw]
  (doto (RoaringBitmap.) (.deserialize (ByteBuffer/wrap raw))))
(defn encode ^bytes [^RoaringBitmap bits]
  (let [out (ByteBuffer/allocate (.serializedSizeInBytes bits))]
    (.serialize bits out)
    (.array out)))
(defn transaction [tx] (assoc tx ::pending (HashMap.)))
(defn bitmap ^RoaringBitmap [tx ^bytes k]
  (let [^HashMap pending (::pending tx) address (ByteBuffer/wrap k)]
    (or (when pending (.get pending address))
        (when-let [raw ((:get tx) k)] (decode raw))
        (RoaringBitmap.))))
(defn put! [tx ^bytes k bits]
  (if-let [^HashMap pending (::pending tx)]
    (.put pending (ByteBuffer/wrap k) bits)
    ((:put tx) k (encode bits))))
(defn delete! [tx ^bytes k]
  (when-let [^HashMap pending (::pending tx)] (.remove pending (ByteBuffer/wrap k)))
  ((:delete tx) k))
(defn flush! [tx observe]
  (when-let [^HashMap pending (::pending tx)]
    (doseq [^java.util.Map$Entry entry (.entrySet pending)]
      (let [raw (encode (.getValue entry))]
        ((:put tx) (.array ^ByteBuffer (.getKey entry)) raw)
        (observe :membership-written (alength raw))))))
