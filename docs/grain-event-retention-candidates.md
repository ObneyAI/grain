# Grain event retention candidates

Status: repository assessment

This document assesses which existing Grain-owned events may safely adopt the
bounded-history technique specified in
[`event-retention.allium`](../components/event-retention/event-retention.allium).
It does not define the technique itself.

## Production motivation

The supplied production store contains 983,374 events. Grain infrastructure
accounts for 789,121 rows (80.21%) but only 86.8 MiB of roughly 443 MiB total
payload. The two infrastructure types are:

| Event type | Events | Share of all rows |
| --- | ---: | ---: |
| `:grain/tx` | 442,641 | 45.01% |
| `:grain/todo-processor-checkpoint` | 346,480 | 35.23% |

The primary opportunity is row, index, scan, replication, and maintenance
reduction rather than payload reduction.

## Verified framework events

The current non-deprecated framework surface has these persisted event types:

| Area | Event types |
| --- | --- |
| Event store | `:grain/tx` |
| Todo processor v2 | `:grain/todo-processor-checkpoint`, `:grain/todo-processor-effect-failure` |
| Control plane | `:grain.control/node-heartbeat`, `:grain.control/node-departed`, `:grain.control/lease-acquired`, `:grain.control/lease-released` |

Periodic tasks emit application-defined events rather than a generic Grain
periodic event. Read models, tailers, and notifiers do not add other durable
framework event types.

## Classification

| Event type | Recommendation | Reason |
| --- | --- | --- |
| `:grain.control/node-heartbeat` | First candidate | Current state uses only the newest heartbeat per node; retain recent history plus latest per `:node` |
| `:grain/todo-processor-checkpoint` | Candidate after dedicated verification | Recovery and CAS read only the newest checkpoint per tenant/processor, but processor lag and fencing make deletion higher risk |
| `:grain/tx` | Eternal | Reified transaction, append boundary, and metadata carrier |
| `:grain/todo-processor-effect-failure` | Eternal | Durable failure/audit fact rather than renewable state |
| `:grain.control/node-departed` | Eternal | Node lifecycle transition |
| `:grain.control/lease-acquired` | Eternal | Durable ownership transition |
| `:grain.control/lease-released` | Eternal | Durable ownership transition |
| Application/domain events | Eternal by default | Authoritative domain history unless an application explicitly proves a bounded-history contract |

## Heartbeats

The control plane appends one heartbeat per node every five seconds by default.
The active-node projection replaces the prior value for that node and filters
staleness from the newest store timestamp. Lease ownership is projected from
separate acquisition/release events.

At the default interval, one continuously running node produces 17,280 heartbeat
events and 17,280 reified transactions per day. Heartbeats therefore provide the
cleanest first validation of bounded history:

```clojure
:history
{:retain-at-least "PT1H"
 :keep-latest-per {:tags #{:node}}}
```

Replay remains correct when recent heartbeats plus the latest heartbeat per node
are retained alongside eternal departure events. This must still be verified for
departure, revival with the same node ID, staleness detection, routing, and
read-model rebuild before activation.

## Todo-processor checkpoints

Todo processor v2 reads the newest checkpoint for a `(tenant, processor)` pair
using a reverse, limit-one indexed query. Its CAS predicate uses the same newest
checkpoint and relies on monotonic, gap-free advancement. Pure handlers are
already batch-checkpointed; effect and handler-CAS paths preserve per-event
semantics.

A prospective policy is:

```clojure
:history
{:retain-at-least "PT1H"
 :keep-latest-per {:tags #{:processor}}}
```

Tenant is the implicit outer key. Before activation, Grain must verify at least:

* newest checkpoint survival under concurrent processing and compaction;
* CAS and lease-fencing equivalence after historical checkpoint deletion;
* catch-up and reassignment from the retained checkpoint;
* no unprocessed trigger older than the cutoff for any subscribed processor;
* diagnostic and audit expectations for removed checkpoint ranges; and
* mixed-version behavior across all v3 backends.

Checkpoint retention should follow a successful heartbeat rollout and a longer
soak period.

## Transactions

`:grain/tx` is not a retention candidate. Every v3 append creates a transaction
event after its members, and the tenant watermark advances to its ID. The event
contains original membership and optional transaction metadata.

When a bounded-history member expires, its original transaction remains
byte-for-byte unchanged. The compaction receipt proves why the member is no
longer available. Reducing transaction volume should come from reducing needless
appends, not expiring transaction entities.

Compaction receipts are retained indefinitely. They are audit facts, not
candidates for the bounded-history technique assessed here.

## Cross-cutting evidence

* PostgreSQL, SQLite, and in-memory v3 serialize append/CAS/watermark changes
  atomically per tenant.
* SQLite can remove tag rows with its existing delete cascade; PostgreSQL stores
  tags in the event row. Neither backend currently exposes deletion.
* Read models may replay matching types from the beginning, so a retained type
  needs an explicit window-safe or snapshot-backed consumer contract.
* Event tailer and Postgres notifier use `:after` watermarks. Ordering survives
  deletion, but a consumer offline beyond the declared window cannot expect the
  expired events to be delivered.
* Existing constructors and inline `->event` calls are independent of event
  definition registration.
* A composite retention key is valid only when every governed event contains
  exactly one value for each declared key tag. Missing or duplicate key tags fail
  closed and keep the event.
