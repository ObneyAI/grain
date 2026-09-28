(ns ai.obney.grain.read-model-processor-v2.instance-ids
  "Stable model-local ordinals shared by text postings and rank summaries."
  (:require [ai.obney.grain.read-model-processor-v2.fold-key :as key]
            [ai.obney.grain.fressian-util.interface :as codec])
  (:import [java.nio ByteBuffer]))

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
