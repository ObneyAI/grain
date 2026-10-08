(ns ai.obney.grain.read-model-processor-v2.core.fold-key
  "Self-delimiting ordered keys for local keyed-fold storage."
  (:import [java.io ByteArrayOutputStream DataOutputStream EOFException]
           [java.nio ByteBuffer BufferUnderflowException]
           [java.security MessageDigest]
           [java.util UUID]))

(defn join ^bytes [& arrays]
  (let [out (byte-array (reduce (fn [size ^bytes a] (+ size (alength a))) 0 arrays))]
    (reduce (fn [offset ^bytes a]
              (System/arraycopy a 0 out offset (alength a))
              (+ offset (alength a)))
            0 arrays)
    out))

(defn- write-key! [^DataOutputStream out value]
  (cond
    (nil? value) (.writeByte out 1)
    (boolean? value) (.writeByte out (if value 3 2))
    (string? value)
    (do (.writeByte out 16)
        (dotimes [i (.length ^String value)]
          (let [c (int (.charAt ^String value i))
                hi (bit-shift-right c 8)
                lo (bit-and 255 c)]
            (.writeByte out hi) (when (zero? hi) (.writeByte out 255))
            (.writeByte out lo) (when (zero? lo) (.writeByte out 255))))
        (.writeShort out 0))
    (and (integer? value) (<= Long/MIN_VALUE value Long/MAX_VALUE))
    (do (.writeByte out 32) (.writeLong out (bit-xor (long value) Long/MIN_VALUE)))
    (uuid? value)
    (do (.writeByte out 48)
        (.writeLong out (.getMostSignificantBits ^UUID value))
        (.writeLong out (.getLeastSignificantBits ^UUID value)))
    (keyword? value)
    (do (.writeByte out 56) (write-key! out (str value)))
    (vector? value)
    (do (.writeByte out 64) (doseq [v value] (write-key! out v)) (.writeByte out 0))
    :else (throw (ex-info "Unsupported keyed-fold key" {:value value}))))

(defn encode ^bytes [value]
  (let [out (ByteArrayOutputStream.)]
    (write-key! (DataOutputStream. out) value)
    (.toByteArray out)))

(defn tuple-prefix
  "Encode complete leading vector components, omitting only the outer terminator."
  ^bytes [components]
  (when-not (vector? components)
    (throw (ex-info "Tuple prefix must be a vector" {:prefix components})))
  (let [encoded (encode components)]
    (java.util.Arrays/copyOf encoded (dec (alength encoded)))))

(defn- read-key! [^ByteBuffer in]
  (case (bit-and 255 (.get in))
    1 nil 2 false 3 true
    16 (let [out (StringBuilder.)
             read-unit (fn [] (let [b (bit-and 255 (.get in))]
                               (if (zero? b)
                                 (case (bit-and 255 (.get in)) 0 -1 255 0
                                   (throw (ex-info "Invalid ordered string escape" {}))) b)))]
         (loop []
           (let [hi (read-unit)]
             (if (= -1 hi) (.toString out)
               (let [lo (read-unit)]
                 (when (= -1 lo) (throw (ex-info "Truncated ordered character" {})))
                 (.append out (char (+ (bit-shift-left hi 8) lo))) (recur))))))
    32 (bit-xor (.getLong in) Long/MIN_VALUE)
    48 (UUID. (.getLong in) (.getLong in))
    56 (keyword (subs (read-key! in) 1))
    64 (loop [out []]
         (.mark in)
         (if (zero? (.get in)) out
           (do (.reset in) (recur (conj out (read-key! in))))))
    (throw (ex-info "Invalid ordered key type" {}))))

(defn decode
  "Decode one ordered value, optionally within a compact metadata field."
  ([^bytes value] (decode value 0 (alength value)))
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

(defn prefix ^bytes [context name version]
  (when-not (uuid? (:tenant-id context))
    (throw (ex-info "Keyed folds require a UUID :tenant-id"
                    {:model name})))
  ;; KF5 addresses models by tenant and version within the configured cache.
  (join (byte-array [75 70 53])
        (.digest (MessageDigest/getInstance "SHA-256")
                 (encode [(:tenant-id context) name version]))))

(defn checked ^bytes [^bytes key]
  (when (> (alength key) 511)
    (throw (ex-info "Keyed-fold key exceeds 511 bytes"
                    {:type :grain.read-model/key-too-large :bytes (alength key)})))
  key)
