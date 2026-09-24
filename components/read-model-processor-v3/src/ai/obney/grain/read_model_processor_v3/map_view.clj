(ns ai.obney.grain.read-model-processor-v3.map-view
  "Clojure map operations backed by projection snapshot state."
  (:import [clojure.lang APersistentMap IKVReduce IReduceInit IObj MapEntry SeqIterator]))

(definterface EngineView
  (engineState []))

(def ^:private missing (Object.))

(defn view
  "Wrap state with map operations and optional metadata."
  ([state ops]
   (view state ops nil))
  ([state ops annotations]
   (proxy [APersistentMap IKVReduce IReduceInit IObj EngineView] []
     (meta []
       annotations)

     (withMeta [m]
       (view state ops m))

     (engineState []
       state)

     (valAt
       ([k]
        ((:get ops) state k nil))
       ([k fallback]
        ((:get ops) state k fallback)))

     (containsKey [k]
       (not (identical? missing ((:get ops) state k missing))))

     (entryAt [k]
       (let [v ((:get ops) state k missing)]
         (when-not (identical? missing v)
           (MapEntry/create k v))))

     (assoc [k v]
       (view ((:assoc ops) state k v) ops annotations))

     (assocEx [k v]
       (when (contains? this k)
         (throw (ex-info "Existing key" {})))
       (assoc this k v))

     (without [k]
       (view ((:dissoc ops) state k) ops annotations))

     (count []
       ((:count ops) state))

     (empty []
       (with-meta {} annotations))

     (seq []
       (seq (map (fn [[k v]]
                   (MapEntry/create k v))
                 ((:entries ops) state))))

     (iterator []
       (SeqIterator. (seq this)))

     (kvreduce [rf init]
       (reduce (fn [a [k v]]
                 (rf a k v))
               init
               ((:entries ops) state)))

     (reduce [rf init]
       (reduce rf init (seq this))))))
