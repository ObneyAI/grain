---
name: event-model
description: Author and validate Grain service topology, CQRS flows, registry declarations and links to Allium.
---

# Event Model

Register each service area with `(em/defeventmodel :area area-map)`. Registration
replaces that area's entry. Blocks live under `:commands`, `:events`, `:read-models`,
`:queries`, `:todo-processors`, `:periodic-tasks` and `:screens`. In `:flows`,
each step has `:from` and `:to` endpoints identified by `[kind :area/name]`;
`nil` marks a boundary.

`emv/connection-grammar` lists legal flow steps. `emv/intent-edges` lists
dependency targets. A processor's `:subscribes` names trigger events and its
`:reads` names queries. Match topology to the runtime declarations
`:grain.event-model/reads` and `:grain.event-model/produces` where applicable.
Match every event block’s `:schema` to its `defevent` payload schema and every
keyed read model’s topology `:schema` to its per-instance `:state-schema`.

Commands must link to an Allium rule and screens to a surface. Every supplied
reference must resolve by kind and name in a repository-relative spec:

```clojure
:grain/allium [{:spec "components/example-service/example-service.allium"
                :kind :rule :name "CreateCounter"}]
```

Load the application’s models, schemas and handlers before checking live coverage.

```clojure
(require '[ai.obney.grain.event-model.interface :as em]
         '[ai.obney.grain.event-model-validator.interface :as emv])

(emv/verify-event-model!) ;; strict verdict with :valid? and :findings
(emv/event-model-coverage (em/registered-model))
```

Check draft EDN with `(emv/validate-event-model model)`.
At boot, `(emv/verify-or-throw!)` raises on failure. Pass `{:event-store store}`
to also check active retention policies. In development or CI, run the link check
from the project root with the Allium CLI on PATH:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(tools/validate-spec-composition (em/registered-model)
                                 {:project-root "." :event-model-opts emv/strict-defaults})
```
