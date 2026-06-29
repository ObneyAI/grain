(ns ai.obney.grain.code-agent-tools.guides
  "Authored concept/workflow guides for the code-agent-tools guide layer.

   Requiring this namespace registers the guides into `core/guide-registry*`;
   `core/guides` and `core/guide` then serve them. Block- and tool-level guides
   are assembled live from the runtime and need no authored content here.

   These are the part an agent cannot reconstruct from docstrings alone: the
   end-to-end workflow, the event-model spec format, the flow grammar, and how to
   resolve each validation finding."
  (:require [ai.obney.grain.code-agent-tools.core :as core]))

(core/register-guide!
 {:id :getting-started
  :title "Getting started — validate your work against the live grain system"
  :summary "Connect over nREPL, introspect the live system, author/validate an event model, fix findings."
  :applies-to :concept
  :body
  "The grain code-agent-tools expose a running grain app to a coding agent over
nREPL. Everything returns plain EDN. The authoritative loop:

1. `(catalog)` — see every live building block (commands, queries, read-models,
   processors, periodic-triggers) and registered schema. Each block entry carries
   an `:instructions {:guide <name>}` pointer.
2. `(guide <id>)` — fetch authoritative usage for any concept, live block, or tool.
   Start with `(guides)` for the index.
3. `(validate-event-model <model>)` — structurally check a service-area-first
   event model (EDN) against the live registries. Returns a total verdict
   `{:valid? :findings :summary}`; never throws.
4. Fix findings until `:valid?` is true. See `(guide :findings)`.

`validate-event-model` needs no `install!` (it reads process-wide registries).
`invoke-command!`/`invoke-query`/`events`/`projection` DO need `(install! ...)`
with a running Integrant system + context; those execute against real state."})

(core/register-guide!
 {:id :event-model
  :title "The service-area-first event model (spec format)"
  :summary "How to write an EDN event model that joins 1:1 with the live runtime."
  :applies-to :concept
  :body
  "An event model is EDN: a map of service AREAS, keyed by a simple keyword
(`:example`). Each area OWNS its building blocks and flows:

  {:example
   {:description \"...\"
    :commands        {:example/create-counter {:description \"...\" :schema [:map [:name :string]]
                                               :produces #{:example/counter-created}}}
    :events          {:example/counter-created {:description \"...\" :schema [:map [:counter-id :uuid]]}}
    :read-models     {:example/counters {:description \"...\" :consumes #{:example/counter-created}}}
    :queries         {:example/counters {:description \"...\" :schema [:map] :reads #{:example/counters}}}
    :todo-processors {:example/avg {:description \"...\" :subscribes #{:example/counter-created}}}
    :periodic-tasks  {:example/tick {:description \"...\" :schedule {:every 30 :duration :seconds}}}
    :screens         {:example/dash {:description \"...\" :queries #{:example/counters}
                                     :commands #{:example/create-counter}}}
    :flows           {:example/lifecycle {:description \"...\" :steps [...]}}}}

Keying & identity:
- Blocks are keyed by the RUNTIME convention :<area>/<name>. The keyword
  NAMESPACE is the area; the KIND comes from structural position (which map it is
  in). So a single :<area>/<name> is NOT unique across kinds — e.g.
  :example/counters is both a read-model and a query. Identity is (kind, name).

Kinds are 1:1 with grain's def* macros: command, event, read-model (NOT 'view'),
query (read side of CQRS), todo-processor, periodic-task, screen.

Dependency graph as kind-typed intent edges (optional, design-time): command
`:reads` read-models + `:produces` events; query `:reads` read-models; read-model
`:consumes` events; todo-processor `:subscribes` events + `:produces` commands;
periodic-task `:produces` events/commands; screen `:queries` queries + `:commands`
commands. The validator checks each target exists AND is the expected kind.

`:schedule` is a MAP: {:every N :duration :seconds} or {:cron \"...\"}.
Given/When/Then go on commands as DATA ({:given :when :then}); they are not
executed. See `(guide :flows)` and `(guide :findings)`."})

(core/register-guide!
 {:id :flows
  :title "Flows and the CQRS connection grammar"
  :summary "How to wire blocks into flows and which connections are legal."
  :applies-to :concept
  :body
  "A flow is a named, ordered chain of steps under an area's :flows
(keyed :<area>/<flow>). Each step is {:from <endpoint> :to <endpoint>}. An
endpoint is KIND-QUALIFIED — [kind :<area>/<name>] — or nil for an entry
point / terminus marker:

  {:example/lifecycle
   {:description \"...\"
    :steps [{:from nil :to [:command :example/increment-counter]}
            {:from [:command :example/increment-counter] :to [:event :example/counter-incremented]}
            {:from [:event :example/counter-incremented]  :to [:read-model :example/counters]}]}}

Legal CQRS adjacency (validated):
  command        -> event
  event          -> read-model | todo-processor
  read-model     -> query | command | screen
  query          -> screen
  screen         -> command
  todo-processor -> command
  periodic-task  -> command | event

Only event->read-model and event->todo-processor are confirmable against live
wiring (read-model :consumes / processor :topics). Production edges
(command->event etc.) are checked for endpoint existence + grammar only — the
runtime cannot confirm a producer actually produces. A flow should be a connected
chain: a step's :from must be a prior step's :to (or nil to mark an entry)."})

(core/register-guide!
 {:id :findings
  :title "Validation findings — what each means and how to resolve it"
  :summary "Field guide to validate-event-model output."
  :applies-to :concept
  :body
  "validate-event-model returns {:valid? :summary :findings}. :valid? is true when
there are no :error-severity findings. Each finding has :type, :severity and a
:message. Types:

  :model/malformed       (error)   spec does not conform to the :event-model schema.
                                   See :explain/humanized. Fix the shape first.
  :block/misnamespaced   (error)   a block key's namespace != its area. Re-key it.
  :block/undeclared      (error)   spec block has no live runtime counterpart.
                                   Implement the def*, or remove it from the spec.
  :block/uncovered       (warning) live block missing from the spec. Document it
                                   (legitimate if intentionally not yet specified).
  :schema/malformed      (error)   a block :schema does not parse. Fix the schema.
  :schema/unresolved-ref (warning) a :schema references an unregistered name.
  :schema/unregistered   (warning) a live command/query/consumed-event lacks a schema.
  :schema/mismatch       (error)   spec :schema differs from the live registered
                                   schema. See :spec/schema vs :live/schema.
  :flow/illegal-connection (error) a flow step violates the CQRS grammar.
  :flow/dangling-reference (error) a flow endpoint names a non-existent block.
  :ref/dangling          (error)   an intent edge names a non-existent block.
  :ref/wrong-kind        (error)   an intent edge names a block of the wrong kind.
  :wiring/mismatch       (warning) spec :consumes/:subscribes/:schedule != live wiring.
  :produces/mismatch     (warning) spec :produces != the runtime-declared production edge
                                   (fires only when the def site declares :grain.event-model/produces).
  :reads/mismatch        (warning) spec :reads != the runtime-declared read-model dependency
                                   (fires only when the def site declares :grain.event-model/reads).
  :flow/discontinuous    (warning) a step's :from has no producing prior step.
  :auth/missing          (warning) a live command/query has no :authorized? predicate.
  :runtime/design-only / :runtime/absent (info) notices, not failures."})

(core/register-guide!
 {:id :authoring-blocks
  :title "Authoring blocks — what each kind records"
  :summary "Per-kind reference for the def* macros and the spec fields."
  :applies-to :concept
  :body
  "command  (defcommand)  — validates rules, emits events. Spec: :description,
            :schema (params), :reads (read-models), :produces (events),
            :given-when-thens. Live: command registry + param schema + :authorized?.
  event    (->event)      — immutable fact. Spec: :description, :schema (body).
            Live: schema registry (no handler registry for events).
  read-model (defreadmodel) — pure (state,event)->state projection. Spec:
            :description, :consumes (events), optional :schema/:version. Live:
            read-model registry + :events.
  query    (defquery)     — reads projections. Spec: :description, :schema (params),
            :reads (read-models). Live: query registry + param schema + :authorized?.
  todo-processor (defprocessor) — async reactor. Spec: :description, :subscribes
            (event topics), :produces (commands). Live: processor registry + :topics.
  periodic-task (defperiodic) — scheduled reactor. Spec: :description, :schedule
            (map), :produces. Live: periodic-trigger registry + :schedule.
  screen   (design-only — no grain macro) — Spec: :description, :queries, :commands.
            No runtime counterpart; only its dependency targets are checkable.

  CONFIRMING PRODUCTION/READ EDGES (opt-in): production edges (command->event,
  todo-processor->command, periodic->event) and read edges (command/query->
  read-model) live inside handler bodies and are NOT recorded at registration, so
  by default the validator only type-checks their endpoints. To have them
  CONFIRMED, annotate the def site with the same edges (matching grain's
  :grain.control/* keyword convention):

    (defcommand :example create-counter
      {:authorized? (constantly true)
       :grain.event-model/produces #{:example/counter-created}
       :grain.event-model/reads    #{:example/counters}}
      ...)

  These optional opts pass through to the catalog (no macro/runtime change);
  validate-event-model then compares them to the spec and reports
  :produces/mismatch / :reads/mismatch on drift. This confirms spec<->code
  agreement; proving the handler actually emits those events still requires
  executable Given/When/Then (a planned follow-on)."})
