# Core Concepts

Code samples in this doc assume the following requires are in scope:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface  :refer [defcommand]]
         '[ai.obney.grain.query-processor.interface       :refer [defquery]]
         '[ai.obney.grain.todo-processor-v2.interface     :refer [defprocessor]]
         '[ai.obney.grain.periodic-task.interface         :refer [defperiodic]]
         '[ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
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

**Always use `->event` to construct events.** It generates UUID v7 IDs (time-ordered) required by the event store for correct ordering and deduplication. Never construct event maps manually with `java.util.UUID/randomUUID` — events with v4 UUIDs will be silently misordered or lost.

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

## Read Models

A read model is a pure reducer over a tenant's events. `defreadmodel` registers
it, and the read-model processor folds events into an LMDB cache and serves
reads from it. Every read first catches the model up to the event store head,
so a read never misses an event that was committed before it started. The one
exception is an ordinary model that sets `:l1-ttl-ms`, which may serve its
in-process copy for that long without checking.

The context for a read needs `:event-store`, `:cache` (a started
`kv-store-lmdb` store) and `:tenant-id`. Each tenant's data is stored
separately.

An ordinary model folds all of its events into one state map. Read it with
`project`:

```clojure
(defreadmodel :example counters
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1}
  "Counters by ID."
  [state event]
  (case (:event/type event)
    :example/counter-created
    (assoc state (:counter-id event) {:id (:counter-id event) :name (:name event) :value 0})
    :example/counter-incremented
    (update-in state [(:counter-id event) :value] inc)))

(rmp/project context :example/counters)
```

A keyed model gives each instance its own state, stored and indexed
separately. Use it for anything a screen lists, sorts, searches or opens by
ID:

```clojure
(defreadmodel :example counter-list
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1
   :key :counter-id
   :indexes {:name (rmp/order-key [:name])}}
  [counter event]
  (case (:event/type event)
    :example/counter-created
    (assoc counter :id (:counter-id event) :name (:name event) :value 0)
    :example/counter-incremented
    (update counter :value inc)))

(rmp/value context :example/counter-list counter-id)
(rmp/page context :example/counter-list {:index :name :page 1 :limit 25})
```

Keyed models also support routing one event to several instances, text
search, event scopes, and `rmp/query`, which joins and pages several keyed
models in one call. Change `:version` whenever what a model stores changes.

The [read-model-processor-v2 guide](../components/read-model-processor-v2/README.md)
documents both kinds of model: options, reads, queries, scopes, consistency,
caching, partitioned ordinary models and operations.
