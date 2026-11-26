---
name: grain-periodic-task
description: Schedule Grain trigger events per tenant and prevent duplicate triggers across nodes.
---

# Grain periodic tasks

Use `defperiodic` from `ai.obney.grain.periodic-task.interface`. Declare an
interval such as `{:schedule {:every 5 :duration :minutes}}` or a five-field cron
schedule such as `{:schedule {:cron "0 6 * * *" :timezone "UTC"}}`.

The handler receives `[tenant-id tick]` for each domain tenant. The tick is a
`java.time.Instant`. Define the trigger event with [`defevent`](../grain-event/SKILL.md).
Return `{:result/events [...]}` to emit it or `{}`
to skip it. Delegate follow-up work to a [processor](../grain-todo-processor/SKILL.md).

Each node runs its own schedule. If only one trigger may fire per business period,
derive that period from the tick and guard the append with `:result/cas`. Interval
ticks start at node startup and can differ across nodes. Restart the triggers
after schedule changes. [Periodic task example](../../docs/core-concepts.md#periodic-tasks)
