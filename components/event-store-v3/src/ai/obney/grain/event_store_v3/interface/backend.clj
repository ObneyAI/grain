(ns ai.obney.grain.event-store-v3.interface.backend
  "Shared persistence-envelope preparation for event-store backends."
  (:require [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid])
  (:import [java.time ZoneOffset]
           [java.time.temporal ChronoUnit]))

(defn- next-event-id [previous]
  (loop [candidate (uuid/v7)]
    (if (or (nil? previous) (uuid/< previous candidate))
      candidate
      (recur (uuid/v7)))))

(defn prepare-append
  "Assign final persistence metadata and create the transaction marker for one
   atomic append. Intended only for event-store backend implementations."
  [last-id events tx-metadata]
  (let [timestamp (-> (time/now)
                      (.withOffsetSameInstant ZoneOffset/UTC)
                      (.truncatedTo ChronoUnit/MICROS))
        domain-events (loop [remaining events previous last-id assigned []]
                        (if-let [event (first remaining)]
                          (let [event-id (next-event-id previous)]
                            (recur (next remaining)
                                   event-id
                                   (conj assigned
                                         (assoc event
                                                :event/id event-id
                                                :event/timestamp timestamp))))
                          assigned))
        tx-id (next-event-id (or (:event/id (last domain-events)) last-id))
        tx (cond-> {:event/id tx-id
                    :event/timestamp timestamp
                    :event/type :grain/tx
                    :event/tags #{}
                    :event-ids (set (mapv :event/id domain-events))}
             tx-metadata (assoc :metadata tx-metadata))]
    {:events domain-events
     :events-with-tx (conj domain-events tx)
     :last-event-id tx-id
     :timestamp timestamp}))
