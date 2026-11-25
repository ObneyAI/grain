# Grain Datastar UI

Write a view as Hiccup. Use `ai.obney.grain.datastar.ui` for signal bindings,
events and routes. Call `ui/hiccup` on the assembled view and return its result
under `:datastar/hiccup` from the query. See [Datastar routes and streams](datastar.md).

## Search as you type

This component sends the search text to a query registered with
`:datastar/path`. The query can read it from `[:query :q]` on the next render.

```clojure
(require '[ai.obney.grain.datastar.ui :as ui])

(defn customer-typeahead []
  (ui/with-signals [query {:init ""}]
    [:section
     [:label "Find customer"
      [:input {:bind/value query
               :on/input {:effect (ui/refresh :customers/typeahead-page {:q query})
                          :modifiers {:debounce "250ms"}}}]]]))

(ui/hiccup (customer-typeahead))
```

## Signals

Each `with-signals` binding takes a local name and an options map. `:init`
supplies the initial value. Pass the handle to child functions that read or
write it. Return one Hiccup element vector from the binding. Compiling the
whole tree gives repeated instances distinct signal names.

Use `{:name "selection" :init {} :stable? true}` when separate patches must
address the same page-level signal. Without `:stable?`, the compiler scopes
the name to the component.

### Collections

```clojure
(ui/with-signals [amounts {:init [10 20]}]
  [:div
   (for [i (range 2)]
     [:input {:bind/value (ui/indexed amounts i)}])
   [:button {:on/click {:effect (ui/dispatch :invoice/save {:amounts amounts})}}
    "Save"]])
```

An indexed binding writes only its element. Indexes can be literals, handles
or checked expressions. A nested binding such as
`(ui/indexed (ui/indexed rows i) "amount")` displays an empty value and skips
writes while its parent row is missing.

## Bindings

| Hiccup attribute | Use |
| --- | --- |
| `:bind/value` | Bind a form control to a signal or indexed element. |
| `:bind/text`, `:bind/show`, `:bind/class` | Set text, visibility or classes from an expression. |
| `:bind/attr {:disabled saving?}` | Set HTML attributes, including ARIA attributes. |
| `:bind/prop {:checked selected?}` | Write DOM properties. Useful for a checkbox’s live checked state. |
| `:effect` | Run a reactive element effect when its dependencies change. |
| `:morph/ignore true` | Preserve browser-managed content, such as a drawn canvas, during patches. |

Other `:bind/foo` keys lower to `data-bind:foo`. Ordinary Hiccup attributes
and raw Datastar `data-*` attributes pass through.

## Events and effects

An `:on/*` handler takes a map with a required `:effect` and optional `:modifiers`.
For example, prevent the browser’s form submission while dispatching a command:

```clojure
{:on/submit {:effect (ui/dispatch :customers/save {:name customer-name})
             :modifiers {:prevent true}}}
```

Use `:on/signal-patch` for Datastar signal updates. Other events, including
`:on/input`, `:on/keydown` and `:on/click`, use DOM event names. A true
modifier emits a bare suffix. Strings, numbers, keywords and symbols supply a
suffix value, as in `{:debounce "250ms"}`. False and nil omit the modifier.

| Effect | Behaviour |
| --- | --- |
| `(ui/set-signal target value)`, `(ui/reset-signal signal)` | Assign a value or restore the declared initial value. |
| `(ui/effects a b)` | Run effects in order. |
| `(ui/when-effect pred a)`, `(ui/choose-effect pred a b)` | Run an effect conditionally. |
| `(ui/on-keys {"Escape" (ui/blur)})` | Match a keyboard key and run its effect. `blur` removes focus from the current element. |
| `(ui/clear-errors)` | Clear Grain’s client error signals. |
| `(ui/action js-string)` | Embed a raw Datastar action. |

## Routes and payloads

| Call | Target |
| --- | --- |
| `(ui/dispatch :customers/save {:name customer-name})` | Post to the shared command endpoint. The page supplies its address in the reserved `$__grainAction` signal. |
| `(ui/refresh :customers/search {:q query})` | Send inputs to a query’s stream route. The default POST includes the `dsNonce` for stream reuse. |
| `(ui/href :customers/profile {:path-params {:id id} :query-params {:tab "history"}})` | Link to the query’s page. Match parameter names to the registered route template. |

Use registered keywords for route targets. Both query helpers require
`:datastar/path`. `refresh` takes an optional third map with `:method`,
`:path-params`, `:query-params` or `:include-nonce? false`.
`dispatch` accepts a `:post` override for the reserved action signal.

Only explicit payload fields are sent. Handles and expressions can appear
inside nested maps and collections. Payload maps use literal keys and omit
nil values, including signals that resolve to null or undefined at submission.
Vectors, lists and sets become arrays and retain null elements. Register the
command or query input schema for server-side validation.

## Expressions

Use a handle directly to read its current value. Use `ui/trimmed` for text,
`ui/num` for numeric conversion, `ui/num-cents` for rounded cents as a string,
`ui/present?` to exclude null and the empty string, and `ui/changed?` to compare
an expression with a saved literal.
`(ui/evt :key)` reads a field of the browser event.

`ui/js` combines JavaScript fragments with checked expressions. Strings passed
directly to `js` are source code. Wrap a literal with `ui/lit` for quoting
and escaping, for example `(ui/js status " === " (ui/lit "open"))`.

## Rendering and inspection

`ui/hiccup` returns Hiccup suitable for an HTML renderer. For tests, pass
`{:query-registry registry}` as its second argument. `ui/defcomponent` defines
a function that compiles its own result.

| Tool | Use |
| --- | --- |
| `ui/ir`, `ui/lower-ir` | Inspect the intermediate representation or lower it to Hiccup. |
| `ui/static` | Render IR without checked events or signal declarations. Options include `:strip-href?` and `:strip-raw-events?`. |
| `ui/lower-expr`, `ui/lower-effect` | Inspect the JavaScript for a single expression or effect. |
| `ui/with-signal-scope`, `ui/create-signal`, `ui/attach-signals`, `ui/signal-ref` | Control signal naming and declarations for interop with existing Datastar code. |

See the [UI namespace](../components/datastar/src/ai/obney/grain/datastar/ui.clj) for arities and compiler options.
