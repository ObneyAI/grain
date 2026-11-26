---
name: grain-schema
description: Define Malli payload schemas for Grain commands, queries, events and keyed projections.
---

# Grain schemas

Put the schema on the declaration that uses it.

- Define every event with `defevent` and its payload `:schema`. [Event definitions](../grain-event/SKILL.md)
- On a keyed `defreadmodel`, put the per-instance state shape in `:state-schema`.
  Put the stored output shape in `:result-schema` when `:result` transforms it.
- In `defeventmodel`, match each block’s `:schema` to its runtime declaration.
  A read-model block describes state.
- `defcommand` and `defquery` look up inputs by qualified name. Register those
  inputs with `defschemas`, which also supports shared named Malli types.

Load the declarations, then check a payload with `(tools/validate schema-name payload)`
or a full envelope with `(tools/validate kind schema-name envelope)`, where `kind`
is `:command`, `:query` or `:event`. [Validation API](../../docs/code-agent-tools.md)
