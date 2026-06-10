(ns ai.obney.grain.tui-adapter.transport.stdio
  "Stdio transport: bridge JLine's `Terminal` to a session's input/output
   channels.

   Responsibilities:
     - Open a JLine `Terminal` in raw mode.
     - Negotiate `:terminal-caps` (color depth, alt-screen) from terminfo
       and `TERM`/`COLORTERM` env vars.
     - Pump bytes from `Terminal.reader()` through `input/feed` and onto
       the session's `input-ch`.
     - Listen for `SIGWINCH` and forward `{:type :resize :size [w h]}`
       onto `resize-ch`.
     - Provide an `on-output` sink that writes bytes to `Terminal.writer()`.
     - Handle terminal restore on shutdown (leave alt-screen, reset cursor).

   In MVS we open one stdio transport, which yields one session bound to
   the controlling terminal."
  (:require [clojure.core.async :as async]
            [clojure.string]
            [com.brunobonacci.mulog :as u]
            [ai.obney.grain.tui-adapter.ansi :as ansi]
            [ai.obney.grain.tui-adapter.input :as input]
            [ai.obney.grain.tui-adapter.session :as session])
  (:import (org.jline.terminal Terminal Terminal$Signal Terminal$SignalHandler TerminalBuilder)))

;; ─────────────────────────────────────────────────────────────────────
;; Capability detection
;; ─────────────────────────────────────────────────────────────────────

(defn detect-color-depth
  "Read TERM and COLORTERM from environment to choose color depth."
  []
  (let [term      (or (System/getenv "TERM") "")
        colorterm (or (System/getenv "COLORTERM") "")]
    (cond
      (or (= "truecolor" colorterm) (= "24bit" colorterm))
      :truecolor
      (or (re-find #"256" term)
          (= "xterm-256color" term))
      :c256
      (or (re-find #"^xterm" term) (re-find #"^screen" term)
          (re-find #"^tmux" term))
      :c16
      :else
      :mono)))

(defn env-color-override
  "Operator knob: `GRAIN_TUI_COLOR=truecolor|c256|c16|mono` forces the
   color depth regardless of TERM/COLORTERM. This is the escape hatch
   for environments where COLORTERM lies (e.g. `truecolor` exported in a
   shell profile while the actual emulator is 256-color) — detection
   alone can't know the emulator is lying, so the operator overrides."
  []
  (some-> (System/getenv "GRAIN_TUI_COLOR")
          clojure.string/trim
          clojure.string/lower-case
          keyword
          #{:truecolor :c256 :c16 :mono}))

(defn resolve-color-depth
  "Pure color-depth decision. Precedence (most → least authoritative):
     1. explicit `:color` in `override` (app/transport opt)
     2. `GRAIN_TUI_COLOR` env (operator knob)
     3. TERM/COLORTERM detection
   Always returns one of `#{:truecolor :c256 :c16 :mono}`."
  [override]
  (or (:color override)
      (env-color-override)
      (detect-color-depth)))

(defn negotiate-caps
  "Build the `:terminal-caps` map for `terminal`.

   `override` may be a full caps map (any of `:color`, `:alt-screen?`,
   `:width`, `:height`); its keys win on merge, with `:color` resolved
   via `resolve-color-depth`. No override preserves historical behavior."
  ([^Terminal terminal] (negotiate-caps terminal nil))
  ([^Terminal terminal override]
   (merge {:color       (resolve-color-depth override)
           :alt-screen? true              ; assume modern terminal
           :width       (max 1 (.getWidth terminal))
           :height      (max 1 (.getHeight terminal))}
          (dissoc override :color))))

;; ─────────────────────────────────────────────────────────────────────
;; Output sink
;; ─────────────────────────────────────────────────────────────────────

(defn make-output-sink
  "Return a function `(fn [^String s] ...)` that writes `s` to the
   terminal's writer and flushes."
  [^Terminal terminal]
  (let [w (.writer terminal)]
    (fn [^String s]
      (.write w s)
      (.flush w))))

;; ─────────────────────────────────────────────────────────────────────
;; Input pump
;; ─────────────────────────────────────────────────────────────────────

(def ^:private escape-flush-timeout-ms
  "How long to wait for a follow-up byte before a lone ESC is delivered
   as the <esc> key rather than treated as a sequence introducer."
  50)

(defn start-input-pump!
  "Start a daemon thread that reads bytes from `terminal`'s reader,
   feeds them into the parser, and pushes events onto `input-ch`.

   While the parser holds an incomplete sequence the read is timed, so a
   bare Escape press resolves to a `<esc>` key event after
   `escape-flush-timeout-ms` instead of hanging until the next keystroke.

   Returns a function that stops the pump."
  [^Terminal terminal input-ch]
  (let [running? (atom true)
        reader   (.reader terminal)
        t        (Thread.
                   ^Runnable
                   (fn []
                     (let [parser (atom (input/make-parser))
                           emit!  (fn [evs]
                                    (doseq [ev evs]
                                      (async/>!! input-ch ev)))]
                       (try
                         (while @running?
                           (let [b (if (input/pending? @parser)
                                     (.read reader (long escape-flush-timeout-ms))
                                     (.read reader))]
                             (cond
                               (>= b 0)
                               (let [[evs new-parser] (input/feed @parser [b])]
                                 (reset! parser new-parser)
                                 (emit! evs))

                               ;; Timed read expired with bytes pending —
                               ;; resolve a lone ESC into the <esc> key.
                               (= (long b) org.jline.utils.NonBlockingReader/READ_EXPIRED)
                               (let [[evs new-parser] (input/flush-lone-esc @parser)]
                                 (reset! parser new-parser)
                                 (emit! evs)))))
                         (catch InterruptedException _
                           nil)
                         (catch Exception e
                           (u/log ::input-pump-error :error e)))))
                   "tui-stdio-input")]
    (.setDaemon t true)
    (.start t)
    (fn []
      (reset! running? false)
      (try (.interrupt t) (catch Exception _ nil)))))

;; ─────────────────────────────────────────────────────────────────────
;; Resize handler
;; ─────────────────────────────────────────────────────────────────────

(defn install-winch-handler!
  "Register a SIGWINCH handler that emits `{:type :resize :size [w h]}`
   onto `resize-ch`. Returns the previous handler so callers can restore
   on shutdown."
  [^Terminal terminal resize-ch]
  (.handle terminal Terminal$Signal/WINCH
           (reify Terminal$SignalHandler
             (handle [_ _]
               (let [w (.getWidth terminal)
                     h (.getHeight terminal)]
                 (async/offer! resize-ch (input/resize-event w h)))))))

;; ─────────────────────────────────────────────────────────────────────
;; Terminal lifecycle
;; ─────────────────────────────────────────────────────────────────────

(defn open-terminal
  "Open a JLine system terminal in raw mode."
  ^Terminal []
  (let [t (-> (TerminalBuilder/builder)
              (.system true)
              (.build))]
    (.enterRawMode t)
    t))

(defn enter-tui!
  "Issue the ANSI sequence needed to start a TUI session. Branches on
   `buffer`:

     :alt  — enter the alt-screen, hide cursor, clear. The user's
             terminal contents are preserved and restored on leave.
     :main — stay in the main buffer; just hide the cursor and emit a
             newline so we start on a fresh row above the user's
             existing scrollback. The substrate will write segments
             into the main buffer with newlines, letting the terminal
             scroll older content into its own scrollback (spec §6.3
             transcript pattern).

   Accepts either a buffer keyword or no argument (defaults to `:alt`
   for backward compatibility with callers that don't yet thread the
   screen's buffer mode through)."
  ([on-output] (enter-tui! on-output :alt))
  ([on-output buffer]
   (on-output
     (case buffer
       :main (str (ansi/hide-cursor) "\n")
       (str (ansi/enter-alt-screen)
            (ansi/hide-cursor)
            (ansi/clear-screen))))))

(defn leave-tui!
  "Restore the terminal. Mirror of `enter-tui!`: for `:alt` sessions
   leaves the alt-screen (restoring prior contents); for `:main`
   sessions just resets styles and shows the cursor so the next thing
   the user types in their shell renders normally."
  ([on-output] (leave-tui! on-output :alt))
  ([on-output buffer]
   (on-output
     (case buffer
       :main (str (ansi/reset-style)
                  (ansi/cursor-style-default-reset)
                  (ansi/show-cursor)
                  "\n")
       (str (ansi/reset-style)
            (ansi/cursor-style-default-reset)
            (ansi/show-cursor)
            (ansi/leave-alt-screen))))))

;; ─────────────────────────────────────────────────────────────────────
;; Top-level: start a session over stdio
;; ─────────────────────────────────────────────────────────────────────

(defn start-stdio-session
  "Open the terminal, build a session, wire up the input pump and resize
   handler, and start the session loop. Returns `{:session ... :terminal ...
   :stop-pump-fn ... :terminal-fn ...}` so the caller can shut down cleanly.

   Required `opts` mirror `session/make-session` plus:
     :default-screen (the screen map)
     :process-query-fn
     :process-command-fn (optional)
     :event-pubsub
     :tenant-resolver  (fn [user] -> tenant-id)  optional
     :user-resolver    (fn []     -> user-id)    optional
     :base-context"
  [opts]
  (let [terminal     (open-terminal)
        ;; Caps override (most → least authoritative):
        ;;   :terminal-caps opt > :color opt > GRAIN_TUI_COLOR env >
        ;;   TERM/COLORTERM detection. No override ⇒ historical behavior.
        override     (cond
                       (:terminal-caps opts) (:terminal-caps opts)
                       (:color opts)         {:color (:color opts)}
                       :else                 nil)
        caps         (negotiate-caps terminal override)
        on-output    (make-output-sink terminal)
        user-id      ((:user-resolver opts (constantly nil)))
        tenant-id    ((:tenant-resolver opts (constantly (random-uuid))) user-id)
        ;; The default-screen's :tui/buffer drives the initial
        ;; terminal lifecycle. Defaults to :alt when not declared.
        buffer       (or (-> opts :default-screen :tui/buffer) :alt)
        sess-opts    (-> opts
                         (dissoc :tenant-resolver :user-resolver :color)
                         (assoc :tenant-id tenant-id
                                :user-id   user-id
                                :viewport  {:width (:width caps) :height (:height caps)}
                                :on-output on-output
                                :terminal-caps caps))
        session      (session/make-session sess-opts)
        stop-pump    (start-input-pump! terminal (:input-ch @session))
        _            (install-winch-handler! terminal (:resize-ch @session))
        _            (enter-tui! on-output buffer)
        _            (swap! session assoc :on-shutdown
                            (fn []
                              (try (stop-pump)
                                   (catch Exception _ nil))
                              (try (leave-tui! on-output
                                               (or (-> @session :current-screen :tui/buffer) :alt))
                                   (catch Exception _ nil))
                              (try (.close terminal)
                                   (catch Exception _ nil))))]
    (session/start! session)
    {:session     session
     :terminal    terminal
     :stop-pump   stop-pump}))

(defn stop-stdio-session [{:keys [session]}]
  (session/stop! session))
