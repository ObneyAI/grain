(ns ai.obney.grain.code-agent-tools.interface
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.code-agent-tools.core :as core]))

(defn install!
  "Installs the live Grain runtime that subsequent tool calls use.

  `runtime` is a map with at least `:mode :dev`; pass `:system` with the
  Integrant system map and `:context` with the Grain request context. Returns a
  small install summary. Throws when `:mode` is present and not `:dev`."
  [runtime]
  (core/install! runtime))

(defn runtime
  "Returns a sanitized summary of the installed runtime.

  Includes install mode/time plus the top-level keys available in `:system` and
  `:context`. Does not expose the raw runtime objects."
  []
  (core/runtime))

(defn catalog
  "Returns a sanitized EDN catalog of the live Grain registries.

  Includes commands, queries, read models, todo processors, periodic triggers,
  registered schemas, source metadata where available, authorization presence,
  consumed events, event schema summaries, and missing schema diagnostics."
  []
  (core/catalog))

(defn schemas
  "Returns all schemas currently registered in Grain's schema registry.

  Values are sanitized for nREPL consumption, so vars/functions/classes are
  represented as data instead of raw runtime objects."
  []
  (core/schemas))

(defn explain-schema
  "Returns the registered schema definition, optionally with validation details.

  With one argument, dereferences and sanitizes the named Malli schema. With
  `value`, also validates the value and includes Malli explain data and
  humanized errors when invalid."
  ([schema]
   (core/explain-schema schema))
  ([schema value]
   (core/explain-schema schema value)))

(defn validate
  "Validates `value` against a schema and returns an EDN result.

  `(validate schema value)` validates directly against `schema`.
  `(validate kind schema value)` wraps the schema in Grain envelope schemas for
  `:command`, `:query`, or `:event`. Returns `{:valid? true ...}` or
  `{:valid? false ...}` with sanitized explain/error details."
  ([schema value]
   (core/validate schema value))
  ([kind schema value]
   (core/validate kind schema value)))

(defn invoke-command!
  "Processes a command through the installed Grain command processor.

  `command` is a command map, usually including `:command/name`. The tool adds
  `:command/id` and `:command/timestamp` when absent, and uses `:tenant-id` from
  the command or installed context. This can mutate application state by
  appending events."
  [command]
  (core/invoke-command! command))

(defn invoke-query
  "Processes a query through the installed Grain query processor.

  `query` is a query map, usually including `:query/name`. The tool adds
  `:query/id` and `:query/timestamp` when absent, and uses `:tenant-id` from
  the query or installed context."
  [query]
  (core/invoke-query query))

(defn events
  "Reads tenant-scoped events from the installed event store.

  `args` may include `:tenant-id`, `:types`, `:tags`, and `:limit`. Tenant id is
  taken from `args` or the installed context. Returns a vector of events."
  [args]
  (core/events args))

(defn projection
  "Projects a registered read model against the installed event store/cache.

  With one argument, projects `read-model-name` using the installed context.
  With `scope`, passes scope through to the read-model processor; scope may
  include `:tenant-id` when the installed context does not provide one."
  ([read-model-name]
   (core/projection read-model-name))
  ([read-model-name scope]
   (core/projection read-model-name scope)))

(defn diagnostics
  "Returns runtime diagnostics for the installed app.

  Includes runtime summary, registry counts, event-store/cache presence, tenant
  info when available, L1 cache stats, and control-plane diagnostics when the
  installed Integrant system contains a control plane. `args` may include
  `:tenant-id` for tenant routing diagnostics and `:staleness-threshold-ms` for
  active-node checks."
  ([] (core/diagnostics))
  ([args] (core/diagnostics args)))
