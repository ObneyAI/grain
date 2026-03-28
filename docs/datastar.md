# Datastar (Reactive UI)

Grain integrates with [Datastar](https://data-star.dev/) for building reactive server-rendered UIs. Queries that return `:datastar/hiccup` are streamed to the browser over SSE — the server re-renders when domain events fire and Datastar patches the DOM.

```clojure
(defquery :example counter-view
  {:authorized?       (constantly true)
   :datastar/path     "/counters"
   :datastar/title    "Counters"
   :grain/read-models {:example/counters 1}}
  [context]
  (let [counters (rmp/project context :example/counters)]
    {:query/result counters
     :datastar/hiccup [:div#app
                        (for [[id c] counters]
                          [:p (str (:name c) ": " (:value c))])]}))
```

## Interceptors

The Datastar component provides three Pedestal interceptor factories:

- **`stream-view`** — Streams `:datastar/hiccup` from a query via SSE. Supports three modes: event-driven (re-renders on domain events), polling (fixed FPS), or one-shot (render once and close).
- **`shim-page`** — Serves an HTML shell that loads the Datastar JS client and connects to a stream endpoint.
- **`action-handler`** — Receives commands from Datastar signals (browser actions), executes them through the command processor, and streams back results or errors.

## Route Generation

Auto-generate Pedestal routes from query registry metadata:

```clojure
(require '[ai.obney.grain.datastar.interface :as ds])

(ds/routes context) ;; scans for queries with :datastar/path
```

This creates paired routes for each annotated query — an HTML page route and an SSE stream route — so there's no manual route wiring.

## UI Flow

```
                    ┌───────────────────────────────────────────┐
  Browser <── SSE ──┤  stream-view ──> Query Processor          │
  (Datastar)        │       ^              │                    │
                    │       │          projections               │
                    │   domain events      │                    │
                    │   (pub/sub)     Read Model Processor       │
                    │                      │                    │
                    │                  Event Store               │
                    │                      ^                    │
  Browser ── POST ──┤  action-handler ──> Command Processor     │
  (signals)         └───────────────────────────────────────────┘
```
