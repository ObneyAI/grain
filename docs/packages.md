# Packages

## grain-core-v2

Multi-tenant CQRS/Event Sourcing with an in-memory event store. Includes v2 processors (command, read-model, todo), v2 request handler, query processor, and pub/sub:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ce037ecfc0bd126b0ca2a3164c6018c0c4a8974c"
 :deps/root "projects/grain-core-v2"}
```

## grain-control-plane

Distributed coordination for multi-instance deployments. Coordinator election, tenant lease management, pull-based polling with batch checkpointing, periodic task scheduling with CAS deduplication, and tenant-aware load balancer routing:

```clojure
obneyai/grain-control-plane
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ce037ecfc0bd126b0ca2a3164c6018c0c4a8974c"
 :deps/root "projects/grain-control-plane"}
```

Includes the core CQRS components (event store, read model processor, todo processor, periodic task, pub/sub).

## grain-datastar

Server-rendered reactive UIs with [Datastar](https://data-star.dev/). Streams hiccup-rendered HTML over SSE, with event-driven re-rendering, SSE connection reuse, auto-generated auth redirects, context-dependent gate interceptors, Malli-based JSON coercion, and automatic Pedestal route generation:

```clojure
obneyai/grain-datastar
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ce037ecfc0bd126b0ca2a3164c6018c0c4a8974c"
 :deps/root "projects/grain-datastar"}
```

Includes the core CQRS components (command/query/read-model processors, event store, pub/sub). See `components/datastar` for the full source.

## grain-event-store-postgres-v3

Multi-tenant Postgres backend with Row-Level Security, per-tenant advisory locks, Fressian binary serialization, and tenant-scoped operations. All read and append operations require a tenant ID, ensuring structural data isolation:

```clojure
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ce037ecfc0bd126b0ca2a3164c6018c0c4a8974c"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

## grain-mulog-aws-cloudwatch-emf-publisher

[mulog](https://github.com/BrunoBonacci/mulog) publisher for CloudWatch metrics:

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "ce037ecfc0bd126b0ca2a3164c6018c0c4a8974c"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

## Deprecated Packages

The following packages are deprecated and will be removed in a future release:

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |
