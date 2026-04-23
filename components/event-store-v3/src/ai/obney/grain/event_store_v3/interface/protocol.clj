(ns ai.obney.grain.event-store-v3.interface.protocol
  (:refer-clojure :exclude [read]))

(defmulti start-event-store #(get-in % [:conn :type]))

(defprotocol EventStore
  (start [this])
  (stop [this])

  (append [this args]
    "Append a series of events to the event store.

     args:

     A map with the following keys:

     :tenant-id - A UUID identifying the tenant. Required.

     :events - A vector of events to append to the event store.

     :tx-metadata - An optional map of metadata to associate with the transaction.

     :cas - An optional map with the following keys:

       :tags  - A set of tags to filter events by. Each tag is a tuple of entity type and entity ID.

       :types - A set of event types to filter events by.

       :as-of - A UUID v7 event id to filter events that occurred before or at this time.

       :after - A UUID v7 event id to filter events that occurred after this time.

       :predicate-fn - A function with signature [events] that returns true or false, deciding whether the events will be stored or not.")

  (tenants [this]
    "Returns a map {tenant-id tenant-metadata} for every tenant known
     to the store. The metadata map currently contains:

       :tenant/last-event-id - UUID or nil. The durable high-watermark
                               maintained on append (matches
                               grain.tenants.last_event_id). nil when
                               the tenant row exists but no events
                               have been committed.

     Additional keys may be added in the future. Callers must not
     assume the set of keys is closed.

     Administrative metadata, not a cross-tenant event read.
     Must not require a transaction.")

  (read [this args]
    "Read an ordered stream of events from the event store.

     Returns a reducible (IReduceInit + IReduce) that streams events without eagerly loading them all into memory.

     If no tags or types are provided, all events for the tenant are returned.

     Cannot supply both :as-of and :after at the same time.

     All queries must include the same :tenant-id. Cross-tenant reads are forbidden.

     May return a cognitect anomaly.

     Usage:
     - (reduce f init (read store query))         ; Direct reduction
     - (transduce xf f init (read store query))   ; Transducer pipeline
     - (into [] (take 10) (read store query))     ; Collect with limit

     args:

     Either a single query map or a vector of query maps (batch query).
     When a vector is provided, results from all queries are merged, deduplicated
     by :event/id, and ordered by :event/id. Each query map has the following keys:

     :tenant-id - A UUID identifying the tenant. Required.

     :tags - A set of tags to filter events by. Each tag is a tuple of entity type and entity ID.

     :types - A set of event types to filter events by.

     :as-of - A UUID v7 event id to filter events that occurred before or at this time.

     :after - A UUID v7 event id to filter events that occurred after this time."))
