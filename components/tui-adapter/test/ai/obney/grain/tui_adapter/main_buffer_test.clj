(ns ai.obney.grain.tui-adapter.main-buffer-test
  "Phase 1 tests: terminal lifecycle is buffer-aware. Alt-screen
   escapes for `:alt` screens; no alt-screen for `:main` screens.
   Plus the new stream-state fields.

   Phase 2+ tests for actual append-emission live alongside in this
   namespace as the feature lands."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.session :as session]
            [ai.obney.grain.tui-adapter.stream :as stream]
            [ai.obney.grain.tui-adapter.transport.stdio :as stdio]))

;; ──────────────────────────────────────────────────────────────────────
;; stream/empty-stream-state — new append-mode fields
;; ──────────────────────────────────────────────────────────────────────

(deftest empty-stream-state-includes-append-mode-fields
  (let [s (stream/empty-stream-state)]
    (is (= [] (:emitted-keys s)))
    (is (false? (:intro-printed? s)))
    ;; Existing fields still present (regression).
    (is (= {} (:segment-cache s)))
    (is (= [] (:last-keys s)))))

;; ──────────────────────────────────────────────────────────────────────
;; stdio/enter-tui! and leave-tui! — buffer-aware
;; ──────────────────────────────────────────────────────────────────────

(defn- capture
  "Build a capturing sink. Returns `{:sink f :out atom-of-strings}`."
  []
  (let [out (atom [])]
    {:sink (fn [s] (swap! out conj s))
     :out  out}))

(defn- combined [out] (apply str @out))

(deftest enter-tui-alt-emits-alt-screen-escape
  (let [{:keys [sink out]} (capture)]
    (stdio/enter-tui! sink :alt)
    (is (str/includes? (combined out) "[?1049h"))    ; CSI ?1049h = enter alt-screen
    (is (str/includes? (combined out) "[?25l"))      ; hide cursor
    (is (str/includes? (combined out) "[2J"))))      ; clear screen

(deftest enter-tui-main-does-not-emit-alt-screen
  (let [{:keys [sink out]} (capture)]
    (stdio/enter-tui! sink :main)
    (is (not (str/includes? (combined out) "[?1049h")))
    (is (not (str/includes? (combined out) "[2J"))
        "main-buffer entry should preserve the user's existing scrollback")
    (is (str/includes? (combined out) "[?25l")
        "cursor still hidden so segment emission doesn't flicker")
    (is (str/includes? (combined out) "\n")
        "newline so we start on a fresh row")))

(deftest enter-tui-default-is-alt-for-compat
  (let [{:keys [sink out]} (capture)]
    (stdio/enter-tui! sink)        ; no buffer arg
    (is (str/includes? (combined out) "[?1049h"))))

(deftest leave-tui-alt-emits-leave-alt-screen
  (let [{:keys [sink out]} (capture)]
    (stdio/leave-tui! sink :alt)
    (is (str/includes? (combined out) "[?1049l"))    ; leave alt-screen
    (is (str/includes? (combined out) "[?25h"))))    ; show cursor

(deftest leave-tui-main-skips-leave-alt-screen
  (let [{:keys [sink out]} (capture)]
    (stdio/leave-tui! sink :main)
    (is (not (str/includes? (combined out) "[?1049l")))
    (is (str/includes? (combined out) "[?25h"))
    (is (str/includes? (combined out) "[0m"))))     ; reset style

;; ──────────────────────────────────────────────────────────────────────
;; change-screen! emits buffer-flip ANSI when buffer changes
;; ──────────────────────────────────────────────────────────────────────

(def ^:private alt-screen   {:query-id :test/alt   :inputs {}
                             :tui/buffer :alt   :tui/projection :snapshot})
(def ^:private main-screen  {:query-id :test/main  :inputs {}
                             :tui/buffer :main  :tui/projection :stream
                             :tui/segments {:items :msgs :key :id :hiccup :tui/hiccup}})

(defn- make-session [{:keys [default-screen]}]
  (let [out (atom [])]
    {:out out
     :session
     (session/make-session
       {:tenant-id          (random-uuid)
        :user-id            (random-uuid)
        :event-pubsub       nil
        :viewport           {:width 30 :height 10}
        :on-output          (fn [s] (swap! out conj s))
        :default-screen     default-screen
        :process-query-fn   (fn [_] {:tui/hiccup [:text "x"]})
        :process-command-fn (fn [_] {:command/result :ok})
        :debounce-ms        0})}))

(deftest change-screen-alt-to-main-leaves-alt-screen
  (let [{:keys [session out]} (make-session {:default-screen alt-screen})]
    (reset! out [])
    (session/change-screen! session main-screen)
    (is (str/includes? (combined out) "[?1049l")
        "switching to a main-buffer screen drops out of alt-screen")
    (is (not (str/includes? (combined out) "[?1049h")))))

(deftest change-screen-main-to-alt-enters-alt-screen
  (let [{:keys [session out]} (make-session {:default-screen main-screen})]
    (reset! out [])
    (session/change-screen! session alt-screen)
    (is (str/includes? (combined out) "[?1049h")
        "switching to an alt-buffer screen takes over the screen")
    (is (str/includes? (combined out) "[2J")
        "and clears the new canvas")))

(deftest change-screen-same-buffer-skips-buffer-flip
  (let [alt2 (assoc alt-screen :query-id :test/alt2)
        {:keys [session out]} (make-session {:default-screen alt-screen})]
    (reset! out [])
    (session/change-screen! session alt2)
    ;; No alt-screen toggle — same buffer mode.
    (is (not (str/includes? (combined out) "[?1049l")))
    (is (not (str/includes? (combined out) "[?1049h")))))

;; ──────────────────────────────────────────────────────────────────────
;; Phase 2: stream/render-stream-main — pure append-emission
;; ──────────────────────────────────────────────────────────────────────

(defn- attach-h [m] (assoc m :tui/hiccup [:text {:text (str ">> " (:body m))}]))

(def ^:private msgs-spec {:items :msgs :key :id :hiccup :tui/hiccup})

(def ^:private append-opts
  {:width 30 :emission-row 5 :terminal-caps {:color :truecolor} :style {}})

(deftest render-stream-main-first-call-emits-all-segments
  (let [prior  (ai.obney.grain.tui-adapter.stream/empty-stream-state)
        result {:msgs [(attach-h {:id 1 :body "hello"})
                       (attach-h {:id 2 :body "world"})]}
        {:keys [state bytes]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec append-opts)]
    (is (= [1 2] (:emitted-keys state)))
    (is (str/includes? bytes ">> hello"))
    (is (str/includes? bytes ">> world"))
    ;; Each segment followed by a newline — the scroll-region scroll trigger.
    (is (= 2 (count (re-seq #"\n" bytes))))
    ;; Each segment preceded by cursor-position to (emission-row, 0).
    ;; CSI 6;1H = move to row 6 col 1 (1-indexed from emission-row 5).
    (is (re-find #"\[6;1H" bytes))))

(deftest render-stream-main-subsequent-call-emits-only-new
  (let [prior (-> (ai.obney.grain.tui-adapter.stream/empty-stream-state)
                  (assoc :emitted-keys [1 2]))
        result {:msgs [(attach-h {:id 1 :body "hello"})
                       (attach-h {:id 2 :body "world"})
                       (attach-h {:id 3 :body "again"})]}
        {:keys [state bytes]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec append-opts)]
    (is (= [1 2 3] (:emitted-keys state)))
    ;; Only the new one is emitted.
    (is (str/includes? bytes ">> again"))
    (is (not (str/includes? bytes ">> hello")))
    (is (not (str/includes? bytes ">> world")))))

(deftest render-stream-main-emits-multiline-text-as-visual-rows
  (let [prior  (ai.obney.grain.tui-adapter.stream/empty-stream-state)
        result {:msgs [{:id 1 :tui/hiccup [:text "abc\ndefghijkl"]}]}
        {:keys [state bytes]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec (assoc append-opts :width 8))]
    (is (= [1] (:emitted-keys state)))
    (is (str/includes? bytes "abc"))
    (is (str/includes? bytes "defghijk"))
    (is (str/includes? bytes "l"))
    (is (= 3 (count (re-seq #"\n" bytes))))
    (is (= 3 (count (re-seq #"\[6;1H" bytes))))))

(deftest render-stream-main-no-new-segments-emits-nothing
  (let [prior (-> (ai.obney.grain.tui-adapter.stream/empty-stream-state)
                  (assoc :emitted-keys [1 2]))
        result {:msgs [(attach-h {:id 1 :body "a"})
                       (attach-h {:id 2 :body "b"})]}
        {:keys [bytes state]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec append-opts)]
    (is (= "" bytes))
    (is (= [1 2] (:emitted-keys state)))))

(deftest render-stream-main-append-violation-logs-and-emits-nothing
  ;; A previously-emitted key disappears — we can't un-scroll history.
  (let [prior (-> (ai.obney.grain.tui-adapter.stream/empty-stream-state)
                  (assoc :emitted-keys [1 2 3]))
        result {:msgs [(attach-h {:id 1 :body "a"})
                       ;; key 2 missing
                       (attach-h {:id 3 :body "c"})]}
        {:keys [bytes state]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec append-opts)]
    (is (= "" bytes)
        "violation should not emit bytes (terminal scrollback is committed)")
    (is (= [1 2 3] (:emitted-keys state))
        "state stays as prior — we don't pretend the violation happened")))

(deftest render-stream-main-style-threading
  ;; Verify the style state threads across segments so SGR sequences
  ;; aren't re-emitted redundantly.
  (let [prior  (ai.obney.grain.tui-adapter.stream/empty-stream-state)
        result {:msgs [{:id 1 :tui/hiccup [:text {:fg :red}    "A"]}
                       {:id 2 :tui/hiccup [:text {:fg :red}    "B"]}
                       {:id 3 :tui/hiccup [:text {:fg :green} "C"]}]}
        {:keys [bytes style]}
        (ai.obney.grain.tui-adapter.stream/render-stream-main
          prior result msgs-spec append-opts)]
    (is (str/includes? bytes "A"))
    (is (str/includes? bytes "B"))
    (is (str/includes? bytes "C"))
    ;; Final style retained green from the last segment.
    (is (= :green (:fg style)))))

;; ──────────────────────────────────────────────────────────────────────
;; Phase 2: session integration — driving a main+stream session
;; ──────────────────────────────────────────────────────────────────────

(def ^:private main-stream-screen
  {:query-id       :test/transcript
   :inputs         {}
   :tui/buffer     :main
   :tui/projection :stream
   :tui/segments   {:items :msgs :key :id :hiccup :tui/hiccup}
   :tui/input      {:command :test/say :prompt "> "}})

(deftest first-render-emits-scroll-region-and-intro
  (let [{:keys [session out]}
        (make-session
          {:default-screen
           (assoc main-stream-screen
                  :process-query-fn-marker :hello)})]
    (reset! out [])
    (with-redefs [;; Override the query stub for this one test to return
                  ;; an intro hiccup + one message.
                  ai.obney.grain.tui-adapter.session/render-frame!
                  ai.obney.grain.tui-adapter.session/render-frame!]
      (swap! session assoc :process-query-fn
             (fn [_]
               {:tui/hiccup [:text {:text "Welcome"}]
                :msgs [(attach-h {:id 1 :body "first"})]}))
      (session/render-frame! session))
    (let [c (combined out)]
      ;; Scroll region set: CSI top;bottom r
      (is (re-find #"\[\d+;\d+r" c)
          "first render should set a DECSTBM scroll region")
      ;; Intro printed somewhere
      (is (str/includes? c "Welcome"))
      ;; First segment emitted
      (is (str/includes? c ">> first")))
    ;; Stream state updated.
    (is (= [1] (:emitted-keys (:stream-state @session))))
    (is (true? (:intro-printed? (:stream-state @session))))))

(deftest subsequent-renders-only-emit-new-segments
  (let [pq-result (atom {:tui/hiccup [:text {:text "Welcome"}]
                         :msgs [(attach-h {:id 1 :body "first"})]})
        {:keys [session out]}
        (make-session
          {:default-screen main-stream-screen})]
    (swap! session assoc :process-query-fn (fn [_] @pq-result))
    (session/render-frame! session)
    (reset! out [])
    ;; Add a new message and re-render.
    (swap! pq-result update :msgs conj (attach-h {:id 2 :body "second"}))
    (session/render-frame! session)
    (let [c (combined out)]
      (is (str/includes? c ">> second"))
      ;; First message wasn't re-emitted (it's already in scrollback).
      (is (not (str/includes? c ">> first")))
      ;; Intro wasn't re-printed.
      (is (not (str/includes? c "Welcome"))))
    (is (= [1 2] (:emitted-keys (:stream-state @session))))))

(deftest chrome-emitted-each-frame-at-viewport-bottom
  (let [{:keys [session out]}
        (make-session {:default-screen main-stream-screen})]
    (swap! session assoc :process-query-fn
           (fn [_] {:tui/hiccup [:text "intro"]
                    :msgs       [(attach-h {:id 1 :body "x"})]}))
    (session/render-frame! session)
    (let [c (combined out)]
      ;; The prompt string "> " should appear (the chrome's prompt row).
      (is (str/includes? c "> "))
      ;; Chrome is 2 rows (rule + prompt, no hint). Viewport height 10
      ;; → chrome-top = 8 (0-indexed) = CSI 9;1H (1-indexed).
      (is (re-find #"\[9;1H" c))
      ;; The substrate also draws a horizontal rule at chrome-top.
      (is (str/includes? c "──")))))

(deftest typed-input-renders-locally-before-server-confirms
  ;; In main+stream mode, typing should update the chrome (showing the
  ;; buffer) without re-emitting any of the prior segments.
  (let [pq (atom {:tui/hiccup [:text "intro"]
                  :msgs       [(attach-h {:id 1 :body "first"})]})
        {:keys [session out]}
        (make-session {:default-screen main-stream-screen})]
    (swap! session assoc :process-query-fn (fn [_] @pq))
    (session/render-frame! session)
    (reset! out [])
    ;; Press 'h' — drives input-area state through dispatch.
    (#'session/dispatch-key! session {:type :key :key "h"})
    ;; The dispatcher itself doesn't re-render in our harness; do it
    ;; explicitly. The chrome should now show "> h" but the prior
    ;; segment ">> first" should NOT be re-emitted.
    (session/render-frame! session)
    (let [c (combined out)]
      (is (str/includes? c "> h"))
      (is (not (str/includes? c ">> first"))
          "prior segments stay in scrollback; only chrome redraws"))))

(deftest main-buffer-submit-repaints-sticky-input-without-stale-text
  ;; Regression: the main-buffer renderer used to repaint bottom chrome
  ;; with trailing newlines. In a terminal with DECSTBM set, that can
  ;; move the physical cursor and leave a stale submitted input row
  ;; visible beneath the sticky input.
  (let [cmd-log (atom [])
        screen  (assoc-in main-stream-screen [:tui/input :multiline?] true)
        {:keys [session out]}
        (make-session {:default-screen screen})]
    (swap! session assoc
           :process-query-fn (fn [_] {:tui/hiccup [:text "intro"]
                                      :msgs       []})
           :process-command-fn (fn [ctx]
                                 (swap! cmd-log conj (:command ctx))
                                 {:command/result :ok}))
    (#'session/dispatch-key! session {:type :key :key "h"})
    (#'session/dispatch-key! session {:type :key :key "i"})
    (session/render-frame! session)
    (reset! out [])
    (#'session/dispatch-key! session {:type :key :key "C-d"})
    (session/render-frame! session)
    (let [c (combined out)]
      (is (= "hi" (-> @cmd-log first :text)))
      (is (= "" (-> @session :input-area :value)))
      (is (str/includes? c "> "))
      (is (not (str/includes? c "> hi"))
          "submitted text should not remain in the sticky input repaint")
      (is (not (str/includes? c "\n"))
          "fixed chrome repaint should use absolute rows, not newlines"))))

(deftest violation-recovers-on-next-append
  ;; If a key disappears (violation logged), the function emits
  ;; nothing for that call. But subsequent calls with NEW keys past
  ;; the prior :emitted-keys should still work.
  (let [prior (-> (ai.obney.grain.tui-adapter.stream/empty-stream-state)
                  (assoc :emitted-keys [1 2]))
        ;; First call: violation (key 2 missing).
        r1 (ai.obney.grain.tui-adapter.stream/render-stream-main
             prior
             {:msgs [(attach-h {:id 1 :body "a"})]}
             msgs-spec append-opts)
        ;; Subsequent call: the read-model has recovered (re-includes
        ;; key 2) plus added key 3. The :emitted-keys is still [1 2]
        ;; (we didn't mutate state on violation), so only key 3 is new.
        r2 (ai.obney.grain.tui-adapter.stream/render-stream-main
             (:state r1)
             {:msgs [(attach-h {:id 1 :body "a"})
                     (attach-h {:id 2 :body "b"})
                     (attach-h {:id 3 :body "c"})]}
             msgs-spec append-opts)]
    (is (= "" (:bytes r1)))
    (is (str/includes? (:bytes r2) ">> c"))
    (is (not (str/includes? (:bytes r2) ">> a")))
    (is (= [1 2 3] (:emitted-keys (:state r2))))))
