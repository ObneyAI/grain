(ns ai.obney.grain.tui-adapter.system
  "Integrant components for the TUI adapter.

   Two keys:
     ::tui-registry        — the session registry atom (sidecar for out-of-band
                             ops: cross-screen toasts, force-refresh, introspection).
     ::tui-stdio-transport — the stdio transport; opens a JLine terminal,
                             starts a session, returns a handle.

   The adapter does NOT own the per-session pubsub subscription wiring —
   that's done by the session itself when a screen changes (see §4.1).
   It does inject the registry into the Grain context map so todo
   processors and admin tools can reach it."
  (:require [ai.obney.grain.tui-adapter.builtins]      ; load registers built-ins
            [ai.obney.grain.tui-adapter.session :as session]
            [ai.obney.grain.tui-adapter.transport.stdio :as stdio]
            [integrant.core :as ig]))

;; ─────────────────────────────────────────────────────────────────────
;; ::tui-registry
;; ─────────────────────────────────────────────────────────────────────

(defmethod ig/init-key ::tui-registry
  [_ _config]
  {:sessions-atom (atom {})})

(defmethod ig/halt-key! ::tui-registry
  [_ {:keys [sessions-atom]}]
  ;; Stop any sessions still in the registry.
  (doseq [[_id session] @sessions-atom]
    (try (session/stop! session) (catch Exception _ nil)))
  (reset! sessions-atom {}))

;; ─────────────────────────────────────────────────────────────────────
;; ::tui-stdio-transport
;; ─────────────────────────────────────────────────────────────────────

(defmethod ig/init-key ::tui-stdio-transport
  [_ {:keys [registry default-screen process-query-fn process-command-fn
             event-pubsub base-context tenant-resolver user-resolver
             debounce-ms session-keymap global-keymap]
      :as opts}]
  (let [handle  (stdio/start-stdio-session
                  {:default-screen     default-screen
                   :process-query-fn   process-query-fn
                   :process-command-fn process-command-fn
                   :event-pubsub       event-pubsub
                   :base-context       base-context
                   :tenant-resolver    tenant-resolver
                   :user-resolver      user-resolver
                   :debounce-ms        debounce-ms
                   :session-keymap     session-keymap
                   :global-keymap      global-keymap})
        session (:session handle)
        sid     (:session-id @session)]
    (swap! (:sessions-atom registry) assoc sid session)
    (assoc handle :registry registry)))

(defmethod ig/halt-key! ::tui-stdio-transport
  [_ handle]
  (when handle
    (when-let [session (:session handle)]
      (let [sid (:session-id @session)]
        (some-> handle :registry :sessions-atom (swap! dissoc sid))))
    (try (stdio/stop-stdio-session handle) (catch Exception _ nil))))
