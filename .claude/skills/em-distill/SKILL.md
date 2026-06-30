---
name: em-distill
description: "Extract a grain event model from EXISTING code. Use when the user has running grain handlers (defcommand/defquery/defreadmodel/defprocessor/defperiodic) and wants to reverse-engineer, scaffold, or generate a (defeventmodel :area {...}) from the live catalog; document an undocumented service area; capture what an app already does as an event model; turn implementation into a service-area-first model; or bootstrap a model before mandating it with the boot-guard. The code-from-model counterpart of Allium's /distill, but driven by the live grain REPL oracle instead of static files."
---

# em-distill — distill an event model from live grain code

## Purpose

Turn a *running* grain service area into a `(defeventmodel :area {...})`. Unlike
Allium distillation (which reads source text with "no compiler, no runtime"),
grain hands you a **live oracle**: requiring the app's base loads the handlers and
populates the registries, and `(catalog)` reports them as structured data. So the
*structural* skeleton — kinds, schemas, consumed-event wiring, schedules,
def-site edges — is **read off the runtime mechanically**, not guessed from code.

What remains is the same judgement Allium's `/distill` is about: choosing the
**service area**, writing **domain-level descriptions** (why a stakeholder cares,
not how the handler works), authoring **Given/When/Then** intent, and drawing the
**screens and flows** the runtime cannot see. You drive the result to correctness
with `validate-event-model` / `event-model-coverage` against the live system —
the grain equivalent of `allium check` / `analyse`.

The output is `defeventmodel` EDN (grain's analogue of a `.allium` file),
registered in a namespace like `your-app.interface.event-model`.

## When to use

- There are live grain handlers but **no model yet**, or a partial/stale one, and
  you want the model scaffolded from what is actually running.
- You want to **document a service area** and eventually MANDATE it with
  `verify-or-throw!` (full coverage + declared edges + GWT).
- You inherited code and need to see, as data, *what blocks exist and how they
  wire* before changing anything.

## When NOT to use (boundaries — name the sibling that owns the work)

- **No code yet — designing from intent/conversation** → `em-elicit` (intent →
  model). Distill needs a populated catalog; elicit does not.
- **Editing, extending, renaming, or fixing an existing model** → `em-tend`.
  Distill *creates* a skeleton from runtime; tend *evolves* a model deliberately.
- **Turning a model into tests / executable Given-When-Then** → `em-propagate`.
- **A model that has drifted from the running system; reconciling spec ↔ runtime**
  → `em-weed`. Distill is first-extraction; weed is ongoing reconciliation.
- **You just need the format, the finding taxonomy, or the entry point** →
  `event-model` (the suite entry) or `(tools/guide :event-model)` /
  `(tools/guide :findings)`.

If distillation surfaces genuine gaps (a block whose intent nobody can state),
hand that block to `em-elicit`, then come back.

## The oracle (the key difference from Allium)

All calls go through the dev-only re-export
`ai.obney.grain.code-agent-tools.interface` (aliased `tools`), evaluated in the
**running app's nREPL (default :7888)** — or a fresh REPL that has
`(require 'your-app.base)`, which loads the handlers (registries populate at
namespace load). Validation is **structural only**: no `install!`,
no `invoke-command!`, no state mutation. The same checks run shippably from
`ai.obney.grain.event-model-validator.interface` (`verify-event-model!` /
`verify-or-throw!`).

Calls you will use, exactly as defined (do not invent others):

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(tools/catalog)                       ; live blocks (all kinds) + schemas + diagnostics
(tools/schemas)                       ; the live schema registry
(tools/explain-schema :area/block)    ; one registered schema, sanitized
(tools/event-model-coverage {})       ; everything live is :uncovered → the worklist
(tools/event-model-coverage model)    ; narrows as you fill the area
(tools/validate-event-model model)             ; lenient verdict {:valid? :summary :findings}
(tools/validate-event-model model {:strict true}) ; the boot-guard gate
(tools/validate-event-model-var 'your-app.interface.event-model/...) ; if registered
(tools/guides) (tools/guide :findings) (tools/guide :event-model)
(tools/guide :area/block {:spec model}) ; usage card: docstring + live schema + your spec
```

## Process

### 1. Connect the oracle and confirm it is live

In the app's nREPL (:7888), or a REPL where you have required the base:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])
(tools/catalog)        ; non-empty maps of commands/queries/read-models/… ⇒ good
```

If `catalog` is empty, the handlers are not loaded — `(require 'your-app.base)`
(or the namespaces that hold the `def*` forms) and retry. An empty catalog means
the validator degrades to spec-internal checks only, so distillation is blind.

### 2. Scope the service area (the "Would we rebuild this?" judgement)

`(catalog)` lists live blocks keyed `:<area>/<name>`. The **area is the keyword
namespace**; the **kind is the map it sits in** — so `:foo/counters` can be both a
read-model and a query, and that is correct, not a duplicate.

Pick **one area keyword** to distill. Get the raw worklist of everything not yet
documented:

```clojure
(tools/event-model-coverage {})   ; spec declares nothing ⇒ all live blocks :uncovered
```

For each block, apply Allium's test: *"if we rebuilt this system, would this be in
the description of this area?"* Group blocks by their `:<area>` namespace; exclude
incidental infra you would not re-derive. Distill area-by-area — validating one
area never reports on another's blocks.

### 3. Scaffold the skeleton from the registries (mechanical — read, don't invent)

Build a `(defeventmodel :area {...})` stub by copying structural truth out of the
catalog. Per kind:

| kind | map | pull from catalog / def site |
|---|---|---|
| `:commands` | `{:area/name {…}}` | `:schema` = live registered schema; `:reads`/`:produces` = def-site `:grain.event-model/reads`/`/produces` if present |
| `:events` | `{:area/name {…}}` | `:schema` from the event schema summaries |
| `:read-models` | `{:area/name {…}}` | `:consumes` = the live `:events` (consumed) set; `:version` if recorded |
| `:queries` | `{:area/name {…}}` | `:schema` = live params schema; `:reads` = def-site `:grain.event-model/reads` |
| `:todo-processors` | `{:area/name {…}}` | `:subscribes` = the live `:topics` set; `:produces` = def-site `:grain.event-model/produces` |
| `:periodic-tasks` | `{:area/name {…}}` | `:schedule` = the live schedule map; `:produces` = def-site |
| `:screens` | `{:area/name {…}}` | **not in catalog** — author by hand (step 4) |
| `:flows` | `{:area/keyword {…}}` | **not in catalog** — author by hand (step 4) |

Use `(tools/explain-schema :area/block)` to read a schema exactly as registered —
**copy it verbatim** so it can't drift into a `:schema/mismatch`. `:consumes`,
`:subscribes`, and `:schedule` are the **only edges the runtime can confirm**
(read-model `:events`, processor `:topics`, periodic `:schedule`); copy them as
given or you will get a `:wiring/mismatch` warning.

`command→event` (`:produces`), `command/query→read-model` (`:reads`) and
`processor→command` (`:produces`) live inside handler bodies and are invisible to
introspection. They are confirmable only when the def site declares the matching
`:grain.event-model/produces` / `:reads`. If those annotations are absent, either
read the handler body to fill the edge (asserted-only) **or** add the annotation
at the def site so the validator can confirm it (`:produces/mismatch` /
`:reads/mismatch` flag disagreement). Until annotated, the edge stays asserted.

### 4. Fill the judgement layer (where distillation, not transcription, happens)

This is the Allium "why does the stakeholder care?" pass — the catalog gives
structure, you give meaning:

- **`:description`** — domain-level, one line, *what it does and why it matters*,
  not how. Start from the handler's docstring, but cut implementation
  (`(tools/guide :area/block {:spec model})` shows the docstring + live schema +
  whatever spec you have so far). "Creates a counter; name must be unique" — yes.
  "Queries the read-model atom and conj's an event" — no.
- **`:given-when-thens`** on commands — author the acceptance intent as **data**
  (`{:given … :when … :then …}`); each rejection branch in the handler (a
  conflict, a not-found) is one G/W/T. These are never executed here — they are
  intent, and they are **required** by strict mode (`:gwt/missing`).
- **`:screens`** — design-only surfaces. Author from the UI: which `:queries` it
  reads and which `:commands` it issues. The validator only checks those targets
  exist and are the right kind (`:ref/dangling` / `:ref/wrong-kind`).
- **`:flows`** — name the end-to-end chains. Each step is `{:from <endpoint> :to
  <endpoint>}` with **kind-qualified** endpoints `[:kind :area/name]` (or `nil`
  for entry/terminus). Stay within the CQRS grammar
  (`command→event`, `event→read-model|todo-processor`,
  `read-model→query|command|screen`, `query→screen`, `screen→command`,
  `todo-processor→command`, `periodic-task→command|event`); violations are
  `:flow/illegal-connection`.

### 5. Validate lenient and clear drift

```clojure
(tools/validate-event-model model)   ; {:valid? :summary :findings}
```

`:valid?` is true when there are no `:error`-severity findings. Walk the findings
(`(tools/guide :findings)` explains and gives the fix for each). Common in a fresh
distillation:

- `:block/undeclared` (error) — you wrote a block the runtime doesn't have. You
  invented or mistyped it; delete it or fix the key.
- `:schema/mismatch` (error) — re-copy from `(tools/explain-schema …)`.
- `:wiring/mismatch` (warning) — your `:consumes`/`:subscribes`/`:schedule`
  diverges from the live `:events`/`:topics`/`:schedule`; match the runtime.
- `:produces/mismatch` / `:reads/mismatch` (warning) — your edge disagrees with
  the def-site declaration; reconcile spec and annotation.
- `:ref/dangling` / `:ref/wrong-kind` (error) — an intent edge or flow endpoint
  names a non-existent block or the wrong kind (e.g. pointed `:reads` at an event
  instead of a read-model).
- `:block/misnamespaced` (error) — a block key's namespace ≠ its area.

Use `{:schema-match :lenient}` to triage schema noise while you work; remove it
before you claim done.

### 6. Close coverage

```clojure
(tools/event-model-coverage model)   ; {:undeclared {…} :uncovered {…}}
```

Drive `:uncovered` (live blocks missing from the model) to empty for your area —
that is "documented everything that runs". `:undeclared` (model blocks with no
live counterpart) should already be empty if step 5 is clean.

### 7. Register, and (optionally) pass the strict gate

Drop the validated map into a `defeventmodel` form (the grain analogue of saving
the `.allium` file), conventionally in `your-app/interface/event_model.clj`:

```clojure
(require '[ai.obney.grain.event-model.interface :refer [defeventmodel]])
(defeventmodel :area { … })   ; shape-checked against :event-model at load
```

If the area will be **mandated** (the app refuses to boot unless the model is a
complete description), make strict pass — it is exactly what the boot-guard runs:

```clojure
(tools/validate-event-model model {:strict true})   ; == verify-event-model!
```

Strict additionally requires: **full coverage** (`:block/uncovered` fatal), every
command/processor/periodic declaring `:grain.event-model/produces` and every
command/query `:reads` at the def site (`:produces/undeclared` / `:reads/undeclared`),
and **GWT on every command** (`:gwt/missing`). When it is green, wiring
`(event-model-validator/verify-or-throw!)` before `ig/init` makes the model
load-bearing. (`:auth/missing` is a warning, not fatal — deny-by-default is not a
model defect.)

## Guardrails

- **The catalog is the source of truth, not the code text.** Never add a block,
  schema, or confirmable edge that isn't in `(catalog)` — that's `:block/undeclared`
  or `:schema/mismatch`. Distill *what runs*.
- **Kind is positional.** Keep `:area/counters`-the-read-model in `:read-models`
  and `:area/counters`-the-query in `:queries`; identity is (kind, name). Same
  name in two kinds is normal, not a duplicate to merge.
- **Don't over-claim production.** `:produces`/`:reads` are asserted, not proven,
  unless the def site declares them. Annotate def sites
  (`:grain.event-model/produces`/`:reads`) to make the validator confirm the
  edge; otherwise treat it as your hypothesis from reading the handler.
- **Descriptions are domain-level; GWT is intent.** Strip ORM/atom/event-store
  mechanics from descriptions. G/W/T is carried as data and is **never executed**
  in distillation — it states acceptance intent (executable G/W/T is a planned
  follow-on; `em-propagate` owns test generation).
- **Screens and flows are hand-authored** and only endpoint/grammar-checked. The
  runtime cannot see a screen exists.
- **Structural only — never mutate.** Distillation needs neither `install!` nor
  `invoke-command!`; do not run them to "see" behaviour. Validation reads
  registries, not the event store.
- **Lenient is the loop; strict is the gate.** Reach `:valid? true` lenient with
  empty `:uncovered`, then opt into `{:strict true}` only when mandating.
- **One area at a time.** Findings are scoped to declared areas; finishing an area
  fully beats half-distilling several.

## Worked snippet

Distilling a `:counter` area that is already running, starting from nothing:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

;; 1–2. Oracle live? What's undocumented?
(tools/catalog)                     ; => commands/queries/read-models/… for :counter
(tools/event-model-coverage {})     ; => everything live is :uncovered (the worklist)
(tools/explain-schema :counter/create-counter)  ; copy the schema verbatim

;; 3–4. Scaffold from registries; fill descriptions + GWT + screens/flows by hand.
(def model
  {:counter
   {:description "Create counters and track their values."
    :commands
    {:counter/create-counter
     {:description "Creates a counter. Name must be unique."   ; domain-level
      :schema [:map [:name :string]]                            ; from explain-schema
      :reads #{:counter/counters}                               ; def-site :reads
      :produces #{:counter/counter-created}                     ; def-site :produces
      :given-when-thens
      [{:given "no counter named \"A\" exists"
        :when  "create-counter with name \"A\""
        :then  "a counter-created event is recorded"}
       {:given "a counter named \"A\" already exists"
        :when  "create-counter with name \"A\""
        :then  "the command is rejected as a conflict"}]}}
    :events
    {:counter/counter-created
     {:description "A counter was created."
      :schema [:map [:counter-id :uuid] [:name :string]]}}
    :read-models
    {:counter/counters
     {:description "All counters, projected from counter events."
      :consumes #{:counter/counter-created}   ; == live read-model :events (confirmable)
      :version 1}}
    :queries
    {:counter/counters
     {:description "Returns all counters."
      :schema [:map]
      :reads #{:counter/counters}}}
    :screens          ; design-only — not in catalog, authored from the UI
    {:counter/dashboard
     {:description "Lists counters and lets the user create them."
      :queries #{:counter/counters}
      :commands #{:counter/create-counter}}}}})

;; 5–6. Drive to clean against the live runtime.
(tools/validate-event-model model)   ; fix :schema/mismatch, :wiring/mismatch, :ref/* …
(tools/event-model-coverage model)   ; shrink :uncovered to {} for :counter

;; 7. Register; gate strict only if mandating the model at boot.
;; (defeventmodel :counter { … })  in your-app/interface/event_model.clj
(tools/validate-event-model model {:strict true})  ; adds coverage + declared-edges + GWT
```

When lenient is `:valid? true` with empty `:uncovered`, the area is distilled.
Hand unclear blocks to `em-elicit`; later edits go through `em-tend`; ongoing
spec↔runtime drift is `em-weed`'s job.
