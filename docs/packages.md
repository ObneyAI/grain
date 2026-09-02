# Packages

## grain-core-v2

Multi-tenant CQRS/Event Sourcing with an in-memory event store. Includes v2 processors (command, read-model, todo), v2 request handler, query processor, pub/sub, declarative event definitions, opt-in retention administration, and the event tailer for replaying shared-store events into a node's local pub/sub:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-core-v2"}
```

## grain-control-plane

Distributed coordination for multi-instance deployments. Coordinator election, tenant lease management, pull-based polling with batch checkpointing, periodic task scheduling with CAS deduplication, and tenant-aware load balancer routing:

```clojure
obneyai/grain-control-plane
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-control-plane"}
```

Includes the core CQRS components (event store, read model processor, todo processor, periodic task, pub/sub).

It also includes event-definition validation and retention administration. A
declared bounded-history policy remains inert until its exact value is durably
activated. See [Event Definitions and Retention](event-definitions-and-retention.md).

## grain-datastar

Server-rendered reactive UIs with [Datastar](https://data-star.dev/). Streams hiccup-rendered HTML over SSE, with event-driven re-rendering, distributed live updates via the event tailer, SSE connection reuse, auto-generated auth redirects, context-dependent gate interceptors, Malli-based JSON coercion, and automatic Pedestal route generation:

```clojure
obneyai/grain-datastar
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-datastar"}
```

Includes the core CQRS components (command/query/read-model processors, event store, pub/sub, event tailer). See `components/datastar` for the full source.

## grain-code-agent-tools

Dev-only nREPL-facing tools for coding agents working against a live Grain app. Exposes registered commands, queries, read models, todo processors, periodic triggers, schemas, tenant-scoped event reads, projections, command/query invocation, validation, and runtime diagnostics as plain EDN:

```clojure
obneyai/grain-code-agent-tools
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-code-agent-tools"}
```

Install it after the app's Grain system starts:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as code-agent-tools])

(code-agent-tools/install! {:system app
                            :context (::context app)
                            :mode :dev})
```

Use it from nREPL to inspect the live catalog, validate command/query payloads
against the schema registry, read events, inspect projections, and run runtime
diagnostics. It also provides Event Model runtime and Allium composition
validation. See [Code Agent Tools](code-agent-tools.md) for the full guide.

## grain-event-model

Service-area Event Model registration plus the shippable structural validator
and strict production boot guard. This package provides `defeventmodel`, runtime
topology reconciliation, coverage checks, and `verify-or-throw!`. Production
validation does not require Allium source files or the Allium CLI:

```clojure
obneyai/grain-event-model
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-event-model"}
```

Use the separate `grain-code-agent-tools` package for the development/CI
composition gate that resolves Event Model `:grain/allium` links. See
[Event Model](event-model.md) for setup and the composed specification workflow.

## grain-event-store-postgres-v3

Multi-tenant Postgres backend with Row-Level Security, per-tenant advisory locks, Fressian binary serialization, tenant-scoped operations, and privileged atomic bounded compaction with durable receipts. All read and append operations require a tenant ID, ensuring structural data isolation:

```clojure
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

## grain-event-store-sqlite-v3

Embedded SQLite backend implementing the v3 event store protocol and privileged atomic bounded compaction for single-process deployments where running Postgres is overkill. WAL mode with a bounded single-writer queue and `BEGIN IMMEDIATE` per append, a tenant-scoped events table plus a normalized `event_tags` join table for indexed superset tag filtering, Fressian binary serialization, and durable compaction receipts. Reads continue to use the connection pool concurrently; increasing the pool size improves available read concurrency but cannot increase SQLite's single-writer throughput. Same tenant-scoped API as the Postgres backend — swap the `:conn` type to move between them:

```clojure
obneyai/grain-event-store-sqlite-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-event-store-sqlite-v3"}
```

SQLite contention policy is configured inside `:conn`:

```clojure
{:type :sqlite
 :database-file "grain.sqlite"
 :maximum-pool-size 4
 :write-queue-capacity 1024
 :busy-timeout-ms 1000
 :busy-max-retries 3
 :busy-retry-backoff-ms 10
 :busy-retry-max-backoff-ms 250
 :write-shutdown-timeout-ms 5000}
```

The queue admits one active write plus `:write-queue-capacity` waiting writes. A full queue returns a `cognitect.anomalies/busy` result immediately. SQLite busy/locked results retry the complete transaction with capped exponential backoff; exhaustion also returns a busy anomaly rather than exposing a raw `SQLITE_BUSY` exception. Keep at least two pooled connections when reads must proceed alongside a writer; the default is four. A larger pool can serve more simultaneous reads, but still cannot create a second SQLite writer. Grain emits μ/log metrics named `SQLiteWriteQueueDepth`, `SQLiteWriteQueueWait`, `SQLiteAppend`, `SQLiteWriteTransaction`, `SQLiteBusyRetry`, `SQLiteBusyExhausted`, and `SQLiteWriteQueueSaturated`.

## grain-mulog-aws-cloudwatch-emf-publisher

[mulog](https://github.com/BrunoBonacci/mulog) publisher for CloudWatch metrics:

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "464fc35e423ea20a2914804307c681f4ca2ac196"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

## Deprecated Packages

The following packages are deprecated and will be removed in a future release:

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |
