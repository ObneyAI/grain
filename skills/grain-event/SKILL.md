---
name: grain-event
description: Define Grain event types with defevent, emit instances, and keep payload changes compatible with replay.
---

# Grain events

Define every event type with `defevent`, including scheduled triggers and processor
outcomes. Put the Malli payload schema on the declaration. Load its namespace before
emitting the event.

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])

(es/defevent :inventory/item-renamed
  "An inventory item received a new name."
  {:schema [:map [:id :uuid] [:name :string]]})

(defn item-renamed [id new-name]
  (es/->event {:type :inventory/item-renamed
               :tags #{[:item id]}
               :body {:id id :name new-name}}))
```

Consumers read `:id` and `:name` directly from the event. The store assigns
`:event/id` and `:event/timestamp` at append.

Keep the event type stable and test payload changes against previously stored
events. Match the event’s `:schema` in the service’s Event Model. Omit `:history`
for complete history. [Bounded history](../../docs/event-definitions-and-retention.md)
