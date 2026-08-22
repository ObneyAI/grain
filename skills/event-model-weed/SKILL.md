---
name: event-model-weed
description: >-
  Reconcile Grain Event Model topology with the live runtime and its linked Allium declarations as the topology phase after Allium weed. Use for spec-code drift, strict boot failures, coverage/schema/wiring/edge findings, or broken command-to-rule and screen-to-surface links.
---

# Event Model Weed

Run Allium `weed` first to reconcile observable behavior. Then load the Grain app
and compare `(em/registered-model)` with `(tools/catalog)` using strict Event Model
validation and coverage. Classify every finding as model drift, implementation
drift, or an intentional aspirational gap; resolve rather than suppress it.

Run `validate-spec-composition` to check required and optional Allium links. A
renamed rule or surface must update the Event Model reference; topology must not
copy the changed behavior. Finish only when tests, Allium verification, strict
runtime topology, and the composition gate are all clean.
