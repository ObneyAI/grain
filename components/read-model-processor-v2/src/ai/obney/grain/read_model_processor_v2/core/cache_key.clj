(ns ai.obney.grain.read-model-processor-v2.core.cache-key
  "Value fingerprints for legacy scope and partition cache identities."
  (:require [ai.obney.grain.fressian-util.interface :as codec])
  (:import [java.security MessageDigest]
           [java.util Arrays HexFormat IdentityHashMap]))

(defn- ordered [encode key-fn values]
  ;; Encode each comparison key once. Maps and sets have no iteration order.
  (let [values (vec values)]
    (if (< (count values) 2) values
      (->> values
           (mapv #(vector (encode (key-fn %)) %))
           (sort (fn [[a] [b]] (Arrays/compareUnsigned ^bytes a ^bytes b)))
           (mapv second)))))

(defn- canonical [value]
  ;; Query unions commonly share the same event-type set. Reuse its normalized
  ;; form within this call, without introducing a long-lived identity cache.
  (let [seen (IdentityHashMap.)
        encoded (IdentityHashMap.)]
    (letfn [(comparison-key [x]
              (or (.get encoded x)
                  (let [bytes (codec/encode x)]
                    (.put encoded x bytes)
                    bytes)))
            (form [x]
              (or (.get seen x)
                  (let [v (cond
                            (record? x) [:record (.getName (class x)) (ordered comparison-key first (map (fn [[k v]] [(form k) (form v)]) x))]
                            (instance? java.util.Map x) [:map (ordered comparison-key first (map (fn [[k v]] [(form k) (form v)]) x))]
                            (instance? java.util.Set x) [:set (ordered comparison-key identity (map form x))]
                            (or (sequential? x) (instance? java.util.List x)) [:sequence (mapv form x)]
                            (integer? x) (if (<= Long/MIN_VALUE x Long/MAX_VALUE) (long x) (bigint x))
                            (instance? java.math.BigDecimal x) (.stripTrailingZeros ^java.math.BigDecimal x)
                            (instance? Float x) (double x)
                            :else x)]
                    (.put seen x v)
                    v)))]
      (form value))))

(defn fingerprint [value]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256")
                       (codec/encode (canonical value)))))
