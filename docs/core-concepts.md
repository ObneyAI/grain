# Core Concepts

## Multi-Tenancy

Every event-store operation requires a `:tenant-id`. The processors extract it from the context map and pass it through automatically:

```clojure
;; Tenant ID flows through context — Grain doesn't care where it comes from.
;; Typically injected by middleware (e.g., from a JWT claim).
(def context {:event-store event-store
              :tenant-id  #uuid "..."
              :command    command})
```

The Postgres backend (`grain-event-store-postgres-v3`) enforces tenant isolation at the database level with Row-Level Security policies and per-tenant advisory locks.

## Commands (Write Side)

Commands change state by generating events:

```clojure
(defcommand :example create-counter
  {:authorized? (constantly true)}
  "Creates a new counter."
  [context]
  (let [id (random-uuid)
        name (get-in context [:command :name])]
    {:command-result/events
     [(->event {:type :example/counter-created
                :body {:counter-id id :name name}})]
     :command/result {:counter-id id}}))
```

## Events

Events are immutable facts about what happened:

```clojure
{:event/type :example/counter-created
 :event/id #uuid "..."           ; UUID v7
 :event/timestamp #inst "..."
 :event/tags #{[:counter #uuid "..."]}  ; for efficient querying
 :counter-id #uuid "..."               ; body fields are merged
 :name "My Counter"}                    ; directly into the event
```

**Always use `->event` to construct events.** It generates UUID v7 IDs (time-ordered) required by the event store for correct ordering and deduplication. Never construct event maps manually with `java.util.UUID/randomUUID` — events with v4 UUIDs will be silently misordered or lost.

## Queries (Read Side)

Queries read from projections without causing state changes:

```clojure
(defquery :example counters
  {:authorized? (constantly true)}
  "Returns all counters."
  [context]
  {:query/result (read-models/counters context)})
```

## Authorization

Commands and queries support an `:authorized?` predicate in their registry opts. This function receives the full context (including the `:command` or `:query` map, `:event-store`, and any application-specific keys) and must return `true` to allow execution.

```clojure
(defcommand :example create-counter
  {:authorized? (fn [context]
                  (some? (get-in context [:command :user-id])))}
  [context]
  ...)
```

Authorization is enforced at the adapter level (request handlers, Datastar) before the command or query processor runs. The behavior is **deny by default**: if `:authorized?` is missing or returns a non-`true` value, the request is rejected. Every command and query must have an `:authorized?` predicate to be executable via an adapter.

## Todo Processors

Todo processors react to events asynchronously. They subscribe to event types and run whenever matching events are appended:

```clojure
(defprocessor :billing charge-membership
  {:topics #{:billing/membership-due}}
  "Charges a member's payment method when their membership is due."
  [context]
  (let [event (:event context)
        member-id (:member-id event)]
    {:result/effect (fn [] (stripe/charge! member-id))
     :result/checkpoint :after
     :result/on-success [(es/->event {:type :billing/membership-charged
                                       :body {:member-id member-id}})]}))
```

The handler receives a context with `:event`, `:event-store`, and `:tenant-id`. Return one of:

| Return map | Semantics | Checkpointing |
| --- | --- | --- |
| `{:result/events [...]}` | Pure — no side effects | Batch checkpointed (one checkpoint per poll cycle) |
| `{:result/effect fn, :result/checkpoint :before}` | At-most-once — checkpoint first, then run effect | Per-event |
| `{:result/effect fn, :result/checkpoint :after}` | At-least-once — run effect first, then checkpoint | Per-event |
| `{}` | No-op — acknowledge and move on | Per-event |

Checkpointing uses CAS (Compare-and-Swap) on the event store, so the same event is never processed twice even across lease transfers between nodes.

## Periodic Tasks

Periodic tasks run on a schedule and emit trigger events for each tenant. CAS deduplication ensures only one trigger per schedule tick across all nodes:

```clojure
(defperiodic :billing daily-membership-check
  {:schedule {:cron "0 0 * * *"}}
  "Emits a billing trigger for each tenant once per day."
  [tenant-id time]
  (let [period (.toString (.toLocalDate time))]
    {:result/events
     [(es/->event {:type :billing/membership-due
                   :body {:period period}})]
     :result/cas
     {:types #{:billing/membership-due}
      :predicate-fn (fn [existing]
                      (not (some #(= period (:period %))
                                 (into [] existing))))}}))
```

Every node runs the schedule independently. The CAS predicate ensures only the first node to append wins — others get a silent conflict. A separate `defprocessor` handles the trigger events (see the billing example above).

Schedule options:

```clojure
{:schedule {:cron "0 0 * * *"}}                    ; UNIX cron
{:schedule {:every 30 :duration :seconds}}          ; interval
{:schedule {:every 5 :duration :minutes}}
```

## Read Models / Projections

Read models are pure reducer functions `(state, event) -> state` that project event streams into queryable state. The processor handles caching, incremental updates, and multi-tenant isolation automatically.

### Defining Read Models

The `defreadmodel` macro defines and registers read model reducers, following the same pattern as `defcommand` and `defquery`:

```clojure
(defreadmodel :example counters
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1}
  "Reducer for counter read model."
  [state event]
  (let [{:event/keys [type] :keys [counter-id name]} event]
    (case type
      :example/counter-created
      (assoc state counter-id {:id counter-id :name name :value 0})
      :example/counter-incremented
      (update-in state [counter-id :value] inc)
      state)))
```

Project it by name anywhere you have a context:

```clojure
(rmp/project context :example/counters)
```

### Two-Tier Caching

Projections use a two-tier cache inspired by [Datomic's object cache](https://docs.datomic.com/operation/caching.html):

**L1 (in-process):** Deserialized Clojure maps held in a global LRU atom. Zero serialization cost on read. Configurable TTL controls the freshness/speed tradeoff:

| TTL | Behavior | Use Case |
| --- | --- | --- |
| 0 (default) | Always checks event store for new events | Real-time consistency |
| > 0 | Skips all I/O within TTL window (< 0.2ms) | Pagination, filter changes |

**L2 (LMDB):** [Fressian](https://github.com/clojure/data.fressian)-serialized state on disk. Survives process restarts. Watermark-based incremental updates — on each call, the processor reads only events *after* the last watermark.

Three L2 storage strategies are selected automatically:

| Strategy | Trigger | Description |
| --- | --- | --- |
| **Monolithic** | < 10K keys | Single LMDB entry per read model |
| **Segmented** | >= 10K keys | 64 hash-based segments; only changed segments are written back |
| **Partitioned** | `partition-fn` + `entity-id-fn` in opts | Per-partition cache entries with a global manifest (see below) |

L2 write-back is deferred until >= 10 new events have been processed, reducing I/O for rapidly changing state.

Configure L1 TTL per read model:

```clojure
(defreadmodel :example counters
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1
   :l1-ttl-ms 1000}  ;; 1s TTL — pagination clicks return in < 0.2ms
  [state event]
  ...)
```

Manage the L1 cache programmatically:

```clojure
(rmp/l1-stats)   ;; => {:entries 42}
(rmp/l1-clear!)  ;; clears all L1 entries (e.g., after deployment)
```

### Tenant-Isolated Cache Keys

Cache keys include the `tenant-id` from the context, ensuring strict multi-tenant isolation at both cache tiers. Two tenants projecting the same read model will never share a cache entry.

### Scoped Projections

The optional `scope` map on `project` supports three patterns:

```clojure
;; Filter events by tag set
(rmp/project context :example/counters {:tags #{[:counter counter-id]}})

;; Override the event query entirely
(rmp/project context :example/counters {:queries [{:types #{:example/counter-created}
                                                   :tags #{[:counter counter-id]}}]})

;; Single partition read (partitioned models only — see below)
(rmp/project context :inventory/items {:partition-key location-id})
```

The scope is hashed into the cache key, so different scopes maintain independent caches.

### Partitioned Read Models

For datasets that naturally partition (e.g., items by location, patients by clinic), supply `:partition-fn` and `:entity-id-fn` in the opts:

```clojure
(defreadmodel :inventory items
  {:events       #{:inventory/item-created :inventory/item-moved}
   :version      1
   :partition-fn  (fn [entity] (:location-id entity))
   :entity-id-fn :item-id}
  [state event]
  ...)
```

- **`partition-fn`** — `(entity -> partition-key)`. Operates on entity *state* (not events). Determines which partition an entity belongs to.
- **`entity-id-fn`** — `(event -> entity-id)`. Extracts the entity identifier from an event. Used to route events to the correct partition.

**How it works:**

1. Each partition is stored as a separate LMDB entry with its own state map.
2. A manifest tracks all partition keys and a global watermark.
3. On projection, new events are routed through the reducer and assigned to partitions via `partition-fn`.
4. **Cross-partition moves** are detected automatically: when `partition-fn` returns a different key after applying an event, the entity is evicted from the source partition and inserted into the destination.
5. A transient in-memory `entity-id -> partition-key` lookup provides O(1) routing during projection.

**Reading a single partition** is much cheaper than projecting the full model — only events relevant to that partition are processed:

```clojure
;; All partitions merged (full projection)
(rmp/project context :inventory/items)

;; Single partition (incremental, no full replay)
(rmp/project context :inventory/items {:partition-key location-id})
```

If no cache exists yet, a single-partition read triggers a full projection first (to build the partition manifest), then serves subsequent reads incrementally.
