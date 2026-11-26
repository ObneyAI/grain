# Changelog

## November 2026

- [Keyed read models](../components/read-model-processor-v2/README.md) fold events into per-instance state. Applications can look up an instance by ID, page through an index, search by text, join models and restrict reads to an event scope. Both ordinary and keyed models accept an exclusive `:start-after` replay boundary.
- Segmented read models now read the manifest and its segments from one snapshot. **Breaking for custom cache backends.** Implement `KVStore/read-snapshot`. Backends serving keyed models also need `TransactionalStore/transact`.
- [Todo processors](core-concepts.md#start-a-new-processor-after-a-known-event) can initialize an unused tenant checkpoint before startup. A failed checkpoint write stops the current batch.
- All built-in v3 stores support [bounded retries](event-stores.md#write-contention) for appends and compaction under lock contention. Postgres startup on an initialized database can now run alongside an append without deadlocking.
- [Compaction](event-definitions-and-retention.md#estimate-and-compact-one-batch) removes a transaction marker when it deletes the last member. The receipt lists deleted event IDs and transaction marker IDs separately.
- [Datastar streams](datastar.md#event-subscription-filters) can exclude event types, match any of several tag groups and refresh when a `:datastar/refresh-key` value changes. Nested input bindings tolerate removed rows. Disconnecting releases the stream’s subscriptions. **Breaking for custom pub/sub implementations.** Implement `unsub` to remove a channel from one topic while keeping its other subscriptions active.
- Grain emits metrics for commands, queries, processors, event stores and read models. The [CloudWatch EMF publisher](../components/mulog-aws-cloudwatch-emf-publisher/README.md) supports metric registries, configurable buffering and a deadline for flushing on shutdown.
- Fressian encoding and Datastar rendering allocate less memory. SQLite uses an indexed lookup for processor checkpoints, and Postgres does less work during compaction.
- Added service implementation skills for commands, events, keyed projections, queries, background work and Datastar views.

[Compare this release with the previous main revision](https://github.com/ObneyAI/grain/compare/dbf5b52...15b0d3e)

## August 2026

- `defevent` registers an event’s description, schema and optional history policy. In-memory, SQLite and Postgres v3 stores support bounded compaction with durable activation and receipts. SQLite and Postgres bound candidate selection within each transaction. (`0f2a561`, `789f439`, `aebc65d`)
- Read-model cache keys now preserve the keyword namespace. On upgrade, entries stored under ambiguous local names rebuild from the event stream.
- The `grain-event-model` package validates registered topology at boot. Command, query, projection, processor and schedule declarations expose metadata for comparing the model with the running app. (`76cf339`, `7d35b3f`)
- Allium holds behavioural rules and examples. Event Model commands link to rules and screens to surfaces via `:grain/allium`. In development or CI, `validate-spec-composition` resolves the links. Claude Code and Codex plugins include skills for authoring and checking the two specs. (`7d35b3f`)

## June 2026

- Added the Datastar UI DSL with scoped and stable signals, indexed inputs, property bindings, signal-patch handlers, morph control, nested payloads and registry-backed routes. **Breaking.** Effect builders use names without a trailing `!`. (`b22ae07`, `082fea4`, `35f5f5c`)
- Datastar streams can follow commits across nodes through a tenant-scoped event tailer. Streams filter by tenant and deduplicate by event ID. The default polling rate is zero. (`21e08d0`, `207d4b1`)
- Fixed effect ordering, synchronization before blur and omission of nil map entries in payloads. Added clj-kondo support for the UI DSL. (`a8f362d`, `3db1781`, `24720f6`, `eec5416`)
- V3 reads accept `:reverse?` and `:limit`. Todo processors use an indexed read for the latest checkpoint. (`dd2e21f`)
- Published the component [behavioural specifications](allium-specs.md). (`d7aa140`)

## May 2026

- Added the SQLite v3 event store for single-process applications and `grain-code-agent-tools` for inspecting a running system. (`4fab864`, `b68f736`)
- Datastar coerces scalar string inputs with the registered command or query schema. (`6120a4b`)
- Fixed overlapping todo-processor batch checkpoints. Published clj-kondo hooks for `grain-core-v2`. (`0fd269c`, `18bcee6`)

## April 2026

- **Breaking.** `EventStore/tenant-ids` became `tenants` and returns per-tenant watermarks. Failed CAS appends no longer advance a tenant’s watermark. (`464bbb7`)
- Reassigned pollers resume from stored checkpoints and skip effects whose triggering event is already checkpointed. (`aff2fd5`, `560caac`, `4ed7c58`)
- Postgres v3 accepts a caller-supplied datasource. Fressian handlers preserve Clojure collection types, `Instant` and `OffsetDateTime` across storage. (`08d1806`, `ddb91e5`, `3c050c7`)

## March 2026

- Added tenant-scoped v3 event stores and `grain-core-v2` processors. Reads support type, tag and time-boundary filters. Commands and todo processors can guard event appends with CAS. (`688152c`, `1c728c8`, `ca1309c`)
- Added `grain-control-plane` for lease assignment, polling, graceful drain and tenant-aware HTTP routing. **Breaking.** Tenant leases identify the owning node across processors. (`e8f3c92`, `a524e61`, `b43bbc6`, `9142691`, `155920e`)
- Added `defperiodic` with interval and time-zone-aware cron schedules. (`fcf627b`, `be91194`)
- Read-model caches include the tenant ID. Fixed cross-partition moves and LMDB reader exhaustion. (`9497a34`, `0028071`, `b62a1c5`)
- **Breaking.** Datastar stream routes use the `/__stream` suffix. The page resolves path parameters and uses a per-load nonce to isolate tabs. Routes support auth redirects, gates, shared defaults and POST signal updates. (`6fed1f5`, `151070d`, `25219e7`)
- Streams compare rendered Hiccup when deciding whether to patch. JSON POST signals retain their native types.

## February 2026

- Added the Datastar page, stream and command interceptors, including tag-filtered updates.
- Read models can register their reducers with `defreadmodel`. Cached snapshots use Fressian encoding. (`20e99de`, `7861952`)
- Event store v2 accepts a vector of read queries and returns their union in event order.

## January 2026

- **Breaking.** HTTP command and query handlers require a successful `:authorized?` predicate. Missing predicates deny access. (`32a3b15`)
- `:command-processor/skip-event-storage` returns command results for callers that own the append. (`840f584`)

## December 2025

- Added self-registering `defcommand` and `defquery` macros, plus clj-kondo support. (`fd31573`, `5715dee`)
- Added the KV protocol, LMDB backend and incremental projection snapshots. LMDB requires JVM module access for `java.nio`. (`ca47fda`)

## November 2025

- Queries validate both their envelope and named input schema. Command and query handler exceptions return fault anomalies. Query HTTP responses map forbidden to 403 and conflict to 409. (`3093b10`, `5b9bdf8`, `fb883bb`)
- Query requests merge `:grain/additional-context` into the handler context. Increased the pub/sub and todo input buffers. (`2d065bd`, `f36aa01`, `a7fdafb`)

## September 2025

- Command requests accept per-request values in `:grain/additional-context`. **Breaking.** Replace the `:http-response-fn` registry hook with a Pedestal interceptor that reads `:grain/command` and `:grain/command-result`. (`fcc065b`, `b1b7807`)

## August 2025

- **Breaking.** Command and query HTTP handlers use Transit+JSON. Fixed command HTTP status codes for forbidden and conflict. (`a2122f0`, `680ddba`)
- **Breaking.** DSPy integration moved to `grain-dspy-extensions`, removing the Python dependency from `grain-core`. Removed the original behavior-tree, event-store and Postgres event-store components. (`c289b3c`)
- Moved event-store-v2, pub/sub, anomalies, schemas and time interfaces to `.cljc`. DSPy signatures gained typed Python models for nested Malli schemas. (`b340595`, `11e9341`)

## July 2025

- **Breaking.** event-store-v2 reads return a reducible. Consume it with `reduce`, `transduce` or `into`. Postgres and in-memory reads match events containing all requested tags. (`0e7189f`, `5fe3778`, `f8c3ec4`, `0126f97`)
- Added DSPy signatures and behavior trees with long-term memory and blackboard snapshots. (`5672078`, `abe0214`, `ff0dedd`, `256fdde`)

## June 2025

- Published the Grain core toolkit, Postgres and in-memory event stores, counter service example, Event Model schema and CloudWatch EMF publisher. (`dbe7d27`, `97f27cd`, `cf9675f`, `c19069a`, `18e7322`)
- **Breaking.** Command and event envelope schemas moved into their components’ `interface.schemas` namespaces. Event Models dropped the required `:version` field. (`aa1e816`, `1cc0fb4`)
- Postgres reads and HTTP request handlers coerce data using the registered domain schemas. (`78893ef`, `1325d74`)
