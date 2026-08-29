# Event Model

Grain projects use two complementary specifications:

- **Allium** describes observable domain behaviour and produces behavioural test
  obligations.
- **Event Model** describes the Grain service topology: CQRS blocks, payload
  schemas, dependency edges, screens, and flows.

The `event-model` component defines the topology format. The shippable
`event-model-validator` reconciles it with a live Grain runtime; the dev-only
`code-agent-tools` additionally verifies explicit links to Allium declarations.

## Model shape

An Event Model is EDN keyed first by service area and then by runtime block name:

```clojure
{:example
 {:description "Counter service area."
  :commands
  {:example/create-counter
   {:description "Create a counter."
    :schema [:map [:name :string]]
    :reads #{:example/counters}
    :produces #{:example/counter-created}
    :grain/allium
    [{:spec "components/example-service/example-service.allium"
      :kind :rule
      :name "CreateCounter"}]}}
  :events
  {:example/counter-created
   {:description "A counter was created."
    :schema [:map [:counter-id :uuid]]}}
  :read-models
  {:example/counters
   {:description "Projected counters."
    :consumes #{:example/counter-created}}}
  :queries
  {:example/counters
   {:description "Return counters."
    :schema [:map]
    :reads #{:example/counters}}}
  :screens
  {:example/dashboard
   {:description "Manage counters."
    :queries #{:example/counters}
    :commands #{:example/create-counter}
    :grain/allium
    [{:spec "components/example-service/example-service.allium"
      :kind :surface
      :name "CounterManagement"}]}}
  :flows
  {:example/counter-lifecycle
   {:description "Create and display a counter."
    :steps [{:from [:screen :example/dashboard]
             :to [:command :example/create-counter]}
            {:from [:command :example/create-counter]
             :to [:event :example/counter-created]}
            {:from [:event :example/counter-created]
             :to [:read-model :example/counters]}
            {:from [:read-model :example/counters]
             :to [:query :example/counters]}
            {:from [:query :example/counters]
             :to [:screen :example/dashboard]}]}}}}
```

The keyword namespace is the service area. A block's kind comes from structural
position, so identity is `(kind, :area/name)`: the same keyword may identify a
read model and a query.

| Kind | Grain definition | Topology fields |
|---|---|---|
| command | `defcommand` | `:schema`, `:reads`, `:produces` |
| event | `->event`/schema registry | `:schema` |
| read model | `defreadmodel` | `:consumes`, optional `:schema`/`:version` |
| query | `defquery` | `:schema`, `:reads` |
| todo processor | `defprocessor` | `:subscribes`, `:reads`, `:produces` |
| periodic task | `defperiodic` | `:schedule`, `:produces` |
| screen | design-only | `:queries`, `:commands` |

Event Model deliberately has no Given/When/Then field. Behavioural examples,
rules, invariants, and their generated tests belong in Allium.

## Flows and edges

Intent edges are kind checked. For example, command `:reads` targets read models,
read-model `:consumes` targets events, and screen `:commands` targets commands.

Flow endpoints are `[kind :area/name]`; `nil` marks an entry or terminus. Legal
adjacency is:

```text
command        -> event
event          -> read-model
read-model     -> command | query
query          -> screen | todo-processor
screen         -> command
todo-processor -> command
periodic-task  -> command | event
```

Read models are internal projections. Screens and automations read through
queries. Processor `:subscribes` is trigger wiring, not an `event -> processor`
flow edge.

Production and read edges can be compared with runtime declarations placed on
the defining macro:

```clojure
(defcommand :example create-counter
  {:authorized? (constantly true)
   :grain.event-model/produces #{:example/counter-created}
   :grain.event-model/reads #{:example/counters}}
  ...)
```

This proves agreement between topology and code declarations. Allium tests prove
the observable behaviour behind those declarations.

## Allium trace links

Any block or flow may carry `:grain/allium`, a vector of references:

```clojure
{:spec "components/orders/orders.allium"
 :kind :rule
 :name "PlaceOrder"}
```

Paths are repository-relative `.allium` paths. Supported declaration kinds are
`:actor`, `:contract`, `:entity`, `:enum`, `:invariant`, `:rule`, `:surface`,
`:value`, and `:variant`.

The composition gate requires:

- every command to reference at least one Allium rule;
- every screen to reference at least one Allium surface;
- every supplied reference to resolve to a declaration of the stated kind.

Other blocks and flows may link declarations when useful, but are topology
concepts and therefore do not require behavioral counterparts.

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(tools/validate-spec-composition
  my-model
  {:project-root "."
   :event-model-opts {:strict true}})
```

This development/CI API runs `allium check` and `allium parse`. Missing tooling,
unsafe/missing paths, invalid specs, unresolved declarations, and missing required
links are fatal findings. It is not part of production startup.

## Runtime validation and boot mandate

The runtime validator reads Grain's process-wide registries and returns a total
EDN verdict without executing commands:

```clojure
(tools/validate-event-model my-model)
(tools/validate-event-model my-model {:strict true})
(tools/event-model-coverage my-model)
```

Strict mode requires full live-block coverage and declared production/read edges,
and makes schema, wiring, production, and read mismatches fatal. Registered
`defevent` definitions are reconciled with modeled events; a bounded definition
must be modeled, schema-compatible, and free of todo-processor subscribers before
boot verification succeeds. Applications may
register each service area with the `grain-event-model` package:

```clojure
(require '[ai.obney.grain.event-model.interface :refer [defeventmodel]])

(defeventmodel :example
  {:commands {...}
   :events {...}
   :read-models {...}
   :queries {...}})
```

`defeventmodel` shape-checks the area at load time and stores it in the process-wide
Event Model registry. To mandate the complete registered topology, run the strict
boot guard before Integrant startup:

```clojure
(require '[ai.obney.grain.event-model-validator.interface :as event-model-validator])

(defn start []
  (event-model-validator/verify-or-throw!)
  (ig/init system))
```

The guard throws `ex-info` carrying the verdict when fatal findings remain.
Production boot requires neither Allium source files nor the Allium CLI.

## Composed authoring workflow

Install Allium and the Grain plugin, then use `/grain`. Grain delegates behavioral
work to Allium and adds the Event Model topology phases:

1. Elicit or distill behavior into `.allium` and topology into `defeventmodel`.
2. Add command-to-rule and screen-to-surface links.
3. Generate behavioral tests from Allium and topology/composition contract tests
   from Grain.
4. Implement.
5. Run tests, Allium weed/check/analyse, strict Event Model validation, and the
   composition gate until all agree.

The individual portable extension skills are `event-model-elicit`,
`event-model-distill`, `event-model-tend`, `event-model-propagate`, and
`event-model-weed`. They add Grain-specific work to the corresponding Allium
phase rather than replacing or copying it.
