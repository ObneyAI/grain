---
name: em-propagate
description: "Generate tests from a grain event model. Use when the user wants to propagate tests, turn an event model into clojure.test, write the boot-guard contract test, make a defeventmodel's Given/When/Then executable, generate command/query integration tests over the live runtime, assert the model strict-validates against the running app, or check which blocks in the model still lack test coverage. The model is the parent, the tests are the offspring."
---

# em-propagate

Generate tests from a grain event model. Propagation is how a model reproduces: a
`(defeventmodel :area {...})` is the parent, the generated `clojure.test`
namespaces are the offspring. The validators guarantee *completeness* (every block
and every command's Given/When/Then becomes a test obligation); you handle the
*implementation bridge* — turning that obligation into a test that runs against
grain's **live runtime over the app's nREPL (:7888)**.

Grain has no static compiler. The oracle is the running app: requiring the app's
base (`ai.obney.grain.example-base.core`, or your app's `…-base.core`) loads the
`def*` handlers into the global registries and runs `(defeventmodel …)`, so the
model and the runtime it describes are both *live* at test load. That is what
makes the tests below real rather than paper.

## Purpose

Produce two tiers of test from a registered model:

1. **Structural / contract test** — one test asserting the model strict-validates
   against the live runtime. This is the literal boot-guard contract:
   `verify-or-throw!` is what refuses to boot the app, so the test pins exactly
   what production enforces (full coverage + declared `:produces`/`:reads` edges +
   Given/When/Then on every command + all schema/wiring/flow checks).

2. **Behavioural / executable Given-When-Then** — for each command's
   `:given-when-thens` (carried on the model as *data*, never run by the
   validator), an executable `clojure.test` that `install!`s the runtime, sets up
   **Given** by invoking prerequisite commands, runs **When** via
   `invoke-command!`, and asserts **Then** via `events`/`projection`/`invoke-query`
   or the returned anomaly. This is where the deferred *executable* GWT lives.

## When to use

- The user says "propagate tests", "generate tests from the model", "make these
  GWTs executable", "write the boot-guard test", "test this area".
- A model already validates **lenient** (`validate-event-model` `:valid?` true) and
  you want to lock it down with tests before/after implementing handlers.
- After `em-tend` edited a model or `em-distill` produced one — re-propagate to
  refresh the obligations.

## When NOT to use (boundaries)

- **The model is wrong or incomplete** (missing GWT, malformed schema, dangling
  edge) → that is `em-tend`'s job (edit the `defeventmodel`). Propagate consumes a
  model that is at least lenient-valid; do not paper over model defects with tests.
- **The model and runtime have drifted** (`:block/undeclared`, `:schema/mismatch`,
  `:wiring/mismatch`, `:produces/mismatch`) → that reconciliation is `em-weed`.
  Resolve drift there first; a structural test over a drifted model just reproduces
  the drift as a red test.
- **There is no model yet** → `em-distill` (from `catalog`) or `em-elicit` (from
  intent) build one; then return here.
- **Concept reference** → the entry skill `event-model`.

A useful split: em-propagate writes tests that *consume* the model; em-tend/em-weed
*change* the model. If a generated test is red because the **model** is wrong, hand
back to tend/weed. If it is red because the **code** is wrong, that is propagate
doing its job (see "Confirm red first").

## The REPL oracle (exact calls — do not invent others)

Dev-only loop, over the running app's nREPL on port 7888:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])  ; dev-only
(require '[ai.obney.grain.event-model.interface :as em])          ; defeventmodel / registered-model
(require '[ai.obney.grain.event-model-validator.interface :as emv]) ; shippable

(em/registered-model)                                   ; the live model (map of areas)
(tools/catalog)                                         ; live blocks, to cross-check coverage
(tools/event-model-coverage (em/registered-model))      ; which blocks lack a spec/test target
(tools/validate-event-model (em/registered-model) {:strict true}) ; the boot-guard verdict, as data
(emv/verify-event-model!)                               ; same strict verdict, shippable, never throws
(emv/verify-or-throw!)                                  ; the actual boot-guard (throws on fatal)
(tools/guide :findings)                                 ; finding taxonomy + how to resolve each
(tools/guide :example/create-counter {:spec (em/registered-model)}) ; usage card: docstring + schema + GWT

;; executable GWT primitives (require an installed runtime):
(tools/install! {:system app :context ctx :mode :dev})
(tools/invoke-command! {:command/name :example/create-counter :name "A"})
(tools/invoke-query    {:query/name :example/counters})
(tools/events     {:types #{:example/counter-created} :limit 10})
(tools/projection :example/counters)
```

`:valid?` is true when no `:error`-severity finding is present. Strict mode adds the
completeness tier; `:gwt/missing` (strict, fatal) is the finding propagate exists to
discharge.

## Process

### Phase 0 — gather

1. Get the model: `(em/registered-model)` (or `(tools/validate-event-model-var 'my-app.model/event-model)` if it lives in a var). Read the area's `:commands` (with `:given-when-thens`, `:schema`, `:reads`, `:produces`), `:events` (with `:schema`), `:read-models` (`:consumes`), `:queries`, `:flows`.
2. See the verdict the boot-guard would render: `(tools/validate-event-model (em/registered-model) {:strict true})`. If it is **not** `:valid?` for a *model* reason (`:gwt/missing`, `:produces/undeclared`, `:reads/undeclared`, malformed schema) → that block is not ready to propagate; route to `em-tend`. If it is not valid for a *drift* reason (`:schema/mismatch`, `:wiring/mismatch`, `:block/undeclared`) → route to `em-weed`.
3. `(tools/event-model-coverage (em/registered-model))` to enumerate commands; each command with `:given-when-thens` is one or more test obligations.
4. Read the existing test conventions (this is a Polylith repo): tests live at `<brick>/test/<ns-path>_test.clj` as `clojure.test` namespaces and **require the app base** so registries + the registered model populate at load. Mirror `components/code-agent-tools/test/.../mandate_test.clj` (structural) and `.../interface_test.clj` `installed-runtime-executes-against-example-app` (executable). Do not invent a fixture if one of these patterns fits.

### Phase 1 — the structural / contract test (always generate exactly one per app)

This is the highest-value, lowest-cost test: it asserts the whole model against the
live runtime, and it is the same check `verify-or-throw!` runs at boot.

5. Generate a namespace that requires the **shippable** validator and the app base (so it is committable with no dev-only dependency), asserting the strict verdict is valid and fatal-free, and that the boot-guard does not throw. Mirror `registered-example-model-verifies-strict` / `verify-or-throw-passes-on-the-good-model`.

### Phase 2 — executable Given-When-Then (one deftest per command, a testing block per GWT row)

6. For each command, read its `:given-when-thens`. For each row, map the prose to runtime calls:
   - **Given** → invoke the prerequisite commands with `invoke-command!` (e.g. "a counter named A already exists" → first `(invoke-command! {:command/name :…/create-counter :name "A"})`). A clean in-memory store per test means "no counter exists" needs no setup. If a precondition cannot be reached through a command, seed events directly via the event-store interface (`es/append`) — but prefer command replay so you exercise the real write path.
   - **When** → the command under test via `invoke-command!`, using its `:schema` to build valid params.
   - **Then** →
     - *event recorded* → `(tools/events {:types #{<the :produces event>} :limit N})`, assert count and (from the event's `:schema`) body fields.
     - *rejected* → `invoke-command!` returns a cognitect anomaly map; assert `(:cognitect.anomalies/category result)` is the expected category (`…/conflict`, `…/not-found`, …) **and** that no new event of the produced type was recorded.
     - *projection/state* → `(tools/projection <:reads read-model>)` or `(tools/invoke-query {:query/name …})`, assert `:query/result`.
7. Isolate every case: `ig/init` a fresh subset of the app system (`event-store` + `cache` + `context`), `tools/install!` it, run the case, `ig/halt!` in a `finally`. The in-memory event-store-v3 gives a clean slate per init, so Given starts empty.

### Phase 3 — confirm red first, then close the loop

8. **Confirm the tests fail before implementing.** This is the discipline: a GWT you just made executable should be red if the handler does not yet emit its declared `:produces` event (the `events` assertion fails) or does not yet reject the bad case (no anomaly). The structural test is red while any command is missing GWT/`:produces`/`:reads` (`:gwt/missing`, `:produces/undeclared`, `:reads/undeclared`). Run the tests, observe red, *then* implement the handler / add the def-site `:grain.event-model/produces`/`:reads` annotation to turn them green.
9. Run the suite (Polylith): `clojure -M:test -m cognitect.test-runner` for the brick, or `poly test`. Then re-check the oracle: `(emv/verify-event-model!)` must be `:valid?` — when it is, the app boots through `verify-or-throw!` and the green structural test guarantees it stays that way.

## Guardrails

- **Do not execute GWT in the validator** — the validator never runs Given/When/Then; propagate is the *only* place that data becomes executable. Conversely, a structural test alone does not prove handlers emit events; you need the executable tier for that ("declaration ↔ behaviour" gap).
- **Commit the shippable validator, not the dev tool.** Structural tests use `ai.obney.grain.event-model-validator.interface` (no dev-only dep). The `code-agent-tools` tools (`install!`/`invoke-command!`/`events`/`projection`) are the dev/test-time execution surface — fine in `test/`, never in shipped `src/`.
- **Reach the app base.** Every generated test must `(:require [ai.obney.grain.<app>-base.core])` (even if only for side-effect loading), or the registries are empty and `validate-event-model` degrades to spec-internal checks and `invoke-command!` has nothing to call.
- **Fresh runtime per behavioural case**; always `ig/halt!` in `finally`. Shared mutable state across cases makes Given unreliable.
- **Don't duplicate existing coverage.** If a hand-written integration test already exercises a GWT row end-to-end, reference it; propagate's value is completeness against the model's obligation list, not regenerating green tests.
- **A red structural test for a model reason is not yours to fix** — `:gwt/missing` / malformed schema → `em-tend`; `:schema/mismatch` / `:block/undeclared` / `:wiring/mismatch` → `em-weed`. Use `(tools/guide :findings)` to classify.

## Worked snippet

Structural / boot-guard contract test (mirrors `mandate_test.clj`):

```clojure
(ns ai.obney.grain.example-service.event-model-contract-test
  "The boot-guard contract, pinned. Requiring the app base loads the def* handlers
   and runs (defeventmodel :example …), so the validator reconciles the registered
   model against the LIVE runtime in strict mode — exactly as verify-or-throw! does
   at boot."
  (:require [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.grain.example-base.core]            ; loads handlers + registers model
            [clojure.test :refer [deftest is]]))

(deftest model-strict-validates-against-live-runtime
  (let [v (emv/verify-event-model!)]                       ; strict verdict, never throws
    (is (:valid? v) (str "boot-guard would reject: " (vec (:findings v))))
    (is (true? (get-in v [:summary :strict])))
    (is (zero? (get-in v [:summary :fatal])))))

(deftest app-would-boot
  (is (map? (emv/verify-or-throw!))))                      ; the real boot-guard, must not throw
```

Executable Given/When/Then for `:example/create-counter` (mirrors
`interface_test.clj`'s `installed-runtime-executes-against-example-app`):

```clojure
(ns ai.obney.grain.example-service.create-counter-gwt-test
  (:require [ai.obney.grain.code-agent-tools.interface :as tools]
            [ai.obney.grain.example-base.core :as base]
            [clojure.test :refer [deftest is testing]]
            [integrant.core :as ig]))

(defn- with-runtime
  "Fresh in-memory runtime per case → Given starts from an empty event store."
  [f]
  (let [app (ig/init (select-keys base/system [::base/event-store ::base/cache ::base/context]))]
    (try (tools/install! {:system app :context (::base/context app) :mode :dev})
         (f)
         (finally (ig/halt! app)))))

(deftest create-counter
  ;; GWT row 1: given no counter "A", when create "A", then counter-created recorded.
  (testing "happy path emits the produced event"
    (with-runtime
      (fn []
        (let [r (tools/invoke-command! {:command/name :example/create-counter :name "A"})]  ; When
          (is (nil? (:cognitect.anomalies/category r)))
          (is (= 1 (count (tools/events {:types #{:example/counter-created} :limit 10})))))))) ; Then
  ;; GWT row 2: given a counter "A" already exists, when create "A", then rejected as conflict.
  (testing "duplicate name is rejected as a conflict and records no new event"
    (with-runtime
      (fn []
        (tools/invoke-command! {:command/name :example/create-counter :name "A"})            ; Given
        (let [r (tools/invoke-command! {:command/name :example/create-counter :name "A"})]   ; When
          (is (= :cognitect.anomalies/conflict (:cognitect.anomalies/category r)))           ; Then
          (is (= 1 (count (tools/events {:types #{:example/counter-created} :limit 10})))))))))
```

Run it, see the second `is` (or the whole thing) fail before the handler enforces
uniqueness/emits the event, then implement until green — and confirm the loop closes
with `(emv/verify-event-model!)` reporting `:valid? true`.
