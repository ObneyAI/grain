---
name: em-elicit
description: "Run a structured discovery session to build a NEW grain service-area event model from intent — through conversation. Use when the user wants to design an event model from scratch, elicit or capture domain behaviour for a service area, specify a feature/area as commands/events/read-models/queries/processors/periodic-tasks/screens/flows, turn a process description or vague idea into a (defeventmodel :area {...}), or shape requirements into a validatable model. Phased discovery (scope -> blocks -> flows -> edges -> Given/When/Then), authoring the defeventmodel incrementally and validating with (validate-event-model model) over the running app's nREPL until (validate-event-model model {:strict true}) is clean. For existing code/catalog use em-distill; for targeted edits to a model use em-tend; for spec<->runtime drift use em-weed; for tests use em-propagate; for the format/finding reference use the event-model entry skill."
---

# Elicitation (em-elicit)

## Purpose

Build a NEW service-area event model from intent through structured conversation.
You hold a running grain app on the other end of the project nREPL (`:7888`) as a
**live oracle**: the model you author is plain `defeventmodel` EDN, and
`(validate-event-model model)` answers it against the real `catalog`, schema
registry, and wiring — not a text linter. This is the key difference from a
static spec language: there is no separate compiler to satisfy; the running
system *is* the checker.

The unit you produce is exactly one service **area**: `(defeventmodel :area {...})`
— a map owning `:commands :events :read-models :queries :todo-processors
:periodic-tasks :screens :flows`. You design it by conversation, validating after
each increment. The session ends when `(validate-event-model model {:strict true})`
is clean — i.e. the app would boot under the `verify-or-throw!` guard.

## When to use / not use

Use em-elicit when:
- starting a new area from a process description, a feature request, or a vague idea;
- the user is describing what a system should do and needs it shaped into
  commands/events/read-models/queries/processors/periodic-tasks/screens/flows;
- substantial new behaviour needs structured discovery (even if the area's
  `defeventmodel` already exists — elicit, then hand the diff to em-tend).

Use a sibling instead when:
- **em-distill** — code/`catalog` already implements the behaviour and you want to
  capture it: `(catalog)` -> skeleton model. Elicit is intent-first; distill is
  code-first.
- **em-tend** — you already know the small, targeted edit to make to an existing model.
- **em-weed** — the model and runtime have drifted and you want to reconcile them.
- **em-propagate** — you want Given/When/Then turned into tests exercised against the runtime.
- **event-model** (entry) — you just need the format or the finding taxonomy:
  `(guide :event-model)`, `(guide :findings)`.

Grain nuance to state up front: elicit usually runs **ahead of code**. With no
handlers loaded, `(validate-event-model model)` degrades to spec-internal checks
(shape + CQRS grammar + referential integrity). The **strict** gate only goes
green once the handlers, registered schemas, def-site `:grain.event-model/produces`
/ `:reads`, and GWT exist and agree. So elicit's terminal state implies the
runtime is being implemented alongside — or you hand off to implementation and
re-run strict when it lands.

## Before you start: connect the oracle

The validators read the live registries. Loading the app's base namespace
populates them (handlers self-register at load via the `def*` macros; a
`defeventmodel` registers the model). Over the nREPL:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])
(require '[ai.obney.grain.event-model.interface :refer [defeventmodel]])

(tools/catalog)             ; live blocks — neighbouring areas, naming, registered schemas
(tools/guide :getting-started)
(tools/guide :event-model)  ; the format
(tools/guide :flows)        ; the legal CQRS adjacency grammar
(tools/guide :findings)     ; the finding taxonomy + how to resolve each
```

`install!` (`(tools/install! {:system app :context (::context app) :mode :dev})`
after `(ig/init system)`) is only needed for *execution* tools
(`invoke-command!`/`invoke-query`/`events`/`projection`) — not for `validate-*`,
which build on `catalog` and never throw.

## Process

The loop is **gather -> act -> verify -> repeat**. Hold the area under
construction as a model literal in the REPL (the validators take a full model = a
map of areas), and re-validate after every increment:

```clojure
(def model {:billing { ... }})
(tools/validate-event-model model)                ; lenient — run after each edit
(tools/validate-event-model model {:strict true}) ; the boot-guard gate — run at the end
```

### Phase 0 — Process discovery (optional)

If the user brings a process or a vague idea, let them describe it in their own
words before imposing structure. Capture the process, the actors, and the
outcomes (facts that get recorded). If they already name blocks ("I need a
create-invoice command and an invoice-created event"), skip to Phase 2.

Translate as you listen: a thing that HAPPENS by decision -> a **command**; a fact
that gets RECORDED -> an **event**; a thing you READ -> a **query** over a
**read-model**; an automation that works a "TODO list" (reads a query
and issues a command) -> a **todo-processor**; a schedule -> a
**periodic-task**; a user surface -> a **screen**.

### Phase 1 — Scope (the area)

Establish exactly one area and its boundary. One question at a time:
1. "What is this area fundamentally about, in one sentence?" -> `:description`.
2. "What's the area keyword?" (a *simple* keyword, e.g. `:billing`) — it becomes
   the namespace of every block key `:billing/<name>`.
3. "What's in scope vs out? Which neighbouring areas does this depend on?" Scan
   `(tools/catalog)` so you reuse existing names rather than colliding.

Record out-of-scope items and unresolved choices as **open decisions**
(`;; OPEN: ...` beside the model, or in `:description`) — never silently assume.
Seed the literal:

```clojure
(def model {:billing {:description "Invoicing and payment capture for the billing area."}})
(tools/validate-event-model model)   ; shape-only; should be clean
```

### Phase 2 — Blocks

Enumerate the building blocks one kind at a time, one question at a time. Author
the key `:billing/<name>` plus its required fields, then re-validate.

- **Commands** — "What decisions/actions does an actor take?" `:description` +
  `:schema` (params, malli-shaped).
- **Events** — "What facts get recorded?" `:description` + `:schema` (body).
- **Read-models** — "What projected state do we read?" `:description` +
  `:consumes` (events). No own `:schema` by convention.
- **Queries** — "What reads does a screen/caller need?" `:description` +
  `:schema` (params) + `:reads`.
- **Todo-processors** — reactors: `:description` + `:subscribes` (events)
  [+ `:produces` commands].
- **Periodic-tasks** — `:description` + `:schedule`
  (`{:every 30 :duration :seconds}` or `{:cron "..."}`) [+ `:produces`].
- **Screens** — design-only surfaces: `:description` + `:queries`/`:commands`.

Apply the abstraction discipline — the grain version of "could it be built
differently and still be the same area?" The `:schema`, the produced events, and
the edges are **domain-level (in the model)**; the handler body, storage, and
transport are **implementation (never in the model)**. If swapping it leaves the
same area (Postgres vs Redis, REST vs gRPC) it stays out; if it changes the
contract (which event is emitted, what the params are) it stays in.

Validate after each kind and fix before moving on:

```clojure
(tools/validate-event-model model)
;; resolve: :model/malformed, :block/misnamespaced, :schema/malformed.
;; once handlers exist: :schema/mismatch (your :schema vs the live one),
;;                      :block/undeclared (a model block with no live counterpart).
```

### Phase 3 — Flows

Wire the blocks into the CQRS data flow under `:flows`. Each flow is
`{:description "..." :steps [{:from <endpoint> :to <endpoint>} ...]}`; an endpoint
is **kind-qualified** `[:command :billing/charge]`, or `nil` (entry/terminus).
The legal adjacency (`(tools/guide :flows)`):

```
command        -> event
event          -> read-model
read-model     -> command | query
query          -> screen | todo-processor
screen         -> command
todo-processor -> command
periodic-task  -> command | event
```
Read-models feed only commands and queries (never a screen/processor directly). A
todo-processor's input is a query (the "TODO list"), NOT a read-model or a raw
event (`event -> todo-processor` and `read-model -> todo-processor` are rejected);
the processor's event `:subscribes` is its runtime trigger, recorded as wiring,
not a flow edge.

Trace the happy path first ("if we built one path through this area, what is
it?"), then add reaction and scheduled flows. Validate:

```clojure
(tools/validate-event-model model)
;; :flow/illegal-connection (grammar), :flow/dangling-reference (unknown endpoint),
;; :flow/discontinuous (a :from with no producing prior step).
```

### Phase 4 — Intent edges

Declare each block's dependency edges so the graph is explicit and matches the
def-site annotations the runtime carries:
- command/query `:reads` (read-models); command `:produces` (events);
- read-model `:consumes`; todo-processor `:subscribes` (event trigger) + `:reads`
  (its TODO-list query — the modeled input) [+ `:produces` commands];
  periodic `:produces`;
- screen `:queries`/`:commands`.

These mirror the def-site opts `:grain.event-model/produces` /
`:grain.event-model/reads` on `defcommand`/`defquery`/`defprocessor`/`defperiodic`.
Validate:

```clojure
(tools/validate-event-model model)   ; :ref/dangling, :ref/wrong-kind, :wiring/mismatch,
                                     ; :produces/mismatch, :reads/mismatch
(tools/event-model-coverage model)   ; bidirectional drift: in-model-not-live, and vice-versa
```

Only `event -> read-model` (read-model `:consumes`) and a processor's event
`:subscribes` (vs live `:topics`) are confirmable against real wiring;
`:wiring/mismatch` flags drift there. Every other edge is a production edge inside a handler body —
checked for endpoint existence + grammar only (the validator never claims a
producer actually produces).

### Phase 5 — Given/When/Then + the strict gate

Every command must carry `:given-when-thens` — a vector of
`{:given "..." :when "..." :then "..."}` strings (data, never executed) — for
strict to pass. Elicit them as acceptance scenarios: the happy path plus each
rejection ("a counter named A already exists -> rejected as a conflict"). These
are also what **em-propagate** later turns into tests.

Then run the gate — *exactly* what the boot-guard enforces:

```clojure
(tools/validate-event-model model {:strict true})
```

Strict adds, on top of all structural errors and spec<->runtime mismatches:
- **full coverage** — every live block in a spec'd area must be in the model
  (`:block/uncovered` becomes fatal);
- **declared edges** — every command/processor/periodic must declare
  `:grain.event-model/produces`, every command/query `:reads`, at its def site
  (`:produces/undeclared`, `:reads/undeclared`);
- **GWT** — every command in the model must carry Given/When/Then (`:gwt/missing`).

`:auth/missing` is *not* fatal (deny-by-default is not a model defect). Resolve
each finding with `(tools/guide :findings)`; for one block,
`(tools/guide :billing/charge {:spec model})` merges the live docstring, the live
schema, and your spec's GWT/edges.

When strict is clean, register it in the service and confirm via the shippable guard:

```clojure
(defeventmodel :billing { ... the area-map (the value under :billing) ... })

(require '[ai.obney.grain.event-model-validator.interface :as event-model-validator])
(event-model-validator/verify-event-model!)   ; reads the registered model, strict, returns the verdict
```

The app now boots under `verify-or-throw!`.

## Guardrails

- **One area per session; depth over breadth.** Fully develop the core flow before
  enumerating peripheral blocks — context exhaustion mid-area leaves nothing
  validatable.
- **Validate after every increment** with lenient `(validate-event-model model)`.
  Never batch a whole area and validate once at the end.
- **Never invent APIs.** The only oracle is the REPL: `catalog`,
  `validate-event-model` (+ `-var`/`-file`), `event-model-coverage`,
  `guides`/`guide`, and (for execution) `install!` + `invoke-command!` /
  `invoke-query` / `events` / `projection`. `{:strict true}` *is* the boot guard;
  don't approximate it by eye.
- **Keys are `:<area>/<name>` — the namespace IS the area** (`:block/misnamespaced`
  otherwise). Identity is the pair **(kind, name)**: the same name can be a
  read-model AND a query, or a command AND a todo-processor. Keep flow and edge
  endpoints kind-qualified.
- **The model is intent, not implementation.** Keep handler internals, storage,
  and transport out. The schema, the produced events, and the edges are the contract.
- **Record open decisions explicitly** (`;; OPEN:`); don't resolve ambiguity by
  assumption. Resolve equivalent terms — don't let "charge" and "payment" both
  survive into the keys.
- **Lenient-clean with no registries loaded only proves shape + grammar + refs.**
  Don't call the area done until `{:strict true}` is clean against loaded handlers
  — that is the only signal that matches the boot guard.

## Worked snippet

A minimal billing area, elicited to a strict-ready model:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(def model
  {:billing
   {:description "Invoicing and payment capture."
    :commands
    {:billing/charge
     {:description "Charges an open invoice. Amount must be positive."
      :schema [:map [:invoice-id :uuid] [:amount :int]]
      :reads #{:billing/invoices}
      :produces #{:billing/charged}
      :given-when-thens
      [{:given "an open invoice exists"
        :when  "charge with a positive amount"
        :then  "a charged event is recorded"}
       {:given "the invoice is already paid"
        :when  "charge that invoice"
        :then  "the command is rejected as a conflict"}]}}
    :events
    {:billing/charged {:description "An invoice was charged."
                       :schema [:map [:invoice-id :uuid] [:amount :int]]}}
    :read-models
    {:billing/invoices {:description "Invoices projected from billing events."
                        :consumes #{:billing/charged} :version 1}}
    :queries
    {:billing/invoices {:description "Lists invoices." :schema [:map]
                        :reads #{:billing/invoices}}}
    :screens
    {:billing/dashboard {:description "Shows invoices and lets the user charge them."
                         :queries #{:billing/invoices} :commands #{:billing/charge}}}
    :flows
    {:billing/charge-flow
     {:description "User charges an invoice; the projection updates and the screen re-reads it."
      :steps [{:from [:screen :billing/dashboard]    :to [:command :billing/charge]}
              {:from [:command :billing/charge]      :to [:event :billing/charged]}
              {:from [:event :billing/charged]       :to [:read-model :billing/invoices]}
              {:from [:read-model :billing/invoices] :to [:query :billing/invoices]}
              {:from [:query :billing/invoices]      :to [:screen :billing/dashboard]}]}}}})

(tools/validate-event-model model)                ; lenient — clean shape/grammar/refs
(tools/event-model-coverage model)                ; align names with the live catalog
(tools/validate-event-model model {:strict true}) ; the gate — green once handlers + def-site edges + GWT exist
```

Once strict is green, lift the value under `:billing` into
`(defeventmodel :billing {...})` in the service's `interface/event_model.clj`,
require that namespace in the base, and `(event-model-validator/verify-event-model!)`
to confirm the app boots under the guard. Hand follow-on edits to **em-tend**, the
GWT-to-tests step to **em-propagate**, and any later spec<->runtime drift to **em-weed**.
