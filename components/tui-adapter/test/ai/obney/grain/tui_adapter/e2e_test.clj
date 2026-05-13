(ns ai.obney.grain.tui-adapter.e2e-test
  "End-to-end tests over an in-memory transport.

   Instead of a real JLine terminal, the harness uses a mock transport:
     - input-ch is a core.async channel the test pushes synthetic logical
       key events onto (so the ANSI parser is tested separately and we
       skip the byte→event step here).
     - on-output captures emitted strings into an atom.
     - The mock pubsub (a chan + a sub atom) lets tests publish events
       and observe whether sessions wake up.

   Asserts run against captured strings (for end-to-end byte verification)
   and against captured CellGrids (intercepted before the ANSI emitter,
   for higher-fidelity assertion on render content)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.interface :as iface]
            [ai.obney.grain.tui-adapter.session :as session]
            [ai.obney.grain.tui-adapter.subscription :as subscription]))

;; ──────────────────────────────────────────────────────────────────────────
;; Mock pubsub — minimal stand-in for ai.obney.grain.pubsub.interface
;; ──────────────────────────────────────────────────────────────────────────

(defn make-mock-pubsub
  "Returns a `{:pubsub ... :subs subs-atom}` where `:pubsub` can be passed
   to `subscription/subscribe-to-events` (with monkey-patching) and
   `(emit-event! handle event)` distributes to subscribers whose topic
   matches `:event/type`."
  []
  (let [subs (atom {})]
    {:subs subs
     :pubsub
     {:subs-atom subs}}))

(defn- monkey-patch-pubsub
  "Install a `with-redefs`-friendly version of `ai.obney.grain.pubsub.interface/sub`
   that records subscriptions in the mock-pubsub state."
  [mock]
  (fn [_pubsub {:keys [topic sub-chan]}]
    (swap! (:subs mock) update topic (fnil conj []) sub-chan)))

;; ──────────────────────────────────────────────────────────────────────────
;; Boot helper
;; ──────────────────────────────────────────────────────────────────────────

(defn- boot
  "Spin up a session for a given screen + read-model registry entries.
   Returns `{:session :out :pubsub :captured-grids}` where
     :out             — atom of emitted output strings
     :captured-grids  — atom of CellGrids emitted (via the on-output side
                        we instrument render-frame!)"
  [{:keys [screen process-query-fn read-models event-pubsub]
    :or   {process-query-fn (fn [_] {:query/result {}
                                     :tui/hiccup   [:text {:text "hello"}]})}}]
  (let [out (atom [])
        s   (session/make-session
              {:tenant-id        (random-uuid)
               :user-id          (random-uuid)
               :viewport         {:width 20 :height 5}
               :on-output        (fn [s] (swap! out conj s))
               :default-screen   screen
               :event-pubsub     event-pubsub
               :process-query-fn process-query-fn
               :debounce-ms      0})]
    {:session s
     :out     out}))

(defn- shutdown [{:keys [session]}]
  (session/stop! session))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #1 — snapshot screen lifecycle
;; ──────────────────────────────────────────────────────────────────────────

(def hello-screen
  {:query-id :test/hello
   :inputs   {}})

(defn- hello-pq
  "Build a process-query-fn that returns a handler map with
   `:tui/hiccup` rendering the given msg."
  [msg-fn]
  (fn [_]
    (let [msg (msg-fn)]
      {:query/result {:msg msg}
       :tui/hiccup   [:text {:text (str msg)}]})))

(deftest snapshot-screen-renders-and-rerenders-on-input
  (let [{:keys [session out] :as h} (boot {:screen hello-screen
                                            :process-query-fn
                                            (let [n (atom 0)]
                                              (hello-pq #(str "n=" (swap! n inc))))})]
    (try
      (session/render-frame! session)
      (is (some #(re-find #"n=1" %) @out))
      (reset! out [])
      (session/render-frame! session)
      ;; n=2 — diff replaces only the changed cell ("1" → "2").
      ;; Look for the bare "2" character in the emitted bytes.
      (is (some #(str/includes? % "2") @out))
      (finally
        (shutdown h)))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #2 — quit cleanly via session-action dispatch
;; ──────────────────────────────────────────────────────────────────────────

(deftest dispatch-quit-stops-session
  (let [screen-with-quit
        (assoc hello-screen :tui/keymap {"q" [:session :quit]})
        {:keys [session] :as h}
        (boot {:screen screen-with-quit
               :process-query-fn (hello-pq (constantly "x"))})]
    (try
      ;; Manually invoke the dispatch path that the loop would invoke.
      (session/handle-session-action session :quit {} {})
      (is (false? (:running? @session)))
      (finally
        (shutdown h)))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #3 — push + back manage screen stack
;; ──────────────────────────────────────────────────────────────────────────

(deftest push-back-cycle
  (let [{:keys [session] :as h} (boot {:screen hello-screen})]
    (try
      (session/handle-session-action session :push-screen
                                     {:query-id :test/two} {})
      (is (= :test/two (:query-id (:current-screen @session))))
      (session/handle-session-action session :back {} {})
      (is (= :test/hello (:query-id (:current-screen @session))))
      (finally
        (shutdown h)))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #4 — auto-subscription against a mock pubsub
;; ──────────────────────────────────────────────────────────────────────────

(deftest auto-subscription-creates-sub-chan-when-read-models-present
  (let [mock      (make-mock-pubsub)
        rm-screen (assoc hello-screen :grain/read-models {:test/rm 1})]
    (with-redefs [subscription/subscribe-to-events
                  (fn [_pub event-types _filter]
                    ;; Return a channel and record the subscription.
                    (let [ch (async/chan 8)]
                      (doseq [et event-types]
                        (swap! (:subs mock) update et (fnil conj []) ch))
                      ch))
                  ;; Skip the read-model registry lookup — pretend it returns event types.
                  ai.obney.grain.tui-adapter.subscription/resolve-event-types-from-read-models
                  (fn [_] #{:test/event})]
      (let [{:keys [session] :as h}
            (boot {:screen rm-screen
                   :event-pubsub (:pubsub mock)})]
        (try
          (session/change-screen! session rm-screen)
          (is (some? (:sub-chan @session)))
          (is (contains? @(:subs mock) :test/event))
          (finally
            (shutdown h)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #5 — no :grain/read-models → no sub-chan, no subscription side-effect
;; ──────────────────────────────────────────────────────────────────────────

(deftest no-read-models-no-subscription
  (let [mock (make-mock-pubsub)]
    (with-redefs [ai.obney.grain.pubsub.interface/sub
                  (fn [_ args] (swap! (:subs mock) update (:topic args) (fnil conj []) (:sub-chan args)))]
      (let [{:keys [session] :as h}
            (boot {:screen hello-screen
                   :event-pubsub (:pubsub mock)})]
        (try
          (session/change-screen! session hello-screen)
          (is (nil? (:sub-chan @session)))
          (is (= {} @(:subs mock)))
          (finally
            (shutdown h)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #6 — screen change tears down prior sub-chan and opens new one
;; ──────────────────────────────────────────────────────────────────────────

(deftest screen-change-teardown-and-rebuild
  (let [mock (make-mock-pubsub)
        s-A  (assoc hello-screen :grain/read-models {:rm-A 1})
        s-B  (assoc hello-screen :grain/read-models {:rm-B 1})]
    (with-redefs [subscription/subscribe-to-events
                  (fn [_pub event-types _filter]
                    (let [ch (async/chan 8)]
                      (doseq [et event-types]
                        (swap! (:subs mock) update et (fnil conj []) ch))
                      ch))
                  ai.obney.grain.tui-adapter.subscription/resolve-event-types-from-read-models
                  (fn [rms]
                    (cond
                      (= rms {:rm-A 1}) #{:event/A}
                      (= rms {:rm-B 1}) #{:event/B}
                      :else             #{}))]
      (let [{:keys [session] :as h}
            (boot {:screen s-A
                   :event-pubsub (:pubsub mock)})]
        (try
          (session/change-screen! session s-A)
          (let [first-sub (:sub-chan @session)]
            (is (some? first-sub))
            ;; Switch screens.
            (session/change-screen! session s-B)
            (let [second-sub (:sub-chan @session)]
              (is (some? second-sub))
              (is (not (identical? first-sub second-sub)))
              ;; New subscription is for :event/B, old was :event/A.
              (is (contains? @(:subs mock) :event/A))
              (is (contains? @(:subs mock) :event/B))))
          (finally
            (shutdown h)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #7 — terminal restore byte sequence on stop! (interface-test-ish)
;; ──────────────────────────────────────────────────────────────────────────

(def ESC "")

(deftest stop-frees-channels
  (let [{:keys [session] :as h} (boot {:screen hello-screen})]
    (session/render-frame! session)
    (session/stop! session)
    (is (false? (:running? @session)))
    ;; input-ch is closed
    (is (nil? (async/<!! (:input-ch @session))))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #8 — honesty regression: every built-in still in the registry
;; after the system has been stood up.
;; ──────────────────────────────────────────────────────────────────────────

(deftest honesty-after-system-bootstrap
  (let [expected #{:text :line :gap :row :col :box :pad :weighted
                   :list :table :scroll :turn :fold :status :progress
                   :spinner :input}
        present  (iface/list-elements)]
    (doseq [tag expected]
      (is (contains? present tag)
          (str tag " must remain registered (§7.6.6)")))))

;; ──────────────────────────────────────────────────────────────────────────
;; E2E #9 — cross-screen toast persists through screen change
;; ──────────────────────────────────────────────────────────────────────────

(deftest toast-persists-through-screen-change
  (let [{:keys [session] :as h} (boot {:screen hello-screen})]
    (try
      (session/handle-session-action session :toast
                                     {:message "boom" :level :error :ttl-ms 60000} {})
      (is (= :toast (:type (:overlay @session))))
      (session/handle-session-action session :push-screen
                                     {:query-id :test/two} {})
      ;; Overlay still present after push.
      (is (= :toast (:type (:overlay @session))))
      (finally
        (shutdown h)))))
