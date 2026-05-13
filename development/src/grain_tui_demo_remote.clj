(ns grain-tui-demo-remote
  "Manual demo for the v0.8 remote-topology TUI adapter.

   Boots the same counter app as `grain-tui-demo`, but instead of an
   in-process stdio transport, it stands up the v0.8 §4.3 HTTP+SSE
   server and a thin TUI client process (in a separate thread) that
   connects to it. The user's terminal is attached to the *client*; the
   *server* is the adapter sitting next to Grain.

   The wire flow:

     [user keystroke at tui-client]
        -> client resolves against frame's :keymap
        -> POST http://localhost:PORT/tui/command/demo/change  {:inputs ...}
        -> server's process-command-fn dispatches via Grain
        -> event published to pubsub
        -> server's screen-stream-loop wakes up, re-runs the query
        -> produces a Frame, resolves custom elements server-side
        -> EDN-serialized as `tui-frame` SSE event
        -> client deserializes, lays out at local viewport, diffs,
           emits ANSI to the user's terminal

   Run with:

     clojure -M:dev -m grain-tui-demo-remote

   The demo binds a free TCP port on 127.0.0.1, prints it, then opens
   the thin client against that endpoint. Press +/-/=/*/  to drive
   the counter, q to quit."
  (:require [grain-tui-demo :as demo]              ; reuse the demo's
                                                   ; defquery/defcommand
                                                   ; registrations
            [ai.obney.grain.tui-adapter.system :as tui-system]
            [ai.obney.grain.tui-client.core :as tui-client]
            [ai.obney.grain.webserver.core :as webserver]
            [integrant.core :as ig]))

;; ─────────────────────────────────────────────────────────────────────
;; System configuration — Grain primitives plus the HTTP transport
;; ─────────────────────────────────────────────────────────────────────

(defn- find-free-port []
  (let [ss (java.net.ServerSocket. 0)]
    (try (.getLocalPort ss)
         (finally (.close ss)))))

(defmethod ig/init-key ::port [_ _] (find-free-port))

(defmethod ig/init-key ::webserver
  [_ {:keys [port routes]}]
  (webserver/start {:http/port   port
                    :http/host   "127.0.0.1"
                    :http/routes routes
                    :http/join?  false}))

(defmethod ig/halt-key! ::webserver
  [_ server]
  (webserver/stop server))

;; The HTTP-routes init-key returns a handle map (`{:routes ... :gc
;; ...}`) — not the bare route set. We use a small adapter init-key to
;; unwrap `:routes` for the webserver.
(defmethod ig/init-key ::http-route-set
  [_ {:keys [http-handle]}]
  (:routes http-handle))

(def system-config
  {::demo/tenant-id        {}
   ::demo/cache-dir        {:tenant-id (ig/ref ::demo/tenant-id)}
   ::demo/event-pubsub     {}
   ::demo/event-store      {:event-pubsub (ig/ref ::demo/event-pubsub)}
   ::demo/cache            {:cache-dir    (ig/ref ::demo/cache-dir)}
   ::demo/base-context     {:event-store  (ig/ref ::demo/event-store)
                            :event-pubsub (ig/ref ::demo/event-pubsub)
                            :cache        (ig/ref ::demo/cache)
                            :tenant-id    (ig/ref ::demo/tenant-id)}
   ::demo/process-query-fn   {:base-context (ig/ref ::demo/base-context)}
   ::demo/process-command-fn {:base-context (ig/ref ::demo/base-context)}
   ::demo/tenant-resolver    {:tenant-id   (ig/ref ::demo/tenant-id)}
   ::demo/user-resolver      {}

   ::tui-system/tui-registry {}

   ::tui-system/tui-http-routes
   {:registry           (ig/ref ::tui-system/tui-registry)
    :event-pubsub       (ig/ref ::demo/event-pubsub)
    :base-context       (ig/ref ::demo/base-context)
    :default-screen     demo/counter-screen
    :process-query-fn   (ig/ref ::demo/process-query-fn)
    :process-command-fn (ig/ref ::demo/process-command-fn)
    :query-registry-fn  (fn []
                          @ai.obney.grain.query-processor.interface/query-registry*)
    :tenant-resolver    (ig/ref ::demo/tenant-resolver)
    :user-resolver      (ig/ref ::demo/user-resolver)
    :debounce-ms        50}

   ::http-route-set {:http-handle (ig/ref ::tui-system/tui-http-routes)}

   ::port {}

   ::webserver {:port   (ig/ref ::port)
                :routes (ig/ref ::http-route-set)}})

;; ─────────────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────────────

(defonce ^:private system* (atom nil))

(defn start!
  "Initialize the Grain + HTTP-transport side. Returns the running
   system map. The thin client is NOT started here — call
   `start-client!` to launch it once the server is up."
  []
  (when @system*
    (throw (ex-info "Demo already running — call (stop!) first" {})))
  (reset! system* (ig/init system-config))
  @system*)

(defn stop!
  ([]
   (when-let [s @system*] (stop! s)))
  ([system]
   (try (ig/halt! system) (catch Exception _ nil))
   (reset! system* nil)
   :stopped))

(defn base-url [system]
  (str "http://127.0.0.1:" (get system ::port)))

(defn start-client!
  "Start the thin TUI client against the running server. Blocks until
   the client loop terminates (via `q` / `[:session :quit]`)."
  [system]
  (tui-client/start! {:base-url (base-url system)}))

(defn -main
  "Boot the server-side system, then attach the thin client to the
   user's terminal. Designed for
       clojure -M:dev -m grain-tui-demo-remote"
  [& _args]
  (let [system (start!)]
    (println "Grain TUI demo (remote) — server at" (base-url system))
    (println "Connecting thin client; press q to quit.")
    (try
      (start-client! system)
      (finally
        (stop! system)
        (System/exit 0)))))

(comment
  (def s (start!))
  (base-url s)
  ;; In a separate REPL or terminal, run the client manually:
  ;;   clojure -M -m ai.obney.grain.tui-client.core <base-url>
  (stop!))
