(ns ai.obney.grain.tui-adapter.stream
  "Streaming-projection (`:tui/projection :stream`) machinery.

   Per §6.3:
     - Iterates segments via `(:items segments-spec)`.
     - Each segment carries a stable `:key` for identity.
     - The substrate caches per-segment CellGrids keyed by the segment id
       and an attrs-hash; cache hit when stream-stable elements + same hash.
     - The visible window is 'last N segments that fit'; the substrate may
       evict cache entries for off-window segments.
     - Append-mostly contract: §13.5 — out-of-order/dropped segments log a
       warning and trigger a full visible-window re-render.

   This namespace is pure: given a `stream-state`, a query result, and a
   segments spec + render fn, it returns a new `stream-state`."
  (:require [com.brunobonacci.mulog :as u]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.layout :as layout]))

;; ─────────────────────────────────────────────────────────────────────
;; State shape
;; ─────────────────────────────────────────────────────────────────────

(defn empty-stream-state []
  {:segment-cache  {}     ; {segment-key {:hash h :grid CellGrid :height n}}
   :visible-window []     ; vector of segment-keys, oldest→newest in slice
   :last-keys      []     ; ordered keys from prior result, for violation detection
   :tail-cursor   {:row 0 :col 0}
   :input-area    {:value "" :cursor 0 :height 0}})

;; ─────────────────────────────────────────────────────────────────────
;; Segment extraction
;; ─────────────────────────────────────────────────────────────────────

(defn extract-segments
  "Given a query result and a segments spec `{:items :key}`, return a
   sequence of segments. `:items` may be a keyword (path), a vector
   (get-in path), or a function."
  [result {:keys [items]}]
  (let [items-fn (cond
                   (keyword? items) #(get % items)
                   (vector? items)  #(get-in % items)
                   (fn? items)      items
                   :else            (constantly []))]
    (vec (items-fn result))))

(defn segment-key
  "Extract `:key` from a single segment. Spec value is a keyword path."
  [{key-path :key} segment]
  (cond
    (keyword? key-path) (get segment key-path)
    (vector? key-path)  (get-in segment key-path)
    (fn? key-path)      (key-path segment)))

;; ─────────────────────────────────────────────────────────────────────
;; Visible window — last N that fit
;; ─────────────────────────────────────────────────────────────────────

(defn compute-visible-window
  "Walk segments newest→oldest accumulating heights until the budget runs
   out; return the slice (oldest→newest) that fits in `available-height`.

   `segments` is a vec of segment maps; `height-fn` is `(seg) → :height`."
  [segments available-height height-fn]
  (loop [acc    []
         budget available-height
         remaining (reverse segments)]
    (cond
      (zero? budget)         acc
      (empty? remaining)     acc
      :else
      (let [s   (first remaining)
            h   (or (height-fn s) 1)]
        (if (> h budget)
          acc
          (recur (cons s acc) (- budget h) (rest remaining)))))))

;; ─────────────────────────────────────────────────────────────────────
;; Per-segment cache
;; ─────────────────────────────────────────────────────────────────────

(defn render-segment
  "Render a single segment: returns `{:hash h :grid CellGrid :height n}`.

   `render-fn` is the screen's `:tui/render` (returns hiccup for the
   segment). The grid is rendered into a box of the segment's preferred
   height — for v0 we use `1` (matches the simple last-N policy)."
  [render-fn segment {:keys [width]}]
  (let [hiccup (render-fn segment)
        grid   (layout/render-element hiccup {:width width :height 1})]
    {:hash   (hash segment)
     :grid   grid
     :height (:height grid)}))

;; ─────────────────────────────────────────────────────────────────────
;; Update cache for the current frame
;; ─────────────────────────────────────────────────────────────────────

(defn refresh-cache
  "Given the prior cache, segments-with-keys (vec of `[key seg]`), and a
   render-fn, return an updated cache that contains entries for every
   segment in the visible window. Reuses cached entries when the
   per-segment hash is unchanged. Evicts entries for keys not present in
   the visible window."
  [prior-cache visible-pairs render-fn box]
  (let [visible-keys (set (map first visible-pairs))
        kept         (select-keys prior-cache visible-keys)]
    (reduce
      (fn [cache [k seg]]
        (let [existing (get kept k)
              h        (hash seg)]
          (if (and existing (= h (:hash existing)))
            cache
            (assoc cache k (render-segment render-fn seg box)))))
      kept
      visible-pairs)))

;; ─────────────────────────────────────────────────────────────────────
;; Violation detection (§13.5)
;; ─────────────────────────────────────────────────────────────────────

(defn detect-violation
  "Compare prior keys to the new key sequence. The contract is
   append-mostly at the tail: the prior key list must be a *prefix* of
   the new key list. Returns `:reorder`, `:drop`, or nil for OK."
  [prior-keys new-keys]
  (let [prefix-len (count prior-keys)]
    (cond
      (and (zero? prefix-len) (seq new-keys))
      nil   ; first frame

      (> prefix-len (count new-keys))
      :drop

      (not= prior-keys (take prefix-len new-keys))
      :reorder

      :else
      nil)))

;; ─────────────────────────────────────────────────────────────────────
;; Top-level: produce a frame's CellGrid for a stream screen
;; ─────────────────────────────────────────────────────────────────────

(defn render-stream
  "End-to-end: given the prior `stream-state`, a fresh query `result`,
   the `:tui/segments` spec, the `:tui/render` fn, and a `box`
   `{:width w :height h}`, return `{:state new-stream-state :grid CellGrid}`.

   On contract violation (§13.5), logs a warning and returns a
   full-window re-render."
  [prior-state result {:keys [items key]} render-fn {:keys [width height] :as box}]
  (let [segments     (extract-segments result {:items items})
        keys-vec     (mapv #(segment-key {:key key} %) segments)
        violation    (detect-violation (:last-keys prior-state) keys-vec)
        _            (when violation
                       (u/log ::stream-projection-violation :kind violation))
        ;; Build [key seg] pairs aligned to the keys-vec.
        pairs        (mapv (fn [k s] [k s]) keys-vec segments)
        ;; Visible window: last-N that fit in available height. We use a
        ;; simple per-segment height of 1 for MVP.
        visible      (compute-visible-window pairs height (constantly 1))
        ;; If there was a violation, force re-render of the visible window
        ;; by clearing the prior cache for those keys.
        cache-input  (if violation
                       (apply dissoc (:segment-cache prior-state) (map first visible))
                       (:segment-cache prior-state))
        new-cache    (refresh-cache cache-input visible render-fn {:width width})
        ordered      (mapv (fn [[k _]] (:grid (get new-cache k))) visible)
        composed     (if (seq ordered)
                       (let [stacked (apply cells/stack ordered)
                             pad-h   (max 0 (- height (:height stacked)))]
                         (if (pos? pad-h)
                           (cells/stack (cells/blank width pad-h) stacked)
                           stacked))
                       (cells/blank width height))
        new-state    (assoc prior-state
                            :segment-cache  new-cache
                            :visible-window (mapv first visible)
                            :last-keys      keys-vec)]
    {:state new-state
     :grid  (cells/clip composed box)}))
