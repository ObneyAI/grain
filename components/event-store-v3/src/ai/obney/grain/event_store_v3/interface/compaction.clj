(ns ai.obney.grain.event-store-v3.interface.compaction
  "Privileged retention capability implemented by event-store backends.

   This namespace is intentionally separate from EventStore: ordinary
   application code can append and read, but cannot compact history."
  (:require [clojure.set :as set])
  (:import [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util UUID]))

(def system-tenant-id
  #uuid "00000000-0000-0000-0000-000000000001")

(def policy-activated-type :grain.retention/policy-activated)
(def policy-deactivated-type :grain.retention/policy-deactivated)
(def compaction-receipt-type :grain.retention/compacted)

(def protected-event-types
  #{:grain/tx
    policy-activated-type
    policy-deactivated-type
    compaction-receipt-type})

(defn event-type-tag-id
  "Stable UUID used only to index policy lifecycle events by event type."
  [event-type]
  (UUID/nameUUIDFromBytes
   (.getBytes (str "grain-retention:" event-type) StandardCharsets/UTF_8)))

(defn policy-tag [event-type]
  [:grain.retention/event-type (event-type-tag-id event-type)])

(defn lifecycle-event? [event]
  (contains? #{policy-activated-type policy-deactivated-type}
             (:event/type event)))

(defn active-activation
  "Project the latest durable lifecycle event. Returns nil when inactive."
  [events event-type]
  (let [event (->> (into [] events)
                   (filter lifecycle-event?)
                   (filter #(= event-type (:retention/event-type %)))
                   (sort-by :event/id)
                   last)]
    (when (= policy-activated-type (:event/type event))
      {:event/type event-type
       :policy (:retention/policy event)
       :activation/id (:event/id event)
       :activated-at (:event/timestamp event)})))

(defn activation-matches?
  [events {:keys [event/type policy activation/id]}]
  (let [active (active-activation events type)]
    (and active
         (= id (:activation/id active))
         (= policy (:policy active)))))

(defn cutoff
  [now {:keys [seconds nanos]}]
  (.minus now (Duration/ofSeconds seconds nanos)))

(defn retention-key
  "Return the composite retention key, or nil when a required tag is missing
   or ambiguous. Additional tags are irrelevant."
  [event tag-names]
  (when tag-names
    (let [by-name (group-by first (:event/tags event))]
      (when (every? #(= 1 (count (get by-name %))) tag-names)
        (into (sorted-map)
              (map (fn [tag-name]
                     [tag-name (second (first (get by-name tag-name)))])
                   tag-names))))))

(defn eligible-events
  "Pure conservative selection shared by backend implementations."
  [events event-type policy cutoff-time limit]
  (let [tag-names (get-in policy [:keep-latest-per :tags])
        matching (filter #(= event-type (:event/type %)) events)
        newest-ids (when tag-names
                     (->> matching
                          (keep (fn [event]
                                  (when-let [key (retention-key event tag-names)]
                                    [key event])))
                          (group-by first)
                          vals
                          (map (fn [entries]
                                 (:event/id
                                  (second
                                   (last (sort-by (comp :event/id second)
                                                  entries))))))
                          set))]
    (->> matching
         (filter #(.isBefore (:event/timestamp %) cutoff-time))
         (filter #(or (nil? tag-names) (retention-key % tag-names)))
         (remove #(contains? newest-ids (:event/id %)))
         (sort-by :event/id)
         (take limit)
         vec)))

(defprotocol EventCompaction
  (estimate [this request]
    "Advisory eligibility estimate; never authorizes or deletes history.")
  (compact! [this request]
    "Atomically recheck activation, delete one bounded batch, and append its
     permanent receipt and transaction marker."))
