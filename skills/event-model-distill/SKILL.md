---
name: event-model-distill
description: Recover a Grain Event Model from existing code and live registries.
---

# Distill topology

Load the service namespaces and compare `(tools/catalog)` with their declarations.
Recover schemas, topics, schedules and declared dependencies. Trace handler calls
for undeclared edges and UI entry points for screens and flows. Record uncertain
intent as an open question.

Use Allium `distill` for behaviour that still needs a spec. Link each command and
screen to its verified declaration, then run the checks in the `event-model` skill.
