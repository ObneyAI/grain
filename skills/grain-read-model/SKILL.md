---
name: grain-read-model
description: Define, read or revise a keyed Grain projection with point lookups, indexes and pagination.
---

# Keyed read models

```clojure
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])

(rmp/defreadmodel :inventory items
  {:events #{:inventory/item-renamed}
   :version 1
   :key :id
   :state-schema [:map [:name :string]]
   :indexes {:name (rmp/order-key [:name])}}
  "Latest name per item."
  [item event]
  (assoc item :name (:name event)))

(rmp/value context :inventory/items id)
(rmp/page context :inventory/items {:index :name :page 1 :limit 25})
```

Supply exactly one of `:key` and `:route`. With `:key`, the reducer receives one
instance’s state and the event. Use `:route` to emit zero or more `[id input]`
pairs. The reducer then receives each routed input.

Pass `:tenant-id`, `:event-store` and a started `:cache` in the context. Use
`read-session` when related reads must share a snapshot. Consume reducibles
inside its callback. Bump `:version` when changing what the model folds, stores
or indexes, and update its subscribers. [Keyed read API](../../components/read-model-processor-v2/README.md)
