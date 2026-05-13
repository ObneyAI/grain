(ns ai.obney.grain.tui-adapter.durability-test
  "Tests for v0.8 §9.2 session durability — sessions outlive their SSE
   connections; a reconnect with the same session-id resumes; idle
   sessions are GC'd after `:idle-timeout-ms`."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.transport.http :as http]))

(defn- fresh-registry [] {:sessions-atom (atom {})})

(def ^:private hello-screen
  {:query-id :hello/screen :inputs {}
   :tui/buffer :alt :tui/projection :snapshot})

(def ^:private hello-query-registry
  {:hello/screen {:tui/buffer :alt :tui/projection :snapshot}})

(defn- base-opts [registry & {:keys [process-query-fn process-command-fn
                                     idle-timeout-ms]
                              :or   {process-query-fn   (fn [_] {:tui/hiccup [:text "x"]})
                                     process-command-fn (fn [_] {:command/result :ok})}}]
  (cond-> {:registry           registry
           :default-screen     hello-screen
           :process-query-fn   process-query-fn
           :process-command-fn process-command-fn
           :base-context       {}
           :event-pubsub       nil
           :query-registry-fn  (fn [] hello-query-registry)
           :tenant-resolver    (fn [_] :tenant-a)
           :user-resolver      (fn [_] :user-a)
           :debounce-ms        0}
    idle-timeout-ms (assoc :idle-timeout-ms idle-timeout-ms)))

(defn- session-interceptor [opts]
  (-> (http/routes opts)
      (->> (filter (fn [[p _ _ & _]] (= p "/tui/session"))) first)
      (nth 2) first))

(defn- post-session [opts body]
  ((:enter (session-interceptor opts))
   {:request {:request-method :post :body (pr-str body)}}))

;; ──────────────────────────────────────────────────────────────────────
;; Resume — fresh allocation vs reuse
;; ──────────────────────────────────────────────────────────────────────

(deftest fresh-post-allocates-new-session
  (let [registry (fresh-registry)
        opts     (base-opts registry)
        body     (-> (post-session opts {}) :response :body edn/read-string)]
    (is (uuid? (:session body)))
    (is (contains? @(:sessions-atom registry) (:session body)))
    (is (some? (-> @(:sessions-atom registry)
                   (get (:session body))
                   :last-activity-ms)))))

(deftest resume-returns-same-id-for-live-session
  (let [registry (fresh-registry)
        opts     (base-opts registry)
        first-id (-> (post-session opts {}) :response :body edn/read-string :session)
        second   (-> (post-session opts {:resume first-id}) :response :body edn/read-string)]
    (is (= first-id (:session second)))))

(deftest resume-bumps-last-activity
  (let [registry (fresh-registry)
        opts     (base-opts registry)
        sid      (-> (post-session opts {}) :response :body edn/read-string :session)
        ;; Back-date the session within the 30-minute default window so
        ;; resume reuses it (not allocates fresh) and we can verify the
        ;; activity bump.
        back-dated (- (System/currentTimeMillis) 1000)
        _        (swap! (:sessions-atom registry)
                        update sid assoc :last-activity-ms back-dated)
        _        (post-session opts {:resume sid})
        bumped   (-> @(:sessions-atom registry) (get sid) :last-activity-ms)]
    (is (> bumped back-dated)
        "resume should refresh :last-activity-ms")))

(deftest expired-resume-allocates-fresh-session
  ;; idle-timeout-ms = 0 → every existing session is immediately expired.
  (let [registry (fresh-registry)
        opts     (base-opts registry :idle-timeout-ms 0)
        first-id (-> (post-session opts {}) :response :body edn/read-string :session)
        _        ;; Back-date the session so it's clearly stale even with 0 timeout
                 (swap! (:sessions-atom registry)
                        update first-id assoc :last-activity-ms 1)
        second   (-> (post-session opts {:resume first-id}) :response :body edn/read-string)]
    (is (not= first-id (:session second))
        "an expired :resume must allocate a new session, not reuse the dead one")))

;; ──────────────────────────────────────────────────────────────────────
;; Idle GC sweep
;; ──────────────────────────────────────────────────────────────────────

(deftest sweep-removes-expired-sessions
  (let [registry (fresh-registry)
        ;; Seed three sessions: two old, one fresh.
        now      (System/currentTimeMillis)
        _        (reset! (:sessions-atom registry)
                         {:a {:session-id :a :last-activity-ms (- now 5000)}
                          :b {:session-id :b :last-activity-ms (- now 5000)}
                          :c {:session-id :c :last-activity-ms now}})
        removed  (http/sweep-expired-sessions! registry 1000)
        live     @(:sessions-atom registry)]
    (is (= #{:a :b} removed))
    (is (= #{:c} (set (keys live))))))

(deftest sweep-removes-untouched-sessions
  ;; A session with no :last-activity-ms at all is treated as expired.
  (let [registry (fresh-registry)
        _        (reset! (:sessions-atom registry)
                         {:a {:session-id :a}})       ; no :last-activity-ms
        removed  (http/sweep-expired-sessions! registry 1000)]
    (is (= #{:a} removed))
    (is (empty? @(:sessions-atom registry)))))

(deftest sweep-leaves-fresh-sessions-alone
  (let [registry (fresh-registry)
        now      (System/currentTimeMillis)
        _        (reset! (:sessions-atom registry)
                         {:a {:session-id :a :last-activity-ms now}})
        removed  (http/sweep-expired-sessions! registry (* 30 60 1000))]
    (is (empty? removed))
    (is (contains? @(:sessions-atom registry) :a))))

;; ──────────────────────────────────────────────────────────────────────
;; Frame emission bumps activity (during a streaming SSE)
;; ──────────────────────────────────────────────────────────────────────

(deftest streaming-frame-bumps-last-activity
  ;; The screen-stream-loop should `touch-session!` after each frame so a
  ;; live SSE keeps its session out of the GC's reach.
  (let [registry (fresh-registry)
        sid      (random-uuid)
        session  {:session-id sid
                  :tenant-id :tenant-a :user-id :user-a
                  :current-screen hello-screen :overlay nil
                  ;; Start with an artificially-old activity time so the
                  ;; bump is observable.
                  :last-activity-ms 1}
        _        (swap! (:sessions-atom registry) assoc sid session)
        opts     (base-opts registry)
        event-ch (async/chan 8)
        ftr      (future
                   (#'http/screen-stream-loop event-ch opts (atom session) hello-screen))]
    (try
      ;; Wait for the initial frame to land
      (let [_ (async/<!! (async/go (async/<! event-ch)))
            bumped (-> @(:sessions-atom registry) (get sid) :last-activity-ms)]
        (is (> bumped 1)))
      (finally
        (async/close! event-ch)
        (future-cancel ftr)))))

;; ──────────────────────────────────────────────────────────────────────
;; Command POST bumps activity
;; ──────────────────────────────────────────────────────────────────────

(deftest command-bumps-last-activity
  (let [registry (fresh-registry)
        sid      (random-uuid)
        _        (swap! (:sessions-atom registry) assoc sid
                        {:session-id sid :tenant-id :tenant-a :user-id :user-a
                         :last-activity-ms 1})
        opts     (base-opts registry)
        cmd-int  (-> (http/routes opts)
                     (->> (filter (fn [[p _ _ & _]] (= p "/tui/command/:ns/:name"))) first)
                     (nth 2) first)]
    ((:enter cmd-int)
     {:request {:request-method :post
                :path-params    {:ns "demo" :name "noop"}
                :body (pr-str {:inputs {} :session sid})}})
    (is (> (-> @(:sessions-atom registry) (get sid) :last-activity-ms) 1))))

;; ──────────────────────────────────────────────────────────────────────
;; GC lifecycle
;; ──────────────────────────────────────────────────────────────────────

(deftest gc-handle-can-be-stopped-cleanly
  (let [registry (fresh-registry)
        handle   (http/start-idle-gc! {:registry registry
                                       :idle-timeout-ms 1000
                                       :sweep-interval-ms 100})]
    (is (some? (:executor handle)))
    (http/stop-idle-gc! handle)
    ;; After stop, the executor is shutdown.
    (is (.isShutdown ^java.util.concurrent.ScheduledExecutorService (:executor handle)))))
