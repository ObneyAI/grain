# Event Model

The `event-model` component defines a **service-area-first** specification format
for a grain application — and the structural validators that check a spec against
a *live* grain runtime over the REPL.

An event model is plain EDN data. It maps **1:1** onto grain's building blocks
(`defcommand`/`defquery`/`defreadmodel`/`defprocessor`/`defperiodic` + screens)
and onto Event Modeling's CQRS data flow, organized around **service areas**
rather than entities. The spec is the *primary* design artefact; the validators
make it answerable to the running system.

This supersedes the prior flat, entity/`view`-oriented event model. The behaviour
of each block still lives in its own grain component; this component only
describes what a *well-formed model* looks like and how to reconcile one with the
live registries. The companion meta-spec is
[`event-model.allium`](../components/event-model/event-model.allium).

## Why this exists

Grain has no first-class "service area" — the `:<area>` keyword segment is only a
naming convention, and code is organized type-first (commands.clj, queries.clj,
…). An event model gives that area an explicit, owned description, and the
[code-agent-tools](code-agent-tools.md) validators turn it into a verification
signal: a structural pass/fail against the live `catalog`, schema registry, and
wiring — not a static text check.

## The model

A model is a map of service **areas**, keyed by a simple keyword. Each area owns
its blocks and flows:

```clojure
{:example
 {:description "Counter service area."
  :commands        {:example/create-counter {:description "..." :schema [:map [:name :string]]
                                             :produces #{:example/counter-created}}}
  :events          {:example/counter-created {:description "..." :schema [:map [:counter-id :uuid]]}}
  :read-models     {:example/counters {:description "..." :consumes #{:example/counter-created}}}
  :queries         {:example/counters {:description "..." :schema [:map] :reads #{:example/counters}}}
  :todo-processors {:example/avg {:description "..." :subscribes #{:example/counter-created}}}
  :periodic-tasks  {:example/tick {:description "..." :schedule {:every 30 :duration :seconds}}}
  :screens         {:example/dash {:description "..." :queries #{:example/counters}
                                   :commands #{:example/create-counter}}}
  :flows           {:example/lifecycle {:description "..." :steps [...]}}}}
```

### Keying and identity

Blocks are keyed by the **runtime convention** `:<area>/<name>` — exactly the key
each `def*` macro registers (`defcommand :example create-counter` →
`:example/create-counter`). The keyword **namespace is the area**; the block's
**kind comes from structural position** (which map it sits in), not the keyword.

A single `:<area>/<name>` is therefore **not unique across kinds** — in the
example service `:example/counters` is both a read-model *and* a query, and
`:example/calculate-average-counter-value` is both a command *and* a
todo-processor. Identity is the pair **(kind, name)**, so the validator joins
kind-partitioned and flow endpoints are kind-qualified.

### Kinds (1:1 with grain)

| kind | grain macro | spec fields | live join |
|---|---|---|---|
| `command` | `defcommand` | `:schema` (params), `:reads`, `:produces`, `:given-when-thens` | command registry + schema + `:authorized?` |
| `event` | `->event` | `:schema` (body) | schema registry + consumption sets |
| `read-model` | `defreadmodel` | `:consumes`, optional `:schema`/`:version` | read-model registry + `:events` |
| `query` | `defquery` | `:schema` (params), `:reads` | query registry + schema + `:authorized?` |
| `todo-processor` | `defprocessor` | `:subscribes`, `:produces` | processor registry + `:topics` |
| `periodic-task` | `defperiodic` | `:schedule` (a map), `:produces` | periodic registry + `:schedule` |
| `screen` | — (design-only) | `:queries`, `:commands` | none (deps checkable) |

`:schedule` is a map: `{:every 30 :duration :seconds}` or `{:cron "..."}`.
Given/When/Then are carried on commands as **data** (`{:given :when :then}`); they
document intent and are never executed.

### Dependency edges

Each block declares its dependencies as **kind-typed intent edges**, so the graph
is expressible without authoring full flows: a screen's `:queries`/`:commands`; a
command's/query's `:reads` (read-models); a command's `:produces` (events); a
read-model's `:consumes` (events); a todo-processor's `:subscribes` (events) and
`:produces` (commands); a periodic-task's `:produces`. The validator type-checks
each edge — the target must exist *and* be of the expected kind. A screen is
commonly 1:1 with a single query but may compose several or none.

### Flows and the connection grammar

A flow is a named, ordered chain of steps under an area's `:flows`. Each step is
`{:from <endpoint> :to <endpoint>}`; an endpoint is **kind-qualified**
`[kind :<area>/<name>]`, or `nil` to mark an entry point / terminus.

Legal CQRS adjacency:

```
command        -> event
event          -> read-model | todo-processor
read-model     -> query | command | screen
query          -> screen
screen         -> command
todo-processor -> command
periodic-task  -> command | event
```

Only `event -> read-model` and `event -> todo-processor` are **confirmable
against live wiring** (read-model `:consumes` / processor `:topics`). Every other
edge is a *production* edge that lives inside handler bodies and is invisible to
runtime introspection, so it is checked for endpoint existence + grammar only —
the validator never claims a producer actually produces.

## Validating against the live runtime

The validators live in [code-agent-tools](code-agent-tools.md) and run over
nREPL. They are **structural only** (no command invocation, no Given/When/Then
execution), build on `catalog`, need **no `install!`**, degrade to spec-internal
checks when no registries are loaded, and never throw.

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(tools/validate-event-model my-model)
;; => {:valid? false
;;     :summary {:findings 2 :errors 1 :warnings 1 :info 1 :by-type {...}
;;               :areas [:example] :runtime/registries-present? true}
;;     :findings [{:type :schema/mismatch :severity :error :area :example
;;                 :kind :command :block :example/create-counter
;;                 :spec/schema [:map [:name :int]] :live/schema [:map [:name :string]]
;;                 :message "..."} ...]}

(tools/validate-event-model-file "components/.../example.event-model.edn")
(tools/validate-event-model-var 'my-app.model/event-model)
(tools/event-model-coverage my-model)   ; bidirectional spec<->live coverage diff
```

`:valid?` is true when there are no `:error`-severity findings. Pass
`{:schema-match :lenient}` to downgrade schema mismatches to warnings, or
`{:error-severities #{...}}` to change which severities fail.

### Findings

| `:type` | severity | meaning |
|---|---|---|
| `:model/malformed` | error | spec does not conform to the `:event-model` schema (short-circuits) |
| `:block/misnamespaced` | error | a block key's namespace ≠ its area |
| `:block/undeclared` | error | a spec block has no live runtime counterpart |
| `:block/uncovered` | warning | a live block (in a spec'd area) is missing from the spec |
| `:schema/malformed` | error | a block `:schema` does not parse |
| `:schema/unresolved-ref` | warning | a `:schema` references an unregistered name |
| `:schema/unregistered` | warning | a live command/query/consumed-event has no schema |
| `:schema/mismatch` | error | a spec `:schema` differs from the live registered schema |
| `:flow/illegal-connection` | error | a flow step violates the CQRS grammar |
| `:flow/dangling-reference` | error | a flow endpoint names a non-existent block |
| `:ref/dangling` | error | an intent edge names a non-existent block |
| `:ref/wrong-kind` | error | an intent edge names a block of the wrong kind |
| `:wiring/mismatch` | warning | a spec `:consumes`/`:subscribes`/`:schedule` diverges from live wiring |
| `:produces/mismatch` | warning | spec `:produces` diverges from a def-site `:grain.event-model/produces` declaration |
| `:reads/mismatch` | warning | spec `:reads` diverges from a def-site `:grain.event-model/reads` declaration |
| `:flow/discontinuous` | warning | a step's `:from` has no producing prior step |
| `:auth/missing` | warning | a live command/query has no `:authorized?` predicate |
| `:runtime/design-only`, `:runtime/absent` | info | notices, not failures |

Coverage, missing-schema, and auth findings are **scoped to the areas the spec
declares**, so validating one area never reports on blocks of another.

### Confirming production & read edges (opt-in, non-breaking)

The runtime catalog records what each block **consumes** (read-model `:events`,
processor `:topics`, periodic `:schedule`) but not what it **produces** —
`command→event`, `todo-processor→command`, `periodic→event` — because that lives
inside handler bodies. So by default the validator only type-checks those edges'
endpoints + grammar.

To have them **confirmed**, a service annotates its def sites with the same edges
(matching grain's `:grain.control/*` keyword convention):

```clojure
(defcommand :example create-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-created}
   :grain.event-model/reads    #{:example/counters}}
  ...)
```

This is **fully non-breaking**: every `def*` macro merges its full opts map (no
whitelist, no closed schema), so the annotation flows into the registry and the
`catalog` with no macro or runtime-engine change; no existing consumer reads it.
`validate-event-model` promotes it to `catalog` keys `:produces`/`:reads`, and
`check-production` compares it to the spec — reporting `:produces/mismatch` /
`:reads/mismatch` on drift, and confirming the edge on agreement. It fires only
when the def site declares the edge; otherwise the edge stays asserted-only.

This confirms **spec ↔ code-declaration** agreement (catching drift, and giving
the catalog a production graph). Proving the handler *actually* emits those events
is the **declaration ↔ behaviour** gap — closed only by executable Given/When/Then
(a planned follow-on). `example-service` is annotated as the worked example.

## REPL-served instructions

The code-agent-tools also serve authoritative usage instructions from the running
system, so an agent can bootstrap itself without external docs:

```clojure
(tools/guides)                 ; index: concept guides + a synthetic entry per live block + tools
(tools/guide :getting-started) ; the end-to-end workflow
(tools/guide :event-model)     ; this format
(tools/guide :findings)        ; the finding taxonomy + how to resolve each
(tools/guide :example/create-counter {:spec my-model})
;; ^ a usage card merging the live handler docstring, the live schema, and the
;;   spec's description / Given-When-Then / intent edges for that block.
```

Each `catalog` block entry also carries an `:instructions {:guide <name>}` pointer.

## Authoring workflow

Today specs are hand-authored (or distilled from `catalog`). The format is
designed so the Allium-style skill suite (elicit / distill / propagate / tend /
weed) can slot in later: `distill` fills the introspectable skeleton from
`catalog`; `weed` is the spec↔live drift the validator already reports;
`tend`/`elicit` target `validate-event-model-var`. Executable Given/When/Then
(running the example via `invoke-command!` and asserting the emitted events) is a
planned follow-on; the schema already carries the examples as data.
