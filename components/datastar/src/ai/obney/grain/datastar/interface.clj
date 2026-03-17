(ns ai.obney.grain.datastar.interface
  (:require [ai.obney.grain.datastar.core :as core]))

(defn stream-view
  "Interceptor factory. Polls query, streams :datastar/hiccup via SSE."
  [context query-name opts]
  (core/stream-view context query-name opts))

(defn shim-page
  "Interceptor factory. HTML shell with Datastar JS."
  [opts]
  (core/shim-page opts))

(defn action-handler
  "Interceptor factory. Processes commands from Datastar signals."
  [context opts]
  (core/action-handler context opts))

(defn render-html
  "Convert hiccup to HTML string."
  [hiccup]
  (core/render-html hiccup))

(defn patch-elements
  "Format patch-elements SSE event."
  [html opts]
  (core/patch-elements html opts))

(defn patch-signals
  "Format patch-signals SSE event."
  [signals opts]
  (core/patch-signals signals opts))

(def parse-datastar-signals
  "Interceptor that parses Datastar signals from GET query params or POST body.
   Auto-included by `routes` on all generated routes."
  core/parse-datastar-signals)

(defn routes
  "Generate Pedestal routes from query registry metadata."
  ([context] (core/routes context))
  ([context overrides] (core/routes context overrides)))
