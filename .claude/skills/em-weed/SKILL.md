---
name: em-weed
description: "Reconcile a grain event model against the LIVE runtime — find and fix where the defeventmodel spec and the running app's registries have diverged. Use when the user wants to check event-model/code alignment, audit for spec drift, sync the model with the code or the code with the model, resolve validate-event-model / verify-event-model! / verify-or-throw! findings, make the strict boot-guard pass so the app boots, or verify the registered model matches the live catalog. The grain-native /weed: run (validate-event-model model {:strict true}) + (event-model-coverage model) over the running app's nREPL, classify each finding (model bug / code bug / aspirational / intentional gap), and fix the defeventmodel EDN or the def* handlers until the strict gate is green."
---

# em-weed

You weed the grain event-model garden. You reconcile a `defeventmodel` spec against the **live grain runtime** over the app's nREPL (`:7888`), find where they have diverged, and resolve the divergences — by fixing the model or the code — until the **strict gate** the boot-guard enforces is green.

The leverage here is that the validator already *computes* the divergences for you. You do not eyeball spec-vs-code: you run the oracle, then classify and fix what it reports.

## Purpose

The model (`defeventmodel :area {...}`) is plain EDN that maps 1:1 onto grain's building blocks. The live registries (`catalog`) are populated when handler namespaces load. `weed` joins the two, surfaces every drift as a typed **finding**, and drives each finding to resolution. "Done" is a strict pass — exactly what `verify-or-throw!` requires for the app to boot.

## When to use

- "Does the event model still match the code?" / spec-drift audit / alignment check.
- A `validate-event-model` / `verify-event-model!` finding needs triaging and fixing.
- The boot-guard (`verify-or-throw!`) is throwing and the app won't start.
- After a code change (new command, changed schema, retuned `:topics`/`:schedule`) you need the model brought back into agreement — or vice-versa.

## When NOT to use (boundaries — name the sibling that owns it)

- **Building a model from intent/conversation** → `em-elicit`.
- **Reverse-engineering a fresh model from `catalog`** → `em-distill` (use it here to *seed* a model when none exists, then weed it).
- **General structural authoring/edits to a model** (adding areas, restructuring flows, writing Given/When/Then prose) → `em-tend`. `em-weed` edits the EDN only as far as needed to land a reconciliation; hand larger authoring back to `em-tend`.
- **Generating tests from the model** (turning `:gwt/missing` into executable acceptance tests) → `em-propagate`.
- The entry skill **`event-model`** routes you here.

## The oracle (the key difference from Allium's static `allium check`)

grain validates against the **running app**, not a text file. Connect to the nREPL, require the app's base so handlers + the registered model load, then:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools]          ; dev/REPL loop
         '[ai.obney.grain.event-model-validator.interface :as emv])      ; shippable + boot-guard
```

The validator is **structural only** (no command execution, no Given/When/Then run), builds on `catalog`, needs **no `install!`**, degrades to spec-internal checks when no registries are loaded, and never throws (except `verify-or-throw!`).

## Modes

Determined by the caller's request; default to **Check**.

- **Check.** Run the oracle, report every finding with its classification and reasoning. Modify nothing.
- **Reconcile to runtime** (update the model). Edit the `defeventmodel` EDN so the spec faithfully describes what the running system does.
- **Reconcile to model** (update the code). Edit the `def*` handlers / def-site annotations so the running system matches the specified model.

Most weeding mixes both directions, finding by finding.

## Process (gather → classify → reconcile → re-verify → repeat)

1. **Get the model.** One of: `(emv/validate-event-model-var 'my-app.model/event-model)` (a var holding the EDN); `(tools/validate-event-model-file "components/.../area.event-model.edn")`; or, for an app that registers via `defeventmodel`, reload the model ns and read the registry:
   ```clojure
   (require 'ai.obney.grain.example-service.interface.event-model :reload)
   (def model (ai.obney.grain.event-model.interface/registered-model))
   ```
   If no model exists yet, stop and seed one with **`em-distill`** first.

2. **Gather divergences (the oracle).** Run all three — they answer different questions:
   ```clojure
   (tools/validate-event-model model)                 ; lenient verdict — triage only
   (tools/validate-event-model model {:strict true})  ; THE GATE the boot-guard enforces
   (tools/event-model-coverage model)                 ; bidirectional spec<->live drift, per kind
   ```
   For an app that mandates the model, `(emv/verify-event-model!)` returns the same strict verdict without throwing. Each returns `{:valid? :summary :findings}`; `:valid?` is true only when no fatal finding remains. **Lenient is not the bar** — it omits the completeness checks (`:block/uncovered`, `:produces/undeclared`, `:reads/undeclared`, `:gwt/missing`). Always finish on strict.

3. **Classify each finding.** For every finding, propose **model bug / code bug / aspirational / intentional gap** with reasoning; the caller confirms or overrides. Ground the call with:
   ```clojure
   (tools/guide :findings)                              ; the taxonomy + how to resolve each
   (tools/guide :example/create-counter {:spec model})  ; per-block card: live docstring + live schema + spec edges/GWT
   (tools/explain-schema :example/create-counter)       ; the live registered schema, to compare a :schema/mismatch
   (tools/catalog)                                       ; what the runtime actually has
   ```
   Use the **finding-type → reconciliation-action** table below as your default classification.

4. **Reconcile** per the chosen action:
   - **Fix the model** → edit the `defeventmodel` EDN (mechanics belong to `em-tend`): correct a `:schema`, add a missing block, fix a `:consumes`/`:subscribes`/`:produces`/`:reads` edge, re-key a misnamespaced block, repair a flow step.
   - **Fix the code** → edit the `def*` handler / def site: add the `:grain.event-model/produces` / `:grain.event-model/reads` annotation, correct the handler's `:topics`/`:schedule`, register a missing schema (`defschemas`), add the `:authorized?` predicate, implement or `require` a missing handler.

5. **Reload, then re-verify.** The oracle reads the **live registry** — a stale namespace lies. After editing a handler ns *or* the `defeventmodel` ns, reload it (`(require '...ns :reload)`) so the registry repopulates, re-`def model` if it came from the registry, then re-run step 2's strict call. Repeat 3–5 until `{:strict true}` reports `:valid? true`.

6. **Confirm boot.** `(emv/verify-or-throw!)` — returns the verdict on success, throws `ex-info` on any fatal finding. When it returns, the running system and the model agree and the app will boot through the guard.

## Finding-type → reconciliation-action table

`(guide :findings)` is authoritative; this is the weeding playbook.

| `:type` | sev | usual classification | reconciliation action |
|---|---|---|---|
| `:model/malformed` | error | model bug | The EDN doesn't conform to `:event-model`; short-circuits everything else. Fix the shape first (`em-tend`), then re-run. |
| `:block/misnamespaced` | error | model bug | A block key's namespace ≠ its area. Re-key it as `:<area>/<name>`. |
| `:block/undeclared` | error | code bug \| aspirational \| model bug | Spec block has no live counterpart. Code bug: implement the `def*` / `require` the missing ns. Aspirational: the block is planned — move it out of the mandated model (or implement it). Model bug: it's a stale/renamed block — remove or rename in the EDN. |
| `:block/uncovered` | warn (fatal strict) | model bug \| intentional | Live block missing from the spec. Model bug (common): add the block (`em-tend`). Intentional: the handler shouldn't exist — delete the `def*`. |
| `:schema/malformed` | error | model bug | A block `:schema` doesn't parse. Fix the malli schema in the EDN. |
| `:schema/unresolved-ref` | warn | model bug \| code bug | `:schema` references an unregistered name. Fix the ref, or register the referenced schema via `defschemas`. |
| `:schema/unregistered` | warn | code bug | A live command/query/consumed-event has no registered schema. Register one (`defschemas`). |
| `:schema/mismatch` | error | model bug \| code bug | Spec `:schema` ≠ live registered schema. Compare with `(explain-schema block)`. If the spec is wrong → update the EDN to the live shape. If the handler/schema is wrong → fix the live schema. |
| `:flow/illegal-connection` | error | model bug | Flow step violates the CQRS grammar. Rewrite the step to legal adjacency (command→event, event→read-model, read-model→command/query, query→screen/todo-processor, screen→command, todo-processor→command, periodic-task→command/event). Read-models feed only commands and queries; a todo-processor's input is a query (never a read-model or a raw event). |
| `:flow/dangling-reference` | error | model bug \| code bug | Flow endpoint names a non-existent block. Fix the endpoint, or build the block. |
| `:flow/discontinuous` | warn | model bug | A step's `:from` has no producing prior step. Reorder/insert steps so the chain is continuous. |
| `:ref/dangling` | error | model bug \| code bug | An intent edge (`:reads`/`:produces`/`:consumes`/`:subscribes`/`:queries`/`:commands`) names a non-existent block. Fix the edge, or build the target. |
| `:ref/wrong-kind` | error | model bug | An intent edge names a real block of the wrong kind (e.g. `:reads` pointing at a command). Point it at the right kind. |
| `:wiring/mismatch` | warn | model bug \| code bug | Spec `:consumes`/`:subscribes`/`:schedule` diverges from live wiring (read-model `:events` / processor `:topics` / periodic `:schedule`). One of the two real edges confirmable against the runtime — take it seriously. Update the EDN to live, or retune the handler's `:topics`/`:schedule`/consumption. |
| `:produces/mismatch` | warn | model bug \| code bug | Spec `:produces` ≠ def-site `:grain.event-model/produces`. Update the EDN, or correct the annotation (and verify the handler body actually emits those events). |
| `:reads/mismatch` | warn | model bug \| code bug | Spec `:reads` ≠ def-site `:grain.event-model/reads`. Update the EDN, or correct the annotation. |
| `:produces/undeclared` | error (strict) | code bug | A live command/processor/periodic doesn't declare `:grain.event-model/produces`. Add the annotation to the def site (`#{}` is valid for a no-op like the periodic heartbeat). |
| `:reads/undeclared` | error (strict) | code bug | A live command/query doesn't declare `:grain.event-model/reads`. Add the annotation to the def site. |
| `:gwt/missing` | error (strict) | model bug | A command in the model carries no Given/When/Then. Author the `:given-when-thens` data (`em-tend`; turning it into tests is `em-propagate`). |
| `:auth/missing` | warn (NOT fatal) | code bug | A live command/query has no `:authorized?` predicate. Not a model defect — but it blocks HTTP reachability via command/query-request-handler. Fix it as code; never mute it. |
| `:runtime/design-only`, `:runtime/absent` | info | — | Notices (e.g. screens have no runtime counterpart; no registries loaded). Not failures. |

## Guardrails

- **Reload before you re-verify.** The oracle reads the live registry; `def*`/`defschemas`/`defeventmodel` register at namespace load. After any edit, `(require '...ns :reload)` (and re-read `registered-model`) or you are validating stale code. Require the app's base so *all* handlers and the registered model are present.
- **Strict is the bar.** `(validate-event-model model)` is for triage. The boot-guard runs `{:strict true}` / `verify-event-model!`; only that pass means the app boots. Don't declare victory on lenient.
- **Don't mute findings to go green.** `:schema-match :lenient`, `:error-severities`, and `:fatal-types` are for *triage and classification*, not for shipping a model that disagrees with the runtime. Loosening opts to force `:valid? true` defeats the gate.
- **A green `:produces`/`:reads` is agreement, not proof.** Those edges are confirmed only against the def-site *annotation*, never the handler body. The declaration↔behaviour gap is closed by executable Given/When/Then (`em-propagate`), not by `weed`. Only `event→read-model` (read-model `:consumes`) and a processor's event `:subscribes` (vs live `:topics`) are confirmable against actual live wiring; every other flow edge is checked for endpoint existence + grammar only.
- **`:auth/missing` is real but separate.** It's not fatal to the model and not a model bug — resolve it in code, don't suppress it.
- **Stay in scope.** Coverage, missing-schema, and auth findings are scoped to the areas the spec declares. Weed one area without dragging in another's blocks.
- **Hand off heavy authoring.** Large EDN restructuring → `em-tend`; a model that doesn't exist yet → `em-distill`/`em-elicit`. Keep `weed` edits minimal and targeted at the divergence.
- **Long sessions.** Reconciliation can be many reload-validate cycles. If context grows large, suggest a fresh session: "Use `em-weed` to keep reconciling the `:example` model against the running app — strict gate still red on `:schema/mismatch`."

## Worked snippet

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools]
         '[ai.obney.grain.event-model-validator.interface :as emv])

;; 1+2. load model + handlers, run the strict gate
(require 'ai.obney.grain.example-service.interface.event-model :reload)
(def model (ai.obney.grain.event-model.interface/registered-model))

(tools/validate-event-model model {:strict true})
;; => {:valid? false
;;     :summary {:errors 1 :by-type {:reads/undeclared 1} :areas [:example]
;;               :runtime/registries-present? true}
;;     :findings [{:type :reads/undeclared :severity :error :area :example
;;                 :kind :query :block :example/counter
;;                 :message "live query declares no :grain.event-model/reads"}]}

;; 3. classify — the live query has no def-site :reads annotation → CODE BUG.
;;    (the model already says :reads #{:example/counters}; the handler must declare it)

;; 4. fix the code (queries.clj), adding the annotation to the def site:
;;    (defquery :example counter
;;      {:authorized? (constantly true)
;;       :grain.event-model/reads #{:example/counters}}  ; <- add
;;      ...)

;; 5. reload the handler ns so the registry repopulates, then re-verify
(require 'ai.obney.grain.example-service.core.queries :reload)
(tools/validate-event-model model {:strict true})
;; => {:valid? true :summary {:errors 0 ...} :findings [...warnings/info only...]}

;; 6. prove the boot-guard would pass
(emv/verify-or-throw!)   ; returns the verdict; would have thrown if still divergent
```

Output (Check mode), grouped by area/block, most consequential first:

```
### :example/counter  (query)
Finding: :reads/undeclared (error, strict)
Model:   :reads #{:example/counters}  (event_model.clj)
Code:    no :grain.event-model/reads on the def site  (core/queries.clj)
Classification: code bug — the model is correct; the handler must declare the edge.
Action: add :grain.event-model/reads #{:example/counters} to (defquery :example counter ...).
```
