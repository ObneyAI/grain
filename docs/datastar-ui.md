# Grain Datastar UI DSL

`ai.obney.grain.datastar.ui` is the checked UI layer for writing Datastar
attributes from ordinary Hiccup. In application code it should usually be
required as `ui`:

```clojure
(require '[ai.obney.grain.datastar.ui :as ui])
```

The DSL has three jobs:

- Build Datastar attributes from data, not handwritten strings.
- Keep command/query payloads explicit so ambient page state is not sent by
  accident.
- Own Datastar signal scope generation so application developers do not need
  to name, thread, or isolate component-local signals by hand.

The normal production path is:

```clojure
(ui/hiccup view)
;; Hiccup + UI forms -> IR -> Hiccup with Datastar attributes
```

The server still renders the final Hiccup to HTML with the application's normal
HTML renderer. The UI DSL owns construction of Datastar attributes, not the full
HTML rendering pipeline.

## Quick Start

Commands and queries are addressed by keywords, not literal routes:

```clojure
(ns app.students.ui
  (:require [ai.obney.grain.datastar.ui :as ui]))

(defn search-box []
  (ui/with-signals [query {:init ""}
                    selected-id {:init nil}]
    [:div.search
     [:input {:bind/value query
              :on/input {:effect (ui/refresh :students/typeahead-page
                                             {:q query})
                         :modifiers {:debounce "250ms"}}}]
     [:button {:on/click {:effect (ui/dispatch :students/archive
                                               {:student-id selected-id})}}
      "Archive"]]))
```

Lower it before rendering:

```clojure
(ui/hiccup (search-box))
```

The checked output contains generated Datastar signal names and Datastar
actions. The developer-facing source keeps the local signal handles (`query`,
`selected-id`) and route refs (`:students/typeahead-page`,
`:students/archive`).

## Checked UI Rules

- Require the namespace as `ui`.
- Use `ui/hiccup` at the render boundary.
- Use `ui/with-signals` for component-local state.
- Do not manually scope signal names in normal application code.
- Do not hard-code command or query routes in checked effects.
- Use verbose event maps: `{:effect ...}` plus optional `:modifiers`.
- Send explicit command/query payloads. There is no ambient signal payload mode.
- Use raw Datastar attributes only as an escape hatch.

These rules are intentionally narrow. A code agent should be able to generate
UI without learning Datastar's string action grammar or Grain's route layout.

## End-to-End Example

Queries that should be reachable by Datastar UI declare `:datastar/path`.
Commands are registered by `defcommand`; `ui/dispatch` sends the command name
and explicit payload to Grain's shared Datastar action endpoint.

```clojure
(require '[ai.obney.grain.command-processor-v2.interface :refer [defcommand]]
         '[ai.obney.grain.query-processor.interface :refer [defquery]])

(defquery :students typeahead-page
  {:authorized? (constantly true)
   :datastar/path "/students/typeahead"}
  [context]
  ...)

(defquery :students profile
  {:authorized? (constantly true)
   :datastar/path "/students/:student-id"}
  [context]
  ...)

(defcommand :students archive-student
  {:authorized? (constantly true)}
  [context]
  ...)
```

The UI refers to those queries and commands by registry keyword:

```clojure
(defn student-search []
  (ui/with-signals [query {:init ""}
                    selected-id {:init nil}]
    [:section
     [:input {:bind/value query
              :placeholder "Search students"
              :on/input {:effect (ui/refresh :students/typeahead-page
                                             {:q query})
                         :modifiers {:debounce "300ms"}}}]

     [:a {:href (ui/href :students/profile
                        {:path-params {:student-id selected-id}})}
      "Open profile"]

     [:button {:bind/attr {:disabled (ui/js "!" selected-id)}
               :on/click {:effect (ui/dispatch :students/archive-student
                                               {:id selected-id})}}
      "Archive"]]))
```

At the render boundary:

```clojure
(ui/hiccup (student-search))
```

For tests or non-default registries, pass an explicit registry:

```clojure
(ui/hiccup (student-search) {:query-registry registry})
```

## Pipeline

The UI DSL lowers in two phases:

1. `ui/lower-ir` converts checked forms into a Datastar UI IR.
2. `ui/hiccup` converts that IR into Hiccup attributes such as
   `data-signals`, `data-on-click`, and `data-attr-href`.

The IR is deliberately upfront in the design. It gives tests and tooling a
stable place to inspect intent before it becomes Datastar strings. That matters
for route validation, explicit payload enforcement, static interpretation, and
future code-generation support.

Typical use:

```clojure
(ui/hiccup view)
```

Inspection use:

```clojure
(ui/lower-ir view)
```

Static interpretation use:

```clojure
(ui/static view)
```

## Route References

Checked navigation and server interaction use route refs:

```clojure
(ui/dispatch :students/archive {:id student-id})
(ui/refresh :students/typeahead-page {:q query})
(ui/href :students/profile {:path-params {:student-id student-id}})
```

The keyword resolves through the Datastar query registry. Literal route strings
are rejected in checked `dispatch`, `refresh`, and `href` forms. This prevents
developers and code agents from scattering route names through UI code.

Route forms:

- `ui/dispatch` posts a command payload.
- `ui/refresh` fetches a query payload and patches the page from Grain's
  reusable Datastar streams.
- `ui/href` builds a normal link from a registered route.

Route options:

```clojure
(ui/href :students/profile
         {:path-params {:student-id student-id}
          :query-params {:tab "documents"}})
```

## Explicit Payloads

Commands and queries send only the payload you provide:

```clojure
(ui/dispatch :documents/sign
             {:document-id document-id
              :signature signature})

(ui/refresh :documents/signing-page
            {:document-id document-id
             :step "review"})
```

The DSL does not have a mode that sends every signal on the page. This keeps
command and query inputs readable, testable, and stable when surrounding UI
state changes.

`ui/dispatch` reserves Grain's command route signal name for the actual command
target. By default this is `$__grainAction`. Developers should not set or read
that signal directly.

## Signals

Use `with-signals` to define component-local state:

```clojure
(ui/with-signals [query {:init ""}
                  open? {:init false}]
  [:div
   [:input {:bind/value query}]
   [:button {:on/click {:effect (ui/set-signal! open? true)}} "Open"]])
```

Inside the body, local names are automatically scoped. Nested components can use
the same local signal names without colliding:

```clojure
(ui/with-signals [query {:init ""}]
  [:div
   (search-box)
   (search-box)])
```

Pass lexical signal handles such as `query` directly in bindings, expressions,
and payload maps. Application developers should not use `with-signal-scope` for
ordinary UI. It exists for framework-level code and advanced tests.

## Events And Modifiers

Checked events use verbose maps:

```clojure
{:on/input {:effect (ui/refresh :students/typeahead-page {:q query})
            :modifiers {:debounce "300ms"}}}
```

The verbose shape keeps modifiers unambiguous and avoids multiple spellings for
the same thing. Modifier keys are passed through to Datastar, so the DSL does
not need a hard-coded table of allowed modifiers.

Common event attributes:

```clojure
:on/click
:on/input
:on/submit
:on/change
:on/keydown
```

## Effects

`dispatch`

Posts an explicit command payload to a registered action route:

```clojure
(ui/dispatch :documents/submit-signature
             {:document-id document-id
              :signature signature})
```

`refresh`

Fetches a registered page or stream route with an explicit payload and applies
the returned Datastar stream:

```clojure
(ui/refresh :documents/signing-page
            {:document-id document-id})
```

`set-signal!`

Sets one signal:

```clojure
(ui/set-signal! query "")
```

`reset!`

Resets a scoped signal to its declared initial value:

```clojure
(ui/reset! query)
```

`when!`

Runs an effect when a condition is truthy:

```clojure
(ui/when! (ui/present? signature)
  (ui/dispatch :documents/submit-signature
               {:signature signature}))
```

`if!`

Chooses between two effects:

```clojure
(ui/if! (ui/present? signature)
  (ui/dispatch :documents/submit-signature {:signature signature})
  (ui/set-signal! error "Signature is required"))
```

`do!`

Runs effects in order:

```clojure
(ui/do!
  (ui/set-signal! saving? true)
  (ui/dispatch :documents/submit-signature {:signature signature}))
```

`on-keys`

Runs effects based on keyboard keys:

```clojure
(ui/on-keys
  {"Enter" (ui/dispatch :search/submit {:q query})
   "Escape" (ui/set-signal! query "")})
```

`action`

Embeds a raw Datastar action string. Use it only when the checked vocabulary is
not enough:

```clojure
(ui/action "@post('/legacy/action')")
```

Raw actions are not route-checked and do not receive automatic route-ref
handling.

## Expressions

Expressions compile into Datastar-compatible JavaScript snippets:

```clojure
query
(ui/lit "open")
(ui/trimmed query)
(ui/num amount)
(ui/num-cents amount)
(ui/present? signature)
(ui/changed? draft saved)
(ui/evt :key)
```

Use `ui/js` for raw JavaScript expression fragments with checked expressions
embedded:

```clojure
(ui/js "Math.max(0, " (ui/num amount) ")")
```

`ui/js` is an expression escape hatch. Prefer checked expression helpers when
they can describe the intent.

## Raw Datastar Passthrough

Raw Datastar attributes remain available:

```clojure
[:button {:data-on-click "@post('/legacy/action')"} "Run"]
```

Raw passthrough is useful for migration and unsupported Datastar features. It is
not checked for route refs, explicit payload shape, or signal scoping. Prefer
checked `ui/...` forms in new production UI.

## Static Interpretation

`ui/static` removes interactive event attributes and can resolve checked links:

```clojure
(ui/static view)
(ui/static view {:query-registry registry})
```

Static output is useful for non-interactive previews, email-safe rendering, and
tests that need to inspect the rendered structure without Datastar behavior.

## Production Use Cases

For workflows like document signing:

- Each command names only the required inputs, such as document id, signer id,
  signature text, checkbox state, or selected step.
- Each query refresh names only the state needed to re-render the relevant page
  or fragment.
- Local UI details such as modal state, typeahead text, tab state, or transient
  validation state do not leak into unrelated server calls.
- Routes live in command/query metadata, not in click handlers.
- Reusable components can share local signal names without collisions.

That combination is the main production improvement over handwritten Datastar
strings: the UI source describes application intent, and route construction,
payload encoding, action strings, and signal scoping are centralized.

## Code Agent Checklist

When generating UI with this DSL:

- Always require `[ai.obney.grain.datastar.ui :as ui]`.
- Wrap component-local state with `ui/with-signals`.
- Use `ui/dispatch`, `ui/refresh`, and `ui/href` with keyword route refs.
- Use verbose event maps: `{:effect ...}` and optional `:modifiers`.
- Put every command/query input in the explicit payload map.
- Call `ui/hiccup` at the render boundary.
- Pass `{:query-registry registry}` in tests when the default registry is not
  available.
- Avoid `ui/action`, `ui/js`, raw `data-*` attributes, and
  `with-signal-scope` unless the checked DSL cannot express the case.
