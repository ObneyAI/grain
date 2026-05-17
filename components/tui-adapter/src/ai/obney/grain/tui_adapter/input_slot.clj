(ns ai.obney.grain.tui-adapter.input-slot
  "Placeable input area (`:tui/input {:placement :slot}`).

   The substrate's sticky input area is normally pinned to the bottom of
   the viewport, full width. `:placement :slot` lets it render *inside*
   the screen layout instead: the screen hiccup includes an
   `[:input-slot]` element which fills its allocated box with a sentinel
   cell. After the screen grid is composed, `find-slot-box` recovers the
   slot's absolute box by scanning for that sentinel, and `place-input`
   overlays the input-area grid there and strips every residual sentinel
   back to a blank cell so the downstream diff — which compares whole
   cell maps (`diff/row-diff`) — never sees the marker.

   The layout engine is position-oblivious (a leaf's `:render` receives
   its *size*, never its absolute origin; `cells/beside`/`stack`/`overlay`
   compose with no node→box map), so recovering position from the
   composed output is the only sound mechanism.

   Pure: data in, data out. Required by *both* compositing sites — the
   local session renderer (`session/render-frame-alt!`) and the remote
   thin client (`tui-client.render`) — exactly as `input-area` is shared.
   Sentinel cells are created at layout time and never serialized; the
   SSE wire only ever carries the `[:input-slot]` hiccup tag."
  (:require [ai.obney.grain.tui-adapter.cells :as cells]
            [com.brunobonacci.mulog :as u]))

(def ^:private slot-marker
  "Private cell key marking an input-slot sentinel cell. Stripped before
   diff; never serialized."
  ::sentinel)

(def sentinel-cell
  "A blank cell carrying the input-slot marker."
  (assoc cells/blank-cell slot-marker true))

(defn- sentinel? [cell] (true? (get cell slot-marker)))

(defn sentinel-grid
  "A `w`×`h` CellGrid of sentinel cells. Rendered by the `:input-slot`
   built-in element to mark where the input area should land."
  [w h]
  {:width  w
   :height h
   :cells  (vec (repeat h (vec (repeat w sentinel-cell))))})

(defn find-slot-box
  "Scan `grid` for sentinel cells and return the first contiguous sentinel
   rectangle as `{:x :y :w :h}`, or nil when there are no sentinels.

   \"First\" = anchored at the topmost-leftmost sentinel; the rectangle
   extends right along that row while cells are sentinels, then down while
   the full `[x, x+w)` span stays sentinel. Deterministic. If sentinels
   exist outside that rectangle (the app placed more than one
   `[:input-slot]`), it logs and still returns the first rectangle — the
   one-input-per-screen invariant holds (R5)."
  [{:keys [cells width height] :as _grid}]
  (let [start (first (for [y (range height)
                           x (range width)
                           :when (sentinel? (get-in cells [y x]))]
                       [x y]))]
    (when start
      (let [[sx sy] start
            w (loop [x sx]
                (if (and (< x width)
                         (sentinel? (get-in cells [sy x])))
                  (recur (inc x))
                  (- x sx)))
            h (loop [y sy]
                (if (and (< y height)
                         (every? #(sentinel? (get-in cells [y %]))
                                 (range sx (+ sx w))))
                  (recur (inc y))
                  (- y sy)))
            box   {:x sx :y sy :w w :h h}
            total (count (for [y (range height)
                               x (range width)
                               :when (sentinel? (get-in cells [y x]))]
                           1))]
        (when (not= total (* w h))
          (u/log ::multiple-input-slots
                 :using box :total-sentinels total))
        box))))

(defn strip-sentinels
  "Replace every sentinel cell in `grid` with `cells/blank-cell`.
   Mandatory before diff (R2): `diff` compares whole cell maps, so a
   residual marker key would corrupt diffing and the persisted
   render-model."
  [grid]
  (update grid :cells
          (fn [rows]
            (mapv (fn [row]
                    (mapv (fn [c] (if (sentinel? c) cells/blank-cell c)) row))
                  rows))))

(defn place-input
  "Overlay `input-grid` onto `screen-grid` at `box` (from
   `find-slot-box`), then strip any residual sentinels. Returns
   `{:grid <composed> :origin [x y]}` where `origin` is the absolute slot
   top-left for cursor positioning.

   When `box` is nil (no `[:input-slot]` was in the hiccup), returns the
   stripped screen-grid and a nil origin so callers fall back to the
   bottom-strip behavior."
  [screen-grid box input-grid]
  (if (nil? box)
    {:grid (strip-sentinels screen-grid) :origin nil}
    (let [{:keys [x y]} box]
      {:grid   (-> screen-grid
                   (cells/overlay input-grid x y)
                   strip-sentinels)
       :origin [x y]})))
