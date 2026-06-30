(ns ai.obney.grain.event-model.interface
  "Service-area-first event model: the design-time vocabulary and well-formedness
   schema for describing a grain application.

   An *event model* is a map of service areas, keyed by area (a simple keyword
   such as :example). A *service area* owns its building blocks — commands,
   events, read-models, queries, todo-processors, periodic-tasks and screens —
   together with the flows that wire them into the canonical CQRS data-flow shape.

   Two conventions make this model join 1:1 with a running grain system:

   - Blocks are keyed by the RUNTIME convention :<area>/<name>
     (e.g. :example/create-counter), exactly as defcommand/defquery/defreadmodel/
     defprocessor/defperiodic register their handlers. The keyword NAMESPACE is
     the area; the block's KIND comes from its structural position (which catalogue
     map it lives in), not from the keyword. A single :<area>/<name> is therefore
     NOT unique across kinds — e.g. :example/counters is both a read-model and a
     query — so identity is the pair (kind, :area/name) and flow endpoints are
     kind-qualified.

   - The kinds are 1:1 with grain's def* macros. (Renamed from the prior flat
     model: `view` -> `read-model`; added `query`; `schedule` is a map, not a
     string; keys are :<area>/<name>, not :<kind>/<name>.)

   This component defines what a valid description looks like; it does not run the
   system. Each block's runtime behaviour is governed by its own grain component.

   Because the kind is positional, the CQRS connection grammar, referential
   integrity, continuity and spec<->runtime reconciliation cannot be expressed as
   pure malli predicates over a keyword — malli here checks SHAPE only. Those
   structural rules are enforced by the code-agent-tools validators
   (`validate-event-model`) against the live catalog. See docs/event-model.md.

   All schema keys are namespaced `:event-model/*` so they are self-describing and
   never collide in grain's shared global malli registry. A model INSTANCE is plain
   data validated with `(m/validate :event-model instance)`; it is never registered."
  (:require [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [malli.core :as m]
            [malli.error :as me]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas event-model
  {;; ---- names ----------------------------------------------------------------
   ;; A block name is a qualified keyword :<area>/<name>; the namespace is the
   ;; area, the name is the block's local name. An area name is a simple keyword.
   :event-model/block-name :qualified-keyword
   :event-model/area-name  [:and :keyword [:fn #(nil? (namespace %))]]

   ;; ---- payload schema: must be schema-SHAPED data here (keyword/vector/map/
   ;; symbol). Authoritative well-formedness (does it actually parse? does it
   ;; reference an unregistered name?) is classified by the validator's C6, so a
   ;; malformed-but-shaped schema yields a precise :schema/malformed finding
   ;; rather than collapsing into a generic model-shape error.
   :event-model/malli-schema
   [:fn #(or (keyword? %) (vector? %) (map? %) (symbol? %))]

   ;; ---- design-time acceptance examples: carried as DATA, never executed ------
   :event-model/given-when-then
   [:map
    [:given :string]
    [:when :string]
    [:then :string]]
   :event-model/given-when-thens [:vector :event-model/given-when-then]

   ;; ---- schedule: grain's MAP form (defperiodic), not a string ---------------
   :event-model/schedule
   [:or
    [:map [:cron :string] [:timezone {:optional true} :string]]
    [:map [:every :int] [:duration :keyword]]]

   ;; ===========================================================================
   ;; BLOCK KINDS — 1:1 with grain's def* macros.
   ;; "Intent edges" (:reads/:produces/:consumes/:subscribes/:queries/:commands)
   ;; are OPTIONAL design-time declarations of the dependency graph; the validator
   ;; type-checks each (target exists AND is of the expected kind).
   ;; ===========================================================================

   ;; defcommand — validates business rules and emits events; carries a params
   ;; schema (registered live under :<area>/<name>). May compose read-models.
   :event-model/command
   [:map
    [:description :string]
    [:schema :event-model/malli-schema]
    [:reads {:optional true} [:set :event-model/block-name]]            ; read-models composed (e.g. for validation)
    [:produces {:optional true} [:set :event-model/block-name]]         ; events emitted
    [:given-when-thens {:optional true} :event-model/given-when-thens]]

   ;; an immutable recorded fact; carries an event-body schema (registered live).
   :event-model/event
   [:map
    [:description :string]
    [:schema :event-model/malli-schema]]

   ;; defreadmodel — a pure (state,event)->state projection; subscribes to events
   ;; via :consumes (cross-checked against the live :events set). Carries no own
   ;; schema by convention (its key collides with the same-named query's slot).
   :event-model/read-model
   [:map
    [:description :string]
    [:consumes [:set :event-model/block-name]]                          ; events consumed
    [:schema {:optional true} :event-model/malli-schema]                ; optional, design-time only
    [:version {:optional true} :int]]

   ;; defquery — reads the projected state; carries a params schema (registered
   ;; live under :<area>/<name>). Composes read-models via :reads.
   :event-model/query
   [:map
    [:description :string]
    [:schema :event-model/malli-schema]
    [:reads {:optional true} [:set :event-model/block-name]]]           ; read-models read

   ;; defprocessor — an async reactor. At runtime it SUBSCRIBES to event :topics
   ;; (the trigger, cross-checked against the live :topics set); the modeled INPUT
   ;; edge, however, is the read-model/query it works from (the "TODO list") — see
   ;; the connection grammar. It issues commands.
   :event-model/todo-processor
   [:map
    [:description :string]
    [:subscribes [:set :event-model/block-name]]                        ; event topics subscribed (runtime trigger)
    [:reads {:optional true} [:set :event-model/block-name]]            ; queries it works from (the TODO list — its modeled input)
    [:produces {:optional true} [:set :event-model/block-name]]]        ; commands issued

   ;; defperiodic — a scheduled reactor; the handler returns :result/events, so
   ;; it produces EVENTS (and/or commands) on a schedule.
   :event-model/periodic-task
   [:map
    [:description :string]
    [:schedule :event-model/schedule]
    [:produces {:optional true} [:set :event-model/block-name]]]        ; events/commands emitted

   ;; a user-facing surface. Design-time only — grain has no defscreen, so a
   ;; screen's existence is not runtime-verifiable, but its declared dependencies
   ;; ARE (their targets are real queries/commands). A screen is commonly 1:1 with
   ;; a single query, but :queries is a set so 0..n is allowed.
   :event-model/screen
   [:map
    [:description :string]
    [:queries {:optional true} [:set :event-model/block-name]]          ; queries depended on
    [:commands {:optional true} [:set :event-model/block-name]]]        ; commands issued on user action

   ;; ---- flows: endpoints are KIND-QUALIFIED [kind name] or nil (entry/terminus)
   :event-model/kind
   [:enum :command :event :read-model :query :todo-processor :periodic-task :screen]

   :event-model/endpoint
   [:maybe [:tuple :event-model/kind :event-model/block-name]]

   :event-model/step
   [:map
    [:from :event-model/endpoint]
    [:to :event-model/endpoint]]

   :event-model/flow
   [:map
    [:description :string]
    [:steps [:vector :event-model/step]]]

   ;; ===========================================================================
   ;; SERVICE AREA — the central construct. Owns its blocks and flows.
   ;; ===========================================================================
   :event-model/service-area
   [:map
    [:description {:optional true} :string]
    [:commands {:optional true} [:map-of :event-model/block-name :event-model/command]]
    [:events {:optional true} [:map-of :event-model/block-name :event-model/event]]
    [:read-models {:optional true} [:map-of :event-model/block-name :event-model/read-model]]
    [:queries {:optional true} [:map-of :event-model/block-name :event-model/query]]
    [:todo-processors {:optional true} [:map-of :event-model/block-name :event-model/todo-processor]]
    [:periodic-tasks {:optional true} [:map-of :event-model/block-name :event-model/periodic-task]]
    [:screens {:optional true} [:map-of :event-model/block-name :event-model/screen]]
    [:flows {:optional true} [:map-of :qualified-keyword :event-model/flow]]]

   ;; ---- ROOT: a model is a map of service areas, keyed by area ----------------
   :event-model
   [:map-of :event-model/area-name :event-model/service-area]})

;; ===========================================================================
;; Model registration
;; ===========================================================================
;;
;; A service registers its area's event model with `defeventmodel`, exactly as
;; handlers self-register via the def* macros and schemas via defschemas. The
;; registered models are what the boot-guard (event-model-validator) reconciles
;; against the live runtime. Registration shape-validates the area against the
;; :event-model meta-schema and throws on a malformed model at load.

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def event-model-registry*
  "Global registry of registered area models: {area -> service-area-map}. The map
   itself is a well-formed :event-model value (a map of areas)."
  (atom {}))

(defn register-event-model!
  "Shape-validates `area-map` (as the single-area model `{area area-map}`) against
   the :event-model meta-schema and registers it under `area`. Throws ex-info with
   humanized errors when the area model is malformed."
  [area area-map]
  (let [model {area area-map}]
    (when-let [err (m/explain :event-model model)]
      (throw (ex-info (str "Invalid event model for area " area)
                      {:area area :explain/humanized (me/humanize err)})))
    (swap! event-model-registry* assoc area area-map)
    area))

(defmacro defeventmodel
  "Registers a service area's event model. `area` is a simple keyword (e.g.
   :example); `area-map` is its service-area map (commands/events/read-models/
   queries/todo-processors/periodic-tasks/screens/flows). Shape-validated at load."
  [area area-map]
  `(register-event-model! ~area ~area-map))

(defn registered-model
  "The merged event model across all registered areas — a well-formed :event-model
   value the validator/boot-guard can check against the live runtime."
  []
  @event-model-registry*)
