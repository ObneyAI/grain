---
name: event-model-weed
description: Diagnose drift between a Grain Event Model, the live registries and linked Allium declarations.
---

# Reconcile topology

Load the affected namespaces. Inspect `:findings` from `(emv/verify-event-model!)`.
In `emv/event-model-coverage`, `:undeclared` means modeled but missing at runtime;
`:uncovered` means live but missing from a modeled area.

Compare each discrepancy with the intended design before changing the model or
implementation. Use Allium `weed` for behavioural drift. Rerun the runtime and
composition checks in the `event-model` skill after repairs.
