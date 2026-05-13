(ns ai.obney.grain.tui-adapter.transport.http-test
  "Unit tests for the v0.8 §4.3 HTTP+SSE transport.

   These tests drive the Pedestal interceptors' `:enter` functions
   directly with constructed contexts; no actual HTTP server is booted.
   For the SSE stream, we test the inner `screen-stream-loop` directly
   with a regular core.async channel as the 'event-ch' — the
   Pedestal `start-stream` plumbing is exercised in the e2e test
   (Phase 6)."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.transport.http :as http]))

;; ──────────────────────────────────────────────────────────────────────
;; Test harness: a fresh registry + opts builder
;; ──────────────────────────────────────────────────────────────────────

(defn- fresh-registry [] {:sessions-atom (atom {})})

(def ^:private hello-screen
  {:query-id       :hello/screen
   :inputs         {}
   :tui/buffer     :alt
   :tui/projection :snapshot})

(def ^:private hello-query-registry
  {:hello/screen {:tui/buffer     :alt
                  :tui/projection :snapshot}})

(defn- base-opts [registry process-query-fn process-command-fn]
  {:registry           registry
   :default-screen     hello-screen
   :process-query-fn   process-query-fn
   :process-command-fn process-command-fn
   :base-context       {}
   :event-pubsub       nil
   :query-registry-fn  (fn [] hello-query-registry)
   :tenant-resolver    (fn [_] :tenant-a)
   :user-resolver      (fn [_] :user-a)
   :debounce-ms        0})

(defn- pedestal-ctx
  "Build a minimal Pedestal context for invoking an interceptor's :enter."
  [request]
  {:request request})

(defn- read-response-edn [ctx]
  (-> ctx :response :body edn/read-string))

;; ──────────────────────────────────────────────────────────────────────
;; routes — basic shape
;; ──────────────────────────────────────────────────────────────────────

(deftest routes-include-three-endpoints
  (let [rs (http/routes (base-opts (fresh-registry)
                                   (fn [_] {:tui/hiccup [:text "x"]})
                                   (fn [_] {:command/result :ok})))
        paths (into #{} (map first) rs)]
    (is (contains? paths "/tui/session"))
    (is (contains? paths "/tui/screen/:ns/:name"))
    (is (contains? paths "/tui/command/:ns/:name"))))

;; ──────────────────────────────────────────────────────────────────────
;; POST /tui/session
;; ──────────────────────────────────────────────────────────────────────

(deftest session-handler-allocates-new-session
  (let [registry (fresh-registry)
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:text "x"]})
                            (fn [_] {:command/result :ok}))
        ;; Invoke the interceptor's :enter via the routes table
        intercep (-> (http/routes opts)
                     (->> (filter (fn [[p _ _ & _]] (= p "/tui/session"))) first)
                     (nth 2) first)
        out     ((:enter intercep)
                 (pedestal-ctx {:request-method :post
                                :body (pr-str {})}))
        body    (read-response-edn out)]
    (is (uuid? (:session body)))
    (is (= :hello/screen (:default-screen body)))
    ;; Session was registered.
    (is (contains? @(:sessions-atom registry) (:session body)))))

(deftest session-handler-resume-returns-existing-id
  ;; Phase 3 doesn't fully honor resume semantics (Phase 4 does), but
  ;; passing an existing session-id should return it, not allocate new.
  (let [registry (fresh-registry)
        existing-id (random-uuid)
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:text "x"]})
                            (fn [_] {:command/result :ok}))
        _        (swap! (:sessions-atom registry)
                        assoc existing-id
                        {:session-id existing-id :tenant-id :tenant-a :user-id :user-a
                         :last-activity-ms (System/currentTimeMillis)})
        intercep (-> (http/routes opts)
                     (->> (filter (fn [[p _ _ & _]] (= p "/tui/session"))) first)
                     (nth 2) first)
        out     ((:enter intercep)
                 (pedestal-ctx {:request-method :post
                                :body (pr-str {:resume existing-id})}))
        body    (read-response-edn out)]
    (is (= existing-id (:session body)))))

;; ──────────────────────────────────────────────────────────────────────
;; POST /tui/command/:ns/:name
;; ──────────────────────────────────────────────────────────────────────

(deftest command-handler-dispatches-to-process-command-fn
  (let [registry (fresh-registry)
        sid      (random-uuid)
        _        (swap! (:sessions-atom registry)
                        assoc sid
                        {:session-id sid :tenant-id :tenant-a :user-id :user-a})
        called   (atom nil)
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:text "x"]})
                            (fn [ctx]
                              (reset! called ctx)
                              {:command/result {:got (:command ctx)}}))
        intercep (-> (http/routes opts)
                     (->> (filter (fn [[p _ _ & _]] (= p "/tui/command/:ns/:name"))) first)
                     (nth 2) first)
        out     ((:enter intercep)
                 (pedestal-ctx {:request-method :post
                                :path-params    {:ns "demo" :name "change"}
                                :body (pr-str {:inputs {:demo/delta 3}
                                               :session sid})}))
        body    (read-response-edn out)]
    (is (true? (:ok body)))
    (is (= :demo/change (-> @called :command :command/name)))
    (is (= 3 (-> @called :command :demo/delta)))
    (is (= :tenant-a (:tenant-id @called)))))

(deftest command-handler-unknown-session-returns-404
  (let [registry (fresh-registry)
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:text "x"]})
                            (fn [_] {:command/result :ok}))
        intercep (-> (http/routes opts)
                     (->> (filter (fn [[p _ _ & _]] (= p "/tui/command/:ns/:name"))) first)
                     (nth 2) first)
        out     ((:enter intercep)
                 (pedestal-ctx {:request-method :post
                                :path-params    {:ns "demo" :name "change"}
                                :body (pr-str {:inputs {} :session (random-uuid)})}))]
    (is (= 404 (-> out :response :status)))))

;; ──────────────────────────────────────────────────────────────────────
;; SSE screen-stream — exercising the inner loop directly
;; ──────────────────────────────────────────────────────────────────────
;;
;; The Pedestal `sse/start-stream` plumbing requires a real HTTP server.
;; The interesting logic is in `screen-stream-loop` — it builds frames,
;; serializes them, and writes them to an event-ch. We can drive that
;; directly with a regular channel and a fake session.

(deftest screen-stream-loop-emits-initial-frame
  (let [registry (fresh-registry)
        sid      (random-uuid)
        screen   hello-screen
        session  {:session-id sid :tenant-id :tenant-a :user-id :user-a
                  :current-screen screen :overlay nil}
        _        (swap! (:sessions-atom registry) assoc sid session)
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:text {:text "hello"}]})
                            (fn [_] {:command/result :ok}))
        event-ch (async/chan 8)
        ;; Run the loop in a future so we can inspect events without
        ;; blocking. With no pubsub and no further events, it produces
        ;; the initial frame and idles in the timeout loop.
        ftr      (future
                   (#'http/screen-stream-loop event-ch opts (atom session) screen))]
    (try
      (let [evt (async/<!! (async/go (async/<! event-ch)))]
        (is (= "tui-frame" (:name evt)))
        (let [frm (edn/read-string (:data evt))]
          (is (= :hello/screen (-> frm :screen :query-id)))
          (is (= [:text {:text "hello"}] (:hiccup frm)))))
      (finally
        (async/close! event-ch)
        ;; The loop closes when event-ch closes / on InterruptedException;
        ;; cancel the future as belt-and-suspenders.
        (future-cancel ftr)))))

(deftest screen-stream-loop-serializes-error-frame
  (let [registry (fresh-registry)
        sid      (random-uuid)
        screen   hello-screen
        session  {:session-id sid :tenant-id :tenant-a :user-id :user-a
                  :current-screen screen :overlay nil}
        _        (swap! (:sessions-atom registry) assoc sid session)
        opts     (base-opts registry
                            (fn [_] (throw (ex-info "boom" {})))
                            (fn [_] {:command/result :ok}))
        event-ch (async/chan 8)
        ftr      (future
                   (#'http/screen-stream-loop event-ch opts (atom session) screen))]
    (try
      (let [evt (async/<!! (async/go (async/<! event-ch)))
            frm (edn/read-string (:data evt))]
        (is (= "tui-frame" (:name evt)))
        (is (some? (:error frm)))
        (is (= "boom" (-> frm :error :message))))
      (finally
        (async/close! event-ch)
        (future-cancel ftr)))))

(deftest screen-stream-loop-resolves-custom-elements-in-frame
  ;; Use the :test/sparkline fixture registered in frame_serialize_test.
  (require 'ai.obney.grain.tui-adapter.frame-serialize-test)
  (let [registry (fresh-registry)
        sid      (random-uuid)
        screen   hello-screen
        session  {:session-id sid :tenant-id :tenant-a :user-id :user-a
                  :current-screen screen :overlay nil}
        opts     (base-opts registry
                            (fn [_] {:tui/hiccup [:row [:test/sparkline {:width 3}]
                                                  [:text "ok"]]})
                            (fn [_] {:command/result :ok}))
        _        (swap! (:sessions-atom registry) assoc sid session)
        event-ch (async/chan 8)
        ftr      (future
                   (#'http/screen-stream-loop event-ch opts (atom session) screen))]
    (try
      (let [evt (async/<!! (async/go (async/<! event-ch)))
            frm (edn/read-string (:data evt))]
        ;; Custom element resolved to a :cells leaf with an embedded grid.
        (is (= :cells (-> frm :hiccup (nth 1) first)))
        (let [grid-attrs (-> frm :hiccup (nth 1) second)]
          (is (= 3 (-> grid-attrs :grid :width)))))
      (finally
        (async/close! event-ch)
        (future-cancel ftr)))))
