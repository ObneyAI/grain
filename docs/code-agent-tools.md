# Code Agent Tools

`grain-code-agent-tools` is a dev-only package that exposes a live Grain
application to a coding agent over nREPL. It turns Grain's registries, schema
registry, event store, read models, and processors into plain EDN inspection and
execution tools.

The package is intended for local development and debugging. It is not a
production API.

It also exposes Grain's Event Model validation APIs. Runtime validation compares
service topology with the live registries; composition validation additionally
resolves `:grain/allium` links during development and CI.

## Setup

Add the package to the application project:

```clojure
obneyai/grain-code-agent-tools
{:git/url "https://github.com/ObneyAI/grain.git"
 :git/sha "382e7adc66ba347fd88e20b66cf02e737370c395"
 :deps/root "projects/grain-code-agent-tools"}
```

Require and install the tools after the Grain system has started:

```clojure
(ns my-app.core
  (:require [ai.obney.grain.code-agent-tools.interface :as code-agent-tools]
            [integrant.core :as ig]))

(defn start
  []
  (let [app (ig/init system)]
    (code-agent-tools/install! {:system app
                                :context (::context app)
                                :mode :dev})
    app))
```

`install!` stores the runtime that all other tool calls use. The runtime should
include:

- `:system` - the Integrant system map.
- `:context` - the Grain request context, usually containing `:event-store`,
  `:cache`, `:tenant-id`, and application services.
- `:mode :dev` - required when `:mode` is supplied. Any other mode throws.

From nREPL:

```clojure
(require '[ai.obney.grain.code-agent-tools.interface :as tools])

(tools/runtime)
;; => {:mode :dev
;;     :installed-at #inst "..."
;;     :system/keys #{...}
;;     :context/keys #{...}}
```

## Public API

### `catalog`

Returns a sanitized catalog of live Grain registries:

```clojure
(tools/catalog)
;; => {:commands {...}
;;     :queries {...}
;;     :read-models {...}
;;     :processors {...}
;;     :periodic-triggers {...}
;;     :schemas {...}
;;     :missing-schemas {...}}
```

Each command, query, read model, processor, and periodic trigger includes its
registered options, schema presence, source metadata where available,
authorization presence, and consumed event schemas where applicable.

Use this first when entering an unfamiliar app:

```clojure
(keys (:commands (tools/catalog)))
(get-in (tools/catalog) [:commands :example/create-counter])
```

### `schemas`, `explain-schema`, and `validate`

These functions expose Grain's schema registry. They are the main safety layer
for an agent before invoking commands or queries.

```clojure
(tools/schemas)
;; => {:example/create-counter [:map ...]
;;     :example/counter-created [:map ...]
;;     ...}

(tools/explain-schema :example/create-counter)
;; => {:schema/name :example/create-counter
;;     :schema [:map ...]}

(tools/validate :example/create-counter {:name "Counter A"})
;; => {:valid? true, :schema [:map ...]}

(tools/validate :example/create-counter {:name 1})
;; => {:valid? false
;;     :schema [:map ...]
;;     :value {:name 1}
;;     :explain/data {...}
;;     :explain/humanized {...}}
```

`validate` also supports Grain envelope validation. Use this when checking a
fully materialized command, query, or event map, including Grain metadata such
as ids and timestamps:

```clojure
(tools/validate :command
                :example/create-counter
                {:command/name :example/create-counter
                 :command/id #uuid "00000000-0000-0000-0000-000000000010"
                 :command/timestamp (java.time.OffsetDateTime/now)
                 :name "Counter A"})

(tools/validate :query
                :example/counters
                {:query/name :example/counters
                 :query/id #uuid "00000000-0000-0000-0000-000000000011"
                 :query/timestamp (java.time.OffsetDateTime/now)})

(tools/validate :event
                :example/counter-created
                {:event/type :example/counter-created
                 :event/id #uuid "01900000-0000-7000-8000-000000000012"
                 :event/timestamp (java.time.OffsetDateTime/now)
                 :event/tags #{}
                 :counter-id #uuid "00000000-0000-0000-0000-000000000013"
                 :name "Counter A"})
```

An agent should prefer this workflow:

1. Use `catalog` to discover the command/query/event name.
2. Use `explain-schema` to inspect the expected shape.
3. Use `validate` on the proposed payload.
4. Invoke only after validation succeeds.

### `invoke-command!`

Processes a command through the live command processor:

```clojure
(tools/invoke-command! {:command/name :example/create-counter
                        :name "Counter A"})
```

The tool adds `:command/id` and `:command/timestamp` when absent. It uses
`:tenant-id` from the command map or from the installed context.

This call can mutate application state by appending events.

### `invoke-query`

Processes a query through the live query processor:

```clojure
(tools/invoke-query {:query/name :example/counters})
;; => {:query/result [...] ...}
```

The tool adds `:query/id` and `:query/timestamp` when absent. It uses
`:tenant-id` from the query map or from the installed context.

### `events`

Reads events from the installed event store:

```clojure
(tools/events {:types #{:example/counter-created}
               :limit 10})
```

Accepted keys:

- `:tenant-id` - required when the installed context does not include one.
- `:types` - event type set.
- `:tags` - event tag set.
- `:limit` - max number of events returned.

### `projection`

Projects a registered read model:

```clojure
(tools/projection :example/counters)
```

Pass a scope map when the read model expects one or when tenant id must be
supplied explicitly:

```clojure
(tools/projection :example/counters {:tenant-id tenant-id})
```

### `diagnostics`

Returns runtime health and discovery information:

```clojure
(tools/diagnostics)
;; => {:runtime {...}
;;     :registries {:commands 3, :queries 2, ...}
;;     :event-store {:present? true, :tenants {...}}
;;     :cache {:present? true, :l1 {...}}
;;     :control-plane {...}}
```

For apps using the control plane, pass a tenant id to include routing
diagnostics:

```clojure
(tools/diagnostics {:tenant-id tenant-id
                    :staleness-threshold-ms 6000})
```

### Event Model validation

Validate a service-area Event Model against its own structure and the live Grain
registries without installing the tool runtime:

```clojure
(tools/validate-event-model model)
(tools/validate-event-model model {:strict true})
(tools/validate-event-model-file "components/example-service/example.event-model.edn")
(tools/validate-event-model-var 'my-app.event-model/model)
(tools/event-model-coverage model)
```

The validator returns `{:valid? ... :summary ... :findings ...}` and does not
invoke commands. Strict mode requires complete live-block coverage plus declared
production and read edges. See [Event Model](event-model.md) for the model shape,
edge grammar, and production boot mandate.

To verify the topology-to-behaviour boundary as well, run the development/CI
composition gate from the repository root:

```clojure
(tools/validate-spec-composition
  model
  {:project-root "."
   :allium-bin "allium"
   :event-model-opts {:strict true}})
```

This first validates the Event Model, then runs `allium check` and `allium parse`
for its referenced specifications. Every command must link to an Allium rule,
every screen must link to an Allium surface, and every supplied reference must
resolve. Missing tooling, unsafe or missing paths, invalid specifications, and
unresolved declarations are returned as error findings. The composition gate is
never used by production boot.

### `guides` and `guide`

The live tools also serve concise workflow and block-specific instructions:

```clojure
(tools/guides)
(tools/guide :getting-started)
(tools/guide :event-model)
(tools/guide :example/create-counter {:spec model})
```

When an Event Model block is supplied, its usage card includes `:spec/allium`
trace references alongside topology edges and live source/schema information.

## Agent Workflow

A useful first pass in a live app is:

```clojure
(def cat (tools/catalog))

(keys (:commands cat))
(keys (:queries cat))
(keys (:read-models cat))
(:missing-schemas cat)
(tools/diagnostics)
```

Then inspect one workflow:

```clojure
(get-in cat [:commands :example/create-counter])
(tools/explain-schema :example/create-counter)
(tools/validate :example/create-counter {:name "Counter A"})
(tools/invoke-command! {:command/name :example/create-counter
                        :name "Counter A"})
(tools/events {:types #{:example/counter-created} :limit 5})
(tools/projection :example/counters)
```

Because the tools expose source metadata for registered handlers and reducers,
an agent can move from runtime discovery to the exact source file/line that owns
the behavior.

## Safety Notes

- `install!` is dev-only. Passing any mode other than `:dev` throws.
- `catalog`, `schemas`, `explain-schema`, `validate`, `events`, `projection`,
  `runtime`, `diagnostics`, and the Event Model validators are inspection-oriented.
- `validate-spec-composition` executes the local Allium CLI and reads only safe,
  repository-relative `.allium` paths beneath `:project-root`.
- `invoke-command!` is state-changing and should be treated like any other
  command execution path.
- Commands, queries, event reads, and projections require a tenant id either in
  the installed context or in the call arguments.
- Returned data is sanitized for nREPL: vars, functions, classes, and nested
  values are represented as EDN-friendly data.
