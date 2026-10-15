(ns ai.obney.grain.fressian-util.interface
  (:require [clojure.data.fressian :as fressian])
  (:import [java.io ByteArrayInputStream]
           [java.time Instant OffsetDateTime]
           [org.fressian FressianWriter]
           [org.fressian.handlers ILookup ReadHandler WriteHandler]
           [org.fressian.impl Codes Handlers]))

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

(def ^:private native-map-write-handler
  (let [^WriteHandler fallback (get (.valAt Handlers/extendedWriteHandlers java.util.Map) "map")]
    (reify WriteHandler
      (write [_ writer value]
        (if (instance? FressianWriter writer)
          (let [^FressianWriter writer writer
                n (* 2 (count value))]
            (.writeTag writer "map" 1)
            ;; Match Fressian's sized-list bytes without allocating an
            ;; alternating key/value ArrayList for each nested map.
            (if (< n 8)
              (.writeCode writer (+ Codes/LIST_PACKED_LENGTH_START n))
              (do (.writeCode writer Codes/LIST)
                  (.writeCount writer n)))
            (reduce-kv (fn [_ k v]
                         (.writeObject writer k)
                         (.writeObject writer v)
                         nil)
                       nil value))
          ;; The published handler lookup also supports other Writer instances.
          (.write fallback writer value))))))

(def ^:private native-map-write-handlers
  {clojure.lang.PersistentArrayMap {"map" native-map-write-handler}
   clojure.lang.PersistentHashMap {"map" native-map-write-handler}
   clojure.lang.PersistentTreeMap {"map" native-map-write-handler}})

(def write-handlers
  "Fressian write-handler lookup extending the standard Clojure handlers with
  java.time.OffsetDateTime and java.time.Instant. Suitable to pass as the
  `:handlers` option to `clojure.data.fressian/write` in user code."
  (let [^ILookup standard (-> (merge fressian/clojure-write-handlers java-time-write-handlers)
                              fressian/associative-lookup
                              fressian/inheritance-lookup)]
    (reify ILookup
      (valAt [_ type]
        ;; Exact classes only: records and custom map subclasses retain their
        ;; existing handlers and iteration semantics.
        (or (get native-map-write-handlers type)
            (.valAt standard type))))))

(def read-handlers
  "Fressian read-handler lookup extending the standard Clojure handlers with
  the java.time tags emitted by `write-handlers`. Suitable to pass as the
  `:handlers` option to `clojure.data.fressian/read` in user code."
  (fressian/associative-lookup
   (merge fressian/clojure-read-handlers java-time-read-handlers)))

(defn- deep-clojurize
  "Recursively convert Java collections to Clojure equivalents.
  clojure.walk/postwalk doesn't recurse into Java collections (they fail coll?),
  so nested structures like sets-of-vectors round-trip as sets-of-ArrayLists.
  Reuse Clojure collections whose keys and children need no conversion."
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
    (reduce-kv (fn [m k v]
                 (let [k' (deep-clojurize k)
                       v' (deep-clojurize v)]
                   ;; After a change, replay later entries in case keys converge.
                   (if (and (identical? m x) (identical? k k') (identical? v v'))
                     m
                     ;; assoc alone can retain an equal, unconverted key.
                     (assoc (if (identical? k k') m (dissoc m k)) k' v'))))
               x x)

    (set? x)
    (reduce (fn [s v]
              (let [v' (deep-clojurize v)]
                (if (identical? v v') s (conj (disj s v) v'))))
            x x)

    (vector? x)
    (reduce-kv (fn [v i child]
                 (let [child' (deep-clojurize child)]
                   (if (identical? child child') v (assoc v i child'))))
               x x)

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
   ;; Bytes are already resident. The convenience read function wraps streams
   ;; in an unnecessary BufferedInputStream for every small result lookup.
   (fressian/read-object
    (fressian/create-reader (ByteArrayInputStream. bytes) :handlers read-handlers))))
