# Event definitions and retention

Define every event type with `defevent` and its payload `:schema`.
Grain keeps complete event history by default. To expire old events in a v3
store, register a bounded history policy, activate it, then run compaction for
each tenant. Starting the store alone leaves history intact.

## Define a policy

The control plane declares this policy for heartbeats:

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])

(es/defevent :grain.control/node-heartbeat
  "A control-plane node reported that it is alive."
  {:schema [:map
            [:node/id :uuid]
            [:node/metadata {:optional true} [:map]]]
   :history {:retain-at-least "PT1H"
             :keep-latest-per {:tags #{:node}}}})
```

This keeps every heartbeat for at least an hour and the newest heartbeat per
node indefinitely. Age uses the timestamp assigned at append. The window accepts
positive, fixed ISO 8601 durations such as `"PT30M"` and `"P7D"`. A day is
24 hours. Without `:keep-latest-per`, all events of that type older than the
window are eligible.

Each key is scoped to the tenant. For a composite key such as
`#{:node :region}`, each event must have exactly one tag of each named type.
Preflight reports missing or ambiguous tags. Compaction preserves malformed
events and ignores unrelated tags when comparing keys.

`defevent` registers the payload schema and description. Use `es/->event` to
construct instances and `es/event-definition` to inspect the declaration.
Identical declarations can be reloaded. Omit `:history` to keep complete history.

## Assess and activate

The Postgres and SQLite v3 packages include the retention administration API.

Check that every consumer can recover from the retained history, including
external consumers that may be offline longer than the window. Test read-model
rebuilds from an empty cache. The Event Model boot guard requires a modeled,
schema-compatible bounded event and rejects todo processors subscribed to it.

```clojure
(require '[ai.obney.grain.event-retention.interface :as retention])

(def admin (retention/administration event-store))
(def heartbeat :grain.control/node-heartbeat)

(retention/assess admin heartbeat)     ;; {:safe? true :findings []} on success
(retention/activate! admin heartbeat)
```

Assessment checks stored events across tenants. To include application-specific
consumer checks, pass `{:consumer-assessment (fn [event-type policy] ...)}` to
`administration`. Return a collection of findings, empty when safe.
Activation repeats the assessment and records the normalized policy in the
reserved system tenant. Activation applies to the type across tenants.

To change an active policy, call `(retention/deactivate! admin heartbeat)`,
deploy the new definition to all workers, then activate it. A worker can compact
only while its definition matches the durable activation. Deactivation stops
future batches. Already deleted history cannot be restored by changing a policy.
Call `(retention/verify-at-boot! admin)` to check active policies against the
loaded definitions at startup.

## Estimate and compact one batch

Control-plane heartbeats live in the reserved tenant. For application events,
pass the domain tenant's UUID. Run the estimate after activation.

```clojure
(require '[ai.obney.grain.event-store-v3.interface.compaction :as compaction])

(def tenant-id compaction/system-tenant-id)
(retention/estimate admin heartbeat tenant-id 1000)
(retention/compact! admin heartbeat tenant-id 1000)
(retention/status admin heartbeat tenant-id)
```

The limit bounds domain events per batch. The estimate returns
`:eligible-event-ids`, `:eligible-count`, `:eligible-transaction-ids` and
`:eligible-transaction-count`. Concurrent writes or the passing of time can
change the selection before compaction.

A nonempty batch atomically deletes the selected rows, appends a permanent
`:grain.retention/compacted` receipt and advances the tenant head to the receipt's
transaction marker. The receipt identifies the activation, cutoff, domain events
(`:retention/deleted-event-ids`) and markers (`:retention/deleted-transaction-ids`).
Repeat as needed. An empty batch returns `nil` and appends nothing. A busy
anomaly with `:event-store/attempts` means the write exhausted its [contention retries](event-stores.md#write-contention).

A `:grain/tx` marker with surviving members keeps its original membership and
metadata, even when some IDs now refer to expired events. The batch that deletes
its last member also deletes the marker and its metadata. Empty-append markers,
policy lifecycle events and receipts survive.

Before a replay that needs complete history, check `:history-truncated?` in
`retention/status`. The result also includes the active policy, earliest retained
event and latest compaction counts. Ordinary event reads return the surviving
events.

## Backend and benchmark references

The in-memory, SQLite and Postgres v3 backends implement atomic compaction.
SQLite queues compaction with appends. Postgres serializes it with writes to the
affected tenant. Physical disk reclamation depends on database maintenance.

Run the scale test from the repository root:

```sh
clojure -J-Xmx4g -M:dev scripts/benchmarks/retention-scale.clj \
  sqlite 1000000 artifacts/retention-scale/sqlite-1m.edn 1000
```

SQLite uses a temporary database. For Postgres, substitute `postgres` and set
`PG_HOST`, `PG_PORT`, `PG_DATABASE`, `PG_USER` and `PG_PASSWORD` for a disposable
empty database. The test checks receipts, surviving events, concurrent appends
and restart. See the [scale results](../scripts/benchmarks/retention-scale-results.edn)
and [Postgres latency profiles](../scripts/benchmarks/retention-optimization-results.edn).
To reproduce the latency comparison, [seed](../scripts/benchmarks/retention-seed.clj)
one disposable database, clone it twice, and [profile](../scripts/benchmarks/retention-profile.clj)
each clone sequentially. Supply the [baseline implementation](../scripts/benchmarks/retention-postgres-baseline.clj)
for the first run. [Compare the receipts](../scripts/benchmarks/retention-compare.clj)
using the same fixture.

See also [retention for Grain's own events](grain-event-retention-candidates.md)
and the [retention specification](../components/event-retention/event-retention.allium).
