---
name: event-model-propagate
description: >-
  Generate Grain topology and composition contract tests after Allium propagates behavioural tests. Use for strict Event Model validation tests, boot-guard tests, coverage checks, or deterministic validation of :grain/allium links. Do not generate behavioral Given/When/Then from Event Model.
---

# Event Model Propagate

Use Allium `propagate` for behavioural integration, property, and state-machine
tests. Event Model propagation adds only structural contracts:

- Assert the registered model passes `verify-event-model!` in the fully loaded app.
- Assert `verify-or-throw!` rejects representative incomplete or drifted models.
- Assert `validate-spec-composition` passes from the repository root.
- Cover new validator rules or trace failure modes when the schema changes.

Do not create command behavior tests from Event Model. Confirm new structural
tests fail for the intended missing topology before implementing the correction.
