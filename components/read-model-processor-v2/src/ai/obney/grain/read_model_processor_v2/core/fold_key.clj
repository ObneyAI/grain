(ns ai.obney.grain.read-model-processor-v2.core.fold-key
  "Self-delimiting ordered values for keyed-fold storage and sort metadata.
   One string mode escapes UTF-16 bytes; the compact mode favors ASCII strings.
   Both preserve UTF-16 ordering, but their sizes differ for non-ASCII text."
  (:import [java.io ByteArrayOutputStream DataOutputStream EOFException]
           [java.nio ByteBuffer BufferUnderflowException]
           [java.util UUID]))

(defn join ^bytes [& arrays]
  (let [out (byte-array (reduce (fn [size ^bytes a] (+ size (alength a))) 0 arrays))]
    (reduce (fn [offset ^bytes a]
              (System/arraycopy a 0 out offset (alength a))
              (+ offset (alength a)))
            0 arrays)
    out))

(defn- write-physical-string! [^DataOutputStream out ^String value]
  (dotimes [i (.length value)]
    (let [c (int (.charAt value i))
          hi (bit-shift-right c 8)
          lo (bit-and 255 c)]
      (.writeByte out hi) (when (zero? hi) (.writeByte out 255))
      (.writeByte out lo) (when (zero? lo) (.writeByte out 255)))))

(defn- write-compact-string! [^DataOutputStream out ^String value]
  (dotimes [i (.length value)]
    (let [c (int (.charAt value i))]
      (cond
        (zero? c) (do (.writeByte out 0) (.writeByte out 255))
        (< c 128) (.writeByte out c)
        :else (do (.writeByte out 128) (.writeShort out c))))))

(defn- write-key! [^DataOutputStream out value write-string!]
  (cond
    (nil? value) (.writeByte out 1)
    (boolean? value) (.writeByte out (if value 3 2))
    (string? value)
    (do (.writeByte out 16)
        (write-string! out value)
        (.writeShort out 0))
    (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
    (do (.writeByte out 32) (.writeLong out (bit-xor (long value) Long/MIN_VALUE)))
    (uuid? value)
    (do (.writeByte out 48)
        (.writeLong out (.getMostSignificantBits ^UUID value))
        (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value)
    (do (.writeByte out 56) (write-key! out (str value) write-string!))
    (vector? value)
    (do (.writeByte out 64) (doseq [v value] (write-key! out v write-string!)) (.writeByte out 0))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn- encode-with ^bytes [write-string! value]
  (let [out (ByteArrayOutputStream.)]
    (write-key! (DataOutputStream. out) value write-string!)
    (.toByteArray out)))

(defn encode ^bytes [value]
  (encode-with write-physical-string! value))

(defn encode-compact ^bytes [value]
  (encode-with write-compact-string! value))

(defn- tuple-prefix-with ^bytes [encode components]
  (when-not (vector? components)
    (throw (ex-info "Tuple prefix must be a vector" {:prefix components})))
  (let [encoded (encode components)]
    (java.util.Arrays/copyOf encoded (dec (alength encoded)))))

(defn tuple-prefix
  "Encode complete leading vector components, omitting only the outer terminator."
  ^bytes [components]
  (tuple-prefix-with encode components))

(defn compact-tuple-prefix ^bytes [components]
  (tuple-prefix-with encode-compact components))

(defn- read-key! [^ByteBuffer in]
  (case (bit-and 255 (.get in))
    1 nil 2 false 3 true
    16 (let [out (StringBuilder.)]
         (loop []
           (let [b (bit-and 255 (.get in))]
             (cond
               (zero? b)
               (case (bit-and 255 (.get in))
                 0 (.toString out)
                 255 (do (.append out (char 0)) (recur))
                 (throw (ex-info "Invalid ordered string escape" {})))
               (< b 128) (do (.append out (char b)) (recur))
               (= b 128) (let [c (bit-and 65535 (.getShort in))]
                           (when (< c 128) (throw (ex-info "Noncanonical ordered character" {})))
                           (.append out (char c)) (recur))
               :else (throw (ex-info "Invalid ordered string byte" {}))))))
    32 (bit-xor (.getLong in) Long/MIN_VALUE)
    48 (UUID. (.getLong in) (.getLong in))
    56 (keyword (subs (read-key! in) 1))
    64 (loop [out []]
         (.mark in)
         (if (zero? (.get in)) out
           (do (.reset in) (recur (conj out (read-key! in))))))
    (throw (ex-info "Invalid ordered key type" {}))))

(defn decode-compact
  "Decode one compact ordered value, optionally within a metadata field."
  ([^bytes value] (decode-compact value 0 (alength value)))
  ([^bytes value ^long offset ^long length]
   (try
     (read-key! (ByteBuffer/wrap value offset length))
     (catch BufferUnderflowException _ (throw (EOFException.))))))

(defn descending ^bytes [^bytes key]
  (byte-array (map #(unchecked-byte (bit-xor 255 (bit-and 255 %))) key)))

(defn prefix-end
  "Exclusive upper bound of a byte prefix, or nil when it has no upper bound."
  ^bytes [^bytes prefix]
  (loop [i (dec (alength prefix))]
    (when (>= i 0)
      (if (= 255 (bit-and 255 (aget prefix i))) (recur (dec i))
        (let [end (java.util.Arrays/copyOf prefix (inc i))]
          (aset-byte end i (unchecked-byte (inc (bit-and 255 (aget prefix i)))))
          end)))))

(defn checked ^bytes [^bytes key]
  (when (> (alength key) 511)
    (throw (ex-info "Keyed-fold key exceeds 511 bytes"
                    {:type :grain.read-model/key-too-large :bytes (alength key)})))
  key)
