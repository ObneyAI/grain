# Grain Datastar UI DSL

`ai.obney.grain.datastar.ui` is the checked UI layer for writing Datastar
attributes from ordinary Hiccup. Application code should usually require it as
`ui`:

```clojure
(require '[ai.obney.grain.datastar.ui :as ui])
```

The DSL has three jobs:

- Build Datastar attributes from data, not handwritten strings.
- Keep command/query payloads explicit so ambient page state is not sent by
  accident.
- Own signal scoping so developers do not manually name, thread, or isolate
  component-local Datastar signals.

The normal production boundary is:

```clojure
(ui/hiccup view)
;; Hiccup + UI forms -> UI IR -> Hiccup with Datastar attributes
```

The Datastar UI DSL does not render HTML. It returns Hiccup. Grain's Datastar
adapter and the application's normal HTML renderer still own HTML strings and
SSE patch rendering.

## Table Of Contents

- [Quick Start](#quick-start)
- [Mental Model](#mental-model)
- [End-To-End Workflow](#end-to-end-workflow)
- [Signals](#signals)
- [Bindings](#bindings)
- [Indexed Collections](#indexed-collections)
- [Events And Modifiers](#events-and-modifiers)
- [Routes And Payloads](#routes-and-payloads)
- [Effects](#effects)
- [Expressions](#expressions)
- [IR, Lowering, And Static Output](#ir-lowering-and-static-output)
- [Raw Escape Hatches](#raw-escape-hatches)
- [Complete DSL Reference](#complete-dsl-reference)
- [Code Agent Checklist](#code-agent-checklist)

## Quick Start

Commands and queries are addressed by keywords, not literal routes:

```clojure
(ns app.students.ui
  (:require [ai.obney.grain.datastar.ui :as ui]))

(defn student-typeahead []
  (ui/with-signals [query {:init ""}]
    [:section.typeahead
     [:label {:for "student-search"} "Find student"]
     [:input#student-search
      {:bind/value query
       :placeholder "Search by name, email, or id"
       :on/input {:effect (ui/refresh :students/typeahead-page
                                      {:q query})
                  :modifiers {:debounce "250ms"}}}]
     [:div#student-results]]))

(defn student-index-screen []
  (ui/with-signals [selected-id {:init nil}
                    documents-open? {:init false}
                    saving? {:init false}]
    [:main.students-screen
     [:header
      [:h1 "Students"]
      [:a {:href (ui/href :students/new-student-page)}
       "New student"]]

     (student-typeahead)

     [:section.student-workspace
      [:nav
       [:a {:href (ui/href :students/profile-page
                          {:path-params {:student-id selected-id}})}
        "Profile"]
       [:button {:on/click {:effect (ui/effects
                                      (ui/set-signal documents-open? true)
                                      (ui/refresh :students/documents-panel
                                                  {:student-id selected-id}))}}
        "Documents"]]

      [:section {:bind/show documents-open?}
       [:div#student-documents]]

      [:button {:bind/attr {:disabled (ui/js "!" selected-id " || " saving?)}
                :on/click {:effect (ui/effects
                                     (ui/set-signal saving? true)
                                     (ui/dispatch :students/archive
                                                  {:student-id selected-id}))}}
       "Archive student"]]]))
```

Lower it before rendering:

```clojure
(ui/hiccup (student-index-screen))
```

The checked output contains generated Datastar signal names and Datastar action
strings. The source keeps local signal handles (`query`, `selected-id`,
`documents-open?`, `saving?`) and route refs (`:students/typeahead-page`,
`:students/profile-page`, `:students/documents-panel`, `:students/archive`).
The typeahead has its own scoped `query` signal, separate from the screen's
state.

## Mental Model

Write ordinary Hiccup with checked Datastar-aware attributes:

- `:bind/...` attributes describe Datastar bindings.
- `:on/...` attributes describe Datastar events.
- `ui/dispatch`, `ui/refresh`, and `ui/href` use registry keywords instead of
  route strings.
- `ui/with-signals` declares component-local client state.
- Signal handles are passed directly in bindings, expressions, and payloads.

The compiler walks this source, resolves signal scopes, builds an intermediate
representation, then lowers that IR to plain Hiccup with Datastar attributes.

Application code normally consumes only `ui/hiccup`. Tests and tooling can use
`ui/ir`, `ui/lower-ir`, `ui/lower-expr`, and `ui/lower-effect`.

## End-To-End Workflow

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

## Signals

Use `with-signals` to define component-local state. Each signal binding has a
local Clojure name and an options map:

```clojure
(ui/with-signals [query {:init ""}
                  open? {:init false}
                  amount {:name "amount-dollars" :init ""}]
  [:div
   [:input {:bind/value query}]
   [:button {:on/click {:effect (ui/set-signal open? true)}} "Open"]])
```

Options:

- `:init` - initial client-side signal value.
- `:name` - optional semantic Datastar name. If omitted, the Clojure binding
  name is used.

Signal handles are lexical values. Pass them directly:

```clojure
[:input {:bind/value query
         :bind/text (ui/trimmed query)}]

(ui/refresh :students/typeahead-page {:q query})
(ui/present? query)
```

Nested components can reuse local signal names without colliding. The compiler
generates deterministic scoped names:

```clojure
[:div
 (student-typeahead)
 (student-typeahead)]
```

Application code should not use `with-signal-scope` for normal UI. It exists
for framework code, tests, and rare raw interop where the scope must be
controlled explicitly.

## Indexed Collections

Use `indexed` when repeated inputs edit elements inside one collection signal:

```clojure
(ui/with-signals [custom-amounts {:init [80000 80000 80000]}]
  [:div
   (for [[idx amount] (map-indexed vector [80000 80000 80000])]
     [:input {:value amount
              :bind/value (ui/indexed custom-amounts idx)}])
   [:button {:on/click {:effect
                        (ui/dispatch :payment-plan/save
                          {:custom-amounts custom-amounts})}}
    "Save"]])
```

The row input reads from `custom-amounts[idx]` and writes only that element on
input/change. The whole collection signal can still be sent directly in
`dispatch` or `refresh` payloads. Server re-rendering can replace the `:init`
vector when row counts change.

Indexes may be literals, signal handles, or checked expressions:

```clojure
(ui/indexed custom-amounts idx)
(ui/num-cents (ui/indexed custom-amounts idx))
```

## Bindings

Checked binding attributes lower to Datastar binding attributes:

```clojure
[:input {:bind/value query}]
[:p {:bind/text (ui/trimmed query)}]
[:section {:bind/show open?}]
[:div {:bind/class (ui/present? query)}]
[:button {:bind/attr {:disabled saving?}}]
```

Use `:bind/value` for form values, `:bind/text` for text content,
`:bind/show` for visibility, `:bind/class` for class expressions, and
`:bind/attr` for attribute maps such as `disabled`, `href`, or `aria-*`.

Raw, non-DSL Hiccup attributes pass through unchanged.

## Events And Modifiers

Checked events use explicit maps:

```clojure
{:on/input {:effect (ui/refresh :students/typeahead-page {:q query})
            :modifiers {:debounce "300ms"}}}
```

The event map must contain `:effect`. It may contain `:modifiers`. No other
keys are allowed.

Common event attributes:

```clojure
:on/click
:on/input
:on/submit
:on/change
:on/keydown
```

Modifiers lower generically to Datastar event suffixes. There is no hard-coded
modifier allowlist:

```clojure
{:on/submit {:effect (ui/dispatch :forms/save {:name name})
             :modifiers {:prevent true}}}

{:on/input {:effect (ui/refresh :search/page {:q query})
            :modifiers {:debounce "300ms"}}}
```

Falsy modifier values are omitted. Boolean `true` lowers to a bare suffix, and
string/number/keyword/symbol values lower to dotted suffix values.

## Routes And Payloads

Checked navigation and server interaction use route refs:

```clojure
(ui/dispatch :students/archive {:id student-id})
(ui/refresh :students/typeahead-page {:q query})
(ui/href :students/profile {:path-params {:student-id student-id}})
```

Rules:

- `ui/dispatch` targets a registered command keyword.
- `ui/refresh` targets a registered query keyword with `:datastar/path`.
- `ui/href` targets a registered query keyword with `:datastar/path`.
- Literal route strings are rejected in checked `dispatch`, `refresh`, and
  `href`.
- Payloads are explicit. Ambient page signals are not sent.

Route options:

```clojure
(ui/href :students/profile
         {:path-params {:student-id student-id}
          :query-params {:tab "documents"}})

(ui/refresh :students/profile
            {:tab "documents"}
            {:path-params {:student-id student-id}
             :query-params {:tab "documents"}})
```

Payloads may contain nested maps and collections. Signal handles and checked
expressions are lowered recursively:

```clojure
(ui/dispatch :documents/submit
             {:document {:id document-id
                         :signer {:name signer-name
                                  :email signer-email}
                         :fields [{:id field-id
                                   :value (ui/trimmed field-value)}]}
              :ordered (list signer-name "literal")
              :tags #{"signed" "urgent"}})
```

Maps lower to JSON objects. Vectors, lists, and sets lower to JSON arrays.
Payload map keys must be static literal values. Grain command/query schemas
remain responsible for nested validation and server-side coercion.

`ui/dispatch` reserves Grain's command route signal name for the command action
endpoint. By default this is `$__grainAction`. Application code should not set
or read that signal directly.

## Effects

Effects compile to Datastar action strings and are used in checked event maps.

`dispatch`

Posts an explicit command payload:

```clojure
(ui/dispatch :documents/submit-signature
             {:document-id document-id
              :signature signature})
```

`refresh`

Posts an explicit query payload to a Datastar stream route and applies the
returned stream:

```clojure
(ui/refresh :documents/signing-page
            {:document-id document-id})
```

By default `refresh` includes the reusable stream nonce `dsNonce`. Pass
`{:include-nonce? false}` for one-shot/manual interop.

`set-signal`

Sets one signal:

```clojure
(ui/set-signal query "")
```

`reset-signal`

Resets a signal to its declared `:init` value:

```clojure
(ui/reset-signal query)
```

`clear-errors`

Clears Grain's conventional Datastar error signals:

```clojure
(ui/clear-errors)
```

`blur`

Calls `el.blur()` in the current Datastar event:

```clojure
(ui/blur)
```

`action`

Embeds a raw Datastar action string. Use only when the checked vocabulary is not
enough:

```clojure
(ui/action "@post('/legacy/action')")
```

Raw actions are not route-checked and do not receive route-ref or payload
handling.

`effects`

Runs effects in order:

```clojure
(ui/effects
  (ui/set-signal saving? true)
  (ui/dispatch :documents/submit {:id document-id}))
```

`when-effect`

Runs an effect when a predicate is truthy:

```clojure
(ui/when-effect (ui/present? signature)
  (ui/dispatch :documents/submit-signature {:signature signature}))
```

`choose-effect`

Chooses between effects:

```clojure
(ui/choose-effect (ui/present? signature)
  (ui/dispatch :documents/submit-signature {:signature signature})
  (ui/set-signal error "Signature is required"))
```

`on-keys`

Runs effects by keyboard key:

```clojure
(ui/on-keys
  {"Enter" (ui/dispatch :search/submit {:q query})
   "Escape" (ui/effects (ui/reset-signal query) (ui/blur))})
```

## Expressions

Expressions compile to Datastar-compatible JavaScript snippets:

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

Signal handles can be used directly anywhere an expression is expected.

`ui/js` is the expression escape hatch. It joins raw JavaScript fragments with
checked expressions and signal handles:

```clojure
(ui/js "Math.max(0, " (ui/num amount) ")")
```

Prefer checked expression helpers when they can express the intent.

## IR, Lowering, And Static Output

The compiler pipeline is:

```clojure
(ui/ir source)
(ui/lower-ir ir-node)
(ui/hiccup source)
```

Use `ui/hiccup` for normal rendering. It returns Hiccup, not HTML.

Use `ui/ir` and `ui/lower-ir` for tests, tooling, and inspection. The IR is
stable enough to test intent before it becomes Datastar strings.

Use `ui/static` for non-interactive rendering:

```clojure
(ui/static (ui/ir view))
(ui/static (ui/ir view) {:strip-href? true
                         :strip-raw-events? true
                         :query-registry registry})
```

Static output drops checked events and signal declarations. It can optionally
remove links and raw `data-on*` attributes.

`ui/lower-expr` and `ui/lower-effect` are low-level helpers for inspecting
single expression/effect nodes.

`ui/defcomponent` defines a function whose body is compiled with `ui/hiccup`.
It is a convenience macro, not required for application code.

## Raw Escape Hatches

Raw Datastar attributes pass through unchanged:

```clojure
[:button {:data-on-click "@post('/legacy/action')"} "Run"]
```

Raw attributes are useful for migration and unsupported Datastar features. They
are not checked for route refs, explicit payload shape, or signal scoping.

Escape hatches:

- `ui/action` for raw Datastar action strings.
- `ui/js` for raw JavaScript expression fragments.
- Raw `data-*` attributes for unsupported Datastar attributes.
- `with-signal-scope`, `create-signal`, `attach-signals`, and `signal-ref` for
  low-level interop and tests.

Prefer checked `ui/...` forms in new production UI.

## Complete DSL Reference

### Render And Compilation

`hiccup`

Compiles checked UI source to plain Hiccup with Datastar attributes:

```clojure
(ui/hiccup source)
(ui/hiccup source {:query-registry registry})
```

`ir`

Compiles checked UI source to Datastar UI IR:

```clojure
(ui/ir source)
```

`lower-ir`

Lowers UI IR to plain Hiccup:

```clojure
(ui/lower-ir ir-node)
(ui/lower-ir ir-node {:query-registry registry})
```

`static`

Interprets IR as static Hiccup:

```clojure
(ui/static ir-node)
(ui/static ir-node {:strip-href? true
                    :strip-raw-events? true
                    :query-registry registry})
```

`defcomponent`

Defines a component function that returns `ui/hiccup` output:

```clojure
(ui/defcomponent save-button [document-id]
  [:button {:on/click {:effect (ui/dispatch :documents/save
                                            {:id document-id})}}
   "Save"])
```

### Signals

`with-signals`

Declares lexical Datastar signals and attaches declarations to the returned
subtree root:

```clojure
(ui/with-signals [query {:init ""}
                  open? {:init false}
                  amount {:name "amount-dollars" :init ""}]
  ...)
```

`with-signal-scope`

Advanced. Binds an explicit signal scope:

```clojure
(ui/with-signal-scope {:prefix "plan" :key application-id}
  ...)
```

`create-signal`

Low-level. Creates a signal handle from a binding symbol and options map:

```clojure
(ui/create-signal 'query {:init ""})
```

`attach-signals`

Low-level. Attaches signal declarations to a Hiccup element:

```clojure
(ui/attach-signals [query-signal] [:div])
```

`signal-ref`

Low-level. Returns the Datastar JavaScript reference for a signal/name:

```clojure
(ui/signal-ref "query")
(ui/signal-ref "amount-dollars")
```

### Binding Attributes

`:bind/value`

Lowers to `data-bind` for ordinary signal handles:

```clojure
[:input {:bind/value query}]
```

For indexed collection references, the compiler emits checked read/write
behavior that updates only that collection element:

```clojure
[:input {:bind/value (ui/indexed custom-amounts idx)}]
```

`:bind/text`

Lowers to `data-text`:

```clojure
[:p {:bind/text (ui/trimmed query)}]
```

`:bind/show`

Lowers to `data-show`:

```clojure
[:section {:bind/show open?}]
```

`:bind/class`

Lowers to `data-class`:

```clojure
[:div {:bind/class (ui/present? query)}]
```

`:bind/attr`

Lowers each entry to `data-attr:*`:

```clojure
[:button {:bind/attr {:disabled saving?
                      :aria-busy saving?}}]
```

Other `:bind/foo` attrs lower to `data-bind:foo`.

### Event Attributes

Checked event values must be maps:

```clojure
{:on/click {:effect effect}}
{:on/input {:effect effect :modifiers {:debounce "300ms"}}}
```

Supported checked event names are open-ended. Common names are:

```clojure
:on/click
:on/input
:on/submit
:on/change
:on/keydown
```

`:modifiers`

Generic Datastar event modifier map:

```clojure
{:prevent true
 :debounce "300ms"
 :window true}
```

### Route And Payload Forms

`dispatch`

Creates a checked command dispatch effect:

```clojure
(ui/dispatch :documents/sign {:id document-id})
```

Options:

```clojure
(ui/dispatch :documents/sign {:id document-id}
             {:post "$__grainAction"})
```

`:post` must be a reserved signal reference. Literal routes are rejected.

`refresh`

Creates a checked query/stream refresh effect:

```clojure
(ui/refresh :documents/signing-page {:id document-id})
```

Options:

```clojure
(ui/refresh :documents/signing-page
            {:id document-id}
            {:method :post
             :path-params {:document-id document-id}
             :query-params {:tab "review"}
             :include-nonce? true})
```

`href`

Creates a checked page href:

```clojure
(ui/href :documents/signing-page
         {:path-params {:document-id document-id}
          :query-params {:tab "review"}})
```

### Effects

`set-signal`

```clojure
(ui/set-signal saving? true)
```

`reset-signal`

```clojure
(ui/reset-signal query)
```

`clear-errors`

```clojure
(ui/clear-errors)
```

`blur`

```clojure
(ui/blur)
```

`action`

```clojure
(ui/action "$open = false;")
```

`effects`

```clojure
(ui/effects (ui/set-signal saving? true)
        (ui/dispatch :documents/save {:id document-id}))
```

`when-effect`

```clojure
(ui/when-effect (ui/present? signature)
  (ui/dispatch :documents/sign {:signature signature}))
```

`choose-effect`

```clojure
(ui/choose-effect (ui/present? signature)
  (ui/dispatch :documents/sign {:signature signature})
  (ui/set-signal error "Required"))
```

`on-keys`

```clojure
(ui/on-keys {"Enter" (ui/dispatch :search/submit {:q query})
             "Escape" (ui/reset-signal query)})
```

`lower-effect`

Low-level. Lowers a checked effect to a Datastar action string:

```clojure
(ui/lower-effect (ui/set-signal query ""))
```

### Expressions

Signal handles

Use a signal handle directly:

```clojure
query
```

`lit`

Wraps a literal expression value:

```clojure
(ui/lit "open")
```

`indexed`

References one element inside a collection signal:

```clojure
(ui/indexed custom-amounts 0)
(ui/indexed custom-amounts idx)
```

`trimmed`

Calls `.trim()` on a signal/expression:

```clojure
(ui/trimmed query)
```

`num`

Wraps `Number(x)`:

```clojure
(ui/num amount)
```

`num-cents`

Converts dollar text to rounded integer cents:

```clojure
(ui/num-cents amount)
```

`present?`

Checks for non-empty/non-null:

```clojure
(ui/present? signature)
```

`changed?`

Compares an expression to an old value:

```clojure
(ui/changed? draft saved)
(ui/changed? title "Old")
```

`evt`

References a field on Datastar's event object:

```clojure
(ui/evt :key)
```

`js`

Raw JavaScript expression fragments with checked values embedded:

```clojure
(ui/js "Math.max(0, " (ui/num amount) ")")
```

`lower-expr`

Low-level. Lowers an expression to a Datastar JavaScript string:

```clojure
(ui/lower-expr (ui/trimmed query))
```

### Constants And Dynamic Vars

`default-command-post`

Default reserved Datastar action target used by `dispatch`:

```clojure
ui/default-command-post
```

`*signal-scope*`

Dynamic var used by `with-signal-scope`. Framework-level only.

`*lower-opts*`

Dynamic var used during lowering. Framework-level only.

## Code Agent Checklist

When generating UI with this DSL:

- Require `[ai.obney.grain.datastar.ui :as ui]`.
- Wrap component-local state with `ui/with-signals`.
- Use plain signal option maps: `[query {:init ""}]`.
- Pass signal handles directly in bindings, expressions, and payloads.
- Use `:bind/value`, `:bind/text`, `:bind/show`, `:bind/class`, and
  `:bind/attr` for checked bindings.
- Use verbose event maps: `{:effect ...}` and optional `:modifiers`.
- Use `ui/dispatch`, `ui/refresh`, and `ui/href` with keyword route refs.
- Put every command/query input in the explicit payload map.
- Use nested payload maps/collections when they match command/query schemas.
- Call `ui/hiccup` at the render boundary.
- Pass `{:query-registry registry}` in tests when the default registry is not
  available.
- Avoid `ui/action`, `ui/js`, raw `data-*` attrs, low-level signal helpers, and
  `with-signal-scope` unless the checked DSL cannot express the case.
