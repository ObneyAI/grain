(ns ai.obney.grain.tui-adapter.subscription
  "Per-screen pubsub subscription pipeline.

   Direct port of `datastar/core.clj:319-362`:

     resolve-event-types-from-read-models  (lines 319-327)
     resolve-event-tags                    (lines 329-341)
     subscribe-to-events                   (lines 343-354)
     drain-channel                         (lines 356-362)

   The TUI adapter uses these to set up a per-screen subscription on
   Grain's existing pubsub keyed off `:grain/read-models` from the
   query metadata, optionally filtered by `:tui/event-tags`.

   We deliberately re-implement these (rather than depend on the
   `datastar` sibling component) so the two adapters remain independent.
   The functions are small; if a third adapter appears, factor them out then."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(def ^:const default-buffer-size 64)

;; ─────────────────────────────────────────────────────────────────────
;; Read-model → event-type resolution
;; ─────────────────────────────────────────────────────────────────────

(defn resolve-event-types-from-read-models
  "Returns the union of `:events` sets from the named read models in the
   global registry. `read-models` is a map of `{rm-name version, ...}` —
   the version is declarative, multiple versions can co-exist.

   Mirrors datastar/core.clj:319-327."
  [read-models]
  (let [registry @rmp/read-model-registry*]
    (into #{}
          (mapcat (fn [[rm-name _version]]
                    (:events (get registry rm-name))))
          read-models)))

;; ─────────────────────────────────────────────────────────────────────
;; Event-tag predicate
;; ─────────────────────────────────────────────────────────────────────

(defn resolve-event-tags
  "Resolves a `{:tag-key :query-key-or-path}` map against `query-context`
   into a predicate `(fn [event] -> boolean)` suitable for a channel
   transducer. Bare keyword values resolve from `[:query k]`; vectors
   are full `get-in` paths.

   Mirrors datastar/core.clj:329-341."
  [event-tags query-context]
  (let [resolved (into #{}
                       (map (fn [[tag-key path]]
                              [tag-key (if (vector? path)
                                         (get-in query-context path)
                                         (get-in query-context [:query path]))]))
                       event-tags)]
    (fn [event]
      (set/subset? resolved (:event/tags event)))))

;; ─────────────────────────────────────────────────────────────────────
;; Subscribe to events
;; ─────────────────────────────────────────────────────────────────────

(defn subscribe-to-events
  "Creates a sliding-buffer channel and subscribes it to each `event-type`
   via `pubsub`. When `event-filter-fn` is non-nil, applies it as a
   transducer on the channel. Returns the subscription channel.

   Mirrors datastar/core.clj:343-354."
  [event-pubsub event-types event-filter-fn]
  (let [buf      (async/sliding-buffer default-buffer-size)
        sub-chan (if event-filter-fn
                   (async/chan buf (filter event-filter-fn))
                   (async/chan buf))]
    (doseq [event-type event-types]
      (pubsub/sub event-pubsub {:topic event-type :sub-chan sub-chan}))
    sub-chan))

;; ─────────────────────────────────────────────────────────────────────
;; Channel drain
;; ─────────────────────────────────────────────────────────────────────

(defn drain-channel
  "Non-blocking drain of all buffered messages on `ch`. Returns count
   drained.

   Mirrors datastar/core.clj:356-362."
  [ch]
  (loop [n 0]
    (if (async/poll! ch)
      (recur (inc n))
      n)))

;; ─────────────────────────────────────────────────────────────────────
;; Convenience: build subscription for a screen
;; ─────────────────────────────────────────────────────────────────────

(defn subscribe-screen
  "End-to-end helper: given a `screen` map with `:grain/read-models` and
   optional `:tui/event-tags`, plus the session's `query-context`, return
   a subscription channel, or nil if there are no read-models declared.

   The session loop alts!! over this channel along with input/resize."
  [event-pubsub screen query-context]
  (let [read-models (:grain/read-models screen)
        event-types (when (seq read-models)
                      (resolve-event-types-from-read-models read-models))]
    (when (seq event-types)
      (let [event-tags (:tui/event-tags screen)
            filter-fn  (when (seq event-tags)
                         (resolve-event-tags event-tags query-context))]
        (subscribe-to-events event-pubsub event-types filter-fn)))))
