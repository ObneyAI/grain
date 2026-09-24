# Core Concepts

Code samples in this doc assume the following requires are in scope:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface  :refer [defcommand]]
         '[ai.obney.grain.query-processor.interface       :refer [defquery]]
         '[ai.obney.grain.todo-processor-v2.interface     :refer [defprocessor]]
         '[ai.obney.grain.periodic-task.interface         :refer [defperiodic]]
         '[ai.obney.grain.read-model-processor-v3.interface :as rmp :refer [defreadmodel]]
         '[ai.obney.grain.event-store-v3.interface        :refer [->event defevent]])
```

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

Use `->event` to construct event payloads. The event-store backend assigns ordered
UUID v7 IDs and timestamps when appending them. Read-model watermarks use those
persisted IDs.

### Event Definitions

`defevent` optionally registers an event type's schema, documentation, and
history contract. It does not construct events, so existing `->event` call sites
remain unchanged:

```clojure
(defevent :example/counter-created
  "A counter was created."
  {:schema [:map
            [:counter-id :uuid]
            [:name :string]]})
```

Omitting `:history` means permanent, complete history. A bounded history policy
is inert until its exact normalized value is durably activated, and it is
subject to boot validation and stored-data preflight checks. See
[Event Definitions and Retention](event-definitions-and-retention.md) before
making an event type eligible for compaction.

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
     :result/on-success [(->event {:type :billing/membership-charged
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
     [(->event {:type :billing/membership-due
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

`defperiodic` registrations are snapshotted when periodic triggers start. The
current node's next armed fire time can be queried by trigger name:

```clojure
(pt/next-fire-at :billing/daily-membership-check) ; => java.time.Instant or nil
```

The result is `nil` before startup, while the trigger is firing, after it is
stopped, or when the name is unknown. Interval schedules fire immediately at
startup and then at the configured interval. In a cluster this timestamp is
node-local because each node runs its own scheduler.

## Read Models / Projections

The v3 read-model processor folds events through a deterministic reducer
`(state, event) -> state`. Initial state is `{}`. Each processed event commits its
state changes, secondary indexes, and watermark in one Datahike transaction.
Queries catch up to a captured event head before reading a committed snapshot.

Serialized record values, original keys, root values, metadata, and definition
descriptors are stored as raw Fressian bytes. Lookup keys, projection identities,
and partition identifiers also use raw bytes. External cursors use URL-safe
Base64; indexed fields use native scalar types and indexed record IDs use string
ordering. Datahike uses keyword attributes
(`:attribute-refs? false`) to support byte attributes in the pinned version.
Earlier v3 stores using string identifiers or Base64 payloads must be rebuilt
in a fresh storage directory by replaying events; they are not converted automatically.

Catch-up is serialized per tenant, model, version, and query scope. Partition
selections share the catch-up for their underlying projection. A projection's
event fetching or reducer does not hold up unrelated projections or reads from
committed snapshots. Query traversal and `reduce-records` callbacks do not hold
a writer lock. Datahike still serializes database transactions.

The component is included in `grain-core-v2`.

### Store and context

Open one projection store for the application's lifetime and close it at shutdown:

```clojure
(def projection-store
  (rmp/open-store {:storage-dir "data/projections"}))

(def context {:event-store event-store
              :projection-store projection-store
              :tenant-id tenant-id}) ; trusted UUID, supplied by the application

;; At shutdown:
(rmp/close-store! projection-store)
```

LMDB is the default backend. It requires Java 22+ and the native LMDB library;
set `KONSERVE_LMDB_LIB` if the library cannot be discovered. Use `:backend :file`
for Datahike's file backend. Only one store handle/process may own a storage
path. Use a separate path per node in a multi-instance deployment.

A store contains multiple tenants, models, versions, and event scopes. Projection
identity includes the trusted tenant UUID, qualified model name, version, and
scope. Tenant identity comes from context, including for custom event queries.

### Define a projection

The examples assume the event payload schemas are registered with `defevent`
or `defschemas`, as described above.

```clojure
(defreadmodel :example counters
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1}
  "Counter names and values, keyed by counter ID."
  [state event]
  (let [{:event/keys [type] :keys [counter-id name]} event]
    (case type
      :example/counter-created
      (assoc state counter-id {:name name :value 0})
      :example/counter-incremented
      (update-in state [counter-id :value] inc)
      state)))

(rmp/project context :example/counters)
(rmp/record context :example/counters counter-id)
;; => {:item {:id counter-id :value {:name "Visits" :value 3}}
;;     :watermark event-id}
```

`record` returns `:item nil` when the key is absent. A record is a top-level map
entry; its value may be a scalar, map, vector, or another supported serializable
value. Map entries are stored and decoded independently. Non-map root values use
the same store, serialized as one value; `record`, `page`, and `reduce-records`
require a map root.

Reducers may use `get`, `assoc`, `update`, `dissoc`, `seq`, `reduce`, and
`reduce-kv`. They can read and update multiple entries in one event. The returned
map determines the committed changes. Returning an ordinary map replaces the
whole projection; returning `{}` clears it. Storage-backed maps do not support
transients. An event's working set must fit available memory.

Reducers must be deterministic and side-effect free. If an event fails, its
changes and watermark are not committed. Earlier successful events remain
committed; retries resume after their watermark.

### Result lifetime

`project` returns a read-only, storage-backed map for map projections. Looking up
one key decodes that entry. Traversing all entries still reads and decodes the
whole collection. Use `(into {} result)` when an editable, materialized map is
needed.

Committed results retain their snapshot across later updates, storage cleanup,
and store close. Retaining a lazy traversal also retains its snapshot. Release
application references when finished: retained results delay storage reclamation
and final store release. Snapshots protect the database commit, which may include
other models and tenants in the same store.

Intermediate storage-backed maps may only be accessed during their reducer call,
on its thread. Do not retain them or return lazy computations that access them
later. Persisted map metadata must be serializable.

### Secondary indexes and pages

Declare indexes over fields of a map's values:

```clojure
(defreadmodel :admin students
  {:events #{:student/updated :student/deleted}
   :version 1
   :schema [:map-of :string
            [:map [:surname :string] [:status :keyword] [:balance :int]]]
   :indexes {:by-status-name {:fields [:status :surname]}
             :by-balance {:fields [:balance]}}}
  [state event]
  (case (:event/type event)
    :student/deleted (dissoc state (:student-id event))
    :student/updated (assoc state (:student-id event)
                           (select-keys event [:surname :status :balance]))))

(def first-page
  (rmp/page context :admin/students
    {:index :by-status-name :prefix [:active] :limit 25}))
;; => {:items [{:id "s42" :value {:surname "Adams" :status :active :balance 100}} ...]
;;     :watermark event-id
;;     :next-cursor "..."}

(when-let [cursor (:next-cursor first-page)]
  (rmp/page context :admin/students
    {:index :by-status-name :prefix [:active] :limit 25 :after cursor}))
```

Indexes require a `[:map-of id-schema record-schema]` state schema with string or
UUID IDs. Each index has one to six fields. A field is a keyword or a vector path,
for example `:surname` or `[:address :city]`. Every indexed path must be required
and non-nullable, with a string, keyword, signed 64-bit integer, or UUID schema.
Other record fields may contain nested or non-scalar values. Types are derived
from `:schema`. Invalid declarations fail at registration; invalid record updates
fail before commit. A schema is optional when there are no indexes.

Datahike maintains indexes as records change. Pages use ascending native scalar
order, with the string form of the record ID as the final tie-breaker. `:prefix`
matches consecutive leading fields by equality; omitting it traverses the whole
index. There are no descending, arbitrary predicate, or substring-search queries.

`:limit` is a required positive maximum. Resource budgets may produce shorter
pages. Continue while `:next-cursor` is non-nil; an empty or short item count is
not a completion signal. Cursors are opaque and bound to the tenant, model,
version, event scope, rebuild generation, index, and prefix.

Each page is consistent with its own watermark. Later pages catch up again, so
concurrent changes may move records across the cursor boundary. A cursor does
not retain the first page's snapshot for subsequent requests. Catch-up and
rebuilding still process matching events; indexed paging bounds traversal of an
already-current projection.

### Reduce records

`reduce-records` traverses entries in one committed snapshot and honors `reduced`:

```clojure
(rmp/reduce-records context :admin/students
  {:index :by-status-name :prefix [:active]}
  (fn [total {:keys [value]}] (+ total (:balance value)))
  0)
```

Use `{}` as the request to traverse the entire map without a declared index.
Traversal reads records incrementally; the accumulator determines how much data
the caller retains. A full reduction still performs work for every visited entry.

### Event scopes and partitions

All four query operations accept an optional final scope argument:

```clojure
(rmp/project context :example/counters {:tags #{[:counter counter-id]}})

(rmp/project context :example/counters
  {:queries [{:types #{:example/counter-created :example/counter-incremented}
              :tags #{[:counter counter-id]}}]})
```

Tags and custom event queries produce independent projections and watermarks.
A custom query replaces the declaration's event selection. Nil and empty scopes
refer to the same projection.

A map model may declare `:partition-fn`, which maps an entry's value to a partition
key. Query it with `{:partition-key key}`. The reducer still receives the full
projection and processes every matching event. Partition membership changes
atomically with records; partition reads share the projection's watermark.
`:entity-id-fn` is accepted for compatibility but is not used.

`project`, `record`, and unindexed `reduce-records` support partition selection.
An indexed request cannot also supply `:partition-key`. For indexed partition
pages, put the partition field first in the index and supply it in `:prefix`.

### Store options and budgets

| Store option | Default | Meaning |
| --- | --- | --- |
| `:storage-dir` | Required | Local storage directory |
| `:backend` | `:lmdb` | `:lmdb` or `:file` |
| `:map-size` | 16 GiB | LMDB virtual map ceiling |
| `:gc-interval-ms` | 300000 | Automatic storage collection interval |
| `:pin-ttl-ms` | 3600000 | Lease duration for retained committed snapshots |

`(rmp/store-status projection-store)` reports retained commits, lifecycle state,
and maintenance failures. `(rmp/collect! projection-store)` requests storage
collection explicitly. Full collection can be expensive. Datahike's durable-root
API, used to pin and renew retained snapshots, is marked experimental upstream.
The component pins its Datahike dependencies.

Set positive integer budgets in context under `:projection-options`:

| Option | Meaning |
| --- | --- |
| `:batch-events` | Event fetch batch size; default 128 |
| `:batch-bytes` | Encoded event batch limit; each event must fit |
| `:catch-up-ms` | Catch-up time budget |
| `:page-bytes` / `:page-ms` | Page payload/time budgets |
| `:reduce-bytes` / `:reduce-ms` | Reduction payload/time budgets |

Other budgets are unset by default. Events commit separately regardless of fetch
batch size. Page budgets can return a continuation after making progress; an
oversized first record or a time budget exhausted before progress raises an error.
Reduction budget exhaustion raises an error. These budgets are checked between
operations; they do not interrupt a running reducer or database operation.

### Upgrade from v2

1. Require `ai.obney.grain.read-model-processor-v3.interface`.
2. Open a fresh projection store and supply `:projection-store` in context.
3. Remove L1/L2, segment, checkpoint, `:kind`, and `:storage` options and cache
   management calls. V3 rejects obsolete declaration options.
4. Replay the required event history. Existing v2 storage is not imported.

Bump `:version` when changing reducer semantics, schemas, or indexes. Changed
stored declaration descriptors at the same version fail explicitly; reducer
function changes cannot be detected automatically. Rebuilding requires sufficient
event history, including after retention policies have removed events.

Datastar subscriptions, code-agent tools, Event Model runtime validation, and
the control plane use the v3 registry. Supply `:projection-store` to the control
plane and to application contexts that project models. The application owns the
store and closes it after stopping those consumers.

### Component tests

From `projects/grain-core-v2`:

```sh
JAVA_CMD=/path/to/java clojure -J-Xmx4g -M:test \
  -m ai.obney.grain.read-model-processor-v3.test-runner
```

The recovery scenario uses SQLite events and LMDB projections. Run the following
command in three fresh JVMs with `PHASE` set to `seed`, `interrupt`, then `verify`.
Use the same initially empty directory each time. Expected exit codes are 0, 17
(the deliberate crash after transaction commit), and 0.

```sh
JAVA_CMD=/path/to/java KONSERVE_LMDB_LIB=/path/to/liblmdb.dylib \
  clojure -J-Xmx4g -J--enable-native-access=ALL-UNNAMED -M:test \
  -m ai.obney.grain.read-model-processor-v3.restart-scenario PHASE /tmp/grain-v3-recovery
```
