(ns ai.obney.grain.fressian-util.interface
  (:require [clojure.data.fressian :as fressian])
  (:import [java.io ByteArrayInputStream]
           [java.time Instant OffsetDateTime]
           [org.fressian.handlers ReadHandler WriteHandler]))

(def ^:private java-time-write-handlers
  {OffsetDateTime
   {"grain/odt"
    (reify WriteHandler
      (write [_ w odt]
        (.writeTag w "grain/odt" 1)
        (.writeObject w (.toString ^OffsetDateTime odt))))}
   Instant
   {"grain/instant"
    (reify WriteHandler
      (write [_ w inst]
        (.writeTag w "grain/instant" 1)
        (.writeObject w (.toString ^Instant inst))))}})

(def ^:private java-time-read-handlers
  {"grain/odt"
   (reify ReadHandler
     (read [_ rdr _tag _component-count]
       (OffsetDateTime/parse ^CharSequence (.readObject rdr))))
   "grain/instant"
   (reify ReadHandler
     (read [_ rdr _tag _component-count]
       (Instant/parse ^CharSequence (.readObject rdr))))})

(def write-handlers
  "Fressian write-handler lookup extending the standard Clojure handlers with
  java.time.OffsetDateTime and java.time.Instant. Suitable to pass as the
  `:handlers` option to `clojure.data.fressian/write` in user code."
  (-> (merge fressian/clojure-write-handlers java-time-write-handlers)
      fressian/associative-lookup
      fressian/inheritance-lookup))

(def read-handlers
  "Fressian read-handler lookup extending the standard Clojure handlers with
  the java.time tags emitted by `write-handlers`. Suitable to pass as the
  `:handlers` option to `clojure.data.fressian/read` in user code."
  (fressian/associative-lookup
   (merge fressian/clojure-read-handlers java-time-read-handlers)))

(defn- deep-clojurize
  "Recursively convert Java collections to Clojure equivalents.
  clojure.walk/postwalk doesn't recurse into Java collections (they fail coll?),
  so nested structures like sets-of-vectors round-trip as sets-of-ArrayLists."
  [x]
  (cond
    (and (instance? java.util.Map x) (not (map? x)))
    (persistent!
     (reduce (fn [m e] (assoc! m (deep-clojurize (.getKey ^java.util.Map$Entry e))
                               (deep-clojurize (.getValue ^java.util.Map$Entry e))))
             (transient {}) x))

    (and (instance? java.util.Set x) (not (set? x)))
    (persistent!
     (reduce (fn [s v] (conj! s (deep-clojurize v)))
             (transient #{}) x))

    (and (instance? java.util.List x) (not (vector? x)))
    (mapv deep-clojurize x)

    (map? x)
    (persistent!
     (reduce-kv (fn [m k v] (assoc! m (deep-clojurize k) (deep-clojurize v)))
                (transient {}) x))

    (set? x)
    (persistent!
     (reduce (fn [s v] (conj! s (deep-clojurize v)))
             (transient #{}) x))

    (vector? x)
    (mapv deep-clojurize x)

    :else x))

(defn encode
  "Fressian-encode `data` to a byte array, using grain's custom handlers
  (java.time.OffsetDateTime and java.time.Instant are supported in addition
  to the standard Clojure types)."
  ^bytes [data]
  (let [^java.nio.ByteBuffer buf (fressian/write data :handlers write-handlers)
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn decode
  "Fressian-decode a byte array produced by `encode`. Nested java.util
  collections are converted to Clojure equivalents."
  [^bytes bytes]
  (deep-clojurize
   (fressian/read (ByteArrayInputStream. bytes) :handlers read-handlers)))
