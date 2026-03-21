# Grain

An Event-Sourced framework for building multi-tenant systems in Clojure.

## What is Grain?

Grain is a framework for building Event-Sourced systems using CQRS (Command Query Responsibility Segregation). It provides composable components that snap together like Lego bricks—start with an in-memory event store for quick iteration, then swap in Postgres with a single line change when you're ready.

Multi-tenancy is built in: every event-store operation is scoped to a `:tenant-id`, and the Postgres backend enforces isolation with Row-Level Security and per-tenant advisory locks.

## Architecture

```
                            ┌────────────────────────────────────────────────────────┐
                            │                      Write Side                        │
                            │                                                        │
  POST /command ───────────▶│  Command Processor ──▶ Validate ──▶ Handler ──▶ Events │
                            │         ▲                  ▲                      │    │
                            └─────────│──────────────────│──────────────────────┼────┘
                                      │                  │                      │
                                      │                  │ read                 │ append
                                      │        ┌─────────┴─────────┐            │
                                      │        │                   │            ▼
            Todo Processors ──────────┘        │    Read Model     │◀───┬───────────┐
                   ▲                           │                   │    │   Event   │
                   │                           └───────────────────┘    │   Store   │
                   │                                     ▲         proj └───────────┘
                   │        ┌────────────────────────────│─────────────┐      │
                   │        │              Read Side     │             │      │ publish
                   │        │                            │             │      ▼
                   │        │  Query Processor ──────────┘             │ ┌─────────┐
  POST /query ─────────────▶│                                          │ │ Pub/Sub │
                   │        └──────────────────────────────────────────┘ └─────────┘
                   │                                                          │
                   └──────────────────────────────────────────────────────────┘
                                                (async)
```

**Commands** are the only path to state change—they validate business rules and emit events. **Events** are immutable facts stored in the event store. **Queries** read from projections (read models) built from events. **Todo Processors** react to events asynchronously, enabling event-driven workflows.

## Core Concepts

### Multi-Tenancy

Every event-store operation requires a `:tenant-id`. The processors extract it from the context map and pass it through automatically:

```clojure
;; Tenant ID flows through context — Grain doesn't care where it comes from.
;; Typically injected by middleware (e.g., from a JWT claim).
(def context {:event-store event-store
              :tenant-id  #uuid "..."
              :command    command})
```

The Postgres backend (`grain-event-store-postgres-v3`) enforces tenant isolation at the database level with Row-Level Security policies and per-tenant advisory locks.

### Commands (Write Side)

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

### Events

Events are immutable facts about what happened:

```clojure
{:event/type :example/counter-created
 :event/id #uuid "..."           ; UUID v7
 :event/timestamp #inst "..."
 :event/tags #{[:counter #uuid "..."]}  ; for efficient querying
 :counter-id #uuid "..."               ; body fields are merged
 :name "My Counter"}                    ; directly into the event
```

### Queries (Read Side)

Queries read from projections without causing state changes:

```clojure
(defquery :example counters
  {:authorized? (constantly true)}
  "Returns all counters."
  [context]
  {:query/result (read-models/counters context)})
```

### Authorization

Commands and queries support an `:authorized?` predicate in their registry opts. This function receives the full context (including the `:command` or `:query` map, `:event-store`, and any application-specific keys) and must return `true` to allow execution.

```clojure
(defcommand :example create-counter
  {:authorized? (fn [context]
                  (some? (get-in context [:command :user-id])))}
  [context]
  ...)
```

Authorization is enforced at the adapter level (request handlers, Datastar) before the command or query processor runs. The behavior is **deny by default**: if `:authorized?` is missing or returns a non-`true` value, the request is rejected. Every command and query must have an `:authorized?` predicate to be executable via an adapter.

### Read Models / Projections

Read models are pure reducer functions `(state, event) -> state` that project event streams into queryable state. The processor handles caching, incremental updates, and multi-tenant isolation automatically.

#### Defining Read Models

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

#### Two-Tier Caching

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

#### Tenant-Isolated Cache Keys

Cache keys include the `tenant-id` from the context, ensuring strict multi-tenant isolation at both cache tiers. Two tenants projecting the same read model will never share a cache entry.

#### Scoped Projections

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

#### Partitioned Read Models

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
5. A transient in-memory `entity-id → partition-key` lookup provides O(1) routing during projection.

**Reading a single partition** is much cheaper than projecting the full model — only events relevant to that partition are processed:

```clojure
;; All partitions merged (full projection)
(rmp/project context :inventory/items)

;; Single partition (incremental, no full replay)
(rmp/project context :inventory/items {:partition-key location-id})
```

If no cache exists yet, a single-partition read triggers a full projection first (to build the partition manifest), then serves subsequent reads incrementally.

### Datastar (Reactive UI)

Grain integrates with [Datastar](https://data-star.dev/) for building reactive server-rendered UIs. Queries that return `:datastar/hiccup` are streamed to the browser over SSE — the server re-renders when domain events fire and Datastar patches the DOM.

```clojure
(defquery :example counter-view
  {:authorized?       (constantly true)
   :datastar/path     "/counters"
   :datastar/title    "Counters"
   :grain/read-models {:example/counters 1}}
  [context]
  (let [counters (rmp/project context :example/counters)]
    {:query/result counters
     :datastar/hiccup [:div#app
                        (for [[id c] counters]
                          [:p (str (:name c) ": " (:value c))])]}))
```

The Datastar component provides three Pedestal interceptor factories:

- **`stream-view`** — Streams `:datastar/hiccup` from a query via SSE. Supports three modes: event-driven (re-renders on domain events), polling (fixed FPS), or one-shot (render once and close).
- **`shim-page`** — Serves an HTML shell that loads the Datastar JS client and connects to a stream endpoint.
- **`action-handler`** — Receives commands from Datastar signals (browser actions), executes them through the command processor, and streams back results or errors.

Auto-generate Pedestal routes from query registry metadata:

```clojure
(require '[ai.obney.grain.datastar.interface :as ds])

(ds/routes context) ;; scans for queries with :datastar/path
```

This creates paired routes for each annotated query — an HTML page route and an SSE stream route — so there's no manual route wiring.

#### Datastar UI Flow

```
                    ┌───────────────────────────────────────────┐
  Browser ◀── SSE ──┤  stream-view ──▶ Query Processor          │
  (Datastar)        │       ▲              │                    │
                    │       │          projections               │
                    │   domain events      │                    │
                    │   (pub/sub)     Read Model Processor       │
                    │                      │                    │
                    │                  Event Store               │
                    │                      ▲                    │
  Browser ── POST ──┤  action-handler ──▶ Command Processor     │
  (signals)         └───────────────────────────────────────────┘
```

## Getting Started

Add to your `deps.edn`:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0028071f79f5fed8ce1cb0677c4f2d0c2e40f76c"
 :deps/root "projects/grain-core-v2"}
```

See `bases/example-base` and `components/example-service` for a complete example application. Run `development/src/example_app_demo.clj` to start and interact with the example system.

## Available Packages

| Package | Summary |
| --- | --- |
| **grain-core-v2** | Multi-tenant CQRS/Event Sourcing with in-memory event store |
| **grain-datastar** | Reactive server-rendered UIs with [Datastar](https://data-star.dev/) over SSE |
| **grain-event-store-postgres-v3** | Multi-tenant Postgres backend with RLS, per-tenant advisory locks, and Fressian serialization |
| **grain-mulog-aws-cloudwatch-emf-publisher** | AWS CloudWatch metrics & dashboards |

<details>
<summary>Package Details</summary>

### grain-core-v2

Multi-tenant CQRS/Event Sourcing with an in-memory event store. Includes v2 processors (command, read-model, todo), v2 request handler, query processor, and pub/sub:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0028071f79f5fed8ce1cb0677c4f2d0c2e40f76c"
 :deps/root "projects/grain-core-v2"}
```

### grain-datastar

Server-rendered reactive UIs with [Datastar](https://data-star.dev/). Streams hiccup-rendered HTML over SSE, with event-driven re-rendering, Malli-based JSON coercion, and automatic Pedestal route generation:

```clojure
obneyai/grain-datastar
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0028071f79f5fed8ce1cb0677c4f2d0c2e40f76c"
 :deps/root "projects/grain-datastar"}
```

Includes the core CQRS components (command/query/read-model processors, event store, pub/sub). See `components/datastar` for the full source.

### grain-event-store-postgres-v3

Multi-tenant Postgres backend with Row-Level Security, per-tenant advisory locks, Fressian binary serialization, and tenant-scoped operations. All read and append operations require a tenant ID, ensuring structural data isolation:

```clojure
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0028071f79f5fed8ce1cb0677c4f2d0c2e40f76c"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

### grain-mulog-aws-cloudwatch-emf-publisher

[mulog](https://github.com/BrunoBonacci/mulog) publisher for CloudWatch metrics:

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0028071f79f5fed8ce1cb0677c4f2d0c2e40f76c"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

</details>

<details>
<summary>Deprecated Packages</summary>

The following packages are deprecated and will be removed in a future release:

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |

</details>

## Why Grain?

We use [Event Modeling and Event Sourcing](https://leanpub.com/eventmodeling-and-eventsourcing) to design [Simple](https://www.youtube.com/watch?v=SxdOUGdseq4) systems. Grain provides a single, composable toolkit for building multi-tenant, event-sourced applications in Clojure.

[Polylith](https://polylith.gitbook.io/polylith) enables us to evolve components independently and publish standalone tools from a single repository.

## Status

Grain is MIT licensed. We use it in production, but it's actively evolving. The core CQRS/Event Sourcing components are stable.

## More Information

- **Examples**: `bases/example-base`, `components/example-service`, `development/src/example_app_demo.clj`
- **Slack**: [#grain](https://clojurians.slack.com/archives/C099K3D7XRV) on Clojurians
- **Issues**: [GitHub Issues](https://github.com/ObneyAI/grain/issues)
