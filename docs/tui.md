# TUI Adapter (Terminal UI)

Grain exposes queries and commands as terminal applications via the `tui-adapter` component, in a manner analogous to how the Datastar adapter exposes them as hypermedia web apps. The adapter is medium-native: it plays to the terminal's strengths (alt-screen and main-screen, dense layout, modal keymaps, scrollback) rather than constraining itself to a cross-medium presentation layer.

For application authors building TUI apps, see the **[TUI Manual](./tui-manual.md)** for a walk-through. This document is the architecture and reference.

```clojure
(defquery :counter view
  {:authorized?       (constantly true)
   :grain/read-models {:counter/state 1}
   :tui/buffer        :alt
   :tui/projection    :snapshot
   :tui/render
   (fn [{:keys [count]}]
     [:col
      [:text {:bold? true} "Counter"]
      [:text (str count)]
      [:text {:dim? true} "[+] inc   [q] quit"]])
   :tui/keymap
   {"+" [:command :counter/inc]
    "q" [:session :quit]}}
  [context]
  {:query/result (rmp/project context :counter/state)})
```

## Architecture

```
                          Grain
                            │
                ┌───────────┴───────────┐
                │                       │
            event log               command bus
                │                       ▲
                ▼          ┌── pubsub ──┐
            queries  ──────┘            │
                │                       │
                ▼                       │
        ┌───────────────────────────────┴────────┐
        │           TUI adapter                  │
        │  ┌─────────────────────────────────┐   │
        │  │ Per-session state:              │   │
        │  │ ─ screen + screen stack         │   │
        │  │ ─ overlay layer                 │   │
        │  │ ─ render model (cell buffer)    │   │
        │  │ ─ input parser + keymap stack   │   │
        │  │ ─ pubsub subscription channel ──┼───┘
        │  └────────────┬────────────────────┘   │
        │               │                         │
        │  ┌────────────▼────────────────────┐   │
        │  │ Session registry                │   │
        │  │ (out-of-band: toasts,           │   │
        │  │  force-refresh, introspection)  │   │
        │  └────────────┬────────────────────┘   │
        │               │                         │
        │  ┌────────────▼──────────┐              │
        │  │ Renderers             │              │
        │  │ ─ buffer × projection │              │
        │  │ ─ overlay primitives  │              │
        │  └────────────┬──────────┘              │
        │               │                         │
        │  ┌────────────▼──────────┐              │
        │  │ Transport             │              │
        │  │ ─ stdio (MVS)         │              │
        │  └───────────────────────┘              │
        └────────────────────────────────────────┘
                          │
                          ▼
                       terminal
```

A **session** is a long-lived, bidirectional, stateful connection between a single user and the adapter, bound to a single Grain tenant for its lifetime. Each session has a current screen, a render model (the cells currently believed to be on the terminal), an input parser, a keymap stack, and an optional overlay layer.

## Subscription model

When a session opens a screen, the adapter:

1. Reads `:grain/read-models` from the query metadata.
2. Resolves those read-model names to event types via `rmp/read-model-registry*`.
3. Optionally compiles `:tui/event-tags` into a predicate over `:event/tags` for fine-grained filtering.
4. Creates a sliding-buffer-64 channel and subscribes it via `pubsub/sub` to each event type, with the optional filter as a transducer.
5. Runs the session's render loop on `alts!!` over `[sub-chan input-ch resize-ch (timeout 30000)]`.
6. On event delivery, debounces by `:tui/debounce-ms` (default 50ms), then re-evaluates the query and emits diffed output.

**On screen change** (via `[:session :push-screen]` / `[:session :back]`), the prior subscription is closed and a new one is created against the new screen's read-models. There is no leakage between screens.

**No polling fallback.** Queries that want event-driven re-renders must declare `:grain/read-models`. Queries without it render once on screen-enter and re-render only on input events that mutate session state, explicit `[:session :refresh]`, resize, or out-of-band `refresh-screen!` from the registry.

This is the same pattern Grain's Datastar adapter uses — read-model resolution, pubsub subscription, debounced re-render, optional event-tag filtering. Same machinery, different transport.

### Out-of-band registry

The session registry exists for operations that don't fit the per-screen subscription model:

- **Cross-screen toasts** — a long-running command issued from screen A fails while the user has navigated to screen B; the failure surfaces as a toast in screen B's overlay.
- **Force-refresh from operational tools** — admin tools or REPL workflows can invalidate a session.
- **Introspection** — tooling can enumerate active sessions, their tenants, their current screens.

The registry helpers (`sessions-on-screen`, `sessions-for-tenant`, `sessions-for-user`) remain available for these cases but are **not** the primary refresh mechanism.

## Buffer × projection

Two orthogonal axes determine the shape of a screen.

### Buffer (`:tui/buffer`)

- **`:alt`** — alt-screen. Acquired on screen enter, released on exit. Prior terminal contents restored on exit.
- **`:main`** — main screen. Content goes into the terminal's scrollback as it would for any program writing to stdout.

### Projection (`:tui/projection`)

- **`:snapshot`** — the query's result is rendered as a single frame. When broadcasts arrive, the query is re-evaluated and the new result is diffed against the prior frame.
- **`:stream`** — the query's result is an ordered sequence with stable per-segment identity (declared via `:tui/segments {:items :path :key :id-field}`). The render function is called once per segment. New segments are appended; previously-rendered segments may be updated in place if their data changes while still in the visible window.

|         | `:snapshot`                                               | `:stream`                                                |
| ------- | --------------------------------------------------------- | -------------------------------------------------------- |
| `:alt`  | dashboard pattern: htop, k9s, dense status views          | live tail in a fixed canvas, multi-pane streaming dashboards |
| `:main` | static print into scrollback: man-page-style screens      | transcript pattern: conversational agents, REPLs, log tails |

## Hiccup vocabulary

The adapter's rendering currency is hiccup — pure data, no functions in the tree. The vocabulary is closed at the substrate level (§7.1–§7.5 of the spec):

| Element     | Form                                                           |
| ----------- | -------------------------------------------------------------- |
| `:text`     | `[:text "..."]` or `[:text {:fg :bg :bold? :italic? ...} "..."]` |
| `:line`     | `[:line]`                                                      |
| `:gap`      | `[:gap n]`                                                     |
| `:row`      | `[:row & children]` (also accepts `:weights`)                  |
| `:col`      | `[:col & children]`                                            |
| `:weighted` | `[:weighted {:weights [w1 w2 ...]} & children]`                |
| `:box`      | `[:box {:title? :border? :width? :height?} & children]`        |
| `:pad`      | `[:pad {:t :r :b :l} child]`                                   |
| `:list`     | `[:list {:items [hiccup ...] :selected idx?}]`                 |
| `:table`    | `[:table {:columns [...] :rows [...] :selected idx?}]`         |
| `:scroll`   | `[:scroll {:height n :offset n} & children]`                   |
| `:turn`     | `[:turn {:role :user|:assistant|:system} & children]`          |
| `:fold`     | `[:fold {:summary "..." :expanded? bool} & children]`          |
| `:status`   | `[:status {:state :pending|:running|:done|:failed :label "..."}]` |
| `:progress` | `[:progress {:value 0.0-1.0 :label?}]`                         |
| `:spinner`  | `[:spinner {:label? :phase n}]`                                |
| `:input`    | `[:input {:value "..." :cursor n :placeholder?}]`              |

Colors: `:default`, named keywords (`:red`, `:bright-blue`, …), or `[:rgb r g b]`. Color is downgraded automatically based on the negotiated terminal capability (truecolor → 256 → 16 → mono).

## Cell-rendering elements (extension point)

Applications can register new hiccup elements via `defelement` — the **single** user-space extension to the visual language. The substrate's built-ins (`:text`, `:row`, etc.) are themselves implemented as registered elements over the same primitive `CellGrid` layer (§7.6.6 honesty requirement); there is no privileged path.

```clojure
(require '[ai.obney.grain.tui-adapter.interface :as tui])

(tui/defelement :sparkline
  {:doc            "Inline sparkline chart from numeric data."
   :attrs          {:data [:vector :number]
                    :fg   {:optional? true :default :default}}
   :preferred-size (fn [{:keys [data]}] {:width (count data) :height 1})
   :min-size       {:width 1 :height 1}
   :stream-stable? true
   :render
   (fn [{:keys [data fg]} {:keys [width]}]
     (let [chars ["▁" "▂" "▃" "▄" "▅" "▆" "▇" "█"]
           mx    (apply max 1 data)
           cells (->> (take width data)
                      (mapv (fn [v]
                              {:char (get chars (min 7 (int (* (/ v mx) 8))))
                               :fg   fg})))]
       {:width  (count cells) :height 1 :cells [cells]}))})

;; Now usable in any screen:
[:row [:text "CPU "]
      [:sparkline {:data cpu-history :fg :green}]
      [:text (format " %.1f%%" current-cpu)]]
```

A `CellGrid` is plain Clojure data: `{:width n :height n :cells [[Cell ...]]}` where each cell is `{:char :fg :bg :bold? :italic? :underline? :dim?}`. The substrate provides constructors and combinators in `tui-adapter.interface`: `blank`, `text-row`, `stack`, `beside`, `overlay`, `with-style`, `clip`.

Registered elements participate in layout, diffing, capability fallback, and stream-projection caching exactly as built-ins do. Constraints: cannot emit raw bytes, cannot persist state (render must be pure), cannot define new buffers/projections/event types.

## Session model

A session holds:

- `:session-id` — UUID generated on connect.
- `:tenant-id` — Grain tenant scope. **Immutable for the session lifetime.**
- `:user-id` — authenticated user identity, derived from the transport.
- `:current-screen` — `{:query-id :inputs :params}` plus the screen's full metadata.
- `:screen-stack` — push/pop history for `[:session :back]`.
- `:overlay` — `nil` or a `{:type :toast|:modal|:palette ...}` map. Rendered above the current screen.
- `:render-model` — the cell grid the adapter believes is on the terminal.
- `:focus` — current focused region (in screens with `:tui/layout`).
- `:keymap-stack` — assembled per event in priority order.
- `:subscription` — sub-chan for the current screen.
- `:stream-state` — for streaming-projection screens: per-segment-key render cache + visible window.
- `:terminal-caps` — negotiated capabilities (color depth, alt-screen).

### Multi-session

Sessions are independent. A single user may hold any number of concurrent sessions; they share user identity and tenant binding but nothing else. Two terminals SSH'd in by the same user are two unrelated sessions with independent screen stacks, overlays, and focus state — the browser-tab model.

### Session actions

A small set of operations the adapter handles directly (not Grain commands):

| Action                                      | Effect                                                       |
| ------------------------------------------- | ------------------------------------------------------------ |
| `[:session :push-screen {:query-id ...}]`   | Push a new screen onto the stack; tear down old subscription, open new. |
| `[:session :back]`                          | Pop the screen stack; subscription rebuilt for the restored screen. |
| `[:session :quit]`                          | Terminate the session cleanly.                               |
| `[:session :open-palette {:query-id ...}]`  | Open a query-driven filterable list as an overlay (§9.5).    |
| `[:session :open-overlay {:type :modal ...}]` | Open an arbitrary modal overlay.                           |
| `[:session :dismiss-overlay]`               | Close any active overlay.                                    |
| `[:session :focus {:region}]`               | Move focus to a named region.                                |
| `[:session :refresh]`                       | Force re-render of the current screen.                       |
| `[:session :toast {:message :level :ttl-ms?}]` | Emit a transient overlay (typically from error handling). |

### Lifecycle hooks

Screens may declare `:tui/on-enter` and `:tui/on-exit` — both `(fn [session-state-snapshot] ...)`. They fire on screen change. Hook exceptions are caught and logged; a misbehaving hook cannot destabilize the session.

## Keymaps

### Key syntax

- Single keys: `"a"`, `"A"`, `"1"`, `"?"`.
- Named keys: `"<enter>"`, `"<esc>"`, `"<tab>"`, `"<up>"`, `"<f1>"`.
- Modifiers: `"C-a"` (Ctrl), `"M-x"` (Alt/Meta), `"S-<tab>"` (Shift), combinable `"C-M-a"`.
- Sequences: vectors `["g" "g"]`, `["<leader>" "f" "s"]`.

### Tagged tuples

Keymap values are tagged tuples — the tag determines the handler:

```clojure
{:tui/keymap
 {"q"          [:session :quit]
  "?"          [:session :open-palette {:query-id :help/all}]
  ["g" "g"]    [:session :scroll-top]
  "<enter>"    [:command :invoice/post {:inputs-from-selection :invoice-id}]
  "p"          [:command :invoice/post {:inputs {:status :paid}}]}}
```

- `[:command name opts?]` — issue a Grain command. `opts` may contain `:inputs` (literal) and input-derivation keys: `:inputs-from-selection`, `:inputs-from-focus`, `:inputs-from-prompt`.
- `[:session action opts?]` — invoke a session action.

Tags other than `:command` and `:session` are errors.

### Resolution order

Keymaps are layered. On each input event, the adapter walks the stack:

1. Active overlay
2. Focused region keymap
3. Screen keymap
4. Session keymap
5. Global keymap (substrate-provided defaults)

First match wins. Sequence chords hold for ~1 s before timing out.

## Layout

Declared via `:tui/layout` on any screen that wants region decomposition:

```clojure
:tui/layout
[:col
 [:region :header  {:height 1}]
 [:row {:weight 1}
  [:region :sidebar {:width 30}]
  [:region :main    {:weight 1}]]
 [:region :footer  {:height 1}]]

:tui/regions
{:header (fn [result] [:text "..."])
 :sidebar (fn [result] [:list ...])
 :main    (fn [result] ...)
 :footer  (fn [result] [:text {:dim? true} "..."])}
```

Sizes: `{:width n}` / `{:height n}` for fixed, `{:weight n}` for proportional, `{:min n :max n}` for bounded. Default is weight 1.

Layout is recomputed on `SIGWINCH`. Region focus and scroll state are preserved through resize and refresh.

## Rendering pipeline

```
:tui/render -> hiccup (pure data)
       v
layout/render-element -> CellGrid
       v
overlay compositing (cells/overlay overlay-grid onto screen-grid)
       v
diff against :render-model -> seq of {:row :col :cells [...]} runs
       v
ansi/emit threads style state across runs, writes minimum bytes
       v
update :render-model
```

The diff is bounded for streaming projections — only segments inside the current visible window plus the input area participate. The overlay is composited independently and diffed on top.

## Streaming contract

When `:tui/projection` is `:stream`:

```clojure
:tui/projection :stream
:tui/segments
{:items :turns       ; keyword path, vector path, or fn
 :key   :turn/id}    ; stable identifier for each segment
```

Two requirements:

1. **Append-mostly at the tail.** New segments are added at the end. Earlier segments may be modified in bounded ways (status flips, growing text) but the count and ordering of segments before the tail are stable.
2. **Stable identity.** Every segment carries a stable identifier.

A query that needs to reorder, insert in the middle, or delete segments must use `:snapshot` projection. Contract violations (reorder, drop, key-mutate) are logged as warnings and trigger a full visible-window re-render for that frame.

The render function takes a single segment and returns hiccup for that segment. The substrate handles iteration, segment identity tracking, in-place updates for in-window segments, and append emission.

## Palettes

A palette is an overlay containing a filterable, selectable list driven by a query result. Configured at the call site:

```clojure
[:session :open-palette
 {:query-id   :sheets/all
  :inputs     {}
  :item-key   :sheet/id
  :item-label :sheet/name
  :on-select  [:command :sheet/execute {:inputs-from-selection :sheet-id}]}]
```

The substrate provides:

- A filter input pinned to the top of the overlay.
- A scrollable list filtered by the current filter text against `:item-label`.
- Arrow-key selection and `Enter` to dispatch `:on-select`.
- `Esc` to dismiss without action.

On selection, `:inputs-from-selection` resolves the highlighted item's `:item-key` value into the dispatched command's input. Queries are unaware of being used as palette sources — the same query can drive multiple palettes with different item-labels and on-select actions.

## Error and edge-case handling

| Situation                          | Behavior                                                      |
| ---------------------------------- | ------------------------------------------------------------- |
| Query throws                       | Error frame rendered with the message; session not terminated. |
| Query returns Cognitect anomaly    | Error frame with category + message; session not terminated. |
| Command fails                      | Transient toast in the overlay layer; no state advance.       |
| Disconnection                      | Session removed from registry; in-flight commands continue.   |
| Reduced color depth / alt-screen   | Graceful fallback (color quantized; alt-screen → clear-and-reposition). |
| Streaming projection contract violation | Warning logged; full visible-window re-render that frame. |

Render exceptions are caught by `safe-render-frame!` — the loop survives, an error frame is painted, and the stack trace goes to STDERR (JLine owns STDOUT, so a console publisher would trample the TUI).

## Query metadata reference

| Key                  | Required when           | Purpose                                                 |
| -------------------- | ----------------------- | ------------------------------------------------------- |
| `:tui/buffer`        | screen-able             | `:alt \| :main` — which terminal screen to render into  |
| `:tui/projection`    | screen-able             | `:snapshot \| :stream` — iteration discipline           |
| `:tui/render`        | screen-able             | `(thing) → hiccup` — pure render fn                     |
| `:tui/segments`      | `:stream` projection    | `{:items :key}` — extracts segment list and identity    |
| `:tui/layout`        | optional                | layout tree using `:region` leaves                      |
| `:tui/regions`       | with `:tui/layout`      | per-region renderers and keymaps                        |
| `:tui/input`         | optional                | sticky input area config                                |
| `:tui/keymap`        | optional                | screen-level keymap                                     |
| `:tui/event-tags`    | optional                | `{:tag-key :query-context-path}` — fine-grained filter  |
| `:tui/debounce-ms`   | optional                | Coalesce window for event-driven re-renders. Default 50. |
| `:tui/on-enter`      | optional                | lifecycle hook fired on screen entry                    |
| `:tui/on-exit`       | optional                | lifecycle hook fired on screen exit                     |
| `:grain/read-models` | for event-driven re-renders | shared with other adapters (Datastar etc.)         |
| `:authorized?`       | optional                | auth predicate `(fn [ctx] boolean)`                     |

## Integrant components

The component exposes two Integrant keys:

```clojure
::tui-system/tui-registry        {}
::tui-system/tui-stdio-transport {:registry           (ig/ref ::tui-system/tui-registry)
                                  :event-pubsub       (ig/ref ::event-pubsub)
                                  :base-context       (ig/ref ::base-context)
                                  :default-screen     my-screen
                                  :process-query-fn   (ig/ref ::process-query-fn)
                                  :process-command-fn (ig/ref ::process-command-fn)
                                  :tenant-resolver    (ig/ref ::tenant-resolver)
                                  :user-resolver      (ig/ref ::user-resolver)
                                  :debounce-ms        50}
```

`::tui-registry` initializes a sessions atom and is the side-channel for out-of-band ops. `::tui-stdio-transport` opens a JLine terminal in raw mode, starts a session, and registers it. On halt, transports tear down before the registry, so sessions are stopped and the terminal restored.

## Testing

The component ships a `loop_test.clj` that spawns the actual `run-loop!` thread and pokes it via `input-ch` — the same path the JLine pump uses. Unit tests in this brick assert on emitted `CellGrid` data, not raw ANSI bytes; bytes are environment-dependent, CellGrids are pure data.

For your own apps, the same pattern works: build a `make-session` with a captured-output sink and a mock pubsub, push synthetic key events, assert on the emitted grids and on registry state.

## Non-goals

- No application-defined render elements outside `defelement` (which produces CellGrids — no raw ANSI escape hatches).
- No imperative draw API. Renderers are pure.
- No functions in hiccup. The render function is the only function; its output is fully data.
- No general layout engine. Tree-of-boxes with weights and bounds.
- No application-managed scrollback. The terminal owns scrollback for `:main`-buffer screens.
- No widgets framework. If a primitive is insufficient, the adapter extends — applications don't.
- No new subscription mechanism. The adapter consumes Grain's existing pubsub like any other Grain consumer.

For the full conceptual surface, see the working spec at `grain-tui-adapter-spec-v0.6.md`.
