(ns ai.obney.grain.read-model-processor-v2.core.span-membership
  "Experimental rank-span bitmaps. Mutable values never leave a transaction.
   A write batch coalesces repeated changes to the same span before serialization."
  (:import [java.nio ByteBuffer]
           [java.util HashMap]
           [org.roaringbitmap RoaringBitmap]))

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
