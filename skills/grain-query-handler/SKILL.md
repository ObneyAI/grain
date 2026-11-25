---
name: grain-query-handler
description: Implement Grain data queries and Datastar page queries with bounded reads, authorization and subscriptions.
---

# Grain queries

Use `defquery` from `ai.obney.grain.query-processor.interface`. Register the
[input schema](../grain-schema/SKILL.md) under the query’s qualified name. Take input from
`:query` and pass the full context to keyed reads. Use `value` for an ID or
`page` for a list. Enrich only the returned entries. Return `{:query/result value}`
or a Cognitect anomaly. For HTTP access, supply an `:authorized?` predicate
that checks access to the requested data.

For a routed Datastar page, declare `:datastar/path` and return the compiled
[view](../grain-view/SKILL.md) under `:datastar/hiccup`. For live updates,
declare every model the view reads in `:grain/read-models` as `{model-name version}`
and wire `:event-pubsub`. For a composed `rmp/query`, `(rmp/query-models definition)`
provides that map. Rebuild generated routes after changing route metadata.
[Event subscriptions](../../docs/datastar.md#event-subscription-filters)
