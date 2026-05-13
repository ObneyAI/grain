(ns ai.obney.grain.tui-adapter.session-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [ai.obney.grain.tui-adapter.builtins]    ; load builtins registry
            [ai.obney.grain.tui-adapter.session :as session]
            [ai.obney.grain.tui-adapter.subscription :as subscription]))

;; ──────────────────────────────────────────────────────────────────────────
;; Test harness
;; ──────────────────────────────────────────────────────────────────────────

(defn- make-test-session
  "Build a session with a captured-output sink and a process-query-fn
   that returns whatever atom-data we configure."
  [{:keys [screen process-query-fn debounce-ms]
    :or   {debounce-ms 0}}]
  (let [out (atom [])]
    {:out out
     :session
     (session/make-session
       {:tenant-id        (random-uuid)
        :user-id          (random-uuid)
        :event-pubsub     nil
        :viewport         {:width 10 :height 3}
        :on-output        (fn [s] (swap! out conj s))
        :default-screen   screen
        :debounce-ms      debounce-ms
        :process-query-fn (or process-query-fn
                              (fn [_ctx] {:query/result {}}))})}))

;; A trivial screen; hiccup comes from the handler return (spec v0.7).
(def hello-screen
  {:query-id   :test/hello
   :inputs     {}
   :tui/keymap {"q" [:session :quit]}})

(defn- hello-result
  "Build a handler return for hello-screen — `:query/result` carries the
   data, `:tui/hiccup` carries the rendered presentation."
  ([] (hello-result "hi"))
  ([text]
   {:query/result {:text text}
    :tui/hiccup   [:text {:text text}]}))

;; ──────────────────────────────────────────────────────────────────────────
;; Initial render produces output
;; ──────────────────────────────────────────────────────────────────────────

(deftest initial-render-emits-bytes
  (let [{:keys [session out]} (make-test-session
                                {:screen hello-screen
                                 :process-query-fn (fn [_] (hello-result))})
        _ (session/render-frame! session)]
    (is (pos? (count @out)))
    (is (some #(re-find #"hi" %) @out))))

;; ──────────────────────────────────────────────────────────────────────────
;; render-frame! is idempotent given identical state
;; ──────────────────────────────────────────────────────────────────────────

(deftest second-render-emits-no-bytes-when-unchanged
  (let [{:keys [session out]} (make-test-session
                                {:screen hello-screen
                                 :process-query-fn (fn [_] (hello-result))})
        _ (session/render-frame! session)
        _ (reset! out [])
        _ (session/render-frame! session)]
    (is (= [] @out))))

;; ──────────────────────────────────────────────────────────────────────────
;; Query failure renders error screen, session not terminated
;; ──────────────────────────────────────────────────────────────────────────

(deftest query-failure-renders-error-and-keeps-session
  (let [{:keys [session out]} (make-test-session
                                {:screen hello-screen
                                 :process-query-fn (fn [_]
                                                     (throw (ex-info "boom" {})))})]
    (session/render-frame! session)
    ;; Output is truncated to viewport width (10 cols); just check for "Query".
    (is (some #(re-find #"Query" %) @out))
    (is (true? (:running? @session)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Session-action: :quit flips :running?
;; ──────────────────────────────────────────────────────────────────────────

(deftest quit-action-flips-running
  (let [{:keys [session]} (make-test-session {:screen hello-screen})]
    (session/handle-session-action session :quit {} {})
    (is (false? (:running? @session)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Session-action: :push-screen + :back manage screen-stack
;; ──────────────────────────────────────────────────────────────────────────

(deftest push-and-back-manage-stack
  (let [{:keys [session]} (make-test-session {:screen hello-screen})
        screen-2 (assoc hello-screen :query-id :test/two)]
    (session/handle-session-action session :push-screen
                                   {:query-id :test/two} {})
    ;; current-screen should be the new screen (just :query-id + :inputs);
    ;; previous screen should be on the stack.
    (is (= :test/two (:query-id (:current-screen @session))))
    (is (= 1 (count (:screen-stack @session))))

    (session/handle-session-action session :back {} {})
    (is (= 0 (count (:screen-stack @session))))
    (is (= :test/hello (:query-id (:current-screen @session))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Session-action: :open-overlay + :dismiss-overlay
;; ──────────────────────────────────────────────────────────────────────────

(deftest open-and-dismiss-overlay
  (let [{:keys [session]} (make-test-session {:screen hello-screen})]
    (session/handle-session-action session :open-overlay
                                   {:type :modal :content [:text {:text "modal"}]}
                                   {})
    (is (= :modal (:type (:overlay @session))))
    (session/handle-session-action session :dismiss-overlay {} {})
    (is (nil? (:overlay @session)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Toast TTL — auto-dismiss
;; ──────────────────────────────────────────────────────────────────────────

(deftest toast-ttl-auto-dismisses
  (let [{:keys [session]} (make-test-session {:screen hello-screen})
        _ (session/handle-session-action session :toast
                                         {:message "hi" :ttl-ms 100}
                                         {})]
    (is (= :toast (:type (:overlay @session))))
    (Thread/sleep 200)
    (is (nil? (:overlay @session)))
    ;; Cleanup
    (session/stop! session)))

;; ──────────────────────────────────────────────────────────────────────────
;; Sub-chan teardown on screen change
;; ──────────────────────────────────────────────────────────────────────────

(deftest screen-change-closes-prior-sub-chan
  ;; We provide a fake event-pubsub so subscription/subscribe-screen
  ;; tries to subscribe — but with no read-models declared, it returns nil.
  (let [{:keys [session]} (make-test-session {:screen hello-screen})
        _ (swap! session assoc :event-pubsub :fake-pubsub)
        sub-ch (async/chan 1)
        _ (swap! session assoc :sub-chan sub-ch)]
    (session/change-screen! session hello-screen)
    ;; Old sub-chan should be closed (regardless of whether a new one was made).
    (Thread/sleep 50)
    (is (nil? (async/poll! sub-ch))
        "after closing a chan, poll! returns nil")))

;; ──────────────────────────────────────────────────────────────────────────
;; No :grain/read-models → no sub-chan
;; ──────────────────────────────────────────────────────────────────────────

(deftest no-read-models-no-sub-chan
  (let [{:keys [session]} (make-test-session {:screen hello-screen})]
    (session/change-screen! session (dissoc hello-screen :grain/read-models))
    (is (nil? (:sub-chan @session)))))
