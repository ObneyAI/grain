# Grain

Opinionated building blocks for AI-native information systems in Clojure.

## What is Grain?

Grain is a set of composable building blocks for building event-sourced information systems using CQRS (Command Query Responsibility Segregation). The event store is the single source of truth — humans, application code, and AI agents all read from and write to the same immutable log of facts.

Multi-tenancy is built in: every event-store operation is scoped to a `:tenant-id`, and the Postgres backend enforces isolation with Row-Level Security and per-tenant advisory locks. Start with an in-memory event store for quick iteration, then swap in Postgres with a single line change.

For multi-instance deployments, an opt-in control plane coordinates tenant assignment across nodes using event-sourced leases — no external coordination service required.

## Architecture

```
                            ┌────────────────────────────────────────────────────────┐
                            │                      Write Side                        │
                            │                                                        │
  POST /command ───────────▶│  Command Processor ──▶ Validate ──▶ Handler ──▶ Events │
                            │         ▲                  ▲                      │    │
                            └─────────│──────────────────│──────────────────────┼────┘
                                      │                  │                      │
                                      │                  │ read                 │ append
                                      │        ┌─────────┴─────────┐            │
                                      │        │                   │            ▼
            Todo Processors ──────────┘        │    Read Model     │◀───┬───────────┐
                   ▲                           │                   │    │   Event   │
                   │                           └───────────────────┘    │   Store   │
                   │                                     ▲         proj └───────────┘
                   │        ┌────────────────────────────│─────────────┐      │
                   │        │              Read Side     │             │      │ publish
                   │        │                            │             │      ▼
                   │        │  Query Processor ──────────┘             │ ┌─────────┐
  POST /query ─────────────▶│                                          │ │ Pub/Sub │
                   │        └──────────────────────────────────────────┘ └─────────┘
                   │                                                          │
                   └──────────────────────────────────────────────────────────┘
                                                (async)
```

**Commands** are the only path to state change — they validate business rules and emit events. **Events** are immutable facts stored in the event store. **Queries** read from projections (read models) built from events. **Todo Processors** react to events asynchronously, enabling event-driven workflows.

## Core Concepts

> Full documentation with code examples: [docs/core-concepts.md](docs/core-concepts.md)

- **Commands** — the only path to state change. Validate business rules and emit events.
- **Events** — immutable facts stored in the event store. Body fields, tags, UUID v7 IDs.
- **Queries** — read from projections without side effects.
- **Read Models** — pure reducers `(state, event) -> state` with two-tier caching (in-process LRU + LMDB on disk).
- **Todo Processors** — react to events asynchronously with configurable checkpointing (at-most-once or at-least-once).
- **Periodic Tasks** — run on cron or interval schedules with CAS deduplication across nodes.
- **Authorization** — all commands and queries require an `:authorized?` predicate. Deny by default.

## Distributed Coordination

> Full documentation: [docs/distributed-coordination.md](docs/distributed-coordination.md)

The `grain-control-plane` package provides coordinator election, tenant lease management, and tenant-aware routing — all event-sourced on the shared Postgres event store. No ZooKeeper, etcd, or Consul required.

## Datastar (Reactive UI)

> Full documentation: [docs/datastar.md](docs/datastar.md)

Grain integrates with [Datastar](https://data-star.dev/) for reactive server-rendered UIs. Queries return hiccup that streams to the browser over SSE — the server re-renders when domain events fire and Datastar patches the DOM.

## Getting Started

Add to your `deps.edn`:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "0900cb22bf43121edbe5287b0e65ea66b729b091"
 :deps/root "projects/grain-core-v2"}
```

See `bases/example-base` and `components/example-service` for a complete example application. Run `development/src/example_app_demo.clj` to start and interact with the example system.

For multi-instance deployments, add the [control plane](docs/distributed-coordination.md) package.

## Packages

> Full package details and deps.edn snippets: [docs/packages.md](docs/packages.md)

| Package | Summary |
| --- | --- |
| **grain-core-v2** | Multi-tenant CQRS/Event Sourcing with in-memory event store |
| **grain-control-plane** | Distributed coordination — coordinator election, tenant leases, routing |
| **grain-datastar** | Reactive server-rendered UIs with [Datastar](https://data-star.dev/) over SSE |
| **grain-event-store-postgres-v3** | Multi-tenant Postgres backend with RLS, per-tenant advisory locks, and Fressian serialization |
| **grain-mulog-aws-cloudwatch-emf-publisher** | AWS CloudWatch metrics & dashboards |

## Why Grain?

We use [Event Modeling and Event Sourcing](https://leanpub.com/eventmodeling-and-eventsourcing) to design [Simple](https://www.youtube.com/watch?v=SxdOUGdseq4) systems. Grain provides a single, composable toolkit for building multi-tenant, event-sourced applications in Clojure.

[Polylith](https://polylith.gitbook.io/polylith) enables us to evolve components independently and publish standalone tools from a single repository.

## Status

Grain is MIT licensed. We use it in production, but it's actively evolving. The core CQRS/Event Sourcing components are stable. The control plane is new and under active development.

## More Information

- **Docs**: [Core Concepts](docs/core-concepts.md) | [Distributed Coordination](docs/distributed-coordination.md) | [Datastar](docs/datastar.md) | [Packages](docs/packages.md)
- **Examples**: `bases/example-base`, `components/example-service`, `development/src/example_app_demo.clj`
- **Slack**: [#grain](https://clojurians.slack.com/archives/C099K3D7XRV) on Clojurians
- **Issues**: [GitHub Issues](https://github.com/ObneyAI/grain/issues)
