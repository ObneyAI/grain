# Core Concepts

Code samples in this doc assume the following requires are in scope:

```clojure
(require '[ai.obney.grain.command-processor-v2.interface  :refer [defcommand]]
         '[ai.obney.grain.query-processor.interface       :refer [defquery]]
         '[ai.obney.grain.todo-processor-v2.interface     :as todo :refer [defprocessor]]
         '[ai.obney.grain.periodic-task.interface         :as pt :refer [defperiodic]]
         '[ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
         '[ai.obney.grain.event-store-v3.interface        :as es :refer [->event defevent]])
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

Commands change state by generating events. Both `defcommand` and `defquery`
look up their input schemas by qualified name. Register those inputs with
`defschemas`.

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

The command handler runs once per dispatch. The event store retries a contended
append internally. Exhaustion returns the store's busy anomaly. A failed CAS
precondition returns a conflict. See [v3 write contention](event-stores.md#write-contention).

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

Construct events with `->event`. The v3 store assigns the final UUIDv7 ID and
timestamp at append. Use the persisted ID for checkpoints and replay boundaries.
`(es/head event-store tenant-id)` returns the latest committed ID, including
transaction markers, or `nil` for an empty tenant.

### Event Definitions

Define every event type with `defevent`. Give it a qualified keyword,
a description and the Malli schema for its body.

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
  "Returns the first page of counters in name order."
  [context]
  {:query/result (rmp/page context :example/counter-list {:index :name :limit 25})})
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

The handler receives `:event`, `:event-store` and `:tenant-id`. The poller treats its result as follows:

| Return map | Semantics | Checkpointing |
| --- | --- | --- |
| `{:result/events [...]}` | Pure — no side effects | Batched with adjacent pure results |
| `{:result/effect fn, :result/checkpoint :before}` | At-most-once — checkpoint first, then run effect | Per-event |
| `{:result/effect fn, :result/checkpoint :after}` | At-least-once — run effect first, then checkpoint | Per-event |
| `{:result/events [...] :result/cas guard}` | Append only if the guard passes | The handler’s CAS guard prevents duplicate output |
| `{}` | Acknowledge without emitting events | Batched with pure results |

A busy result from a handler or a failed checkpoint write stops the tenant's batch. The poller resumes from the
last committed checkpoint on its next cycle. With `:after`, an effect can run
again if its checkpoint fails, so give the external call an idempotency key.
With `:before`, the effect runs only after the checkpoint commits.

### Start a new processor after a known event

To skip historical triggers for an unused processor, initialize its checkpoint
for each tenant **before starting or registering it with a running poller on
any node**. Choose a retained UUIDv7 from the tenant that marks the last event
you intend to skip. To skip all existing events in a nonempty tenant:

```clojure
(def start-after (es/head event-store tenant-id))
(todo/initialize-checkpoint! event-store tenant-id :billing/charge-membership start-after)
```

Start the processor after the call returns `:checkpoint/status :initialized` or
`:already-initialized`. It handles matching events strictly after that ID. For a
retry, reuse the original ID. A different boundary or a checkpoint already
advanced by processing returns a conflict. An empty tenant has no history to skip.

## Periodic Tasks

Periodic tasks receive the tenant ID and the scheduled `java.time.Instant`.
This task emits a daily trigger and uses a CAS guard to prevent duplicates.

```clojure
(defperiodic :billing daily-membership-check
  {:schedule {:cron "0 0 * * *" :timezone "UTC"}}
  "Requests a daily membership check for each tenant."
  [_tenant-id tick]
  (let [period (str (.toLocalDate (.atZone tick java.time.ZoneOffset/UTC)))]
    {:result/events
     [(->event {:type :billing/membership-check-due
                :body {:period period}})]
     :result/cas
     {:types #{:billing/membership-check-due}
      :predicate-fn (fn [existing]
                      (reduce (fn [_ event]
                                (if (= period (:period event)) (reduced false) true))
                              true existing))}}))
```

Every node runs the schedule independently. The CAS guard admits one request
per tenant per UTC day. A todo processor can subscribe to that request and select
the memberships due for billing.

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

A read model folds a tenant's events through a pure reducer. Supply
`:event-store`, a started LMDB `:cache` and `:tenant-id` in the read context.
Define a keyed model with `:key` to maintain state per instance. Here each
counter has its own state, and the name index orders the list:

```clojure
(defreadmodel :example counter-list
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1
   :key :counter-id
   :state-schema [:map [:id :uuid] [:name :string] [:value :int]]
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

Set `:start-after` to fold only events after a fixed
UUIDv7. Changing the boundary builds a separate projection. Bump `:version`
when changing the reducer, routing or indexes.

For search, joins, event scopes and cache configuration, see the
[read-model guide](../components/read-model-processor-v2/README.md).
