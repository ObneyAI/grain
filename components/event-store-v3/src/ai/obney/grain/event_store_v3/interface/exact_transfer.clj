(ns ai.obney.grain.event-store-v3.interface.exact-transfer
  "Optional one-tenant, point-in-time transfer; not a live cutover."
  (:require [ai.obney.grain.fressian-util.interface :as fressian]
            [clj-uuid :as uuid]
            [clojure.set :as set])
  (:import [java.time OffsetDateTime Instant ZoneOffset]
           [java.time.temporal ChronoUnit]
           [java.util UUID Arrays]))

(defprotocol TenantSnapshot
  (snapshot [store tenant-id] "Capture one complete tenant and its watermark."))
(defprotocol TenantRestore
  (restore! [store snapshot] "Restore a snapshot into a wholly empty store."))

(defn- fail [message] (throw (ex-info message {})))
(def ^:private bytes-class (Class/forName "[B"))
(defn- copy-value [v]
  (cond
    (instance? bytes-class v) (aclone ^bytes v)
    (or (nil? v) (string? v) (keyword? v) (symbol? v) (boolean? v)
        (number? v) (uuid? v) (instance? OffsetDateTime v) (instance? Instant v)) v
    (vector? v) (mapv copy-value v)
    (map? v) (into {} (map (fn [[k x]] [(copy-value k) (copy-value x)])) v)
    (set? v) (set (map copy-value v))
    :else (fail (str "Unsupported snapshot value: " (type v)))))

(defn- same-value? [a b]
  (cond
    (instance? bytes-class a) (and (instance? bytes-class b) (Arrays/equals ^bytes a ^bytes b))
    (and (map? a) (map? b)) (and (= (count a) (count b))
                                  (every? (fn [[k v]]
                                            (some (fn [[k2 v2]] (and (same-value? k k2) (same-value? v v2))) b)) a))
    (and (set? a) (set? b)) (and (= (count a) (count b))
                                  (every? (fn [v] (some #(same-value? v %) b)) a))
    (and (vector? a) (vector? b)) (and (= (count a) (count b)) (every? true? (map same-value? a b)))
    (number? a) (and (number? b) (= (class a) (class b)) (= a b))
    :else (= a b)))

(defn detached [value]
  (let [copy (copy-value value)
        roundtrip (fressian/decode (fressian/encode copy))]
    (when-not (same-value? copy roundtrip) (fail "Snapshot value does not round-trip faithfully"))
    copy))

(defn validate! [{:keys [version tenant-id watermark events] :as snapshot}]
  (when-not (and (= #{:version :tenant-id :watermark :events} (set (keys snapshot)))
                 (= 1 version) (uuid? tenant-id) (vector? events) (seq events))
    (fail "Invalid snapshot"))
  (loop [remaining events previous nil batch []]
    (if (seq remaining)
      (let [event (first remaining) id (:event/id event) timestamp (:event/timestamp event) tags (:event/tags event)
            tx? (= :grain/tx (:event/type event))]
        (when-not (and (map? event) (uuid? id) (= 7 (uuid/get-version id))
                       (or (nil? previous) (uuid/< previous id))
                       (instance? OffsetDateTime timestamp)
                       (= ZoneOffset/UTC (.getOffset ^OffsetDateTime timestamp))
                       (= timestamp (.truncatedTo ^OffsetDateTime timestamp ChronoUnit/MICROS))
                       (keyword? (:event/type event)) (set? tags)
                       (every? #(and (vector? %) (= 2 (count %)) (keyword? (first %)) (uuid? (second %))) tags)
                       (not (contains? event :grain/tenant-id))
                       (not= :grain.retention/compacted (:event/type event)))
          (fail "Invalid persisted event"))
        (when tx?
          (when-not (and (= #{} tags) (set? (:event-ids event))
                         (= (:event-ids event) (set (map :event/id batch)))
                         (every? #(= timestamp (:event/timestamp %)) batch)
                         (or (not (contains? event :metadata)) (map? (:metadata event)))
                         (set/subset? (set (keys event)) #{:event/id :event/timestamp :event/type :event/tags :event-ids :metadata}))
            (fail "Invalid transaction marker")))
        (recur (next remaining) id (if tx? [] (conj batch event))))
      (when-not (and (empty? batch) (= previous watermark) (= :grain/tx (:event/type (peek events))))
        (fail "Incomplete transaction or watermark"))))
  (detached snapshot))
