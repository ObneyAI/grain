# Packages

Add the packages you need to the `:deps` map in `deps.edn`.

## grain-core-v2

Core components for [event-sourced applications](core-concepts.md).

```clojure
obneyai/grain-core-v2
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-core-v2"}
```

## grain-control-plane

[Distributed coordination](distributed-coordination.md) for multi-node deployments.

```clojure
obneyai/grain-control-plane
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-control-plane"}
```

## grain-datastar

Server-rendered UIs with [Datastar](datastar.md).

```clojure
obneyai/grain-datastar
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-datastar"}
```

## grain-code-agent-tools

[Development tools](code-agent-tools.md) for inspecting a running Grain app over nREPL.

```clojure
obneyai/grain-code-agent-tools
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-code-agent-tools"}
```

## grain-event-model

[Event Model](event-model.md) registration and validation.

```clojure
obneyai/grain-event-model
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-event-model"}
```

## grain-event-store-postgres-v3

Postgres backend for the [v3 event store](event-stores.md).

```clojure
obneyai/grain-event-store-postgres-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-event-store-postgres-v3"}
```

## grain-event-store-sqlite-v3

Embedded SQLite backend for the [v3 event store](event-stores.md#sqlite).

```clojure
obneyai/grain-event-store-sqlite-v3
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-event-store-sqlite-v3"}
```

## grain-mulog-aws-cloudwatch-emf-publisher

[CloudWatch EMF publisher](../components/mulog-aws-cloudwatch-emf-publisher/README.md) for μ/log.

```clojure
obneyai/grain-mulog-aws-cloudwatch-emf-publisher
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "0555370637af92daea24132859f53dc44cdba8a5"
 :deps/root "projects/grain-mulog-aws-cloudwatch-emf-publisher"}
```

## Deprecated Packages

| Package | Replacement |
| --- | --- |
| grain-core | grain-core-v2 |
| grain-event-store-postgres-v2 | grain-event-store-postgres-v3 |
| grain-dspy-extensions | None (deprecated) |
