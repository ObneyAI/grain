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
  (let [{:keys [sub-chan event-pubsub current-screen]} @session]
    (when sub-chan (close! sub-chan))
    (when current-screen
      (fire-hook! :tui/on-exit current-screen session))
    (warn-if-periodic-refresh! new-screen)
    (let [new-sub (when event-pubsub
                    (subscription/subscribe-screen
                      event-pubsub
                      new-screen
                      (query-context-for-screen session new-screen)))]
      (swap! session assoc
             :current-screen new-screen
             :sub-chan       new-sub
             :stream-state   nil))   ; reset stream cache on screen change
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

(defn- anomaly?
  "True when `result` carries a Cognitect anomaly category — the
   convention queries use to signal failure without throwing."
  [result]
  (and (map? result)
       (contains? result :cognitect.anomalies/category)))

(defn- compute-screen-grid
  "Compute the screen's CellGrid for the current frame. Branches on
   `:tui/projection`:
     :stream   — invokes `stream/render-stream` with the whole handler
                 return; the substrate extracts segments and reads each
                 segment's hiccup at the path declared by
                 `:tui/segments :hiccup`.
     :snapshot — reads `:tui/hiccup` from the handler return and lays out
                 via `layout/render-element`.

   Anomaly results (from a thrown query handler OR a returned Cognitect
   anomaly) render an error frame. Per spec §6.4, a snapshot handler that
   returns no `:tui/hiccup` produces an empty screen and logs a warning.

   Returns `[grid new-stream-state-or-nil]`."
  [session viewport]
  (let [{:keys [current-screen stream-state]} @session
        result    (run-query session)]
    (cond
      (:query/error result)
      [(layout/render-element
         (error-hiccup "Query error" (:query/error result))
         viewport)
       nil]

      (anomaly? result)
      [(layout/render-element
         (error-hiccup (str "Query failed (" (:cognitect.anomalies/category result) ")")
                       (:cognitect.anomalies/message result))
         viewport)
       nil]

      (= :stream (:tui/projection current-screen))
      (let [{:keys [state grid]}
            (stream/render-stream (or stream-state (stream/empty-stream-state))
                                  result
                                  (:tui/segments current-screen)
                                  viewport)]
        [grid state])

      :else
      (let [hiccup (:tui/hiccup result)]
        (when (nil? hiccup)
          (u/log ::missing-presentation
                 :screen (:query-id current-screen)))
        [(layout/render-element (or hiccup [:text {:text ""}]) viewport) nil]))))

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
          ovr-grid (when hiccup
                     (layout/render-element hiccup
                                            {:width  (max 1 (- (:width viewport) 4))
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

(defn render-frame!
  "Render the current screen + overlay, diff against :render-model, emit
   bytes via :on-output. Returns the new render-model CellGrid."
  [session]
  (let [{:keys [viewport overlay terminal-caps render-model ansi-style on-output]} @session
        [screen-g new-stream-state] (compute-screen-grid session viewport)
        screen-g  (pad-to-viewport screen-g viewport)
        composed  (compose-overlay screen-g overlay viewport)
        runs      (diff/diff render-model composed)
        [out new-style] (ansi/emit runs (or terminal-caps {:color :truecolor})
                                   (or ansi-style {}))]
    (when (and on-output (seq out))
      (on-output out))
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
  (let [{:keys [overlay current-screen session-keymap focus regions global-keymap]} @session]
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
  (let [{:keys [overlay]} @session
        result (overlay/handle-palette-key overlay (:key key-event))]
    (case (:state result)
      :dismiss
      (swap! session assoc :overlay nil)

      :select
      ;; Bind the selected item's key-value into `:selection` on the session
      ;; so the standard `keymap/build-inputs` `:inputs-from-selection` path
      ;; resolves it. Then dismiss the overlay and dispatch `:on-select`.
      (let [{:keys [item]}    result
            {:keys [config]}  overlay
            on-select         (:on-select config)
            item-key          (:item-key  config)
            sel-value         (when item (get item item-key))]
        (swap! session assoc :overlay nil :selection sel-value)
        (when on-select (dispatch-action! session on-select)))

      :update
      (swap! session assoc :overlay (:palette result))

      ;; nil — ignored
      nil)))

(defn- dispatch-key!
  "Resolve `key-event` against the keymap stack and dispatch.
   When the active overlay is a palette, route via the palette's
   substrate-owned handler instead."
  [session key-event]
  (let [{:keys [overlay]} @session]
    (if (= :palette (:type overlay))
      (dispatch-palette-key! session key-event)
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
          (swap! session assoc :sequence-buffer []))))))

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

            ;; Resize
            (= port resize-ch)
            (do (swap! session assoc :viewport (:size val))
                (swap! session assoc :render-model nil) ; force full redraw
                (safe-render-frame! session)
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
