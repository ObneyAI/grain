---
name: event-model-tend
description: Edit an existing Grain Event Model when blocks, schemas, dependencies, flows or trace links change.
---

# Edit topology

Locate the block by kind and qualified name. Update its area entry, affected
flow endpoints, dependency references and runtime declarations. When a rule or
surface moves, update its `:grain/allium` path, kind and name. Use Allium `tend`
if its behaviour changes.

Run the runtime and composition checks in the `event-model` skill.
