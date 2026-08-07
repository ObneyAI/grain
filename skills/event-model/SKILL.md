---
name: event-model
description: >-
  Read, write, and validate Grain's service-area-first Event Model topology. Use for defeventmodel EDN, Grain CQRS blocks and flows, live registry reconciliation, boot mandates, or :grain/allium trace links. Event Model describes topology only; route observable behavioural rules and test obligations to Allium.
---

# Event Model

An Event Model is `{area -> service-area}` EDN. Areas contain `:commands`,
`:events`, `:read-models`, `:queries`, `:todo-processors`, `:periodic-tasks`,
`:screens`, and `:flows`. Blocks use `:<area>/<name>` keys; identity is
`[kind name]` because kinds come from structural position.

Legal flow adjacency:

```text
command -> event -> read-model -> query -> screen
                                  query -> todo-processor -> command
screen -> command
read-model -> command
periodic-task -> command | event
```

Processor `:subscribes` is event trigger wiring, not a flow input. Commands and
queries `:reads` target read models. Production/read declarations at `def*` sites
use `:grain.event-model/produces` and `:grain.event-model/reads`.

## Behavioural trace

Commands must link to at least one Allium rule and screens to at least one Allium
surface for the dev/CI composition gate:

```clojure
:grain/allium
[{:spec "components/orders/orders.allium"
  :kind :rule
  :name "PlaceOrder"}]
```

Paths are safe repository-relative `.allium` paths. Optional references on other
blocks and flows must also resolve. Never add `:given-when-thens`; behavior lives
in Allium.

## Oracle

Over the project nREPL, require:

```clojure
[ai.obney.grain.event-model.interface :as em]
[ai.obney.grain.event-model-validator.interface :as emv]
[ai.obney.grain.code-agent-tools.interface :as tools]
```

Use `(em/registered-model)`, `(tools/catalog)`,
`(emv/validate-event-model model {:strict true})`,
`(tools/event-model-coverage model)`, and
`(tools/validate-spec-composition model {:project-root "." :event-model-opts {:strict true}})`.
The last call requires the Allium CLI and is dev/CI-only. Production boot uses
`emv/verify-or-throw!` and never requires Allium source or tooling.
