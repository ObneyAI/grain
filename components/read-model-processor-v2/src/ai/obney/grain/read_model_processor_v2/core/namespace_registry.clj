(ns ai.obney.grain.read-model-processor-v2.core.namespace-registry
  "Atomic cache-local namespace reservations, independent of fold publication."
  (:require [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key])
  (:import [java.nio ByteBuffer]
           [java.security MessageDigest]
           [java.util Arrays]))

(def ^:private registry-prefix (byte-array [75 82 57]))
(def ^:private counter-key (key/join registry-prefix (byte-array [0])))
(def ^:private max-ordinal Integer/MAX_VALUE)

(defn- failure [message data]
  (throw (ex-info message (assoc data :type :grain.read-model/invalid-registry))))

(defn- valid-identity? [identity]
  (and (vector? identity) (= 3 (count identity))
       (uuid? (nth identity 0)) (qualified-keyword? (nth identity 1))
       (pos-int? (nth identity 2))))

(defn- digest [identity]
  (.digest (MessageDigest/getInstance "SHA-256") (key/encode identity)))

(defn- forward-key [identity]
  (key/join registry-prefix (byte-array [1]) (digest identity)))

(defn- ordinal-bytes [ordinal]
  (.array (doto (ByteBuffer/allocate 4) (.putInt (int ordinal)))))

(defn- reverse-key [ordinal]
  (key/join registry-prefix (byte-array [2]) (ordinal-bytes ordinal)))

(defn- address [ordinal]
  (key/join (byte-array [75 70 57]) (ordinal-bytes ordinal)))

(defn- read-value [tx k]
  (when-let [raw ((:get tx) k)]
    (try
      {:value (codec/decode raw)}
      (catch Exception e
        (throw (ex-info "Cannot decode namespace registry entry"
                        {:type :grain.read-model/invalid-registry} e))))))

(defn- counter [tx]
  (when-let [entry (read-value tx counter-key)]
    (let [value (:value entry) n (:next-id value)]
      (when-not (and (map? value) (= #{:next-id} (set (keys value)))
                     (integer? n) (<= 0 n max-ordinal))
        (failure "Invalid namespace registry counter" {}))
      n)))

(defn- lookup [tx identity next-id]
  (when-let [entry (read-value tx (forward-key identity))]
    (let [{stored :identity ordinal :ordinal :as value} (:value entry)]
      (when-not (and (map? value) (= #{:identity :ordinal} (set (keys value)))
                     (valid-identity? stored) (integer? ordinal)
                     (some? next-id) (<= 0 ordinal) (< ordinal next-id))
        (failure "Invalid namespace registry forward entry" {:identity identity}))
      (when-not (= identity stored)
        (throw (ex-info "Namespace identity digest collision"
                        {:type :grain.read-model/namespace-collision :identity identity})))
      (when-not (= value (:value (read-value tx (reverse-key ordinal))))
        (failure "Namespace registry inverse does not match" {:identity identity}))
      (address ordinal))))

(defn- resolve-known [tx identities]
  (let [next-id (counter tx)]
    (reduce (fn [out identity]
              (if-let [prefix (lookup tx identity next-id)]
                (assoc out identity prefix)
                (reduced nil))) {} identities)))

(defn- validate-registry! [tx next-id]
  (let [counts
        ((:reduce tx) {:prefix registry-prefix}
         (fn [counts {:keys [k]}]
           (when (< (alength ^bytes k) 4)
             (failure "Invalid namespace registry address" {}))
           (let [kind (bit-and 255 (aget ^bytes k 3))]
             (case kind
               0 (when-not (Arrays/equals ^bytes k ^bytes counter-key)
                   (failure "Invalid namespace counter address" {}))
               (1 2)
               (let [{stored :identity ordinal :ordinal :as value} (:value (read-value tx k))]
                 (when-not (and (map? value) (= #{:identity :ordinal} (set (keys value)))
                                (valid-identity? stored) (integer? ordinal) (<= 0 ordinal) (< ordinal next-id))
                   (failure "Invalid namespace registry record" {}))
                 (when-not (Arrays/equals ^bytes k ^bytes (if (= kind 1) (forward-key stored) (reverse-key ordinal)))
                   (failure "Namespace registry address does not match its record" {}))
                 (when-not (lookup tx stored next-id)
                   (failure "Namespace registry forward entry is missing" {:identity stored})))
               (failure "Unknown namespace registry entry" {}))
             (update counts kind inc))) {0 0 1 0 2 0})]
    (when-not (= {0 1 1 next-id 2 next-id} counts)
      (failure "Namespace registry counter does not cover its entries" {}))))

(defn- allocate! [tx identities]
  (let [initial (counter tx)
        _ (when (some? initial) (validate-registry! tx initial))
        _ (when (and (nil? initial)
                     ((:reduce tx) {:prefix (byte-array [75 70 57])}
                      (fn [_ _] (reduced true)) false))
            (failure "Namespace registry is missing while materialized data remains" {}))
        _ (when (and (nil? initial)
                     ((:reduce tx) {:prefix registry-prefix}
                      (fn [_ _] (reduced true)) false))
            (failure "Namespace registry counter is missing" {}))]
    (first
     (reduce (fn [[out next-id] identity]
               (if-let [prefix (lookup tx identity next-id)]
                 [(assoc out identity prefix) next-id]
                 (do
                   (when (>= next-id max-ordinal)
                     (throw (ex-info "Namespace ordinal space exhausted"
                                     {:type :grain.read-model/namespace-exhausted})))
                   (when (or ((:get tx) (reverse-key next-id))
                             ((:reduce tx) {:prefix (address next-id)}
                              (fn [_ _] (reduced true)) false))
                     (failure "Namespace ordinal is already assigned" {:ordinal next-id}))
                   (let [entry (codec/encode {:identity identity :ordinal next-id})]
                     ((:put tx) (forward-key identity) entry)
                     ((:put tx) (reverse-key next-id) entry)
                     ((:put tx) counter-key (codec/encode {:next-id (inc next-id)}))
                     [(assoc out identity (address next-id)) (inc next-id)]))))
             [{} (or initial 0)] identities))))

(defn resolve! [cache identities]
  (when-not (and cache (sequential? identities) (every? valid-identity? identities))
    (throw (ex-info "Expected cache and tenant/model/version namespace identities"
                    {:type :grain.read-model/invalid-namespace})))
  (let [identities (vec (distinct identities))]
    (if (empty? identities) {}
      (or (kv/transact cache :read #(resolve-known % identities))
          (kv/transact cache :write #(allocate! % identities))))))
