(ns ai.obney.grain.event-model-validator.interface
  "Shippable structural validator + boot-guard for the service-area-first event
   model. Pure: reads only the live building-block registries, needs no `install!`,
   and (except `verify-or-throw!`) never throws. Safe to run in production at boot.

   `code-agent-tools` re-exports the introspection/validation fns here for the
   dev/agent loop; apps use `verify-or-throw!` to MANDATE the model at startup."
  (:require [ai.obney.grain.event-model-validator.core :as core]))

(defn sanitize
  "Recursively convert a value to inert EDN (vars/fns/classes → descriptor maps)."
  [x] (core/sanitize x))

(defn source-info
  "Source metadata (var/ns/file/line/doc/arglists) for a var, or nil." [x]
  (core/source-info x))

(defn catalog
  "Sanitized EDN view of the live grain registries (commands/queries/read-models/
   processors/periodic-triggers/schemas + missing-schema diagnostics)."
  [] (core/catalog))

(defn catalog-block
  "Find a live block named `id` across the catalog's kind-maps." [cat id]
  (core/catalog-block cat id))

(defn schemas [] (core/schemas))
(defn schema-registry [] (core/schema-registry))

(defn spec-block
  "Find a block named `id` in an event `model` across kinds → the block + its :kind."
  [model id] (core/spec-block model id))

(defn validate-event-model
  "Structurally validate an EDN event model against the live runtime; returns a
   total verdict `{:valid? :summary :findings}`. Never throws. `opts`: `:strict`
   (adds completeness checks: coverage/produces-reads-required/gwt), `:fatal-types`
   (finding types that fail `:valid?` regardless of severity), `:error-severities`,
   `:schema-match :lenient`."
  ([model] (core/validate-event-model model))
  ([model opts] (core/validate-event-model model opts)))

(defn validate-event-model-file
  ([path] (core/validate-event-model-file path))
  ([path opts] (core/validate-event-model-file path opts)))

(defn validate-event-model-var
  ([sym] (core/validate-event-model-var sym))
  ([sym opts] (core/validate-event-model-var sym opts)))

(defn event-model-coverage
  "Bidirectional spec↔live coverage diff per kind." [model]
  (core/event-model-coverage model))

(def strict-defaults
  "The strictest-tier opts used by the boot-guard." core/strict-defaults)

(defn verify-event-model!
  "Validate the registered event model (or `(:model opts)`) against the live runtime
   in strict mode; returns the verdict, does not throw."
  ([] (core/verify-event-model!))
  ([opts] (core/verify-event-model! opts)))

(defn verify-or-throw!
  "Boot-guard: throw ex-info (with the verdict) when the registered/`(:model opts)`
   model is invalid or incomplete — so a system refuses to start. Returns the
   verdict on success."
  ([] (core/verify-or-throw!))
  ([opts] (core/verify-or-throw! opts)))
