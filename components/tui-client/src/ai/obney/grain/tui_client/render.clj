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
   server-side per §7.6.8); the client just paints them.

   When a frame declares `:input` (sticky input area per spec §6.2),
   the bottom rows of the viewport are reserved for it and the client
   emits cursor-positioning ANSI to land the terminal cursor inside
   the buffer. Input-area *state* lives in the client's main loop;
   this namespace reads it from `render-state :input-area`."
  (:require [ai.obney.grain.tui-adapter.ansi       :as ansi]
            [ai.obney.grain.tui-adapter.builtins]   ; register built-ins
            [ai.obney.grain.tui-adapter.cells      :as cells]
            [ai.obney.grain.tui-adapter.diff       :as diff]
            [ai.obney.grain.tui-adapter.input-area :as input-area]
            [ai.obney.grain.tui-adapter.layout     :as layout]
            [ai.obney.grain.tui-adapter.overlay    :as overlay]))

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

(defn- input-area-height
  "How many rows the input area should occupy given the frame's
   `:input` config and the current input-area state. 0 when no input
   area is declared on the frame."
  [frame input-area-state]
  (if (:input frame)
    (input-area/preferred-height input-area-state (:input frame))
    0))

(defn- render-input-area
  "Render the input area into a CellGrid sized to the viewport width
   and the input-area height. Returns `{:grid g :cursor-pos [c r]}`
   where cursor-pos is relative to the input area's box."
  [frame input-area-state viewport ia-height]
  (let [cfg (:input frame)]
    (input-area/render input-area-state
                       {:prompt     (or (:prompt cfg) "")
                        :width      (:width viewport)
                        :height     ia-height
                        :multiline? (boolean (:multiline? cfg))})))

(defn frame->grid
  "Pure: frame + viewport + optional input-area state → CellGrid.

   When the frame declares `:input`, the bottom `input-area-height`
   rows are reserved for the input area; screen content gets the
   remainder. Otherwise the screen fills the whole viewport.

   2-arity overload keeps the namespace's earlier signature working
   (used by tests that don't care about input)."
  ([frame viewport]
   (frame->grid frame viewport nil))
  ([frame viewport input-area-state]
   (let [ia-h     (input-area-height frame input-area-state)
         screen-h (max 1 (- (:height viewport) ia-h))
         screen-vp {:width (:width viewport) :height screen-h}
         screen-g (layout/render-element (screen-hiccup frame) screen-vp)
         base     (cells/blank (:width viewport) (:height viewport))
         canvas   (cells/overlay base screen-g 0 0)
         canvas   (if (pos? ia-h)
                    (let [{ia-grid :grid} (render-input-area frame input-area-state viewport ia-h)]
                      (cells/overlay canvas ia-grid 0 (- (:height viewport) ia-h)))
                    canvas)]
     (compose-overlay frame canvas viewport))))

(defn- maybe-emit-cursor!
  "Emit cursor-positioning + show/hide ANSI based on whether the input
   area is active on this frame. Returns the new `:cursor-shown?`
   bookkeeping value so the caller can persist idempotency state."
  [state frame input-area-state viewport on-output]
  (let [ia-h    (input-area-height frame input-area-state)
        active? (and (pos? ia-h) (nil? (:overlay frame)))
        prior   (boolean (:cursor-shown? state))]
    (cond
      active?
      (let [{[c-col c-row] :cursor-pos} (render-input-area frame input-area-state viewport ia-h)
            ia-top (- (:height viewport) ia-h)
            ;; ansi/cursor-position takes 0-indexed (row, col).
            move   (ansi/cursor-position (+ ia-top c-row) c-col)]
        (when on-output
          (on-output (str move (ansi/show-cursor))))
        true)

      prior
      (do (when on-output (on-output (ansi/hide-cursor)))
          false)

      :else
      false)))

(defn render-frame!
  "Render a frame to ANSI bytes against the prior `render-model`. Calls
   `on-output` with the byte string when there's anything to emit.
   Returns the updated `state` map.

   `state`:
     :render-model   — prior CellGrid (nil on first frame).
     :ansi-style     — prior ANSI style (nil on first frame).
     :terminal-caps  — `{:color :truecolor|...}`.
     :input-area     — the loop's input-area state (nil when frame has
                       no `:tui/input` config).
     :cursor-shown?  — internal bookkeeping for cursor idempotency.

   Returns state with `:render-model`, `:ansi-style`, and
   `:cursor-shown?` updated."
  [state frame viewport on-output]
  (let [ia-state      (:input-area state)
        grid          (frame->grid frame viewport ia-state)
        runs          (diff/diff (:render-model state) grid)
        [out new-sty] (ansi/emit runs
                                 (or (:terminal-caps state) {:color :truecolor})
                                 (or (:ansi-style state) {}))]
    (when (and on-output (seq out))
      (on-output out))
    (let [shown? (maybe-emit-cursor! state frame ia-state viewport on-output)]
      (assoc state
             :render-model  grid
             :ansi-style    new-sty
             :cursor-shown? shown?))))
