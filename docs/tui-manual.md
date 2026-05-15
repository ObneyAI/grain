# TUI Adapter — Application Author's Manual

A practical guide for building terminal applications on top of Grain's TUI adapter. Assumes familiarity with [core concepts](./core-concepts.md) (commands, queries, events, read models). For the architecture overview and full surface area, see [tui.md](./tui.md).

We'll build the demo at `development/src/grain_tui_demo.clj` from scratch — a counter app with real Grain wiring (event store, pubsub, read model) and an Integrant lifecycle.

## Table of contents

1. [Setup](#1-setup)
2. [Schemas](#2-schemas)
3. [The read model](#3-the-read-model)
4. [Commands](#4-commands)
5. [The query](#5-the-query)
6. [The screen](#6-the-screen)
7. [Wiring with Integrant](#7-wiring-with-integrant)
8. [Running it](#8-running-it)
9. [Patterns beyond the counter](#9-patterns-beyond-the-counter)
   - [Streaming screens](#streaming-screens)
   - [Palettes](#palettes)
   - [Multi-screen navigation](#multi-screen-navigation)
   - [Lifecycle hooks](#lifecycle-hooks)
   - [Custom cell-rendering elements](#custom-cell-rendering-elements)
10. [Testing your app](#10-testing-your-app)
11. [Operational notes](#11-operational-notes)

## 1. Setup

Add `grain-tui` to your project's `deps.edn`:

```clojure
obneyai/grain-tui
{:git/url "https://github.com/ObneyAI/grain.git"
 :sha "..."
 :deps/root "projects/grain-tui"}
```

The project pulls in `tui-adapter` plus the v2 stack (`command-processor-v2`, `query-processor`, `read-model-processor-v2`, `event-store-v3`, `pubsub`, `kv-store`, `kv-store-lmdb`, `time`, `anomalies`, `schema-util`, `query-schema`, `fressian-util`).

Required namespaces in your app:

```clojure
(ns my.tui-app
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.command-processor-v2.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.query-schema.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.tui-adapter.interface :as tui]
            [ai.obney.grain.tui-adapter.system :as tui-system]
            [integrant.core :as ig]
            [malli.core :as m]))
```

## 2. Schemas

Every command, query, and event must have a Malli schema registered. Define them once in a `defschemas` block. Closed maps (`{:closed true}`) are recommended — they reject extra keys and surface typos as schema violations.

```clojure
(defschemas counter-schemas
  {:counter/delta [:and :int [:>= -10] [:<= 10] [:not= 0]]

   ;; Command — the user-facing :inputs are :counter/delta
   :counter/change
   [:map {:closed true}
    [:command/name      [:= :counter/change]]
    [:command/id        :uuid]
    [:command/timestamp :time/offset-date-time]
    [:counter/delta     :counter/delta]]

   ;; Query — only the base :query/* keys
   :counter/state-query
   [:map {:closed true}
    [:query/name      [:= :counter/state-query]]
    [:query/id        :uuid]
    [:query/timestamp :time/offset-date-time]]

   ;; Event — base :event/* keys come from event-store-v3.interface.schemas
   ;; via the [:and ::schemas/event :counter/changed] check at append time.
   :counter/changed
   [:map [:counter/delta :counter/delta]]

   ;; Read-model state — your own contract for what the reducer produces.
   :counter/state
   [:map {:closed true}
    [:count       :int]
    [:event-count [:and :int [:>= 0]]]]})
```

The `:counter/delta` schema bounds the delta to a non-zero integer in `[-10, 10]`. Out-of-range commands (or a `:delta 0`) are rejected at the command processor with a `Failed Schema Validation` anomaly **before** any event is persisted.

## 3. The read model

A read model is a pure reducer `(state, event) -> state`. The substrate caches the projected state (L1 in-process + L2 LMDB) and applies new events incrementally as they arrive.

```clojure
(rmp/defreadmodel :counter state
  {:events  #{:counter/changed}
   :version 1}
  [state event]
  (-> state
      (update :count       (fnil + 0)   (:counter/delta event))
      (update :event-count (fnil inc 0))))
```

Bumping `:version` invalidates the cache; the next projection re-reads all events from the event store.

## 4. Commands

Commands return events for the event store to persist. Persistence triggers a pubsub publication, which wakes any TUI session subscribed to the event type.

```clojure
(defcommand :counter change
  {:authorized? (constantly true)}
  [context]
  (let [delta (get-in context [:command :counter/delta])]
    {:command-result/events [(es/->event {:type :counter/changed
                                          :body {:counter/delta delta}})]
     :command/result        {:counter/delta delta}}))
```

`(es/->event {:type :body :tags?})` constructs an event with the base fields (`:event/id`, `:event/timestamp`, `:event/tags`, `:event/type`) and merges the body. The event store validates against `[:and ::schemas/event :counter/changed]` at append time, so the `:counter/delta` constraint is checked again on the way in.

## 5. The query

The query does two jobs: it projects the read model into view-data **and returns the screen's presentation as hiccup** under `:tui/hiccup`. The adapter never calls a render function — it reads `:tui/hiccup` (snapshot screens) or per-segment hiccup (stream screens, §9) straight off the query handler's return map. Validating the projected state against your read-model schema catches reducer bugs early — without it, garbage state silently produces a garbage UI.

```clojure
(defquery :counter state-query
  {:authorized?       (constantly true)
   :grain/read-models {:counter/state 1}
   ;; TUI behavior metadata lives in the query's own metadata map
   ;; (the adapter also accepts it on the screen map — see §6).
   :tui/buffer        :alt
   :tui/projection    :snapshot
   :tui/keymap
   {"q" [:session :quit]
    "+" [:command :counter/change {:inputs {:counter/delta 1}}]
    "-" [:command :counter/change {:inputs {:counter/delta -1}}]
    "*" [:command :counter/change {:inputs {:counter/delta 5}}]
    "/" [:command :counter/change {:inputs {:counter/delta -5}}]}}
  [context]
  (let [raw   (rmp/project context :counter/state)
        state (merge {:count 0 :event-count 0} raw)]
    (if (m/validate :counter/state state)
      (let [{:keys [count event-count]} state
            last-query (str (time/now))]
        {:query/result (assoc state :last-query last-query)
         :tui/hiccup
         [:col
          [:text {:bold? true :fg :cyan} "Counter"]
          [:line]
          [:row
           [:text "Count: "]
           [:text {:bold? true :fg :yellow} (str count)]
           [:text {:dim? true} (str "  (events: " event-count ")")]]
          [:text {:dim? true} (str "(query last ran: " last-query ")")]
          [:line]
          [:text {:dim? true} "[+] +1   [-] -1   [*] +5   [/] -5   [q] quit"]]})
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       :cognitect.anomalies/message  "Read-model state failed schema validation"
       :error/explain                (m/explain :counter/state state)})))
```

`:grain/read-models {:counter/state 1}` is the load-bearing key — it tells the TUI adapter to subscribe the session to every event type the read model depends on (`#{:counter/changed}`). When events arrive, the session re-runs this query and re-renders from the freshly returned `:tui/hiccup`.

The hiccup must be computed **purely** from values already in hand — capture time/data in the handler body (as `last-query` does above) and render the captured value; never call `(time/now)` or do I/O from inside the hiccup tree. This matches `grain_tui_demo.clj`, which is the canonical correct example.

## 6. The screen

A screen is a small map naming the query to render plus its inputs. **It carries no render function** — presentation comes from the `:tui/hiccup` the query handler returns (§5). The adapter reads `:tui/...` behavior metadata (`:tui/buffer`, `:tui/projection`, `:tui/keymap`, `:tui/segments`, `:tui/input`, …) from the query metadata, the screen map, or both; the demo declares it on the query (§5) and the screen merely references the query.

```clojure
(def counter-screen
  {:query-id          :counter/state-query
   :inputs            {}
   :grain/read-models {:counter/state 1}
   :tui/buffer        :alt
   :tui/projection    :snapshot
   :tui/keymap
   {"q" [:session :quit]
    "+" [:command :counter/change {:inputs {:counter/delta 1}}]
    "-" [:command :counter/change {:inputs {:counter/delta -1}}]
    "*" [:command :counter/change {:inputs {:counter/delta 5}}]
    "/" [:command :counter/change {:inputs {:counter/delta -5}}]}})
```

A few things worth noting:

- **There is no `:tui/render` key.** The adapter pulls hiccup from the query handler's return value (`:tui/hiccup`). A screen whose query returns no hiccup renders blank and logs `::missing-presentation` — no error is thrown. This is the single most common newcomer mistake; when in doubt, diff against `grain_tui_demo.clj`.
- `:tui/buffer :alt` — claim the canvas; restore prior shell state on exit.
- `:tui/projection :snapshot` — the whole result is rendered on each refresh; the diff renderer figures out which cells actually changed.
- Purity is a property of the **query handler's hiccup-building code**, not of a screen render fn: function of the query result only, no I/O or time inside the hiccup tree. Capture in the handler, render the captured value.
- `:tui/*` behavior metadata may live on the query (§5) or be restated on the screen map (the demo does both verbatim for clarity).
- Every keymap value is a tagged tuple: `[:command name opts?]` or `[:session action opts?]`. The `:inputs` map under `opts` becomes the command's payload (after merging with the base `:command/{name,id,timestamp}` fields).

## 7. Wiring with Integrant

The adapter ships **three** Integrant keys:

- `::tui-system/tui-registry` — the session registry.
- `::tui-system/tui-stdio-transport` — the local terminal transport (used in this section).
- `::tui-system/tui-http-routes` — the v0.8 remote HTTP+SSE transport consumed by the thin `tui-client`. It takes the same config as the stdio key plus a `:query-registry-fn` (so the server can resolve `:tui/*` metadata from the query registry); see `development/src/grain_tui_demo_remote.clj`.

The remote topology is the *same app code* — you swap `::tui-stdio-transport` for `::tui-http-routes` behind a webserver and point `tui-client` at it; screens, queries, and commands are unchanged. This section wires the stdio transport. Compose the keys with your own for the Grain infrastructure:

```clojure
;; ── Grain infrastructure as ig keys ──────────────────────────────────

(defmethod ig/init-key ::tenant-id [_ _]
  (random-uuid))

(defmethod ig/init-key ::cache-dir [_ {:keys [tenant-id]}]
  (str "/tmp/my-tui-app-" tenant-id))

(defmethod ig/halt-key! ::cache-dir [_ ^String dir]
  (let [f (java.io.File. dir)]
    (when (.exists f)
      (run! #(.delete ^java.io.File %) (reverse (file-seq f))))))

(defmethod ig/init-key ::event-pubsub [_ _]
  (pubsub/start {:type :core-async :topic-fn :event/type}))

(defmethod ig/halt-key! ::event-pubsub [_ ps] (pubsub/stop ps))

(defmethod ig/init-key ::event-store [_ {:keys [event-pubsub]}]
  (es/start {:conn {:type :in-memory} :event-pubsub event-pubsub}))

(defmethod ig/halt-key! ::event-store [_ s] (es/stop s))

(defmethod ig/init-key ::cache [_ {:keys [cache-dir]}]
  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "app"})))

(defmethod ig/halt-key! ::cache [_ c]
  (kv/stop c)
  (rmp/l1-clear!))

(defmethod ig/init-key ::base-context
  [_ {:keys [event-store event-pubsub cache tenant-id]}]
  {:event-store      event-store
   :event-pubsub     event-pubsub
   :cache            cache
   :tenant-id        tenant-id
   :command-registry @cp/command-registry*
   :query-registry   @qp/query-registry*})

(defn- enrich-query [ctx]
  (update ctx :query merge {:query/id (random-uuid) :query/timestamp (time/now)}))

(defn- enrich-command [ctx]
  (update ctx :command merge {:command/id (random-uuid) :command/timestamp (time/now)}))

(defmethod ig/init-key ::process-query-fn [_ {:keys [base-context]}]
  (fn [ctx] (qp/process-query (enrich-query (merge base-context ctx)))))

(defmethod ig/init-key ::process-command-fn [_ {:keys [base-context]}]
  (fn [ctx] (cp/process-command (enrich-command (merge base-context ctx)))))

(defmethod ig/init-key ::tenant-resolver [_ {:keys [tenant-id]}]
  (constantly tenant-id))

(defmethod ig/init-key ::user-resolver [_ _]
  (constantly nil))

;; ── System config ─────────────────────────────────────────────────────

(def system-config
  {::tenant-id        {}
   ::cache-dir        {:tenant-id (ig/ref ::tenant-id)}
   ::event-pubsub     {}
   ::event-store      {:event-pubsub (ig/ref ::event-pubsub)}
   ::cache            {:cache-dir    (ig/ref ::cache-dir)}
   ::base-context     {:event-store  (ig/ref ::event-store)
                       :event-pubsub (ig/ref ::event-pubsub)
                       :cache        (ig/ref ::cache)
                       :tenant-id    (ig/ref ::tenant-id)}
   ::process-query-fn   {:base-context (ig/ref ::base-context)}
   ::process-command-fn {:base-context (ig/ref ::base-context)}
   ::tenant-resolver    {:tenant-id    (ig/ref ::tenant-id)}
   ::user-resolver      {}
   ::tui-system/tui-registry {}
   ::tui-system/tui-stdio-transport
   {:registry           (ig/ref ::tui-system/tui-registry)
    :event-pubsub       (ig/ref ::event-pubsub)
    :base-context       (ig/ref ::base-context)
    :default-screen     counter-screen
    :process-query-fn   (ig/ref ::process-query-fn)
    :process-command-fn (ig/ref ::process-command-fn)
    :tenant-resolver    (ig/ref ::tenant-resolver)
    :user-resolver      (ig/ref ::user-resolver)
    :debounce-ms        50}})

(defonce ^:private system* (atom nil))

(defn start! []
  (when @system* (throw (ex-info "Already running" {})))
  (reset! system* (ig/init system-config))
  @system*)

(defn stop!
  ([] (when-let [s @system*] (stop! s)))
  ([system]
   (try (ig/halt! system) (catch Exception _ nil))
   (reset! system* nil)
   :stopped))
```

The halt order is the reverse of the init order, so the transport tears down (which removes itself from the registry and stops the session) before the registry, then the cache, store, pubsub, and finally the cache directory.

## 8. Running it

The transport takes over the controlling terminal — alt-screen, raw mode, hidden cursor — and pumps JLine input into the session. Add a `-main` so you can invoke it directly:

```clojure
(defn -main [& _args]
  (let [system  (start!)
        handle  (get system ::tui-system/tui-stdio-transport)
        ^Thread loop-thread (:loop-thread @(:session handle))]
    (.join loop-thread)
    (stop! system)
    (System/exit 0)))
```

```bash
clojure -M:dev -m my.tui-app
```

Press `q` to quit cleanly. The transport restores your terminal on exit (leaves alt-screen, shows cursor, resets style).

To experiment from a REPL instead of the CLI:

```clojure
(def s (start!))
(stop!)
```

Note: starting from a REPL inside another terminal works, but JLine and the REPL will fight for stdout. Prefer the `-m` invocation for real use.

## 9. Patterns beyond the counter

### Streaming screens

For conversational agents, log tails, and REPL transcripts, use `:tui/projection :stream`. The query handler attaches a hiccup fragment to **each segment** (at the path named by `:tui/segments :hiccup`, default `:tui/hiccup`); the substrate caches and diffs per segment, not once per result. As in §5, there is no render function — the handler builds the hiccup.

```clojure
(defquery :conversation by-id
  {:grain/read-models {:conversation/messages 1}
   :tui/event-tags    {:conversation-id [:query :conversation-id]}}
  [context]
  (let [turns (rmp/project context :conversation/messages
                           {:tags #{[:conversation
                                     (get-in context [:query :conversation-id])]}})]
    {:query/result
     {:turns
      (for [turn turns]
        ;; Each segment carries its pre-rendered hiccup at :tui/hiccup
        ;; (the default path; override via :tui/segments :hiccup).
        (assoc turn :tui/hiccup
               [:turn {:role (:role turn)}
                (for [seg (:segments turn)]
                  (case (:type seg)
                    :text       [:text (:text seg)]
                    :tool-call  [:fold {:summary (str "→ " (:tool seg))}
                                 [:status {:state (:status seg) :label (:tool seg)}]
                                 [:text (:result seg)]]))]))}}))

(def conversation-screen
  {:query-id          :conversation/by-id
   :inputs            {:conversation-id #uuid "..."}
   :grain/read-models {:conversation/messages 1}
   :tui/event-tags    {:conversation-id [:query :conversation-id]}
   :tui/buffer        :main
   :tui/projection    :stream
   :tui/segments      {:items :turns :key :turn/id :hiccup :tui/hiccup}
   :tui/input
   {:command   :conversation/append-user-message
    :prompt    "› "
    :multiline? true}
   :tui/keymap
   {"C-c"   [:command :conversation/interrupt]
    "<esc>" [:session :back]}})
```

(See `development/src/grain_input_demo.clj` for a working version of exactly this pattern.) The substrate iterates `(:turns result)`, reads each turn's pre-rendered hiccup at the `:hiccup` path, caches its CellGrid keyed by `:turn/id` (cache validity is the segment value's hash), and only re-renders turns whose data changed. With `:tui/buffer :main`, new turns append into scrollback like any other terminal output — the terminal owns history, the adapter does not try to manage it.

`:tui/event-tags` filters the per-session subscription so only events tagged with the current conversation's id wake this screen.

The streaming contract is **append-mostly** — earlier segments may flip status or grow text, but the count and ordering of segments before the tail must be stable. Reordering, inserting in the middle, or deleting forces `:snapshot` projection.

### Palettes

A palette is a query-driven filterable list overlay, summoned at the call site:

```clojure
:tui/keymap
{["<leader>" "p"]
 [:session :open-palette
  {:query-id   :sheets/all
   :item-key   :sheet/id
   :item-label :sheet/name
   :on-select  [:command :sheet/execute {:inputs-from-selection :sheet-id}]}]}
```

The substrate handles filter input, list rendering, arrow-key selection, `Enter` to dispatch `:on-select`, `Esc` to dismiss. On selection, the highlighted item's `:item-key` value is bound into the dispatched command via `:inputs-from-selection`. (The filtered list is clipped to the overlay height; it does not yet scroll-follow a selection past the visible window, so keep palette result sets modest or pre-filter the query.)

The query referenced (`:sheets/all`) doesn't need any palette-specific metadata — palettes are summoned, not declared.

### Multi-screen navigation

Push and pop screens to build navigable apps:

```clojure
:tui/keymap
{"<enter>" [:session :push-screen
            {:query-id :invoice/detail
             :inputs   {:invoice-id #uuid "..."}}]
 "<esc>"   [:session :back]}
```

Each push tears down the prior subscription and creates a new one for the new screen's read-models. Pop restores it. Sessions hold a stack of screens for back-navigation.

`:push-screen` takes its `:inputs` **literally** — it does *not* resolve `:inputs-from-selection` (only `:command` actions and palette `:on-select` do that). For selection-driven drill-down, summon a palette whose `:on-select` is a command, or use a command that records the selection and then pushes.

### Lifecycle hooks

`:tui/on-enter` and `:tui/on-exit` fire on screen change. They receive the session-state snapshot (`@session`) and are wrapped in try/catch — a hook *`Exception`* is logged (`::lifecycle-hook-failed`) and swallowed. The catch is `Exception`, not `Throwable`, so an `Error` (e.g. an `AssertionError`) thrown from a hook will still propagate; keep hooks defensive.

```clojure
:tui/on-enter (fn [_session]
                (audit/log :screen-entered {:screen :invoice/detail}))
```

Use them sparingly. Most state should flow through commands and read models, not through hooks.

### Custom cell-rendering elements

When the built-in vocabulary (§7.1–§7.5 of the spec) is insufficient, register a new element via `defelement`:

```clojure
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
       {:width (count cells) :height 1 :cells [cells]}))})

;; Now usable in any hiccup:
[:row [:text "Throughput "]
      [:sparkline {:data req-rate-history :fg :green}]
      [:gap 1]
      [:text (format "%d/s" current-rps)]]
```

The constraints (§7.6.4 of the spec):

- Cannot emit raw bytes — `:render` must return a CellGrid.
- Cannot persist state — render is pure. Animation requires phase to be passed in via attrs.
- Cannot reach outside the bounding box — the substrate validates and clips.
- Cannot define new buffers, projections, or event types.

> **Known gap (v0):** `:stream-stable?` is accepted on the registration map but is currently **inert** — the per-segment stream cache keys purely on each segment value's hash and never consults `:stream-stable?`. Relatedly, there is no substrate-driven animation tick: with `:tui/refresh {:periodic-ms}` deferred (§11), the built-in `:spinner` does not advance on its own — its `:phase` only changes when a domain event re-renders the screen. Don't rely on time-based animation in v0; advance any phase via the data the query returns.

Composition over existing primitives by plain Clojure function is fine — registration is reserved for cases where new primitive cell output is genuinely needed.

## 10. Testing your app

The tui-adapter's own test suite (`components/tui-adapter/test/`) demonstrates the patterns:

- **`cells_test.clj`** — pure-data assertions on CellGrid primitives.
- **`builtins_test.clj`** — snapshot tests for every `:text`, `:row`, `:list`, etc.
- **`integration_test.clj`** — session ↔ stream, session ↔ palette, screen-change lifecycle.
- **`loop_test.clj`** — spawns the actual `run-loop!` thread and pokes it via `input-ch` like the JLine pump does.

For your own app, the same approach works:

```clojure
(defn make-test-session [{:keys [screen process-query-fn process-command-fn]}]
  (let [out (atom [])]
    {:out out
     :session
     (session/make-session
       {:tenant-id          (random-uuid)
        :viewport           {:width 80 :height 24}
        :on-output          (fn [s] (swap! out conj s))
        :default-screen     screen
        :process-query-fn   process-query-fn
        :process-command-fn process-command-fn
        :debounce-ms        0})}))

(deftest counter-renders-and-increments
  (let [calls (atom 0)
        {:keys [session]} (make-test-session
                            {:screen counter-screen
                             ;; Stub query must return :tui/hiccup, same as
                             ;; a real handler — otherwise the screen renders
                             ;; blank (no render fn exists; see §5/§6).
                             :process-query-fn
                             (fn [_] {:query/result {:count 0 :event-count 0}
                                      :tui/hiccup   [:text "Count: 0"]})
                             :process-command-fn (fn [_] (swap! calls inc) :ok)})]
    (session/render-frame! session)
    (#'session/dispatch-key! session {:type :key :key "+"})
    (is (= 1 @calls))))
```

`:user-id` and `:event-pubsub` are accepted but optional — omit them in tests (no pubsub means no live re-render subscription, which is what you want for deterministic dispatch tests). The `make-session` source docstring currently lists them under "Required"; that's a stale label, not an enforced constraint.

Two important rules:

1. **Assert on emitted CellGrids, not ANSI bytes.** Bytes are environment-dependent (color depth, capability negotiation); CellGrids are pure data. Capture bytes only when you specifically need to verify terminal-restore on shutdown.
2. **For loop-driven tests, spawn `run-loop!` on a thread and push events via `input-ch`.** Direct dispatch tests bypass the loop's batching, ordering, and backpressure semantics — and the substrate's own `loop_test.clj` exists precisely because direct-dispatch tests missed real bugs in those areas.

## 11. Operational notes

### What's deferred (post-v0)

- **SSH transport** — for now, stdio only. SSH lands when an SSH server is wired through the existing transport contract (Apache MINA SSHD is the planned backend).
- **Telnet transport** — useful for REPL-driven development but trivially insecure; not yet implemented.
- **Mouse, bracketed paste** — the input parser handles paste sequences without crashing but doesn't deliver them as logical events yet; mouse is unsupported.
- **`:tui/refresh {:periodic-ms n}`** — currently parsed-and-warned but ignored. Queries that want updates must declare `:grain/read-models`.
- **Visible-window heuristics** — uses a simple "last N segments fit" rule for streaming projections. Real workloads may want time-since-modified or similar.
- **Multiple stacked overlays** — single overlay per session in v0.

### Capability detection

The transport reads `TERM` and `COLORTERM` to choose color depth (truecolor / 256 / 16 / mono). Cells use the same color and styling vocabulary regardless of capability; the ANSI emitter quantizes at byte-emit time. Tests assert on cell data, never on bytes, precisely so they're capability-independent.

### Render performance

The diff renderer emits the minimum byte sequence per frame — only changed cells trigger output, and SGR is only re-emitted when the style transition requires it. For streaming projections, only segments inside the visible window participate in the diff; off-window segments are not maintained.

Holding down a key under autorepeat is safe — the input channel uses a sliding-buffer (1024) so the JLine pump can never wedge, and the session loop dispatches every queued event before rendering once (lossy drain was a real bug; the loop-test catches regressions).

### Errors

Render exceptions are caught by `safe-render-frame!` — the loop survives, an error frame is painted with the failure message, and the stack trace goes to STDERR. Because JLine owns STDOUT, any `println` or mulog console publisher will trample the TUI; route diagnostics to STDERR or a log file instead.

Query failures (thrown exceptions OR returned Cognitect anomalies) render an error frame with a `Press <esc> to go back, q to quit.` hint. The session is not terminated; users can recover.

Command failures are **not** auto-toasted. The substrate logs them via mulog (e.g. `::input-submit-command-failed`) and otherwise leaves the screen as-is. Toasts are an *opt-in* affordance you raise explicitly — `[:session :toast {:message "…" :level :error :ttl-ms 3000}]` — typically from the command's failure path. They render top-right, auto-dismiss after `:ttl-ms`, and (because a screen change does not clear the overlay) persist across screen pushes, so an explicitly-raised failure toast from screen A is still visible on screen B.

### Multi-tenancy

Tenant binding is **immutable for the session lifetime**. Switching tenants requires reconnect. The `:tenant-resolver` Integrant component decides the tenant at connect time; for stdio it's typically a constant or derived from the OS user.

### Where to look in the source

| What                              | Where                                                          |
| --------------------------------- | -------------------------------------------------------------- |
| Public API                        | `components/tui-adapter/src/.../interface.clj`                 |
| Session loop, screen lifecycle    | `components/tui-adapter/src/.../session.clj`                   |
| Subscription pipeline             | `components/tui-adapter/src/.../subscription.clj`              |
| Hiccup → CellGrid layout          | `components/tui-adapter/src/.../layout.clj`                    |
| Built-in elements                 | `components/tui-adapter/src/.../builtins.clj`                  |
| Diff + ANSI emit                  | `components/tui-adapter/src/.../diff.clj` + `ansi.clj`         |
| Stdio transport                   | `components/tui-adapter/src/.../transport/stdio.clj`           |
| Integrant components              | `components/tui-adapter/src/.../system.clj`                    |
| Worked demo                       | `development/src/grain_tui_demo.clj`                           |
| Spec (working document)           | `grain-tui-adapter-spec-v0.6.md` (root of this repo's working notes) |
