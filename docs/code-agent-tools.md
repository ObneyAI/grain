# Code Agent Tools

Inspect a Grain application from a development REPL with
`ai.obney.grain.code-agent-tools.interface`. Load the application’s declaration
namespaces first. Catalog, schema and Event Model checks read those registries
directly. [Package coordinates](packages.md#grain-code-agent-tools)

## Catalog

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(def cat (tools/catalog))
(get-in cat [:commands :example/create-counter])
(:missing-schemas cat)
```

The catalog groups commands, queries, read models, processors, periodic triggers
and named schemas. Entries include options, source locations when available,
and event schemas for subscribed types. `:missing-schemas` groups names by kind.

Use `(tools/guides)` for an index of concepts, tools and registered blocks.
`(tools/guide :example/create-counter {:spec model})` includes the matching
block’s description, declared dependencies and Allium references.

## Validate a payload

```clojure
(tools/explain-schema :example/create-counter)
(tools/validate :example/create-counter {:name "Counter A"})
;; :valid? true

(tools/validate :command :example/create-counter
                {:command/name :example/create-counter
                 :command/id (random-uuid)
                 :command/timestamp (java.time.OffsetDateTime/now)
                 :name "Counter A"})
```

The two-argument form checks the named schema. The three-argument form also
checks the `:command`, `:query` or persisted `:event` envelope. On failure,
inspect `:explain/humanized` or `:error/message`. `(tools/schemas)` lists
registered schemas, including the payload schema for each `defevent`.
Keyed state and result schemas live on `defreadmodel`.

## Inspect a running application

After starting the development system, install its system map and Grain context:

```clojure
(tools/install! {:system system :context context :mode :dev})
(tools/runtime)
(tools/diagnostics {:tenant-id tenant-id})
```

Diagnostics include registry counts, store and cache information, and lease
routing when a control plane is present. `:staleness-threshold-ms` controls
the active-node check.

```clojure
(tools/invoke-command! {:command/name :example/create-counter :name "Counter A"})
(tools/invoke-query {:query/name :example/counters})
(tools/events {:types #{:example/counter-created} :limit 10})
```

Invocations fill missing IDs and timestamps. These are direct processor calls.
They bypass adapter authorization, and a successful command can append events.
Pass `:tenant-id` explicitly or supply it in the installed context. Event reads
also accept `:tags`. Set `:limit` to bound the returned vector.

Use the application’s tenant context for keyed reads at the REPL:

```clojure
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp])

(rmp/page context :example/counter-list {:index :name :limit 25})
```

`tools/projection` supports existing single-state models. See the [keyed
counter declaration](core-concepts.md#read-models) and [read API](../components/read-model-processor-v2/README.md#reading).

## Validate an Event Model

```clojure
(require '[ai.obney.grain.event-model.interface :as em]
         '[ai.obney.grain.event-model-validator.interface :as emv])

(def model (em/registered-model))
(tools/event-model-coverage model)
(tools/validate-event-model model emv/strict-defaults)
(tools/validate-spec-composition model {:project-root "."
                                        :event-model-opts emv/strict-defaults})
```

Inspect `:valid?`, `:summary` and `:findings` in the validation result.
Use `validate-event-model-file` for an EDN file or `validate-event-model-var`
for a fully qualified var holding the model. The composition check also runs
the Allium CLI to resolve command-to-rule and screen-to-surface links.
[Event Model validation and boot](event-model.md)
