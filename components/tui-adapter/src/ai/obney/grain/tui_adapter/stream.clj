(ns ai.obney.grain.tui-adapter.stream
  "Streaming-projection (`:tui/projection :stream`) machinery.

   Per spec v0.7 §6.3 and §6.4:
     - Iterates segments at `(:items segments-spec)` of the handler return.
     - Each segment carries a stable identity at `(:key segments-spec)`
       and pre-rendered hiccup at `(:hiccup segments-spec)` (default
       `:tui/hiccup`). The handler builds the hiccup; the substrate reads
       it. No render function is threaded through.
     - The substrate caches per-segment CellGrids keyed by the segment id
       and an attrs-hash; cache hit when stream-stable elements + same hash.
     - The visible window is 'last N segments that fit'; the substrate may
       evict cache entries for off-window segments.
     - Append-mostly contract: §13.5 — out-of-order/dropped segments log a
       warning and trigger a full visible-window re-render.

   This namespace is pure: given a `stream-state`, a query result, and a
   segments spec, it returns a new `stream-state`."
  (:require [com.brunobonacci.mulog :as u]
            [ai.obney.grain.tui-adapter.ansi :as ansi]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.diff :as diff]
            [ai.obney.grain.tui-adapter.layout :as layout]
            [clojure.string :as string]))

;; ─────────────────────────────────────────────────────────────────────
;; State shape
;; ─────────────────────────────────────────────────────────────────────

(defn empty-stream-state []
  {:segment-cache  {}     ; {segment-key {:hash h :grid CellGrid :height n}}
   :visible-window []     ; vector of segment-keys, oldest→newest in slice
   :last-keys      []     ; ordered keys from prior result, for violation detection
   :tail-cursor   {:row 0 :col 0}
   :input-area    {:value "" :cursor 0 :height 0}
   ;; Main-buffer append mode (spec §6.3 transcript pattern):
   ;;   :emitted-keys     — keys already written to scrollback; we only
   ;;                       emit segments whose key is not in here.
   ;;   :intro-printed?   — whether the screen's :tui/hiccup intro has
   ;;                       been emitted yet (once per screen).
   :emitted-keys   []
   :intro-printed? false})

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

(defn- wrap-visual-line-count
  [width s]
  (if (or (zero? width) (empty? s))
    1
    (long (Math/ceil (/ (count s) (double width))))))

(defn- text-content
  [hiccup]
  (when (and (vector? hiccup) (= :text (first hiccup)))
    (let [[_ maybe-attrs & children] hiccup
          attrs (when (map? maybe-attrs) maybe-attrs)
          children (if attrs children (cons maybe-attrs children))]
      (or (:text attrs)
          (apply str (filter string? children))))))

(defn- text-visual-height
  [width s]
  (->> (string/split (or s "") #"\n" -1)
       (map #(wrap-visual-line-count width %))
       (reduce + 0)
       (max 1)))

(defn- segment-height
  [segment hiccup-path width]
  (or (:height segment)
      (when-let [s (text-content (get segment hiccup-path))]
        (text-visual-height width s))
      1))

;; ─────────────────────────────────────────────────────────────────────
;; Per-segment cache
;; ─────────────────────────────────────────────────────────────────────

(defn render-segment
  "Render a single segment: returns `{:hash h :grid CellGrid :height n}`.

   `hiccup-path` is the keyword at which the segment carries its
   pre-rendered hiccup (declared by `(:hiccup segments-spec)`, defaults to
   `:tui/hiccup`). A segment missing hiccup produces a blank row and logs
   `::missing-segment-hiccup`. The grid is rendered into a box of the
   segment's preferred height — for v0 we use `1` (matches the simple
   last-N policy)."
  [segment hiccup-path {:keys [width]}]
  (let [hiccup (get segment hiccup-path)
        _      (when (nil? hiccup)
                 (u/log ::missing-segment-hiccup :segment-key (:id segment)))
        height (segment-height segment hiccup-path width)
        grid   (layout/render-element (or hiccup [:text {:text ""}])
                                      {:width width :height height})]
    {:hash   (hash segment)
     :grid   grid
     :height (:height grid)}))

;; ─────────────────────────────────────────────────────────────────────
;; Update cache for the current frame
;; ─────────────────────────────────────────────────────────────────────

(defn refresh-cache
  "Given the prior cache, segments-with-keys (vec of `[key seg]`), and the
   `hiccup-path` keyword (where each segment carries its hiccup), return an
   updated cache that contains entries for every segment in the visible
   window. Reuses cached entries when the per-segment hash is unchanged.
   Evicts entries for keys not present in the visible window."
  [prior-cache visible-pairs hiccup-path box]
  (let [visible-keys (set (map first visible-pairs))
        kept         (select-keys prior-cache visible-keys)]
    (reduce
      (fn [cache [k seg]]
        (let [existing (get kept k)
              h        (hash seg)]
          (if (and existing (= h (:hash existing)))
            cache
            (assoc cache k (render-segment seg hiccup-path box)))))
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

;; ─────────────────────────────────────────────────────────────────────
;; Main-buffer append-emission (spec §6.3 transcript pattern)
;; ─────────────────────────────────────────────────────────────────────

(defn- segment-ansi
  "Render one segment's hiccup as a CellGrid and emit it as
   sequential ANSI bytes (no cursor-positioning embedded — the caller
   has already positioned the terminal cursor). Returns
   `[bytes new-style]`."
  [seg hiccup-path width terminal-caps style]
  (let [hiccup (get seg hiccup-path)
        height (segment-height seg hiccup-path width)
        grid   (layout/render-element
                 (or hiccup [:text {:text ""}])
                 {:width width :height height})]
    (reduce (fn [[acc st] row]
              (let [[row-bytes st'] (ansi/emit-cells row
                                                     (or terminal-caps {:color :truecolor})
                                                     (or st {}))]
                [(str acc row-bytes "\n") st']))
            ["" style]
            (:cells grid))))

(defn render-stream-main
  "Append-emission renderer for `:main` + `:stream` screens. Given the
   prior `stream-state` and the current handler `result`, returns
   `{:state new-state :bytes <string> :style <new-ansi-style>}`.

   For each segment whose `:key` isn't already in
   `prior-state :emitted-keys`, emit:

     cursor-position(emission-row, 0)
     erase-line-to-eol
     <segment ANSI bytes>
     `\\n`

   The caller is expected to have set a DECSTBM scroll region with the
   bottom at `emission-row`. The `\\n` then scrolls the new segment
   into the region's history while leaving the chrome rows below
   untouched.

   Append-only violations (a previously-emitted key is missing or
   reordered) log `::main-stream-violation` and the function emits
   nothing — we cannot un-scroll content the terminal has already
   committed to its scrollback.

   `opts`:
     :width          — viewport width (segment box width).
     :emission-row   — 0-indexed terminal row where new segments are
                       written. Caller must have configured DECSTBM
                       with this as the bottom of the scroll region.
     :terminal-caps  — for ANSI color/style emission.
     :style          — prior ANSI style state (threaded across calls
                       so we don't re-emit redundant SGR sequences)."
  [prior-state result {:keys [items key hiccup] :or {hiccup :tui/hiccup}}
   {:keys [width emission-row terminal-caps style]}]
  (let [segments     (extract-segments result {:items items})
        keys-vec     (mapv #(segment-key {:key key} %) segments)
        emitted      (vec (:emitted-keys prior-state))
        ;; Append-only check: the prior emitted-keys must be a prefix
        ;; of the new keys-vec.
        prefix-ok?   (= emitted (vec (take (count emitted) keys-vec)))
        new-keys     (when prefix-ok? (subvec keys-vec (count emitted)))
        new-segments (when prefix-ok?
                       (subvec segments (count emitted)))]
    (cond
      (not prefix-ok?)
      (do (u/log ::main-stream-violation
                 :prior-emitted emitted
                 :current-keys  keys-vec)
          {:state prior-state :bytes "" :style style})

      (empty? new-segments)
      {:state prior-state :bytes "" :style style}

      :else
      (let [position (ansi/cursor-position emission-row 0)
            [bytes final-style]
            (reduce (fn [[acc st] seg]
                      (let [[seg-bytes st'] (segment-ansi seg hiccup width
                                                          terminal-caps st)
                            rows (butlast (string/split seg-bytes #"\n" -1))
                            positioned (apply str
                                              (map #(str position
                                                         (ansi/erase-line-to-eol)
                                                         %
                                                         "\n")
                                                   rows))]
                        [(str acc positioned)
                         st']))
                    ["" style]
                    new-segments)
            new-emitted (into emitted new-keys)]
        {:state (assoc prior-state :emitted-keys new-emitted)
         :bytes bytes
         :style final-style}))))

(defn render-stream
  "End-to-end: given the prior `stream-state`, a fresh handler `result`
   map, the `:tui/segments` spec, and a `box` `{:width w :height h}`,
   return `{:state new-stream-state :grid CellGrid}`.

   The `:tui/segments` spec declares `:items` (path to segment list in
   the handler return), `:key` (segment identity), and `:hiccup` (path on
   each segment carrying its pre-rendered hiccup; defaults to
   `:tui/hiccup`).

   On contract violation (§13.5), logs a warning and returns a
   full-window re-render."
  [prior-state result {:keys [items key hiccup]
                       :or   {hiccup :tui/hiccup}}
   {:keys [width height] :as box}]
  (let [segments     (extract-segments result {:items items})
        keys-vec     (mapv #(segment-key {:key key} %) segments)
        violation    (detect-violation (:last-keys prior-state) keys-vec)
        _            (when violation
                       (u/log ::stream-projection-violation :kind violation))
        ;; Build [key seg] pairs aligned to the keys-vec.
        pairs        (mapv (fn [k s] [k s]) keys-vec segments)
        visible      (compute-visible-window
                      pairs
                      height
                      (fn [[_ segment]]
                        (segment-height segment hiccup width)))
        ;; If there was a violation, force re-render of the visible window
        ;; by clearing the prior cache for those keys.
        cache-input  (if violation
                       (apply dissoc (:segment-cache prior-state) (map first visible))
                       (:segment-cache prior-state))
        new-cache    (refresh-cache cache-input visible hiccup {:width width})
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
