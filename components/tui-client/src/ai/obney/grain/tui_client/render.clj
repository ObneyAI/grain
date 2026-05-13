(ns ai.obney.grain.tui-client.render
  "Render a v0.8 Frame to ANSI bytes, client-side.

   The thin client receives Frames over SSE as EDN. This namespace
   consumes a Frame map and a viewport (`{:width :height}`), runs the
   shared `tui-adapter.layout` engine against the client's render-model,
   diffs cells, and produces ANSI output to push to the terminal.

   No Grain knowledge — Frames are pure data; the layout/diff/ansi
   pipeline is the same as the local-topology one. The only seam where
   client differs from server is that custom (application-registered)
   elements arrive as `[:cells {:grid ...}]` markers (resolved
   server-side per §7.6.8); the client just paints them."
  (:require [ai.obney.grain.tui-adapter.ansi    :as ansi]
            [ai.obney.grain.tui-adapter.builtins]   ; register built-ins
            [ai.obney.grain.tui-adapter.cells   :as cells]
            [ai.obney.grain.tui-adapter.diff    :as diff]
            [ai.obney.grain.tui-adapter.layout  :as layout]
            [ai.obney.grain.tui-adapter.overlay :as overlay]))

(defn- error-hiccup [headline message]
  [:col
   [:text {:fg :red :bold? true} (str headline)]
   [:text (str message)]
   [:text {:dim? true} "Press q to quit."]])

(defn- screen-hiccup
  "Build the hiccup we'll layout for a frame, branching on the frame's
   primary content. Returns nil when the frame has nothing renderable
   (the caller paints an empty viewport)."
  [frame]
  (cond
    (:error frame)
    (error-hiccup (-> frame :error :headline) (-> frame :error :message))

    ;; Regions arrive as a {:region-name <hiccup>} map. We don't yet
    ;; implement the layout-engine-driven region placement; for MVP we
    ;; stack regions vertically in insertion order.
    (:regions frame)
    (into [:col]
          (map (fn [[_ h]] (or h [:text {:text ""}])) (:regions frame)))

    ;; Stream segments: stack each segment's pre-rendered hiccup,
    ;; bottom-aligned (matching the local stream renderer's window
    ;; behavior). For MVP, just stack top-down inside the viewport;
    ;; per-segment caching across frames is a future enhancement.
    (:segments frame)
    (let [hp (or (-> frame :metadata :segments-spec :hiccup) :tui/hiccup)]
      (into [:col]
            (map #(get % hp [:text {:text ""}]))
            (:segments frame)))

    :else
    (or (:hiccup frame)
        [:text {:text ""}])))

(defn- overlay-position
  [overlay-type ovr-grid {:keys [width height]}]
  (case overlay-type
    :toast (overlay/toast-position (:width ovr-grid) (:height ovr-grid) width height)
    (overlay/modal-position (:width ovr-grid) (:height ovr-grid) width height)))

(defn- compose-overlay
  "If the frame has an overlay, render it and composite onto `screen-grid`."
  [frame screen-grid viewport]
  (if-let [ov (:overlay frame)]
    (let [hiccup    (or (:content ov) [:text {:text ""}])
          ovr-grid  (layout/render-element hiccup
                                           {:width  (max 1 (- (:width viewport) 4))
                                            :height (max 1 (- (:height viewport) 4))})
          [x y]     (overlay-position (:type ov) ovr-grid viewport)]
      (cells/overlay screen-grid ovr-grid x y))
    screen-grid))

(defn frame->grid
  "Pure: frame + viewport → CellGrid (post-overlay composition)."
  [frame viewport]
  (let [screen (layout/render-element (screen-hiccup frame) viewport)
        base   (cells/blank (:width viewport) (:height viewport))
        ;; Pad screen onto a uniform-size base so the diff sees a
        ;; consistent canvas every frame.
        padded (cells/overlay base screen 0 0)]
    (compose-overlay frame padded viewport)))

(defn render-frame!
  "Render a frame to ANSI bytes against the prior `render-model`. Calls
   `on-output` with the byte string when there's anything to emit.
   Returns `{:render-model <new-grid> :ansi-style <new-style>}` so the
   caller can persist it for the next cycle.

   `state` is `{:render-model <grid-or-nil> :ansi-style <map-or-nil>
                :terminal-caps <map>}`."
  [state frame viewport on-output]
  (let [grid          (frame->grid frame viewport)
        runs          (diff/diff (:render-model state) grid)
        [out new-sty] (ansi/emit runs
                                 (or (:terminal-caps state) {:color :truecolor})
                                 (or (:ansi-style state) {}))]
    (when (and on-output (seq out))
      (on-output out))
    (assoc state
           :render-model grid
           :ansi-style   new-sty)))
