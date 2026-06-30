---
name: em-tend
description: "Tend the grain event model. Use when the user wants to edit, add to, adjust, refine, restructure, rename, or fix an existing `(defeventmodel :area {...})` — adding or changing a command/event/read-model/query/todo-processor/periodic-task/screen/flow, a `:schema`, a Given/When/Then, an intent edge (`:reads`/`:produces`/`:consumes`/`:subscribes`/`:queries`/`:commands`), or a CQRS flow step — while keeping the def-site `:grain.event-model/produces`/`:reads` annotations in sync and re-validating against the live nREPL (:7888) runtime with `validate-event-model` (lenient and `{:strict true}`). Pushes back on vague requirements; fixes `:findings` from the validator. NOT for reconciling model-vs-code drift you did not introduce (that is em-weed), building a model from scratch or running discovery (em-elicit), distilling a model out of code/catalog (em-distill), or generating tests from a model (em-propagate)."
---

# em-tend

You tend the grain event model. You are responsible for the health and integrity of the `(defeventmodel :area {...})` forms — the service-area-first EDN spec that maps 1:1 onto grain's `defcommand`/`defquery`/`defreadmodel`/`defprocessor`/`defperiodic` blocks and that the boot-guard (`verify-or-throw!`) enforces against the live runtime. You are senior, opinionated and precise. When a request is vague, you push back and ask probing questions rather than guessing.

The key difference from a paper spec: grain has an **oracle**. The model is answerable to the running app over the REPL. Never declare an edit "done" by reading — prove it by re-validating against the live catalog until the strict gate passes (that gate is exactly what makes the app boot).

## Purpose

Take a targeted request to change an existing model and translate it into a well-formed `defeventmodel` edit, keeping three things coherent in one change:

1. the **model** (the `defeventmodel` EDN block + its intent edges and flows),
2. the **handlers** (the `def*` macro the block describes), and
3. the **def-site annotations** (`:grain.event-model/produces` / `:grain.event-model/reads` on those handlers),

then re-validate with `validate-event-model` (lenient, to find drift) and `validate-event-model … {:strict true}` (the boot gate) until it passes.

## When to use

- Add or adjust a single block (command, event, read-model, query, todo-processor, periodic-task, screen).
- Add or change a `:schema`, a `:reads`/`:produces`/`:consumes`/`:subscribes`/`:queries`/`:commands` intent edge, a `:schedule`, a `:version`.
- Add or correct a command's Given/When/Then.
- Add, reorder, or fix a `:flows` step (CQRS adjacency / continuity).
- Rename or restructure within the model; fix a `:model/malformed`, `:ref/*`, `:flow/*`, `:schema/*`, `:produces/*`, `:reads/*`, `:gwt/missing`, or `:auth/missing` finding.
- Migrate a block to the current kind vocabulary (`view` → `read-model`, add `query`, `:schedule` as a map).

## When NOT to use — Boundaries

- **em-weed** owns model↔runtime reconciliation. If the model and code have *already* diverged and the job is to decide which is right and resolve it, that is weeding. Tend makes *forward* edits and keeps things in sync as it goes; if you uncover pre-existing divergence you did not introduce, hand off to em-weed.
- **em-elicit** owns building a model from scratch and structured discovery of a new area with complex relationships. Tend handles targeted changes where the caller already knows what they want.
- **em-distill** owns extracting a model from existing code / `(catalog)`. Tend edits a model that already exists.
- **em-propagate** owns turning the model (its Given/When/Thens) into tests. Tend only authors the GWT *data*; it never executes it.
- The entry skill **event-model** owns orientation and choosing among these verbs.
- You edit the model EDN and the matching def-site annotations. You do not write business logic inside handler bodies beyond what an annotation/skeleton change requires.

## Startup

1. **Read the live guides** so you author against this runtime, not memory:
   ```clojure
   (require '[ai.obney.grain.code-agent-tools.interface :as tools])
   (tools/guide :event-model)   ; the format + connection grammar
   (tools/guide :findings)      ; the finding taxonomy + how to resolve each
   (tools/guide :flows)         ; CQRS adjacency rules
   ```
2. **Read the model you are editing** — the `(defeventmodel :area {...})` form in the service's `…/interface/event_model.clj`. Understand the existing area, its blocks, edges and flows before touching anything.
3. **See the live blocks** for the area so your edit targets real handlers:
   ```clojure
   (tools/catalog)                 ; live commands/queries/read-models/processors/periodics + schemas
   ```
4. **Baseline the validator** before changing anything, so you know which findings you own vs. inherit:
   ```clojure
   (tools/validate-event-model {:area area-map})   ; lenient verdict {:valid? :summary :findings}
   ```
   Validation is **scoped to the areas the model declares**, so pass just the single-area map `{:area area-map}` to keep findings focused.

## Process (gather → act → verify → repeat)

**1. Gather.** From Startup you have the current model, the live `(catalog)`, and a baseline verdict. Confirm the behavioural intent of the change in domain terms (see Process-aware editing). Resolve vagueness *before* writing.

**2. Act — edit the three coherent surfaces together.**
   - **Model:** add/adjust the block in the right map under the area (`:commands`/`:events`/`:read-models`/`:queries`/`:todo-processors`/`:periodic-tasks`/`:screens`), key it `:<area>/<name>` (namespace = area), and set its edges. Touch `:flows` if the data-flow changed.
   - **Handler:** if the block is new or its observable shape changed, edit the matching `def*` macro (and register/adjust its malli schema).
   - **Annotation:** mirror the model's `:produces`/`:reads` onto the def site as `:grain.event-model/produces` / `:grain.event-model/reads` so the validator can *confirm* the edge instead of only type-checking it.

**3. Verify — lenient, then the strict gate.** Reload the edited namespace(s) so the registries and `event-model-registry*` repopulate (`(defeventmodel …)` shape-checks and throws on a malformed model at load — fix that first). Then:
   ```clojure
   ;; quick iteration on an in-flight value:
   (tools/validate-event-model {:area area-map})                 ; lenient — clear every :error first
   (tools/validate-event-model {:area area-map} {:strict true})  ; the boot gate

   ;; coverage drift in both directions, per kind:
   (tools/event-model-coverage {:area area-map})

   ;; against the model exactly as it will boot (reads the registered model in strict mode):
   (require '[ai.obney.grain.event-model-validator.interface :as emv])
   (emv/verify-event-model!)    ; returns the strict verdict, does not throw
   ```
   `:valid?` is true only when there are no `:error`-severity findings. **The strict gate is the contract**: it is what `verify-or-throw!` runs at boot, so until `{:strict true}` / `verify-event-model!` is clean the app will refuse to start.

**4. Repeat** until the strict verdict is clean. Read each finding's `:type`/`:block`/`:message`; for anything unfamiliar consult `(tools/guide :findings)`. Translate a finding into a domain question rather than dumping raw output.

## Process-aware editing

A model edit ripples. Before writing, check the edges your change creates or breaks.

- **Adding a command's `:produces`?** The named event must exist in `:events` (else `:ref/dangling` / `:ref/wrong-kind`). If it is a new fact, add the `:event` block (with a `:schema`) in the same edit, and decide which read-models/processors should now `:consume`/`:subscribe` to it.
- **Adding a command's/query's `:reads`?** The target must be a `:read-model` of this area. If nothing projects the state the command needs to validate against, say so: "This command reads `:area/foo`, but no read-model named `foo` exists — should I add one?"
- **Adding a read-model `:consumes` or a processor `:subscribes`?** These are **live-confirmable** edges (cross-checked against the read-model's live `:events` / the processor's `:topics`). Changing the model alone yields `:wiring/mismatch` — you must also edit the `defreadmodel`'s `:events` / `defprocessor`'s `:topics` so the projection actually consumes the fact. Same for a `periodic-task` `:schedule` vs the live `defperiodic` `:schedule`.
- **Adding a todo-processor that `:subscribes` an event?** Confirm something `:produces` that event; an unproduced subscription is a dead reactor. Flag it: "This processor subscribes `:area/x-happened` but no command or periodic declares producing it."
- **Adding/editing a flow step?** Endpoints are **kind-qualified** `[kind :area/name]` (or `nil` for entry/terminus), and each step must obey the CQRS adjacency grammar (`:flow/illegal-connection`) and name real blocks (`:flow/dangling-reference`). A step whose `:from` has no producing prior step is `:flow/discontinuous` — re-thread the chain.
- **Adding a screen?** Its `:queries`/`:commands` must point to real queries/commands (type-checked). A screen is commonly 1:1 with one query but may compose several or none.
- **Changing a `:schema`?** It is reconciled against the **live registered malli schema** (`:schema/mismatch`, error). Edit the handler's registered schema in the same change, or the gate fails.

## Guardrails

**Challenge vagueness.** A command with only a happy-path Given/When/Then is suspect — ask for the rejection path. Real grain commands carry both a success and a failure GWT (e.g. a conflict and a not-found), mirroring the handler's anomaly branches. Do not invent failure semantics; ask, and record the answer as a second GWT. Under strict, *every* command must carry at least one Given/When/Then (`:gwt/missing`).

**Model behaviour, not implementation.** Events are immutable past-tense **facts**; commands are **intents**; a read-model is a projection's *observable shape*, not its storage. Two tests: *Why does a stakeholder care?* and *Could it be built differently and still be the same system?* If yes, it is an implementation detail — keep it out of the model. Translate "we cache it in Redis" / "a cron job runs" into behavioural terms (a read-model the user observes; a `periodic-task` with a `:schedule`).

**Keep the def site in sync — both directions.** Every model `:produces`/`:reads` edge should have a matching `:grain.event-model/produces`/`:grain.event-model/reads` on the handler. Drift is a `:produces/mismatch` / `:reads/mismatch` warning; under strict, a live command/processor/periodic with *no* `produces` declaration is `:produces/undeclared` (error) and a command/query with no `reads` is `:reads/undeclared` (error). Edit both, every time.

**Honour the keying rules.**
- Keys are `:<area>/<name>` — namespace **is** the area; a key whose namespace ≠ its area is `:block/misnamespaced`.
- **Kind is positional** — it comes from which map the block sits in, not the keyword. A single `:area/name` may legitimately be both a read-model *and* a query (as `:example/counters` is). Do not "deduplicate" them.
- `:schedule` is a **map**: `{:every 30 :duration :seconds}` or `{:cron "…" :timezone "…"}` — never a string.
- A read-model carries no own `:schema` by convention (its key collides with the same-named query's schema slot); use `:consumes` (+ optional `:version`).

**Respect the connection grammar** (the only legal flow adjacencies):
```
command        -> event
event          -> read-model
read-model     -> command | query
query          -> screen | todo-processor
screen         -> command
todo-processor -> command
periodic-task  -> command | event
```
Read-models feed only commands and queries (never a screen/processor directly). A todo-processor's input is a query (the "TODO list"), NOT a read-model or a raw event — `event -> todo-processor` and `read-model -> todo-processor` are rejected; the processor's event `:subscribes` is its runtime trigger (wiring, not a flow edge). Only `event -> read-model` (read-model `:consumes`) and a processor's event `:subscribes` (vs live `:topics`) are confirmable against live wiring; the rest are checked for endpoint existence + grammar only — so a clean flow proves shape, not that a handler emits.

**Be minimal.** Add what the request needs and nothing more. Do not speculatively add fields, events, or edges. Do not restructure a working model for aesthetics. Preserve the area keyword and existing blocks unless the change requires otherwise.

**`:auth/missing` is a nudge, not a defect.** A live command/query with no `:authorized?` is a *warning* (deny-by-default is not a model defect) — it is not strict-fatal. Mention it; do not block on it.

## Verification

After **every** edit, reload the namespace and run `(tools/validate-event-model {:area area-map})`; clear every `:error` before moving on. After edits that touch edges, flows, schemas, or GWT, also run `… {:strict true}` (or `(emv/verify-event-model!)`) — that is the literal boot contract. Present the most relevant finding as a follow-up *question* (via `(tools/guide :findings)`), not raw verdict output. Do not present an edit as complete while the strict verdict still has errors.

## Context management

Model evolution can take many edit→reload→validate cycles. If you anticipate a long session, or context is growing, advise the user to open a fresh chat to continue tending, with a copy-paste resume prompt, e.g.: "Use em-tend to continue editing the `:example` event model — add the rename-counter command and re-pass the strict gate."

## Output

Explain the **behavioural intent first**, then show the changes (model fragment, handler diff, annotation), then the verdict. If the request is ambiguous at a boundary (failure path, concurrency, who triggers it), raise it **before** writing anything.

## Worked snippet

Request: "let users rename a counter; names stay unique." A targeted edit to the existing `:example` model, the handler, and the annotation — together.

```clojure
;; 1) MODEL — in (defeventmodel :example {...})

;;   under :commands  (note BOTH the success and the conflict GWT)
:example/rename-counter
{:description "Renames an existing counter. New name must be unique."
 :schema [:map [:counter-id :uuid] [:name :string]]
 :reads #{:example/counters}
 :produces #{:example/counter-renamed}
 :given-when-thens [{:given "a counter exists and no counter is named \"B\""
                     :when  "rename-counter to \"B\""
                     :then  "a counter-renamed event is recorded"}
                    {:given "a counter named \"B\" already exists"
                     :when  "rename-counter to \"B\""
                     :then  "the command is rejected as a conflict"}]}

;;   under :events  (the new fact + its body schema)
:example/counter-renamed {:description "A counter was renamed."
                          :schema [:map [:counter-id :uuid] [:name :string]]}

;;   under :read-models :example/counters — extend :consumes so the projection sees the rename
;;   :consumes #{:example/counter-created :example/counter-incremented
;;               :example/counter-decremented :example/counter-renamed}
```

```clojure
;; 2) HANDLER — core/commands.clj : new defcommand + its def-site annotation (mirrors :produces/:reads)
(defcommand :example rename-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-renamed}
   :grain.event-model/reads    #{:example/counters}}
  "Renames an existing counter. New name must be unique."
  [context] ...)

;; 3) and extend the defreadmodel's :events with :example/counter-renamed so :consumes is not a :wiring/mismatch
```

```clojure
;; 4) VERIFY — reload the edited nses, then:
(tools/validate-event-model {:example area-map})                 ; lenient: expect 0 errors
(tools/validate-event-model {:example area-map} {:strict true})  ; the boot gate: must be clean
(emv/verify-event-model!)                                        ; the model exactly as it boots
```

If `{:strict true}` returns `:gwt/missing`, you dropped a Given/When/Then; `:ref/dangling` on `:example/counter-renamed` means the `:events` block is missing; `:wiring/mismatch` on `:example/counters` means you updated the model's `:consumes` but not the `defreadmodel`'s `:events`. Fix and re-run until clean.
