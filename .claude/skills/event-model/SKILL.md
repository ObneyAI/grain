---
name: event-model
description: >-
  Entry point and router for grain's service-area-first event model — the
  `defeventmodel` EDN spec that maps 1:1 onto defcommand/defquery/defreadmodel/
  defprocessor/defperiodic and is validated against the LIVE runtime over the
  running app's nREPL (:7888). Use when working on a defeventmodel or an event
  model; on a service area's commands / events / read-models / queries /
  todo-processors / periodic-tasks / screens / flows; when `validate-event-model`,
  `event-model-coverage`, `verify-event-model!` or `verify-or-throw!` reports
  findings; when a grain app refuses to boot past its event-model boot-guard; or
  when you need the model grammar, the REPL oracle, or to pick a verb. Routes to
  em-elicit (intent→model), em-distill (code/catalog→model), em-propagate
  (model→tests), em-tend (edit model), em-weed (model↔runtime reconcile).
---

# Event Model

The grain **event model** is a service-area-first specification of a grain
application, written as plain EDN and registered with `(defeventmodel :area
{...})`. It maps **1:1** onto grain's building blocks
(`defcommand`/`defquery`/`defreadmodel`/`defprocessor`/`defperiodic` + screens)
and onto Event Modeling's CQRS data flow, organized around **service areas**
rather than entities.

The defining difference from a static spec language (and the reason this suite
exists): **there is an oracle.** A grain model is not checked against a grammar —
it is checked against the **live runtime** of the running app over its nREPL
(`:7888`). Requiring the app's base loads the handlers and the registered model
(registries populate at namespace load), and the validators reconcile the EDN
model against that live `catalog`, schema registry, and wiring. Authoring an
event model is a **REPL loop**, not a text edit.

This skill is the **router**. It carries the format, the oracle, and the loop,
and points you at the verb that owns your task.

## Purpose

- Be the entry point: examine the project/app and route to the right verb.
- Hold the shared vocabulary — the model format, the REPL tools, the finding
  taxonomy, the boot-guard — so the verb skills can stay focused.
- Drive the **gather → act → verify → repeat** loop that pushes three artefacts
  to agreement: the **model** (intent), the **handlers** (implementation), and
  the **def-site declarations + GWT** (the contract the boot-guard enforces).

## When to use this skill vs a sibling (Boundaries)

Use **this** skill when you need the format, the oracle, the loop, or to decide
where to go. For the actual work, hand off:

| Task | Route to | When |
|---|---|---|
| Understand the model grammar, the REPL oracle, the loop, or which verb to use | **this skill** (`event-model`) | you need vocabulary or a routing decision |
| Build a new area/model from intent or conversation | `em-elicit` | a feature is described and **no** model exists yet; surface ambiguity before code |
| Reverse-engineer a model from existing code | `em-distill` | a system already exists — **grain or not**. Reads source → EDN model (structural validation); on a grain app, additionally scaffolds from `(catalog)` and validates against the live oracle. Also the migration-blueprint path |
| Make targeted edits to a `defeventmodel` | `em-tend` | add/rename/restructure blocks, intent edges, flows, or GWT; resolve a finding by editing the model |
| Turn the model into tests | `em-propagate` | drive Given/When/Then into executable checks via `invoke-command!` / `events` |
| Reconcile model ↔ live runtime | `em-weed` | `validate-event-model` / `event-model-coverage` shows drift and you must decide model-vs-code |

Do **not** do a sibling's job here. If the user wants edits, that is `em-tend`;
if they want drift resolved, that is `em-weed`; if they want a model from code,
that is `em-distill`. This skill orients and routes.

## The model format (compact)

A model is a map of **areas**, keyed by a simple keyword. Each area owns its
blocks and flows. Blocks are keyed `:<area>/<name>` — exactly the key each
`def*` macro registers. **The keyword namespace is the area; a block's KIND
comes from its structural position** (which map it sits in), not from the
keyword — so `:<area>/<name>` is *not* unique across kinds (in `:example`,
`:example/counters` is both a read-model and a query). Identity is the pair
**(kind, name)**, and flow endpoints are kind-qualified.

```clojure
(defeventmodel :example
  {:description "Counter service area."
   :commands {:example/create-counter
              {:description "Creates a counter; name must be unique."
               :schema [:map [:name :string]]
               :reads #{:example/counters}            ; read-models composed
               :produces #{:example/counter-created}  ; events emitted
               :given-when-thens [{:given "no counter named \"A\""
                                   :when  "create-counter name \"A\""
                                   :then  "a counter-created event is recorded"}]}}
   :events {:example/counter-created
            {:description "A counter was created."
             :schema [:map [:counter-id :uuid] [:name :string]]}}
   :read-models {:example/counters
                 {:description "All counters." :consumes #{:example/counter-created} :version 1}}
   :queries {:example/counters
             {:description "Returns all counters." :schema [:map] :reads #{:example/counters}}}
   :todo-processors {:example/avg
                     {:description "..." :subscribes #{:example/counter-created}
                      :produces #{:example/calculate-average-counter-value}}}
   :periodic-tasks {:example/tick
                    {:description "..." :schedule {:every 30 :duration :seconds}}}
   :screens {:example/dashboard
             {:description "..." :queries #{:example/counters}
              :commands #{:example/create-counter}}}
   :flows {:example/lifecycle
           {:description "..."
            :steps [{:from [:screen :example/dashboard]   :to [:command :example/create-counter]}
                    {:from [:command :example/create-counter] :to [:event :example/counter-created]}
                    {:from [:event :example/counter-created]  :to [:read-model :example/counters]}]}}})
```

Kinds and their fields: **command** `:schema :reads :produces :given-when-thens`;
**event** `:schema`; **read-model** `:consumes` (+ optional `:schema`/`:version`);
**query** `:schema :reads`; **todo-processor** `:subscribes :produces`;
**periodic-task** `:schedule :produces` (`:schedule` is a **map**: `{:every 30
:duration :seconds}` or `{:cron "..."}`); **screen** (design-only) `:queries
:commands`. Given/When/Then are **data**, never executed by the validator.

**Intent edges** (`:reads`/`:produces`/`:consumes`/`:subscribes`/`:queries`/
`:commands`) declare the dependency graph; the validator type-checks each (target
exists *and* is the expected kind). **Flow** adjacency grammar:

```
command -> event       event -> read-model | todo-processor
read-model -> query | command | screen      query -> screen
screen -> command      todo-processor -> command      periodic-task -> command | event
```

Only `event -> read-model` and `event -> todo-processor` are confirmable against
live wiring (read-model `:consumes` / processor `:topics`); the rest live inside
handler bodies and are checked for endpoint existence + grammar only.

**Def-site declarations** mirror the production/read edges as handler opts —
required by the strict boot-guard:

```clojure
(defcommand :example create-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-created}
   :grain.event-model/reads    #{:example/counters}}
  ...)
```

(Also valid on `defprocessor`/`defperiodic` (`:produces`) and `defquery`
(`:reads`). Fully non-breaking — the macros merge the full opts map.)

## The oracle (REPL tools)

Connect to the **running app's nREPL on `:7888`**. Requiring the base loads the
handlers + the registered model. Two interfaces:

```clojure
;; Dev/agent loop — re-exports the validators + adds execution:
(require '[ai.obney.grain.code-agent-tools.interface :as tools])
;; Shippable validator + boot-guard (same validate-* fns, plus verify-*):
(require '[ai.obney.grain.event-model-validator.interface :as emv])
;; The model registry:
(require '[ai.obney.grain.event-model.interface :refer [defeventmodel registered-model]])
```

Introspect & validate (no `install!` needed — these read the catalog):

```clojure
(tools/catalog)                                  ; live blocks (commands/queries/read-models/
                                                 ;   processors/periodic + schemas + diagnostics)
(def model (registered-model))                   ; the merged registered defeventmodel(s)

(tools/validate-event-model model)               ; LENIENT verdict {:valid? :summary :findings}
(tools/validate-event-model model {:strict true}); the STRICT gate (== the boot-guard)
(tools/event-model-coverage model)               ; bidirectional spec<->live drift, per kind
(emv/verify-event-model!)                         ; strict-validate the REGISTERED model (no throw)
(emv/verify-or-throw!)                             ; the boot-guard: throws on any fatal finding

(tools/validate-event-model-file "components/.../example.event-model.edn")
(tools/validate-event-model-var 'my-app.model/event-model)

(tools/guides)                                    ; index of REPL-served guides
(tools/guide :event-model)                        ; this format
(tools/guide :findings)                           ; the finding taxonomy + how to resolve each
(tools/guide :example/create-counter {:spec model}) ; per-block usage card (docstring+schema+GWT)
```

Execute (only for running GWT — needed by `em-propagate`; requires `install!`):

```clojure
(tools/install! {:system <ig-system> :context <ctx> :mode :dev})
(tools/invoke-command! {:command/name :example/create-counter :name "A"})
(tools/invoke-query   {:query/name :example/counters})
(tools/events {:types #{:example/counter-created} :limit 50})
(tools/projection :example/counters)
```

`:valid?` is true when no `:error`-severity finding is present. Opts:
`{:schema-match :lenient}` downgrades schema mismatches to warnings;
`{:error-severities #{...}}` / `{:fatal-types #{...}}` tune what fails.

### The strict gate (what the boot-guard enforces)

`verify-or-throw!` / `validate-event-model … {:strict true}` requires a
**complete** description and fails on these (beyond all structural errors):

| Finding | Means |
|---|---|
| `:block/uncovered` | a live block in a spec'd area is missing from the model (strict: fatal) |
| `:block/undeclared` | a model block has no live counterpart |
| `:produces/undeclared` / `:reads/undeclared` | a live command/processor/periodic lacks `:grain.event-model/produces`; a command/query lacks `:reads` |
| `:gwt/missing` | a command in the model carries no Given/When/Then |
| `:schema/malformed` `:mismatch` `:unregistered` `:unresolved-ref` | a `:schema` doesn't parse / differs from live / names an unregistered schema |
| `:flow/illegal-connection` `:dangling-reference` `:discontinuous` | a flow step breaks the grammar / endpoint / continuity |
| `:ref/dangling` `:ref/wrong-kind` | an intent edge names a missing / wrong-kind block |
| `:wiring/mismatch` `:produces/mismatch` `:reads/mismatch` | spec `:consumes`/`:subscribes`/`:schedule`/`:produces`/`:reads` diverge from live wiring or def-site |
| `:auth/missing` | a live command/query has no `:authorized?` — **warning, not fatal by default** |

`(tools/guide :findings)` is authoritative — read it when a finding is unclear.

## The loop (gather → act → verify → repeat)

Two entry points, one convergence loop:

- **Forward (from intent):** `/em-elicit` → author handlers → annotate def sites
  (`:grain.event-model/produces`/`:reads`) → `/em-propagate` → run GWT → `/em-weed`;
  use `/em-tend` then re-`/em-propagate` when requirements change.
- **Backward (from code):** `/em-distill` (`(catalog)` → skeleton model) → review
  intended vs accidental → fill GWT/edges with `/em-tend` → `/em-propagate` →
  `/em-weed` to reconcile → repeat per area.

Each pass:

1. **gather** — `(tools/catalog)` for what's live, `(registered-model)` for the
   spec, or `/em-distill` to seed a model from code.
2. **act** — author/edit the `defeventmodel`, the `def*` handlers, and the
   def-site `:grain.event-model/produces`/`:reads` so all three agree.
3. **verify** — `(tools/validate-event-model model)` **lenient** to triage drift,
   fix, then `(tools/validate-event-model model {:strict true})` /
   `(emv/verify-event-model!)` — *exactly* what the boot-guard enforces.
4. **repeat** until strict passes. The app boots (its `start` calls
   `verify-or-throw!`) only when the model and the running system fully agree.

**Done** when: strict validation passes (so `verify-or-throw!` boots), `em-weed`
reports no drift, no open questions remain, and a fresh `em-distill` finds nothing
new. After invoking one verb, proactively suggest the next step.

## Guardrails

- **The live runtime is the oracle.** Never reason about validity from the EDN
  alone — run `validate-event-model` against the running app. If `:summary`
  shows `:runtime/registries-present? false`, the base isn't loaded; require it
  (or reconnect to `:7888`) before trusting any verdict.
- **Lenient before strict.** Triage with the default verdict, then gate with
  `{:strict true}`. `{:strict true}` is the boot-guard — passing it is the bar.
- **Never weaken the model (or a GWT) just to go green.** A `:schema/mismatch`,
  `:produces/mismatch`, or `:block/uncovered` is a real disagreement: fix the
  handler, fix the def-site declaration, or change the model *deliberately* — do
  not delete the block or loosen the schema to silence it. That decision is
  `em-weed`'s job.
- **Validation is structural only.** It confirms spec↔code-declaration agreement
  (schemas, wiring, declared edges, GWT presence). It does **not** prove a handler
  emits its declared events — that's the declaration↔behaviour gap closed by
  running GWT (`em-propagate`).
- **`:auth/missing` is not a model defect** — it's deny-by-default; don't treat
  it as a strict failure unless the app opts in via `:fatal-types`.
- **Keep keys at `:<area>/<name>`** and flow endpoints kind-qualified
  `[kind :<area>/<name>]`. A key whose namespace ≠ its area is
  `:block/misnamespaced` (error).

## Worked snippet — orient, then route

```clojure
;; On the running app's nREPL (:7888) — registries populate when the base loads.
(require '[ai.obney.grain.code-agent-tools.interface :as tools]
         '[ai.obney.grain.event-model.interface :refer [registered-model]])

(def model (registered-model))
(tools/validate-event-model model)
;; => {:valid? false
;;     :summary {:findings 2 :errors 1 :warnings 1
;;               :areas [:example] :runtime/registries-present? true}
;;     :findings [{:type :reads/mismatch :severity :warning :area :example
;;                 :kind :command :block :example/create-counter ...}
;;                {:type :block/uncovered :severity :warning :kind :query
;;                 :block :example/counter ...}]}
```

Read the findings, then route:

- `:block/uncovered` / `:block/undeclared` / `:*/mismatch` → live and model
  disagree → **`em-weed`** (reconcile), then **`em-tend`** to apply the chosen edit.
- a whole area has no model yet → **`em-distill`** (from `(catalog)`) or
  **`em-elicit`** (from intent).
- model is right but `:gwt/missing` / no tests → **`em-tend`** to add GWT, then
  **`em-propagate`** to drive them through `invoke-command!` / `events`.

Re-run `(tools/validate-event-model model {:strict true})` after each change;
stop when it's `:valid? true` — that's the same gate `verify-or-throw!` applies
at boot.

## References

- `docs/event-model.md` — the format, validation, the boot-guard mandate, the
  full finding table, and the def-site annotation contract.
- `(tools/guide :event-model)` / `(tools/guide :findings)` /
  `(tools/guide :getting-started)` — authoritative, runtime-served.
- `components/event-model/src/.../interface.clj` — the `:event-model` malli
  schema + `defeventmodel` / `registered-model`.
- `components/event-model-validator/src/.../interface.clj` — `validate-event-model`,
  `event-model-coverage`, `verify-event-model!` / `verify-or-throw!`.
- `components/example-service/...interface/event_model.clj` — the worked,
  fully-mandated `:example` model (and its annotated handlers in `core/`).
