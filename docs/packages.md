# Packages

Add the packages you need to `:deps` in `deps.edn`. Each uses
`obneyai/<package>` and `:deps/root "projects/<package>"` at the same Git SHA.
For example:

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "15b0d3ef878c0e7aabbb929dcbc133401ce29ebf"
 :deps/root "projects/grain-core-v2"}
```

## grain-core-v2

Core components for [event-sourced applications](core-concepts.md).

## grain-control-plane

[Distributed coordination](distributed-coordination.md) for multi-node deployments.

## grain-datastar

Server-rendered UIs with [Datastar](datastar.md).

## grain-code-agent-tools

[Development tools](code-agent-tools.md) for inspecting a running Grain app over nREPL.

## grain-event-model

[Event Model](event-model.md) registration and validation.

## grain-event-store-postgres-v3

Postgres backend for the [v3 event store](event-stores.md).

## grain-event-store-sqlite-v3

Embedded SQLite backend for the [v3 event store](event-stores.md#sqlite).

## grain-mulog-aws-cloudwatch-emf-publisher

[CloudWatch EMF publisher](../components/mulog-aws-cloudwatch-emf-publisher/README.md) for μ/log.

## Deprecated Packages

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |
