# Event Model

An Event Model describes a Grain application's service areas, blocks and flows.
Allium specifies the behaviour those blocks implement.

## Model shape

Register each service area with `defeventmodel`. The area is a simple keyword.
A block name uses the area's namespace. Its enclosing map supplies its kind,
so a query and a read model can share a name.

```clojure
(require '[ai.obney.grain.event-model.interface :as em :refer [defeventmodel]])

(defeventmodel :billing
  {:events {:billing/invoice-issued
            {:description "An invoice was issued."
             :schema [:map [:invoice-id :uuid]]}}})
```

This registers topology. Define each runtime event type with `defevent` and
give it the same payload schema. For a complete model with commands, screens
and flows, see the [counter service](../components/example-service/src/ai/obney/grain/example_service/interface/event_model.clj).

| Kind | Grain definition | Topology fields |
|---|---|---|
| command | `defcommand` | `:schema`, `:reads`, `:produces` |
| event | `defevent` | `:schema` |
| read model | `defreadmodel` | `:consumes`, optional `:schema`/`:version` |
| query | `defquery` | `:schema`, `:reads` |
| todo processor | `defprocessor` | `:subscribes`, `:reads`, `:produces` |
| periodic task | `defperiodic` | `:schedule`, `:produces` |
| screen | design-only | `:queries`, `:commands` |

For a keyed projection, model the reducer's per-instance `:state-schema` as
`:schema`. Declare any `:result-schema` on `defreadmodel`. Register command and
query input schemas under their qualified names with `defschemas`.

## Flows and edges

A flow's `:steps` is an ordered vector of maps such as
`{:from [:command :billing/issue-invoice] :to [:event :billing/invoice-issued]}`.
Use `nil` for entry or exit. A step may start at any earlier destination.

```text
command        -> event
event          -> read-model
read-model     -> command | query
query          -> screen | todo-processor
screen         -> command
todo-processor -> command
periodic-task  -> command | event
```

A processor reads its work list through a query. Its `:subscribes` lists the
event types that wake it. The validator compares these with runtime `:topics`.
Declare `:grain.event-model/reads` on commands and queries, and
`:grain.event-model/produces` on commands, processors and periodic tasks.
Use `#{}` for an empty set. Tests exercise the handlers' actual behaviour.

## Allium trace links

Use `:grain/allium` to link a block or flow to a declaration. Paths are relative
to the repository root. For example, the counter creation command includes:

```clojure
{:grain/allium [{:spec "components/example-service/example-service.allium"
                :kind :rule :name "CreateCounter"}]}
```

Composition validation requires a rule for each command and a surface for
each screen. Other links are optional. Each supplied reference must resolve
to an actor, contract, entity, enum, invariant, rule, surface, value or variant
of the specified kind. In development or CI, run:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools]
         '[ai.obney.grain.event-model-validator.interface :as emv])

(tools/validate-spec-composition (em/registered-model)
  {:project-root "." :event-model-opts emv/strict-defaults})
```

The composition check runs `allium check` and `allium parse`.

## Runtime validation and boot mandate

Load the service's model and handler namespaces before validating them.
`(emv/validate-event-model (em/registered-model))` returns `:valid?`, `:summary`
and `:findings`. Use `emv/event-model-coverage` to compare block names by kind.

`{:strict true}` requires reads and outputs to be explicitly declared. The boot
guard also makes uncovered runtime blocks in modeled areas and mismatched wiring,
reads or outputs fatal. Bounded events must have matching schemas and no todo
processor subscribers. Pass `:event-store` to also check active retention policies.

```clojure
(emv/verify-or-throw!)
(ig/init system)
```

On failure the guard throws `ex-info` with the verdict in `:verdict`.
Production startup needs only the runtime validator. The [Grain skill](../skills/grain/SKILL.md)
covers authoring the topology and behavioural specification together.
