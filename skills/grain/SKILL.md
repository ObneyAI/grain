---
name: grain
description: >-
  Drive a Grain engineering goal through one composed Allium and Event Model loop. Use for Grain features, specification work, implementation, reconciliation, or when the user invokes Grain as the entry point. Delegate observable behaviour to installed Allium skills and add Grain service-topology work; continue until specs, tests, code, runtime validation, and trace links agree.
---

# Grain

Use Allium as the behavioural host and Event Model as its Grain topology extension.
Never copy behavioural rules or Given/When/Then into Event Model.

## Preflight

Confirm the Allium entry and verb skills are installed. If not, pause and give the
installation command appropriate to the active editor from
https://github.com/juxt/allium. Do not vendor or silently replace Allium.

Inspect `.allium` files, `defeventmodel` forms, the live Grain catalog when
available, and current tests. Decide whether the goal changes behaviour,
topology, or both.

## Composed loop

1. Gather durable context with Allium `elicit` or `distill`, then run the matching
   `event-model-elicit` or `event-model-distill` phase for topology.
2. Use Allium `propagate` for behavioural tests. Use `event-model-propagate` for
   topology, boot-mandate, and composition contract tests.
3. Implement and run tests. Confirm new behavioural tests were red before the
   implementation when doing spec-first work.
4. Verify with Allium `weed`, `allium check`, and `allium analyse`; then run
   `event-model-weed`, strict runtime validation, and composition validation.
5. If behaviour was wrong, revise with Allium `tend`; if topology was wrong, use
   `event-model-tend`. Re-propagate and repeat.

Finish only when behavioural specs, topology, generated tests, implementation,
runtime registries, and Allium trace links agree and no open question remains.

Read the `event-model` skill for the Grain schema and exact REPL APIs.
