(ns ai.obney.grain.tui-adapter.input-area-session-test
  "Integration tests for `:tui/input` wired through the session.
   Drives key events through `dispatch-key!` (the same path the
   run-loop uses) and asserts the input-area state + command
   dispatch."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.session :as session]))

(defn- make-test-session
  [{:keys [screen process-query-fn process-command-fn debounce-ms]
    :or   {process-query-fn (fn [_] {:tui/hiccup [:text "screen"]})
           debounce-ms      0}}]
  (let [out (atom [])]
    {:out out
     :session
     (session/make-session
       {:tenant-id          (random-uuid)
        :user-id            (random-uuid)
        :event-pubsub       nil
        :viewport           {:width 30 :height 6}
        :on-output          (fn [s] (swap! out conj s))
        :default-screen     screen
        :process-query-fn   process-query-fn
        :process-command-fn process-command-fn
        :debounce-ms        debounce-ms})}))

(def ^:private input-screen
  {:query-id   :test/chat
   :inputs     {}
   :tui/input  {:command :test/say :prompt "› "}
   :tui/keymap {"<esc>" [:session :quit]}})

(defn- press-key! [session k]
  (#'session/dispatch-key! session {:type :key :key k}))

(defn- press-keys! [session & ks]
  (doseq [k ks] (press-key! session k)))

;; ──────────────────────────────────────────────────────────────────────
;; Initialization
;; ──────────────────────────────────────────────────────────────────────

(deftest screen-with-input-initializes-input-area
  (let [{:keys [session]} (make-test-session {:screen input-screen})]
    (is (some? (:input-area @session)))
    (is (= ""  (-> @session :input-area :value)))
    (is (= 0   (-> @session :input-area :cursor)))))

(deftest screen-without-input-has-no-input-area
  (let [no-input-screen (dissoc input-screen :tui/input)
        {:keys [session]} (make-test-session {:screen no-input-screen})]
    (is (nil? (:input-area @session)))))

(deftest change-screen-resets-input-area
  (let [{:keys [session]} (make-test-session {:screen input-screen})
        ;; Type some characters first
        _ (press-keys! session "h" "i")
        _ (is (= "hi" (-> @session :input-area :value)))
        ;; Switch to a different screen with its own input area
        screen-2 (assoc input-screen :query-id :test/other)]
    (session/change-screen! session screen-2)
    (is (= "" (-> @session :input-area :value)))))

(deftest change-to-no-input-screen-clears-input-area
  (let [{:keys [session]} (make-test-session {:screen input-screen})
        no-input         (dissoc input-screen :tui/input)]
    (session/change-screen! session no-input)
    (is (nil? (:input-area @session)))))

;; ──────────────────────────────────────────────────────────────────────
;; Typing
;; ──────────────────────────────────────────────────────────────────────

(deftest printable-keys-update-buffer
  (let [{:keys [session]} (make-test-session {:screen input-screen})]
    (press-keys! session "h" "e" "l" "l" "o")
    (is (= "hello" (-> @session :input-area :value)))
    (is (= 5      (-> @session :input-area :cursor)))))

(deftest backspace-deletes
  (let [{:keys [session]} (make-test-session {:screen input-screen})]
    (press-keys! session "a" "b" "c" "<backspace>")
    (is (= "ab" (-> @session :input-area :value)))))

(deftest arrows-move-cursor
  (let [{:keys [session]} (make-test-session {:screen input-screen})]
    (press-keys! session "a" "b" "c" "<left>" "<left>")
    (is (= 1 (-> @session :input-area :cursor)))))

;; ──────────────────────────────────────────────────────────────────────
;; Submit dispatch
;; ──────────────────────────────────────────────────────────────────────

(deftest enter-submits-and-dispatches-command-with-text
  (let [cmd-log (atom [])
        {:keys [session]}
        (make-test-session
          {:screen input-screen
           :process-command-fn (fn [ctx] (swap! cmd-log conj (:command ctx))
                                          {:command/result :ok})})]
    (press-keys! session "h" "i" "<enter>")
    (is (= 1 (count @cmd-log)))
    (is (= :test/say (-> @cmd-log first :command/name)))
    (is (= "hi"      (-> @cmd-log first :text)))
    ;; Buffer cleared, history has the prior submission.
    (is (= ""   (-> @session :input-area :value)))
    (is (= ["hi"] (-> @session :input-area :history)))))

(deftest input-key-override-routes-text-to-different-input-slot
  (let [cmd-log (atom [])
        screen  (assoc-in input-screen [:tui/input :input-key] :body)
        {:keys [session]}
        (make-test-session
          {:screen screen
           :process-command-fn (fn [ctx] (swap! cmd-log conj (:command ctx))
                                          {:command/result :ok})})]
    (press-keys! session "h" "i" "<enter>")
    (is (= "hi" (-> @cmd-log first :body)))
    (is (nil?   (-> @cmd-log first :text)))))

(deftest history-recall-via-up-arrow
  (let [{:keys [session]}
        (make-test-session
          {:screen input-screen
           :process-command-fn (fn [_] {:command/result :ok})})]
    (press-keys! session "f" "i" "r" "s" "t" "<enter>"
                         "s" "e" "c" "o" "n" "d" "<enter>")
    (is (= ["first" "second"] (-> @session :input-area :history)))
    (press-key! session "<up>")
    (is (= "second" (-> @session :input-area :value)))
    (press-key! session "<up>")
    (is (= "first"  (-> @session :input-area :value)))))

;; ──────────────────────────────────────────────────────────────────────
;; Passthrough — screen-level keymap still works
;; ──────────────────────────────────────────────────────────────────────

(deftest esc-passes-through-to-screen-keymap-and-quits
  (let [{:keys [session]} (make-test-session {:screen input-screen})]
    (press-key! session "<esc>")
    (is (false? (:running? @session)))))

(deftest screen-keymap-printable-keys-are-shadowed-by-input
  ;; If a screen has `:tui/input`, printable keys go to the buffer
  ;; rather than the screen keymap. This is the documented caveat.
  (let [screen (-> input-screen
                   (assoc :tui/keymap {"q" [:session :quit]}))
        {:keys [session]} (make-test-session {:screen screen})]
    (press-key! session "q")
    ;; "q" went into the buffer, not the quit action.
    (is (= "q" (-> @session :input-area :value)))
    (is (true? (:running? @session)))))

;; ──────────────────────────────────────────────────────────────────────
;; Multi-line
;; ──────────────────────────────────────────────────────────────────────

(deftest multiline-enter-inserts-newline
  (let [screen (assoc-in input-screen [:tui/input :multiline?] true)
        {:keys [session]} (make-test-session {:screen screen})]
    (press-keys! session "a" "<enter>" "b")
    (is (= "a\nb" (-> @session :input-area :value)))))

(deftest multiline-c-d-submits
  (let [cmd-log (atom [])
        screen  (assoc-in input-screen [:tui/input :multiline?] true)
        {:keys [session]}
        (make-test-session
          {:screen screen
           :process-command-fn (fn [ctx] (swap! cmd-log conj (:command ctx))
                                          {:command/result :ok})})]
    (press-keys! session "a" "<enter>" "b" "C-d")
    (is (= "a\nb" (-> @cmd-log first :text)))))

;; ──────────────────────────────────────────────────────────────────────
;; Rendering — viewport reserves bottom rows + cursor positioning
;; ──────────────────────────────────────────────────────────────────────

(deftest render-reserves-bottom-row-for-input-area
  (let [{:keys [session out]} (make-test-session {:screen input-screen})]
    ;; Type "hi" so the input area shows something visible.
    (press-keys! session "h" "i")
    (session/render-frame! session)
    (let [combined (apply str @out)]
      ;; The prompt "› " + buffer "hi" should appear in the output.
      (is (re-find #"› hi" combined)))))

(deftest render-shows-cursor-when-input-active
  (let [{:keys [session out]} (make-test-session {:screen input-screen})]
    (session/render-frame! session)
    (let [combined (apply str @out)]
      ;; show-cursor escape: CSI ?25h
      (is (re-find #"\[\?25h" combined)))))

(deftest render-hides-cursor-after-switching-from-input-screen
  (let [{:keys [session out]} (make-test-session {:screen input-screen})]
    (session/render-frame! session)
    (reset! out [])
    (session/change-screen! session (dissoc input-screen :tui/input))
    (session/render-frame! session)
    (let [combined (apply str @out)]
      ;; hide-cursor escape: CSI ?25l
      (is (re-find #"\[\?25l" combined)))))
