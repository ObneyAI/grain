---
name: grain-service
description: Implement or wire a feature across a Grain service’s schemas, handlers, projections and UI.
---

# Grain services

Find the service that owns the behaviour in the Event Model. Trace a nearby feature
from its declarations to the application base. Preserve registered names when moving
code. Event names are persisted, and processor names identify checkpoints.

- For writes, define each event with [`defevent`](../grain-event/SKILL.md) and implement the [command](../grain-command-handler/SKILL.md) that emits it.
- For reads, fold those events into a [keyed model](../grain-read-model/SKILL.md) and expose it through a [query](../grain-query-handler/SKILL.md). A Datastar query can render a [view](../grain-view/SKILL.md).
- For background work, use a [processor](../grain-todo-processor/SKILL.md). Use a [periodic task](../grain-periodic-task/SKILL.md) when a schedule initiates the work.

Load the service’s declaration namespaces from the application before building routes,
starting workers or verifying the [Event Model](../event-model/SKILL.md). The macros
register on load. Inspect them with [`tools/catalog`](../../docs/code-agent-tools.md#catalog).
