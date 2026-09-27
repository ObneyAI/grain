(ns ai.obney.grain.read-model-processor-v2.fold-key
  "Self-delimiting ordered keys for local keyed-fold storage."
  (:import [java.io ByteArrayOutputStream DataOutputStream]
           [java.security MessageDigest]
           [java.util UUID]))

(defn join ^bytes [& arrays]
  (let [out (ByteArrayOutputStream.)]
    (doseq [^bytes a arrays] (.write out a 0 (alength a)))
    (.toByteArray out)))

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
  (when-not (and (uuid? (:tenant-id context))
                 (some? (:event-source context))
                 (or (string? (:event-source context)) (keyword? (:event-source context))
                     (uuid? (:event-source context))))
    (throw (ex-info "Keyed folds require a UUID :tenant-id and stable :event-source identifier"
                    {:model name})))
  (join (byte-array [75 70 49])
        (.digest (MessageDigest/getInstance "SHA-256")
                 (encode [(:event-source context) (:tenant-id context) name version]))))

(defn checked ^bytes [^bytes key]
  (when (> (alength key) 511)
    (throw (ex-info "Keyed-fold key exceeds 511 bytes"
                    {:type :grain.read-model/key-too-large :bytes (alength key)})))
  key)
