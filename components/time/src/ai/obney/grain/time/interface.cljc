(ns ai.obney.grain.time.interface
  (:require
   [tick.core :as t])) ; optional for CLJS specifics

(defn now []
  ;; Returns a tick Instant with timezone offset (UTC by default in CLJS)
  (t/offset-date-time (t/instant)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn now-from-str [s]
  ;; Parse an ISO‑8601 timestamp string into an offset-date-time
  (t/offset-date-time (t/instant s)))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn now-from-ms [ms]
  ;; Create from epoch milliseconds
  (t/offset-date-time (t/instant ms)))
