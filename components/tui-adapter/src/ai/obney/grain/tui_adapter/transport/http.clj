(ns ai.obney.grain.tui-adapter.transport.http
  "Remote-topology transport: HTTP + SSE per spec v0.8 §4.3.

   Exposes three Pedestal routes:

     POST /tui/session                  — open or resume a session
     GET  /tui/screen/<ns>/<name>       — open an SSE stream for a screen
     POST /tui/command/<ns>/<name>      — dispatch a Grain command

   And three SSE event names on the screen stream:

     `tui-frame`    — a full frame as EDN
     `tui-toast`    — a transient overlay {:message :level :ttl-ms}
     `tui-session`  — server-initiated session control event

   Sessions are held by a registry shared with the stdio transport
   (§12.3 mixed deployments). Sessions outlive their SSE streams per
   §9.2: a client that crashes mid-stream can reconnect with the same
   session-id and pick up current state on the next frame. Idle
   sessions are garbage-collected after `:idle-timeout-ms` (default
   30 minutes).

   The adapter is *always in-process with Grain*; the HTTP layer just
   serializes a frame and pushes it onto an `event-ch`. The thin TUI
   client (Phase 5) consumes those frames, lays them out at its own
   viewport, and posts commands back."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [com.brunobonacci.mulog :as u]
            [io.pedestal.http.sse :as sse]
            [ai.obney.grain.tui-adapter.frame :as frame]
            [ai.obney.grain.tui-adapter.subscription :as subscription]))

;; ─────────────────────────────────────────────────────────────────────
;; Registry helpers
;; ─────────────────────────────────────────────────────────────────────

(def ^:const default-idle-timeout-ms
  "Default session idle timeout per §9.2 — 30 minutes."
  (* 30 60 1000))

(defn- now-ms [] (System/currentTimeMillis))

(defn- get-session [registry sid]
  (some-> registry :sessions-atom deref (get sid)))

(defn- put-session! [registry sid session]
  (some-> registry :sessions-atom (swap! assoc sid session))
  sid)

(defn- remove-session! [registry sid]
  (some-> registry :sessions-atom (swap! dissoc sid))
  nil)

(defn- touch-session!
  "Bump a session's `:last-activity-ms` to now. Called on every
   command POST and every frame emission so the idle GC sees recent
   activity."
  [registry sid]
  (some-> registry :sessions-atom
          (swap! update sid (fn [s] (when s (assoc s :last-activity-ms (now-ms))))))
  nil)

(defn- expired?
  "True when the session's last activity is older than the configured
   idle timeout. A session with no `:last-activity-ms` (never touched
   for some reason) is treated as expired."
  [session idle-timeout-ms]
  (let [t (:last-activity-ms session)]
    (or (nil? t)
        (> (- (now-ms) t) idle-timeout-ms))))

;; ─────────────────────────────────────────────────────────────────────
;; Screen-map construction from the query registry
;; ─────────────────────────────────────────────────────────────────────

(def ^:private tui-metadata-keys
  "The closed set of `:tui/*` keys we lift from a query's metadata into
   a screen map. Keeps parity with what `session/change-screen!` reads."
  [:tui/buffer :tui/projection :tui/segments
   :tui/refresh :tui/event-tags :tui/debounce-ms
   :tui/layout :tui/region-keymaps :tui/input
   :tui/keymap :tui/on-enter :tui/on-exit
   :grain/read-models])

(defn- screen-from-registry
  "Build a screen map for `query-id` by reading the query registry. The
   resulting map is the same shape as the screen passed to
   `session/make-session :default-screen` — just looked up by id rather
   than baked into the app's wiring."
  [query-registry query-id inputs]
  (when-let [entry (get query-registry query-id)]
    (-> entry
        (select-keys tui-metadata-keys)
        (assoc :query-id query-id :inputs (or inputs {})))))

;; ─────────────────────────────────────────────────────────────────────
;; EDN serialization for SSE
;; ─────────────────────────────────────────────────────────────────────

(defn- ->edn ^String [data]
  (pr-str data))

(defn- read-edn-body
  "Read the request body as a string and parse it as EDN. Returns nil
   on empty body or parse failure (caller decides what to do)."
  [request]
  (let [body (:body request)]
    (when body
      (try
        (let [s (if (string? body) body (slurp body))]
          (when (seq s) (edn/read-string s)))
        (catch Exception _ nil)))))

(defn- ok-edn [body]
  {:status  200
   :headers {"Content-Type" "application/edn"}
   :body    (->edn body)})

(defn- error-edn [status body]
  {:status  status
   :headers {"Content-Type" "application/edn"}
   :body    (->edn body)})

;; ─────────────────────────────────────────────────────────────────────
;; Query / command context construction
;; ─────────────────────────────────────────────────────────────────────

(defn- query-context
  [{:keys [base-context]} session query-id inputs]
  (merge (or base-context {})
         {:tenant-id (:tenant-id session)
          :user-id   (:user-id session)
          :query     (merge {:query/name query-id} (or inputs {}))}))

(defn- command-context
  [{:keys [base-context]} session command-name inputs]
  (merge (or base-context {})
         {:tenant-id (:tenant-id session)
          :user-id   (:user-id session)
          :command   (merge {:command/name command-name} (or inputs {}))}))

;; ─────────────────────────────────────────────────────────────────────
;; Frame production for HTTP — wraps frame/produce-frame + resolve
;; ─────────────────────────────────────────────────────────────────────

(defn- run-and-produce-frame
  "Run the configured query for `session`'s current screen, then build
   and resolve a Frame. Catches handler exceptions and translates them
   into an error frame, mirroring the local session-loop behavior."
  [{:keys [process-query-fn] :as opts} session]
  (let [screen (:current-screen session)
        ctx   (query-context opts session
                             (:query-id screen)
                             (:inputs screen))
        result (try
                 (process-query-fn ctx)
                 (catch Exception e
                   (u/log ::query-failed
                          :screen (:query-id screen)
                          :error  e)
                   {:query/error (.getMessage e)}))]
    (frame/resolve-frame
      (frame/produce-frame {:current-screen screen :overlay (:overlay session)}
                           result))))

(defn- write-frame!
  "Serialize and push a frame as a `tui-frame` SSE event. The caller is
   responsible for bumping the session's `:last-activity-ms` — keep
   side-effects on the registry out of this pure-ish I/O helper."
  [event-ch frm]
  (async/>!! event-ch {:name "tui-frame" :data (->edn frm)}))

;; ─────────────────────────────────────────────────────────────────────
;; SSE screen-stream loop
;; ─────────────────────────────────────────────────────────────────────

(defn- screen-stream-loop
  "Push the initial frame; subscribe to pubsub for the screen's
   read-models; on each (debounced) event, re-run the query and push a
   new frame. Mirrors datastar's stream-view-loop-events but emits
   `tui-frame` events carrying EDN-encoded Frames.

   Bumps the session's `:last-activity-ms` on every frame emission so
   the idle-GC treats an actively-streaming session as live."
  [event-ch
   {:keys [registry event-pubsub debounce-ms] :or {debounce-ms 50} :as opts}
   session-atom
   screen]
  (let [sid       (:session-id @session-atom)
        ctx       (query-context opts @session-atom (:query-id screen) (:inputs screen))
        sub-chan  (when event-pubsub
                    (subscription/subscribe-screen event-pubsub screen ctx))]
    (try
      ;; Initial frame
      (write-frame! event-ch (run-and-produce-frame opts @session-atom))
      (touch-session! registry sid)
      ;; Subscribe loop (or noop if no subscription was created)
      (when sub-chan
        (loop []
          (let [[val _port] (async/alts!! [sub-chan (async/timeout 30000)])]
            (cond
              ;; Subscription closed → exit loop cleanly.
              (and (nil? val)
                   (some-> sub-chan async/poll!)
                   nil)
              nil

              ;; Liveness timeout — emit a comment heartbeat is handled by
              ;; pedestal's start-stream heartbeat-delay; just continue.
              (nil? val)
              (recur)

              :else
              (do
                (when (pos? debounce-ms) (Thread/sleep (long debounce-ms)))
                (subscription/drain-channel sub-chan)
                (write-frame! event-ch (run-and-produce-frame opts @session-atom))
                (touch-session! registry sid)
                (recur))))))
      (catch InterruptedException _ nil)
      (catch Exception e
        (u/log ::screen-stream-error :error e))
      (finally
        (when sub-chan (async/close! sub-chan))
        (async/close! event-ch)))))

;; ─────────────────────────────────────────────────────────────────────
;; Pedestal interceptors / handler builders
;; ─────────────────────────────────────────────────────────────────────

(defn- session-handler-enter
  "POST /tui/session — open or resume a session. Body (EDN):
     {:resume <session-id-or-nil>}

   When `:resume` names an existing, non-expired session in the
   registry, the same id is returned and the session's
   `:last-activity-ms` is bumped. Otherwise a fresh session is
   allocated and registered.

   Response (EDN):
     {:session <uuid> :default-screen <query-id>}"
  [{:keys [registry default-screen tenant-resolver user-resolver idle-timeout-ms]
    :or   {idle-timeout-ms default-idle-timeout-ms}} pedestal-ctx]
  (let [request   (:request pedestal-ctx)
        body      (or (read-edn-body request) {})
        resume-id (:resume body)
        existing  (when resume-id (get-session registry resume-id))
        ;; Reuse only when the resumed session is still live.
        reuse?    (and existing (not (expired? existing idle-timeout-ms)))
        sid       (if reuse? resume-id (random-uuid))
        session   (if reuse?
                    (assoc existing :last-activity-ms (now-ms))
                    {:session-id      sid
                     :tenant-id       (when tenant-resolver (tenant-resolver request))
                     :user-id         (when user-resolver   (user-resolver   request))
                     :current-screen  nil
                     :overlay         nil
                     :last-activity-ms (now-ms)})]
    (put-session! registry sid session)
    (assoc pedestal-ctx :response
           (ok-edn {:session        sid
                    :default-screen (:query-id default-screen)}))))

(defn- screen-stream-enter
  "GET /tui/screen/:ns/:name — start an SSE stream for the screen
   identified by the path's namespace/name. Required query param:
     ?session=<session-id>
   Optional:
     ?inputs=<edn-encoded-map>

   Looks up the query in the registry, builds a screen map, attaches it
   to the session, and starts the screen-stream-loop on a Pedestal
   SSE stream."
  [opts pedestal-ctx]
  (let [{:keys [registry query-registry-fn]} opts
        request      (:request pedestal-ctx)
        path-params  (:path-params request)
        query-params (:query-params request)
        ns-part      (or (:ns path-params)  (get path-params "ns"))
        nm-part      (or (:name path-params) (get path-params "name"))
        query-id     (keyword ns-part nm-part)
        sid-str      (or (:session query-params) (get query-params "session"))
        sid          (try (parse-uuid sid-str) (catch Exception _ nil))
        session      (get-session registry sid)
        inputs-edn   (or (:inputs query-params) (get query-params "inputs"))
        inputs       (when inputs-edn
                       (try (edn/read-string inputs-edn) (catch Exception _ nil)))
        registry-map (when query-registry-fn (query-registry-fn))
        screen       (when (and session registry-map)
                       (screen-from-registry registry-map query-id inputs))]
    (cond
      (nil? session)
      (assoc pedestal-ctx :response (error-edn 404 {:error "Unknown session" :session sid-str}))

      (nil? screen)
      (assoc pedestal-ctx :response (error-edn 404 {:error "Unknown screen" :query-id query-id}))

      :else
      (do
        ;; Attach the screen to the session for the SSE lifetime.
        (some-> registry :sessions-atom
                (swap! update sid assoc :current-screen screen))
        (sse/start-stream
          (fn [event-ch _sse-ctx]
            (let [session-atom (atom (get-session registry sid))]
              (screen-stream-loop event-ch opts session-atom screen)))
          pedestal-ctx
          10)))))

(defn- command-handler-enter
  "POST /tui/command/:ns/:name — dispatch a Grain command. Body (EDN):
     {:inputs <map> :session <session-id>}
   Response (EDN):
     {:ok true :result <command-result>} on success
     {:ok false :error \"...\"} on failure"
  [{:keys [registry process-command-fn] :as opts} pedestal-ctx]
  (let [request      (:request pedestal-ctx)
        body         (or (read-edn-body request) {})
        path-params  (:path-params request)
        ns-part      (or (:ns path-params)  (get path-params "ns"))
        nm-part      (or (:name path-params) (get path-params "name"))
        command-name (keyword ns-part nm-part)
        sid          (try (parse-uuid (str (:session body))) (catch Exception _ nil))
        session      (get-session registry sid)]
    (cond
      (nil? session)
      (assoc pedestal-ctx :response (error-edn 404 {:error "Unknown session"
                                                    :session (:session body)}))

      :else
      (let [ctx    (command-context opts session command-name (:inputs body))
            result (try
                     (process-command-fn ctx)
                     (catch Exception e
                       (u/log ::command-failed
                              :command command-name
                              :error e)
                       {:cognitect.anomalies/category :cognitect.anomalies/fault
                        :cognitect.anomalies/message  (.getMessage e)}))]
        (touch-session! registry sid)
        (assoc pedestal-ctx :response
               (if (and (map? result)
                        (contains? result :cognitect.anomalies/category))
                 (error-edn 500 {:ok false :anomaly result})
                 (ok-edn {:ok true :result (:command/result result)})))))))

;; ─────────────────────────────────────────────────────────────────────
;; Idle session garbage collection (§9.2)
;; ─────────────────────────────────────────────────────────────────────

(defn sweep-expired-sessions!
  "Remove every session in `registry` whose `:last-activity-ms` is older
   than `idle-timeout-ms`. Returns the set of session-ids that were
   removed. Pure side-effecting helper exposed so tests can drive
   sweeping deterministically without waiting on a scheduler."
  [registry idle-timeout-ms]
  (let [now (now-ms)]
    (loop [removed #{}]
      (let [snap   @(:sessions-atom registry)
            stale  (into #{}
                         (keep (fn [[sid s]]
                                 (let [t (:last-activity-ms s)]
                                   (when (or (nil? t) (> (- now t) idle-timeout-ms))
                                     sid))))
                         snap)]
        (if (empty? stale)
          removed
          (do
            (doseq [sid stale]
              (swap! (:sessions-atom registry) dissoc sid))
            (u/log ::sessions-expired :count (count stale) :session-ids stale)
            (into removed stale)))))))

(defn start-idle-gc!
  "Start a background sweeper that periodically removes idle sessions.
   Returns a 'handle' usable by `stop-idle-gc!`.

   `opts`:
     :registry         — the shared registry.
     :idle-timeout-ms  — sessions older than this are dropped. Default
                         30 minutes (§9.2).
     :sweep-interval-ms — how often to sweep. Default 60 seconds."
  [{:keys [registry idle-timeout-ms sweep-interval-ms]
    :or   {idle-timeout-ms   default-idle-timeout-ms
           sweep-interval-ms 60000}}]
  (let [exec (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)
        task (.scheduleAtFixedRate
               exec
               ^Runnable (fn []
                           (try (sweep-expired-sessions! registry idle-timeout-ms)
                                (catch Throwable t
                                  (u/log ::gc-sweep-failed :error t))))
               (long sweep-interval-ms)
               (long sweep-interval-ms)
               java.util.concurrent.TimeUnit/MILLISECONDS)]
    {:executor exec :task task}))

(defn stop-idle-gc!
  [{:keys [executor] :as _handle}]
  (when executor
    (.shutdownNow ^java.util.concurrent.ScheduledExecutorService executor)))

;; ─────────────────────────────────────────────────────────────────────
;; Public: routes
;; ─────────────────────────────────────────────────────────────────────

(defn routes
  "Return the vector of Pedestal route tuples implementing v0.8 §4.3.

   `opts` is the configuration map shared by all routes:

     {:registry           — {:sessions-atom <atom>} (the shared registry)
      :default-screen     — fully-specified screen map for new sessions
      :process-query-fn   — (fn [ctx] -> handler-return)
      :process-command-fn — (fn [ctx] -> command-result)
      :base-context       — map merged into every query/command ctx
      :event-pubsub       — the pubsub handle
      :query-registry-fn  — (fn [] -> {query-id query-metadata}) — used
                            to build screen maps for SSE requests.
                            Typically `#(deref qp/query-registry*)`.
      :tenant-resolver    — (fn [request] -> tenant-id)  (optional)
      :user-resolver      — (fn [request] -> user-id)    (optional)
      :debounce-ms        — coalesce window for re-render. Default 50.}"
  [opts]
  (let [session-interceptor    {:name  ::session-handler
                                :enter #(session-handler-enter opts %)}
        screen-interceptor     {:name  ::screen-stream
                                :enter #(screen-stream-enter opts %)}
        command-interceptor    {:name  ::command-handler
                                :enter #(command-handler-enter opts %)}]
    #{["/tui/session"               :post [session-interceptor]
       :route-name ::tui-session]
      ["/tui/screen/:ns/:name"      :get  [screen-interceptor]
       :route-name ::tui-screen-stream]
      ["/tui/command/:ns/:name"     :post [command-interceptor]
       :route-name ::tui-command]}))
