# Opt-in event definitions and safe retention

Status: implemented

The normative behavioral source of truth is
[`event-retention.allium`](../components/event-retention/event-retention.allium).
This document records the API, safety model, operational guidance, and backend
notes. If behavioral wording here diverges from the Allium specification, the
Allium specification governs.

## Decision summary

Grain provides an opt-in, registration-only `defevent` macro and an
event-definition registry. Existing event constructors and `->event` calls do
not change. A registered event is eternal unless its definition contains an
explicit bounded-history policy and that exact policy is separately activated.

Retention is an event-store lifecycle operation, not a second mutable state
system. Events with complete history, transaction events, and unregistered events
are never eligible. Every compaction operation is atomic and leaves an eternal,
reified compaction receipt.

The first supported history policy is deliberately narrow:

```clojure
{:retain-at-least "PT1H"
 :keep-latest-per {:tags #{:node}}}
```

It preserves complete history for at least one hour, then permits compaction
while retaining the newest event for each key derived from one or more selected
tag types. Arbitrary predicates and body-field partitioning are out of scope.

The repository evidence and assessment of existing Grain-owned events are kept
separately in
[grain-event-retention-candidates.md](grain-event-retention-candidates.md). This
document defines the reusable event-definition and retention mechanism.

## `defevent`

### Form

`defevent` registers one event definition. It does not construct events or define
an event factory:

```clojure
(defevent :grain.control/node-heartbeat
  "A node reported that it is still alive."
  {:schema
   [:map
    [:node/id :uuid]
    [:node/metadata :map]]

   :history
   {:retain-at-least "PT1H"
    :keep-latest-per {:tags #{:node}}}})
```

The macro expands to:

1. schema registration under `:grain.control/node-heartbeat`; and
2. event-definition registration under that keyword, including its docstring
   and source location.

The existing `->heartbeat` function remains responsible for tags and body
construction. Inline application calls to `->event` also remain unchanged.
Retention cannot be supplied or overridden per event instance. `:schema` is
required: a `defevent` declaration without it is rejected at macro expansion.
Legacy events remain compatible by continuing to use their existing `defschemas`
registration without opting into `defevent`.

### Registry entry

```clojure
{:grain.control/node-heartbeat
 {:event/type :grain.control/node-heartbeat
  :description "A node reported that it is still alive."
  :schema [...]
  :history {:retain-at-least "PT1H"
            :keep-latest-per {:tags #{:node}}}
  :definition/ns 'ai.obney.grain.control-plane.events
  :definition/file ".../events.clj"
  :definition/line 8}}
```

Registration rejects conflicting definitions of the same event type. Identical
registration is idempotent to support namespace reloads. The registry exposes a
pure snapshot function and is included in code-agent-tools' live catalogue.

Absence of `:history` means complete, permanent history. A bounded `:history`
declaration changes the public availability contract for that exact event type,
but does not enable compaction by itself. Activating or changing bounded history
requires a separate durable policy activation.

There are two semantic history modes but no `:mode` slot: omitted `:history`
means complete history, while `:history {:retain-at-least ...}` means windowed
history. `:keep-latest-per` is an optional preservation rule within the windowed
form, not another mode.

### Compatibility contract

```text
not registered with defevent       existing schema/append rules; eternal
registered without bounded history valid, eternal
registered with bounded history    eternal until separately activated
protected transaction/system event never eligible
event-store-v2                     unchanged; definitions are metadata only
```

`->event` remains public and unchanged. Strict coverage of every schema-
registered event may be offered as a future opt-in boot guard, but it is not part
of the initial feature. The initial guard does require every `defevent` carrying
bounded history to be modeled and safe.

### Relationship to existing catalogues

* `defevent` owns runtime identity, payload schema, documentation, and history
  availability.
  It does not own construction.
* Malli continues to own validation mechanics. `defevent` registers its payload
  schema into the existing Malli registry rather than creating another validator.
* `defeventmodel` continues to own design intent, descriptions, topology, and
  Allium references.
* The event-model validator compares modeled events with `defevent` definitions
  when present, while continuing to accept schema-only legacy events.

This can remove separate `defschemas` entries for newly opted-in definitions
without forcing existing constructors or applications to migrate.

## Retention semantics

### Component boundary

Retention policy enforcement is a separate `event-retention` component. It owns
the activation API, activation-state projection, policy normalization/comparison,
tenant enumeration, scheduling, coordination, and metrics. It depends on the
event-definition registry and event-store-v3.

Safe compaction is a separate privileged protocol, not a quality of the ordinary
event-store-v3 protocol. A retention-capable event-store-v3 backend implements
both protocols. Through the compaction protocol, the backend owns atomic
candidate selection, cutoff and latest-key protection, deletion, receipt append,
tenant serialization, and watermark preservation. Computing candidates in
`event-retention` and passing IDs to a generic delete API is forbidden because an
append between selection and deletion would create a time-of-check/time-of-use
race.

Starting an event store does not start retention workers or inspect application
definitions. Event-store-v2 remains unchanged. The compaction protocol operation
is privileged infrastructure API, not general application deletion.

The API boundary is:

```text
application code
    -> event-store-v3.interface
       append / read / tenants

operator-chosen automation
    -> event-retention.interface
       activate! / deactivate! / estimate

event-retention implementation
    -> event-store-v3.interface.compaction
       estimate-compaction / compact!

v3 backend
    -> implements EventStore and EventCompaction
```

The main event-store interface does not re-export compaction, and ordinary
application components do not depend on its namespace. This is an architectural
dependency boundary, not a security boundary: arbitrary production code can
resolve Clojure vars. Runtime safety therefore still requires a matching durable
activation, protected-type rejection, tenant isolation, bounded work, and an
audited receipt. Production code/REPL and database access remain the actual
security controls.

### Eligibility

An event row is eligible only when all conditions hold:

1. its exact type has a loaded `defevent` definition;
2. that definition has a valid bounded `:history` policy;
3. the normalized policy in the latest durable activation equals the normalized
   policy in the loaded definition;
4. the type is not permanently protected;
5. its store-assigned timestamp is older than the computed cutoff; and
6. it is not protected by the optional `:keep-latest-per` rule.

Permanently protected types initially include `:grain/tx`, compaction receipts,
and every unregistered type. Protection is enforced in the event-store component,
not by naming convention or application code.

Grain provides activation and deactivation primitives:

```clojure
(retention/activate! event-store :grain.control/node-heartbeat)
(retention/deactivate! event-store :grain.control/node-heartbeat)
```

The primitives append eternal policy-lifecycle events to Grain's existing
reserved system tenant using CAS. The current control-plane tenant ID becomes a
shared event-store-level system-tenant constant rather than introducing another
reserved tenant: today the control plane excludes only that one ID from domain
assignment. A successful CAS append uses the ordinary v3 persistence envelope:
it also appends `:grain/tx` and advances the system tenant watermark to that
transaction marker. An activation stores the event type, policy-format version,
and normalized `:history` value. Workers compare that value directly with the
loaded definition using Clojure equality. Documentation, schema, and source
metadata are not part of this comparison, so unrelated definition edits do not
disable compaction.

Grain does not prescribe when or how these functions are called. A user may call
them from a production REPL, deployment script, CI/CD workflow, administrative
service, or another policy mechanism. Authorization, review, and orchestration
belong to that environment; every route produces the same durable Grain event.

A compactor acts only when its loaded definition and the latest durable
activation agree. A mismatch fails closed. Compaction workers use CAS-backed
coordination so concurrent nodes may plan, but only one applies a given
tenant/type batch.

Adding or editing `:history` in code never changes durable activation. Adding a
new policy has no effect until `activate!` is called. For an active policy change,
the safe sequence is `deactivate!`, deploy the new definition, then `activate!`
again. Otherwise old-version workers whose definitions still match the old
activation may continue compacting during a rolling deployment. Removing a
policy cannot restore events already compacted.

### Time and identity

Age is calculated from the store-assigned `:event/timestamp`, never a body field
or application clock. `:retain-at-least` states a history guarantee rather than
an exact deletion time: compaction may happen later, and a protected newest event
can survive indefinitely. The cutoff is captured once per compaction transaction.

Durations are ISO 8601 strings, keeping definitions portable data. Grain accepts
only positive, fixed elapsed durations composed of days, hours, minutes, and
seconds—the `PnDTnHnMnS` subset parsed by `java.time.Duration`. Days are exactly
24 hours. Examples include `"P7D"`, `"PT30M"`, `"PT12H"`, and `"P2DT3H"`.
Calendar years/months, weeks, signs, zero, and implementation-specific extensions
are rejected; for example, `"P1Y"`, `"P1M"`, `"P2W"`, and `"-P1D"` are invalid.
Validation normalizes accepted values to seconds and nanoseconds before policy
comparison, so equivalent forms such as `"P1D"` and `"PT24H"` compare equal.

`:keep-latest-per` derives its key from existing indexed tags. Tenant is always
an implicit outer partition. Tag types form an unordered set because their source
order has no semantic meaning:

```clojure
{:retain-at-least "P7D"
 :keep-latest-per {:tags #{:node :region}}}
```

For tags `#{[:node node-a] [:region region-west] [:deployment deploy-7]}`, the
key is `{:node node-a, :region region-west}`; the unrelated deployment tag is
ignored. A single-tag policy uses a one-element set. Clojure set equality already
ignores source and iteration order when policies are compared.

An event must contain exactly one value for every declared key tag. Missing or
duplicate required tag types fail closed: the event is retained and reported as
malformed. Additional unrelated tags are allowed. This prevents a constructor
bug from collapsing distinct histories into an accidental shared partition.

### Atomic compaction

Every retention-capable v3 backend implements a separate privileged protocol. It
is not re-exported through the application event-store interface:

```clojure
(defprotocol EventCompaction
  (estimate-compaction [store request])
  (compact! [store request]))
```

The estimate is advisory; `compact!` is authoritative:

```clojure
(compact! store
  {:tenant-id control-plane-tenant-id
   :event-type :grain.control/node-heartbeat
   :retain-at-least {:seconds 604800 :nanos 0}
   :keep-latest-per {:tags #{:node}}
   :activation-id activation-event-id
   :limit 1000})
```

The retention component converts the public ISO duration to normalized seconds
and nanoseconds. The retention component fixes one evaluation time for each
estimate or compaction attempt, and the backend computes the cutoff from that
time inside the transaction. Production uses the real clock. Tests may inject a
controlled clock and advance it beyond the real policy window; this changes only
the evaluation time and never rewrites, scales, or substitutes the registered or
activated policy. Estimate and apply are separate operations rather
than a `:dry-run?` flag because only apply can make an atomic claim about what was
deleted.

One backend transaction must:

1. lock or otherwise serialize compaction with appends for the affected tenant;
2. compute the cutoff from the attempt's fixed evaluation time;
3. resolve protected newest IDs before deletion;
4. select a bounded deterministic batch of eligible IDs;
5. find their transaction markers and delete those with no surviving member
   events, together with the selected event rows and associated tag rows;
6. append one eternal `:grain.retention/compacted` event and its `:grain/tx`;
7. leave `tenant/last-event-id` pointing at the new receipt transaction; and
8. return the receipt and counts.

The receipt records type, activation event ID, cutoff, and the exact sets of
deleted IDs: `:retention/deleted-event-ids` for selected domain events and
`:retention/deleted-transaction-ids` for their removed markers. The activation
ID resolves to the complete normalized policy value. The batch limit bounds
selected domain events; at most that many transaction markers are also removed.
Estimates report `:eligible-transaction-ids` and `:eligible-transaction-count`
separately from the existing event IDs and count. Status reports
`:deleted-transaction-count` alongside `:deleted-count` in `:latest-compaction`.
Exact membership is needed
to prove that a particular missing event was deliberately expired rather than
lost or corrupted. Empty runs append nothing. Compaction receipts have no
retention policy and are retained indefinitely. Any future expiry mechanism for
receipts requires a separate design and explicit activation.

Compaction is therefore visible as a reified Grain transaction even though the
expired payloads are no longer available.

### Reified transactions after expiry

A `:grain/tx` marker is deleted when compaction removes its last remaining
member event. Members may expire in different batches or under different
policies; the batch removing the last member also removes the marker. Markers
with surviving members remain byte-for-byte unchanged, including their original
`:event-ids` and optional metadata. Deleted marker metadata expires with it;
receipts record the marker ID without copying its payload.

Transaction markers remain protected from direct age-based retention. Markers
for empty appends are left alone, and markers for permanent receipts or policy
lifecycle events survive because their members survive. Empty compaction runs
delete nothing and append nothing.

Each append places its marker immediately after its members in tenant ID order.
Backends find affected markers through that ordering, verify membership, and
check remaining member IDs without decoding unrelated event payloads. Missing or
inconsistent ownership aborts compaction. PostgreSQL and SQLite use a
`(tenant_id, type, id)` index to find markers. The receipt and its new marker are
committed with both deletion sets; the tenant watermark advances to the new
marker even when the old head is removed. Tooling can distinguish legitimate
expiry from corruption by consulting the permanent receipts.

### Read and replay contract

The ordinary `read` result remains a reducible for compatibility. Add a separate
history-availability query:

```clojure
(retention-status store
  {:tenant-id tenant-id
   :types #{:grain.control/node-heartbeat}})
```

It reports active policy values and activation IDs, earliest retained
IDs/timestamps, last compaction, and whether history has been truncated. APIs
that promise complete historical replay must call it before replaying a retained
type.

Grain does not accept a declarative claim that a read model is safe to rebuild
from retained history: the validator cannot prove that claim. Read-model replay
correctness remains a test obligation for the application.

For initial rollout, the boot guard checks the loaded Grain-owned consumers.
That check cannot prove the safety of an unknown external service or a consumer
not loaded in the current process. `:history` is therefore also a public
availability contract: external consumers must be designed for the declared
window. General application opt-in should wait until deployments can account for
all consumers that require complete replay.

Todo processors subscribing to an expiring trigger type are rejected. Grain does
not yet have an implemented checkpoint-safety proof that could authorize this
case, and an operational recovery promise is insufficient evidence for deletion.

### Validator and boot guard

The existing event-model validator is the application-quality boundary. For each
`defevent` with bounded history it requires that:

* the event exists in the event model and its schemas agree;
* no todo processor subscribes to the bounded event;
* retention-key tag names and policy structure are valid.

Stored-event schema and retention-key cardinality are checked by the separate
activation preflight, not by every application boot.

Adding or changing `:history` is reported as a breaking data-contract change,
not ordinary metadata drift. Any unresolved obligation makes the registered
event model invalid. CI exposes the findings early, and the existing strict
production boot guard refuses startup with the same verdict. This prevents an
AI-generated application from silently introducing unsafe compaction; activation
remains a separate runtime primitive.

### Backend notes

#### In-memory

Filter the STM event vector and append the receipt in the same `dosync`. This is
primarily a semantic reference implementation; retention on restart is naturally
irrelevant for this backend.

#### SQLite

Use the existing immediate write transaction and tenant serialization. Delete
from `events`; `event_tags` follows via `ON DELETE CASCADE`. Add an index suitable
for bounded selection by `(tenant_id, type, time, id)`. Run incremental vacuum as
an independent storage-maintenance concern; successful logical deletion does not
promise immediate file shrinkage.

#### Postgres

Use the existing per-tenant advisory transaction lock. Add an index suitable for
bounded selection by `(tenant_id, type, time, id)`. Tags reside in the event row.
Ordinary autovacuum reclaims space; compaction must not issue `VACUUM` inside its
transaction.

#### V2 stores

No deletion API is added. Event construction remains unchanged; retention is a
v3-only capability.

## Implementation and rollout

The event-definition registry, catalogue integration, durable activation,
preflight assessment, status APIs, bounded compaction, and durable receipts are
implemented. The in-memory, SQLite, and Postgres v3 stores implement the
privileged compaction protocol; SQLite and Postgres bound candidate selection in
the database transaction rather than materializing an unbounded eligible set.

Before activating a production policy, migrate the event type to `defevent`,
reconcile it with the Event Model, run strict boot verification, inspect
`retention/assess` and `retention/estimate`, and verify every external consumer's
bounded-history contract. Exercise restart/replay behavior, malformed retention
keys, rolling deployments, and transaction audit behavior in a non-production
deployment first.

## Required conformance tests

Every backend implementing `EventCompaction` must pass the same tests:

* absent registration, absent policy, disabled store, and unknown type delete
  nothing;
* protected types cannot be enabled or selected directly by a policy;
* boundary timestamps are deterministic;
* when `:keep-latest-per` is declared, the newest event per tenant and composite
  key survives regardless of age;
* malformed, missing, or duplicate key tags fail closed when that rule is
  declared;
* concurrent append cannot cause the newly appended latest event to be deleted;
* estimate and apply agree against unchanged state under a fixed backend clock;
* deletion and receipt append are atomic under injected failure;
* tenant watermark advances to the receipt transaction and never regresses;
* transaction markers remain unchanged while members survive and are removed
  atomically with the last member, with both deletion sets audited;
* SQLite tag rows do not orphan; Postgres RLS prevents cross-tenant deletion;
* read models requiring complete history block enablement;
* lagging todo processors block expiry of unprocessed triggers;
* namespace reload permits identical definitions and rejects conflicts;
* `defevent` rejects a missing or malformed `:schema` at macro expansion;
* all legacy schema-registered `->event` paths remain valid and eternal;
* no compaction occurs without a matching definition and durable policy
  activation;
* mixed-version workers cannot apply policies whose normalized values differ
  from the durable activation.

## Million-event scale test

Run `scripts/benchmarks/retention-scale.clj` from the workspace root:

```sh
clojure -J-Xmx4g -M:dev scripts/benchmarks/retention-scale.clj \
  sqlite 1000000 artifacts/retention-scale/sqlite-1m.edn 1000
```

For PostgreSQL, use `postgres` instead of `sqlite` and set `PG_HOST`, `PG_PORT`,
`PG_DATABASE`, `PG_USER`, and `PG_PASSWORD` to a disposable empty database.
The script refuses a store with existing tenants and never truncates a store.
SQLite uses a temporary database and removes it after the run.

The default fixture loads 1,000,000 domain events through the public append API,
ten events per append, producing another 100,000 transaction markers. It mixes
expired history, recent history, permanent facts, transactions containing both
expired and permanent members, and keyed history over 1,000 keys. It should
remove 749,000 domain events and 69,000 original markers, leaving 251,000 original
domain events and 31,000 original markers. Compaction uses 1,000-event batches
while a concurrent caller appends permanent events, pausing 20 ms between writes.

The test checks each deletion against an independently computed manifest and its
receipt, fingerprints every surviving original event and marker, checks receipt
and concurrent-write membership, verifies the watermark and unchanged policy
history, and repeats verification after closing and reopening the store. SQLite
also checks for orphaned tags. Empty retries must leave the watermark unchanged.

SQLite admits compaction batches through the same bounded write queue as
appends, so repeated cleanup batches cannot overtake an already queued append.
Its keyed selector drives tag lookups by event ID even when a freshly loaded
database has no planner statistics.

The EDN result records load and activation times, estimate times, compaction
throughput and batch latency percentiles, concurrent append latency, and exact
verification counts. The [recorded million-event results](../scripts/benchmarks/retention-scale-results.edn)
include both backends, source hashes, and the SQLite query-plan and writer-starvation
issues found and corrected during testing. Timing is diagnostic; this is one
local workload rather than a capacity guarantee for different transaction sizes
or writer loads. The recorded final backend runs overlapped on the same host.

### PostgreSQL latency profiling

PostgreSQL now selects an ordered page of old, valid-key candidates and uses the
existing tag GIN index to find the newest events for those candidate keys. It
continues past protected newest events until it fills the requested deletion
limit or exhausts history. Newest-event lookup includes recent events, validates
all components of composite keys, and ignores unrelated tags. This replaces
ranking the entire keyed history for every batch.
When a page has more than 64 distinct keys, newest-event lookup instead scans
from the first candidate's ID onward. Earlier IDs cannot supersede any candidate.
This avoids a large GIN union over deleted index entries awaiting vacuum, which
caused latency spikes near the end of cleanup. Both paths use the same key
validation and exact composite-key comparison.

Transaction ownership is resolved in one SQL query, with each distinct marker
decoded once. Surviving member IDs are checked in bulk, in chunks of 5,000.
Missing or inconsistent ownership still aborts compaction. Tenant and policy
locking, batch size, atomic deletion and receipt creation, and watermark updates
are unchanged. No schema changes or new indexes are required.

The profiling harness runs both implementations sequentially against clones of
one seed, preserving the exact original IDs and timestamps. Use a disposable
PostgreSQL instance and export its `PG_HOST`, `PG_PORT`, `PG_USER`, and
`PG_PASSWORD`. The following commands also require PostgreSQL client tools:

```sh
createdb grain_retention_seed
PG_DATABASE=grain_retention_seed clojure -J-Xmx4g -M:dev \
  scripts/benchmarks/retention-seed.clj artifacts/retention-scale/fixture.edn 1000000
createdb -T grain_retention_seed grain_retention_before
createdb -T grain_retention_seed grain_retention_after
PG_DATABASE=grain_retention_before clojure -J-Xmx4g -M:dev \
  scripts/benchmarks/retention-profile.clj artifacts/retention-scale/fixture.edn \
  artifacts/retention-scale/before.edn scripts/benchmarks/retention-postgres-baseline.clj
PG_DATABASE=grain_retention_after clojure -J-Xmx4g -M:dev \
  scripts/benchmarks/retention-profile.clj artifacts/retention-scale/fixture.edn \
  artifacts/retention-scale/after.edn
clojure -J-Xmx4g -M:dev scripts/benchmarks/retention-compare.clj \
  artifacts/retention-scale/fixture.edn grain_retention_before grain_retention_after \
  artifacts/retention-scale/parity.edn
```

For `createdb`, provide the corresponding libpq settings (`PGHOST`, `PGPORT`,
`PGUSER`, and `PGPASSWORD`, without underscores). Do not run other benchmarks
alongside these processes. The frozen baseline overrides only the keyed selector
and transaction-marker lookup. Each run checks the full manifest, surviving
payloads and markers, concurrent writes, receipts, restart, and empty retries.
The final comparison hashes the ordered receipt batches' event type, cutoff,
deleted event IDs, and deleted transaction IDs; generated receipt IDs and policy
activation IDs differ between runs and are excluded.

Profiling separates selection, marker inspection, deletion, receipt insertion,
and commit return. The reported tenant lock interval runs from the client's
lock-acquisition acknowledgement through transaction return, including connection
release. It is a client-observed approximation, not exact server-side lock
tracing. JDBC counts measure API calls, not necessarily network round trips.
These measurements do not imply a fixed latency bound: a different key
distribution, transaction width, writer load, or database state can change costs.

The [recorded PostgreSQL optimization results](../scripts/benchmarks/retention-optimization-results.edn)
include source hashes, the complete profiles, and the exact batch comparison.
On the million-event fixture with unchanged 1,000-event batches:

| Measurement | Before | After |
| --- | ---: | ---: |
| Concurrent append p95 | 1,213 ms | 120 ms |
| Concurrent append p99 | 1,486 ms | 130 ms |
| Keyed batch tenant lock interval p95 | 1,471 ms | 118 ms |
| Keyed batch tenant lock interval maximum | 1,617 ms | 136 ms |
| Total compaction time, both policies | 217.85 s | 57.85 s |

All 749 deletion batches matched exactly, deleting 749,000 domain events and
69,000 original transaction markers. The surviving 251,000 original events and
31,000 original markers had identical fingerprints, including after restart.
All 750 concurrent writes in the optimized run survived with their markers.
The PostgreSQL suite passed 77 tests and 265 assertions, including selector
equivalence for malformed and composite keys across tenants and both query paths,
paging past protected newest events, missing ownership, and large append batches.
The highest observed append latency was 346 ms; the highest unkeyed batch lock
interval was 360 ms. The table's keyed improvement is not a bound on all writes.

## Explicit non-goals

The first version does not provide arbitrary event deletion, per-event TTL,
automatic or unproved domain-event retention, user predicates, body-field
partitions, automatic policy changes on code reload, independent transaction expiry, or
silent best-effort replay. Application domain events remain eternal unless a
later rollout explicitly proves and activates the same bounded-history contract.
