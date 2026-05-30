(ns ai.obney.grain.tui-adapter.session
  "Per-session state, event loop, lifecycle.

   A session is a long-lived stateful entity bound to:
   - one transport sink (an `(fn [^String s] ...)` for output bytes)
   - one tenant
   - one user
   - one event-pubsub
   - one current screen (with screen stack for back/push)

   The render loop runs in its own thread; it `alts!!` over:
     - sub-chan   (per-screen pubsub subscription, per §4.1)
     - input-ch   (logical key events from the parser)
     - resize-ch  (synthesized window-size changes)
     - timeout    (30 s liveness only)

   On each event it debounces (config'able via `:tui/debounce-ms`),
   drains BOTH sub-chan and input-ch (correctness, not optimization —
   per the load-bearing comment at datastar/core.clj:405-411), then
   re-renders the screen and emits the diff.

   The render path (spec v0.7):
     1. (process-query-fn ctx) → handler return map
     2. Read presentation from the return:
          - snapshot: (:tui/hiccup result)
          - stream:   segments at (:tui/segments :items), each carrying hiccup
                      at the path declared by (:tui/segments :hiccup)
                      (default :tui/hiccup)
     3. (layout/render-element hiccup viewport) → CellGrid
     4. compose with overlay via cells/overlay
     5. diff against :render-model → runs
     6. ansi/emit → output bytes
     7. update :render-model and :ansi-style"
  (:require [clojure.core.async :as async :refer [alts!! chan close! poll! timeout]]
            [com.brunobonacci.mulog :as u]
            [ai.obney.grain.tui-adapter.ansi :as ansi]
            [ai.obney.grain.tui-adapter.builtins]   ; ensure built-in elements register
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.diff :as diff]
            [ai.obney.grain.tui-adapter.frame :as frame]
            [ai.obney.grain.tui-adapter.input-area :as input-area]
            [ai.obney.grain.tui-adapter.input-slot :as input-slot]
            [ai.obney.grain.tui-adapter.keymap :as keymap]
            [ai.obney.grain.tui-adapter.layout :as layout]
            [ai.obney.grain.tui-adapter.overlay :as overlay]
            [ai.obney.grain.tui-adapter.stream :as stream]
            [ai.obney.grain.tui-adapter.subscription :as subscription]))

;; ─────────────────────────────────────────────────────────────────────
;; Session-action handlers (§9.3)
;; ─────────────────────────────────────────────────────────────────────

(declare change-screen!)
(declare render-frame!)
(declare schedule-toast-dismissal!)

(defmulti handle-session-action
  "Dispatch a session-action keyword. Methods receive `(session opts ctx)`
   and may mutate the session atom. Returns nil."
  (fn [_session action _opts _ctx] action))

(defmethod handle-session-action :push-screen
  [session _action {:keys [query-id inputs]} _ctx]
  (let [{:keys [current-screen screen-stack]} @session
        new-screen {:query-id query-id :inputs (or inputs {})}]
    (swap! session assoc :screen-stack (conj screen-stack current-screen))
    (change-screen! session new-screen)))

(defmethod handle-session-action :back
  [session _action _opts _ctx]
  (let [{:keys [screen-stack]} @session]
    (when (seq screen-stack)
      (let [prev    (peek screen-stack)
            stack'  (pop screen-stack)]
        (swap! session assoc :screen-stack stack')
        (change-screen! session prev)))))

(defmethod handle-session-action :quit
  [session _action _opts _ctx]
  (swap! session assoc :running? false))

(defmethod handle-session-action :refresh
  [session _action _opts _ctx]
  ;; render-frame! is called unconditionally by the loop after this
  ;; returns; nothing else to do here.
  nil)

(defmethod handle-session-action :focus
  [session _action {:keys [region]} _ctx]
  (swap! session assoc :focus region))

(defmethod handle-session-action :open-overlay
  [session _action {:keys [type content keymap ttl-ms id] :or {type :modal}} _ctx]
  (let [overlay {:type type :content content :keymap keymap :id id :ttl-ms ttl-ms}]
    (swap! session assoc :overlay overlay)
    (when (and ttl-ms (= type :toast))
      (schedule-toast-dismissal! session id ttl-ms))))

(defmethod handle-session-action :dismiss-overlay
  [session _action _opts _ctx]
  (swap! session assoc :overlay nil))

(defmethod handle-session-action :toast
  [session _action {:keys [message level ttl-ms] :or {ttl-ms 3000 level :info}} ctx]
  (handle-session-action session :open-overlay
                         {:type    :toast
                          :id      (random-uuid)
                          :ttl-ms  ttl-ms
                          :content [:text {:text message
                                           :fg   (case level
                                                   :error :red
                                                   :warn  :yellow
                                                   :default)}]}
                         ctx))

(defmethod handle-session-action :open-palette
  [session _action opts _ctx]
  ;; Fetch the items immediately by running the configured query, then
  ;; stage palette state into the overlay. The renderer treats overlays
  ;; of type :palette specially via `overlay/palette-hiccup`.
  (let [s     @session
        items (overlay/fetch-palette-items
                {:process-query-fn (:process-query-fn s)
                 :base-context     (:base-context s)
                 :tenant-id        (:tenant-id s)
                 :user-id          (:user-id s)}
                opts)]
    (swap! session assoc :overlay
           {:type      :palette
            :config    opts
            :filter    ""
            :selected  0
            :items     items
            :all-items items})))

(defmethod handle-session-action :default
  [_session action _opts _ctx]
  (u/log ::unknown-session-action :action action))

(defn- schedule-toast-dismissal!
  "Schedule a toast overlay with id `tid` to be dismissed after `ttl-ms`."
  [session tid ttl-ms]
  (let [scheduler (or (:scheduler @session)
                       (let [s (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
                         (swap! session assoc :scheduler s)
                         s))]
    (.schedule ^java.util.concurrent.ScheduledExecutorService scheduler
               ^Runnable (fn []
                           (let [{:keys [overlay]} @session]
                             (when (= tid (:id overlay))
                               (swap! session assoc :overlay nil)
                               ;; Trigger a re-render via the input channel.
                               (when-let [in (:input-ch @session)]
                                 (async/offer! in {:type :synthetic :reason :toast-expire})))))
               (long ttl-ms)
               java.util.concurrent.TimeUnit/MILLISECONDS)))

;; ─────────────────────────────────────────────────────────────────────
;; Screen lifecycle: tear down prior subscription, build new one
;; ─────────────────────────────────────────────────────────────────────

(defn- query-context-for-screen
  "Build the context map passed to the query processor when running the
   screen's query (for both rendering and event-tag resolution)."
  [session screen]
  (let [{:keys [tenant-id user-id]} @session]
    {:tenant-id tenant-id
     :user-id   user-id
     :query     (merge {:query/name (:query-id screen)} (:inputs screen))}))

(defn- fire-hook!
  "Invoke a `:tui/on-enter` / `:tui/on-exit` hook with a session-state
   snapshot. Exceptions are logged and swallowed — a misbehaving hook
   must not destabilize the session."
  [hook-key screen session]
  (when-let [hook (get screen hook-key)]
    (try
      (hook @session)
      (catch Exception e
        (u/log ::lifecycle-hook-failed
               :hook hook-key
               :screen (:query-id screen)
               :error e)))))

(defn- warn-if-periodic-refresh!
  "v0 does not support polling. If `:tui/refresh {:periodic-ms n}` is
   declared, emit a one-time mulog warning so callers see the deferred
   feature instead of silent fallthrough. The screen still installs
   correctly — periodic refresh just won't fire."
  [screen]
  (let [refresh (:tui/refresh screen)]
    (when (and (map? refresh) (:periodic-ms refresh))
      (u/log ::periodic-refresh-deferred
             :screen      (:query-id screen)
             :periodic-ms (:periodic-ms refresh)))))

(defn change-screen!
  "Tear down the prior subscription, install `new-screen`, build a new
   subscription, fire `:tui/on-exit` for the prior screen and `:tui/on-enter`
   for the new screen. Warns when `:tui/refresh {:periodic-ms n}` is
   declared (deferred per v0 MVS).

   `new-screen` is a map with the query metadata (`:query-id`, `:inputs`,
   `:grain/read-models`, `:tui/buffer`, `:tui/projection`, `:tui/segments`,
   `:tui/keymap`, etc.) — usually derived from the query registry. Per spec
   v0.7 the screen carries no render function; hiccup comes from the
   handler return."
  [session new-screen]
  (let [{:keys [sub-chan event-pubsub current-screen on-output]} @session
        old-buffer (or (:tui/buffer current-screen) :alt)
        new-buffer (or (:tui/buffer new-screen)     :alt)]
    (when sub-chan (close! sub-chan))
    (when current-screen
      (fire-hook! :tui/on-exit current-screen session))
    (warn-if-periodic-refresh! new-screen)
    ;; Buffer-mode transition (rare — most sessions stay in one
    ;; buffer). When flipping, we ask the terminal to enter/leave its
    ;; alt-screen so the new screen renders in the right canvas. The
    ;; per-buffer setup (`enter-tui!` already happened at session
    ;; start) doesn't need to be re-emitted in full — just the
    ;; alt-screen toggle and a clear of the new canvas's render
    ;; bookkeeping.
    (when (and on-output current-screen (not= old-buffer new-buffer))
      (on-output (case new-buffer
                   :main (ansi/leave-alt-screen)
                   (str (ansi/enter-alt-screen) (ansi/clear-screen)))))
    (let [new-sub (when event-pubsub
                    (subscription/subscribe-screen
                      event-pubsub
                      new-screen
                      (query-context-for-screen session new-screen)))]
      (swap! session assoc
             :current-screen new-screen
             :sub-chan       new-sub
             :stream-state   nil    ; reset stream cache on screen change
             :render-model   (when (= old-buffer new-buffer) (:render-model @session))
             :input-area     (when (:tui/input new-screen)
                               (input-area/initial-state))))
    (fire-hook! :tui/on-enter new-screen session)
    nil))

;; ─────────────────────────────────────────────────────────────────────
;; Rendering
;; ─────────────────────────────────────────────────────────────────────

(defn- run-query
  "Invoke the session's `:process-query-fn` with the appropriate context.
   Returns the query result (typically `{:query/result ...}`)."
  [session]
  (let [{:keys [process-query-fn current-screen] :as s} @session
        ctx (merge (:base-context s)
                   {:tenant-id (:tenant-id s)
                    :user-id   (:user-id s)
                    :query     (merge {:query/name (:query-id current-screen)}
                                      (:inputs current-screen))})]
    (try
      (process-query-fn ctx)
      (catch Exception e
        (u/log ::query-failed :error e)
        {:query/error (.getMessage e)}))))

(defn- error-hiccup [headline message]
  [:col
   [:text {:fg :red :bold? true} (str headline)]
   [:text (str message)]
   [:text {:dim? true} "Press <esc> to go back, q to quit."]])

(defn- layout-frame-locally
  "Local-topology rendering of a Frame to a CellGrid. Branches on the
   frame's primary content — error, stream segments, regions (layout), or
   snapshot hiccup. Reuses the same `layout/render-element` and
   `stream/render-stream` pipeline as before; the v0.7 behavior is
   preserved end-to-end, just routed through the Frame intermediate.

   Returns `[grid new-stream-state-or-nil]`."
  [frm session viewport]
  (cond
    (frame/error? frm)
    [(layout/render-element
       (error-hiccup (:headline (:error frm)) (:message (:error frm)))
       viewport)
     nil]

    (frame/stream? frm)
    (let [{:keys [stream-state]} @session
          ;; The frame carries segments already extracted from the handler
          ;; return at the `:items` path. Hand them back to render-stream
          ;; via a synthetic result map keyed by ::segments, plus a
          ;; matching segments-spec that points at that key.
          spec  (-> frm :metadata :segments-spec)
          synth-spec (assoc spec :items ::segments)
          synth-res  {::segments (:segments frm)}
          {:keys [state grid]}
          (stream/render-stream (or stream-state (stream/empty-stream-state))
                                synth-res
                                synth-spec
                                viewport)]
      [grid state])

    :else
    (let [hiccup (:hiccup frm)]
      (when (nil? hiccup)
        (u/log ::missing-presentation :screen (-> frm :screen :query-id)))
      [(layout/render-element (or hiccup [:text {:text ""}]) viewport) nil])))

(defn- effective-overlay
  [session]
  (let [{:keys [query-overlay overlay]} @session]
    (or query-overlay overlay)))

(defn- input-blocking-overlay?
  [overlay-state]
  (contains? #{:palette :modal} (:type overlay-state)))

(defn- passive-overlay?
  [overlay-state]
  (and overlay-state (not (input-blocking-overlay? overlay-state))))

(defn- reserved-lane-overlay?
  [overlay-state]
  (and overlay-state
       (or (passive-overlay? overlay-state)
           (= :reserved-lane (:placement overlay-state))
           (= :reserved-lane (get-in overlay-state [:config :placement])))))

(defn- palette-natural-height
  [overlay-state]
  (let [config (:config overlay-state)
        detail-rows (count (:details config))
        filter-row (if (not= false (:filterable? config)) 1 0)
        item-rows (count (:items overlay-state))
        help-row 1
        border-rows 2]
    (+ border-rows detail-rows filter-row item-rows help-row)))

(defn- overlay-render-height
  [overlay-state viewport]
  (cond
    (reserved-lane-overlay? overlay-state)
    (min (:height viewport)
         (case (:type overlay-state)
           :palette (palette-natural-height overlay-state)
           12))

    (input-blocking-overlay? overlay-state)
    (:height viewport)

    :else
    (min (:height viewport) 12)))

(defn- preserve-query-overlay-state
  [prior next-overlay]
  (if (and prior next-overlay (= (:id prior) (:id next-overlay)))
    (merge next-overlay
           (select-keys prior [:filter :selected]))
    next-overlay))

(defn- install-query-presentation!
  [session result]
  (let [overlay (:tui/overlay result)
        input (:tui/input result)
        prior (:query-overlay @session)]
    (swap! session assoc
           :query-overlay (preserve-query-overlay-state prior overlay)
           :query-input input)))

(defn- compute-screen-grid
  "Compute the screen's CellGrid for the current frame. Produces a Frame
   via `frame/produce-frame` (the v0.8 unit of presentation), then lays
   it out locally via `layout-frame-locally`. The Frame intermediate
   makes it possible for the remote-topology HTTP+SSE transport (v0.8
   §4.3) to consume the same presentation without re-running the query
   pipeline.

   Returns `[grid new-stream-state-or-nil]`."
  [session viewport]
  (let [result (run-query session)
        _      (install-query-presentation! session result)
        frm    (frame/produce-frame (assoc @session :overlay (effective-overlay session))
                                    result)]
    (layout-frame-locally frm session viewport)))

(defn- overlay-hiccup
  "Translate an `:overlay` map into hiccup the layout engine can render.
   Palettes are rendered via `overlay/palette-hiccup`; toasts and modals
   carry their hiccup in `:content`."
  [overlay-state]
  (case (:type overlay-state)
    :palette (overlay/palette-hiccup overlay-state)
    (:content overlay-state)))

(defn- overlay-position
  "Compute the (x, y) origin for the overlay grid inside the viewport."
  [overlay-type ovr-grid {:keys [width height] :as _viewport}]
  (case overlay-type
    :toast (overlay/toast-position (:width ovr-grid) (:height ovr-grid) width height)
    (overlay/modal-position (:width ovr-grid) (:height ovr-grid) width height)))

(defn- compose-overlay
  "If an overlay is present, render it and composite onto `screen-grid`
   via cells/overlay. Returns the composed grid."
  [screen-grid overlay-state viewport]
  (if-not overlay-state
    screen-grid
    (let [hiccup (overlay-hiccup overlay-state)
          max-w (or (get-in overlay-state [:config :max-width])
                    (- (:width viewport) 4))
          layout-width (max 1 (min (max 1 (- (:width viewport) 4)) max-w))
          ovr-grid (when hiccup
                     (layout/render-element hiccup
                                            {:width  layout-width
                                             :height (max 1 (- (:height viewport) 4))}))
          [x y] (when ovr-grid
                  (overlay-position (:type overlay-state) ovr-grid viewport))]
      (if ovr-grid
        (cells/overlay screen-grid ovr-grid x y)
        screen-grid))))

(defn- pad-to-viewport
  "Compose `grid` onto a blank canvas of the full viewport size. Ensures
   downstream steps (overlay compositing, diff) see a uniform shape
   regardless of how tall/wide the screen renderer chose to return."
  [grid {:keys [width height]}]
  (let [base (cells/blank width height)]
    (cells/overlay base grid 0 0)))

(defn- input-area-config
  "Read the effective `:tui/input` config map. The screen owns behavior
   such as submission command; query results may override render-only chrome
   such as prompt/hint through `:query-input`."
  [session]
  (let [{:keys [current-screen query-input]} @session
        screen-input (:tui/input current-screen)]
    (when screen-input
      (merge screen-input query-input))))

(defn- effective-overlay-key
  [session]
  (when (effective-overlay session)
    (if (:query-overlay @session) :query-overlay :overlay)))

(defn- input-area-height
  "How many rows the input area occupies for `session`'s current
   screen, or 0 when not declared."
  [session]
  (if-let [cfg (input-area-config session)]
    (input-area/preferred-height (:input-area @session) cfg)
    0))

(defn- render-input-area
  "Render the session's input-area state into a CellGrid of
   (viewport-width × input-area-height). Returns
   `{:grid g :cursor-pos [col row]}` where cursor-pos is relative to
   the input area's box.

   Passes the full `:tui/input` config through so `:hint`, `:max-rows`,
   etc. reach the renderer alongside the box dimensions."
  [session viewport]
  (let [s    @session
        cfg  (input-area-config session)
        h    (input-area-height session)
        opts (merge cfg
                    {:prompt     (or (:prompt cfg) "")
                     :width      (:width viewport)
                     :height     h
                     :multiline? (boolean (:multiline? cfg))})]
    (input-area/render (:input-area s) opts)))

(defn- render-overlay-grid
  [overlay-state viewport]
  (when overlay-state
    (let [hiccup (overlay-hiccup overlay-state)
          max-w (or (get-in overlay-state [:config :max-width])
                    (:width viewport))
          overlay-width (max 1 (min (:width viewport) max-w))
          height (overlay-render-height overlay-state viewport)
          grid (layout/render-element hiccup {:width overlay-width
                                              :height height})
          x (max 0 (min 2 (- (:width viewport) (:width grid))))
          canvas (cells/blank (:width viewport) (:height grid))]
      (cells/overlay canvas grid x 0))))

(defn- emit-grid-as-lines
  "Emit each row of `grid` as sequential ANSI bytes followed by `\\n`.
   Used by the main-buffer renderer to print the intro block (and the
   chrome) into the terminal at the current cursor row, letting the
   scroll region handle vertical positioning naturally."
  [grid caps style]
  (loop [rows  (:cells grid)
         st    style
         sb    (StringBuilder.)]
    (if (empty? rows)
      [(.toString sb) st]
      (let [[row-bytes st'] (ansi/emit-cells (first rows) caps st)]
        (.append sb row-bytes)
        (.append sb "\n")
        (recur (rest rows) st' sb)))))

(defn- emit-grid-at-rows
  "Emit each row of `grid` at an absolute terminal row, clearing the row
   before writing it and never appending newlines. Used for fixed chrome
   in the main-buffer renderer; newline output there can move the
   terminal cursor and leave stale input behind."
  [grid start-row caps style]
  (loop [rows    (:cells grid)
         row-idx 0
         st      style
         sb      (StringBuilder.)]
    (if (empty? rows)
      [(.toString sb) st]
      (let [[row-bytes st'] (ansi/emit-cells (first rows) caps st)]
        (.append sb (ansi/cursor-position (+ start-row row-idx) 0))
        (.append sb (ansi/erase-line))
        (.append sb row-bytes)
        (recur (rest rows) (inc row-idx) st' sb)))))

(defn- render-frame-main!
  "Main-buffer + stream render path (spec §6.3 transcript pattern).

   - On the first frame, sets the DECSTBM scroll region to (0,
     chrome-top - 1), emits the screen's `:tui/hiccup` intro at the
     current cursor row, then primes the cursor at the bottom of the
     scroll region.
   - On every frame, computes new segments via
     `stream/render-stream-main` and emits them at the bottom row of
     the scroll region. The `\\n` after each segment scrolls the
     region; the chrome rows below stay pinned.
   - After segments, redraws the input chrome at the bottom of the
     viewport and positions the terminal cursor inside the prompt."
  [session]
  (let [result      (run-query session)
        _           (install-query-presentation! session result)
        s           @session
        {:keys [viewport terminal-caps ansi-style on-output]} s
        screen      (:current-screen s)
        overlay     (effective-overlay session)
        ia-cfg      (input-area-config session)
        ia-h        (if ia-cfg
                      (input-area/preferred-height (:input-area s) ia-cfg)
                      0)
        H        (:height viewport)
        W        (:width  viewport)
        chrome-top  (- H ia-h)
        overlay-grid (render-overlay-grid overlay
                                          {:width W :height (max 1 chrome-top)})
        overlay-height (or (:height overlay-grid) 0)
        reserve-height (if (reserved-lane-overlay? overlay) overlay-height 0)
        scroll-bottom (max 0 (dec (- chrome-top reserve-height)))
        emit-row    scroll-bottom ; last row of the scroll region
        stream-spec (:tui/segments screen)
        prior-ss    (or (:stream-state s) (stream/empty-stream-state))
        prior-overlay-height (:main-overlay-height s 0)
        prior-overlay-top (:main-overlay-top s)
        scroll-region-bytes (ansi/set-scroll-region 0 scroll-bottom)
        ;; First-frame setup: scroll region + intro emission.
        first?      (not (:intro-printed? prior-ss))
        [intro-bytes intro-style]
        (if first?
          (let [intro-hiccup (:tui/hiccup result)
                ;; Compute intro at the scroll region's full width and a
                ;; height matching the natural preferred-size; clip to
                ;; the scroll region's height as a safety bound.
                intro-grid   (when intro-hiccup
                               (layout/render-element
                                 intro-hiccup
                                 {:width W :height (max 1 (inc scroll-bottom))}))
                [body-bytes body-style]
                (if intro-grid
                  (emit-grid-as-lines intro-grid
                                      (or terminal-caps {:color :truecolor})
                                      (or ansi-style {}))
                  ["" (or ansi-style {})])]
            ;; Sequence: set scroll region, position at top, hide cursor,
            ;; emit intro lines, position at bottom row of scroll region
            ;; so subsequent segments land in the right place.
            [(str (ansi/cursor-position 0 0)
                  (ansi/hide-cursor)
                  body-bytes
                  (ansi/cursor-position emit-row 0))
             body-style])
          ["" (or ansi-style {})])
        ;; Segment append-emission.
        {seg-bytes :bytes new-ss :state final-style :style}
        (if stream-spec
          (stream/render-stream-main prior-ss result stream-spec
                                     {:width         W
                                      :emission-row  emit-row
                                      :terminal-caps (or terminal-caps {:color :truecolor})
                                      :style         intro-style})
          {:bytes "" :state prior-ss :style intro-style})
        ;; Chrome redraw at the bottom of the viewport.
        {ia-grid :grid ia-cur :cursor-pos}
        (when (pos? ia-h) (render-input-area session viewport))
        chrome-bytes
        (if ia-grid
          (let [[cb _] (emit-grid-at-rows
                         ia-grid
                         chrome-top
                         (or terminal-caps {:color :truecolor})
                         final-style)]
            cb)
          "")
        overlay-top (max 0 (- chrome-top overlay-height))
        clear-top (cond
                    (and prior-overlay-top overlay-grid)
                    (min prior-overlay-top overlay-top)
                    prior-overlay-top prior-overlay-top
                    overlay-grid overlay-top
                    :else nil)
        clear-bottom (cond
                       (and prior-overlay-top overlay-grid)
                       (max (+ prior-overlay-top prior-overlay-height)
                            (+ overlay-top overlay-height))
                       prior-overlay-top (+ prior-overlay-top prior-overlay-height)
                       overlay-grid (+ overlay-top overlay-height)
                       :else nil)
        clear-height (if (and clear-top clear-bottom)
                       (max 0 (- clear-bottom clear-top))
                       0)
        clear-grid (when (pos? clear-height)
                     (cells/blank W clear-height))
        [clear-bytes _]
        (if clear-grid
          (emit-grid-at-rows clear-grid clear-top
                             (or terminal-caps {:color :truecolor})
                             final-style)
          ["" final-style])
        [overlay-bytes _]
        (if overlay-grid
          (emit-grid-at-rows overlay-grid overlay-top
                             (or terminal-caps {:color :truecolor})
                             final-style)
          ["" final-style])
        ;; Cursor: position inside the prompt area (if any) and show.
        cursor-bytes
        (if (and ia-cur (not (input-blocking-overlay? overlay)))
          (let [[c-col c-row] ia-cur]
            (str (ansi/cursor-style-block)
                 (ansi/cursor-position (+ chrome-top c-row) c-col)
                 (ansi/show-cursor)))
          (ansi/hide-cursor))
        out (str scroll-region-bytes
                 clear-bytes
                 intro-bytes
                 seg-bytes
                 chrome-bytes
                 overlay-bytes
                 cursor-bytes)]
    (when (and on-output (seq out))
      (on-output out))
    (swap! session assoc
           :ansi-style    final-style
           :cursor-shown? (and (some? ia-cur)
                               (not (input-blocking-overlay? overlay)))
           :main-overlay-height overlay-height
           :main-overlay-top (when overlay-grid overlay-top)
           :main-overlay-reserve-height reserve-height
           :stream-state  (assoc new-ss :intro-printed? true))
    nil))

(declare render-frame-alt!)

(defn render-frame!
  "Render the current screen + overlay + (optional) input area, diff
   against `:render-model`, emit bytes via `:on-output`. When the
   current screen declares `:tui/input`, the bottom rows of the
   viewport are reserved for the input area and the terminal cursor
   is positioned inside it.

   Main-buffer stream screens use append-only scrollback emission.
   Snapshot screens and alt-buffer screens use the viewport diff path.

   Returns the new render-model CellGrid."
  [session]
  (let [{:keys [current-screen]} @session]
    (if (and (= :main (:tui/buffer current-screen))
             (= :stream (:tui/projection current-screen)))
      (do (render-frame-main! session)
          (:render-model @session))
      (do (render-frame-alt! session)
          (:render-model @session)))))

(defn- render-frame-alt!
  "Original viewport-diff render path. Used by alt-buffer screens and
   by main-buffer screens that aren't `:stream` (snapshot/regions in
   main-buffer are deferred; they fall back here)."
  [session]
  (let [s              @session
        {:keys [viewport terminal-caps render-model ansi-style on-output]} s
        cfg            (input-area-config session)
        ;; `:placement :slot` (B): the input area renders *inside* the
        ;; screen layout at an `[:input-slot]` marker rather than the
        ;; bottom strip. Absent ⇒ the original path, byte-identical.
        slot?          (and cfg (= :slot (:placement cfg)))
        ia-height      (if slot? 0 (input-area-height session))
        screen-viewport (assoc viewport :height (max 1 (- (:height viewport) ia-height)))
        [screen-g new-stream-state] (compute-screen-grid session screen-viewport)
        overlay        (effective-overlay session)
        screen-g       (pad-to-viewport screen-g screen-viewport)
        ;; Compose screen content into a full-height canvas; if there's
        ;; an input area, its grid is placed in the bottom `ia-height`
        ;; rows (non-slot) or into the located slot box (slot).
        canvas         (cells/blank (:width viewport) (:height viewport))
        canvas         (cells/overlay canvas screen-g 0 0)
        slot-box       (when slot? (input-slot/find-slot-box canvas))
        {ia-grid :grid ia-cur :cursor-pos}
                       (cond
                         slot-box
                         (input-area/render
                           (:input-area s)
                           {:prompt     (or (:prompt cfg) "")
                            :width      (:w slot-box)
                            :height     (:h slot-box)
                            :multiline? (boolean (:multiline? cfg))})

                         (and (not slot?) (pos? ia-height))
                         (render-input-area session viewport)

                         :else nil)
        [canvas ia-origin]
                       (cond
                         slot-box
                         (let [{g :grid o :origin}
                               (input-slot/place-input canvas slot-box ia-grid)]
                           [g o])

                         slot?
                         ;; `:placement :slot` declared but no
                         ;; `[:input-slot]` in the hiccup — strip any
                         ;; stray sentinels and fall back gracefully.
                         (do (u/log ::input-slot-missing
                                    :screen (:query-id (:current-screen s)))
                             [(input-slot/strip-sentinels canvas) nil])

                         ia-grid
                         [(cells/overlay canvas ia-grid 0
                                         (- (:height viewport) ia-height))
                          [0 (- (:height viewport) ia-height)]]

                         :else
                         [canvas nil])
        composed       (compose-overlay canvas overlay viewport)
        runs           (diff/diff render-model composed)
        [out new-style] (ansi/emit runs (or terminal-caps {:color :truecolor})
                                   (or ansi-style {}))]
    (when (and on-output (seq out))
      (on-output out))
    ;; Cursor management: show + position at the input area's absolute
    ;; origin (`ia-origin`) when active and no overlay; hide otherwise.
    ;; Emitted after the diff so the cursor sits at the final position.
    (when on-output
      (let [cursor-active? (and ia-cur ia-origin (nil? overlay))
            prior          (:cursor-shown? s false)
            [ox oy]        (or ia-origin [0 0])
            [c-col c-row]  (or ia-cur [0 0])
            ;; ansi/cursor-position takes (row col) both 0-indexed and
            ;; emits as 1-indexed CSI parameters.
            move           (ansi/cursor-position (+ oy c-row) (+ ox c-col))]
        (cond
          cursor-active?
          ;; Block cursor (DECSCUSR 2) + show-cursor + positioning. The
          ;; block style matches Claude Code's input affordance and
          ;; makes the active-input row visually obvious. The shape
          ;; gets reset on leave-tui.
          (on-output (str (ansi/cursor-style-block) move (ansi/show-cursor)))

          prior
          (on-output (str (ansi/cursor-style-default-reset) (ansi/hide-cursor))))
        (swap! session assoc :cursor-shown? cursor-active?)))
    (swap! session
           (fn [s]
             (cond-> s
               true (assoc :render-model composed :ansi-style new-style)
               new-stream-state (assoc :stream-state new-stream-state))))
    composed))

;; ─────────────────────────────────────────────────────────────────────
;; Keymap stack assembly + dispatch
;; ─────────────────────────────────────────────────────────────────────

(defn- session-keymap-stack
  [session]
  (let [{:keys [current-screen session-keymap focus regions global-keymap]} @session
        overlay (effective-overlay session)]
    (keymap/build-stack
      {:overlay (when overlay (:keymap overlay))
       :region  (get regions focus)
       :screen  (:tui/keymap current-screen)
       :session session-keymap
       :global  global-keymap})))

(defn- session-state-snapshot [session]
  (select-keys @session [:focus :selection :input-area]))

(defn- dispatch-action!
  "Dispatch a tagged-tuple action against this session."
  [session action]
  (try
    (keymap/assert-action! action)
    (let [s @session
          ctx {:command-dispatcher
               (fn [name inputs _ctx]
                 (when-let [run-cmd (:process-command-fn s)]
                   (run-cmd (merge (:base-context s)
                                   {:tenant-id (:tenant-id s)
                                    :user-id   (:user-id s)
                                    :command   (merge {:command/name name} inputs)}))))
               :session-dispatcher
               (fn [action opts _ctx]
                 (handle-session-action session action opts _ctx))
               :session-state (session-state-snapshot session)}]
      (keymap/dispatch! ctx action))
    (catch Exception e
      (u/log ::dispatch-error :action action :error e))))

(defn- dispatch-palette-key!
  "Route a key event through the active palette overlay's substrate-owned
   handler. Resolves :select → fires :on-select with `:inputs-from-selection`
   bound, dismisses on :select/:dismiss, updates state on :update."
  [session key-event]
  (let [overlay (effective-overlay session)
        overlay-key (effective-overlay-key session)
        result (overlay/handle-palette-key overlay (:key key-event))]
    (case (:state result)
      :dismiss
      (let [on-dismiss (-> overlay :config :on-dismiss)]
        (swap! session assoc overlay-key nil)
        (when on-dismiss (dispatch-action! session on-dismiss)))

      :select
      ;; Bind the selected item's key-value into `:selection` on the session
      ;; so the standard `keymap/build-inputs` `:inputs-from-selection` path
      ;; resolves it. Then dismiss the overlay and dispatch `:on-select`.
      (let [{:keys [item]}    result
            {:keys [config]}  overlay
            on-select         (:on-select config)
            item-key          (:item-key  config)
            sel-value         (when item (get item item-key))]
        (swap! session assoc overlay-key nil :selection sel-value)
        (when on-select (dispatch-action! session on-select)))

      :update
      (swap! session assoc overlay-key (:palette result))

      ;; nil — ignored
      nil)))

(defn- dispatch-keymap-key!
  "Resolve `key-event` against the keymap stack and dispatch the
   matching action (or buffer a chord prefix)."
  [session key-event]
  (let [stack  (session-keymap-stack session)
        buf    (:sequence-buffer @session [])
        result (keymap/resolve-key stack buf (:key key-event))]
    (case (:state result)
      :match
      (do (swap! session assoc :sequence-buffer [])
          (dispatch-action! session (:action result)))

      :pending
      (swap! session assoc :sequence-buffer (:buffer result))

      :no-match
      (swap! session assoc :sequence-buffer []))))

(defn- dispatch-input-area-submission!
  "On submit from the input area, invoke `:process-command-fn` with the
   buffered text bound at the screen's `:tui/input :input-key` (default
   `:text`). The command's `:command/name` comes from the screen's
   `:tui/input :command`."
  [session submission]
  (let [s        @session
        screen   (:current-screen s)
        cfg      (:tui/input screen)
        cmd-name (:command cfg)
        in-key   (or (:input-key cfg) :text)
        run-cmd  (:process-command-fn s)]
    (when (and cmd-name run-cmd)
      (try
        (run-cmd (merge (:base-context s)
                        {:tenant-id (:tenant-id s)
                         :user-id   (:user-id s)
                         :command   {:command/name cmd-name
                                     in-key        submission}}))
        (catch Exception e
          (u/log ::input-submit-command-failed
                 :command cmd-name :error e))))))

(defn- dispatch-input-area-key!
  "Route a key event through the input-area state machine. Updates
   `:input-area` in the session; on `:submission`, dispatches the
   configured command; on `:passthrough?`, falls back to the keymap
   resolver so screen-level bindings (like `<esc>`) remain reachable."
  [session key-event]
  (let [s      @session
        screen (:current-screen s)
        opts   {:multiline? (boolean (-> screen :tui/input :multiline?))
                :history-max (or (-> screen :tui/input :history-max) 200)}
        {:keys [state submission passthrough?]}
        (input-area/handle-key (:input-area s) key-event opts)]
    (swap! session assoc :input-area state)
    (cond
      submission     (dispatch-input-area-submission! session submission)
      passthrough?   (dispatch-keymap-key! session key-event)
      :else          nil)))

(defn- dispatch-key!
  "Resolve `key-event` and dispatch. Routing order:
     1. Active palette overlay — substrate-owned handler.
     2. Active input-area (screen has `:tui/input`, no overlay) — the
        input-area state machine handles editing keys and on submit
        dispatches the configured command. Unhandled keys passthrough
        to (3).
     3. Keymap stack — overlay/region/screen/session/global per §10.3."
  [session key-event]
  (let [{:keys [current-screen]} @session
        overlay (effective-overlay session)]
    (cond
      (= :palette (:type overlay))
      (dispatch-palette-key! session key-event)

      (and (:tui/input current-screen)
           (not (input-blocking-overlay? overlay)))
      (dispatch-input-area-key! session key-event)

      :else
      (dispatch-keymap-key! session key-event))))

;; ─────────────────────────────────────────────────────────────────────
;; Event loop
;; ─────────────────────────────────────────────────────────────────────

(defn- safe-render-frame!
  "Wrap `render-frame!` so a misbehaving renderer logs and produces an
   error screen instead of silently killing the loop thread. The error
   gets emitted both to mulog and to STDERR — JLine owns STDOUT, so any
   `println` / mulog console publisher will trample the TUI."
  [session]
  (try
    (render-frame! session)
    (catch Throwable t
      (u/log ::render-frame-failed :error t)
      (binding [*out* *err*]
        (println "[tui-adapter] render-frame! threw:" (.getMessage t))
        (.printStackTrace t *err*))
      ;; Best-effort error frame — render without going through the
      ;; screen's hiccup. If even this throws, swallow.
      (try
        (let [{:keys [viewport on-output ansi-style terminal-caps render-model]} @session
              g    (layout/render-element
                     [:col
                      [:text {:fg :red :bold? true} "TUI render failed:"]
                      [:text (.getMessage t)]
                      [:text {:dim? true} "Press 'q' to quit; see STDERR for stacktrace."]]
                     viewport)
              g    (let [base (cells/blank (:width viewport) (:height viewport))]
                     (cells/overlay base g 0 0))
              runs (diff/diff render-model g)
              [out new-style] (ansi/emit runs (or terminal-caps {:color :truecolor})
                                         (or ansi-style {}))]
          (when (and on-output (seq out)) (on-output out))
          (swap! session assoc :render-model g :ansi-style new-style))
        (catch Throwable t2
          (u/log ::error-frame-failed :error t2))))))

(defn run-loop!
  "Run the session's render loop. Blocks. Should be called on a dedicated
   thread. Exits cleanly when `:running?` flips false or when channels
   close. Render exceptions are caught by `safe-render-frame!` so the
   loop survives."
  [session]
  (safe-render-frame! session)
  (loop []
    (let [{:keys [sub-chan input-ch resize-ch running?]} @session]
      (when running?
        (let [chans   (cond-> [input-ch resize-ch (timeout 30000)]
                        sub-chan (conj sub-chan))
              [val port] (alts!! chans)]
          (cond
            ;; Liveness timeout — no-op
            (nil? val)
            (recur)

            ;; Resize. `input/resize-event` carries `:size [w h]` (a
            ;; vector); every renderer wants `{:width :height}`. Normalize
            ;; here — the single consumer — and ignore malformed/zero
            ;; sizes (keep the prior viewport) so a resize never feeds a
            ;; nil width/height into the render path.
            (= port resize-ch)
            (let [[w h] (:size val)
                  vp    (when (and (number? w) (number? h))
                          {:width (max 1 (long w)) :height (max 1 (long h))})]
              (when vp
                (swap! session assoc
                       :viewport     vp
                       :render-model nil) ; force full redraw
                (safe-render-frame! session))
              (recur))

            ;; Input event
            (= port input-ch)
            (do
              ;; Dispatch the event we pulled, plus every other input
              ;; event already buffered. Unlike the sub-chan path,
              ;; input events are NOT idempotent — each keystroke must
              ;; be delivered to the keymap or the user loses input.
              (try (when (= :key (:type val))
                     (dispatch-key! session val))
                   (catch Throwable t
                     (u/log ::dispatch-failed :error t)))
              (loop []
                (when-let [more (poll! input-ch)]
                  (try (when (= :key (:type more))
                         (dispatch-key! session more))
                       (catch Throwable t
                         (u/log ::dispatch-failed :error t)))
                  (recur)))
              ;; After draining input, also clear sub-chan — the upcoming
              ;; render will reflect any pending domain events anyway, so
              ;; coalescing them avoids redundant renders.
              (when-let [debounce (:debounce-ms @session)]
                (when (pos? debounce) (Thread/sleep ^long debounce)))
              (when sub-chan (subscription/drain-channel sub-chan))
              (when (:running? @session)
                (safe-render-frame! session))
              (recur))

            ;; Subscription event
            (= port sub-chan)
            (do
              (when-let [debounce (:debounce-ms @session)]
                (when (pos? debounce) (Thread/sleep ^long debounce)))
              (subscription/drain-channel sub-chan)
              (subscription/drain-channel input-ch)
              (safe-render-frame! session)
              (recur))))))))

;; ─────────────────────────────────────────────────────────────────────
;; Construction
;; ─────────────────────────────────────────────────────────────────────

(defn- noop [& _] nil)

(defn make-session
  "Construct a new session atom and initialize per-session channels.

   Required `opts`:
     :tenant-id        UUID
     :user-id          UUID or nil
     :event-pubsub     pubsub instance (or nil for tests with no pubsub)
     :viewport         {:width n :height n}
     :on-output        (fn [^String s] ...)  — transport sink
     :default-screen   the screen to push on startup
     :process-query-fn (fn [ctx] -> {:query/result ...})

   Optional:
     :process-command-fn  (fn [ctx] -> ...)
     :base-context        merged into every query/command context
     :terminal-caps       {:color :truecolor :alt-screen? true ...}
     :debounce-ms         coalesce window for events (default 50)
     :session-keymap      session-tier keymap
     :global-keymap       global defaults"
  [{:keys [tenant-id user-id event-pubsub viewport on-output
           default-screen process-query-fn process-command-fn
           base-context terminal-caps debounce-ms
           session-keymap global-keymap]
    :or   {viewport       {:width 80 :height 24}
           terminal-caps  {:color :truecolor}
           debounce-ms    50
           on-output      noop
           session-keymap {}
           global-keymap  {}}}]
  (atom
    {:session-id        (random-uuid)
     :tenant-id         tenant-id
     :user-id           user-id
     :event-pubsub      event-pubsub
     :viewport          viewport
     :on-output         on-output
     :process-query-fn  process-query-fn
     :process-command-fn process-command-fn
     :base-context      base-context
     :terminal-caps     terminal-caps
     :debounce-ms       debounce-ms
     :session-keymap    session-keymap
     :global-keymap     global-keymap
     :current-screen    default-screen
     :screen-stack      []
     :overlay           nil
     :render-model      nil
     :ansi-style        nil
     :focus             nil
     :input-area        (when (:tui/input default-screen)
                          (input-area/initial-state))
     :sequence-buffer   []
     :sub-chan          nil
     ;; Input channel sized for keyboard autorepeat — `>!!` from the
     ;; JLine pump is blocking, so under-sizing this wedges input when
     ;; the session loop falls behind on a long render.
     :input-ch          (chan (async/sliding-buffer 1024))
     :resize-ch         (chan 8)
     :running?          true}))

(defn start!
  "Initialize the subscription for the default screen, then spawn the
   render loop on a daemon thread. Returns the session atom."
  [session]
  ;; Install the initial subscription for the default screen.
  (change-screen! session (:current-screen @session))
  (let [t (Thread.
            ^Runnable (fn [] (run-loop! session))
            (str "tui-session-" (:session-id @session)))]
    (.setDaemon t true)
    (.start t)
    (swap! session assoc :loop-thread t)
    session))

(defn stop!
  "Cleanly shut down a session: stop the loop, close channels, close
   the subscription, shut down the toast scheduler."
  [session]
  (swap! session assoc :running? false)
  (let [{:keys [sub-chan input-ch resize-ch scheduler on-shutdown]} @session]
    (when sub-chan  (close! sub-chan))
    (when input-ch  (close! input-ch))
    (when resize-ch (close! resize-ch))
    (when scheduler (.shutdownNow ^java.util.concurrent.ScheduledExecutorService scheduler))
    (when on-shutdown (on-shutdown))
    nil))
