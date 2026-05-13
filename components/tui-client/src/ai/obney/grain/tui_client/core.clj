(ns ai.obney.grain.tui-client.core
  "Thin TUI client — v0.8 §4.2 remote topology.

   Connects to a remote tui-adapter HTTP+SSE server, receives frames
   (EDN over SSE), lays them out at the local terminal's viewport,
   diffs to ANSI, and emits bytes. Reads stdin (via JLine raw mode),
   parses keys via the adapter's pure input library, resolves them
   against the frame's keymap, and dispatches:

     [:command name opts]  →  POST /tui/command/<ns>/<name>
     [:session :quit]      →  shut down loop, restore terminal
     [:session ...]        →  (currently MVP-limited; future phase
                               will add push/back/palette)

   The client holds *no* application logic; the protocol is in §4.3.
   This module is a few hundred lines by design."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [com.brunobonacci.mulog :as u]
            [ai.obney.grain.tui-adapter.keymap :as keymap]
            [ai.obney.grain.tui-client.http     :as http]
            [ai.obney.grain.tui-client.render   :as render]
            [ai.obney.grain.tui-client.sse      :as sse]
            [ai.obney.grain.tui-client.terminal :as term])
  (:import (org.jline.terminal Terminal)))

;; ─────────────────────────────────────────────────────────────────────
;; Session bootstrap
;; ─────────────────────────────────────────────────────────────────────

(defn open-session!
  "POST <base>/tui/session — open or resume a session. Returns
   `{:session <uuid> :default-screen <query-id>}` or throws on error."
  [{:keys [base-url resume http-client]}]
  (let [{:keys [status body raw]}
        (http/post-edn {:http-client http-client
                        :url (str base-url "/tui/session")
                        :body (cond-> {} resume (assoc :resume resume))})]
    (when (not= 200 status)
      (throw (ex-info "POST /tui/session failed"
                      {:status status :raw raw})))
    body))

(defn- screen-stream-url [base-url query-id session-id inputs]
  (let [{:keys [ns name]} (let [n (clojure.core/name query-id)
                                ns (clojure.core/namespace query-id)]
                            {:ns ns :name n})
        inputs-q (when (seq inputs)
                   (str "&inputs=" (java.net.URLEncoder/encode
                                     ^String (pr-str inputs) "UTF-8")))]
    (str base-url "/tui/screen/" ns "/" name
         "?session=" session-id
         inputs-q)))

;; ─────────────────────────────────────────────────────────────────────
;; Keymap stack assembly — narrower than session.clj since the client
;; only knows the frame's keymap (screen-level) and any overlay keymap.
;; ─────────────────────────────────────────────────────────────────────

(defn- keymap-stack-for
  "Per §10.3, the resolution order is: overlay > region > screen >
   session > global. The client supports the screen keymap and overlay
   keymap; region focus and session/global layers come in a later
   phase."
  [frame]
  (vec
    (concat (when-let [k (-> frame :overlay :keymap)] [k])
            (when-let [k (-> frame :keymap)]          [k]))))

;; ─────────────────────────────────────────────────────────────────────
;; Action dispatch
;; ─────────────────────────────────────────────────────────────────────

(defn- dispatch-command!
  "Issue a `[:command name opts]` action against the server. Builds the
   inputs from the (currently minimal) client session state, then POSTs
   to /tui/command/<ns>/<name>. The server response is logged and
   discarded; the resulting frame arrives via SSE."
  [{:keys [base-url http-client session-id]} [_ cmd-name opts]]
  (let [inputs   (keymap/build-inputs (or opts {}) {})
        cmd-ns   (namespace cmd-name)
        cmd-nm   (name cmd-name)
        url      (str base-url "/tui/command/" cmd-ns "/" cmd-nm)]
    (try
      (http/post-edn {:http-client http-client
                      :url url
                      :body {:inputs  inputs
                             :session session-id}})
      (catch Exception e
        (u/log ::command-post-failed :command cmd-name :error e)))))

;; ─────────────────────────────────────────────────────────────────────
;; Main loop
;; ─────────────────────────────────────────────────────────────────────

(defn- handle-sse-event
  "Returns updated render-state given an SSE event. Frames are parsed
   and painted; toasts/session events are deferred to a later phase."
  [render-state viewport on-output evt last-frame]
  (case (:name evt)
    "tui-frame"
    (let [frm (edn/read-string (:data evt))
          rs' (render/render-frame! render-state frm viewport on-output)]
      [rs' frm])

    ;; Future: render toasts via overlay layer. For MVP, log.
    "tui-toast"
    (do (u/log ::tui-toast :payload (:data evt))
        [render-state last-frame])

    ;; Future: react to server-initiated session events.
    "tui-session"
    (do (u/log ::tui-session :payload (:data evt))
        [render-state last-frame])

    ;; Unknown event name — log and continue.
    (do (u/log ::sse-event-unknown :name (:name evt))
        [render-state last-frame])))

(defn- handle-key
  "Resolve `key-evt` against `last-frame`'s keymap. Returns the action
   tuple to dispatch, or nil."
  [last-frame key-evt seq-buf]
  (let [stack  (keymap-stack-for last-frame)
        key-s  (or (:key key-evt) (:char key-evt))]
    (if (nil? key-s)
      {:state :no-match :buffer []}
      (keymap/resolve-key stack seq-buf key-s))))

(defn start!
  "Run the thin client.

   `opts`:
     :base-url    — e.g. \"http://localhost:8080\"
     :resume      — optional session-id to resume.
     :on-shutdown — optional fn called after the loop exits cleanly.

   Blocks until the loop terminates (via `[:session :quit]` or
   a fatal SSE error)."
  [{:keys [base-url resume on-shutdown]}]
  (let [http-client     (sse/default-http-client)
        ;; 1. Open session.
        {:keys [session default-screen]}
        (open-session! {:base-url base-url :resume resume
                        :http-client http-client})
        session-id      session
        _               (u/log ::client-session-opened
                               :session session-id
                               :default-screen default-screen)

        ;; 2. Open terminal.
        ^Terminal term  (term/open-terminal)
        on-output       (term/make-output-sink term)
        input-ch        (async/chan 1024)
        resize-ch       (async/chan (async/sliding-buffer 4))
        stop-input!     (term/start-input-pump! term input-ch)
        _               (term/install-winch-handler! term resize-ch)
        _               (term/enter-tui! on-output)

        ;; 3. Open SSE for the default screen.
        sse-event-ch    (async/chan 64)
        url             (screen-stream-url base-url default-screen session-id nil)
        sse-handle      (sse/start! {:url url
                                     :event-ch sse-event-ch
                                     :http-client http-client
                                     :on-error (fn [t] (u/log ::sse-error :error t))})

        running?        (atom true)
        cleanup
        (fn []
          (try (stop-input!) (catch Exception _ nil))
          (try ((:stop! sse-handle)) (catch Exception _ nil))
          (try (term/leave-tui! on-output) (catch Exception _ nil))
          (try (.close term) (catch Exception _ nil))
          (when on-shutdown (on-shutdown)))]
    (try
      (loop [render-state {:render-model nil
                           :ansi-style   nil
                           :terminal-caps {:color :truecolor}}
             last-frame   nil
             viewport     {:width (.getWidth term) :height (.getHeight term)}
             seq-buf      []]
        (if-not @running?
          nil
          (let [[v port] (async/alts!! [sse-event-ch input-ch resize-ch
                                        (async/timeout 30000)])]
            (cond
              ;; Timeout — keepalive; loop.
              (and (nil? v) (not= port sse-event-ch))
              (recur render-state last-frame viewport seq-buf)

              ;; SSE channel closed unexpectedly — shut down.
              (and (nil? v) (= port sse-event-ch))
              (reset! running? false)

              (= port sse-event-ch)
              (let [[rs' frm'] (handle-sse-event render-state viewport on-output v last-frame)]
                (recur rs' frm' viewport seq-buf))

              (= port resize-ch)
              (let [vp' {:width (or (first (:size v)) (:width viewport))
                         :height (or (second (:size v)) (:height viewport))}
                    rs' (if last-frame
                          (render/render-frame! (assoc render-state :render-model nil)
                                                last-frame vp' on-output)
                          render-state)]
                (recur rs' last-frame vp' seq-buf))

              (= port input-ch)
              (let [{:keys [state action buffer]} (handle-key last-frame v seq-buf)]
                (case state
                  :match
                  (case (first action)
                    :command
                    (do (dispatch-command! {:base-url base-url
                                            :http-client http-client
                                            :session-id session-id}
                                           action)
                        (recur render-state last-frame viewport []))

                    :session
                    (case (second action)
                      :quit    (do (reset! running? false)
                                   (recur render-state last-frame viewport []))
                      ;; Other session actions: refresh/back/push/etc.
                      ;; Future: open new SSE for push-screen, close on back.
                      (do (u/log ::session-action-deferred :action action)
                          (recur render-state last-frame viewport [])))
                    ;; Unknown action tag
                    (recur render-state last-frame viewport []))

                  :pending
                  (recur render-state last-frame viewport buffer)

                  ;; :no-match — drop key
                  (recur render-state last-frame viewport [])))

              :else
              (recur render-state last-frame viewport seq-buf)))))
      (catch InterruptedException _ nil)
      (catch Throwable t
        (u/log ::client-run-error :error t))
      (finally
        (cleanup)))))

(defn -main
  "Command-line entry. Usage:
     clojure -M -m ai.obney.grain.tui-client.core <base-url> [<resume-session-id>]"
  [& args]
  (let [base-url (or (first  args) "http://localhost:8080")
        resume   (when-let [s (second args)] (parse-uuid s))]
    (start! {:base-url base-url :resume resume})))
