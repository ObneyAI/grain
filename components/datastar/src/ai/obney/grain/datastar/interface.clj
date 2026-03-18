(ns ai.obney.grain.datastar.interface
  "Public API for Grain's Datastar integration.

   Most consumers only need `routes` (auto-generates shim + stream routes from
   query metadata) and `action-handler` (processes commands from Datastar @post).
   The lower-level functions (`stream-view`, `shim-page`, `patch-elements`,
   `patch-signals`) are available for custom route wiring.

   See `ai.obney.grain.datastar.core` namespace docstring for architecture overview."
  (:require [ai.obney.grain.datastar.core :as core]))

(defn stream-view
  "Interceptor factory. Opens an SSE that streams `:datastar/hiccup` from a query.

   Three modes, selected automatically:
   - **Event-driven**: when `:event-types` is non-empty AND `:event-pubsub` is in
     context — re-renders only when relevant domain events fire. POST requests
     update the existing SSE's signals without opening a new connection.
   - **Polling**: when `:fps` > 0 — re-renders on a timer.
   - **One-shot**: when `:fps` is 0 or nil — renders once and closes.

   opts keys:
     :fps            — frames per second for polling mode (default 30, ignored in event-driven)
     :heartbeat-delay — SSE heartbeat interval in seconds (default 10)
     :event-types    — set of event type keywords to subscribe to
     :debounce-ms    — ms to wait before re-rendering after an event (default 50)
     :event-tags     — map of {:tag-key :query-key} for tag-based event filtering"
  [context query-name opts]
  (core/stream-view context query-name opts))

(defn shim-page
  "Interceptor factory. Returns an HTML shell that boots Datastar JS and opens
   an SSE connection to `stream-path` via `@get` (or `@post`).

   Generates a unique nonce per page load so multiple tabs get independent SSE
   streams. For `@get`, the nonce is appended as a query param; for `@post`, it's
   emitted as a Datastar signal so it's included in every request body.

   opts keys:
     :title         — HTML <title> (default \"Grain App\")
     :stream-path   — SSE endpoint path (e.g., \"/my-page/stream\")
     :stream-method — \"get\" (default) or \"post\"
     :head          — extra <head> hiccup or (fn [] hiccup)
     :body          — extra <body> hiccup (rendered before the #app div)
     :html-attrs    — attributes for the <html> element
     :datastar-url  — CDN URL for Datastar JS (default RC.7)"
  [opts]
  (core/shim-page opts))

(defn action-handler
  "Interceptor factory. Parses a command from Datastar signals (POST body),
   executes it via the command processor, and returns the result as an SSE event.

   On success: sends `datastar-patch-signals` with `:datastar/signals` from the
   command result (e.g., `{:__toast \"Saved\"}`).
   On failure: sends `datastar-patch-signals` with `:error` and optional `:fieldErrors`.
   On unauthorized: sends `datastar-patch-signals` with `{:error \"Unauthorized\"}`."
  [context opts]
  (core/action-handler context opts))

(defn render-html
  "Render a hiccup data structure to an HTML string."
  [hiccup]
  (core/render-html hiccup))

(defn patch-elements
  "Build a `datastar-patch-elements` SSE event map from an HTML string.

   opts keys:
     :selector — CSS selector to patch (default: Datastar's default, usually #app)
     :mode     — :inner (default) or :outer (replaces the element itself)"
  [html opts]
  (core/patch-elements html opts))

(defn patch-signals
  "Build a `datastar-patch-signals` SSE event map from a signal map.

   opts keys:
     :only-if-missing — when true, only sets signals that don't already exist on the client"
  [signals opts]
  (core/patch-signals signals opts))

(def parse-datastar-signals
  "Interceptor that extracts Datastar signals from the request and merges them
   into `:query-params`. Handles both GET (`?datastar={...}` query param) and
   POST (JSON body, wrapped or flat). Auto-included by `routes` on all generated
   routes — only needed when wiring routes manually."
  core/parse-datastar-signals)

(defn routes
  "Scan the query registry for entries with `:datastar/path` metadata and generate
   Pedestal routes. Each query produces three routes:

     1. GET  `/path`        — Shim page (HTML shell that boots Datastar)
     2. GET  `/path/stream` — Initial SSE connection (opened by Datastar's `@get`)
     3. POST `/path/stream` — Signal updates on existing SSE (Datastar's `@post`)

   Optional `overrides` map merges additional metadata per query-name, e.g.:
     {::my-query {:datastar/fps 2 :datastar/interceptors [auth-interceptor]}}"
  ([context] (core/routes context))
  ([context overrides] (core/routes context overrides)))
