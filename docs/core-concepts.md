# Core Concepts

The examples use these namespaces.

```clojure
(require '[ai.obney.grain.command-processor-v2.interface  :refer [defcommand]]
         '[ai.obney.grain.schema-util.interface :refer [defschemas]]
         '[ai.obney.grain.query-processor.interface       :refer [defquery]]
         '[ai.obney.grain.todo-processor-v2.interface     :as todo :refer [defprocessor]]
         '[ai.obney.grain.periodic-task.interface         :as pt :refer [defperiodic]]
         '[ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
         '[ai.obney.grain.event-store-v3.interface        :as es :refer [->event defevent]])
```

## Tenant context

A v3 append or read operates on one tenant. Pass the authenticated tenant ID in
the processor context. Keyed reads also need a started LMDB cache.

```clojure
(def context {:event-store event-store :tenant-id tenant-id :cache cache})
```

Postgres uses row-level security to isolate tenant data and advisory locks to
serialize writes per tenant.

## Events

Define every event type with `defevent`. Give it a qualified keyword,
a description and the Malli schema for its body.

```clojure
(defevent :example/counter-created
  "A counter was created."
  {:schema [:map
            [:counter-id :uuid]
            [:name :string]]})

(defevent :example/counter-incremented
  "A counter was incremented."
  {:schema [:map [:counter-id :uuid]]})
```

Construct an instance with `->event`. Consumers read body fields directly from
the event map. At append the store assigns `:event/id` (UUIDv7) and
`:event/timestamp` (OffsetDateTime). `(es/head event-store tenant-id)` returns
the latest committed ID, including transaction markers, or nil for an empty tenant.

Events keep complete history by default. To expire old events, define and activate
a [bounded history policy](event-definitions-and-retention.md).

## Commands (Write Side)

Commands change state by generating events. Both `defcommand` and `defquery`
look up their input schemas by qualified name. Register those inputs with
`defschemas`.

```clojure
(defschemas inputs
  {:example/create-counter [:map [:name :string]]
   :example/counters [:map]})

(defcommand :example create-counter
  {:authorized? (fn [ctx] (true? (get-in ctx [:auth-claims :can-create-counters?])))}
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

HTTP and Datastar adapters call the registered `:authorized?` predicate with
the request context. Only `true` permits dispatch. Check trusted claims and
access to the requested resource. Direct processor calls bypass this adapter check.

## Todo Processors

A todo processor handles stored events matching its `:topics`. For an external
charge, use the triggering event ID as the provider’s idempotency key.

```clojure
(defevent :billing/membership-due
  "A membership payment came due."
  {:schema [:map [:member-id :uuid]]})

(defevent :billing/membership-charged
  "A membership payment was collected."
  {:schema [:map [:member-id :uuid]]})

(defprocessor :billing charge-membership
  {:topics #{:billing/membership-due}}
  "Charges a member's payment method when their membership is due."
  [context]
  (let [event (:event context)
        member-id (:member-id event)]
    {:result/effect (fn [] ((:charge! context) member-id (:event/id event)))
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
(defevent :billing/membership-check-due
  "A daily membership check was requested."
  {:schema [:map [:period :string]]})

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
