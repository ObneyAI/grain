---
name: em-distill
description: >-
  Distil a service-area-first event model from an EXISTING system by reading its
  code — works on ANY codebase, grain or not (a Rails/Node/Java/Clojure service,
  a monolith, a foreign event-sourced system). Maps what the system does onto the
  event-model vocabulary (commands / events / read-models / queries /
  todo-processors / periodic-tasks / screens / flows) and emits an EDN model
  validated structurally with `validate-event-model`. When the target IS a grain
  app with a running nREPL, it additionally uses the live `catalog` as an
  authoritative oracle and writes a `defeventmodel`. Use to understand/document a
  system's CQRS / event-flow shape, or as the blueprint to (re)implement it in
  grain. For a NEW model from intent use em-elicit; to reconcile an existing grain
  model against its runtime use em-weed; for targeted edits use em-tend.
---

# Em-Distil — extract an event model from an existing system

`em-distill` reverse-engineers a **service-area-first event model** from a system
that already exists, by **reading its code**. It is the grain parity of Allium's
`/distill`, and like Allium it does **not** require the target to be a grain
project — any codebase can be distilled into the event-model vocabulary.

There are two modes, and the **source mode is the base** (it works everywhere):

| Mode | When | Input / oracle | Output | Validation |
|---|---|---|---|---|
| **Source** (default) | any system, grain or not | you READ the code | a plain EDN model `{:area {…}}` in a file | `validate-event-model` — degrades to **structural-only** checks (no runtime) |
| **Grain-enhanced** | target is a grain app with a running nREPL (:7888) | the live `catalog` registries | a `(defeventmodel :area {…})` form | the **authoritative** oracle (coverage + schema + wiring against the real runtime) |

If the system isn't grain, you only have the source mode — and that is fine: the
validator is built to degrade gracefully (it runs shape, connection-grammar,
referential-integrity, kind-typing and flow-continuity checks with no registries
loaded, and reports `:runtime/registries-present? false`). What it *cannot* do
without a runtime is confirm coverage or that schemas match a live registry — for
a foreign system that is the human's job (review for semantic accuracy).

## When to use this vs a sibling (Boundaries)

- **em-distill** — a system already exists and you want its event model *out of
  the code*. Both for a foreign system (document / migration blueprint) and for a
  grain app (authoritative distillation from the runtime).
- **em-elicit** — no system yet; build the model forward from intent/conversation.
- **em-weed** — a grain model already exists and you must reconcile it against the
  live runtime (resolve drift). Distil produces the first draft; weed keeps it honest.
- **em-tend** — targeted edits to an existing model.
- **em-propagate** — turn a model into tests.

## The model you are producing

A map of **service areas** (bounded contexts), each owning its blocks and flows;
blocks keyed `:<area>/<name>`, kind from structural position (see the
`event-model` skill for the full grammar). For a non-grain system you write plain
EDN; for a grain app you write a `(defeventmodel :area {…})`. Same shape either way.

## Mapping a foreign system onto the event-model vocabulary

Read the code and classify. The point is to capture **observable behaviour and
data flow**, not implementation. Typical source → kind mappings:

| What you find in the code | event-model kind |
|---|---|
| a write endpoint / mutation / RPC that changes state; a "do X" use-case / service method / command handler | **command** (`:schema` ≈ the request/params shape; `:produces` = the events it causes; `:reads` = state it consults) |
| a domain fact / "X happened" record; rows appended to an event/audit table; a message published to a bus/topic; a webhook emitted | **event** (`:schema` = the fact's payload) |
| a materialized view / denormalized/read table / cache / projection rebuilt from events or write-side changes | **read-model** (`:consumes` = the events that update it) |
| a read endpoint / GET / query handler / report that returns view data | **query** (`:schema` = params; `:reads` = the read-models it serves from) |
| a background worker / message consumer / reactor / saga / process-manager that responds to events and triggers more work | **todo-processor** (`:subscribes` = event topics it triggers on; `:reads` = the **query** "TODO list" it works from — its modeled input edge; `:produces` = commands it issues) |
| a cron job / scheduled task / timer | **periodic-task** (`:schedule` map; `:produces`) |
| a UI page / screen / view template | **screen** (design-only; `:queries` shown, `:commands` issued) |
| an end-to-end user or system journey across the above | **flow** (kind-qualified `[kind :area/name]` step chain) |
| a bounded context / top-level module / domain package / microservice | a **service area** (the `:<area>` key) |

If the system is **already event-sourced** (an event store, CQRS, or a message
bus), this is close to mechanical — its events and commands map almost directly.
If it is a CRUD monolith, infer the *implied* events: a state-changing operation
that today just `UPDATE`s a row is a **command** whose **event** is the change it
represents ("OrderShipped"), and the rows it reads are an implied **read-model**.

## Abstraction discipline (filter implementation)

Borrow Allium's distillation tests on every block — keep the *what/why*, drop the
*how*:

1. **Why does the stakeholder care?** If the answer is purely technical (a cache, a
   retry, a serializer), it is implementation — leave it out.
2. **Could it be implemented differently and still be the same system?** If yes,
   the differing detail (Postgres vs Mongo, REST vs gRPC, which library) is
   implementation — exclude it.
3. **Template vs instance?** Model the *kind* of thing, not one concrete record.

Exclude: DB schemas/migrations, framework/transport, auth mechanics, algorithms,
pagination, logging. Include: the commands a user/other-system can issue, the
events that result, the read models/queries they feed, the reactors and schedules,
and how they wire into flows. Name areas after bounded contexts; name blocks for
domain meaning (`:billing/charge-card`, not `:billing/post-handler`).

## Process — Source mode (any system)

1. **Scope.** Pick the area(s) to distil (one bounded context at a time). State
   Scope / Includes / Excludes.
2. **Sweep the code** for each kind using the mapping table — write endpoints →
   commands, state changes / published facts → events, views/reports → read-models
   + queries, workers/consumers → todo-processors, cron → periodic-tasks, UI →
   screens.
3. **Apply the abstraction tests**; filter implementation; choose domain names.
4. **Write the EDN model** to a file, e.g. `<area>.event-model.edn`:
   ```clojure
   {:billing
    {:description "Billing context."
     :commands {:billing/charge-card {:description "Charge a customer's card for an invoice."
                                      :schema [:map [:invoice-id :uuid] [:amount :int]]
                                      :reads #{:billing/invoices}
                                      :produces #{:billing/card-charged :billing/charge-failed}
                                      :given-when-thens [{:given "an open invoice" :when "charge-card" :then "a card-charged event"}]}}
     :events {:billing/card-charged {:description "A card was charged." :schema [:map [:invoice-id :uuid] [:amount :int]]}
              :billing/charge-failed {:description "A charge failed." :schema [:map [:invoice-id :uuid] [:reason :string]]}}
     :read-models {:billing/invoices {:description "Invoices and their status." :consumes #{:billing/card-charged :billing/charge-failed}}}
     :queries {:billing/invoice {:description "One invoice by id." :schema [:map [:invoice-id :uuid]] :reads #{:billing/invoices}}}
     :flows {:billing/charge {:description "Charge an open invoice."
                              :steps [{:from [:command :billing/charge-card] :to [:event :billing/card-charged]}
                                      {:from [:event :billing/card-charged] :to [:read-model :billing/invoices]}]}}}}
   ```
   Notes for foreign systems: command/event/query `:schema` is **required** by the
   format — write a best-effort malli schema (`[:map …]`, or `[:map]` if the shape
   is unknown); it is checked for well-formedness, not matched against any runtime.
   GWT is optional (good documentation; not enforced off-grain).
5. **Validate structurally** over the REPL (no app needed):
   ```clojure
   (require '[ai.obney.grain.event-model-validator.interface :as emv])
   (emv/validate-event-model-file "billing.event-model.edn" {:structural-only true})
   ;; => {:valid? … :summary {… :runtime/registries-present? false} :findings […]}
   ```
   Pass `{:structural-only true}` for a foreign model — it forces the spec-internal
   checks even if a grain app happens to be loaded in the same JVM (otherwise the
   live-comparison checks would flag every foreign block as `:block/undeclared`).
   Fix the structural findings until `:valid?` is true: `:model/malformed` (shape),
   `:block/misnamespaced` (block ns ≠ area), `:schema/malformed`,
   `:flow/illegal-connection` / `:flow/dangling-reference`, `:ref/dangling` /
   `:ref/wrong-kind` (an intent edge naming a non-existent / wrong-kind block),
   `:flow/discontinuous`. Coverage / schema-match / wiring findings will **not**
   appear (no runtime) — that limit is expected; `:runtime/registries-present?
   false` confirms you are in structural-only mode. The human reviews semantic
   accuracy (did I read the system right?).
6. **Repeat per area**; cross-area edges in a flow are fine (the validator resolves
   refs across the whole model). Iterate until a fresh pass finds nothing new.

The result is a documented event model of the system — useful on its own to
understand its CQRS/event-flow shape, and ready to serve as a **migration
blueprint** (below).

## Process — Grain-enhanced mode (target is a grain app)

When the system you are distilling **is** grain and is running, switch on the
oracle for an authoritative distillation:

1. Connect to the app's nREPL (`:7888`); `(require '[ai.obney.grain.code-agent-tools.interface :as t])`. Requiring the base loads the handlers (registries populate).
2. `(t/catalog)` enumerates the live blocks; `(t/event-model-coverage {})` shows what is undocumented. Scaffold the **introspectable skeleton** from the registries: block names + kinds, command/event/query `:schema` (from the live schema registry, so they MATCH), read-model `:consumes` (from `:events`), processor `:subscribes` (from `:topics`), periodic `:schedule`, and any def-site `:grain.event-model/produces`/`:reads`.
3. Read the handler code/docstrings to fill what the runtime can't: `:description`, the production edges (`:produces`/`:reads`), GWT, flows.
4. Write it as `(defeventmodel :area {…})` and validate against the oracle: `(t/validate-event-model model)` then the gate `(t/validate-event-model model {:strict true})`. Now coverage/schema-match/wiring ARE checked. Drive to clean, then hand to **em-weed** to keep it honest as the code evolves.

## Migration blueprint (distil foreign → implement in grain)

The distilled EDN model is the target design for porting the system to grain:

1. Distil the source system (source mode) → a validated EDN model per area.
2. Implement each block: `defcommand`/`defquery`/`defreadmodel`/`defprocessor`/
   `defperiodic` handlers, annotating def sites with `:grain.event-model/produces`
   and `:grain.event-model/reads` to match the model's edges.
3. Turn the EDN model into a `(defeventmodel :area …)` (same data) and register it.
4. Run **em-weed** / strict `validate-event-model` against the new grain runtime
   until the model and the implementation agree; wire `verify-or-throw!` to mandate
   it. Use **em-propagate** to turn the model's GWT into executable tests that
   prove the new handlers behave like the old system.

## Guardrails

- **Behaviour, not implementation.** Re-run the three abstraction tests on every
  block. A distilled model full of `:post-handler`/`:dao`/`:dto` names is a sign
  you captured the code, not the system.
- **One area at a time.** Large systems need several passes (a Ralph-style loop):
  distil an area, validate, review, repeat until a pass finds nothing new.
- **Off-grain limits are real.** Without a runtime you cannot confirm coverage or
  schema accuracy — say so; don't imply the model is verified against a system it
  was only read from. Mark genuinely uncertain blocks/edges and ask.
- **Prefer command-replay semantics.** When inferring events for a CRUD system,
  name the event for the *domain change* ("invoice-paid"), not the table write.
- **Cross-check produces/reads.** Every `:produces`/`:reads`/`:consumes`/
  `:subscribes` entry must resolve to a real block of the right kind (the validator
  enforces this even off-grain) — so the dependency graph stays honest.
