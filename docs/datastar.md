# Datastar pages and streams

Return `:datastar/hiccup` from a query to render a page or patch it over
server-sent events (SSE). This query pages through the [keyed counter list](core-concepts.md#read-models).
For interactive form controls, see the [Datastar UI guide](datastar-ui.md).

```clojure
(defschemas page-inputs {:example/counter-view [:map]})

(defquery :example counter-view
  {:authorized?       (constantly true)
   :datastar/path     "/counters"
   :datastar/title    "Counters"
   :grain/read-models {:example/counter-list 1}}
  [context]
  (let [page (rmp/page context :example/counter-list {:index :name :limit 25})]
    {:query/result page
     :datastar/hiccup [:div#app
                       (for [{counter :value} (:entries page)]
                         [:p (str (:name counter) ": " (:value counter))])]}))
```

## Route generation

Load the query declarations, then call `ds/routes` with the application's Grain
context. Each query with `:datastar/path` gets a GET page and GET/POST stream
routes. For `/counters` the stream is `/counters/__stream`. Path parameters
such as `/customers/:id` are resolved when the page opens its stream.

```clojure
(require '[ai.obney.grain.datastar.interface :as ds])

(ds/routes context)
(ds/routes context {:example/counter-view {:datastar/title "All counters"}}
           {:datastar/shim-opts {:html-attrs {:lang "en"}}
            :datastar/action-path "/actions"
            :datastar/auth-redirect {:unauthenticated "/sign-in"
                                     :unauthorized "/dashboard"}})
```

The second argument overrides metadata by query name. The third sets shared
defaults. Shim options include `:head`, `:html-attrs` and `:datastar-url`.
Page-specific shim options, action paths and heartbeat intervals take precedence.

Mount `(ds/action-handler context {})` on the shared POST command route.
The handler decodes the command, checks authorization, dispatches it and streams
the result. A Pedestal `:leave` interceptor can inspect `:grain/command` and
`:grain/command-result` to set response headers after a successful command.

### Authorization and gates

Command and query adapters require `:authorized?` to return true. With
`:datastar/auth-redirect` configured, a protected page also checks the predicate
with `{:auth-claims claims}`. A failed check redirects to `:unauthenticated`
without claims or `:unauthorized` with claims. Queries whose predicate accepts
`{}` skip this redirect.

For an additional prerequisite, set
`:datastar/gate {:check shift-ready? :redirect "/shift-start"}`. The check
receives the system context merged with `:grain/additional-context` and the
request’s `:query-params`. On failure, `:redirect` may be a path or a function
of that context. Its fallback is the auth redirect’s `:unauthorized` path.

Generated routes parse signals first, then apply the auth redirect, gate,
per-query interceptors and finally the page or stream handler.

## Stream behaviour

- **Event-driven.** With `:event-pubsub` and subscribed event types, render
  when a matching event arrives or the client changes query inputs.
- **Polling.** Without event-driven delivery, a positive `:datastar/fps`
  reruns the query at that rate.
- **One-shot.** With neither subscriptions nor a positive rate, render once and close.

Queries patch elements. To publish signals too, set `:datastar/emit-signals? true`
and return `:datastar/signals` alongside the Hiccup. For example, a page that
handles a redirect signal can return `{:__redirect "/dashboard"}` in that map.

### Connection reuse

Event-driven streams identify a session by user ID, query name and page nonce.
A matching GET or POST updates the existing stream with the new signals.
GET reuse requires a nonce. Each page load gets its own nonce, so two tabs can
show different filters. Disconnecting releases subscriptions and tailer interest
and discards unfinished renders. A custom pub/sub must implement `unsub` for
one topic/channel pair while leaving the channel open for other subscriptions.

### Multi-node live updates

On one node, the event store can publish directly to the local `:event-pubsub`.
Across nodes, also supply `:event-tailer`. The tailer tracks interest by tenant
and event type, reads matching events from the shared store and republishes
locally. A stream can stay on any node. It deduplicates stored events by
`[:grain/tenant-id :event/id]`. Manual messages without an event ID bypass deduplication.

## Event Subscription Filters

An event-driven view listens to the event types declared by its
`:grain/read-models`. For a composed query, `(rmp/query-models definition)`
returns the subscription map. Use exclusions and tag filters to narrow what
wakes the view:

```clojure
{:grain/read-models {:app/items 1 :app/users 1}
 :datastar/exclude-events #{:app/item-viewed :app/user-seen}
 :datastar/event-tags [{:org :org-id :user :owner-id}
                       {:org :org-id :user :assignee-id}]}
```

Here a trigger must match the organization and either the owner or the assignee.
A keyword reads a value from the current query. A vector such as
`[:auth-claims :user-id]` reads a path in the stream context. A missing value
prevents that group from matching. A signal update changes the filter on the
existing stream.

| Tag filter | Matches |
| --- | --- |
| Omitted, `nil` or `{}` | Any tags. |
| One map | All tags in the map. |
| Vector of nonempty maps | Any matching group, once per event. |
| `[]` | No events. |

Supply the stream's tenant in `:tenant-id` or `[:auth-claims :tenant-id]` to match only that tenant's events. Exclusions remove a type from all
subscriptions on the view. Excluding every type leaves the stream available
for signals and refresh-key changes. Read models still fold excluded events,
so later renders can show their effects. To restrict which events a keyed
model folds, use an [event scope](../components/read-model-processor-v2/README.md#event-scopes).

For a manually wired `stream-view`, use `:event-types`, `:exclude-events` and
`:event-tags`.

### Refresh when a value changes without an event

For a view that depends on the current date, set
`:datastar/refresh-key (fn [_] (java.time.LocalDate/now))` on the query.
Datastar samples the key when the event loop wakes, including after 30 seconds
idle. A changed key reruns the authorized query. Keep the function cheap and
use the same time zone as the view.

## SSE heartbeat

The default keep-alive interval is 10 seconds. If small patches arrive late
behind an HTTP/2 proxy, compare the direct and proxied stream. A shorter
`:datastar/heartbeat-delay` can flush buffered patches sooner at the cost of
more keep-alive traffic. Set it globally in route defaults or on an individual query.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Adapter denies a request | `::missing-authorized-predicate` identifies a missing `:authorized?`. Check the predicate against the request context. |
| Query parameters arrive as strings | `::missing-query-schema` means the adapter could not find the named input schema for coercion. Register it under the query keyword. |
| A command’s events fail validation | Inspect the returned `:error/explain` for the rejected fields. |

## Query Metadata Reference

| Key | Description | Default |
| --- | --- | --- |
| `:datastar/path` | Route path, supports Pedestal path params (e.g. `/events/:event-id`) | — |
| `:datastar/action-path` | Shared command action endpoint emitted into a Datastar page | `/actions` |
| `:datastar/title` | HTML `<title>` in the shim page | `"Grain App"` |
| `:datastar/fps` | Polling rate (ignored in event-driven mode; `0` means one-shot) | `0` |
| `:datastar/refresh-key` | Function of context that triggers a render when its value changes. | — |
| `:datastar/debounce-ms` | Debounce before re-render after event | `50` |
| `:datastar/heartbeat-delay` | Seconds between SSE keep-alives. Accepts a route default or a per-query override. | `10` |
| `:datastar/event-tags` | Groups of tags that wake the stream. See [event subscription filters](#event-subscription-filters). | — |
| `:datastar/exclude-events` | Event types to ignore as refresh triggers. | `#{}` |
| `:datastar/shim-opts` | Per-query override for shim page options | — |
| `:datastar/interceptors` | Additional Pedestal interceptors for this query | `[]` |
| `:datastar/gate` | Context-dependent gate `{:check fn :redirect path-or-fn}` | — |
| `:datastar/emit-signals?` | Allow the query to push a one-shot `:datastar/signals` patch alongside its hiccup | `false` |
| `:grain/read-models` | `{model-name version}` subscriptions. Enables event-driven mode with `:event-pubsub` and declared event types. | — |
| `:authorized?` | Auth predicate `(fn [ctx] boolean)` | — |
