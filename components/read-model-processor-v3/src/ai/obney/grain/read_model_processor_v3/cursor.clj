(ns ai.obney.grain.read-model-processor-v3.cursor
  "Bounded scalar cursor transport. This codec does not order or maintain indexes;
   Datahike owns ordering. Unlike a general object decoder, it cannot allocate
   a collection based on a length claimed by untrusted cursor bytes."
  (:import [java.io ByteArrayOutputStream DataOutputStream ByteArrayInputStream DataInputStream]
           [java.nio.charset StandardCharsets]
           [java.util UUID Arrays]))

(defn- write-text!
  [^DataOutputStream out ^String s]
  (let [bytes (.getBytes s StandardCharsets/UTF_8)]
    (when-not (= s (String. bytes StandardCharsets/UTF_8))
      (throw (ex-info "Index strings must contain valid Unicode; unpaired surrogates are unsupported"
                      {:error :invalid-index-value})))
    (doseq [b bytes]
      (.writeByte out b)
      (when (zero? b)
        (.writeByte out 255)))
    (.writeByte out 0)
    (.writeByte out 0)))

(defn encode
  "Encode scalar continuation values; no database ordering depends on this encoding."
  ^bytes [values]
  (let [bytes (ByteArrayOutputStream.)
        out (DataOutputStream. bytes)]
    (doseq [v values]
      (cond
        (string? v)
        (do
          (.writeByte out 1)
          (write-text! out v))
        (keyword? v)
        (do
          (.writeByte out 2)
          (write-text! out (or (namespace v) ""))
          (write-text! out (name v)))
        (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE))
        (do
          (.writeByte out 3)
          (.writeLong out (bit-xor (long v) Long/MIN_VALUE)))
        (uuid? v)
        (do
          (.writeByte out 4)
          (.writeLong out (.getMostSignificantBits ^UUID v))
          (.writeLong out (.getLeastSignificantBits ^UUID v)))
        :else
        (throw (ex-info "Unsupported ordered index value"
                        {:error :invalid-index-value :value v}))))
    (.toByteArray bytes)))

(defn- read-text!
  [^DataInputStream in]
  (let [out (ByteArrayOutputStream.)]
    (loop []
      (let [b (.readUnsignedByte in)]
        (if (zero? b)
          (case (.readUnsignedByte in)
            0 (.toString out "UTF-8")
            255 (do
                  (.write out 0)
                  (recur))
            (throw (ex-info "Invalid escaped key" {:error :invalid-index-key})))
          (do
            (.write out b)
            (recur)))))))

(defn decode
  [^bytes bytes]
  (with-open [in (DataInputStream. (ByteArrayInputStream. bytes))]
    (loop [out []]
      (if (zero? (.available in))
        out
        (recur
         (conj out
               (case (.readUnsignedByte in)
                 1 (read-text! in)
                 2 (let [ns (read-text! in)
                         n (read-text! in)]
                     (keyword (when-not (empty? ns)
                                ns) n))
                 3 (bit-xor (.readLong in) Long/MIN_VALUE)
                 4 (UUID. (.readLong in) (.readLong in))
                 (throw (ex-info "Unknown index key tag" {:error :invalid-index-key})))))))))
