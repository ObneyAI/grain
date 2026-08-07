---
name: event-model-tend
description: >-
  Make targeted changes to an existing Grain Event Model while preserving its topology-only boundary and Allium trace links. Use for blocks, schemas, intent edges, flows, service areas, validator findings, or synchronized def-site produces/reads declarations; use Allium tend for behavioural rules.
---

# Event Model Tend

Inspect the model, relevant `def*` definitions, live catalog, and linked Allium
declarations. Change only topology. If the request changes preconditions,
outcomes, invariants, or examples, invoke Allium `tend` as a separate phase.

Keep block namespaces, kind-typed references, flow grammar, registered schemas,
and def-site produces/reads declarations synchronized. Commands retain rule links
and screens surface links after renames or moves. Run lenient validation while
editing, then strict runtime and composition validation before finishing.
