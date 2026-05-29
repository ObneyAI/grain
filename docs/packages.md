# Packages

## grain-core-v2

Multi-tenant CQRS/Event Sourcing with an in-memory event store. Includes v2 processors (command, read-model, todo), v2 request handler, query processor, and pub/sub:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-core-v2"}
```

## grain-control-plane

Distributed coordination for multi-instance deployments. Coordinator election, tenant lease management, pull-based polling with batch checkpointing, periodic task scheduling with CAS deduplication, and tenant-aware load balancer routing:

```clojure
obneyai/grain-control-plane
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-control-plane"}
```

Includes the core CQRS components (event store, read model processor, todo processor, periodic task, pub/sub).

## grain-datastar

Server-rendered reactive UIs with [Datastar](https://data-star.dev/). Streams hiccup-rendered HTML over SSE, with event-driven re-rendering, SSE connection reuse, auto-generated auth redirects, context-dependent gate interceptors, Malli-based JSON coercion, and automatic Pedestal route generation:

```clojure
obneyai/grain-datastar
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-datastar"}
```

Includes the core CQRS components (command/query/read-model processors, event store, pub/sub). See `components/datastar` for the full source.

## grain-tui

Terminal UIs over stdio. Renders `:tui/...` query metadata as alt-screen or main-screen sessions with snapshot or streaming projections, tagged-tuple keymaps, palette overlays, and an extension point (`defelement`) for custom cell-rendering primitives. Per-session pubsub subscriptions are auto-resolved from `:grain/read-models` — the same machinery the Datastar adapter uses, just with a different transport and rendering pipeline:

```clojure
obneyai/grain-tui
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ddb91e512966f6a93d757e18ef5d39fbacf18f14"
 :deps/root "projects/grain-tui"}
```

Includes the core CQRS components (command/query/read-model processors, event store, pub/sub). See [`docs/tui.md`](./tui.md) for architecture and [`docs/tui-manual.md`](./tui-manual.md) for a build-from-scratch walk-through. Working demo at `development/src/grain_tui_demo.clj`.

## grain-code-agent-tools

Dev-only nREPL-facing tools for coding agents working against a live Grain app. Exposes registered commands, queries, read models, todo processors, periodic triggers, schemas, tenant-scoped event reads, projections, command/query invocation, validation, and runtime diagnostics as plain EDN:

```clojure
obneyai/grain-code-agent-tools
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
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
diagnostics. See [Code Agent Tools](code-agent-tools.md) for the full guide.

## grain-event-store-postgres-v3

Multi-tenant Postgres backend with Row-Level Security, per-tenant advisory locks, Fressian binary serialization, and tenant-scoped operations. All read and append operations require a tenant ID, ensuring structural data isolation:

```clojure
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

## grain-event-store-sqlite-v3

Embedded SQLite backend implementing the v3 event store protocol for single-process deployments where running Postgres is overkill. WAL mode with `BEGIN IMMEDIATE` per append, a tenant-scoped events table plus a normalized `event_tags` join table for indexed superset tag filtering, and Fressian binary serialization. Same tenant-scoped API as the Postgres backend — swap the `:conn` type to move between them:

```clojure
obneyai/grain-event-store-sqlite-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-event-store-sqlite-v3"}
```

## grain-mulog-aws-cloudwatch-emf-publisher

[mulog](https://github.com/BrunoBonacci/mulog) publisher for CloudWatch metrics:

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "6120a4b3dceaff827bddd7cbf0703ead0131ab11"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

## Deprecated Packages

The following packages are deprecated and will be removed in a future release:

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |
