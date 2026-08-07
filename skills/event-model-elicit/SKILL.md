---
name: event-model-elicit
description: >-
  Add Grain Event Model topology to an Allium intent-first elicitation. Use after or alongside Allium elicit for a new Grain service area or feature: identify CQRS blocks, schemas, edges, screens, flows, and explicit Allium trace links, then validate against the live runtime when available.
---

# Event Model Elicit

Run Allium `elicit` first for observable behaviour. From the agreed rules and
surfaces, identify only the Grain topology required to realize them: commands,
events, projections, queries, processors, schedules, screens, and significant
flows. Do not restate rule outcomes in Event Model.

Create or extend one `defeventmodel` area. Add command-to-rule and
screen-to-surface `:grain/allium` links. Validate incrementally, accepting that
new blocks may remain aspirational until implementation loads their registries.
Hand behavioural changes back to Allium; hand the finished topology to
`event-model-propagate` and implementation.
