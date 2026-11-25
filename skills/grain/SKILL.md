---
name: grain
description: Coordinate Allium behaviour specs, Grain Event Models, tests and implementation for a Grain feature or spec review.
---

# Grain

The separately installed Allium skills describe behaviour. Grain Event Models describe service topology.
Choose the phase that matches the request:

| Task | Allium skill | Grain topology skill |
| --- | --- | --- |
| Discover requirements | `elicit` | `event-model-elicit` |
| Recover a spec from code | `distill` | `event-model-distill` |
| Edit a spec | `tend` | `event-model-tend` |
| Derive tests | `propagate` | `event-model-propagate` |
| Reconcile drift | `weed` | `event-model-weed` |

For a feature spanning both specs, establish behaviour and topology, link commands
to rules and screens to surfaces, then use [grain-service](../grain-service/SKILL.md)
to implement against the tests. Check changed
specs with `allium check <path>` and `allium analyse <path>`. Use the `event-model` skill
for runtime validation and the development-time link check.
