(ns ai.obney.grain.event-store-v2.interface.protocol
  (:refer-clojure :exclude [read]))

(defmulti start-event-store #(get-in % [:conn :type]))

(defprotocol EventStore
  (start [this])
  (stop [this])

  (append [this args]
    "Append a series of events to the event store.
     
     args: 
     
     A map with the following keys:
     
     :events - A vector of events to append to the event store.

     :tx-metadata - An optional map of metadata to associate with the transaction.
     
     :cas - An optional map with the following keys:
     
       :tags  - A set of tags to filter events by. Each tag is a tuple of entity type and entity ID.
     
       :types - A set of event types to filter events by.
     
       :as-of - A UUID v7 event id to filter events that occurred before or at this time.
     
       :after - A UUID v7 event id to filter events that occurred after this time.
     
       :predicate-fn - A function with signature [events] that returns true or false, deciding whether the events will be stored or not.")

  (read [this args]
    "Read an ordered stream of events from the event store.
     
     Returns a reducible (IReduceInit + IReduce) that streams events without eagerly loading them all into memory.
     
     If no tags or types are provided, all events are returned.

     Cannot supply both :as-of and :after at the same time.

     May return a cognitect anomaly.
     
     Usage:
     - (reduce f init (read store query))         ; Direct reduction
     - (transduce xf f init (read store query))   ; Transducer pipeline
     - (into [] (take 10) (read store query))     ; Collect with limit
     
     args:
     
     A map with the following optional keys:
     
     :tags - A set of tags to filter events by. Each tag is a tuple of entity type and entity ID.

     :types - A set of event types to filter events by.
     
     :as-of - A UUID v7 event id to filter events that occurred before or at this time.
     
     :after - A UUID v7 event id to filter events that occurred after this time."))