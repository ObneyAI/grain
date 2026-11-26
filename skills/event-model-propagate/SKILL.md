---
name: event-model-propagate
description: Derive Grain tests for registered topology, boot validation and Allium trace links.
---

# Test topology

Load the application's model and handlers in the test fixture. Assert
`(:valid? (emv/verify-event-model!))`. To test a boot failure, pass a model
with a specific mismatch to `emv/verify-or-throw!` via `{:model model}` and
check the finding under `[:verdict :findings]` in `ex-data`.

In development or CI, assert `:valid?` on the composition check in the
`event-model` skill. Derive tests for domain behaviour from Allium with `propagate`.
