---
name: grain-command-handler
description: Implement Grain command handlers, event emission and optimistic concurrency checks.
---

# Grain commands

Use `defcommand` from `ai.obney.grain.command-processor-v2.interface`.
Put options before the docstring and `[context]`. Read inputs from `:command` and
pass the tenant-bearing context to read helpers. Supply `:authorized?` for HTTP
or Datastar access. Direct `process-command` calls bypass that predicate.

Return a Cognitect anomaly for a rejected command. Define each emitted type with
[`defevent`](../grain-event/SKILL.md). On success, return events built with `->event`
in `:command-result/events` and caller-facing data in `:command/result`.

When concurrent commands could both pass admission, include a `:command-result/cas`
guard on the append. Select the event types and tags that determine the invariant.
Check the supplied events with a pure `:predicate-fn` using `reduce`. A false
predicate produces a conflict. [Command example](../../docs/core-concepts.md#commands-write-side)
