(ns ai.obney.grain.tui-client.input-area-client-test
  "Tests for the thin client's input-area integration.

   We don't spin up a full client loop here (it owns a JLine terminal
   and would require a PTY). Instead we exercise the building blocks:
     - `maybe-init-input-area` (frame-driven IA state lifecycle)
     - the render pipeline with input-area state
     - `post-input-submission!` against a mock HTTP client

   The full main-loop integration is validated by manual smoke on
   `grain-tui-demo-remote`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.input-area :as input-area]
            [ai.obney.grain.tui-client.core :as client]
            [ai.obney.grain.tui-client.render :as render]))

;; ──────────────────────────────────────────────────────────────────────
;; Frame-driven input-area lifecycle
;; ──────────────────────────────────────────────────────────────────────

(deftest no-input-on-frame-yields-nil-state
  (is (nil? (#'client/maybe-init-input-area nil {:hiccup [:text "x"]}))))

(deftest input-on-frame-initializes-fresh-state
  (let [ia (#'client/maybe-init-input-area
             nil
             {:hiccup [:text "x"]
              :input  {:command :test/say :prompt "› "}})]
    (is (= "" (:value ia)))
    (is (= 0  (:cursor ia)))))

(deftest input-state-preserved-across-frames
  (let [typed   (-> (input-area/initial-state)
                    (input-area/handle-key {:type :key :key "h"} {})
                    :state
                    (input-area/handle-key {:type :key :key "i"} {})
                    :state)
        next-ia (#'client/maybe-init-input-area
                  typed
                  {:hiccup [:text "fresh from server"]
                   :input  {:command :test/say}})]
    ;; Buffer survives a frame that didn't reset it.
    (is (= "hi" (:value next-ia)))))

(deftest input-removed-from-frame-clears-state
  (let [prior (input-area/initial-state)
        next  (#'client/maybe-init-input-area prior {:hiccup [:text "x"]})]
    (is (nil? next))))

;; ──────────────────────────────────────────────────────────────────────
;; Render: input area in the painted grid
;; ──────────────────────────────────────────────────────────────────────

(defn- chars-of [grid r]
  (apply str (mapv :char (get-in grid [:cells r]))))

(deftest render-reserves-bottom-row-when-frame-has-input
  (let [frame {:hiccup [:text {:text "screen"}]
               :input  {:command :test/say :prompt "› "}}
        ia    (-> (input-area/initial-state)
                  (input-area/handle-key {:type :key :key "h"} {}) :state
                  (input-area/handle-key {:type :key :key "i"} {}) :state)
        grid  (render/frame->grid frame {:width 10 :height 3} ia)]
    ;; Top rows: screen content; bottom row: prompt + buffer.
    (is (= "screen    " (chars-of grid 0)))
    (is (= "› hi      " (chars-of grid 2)))))

(deftest render-without-input-fills-entire-viewport
  (let [frame {:hiccup [:text {:text "screen"}]}
        grid  (render/frame->grid frame {:width 10 :height 2} nil)]
    (is (= "screen    " (chars-of grid 0)))))

(deftest render-frame-emits-cursor-positioning-when-input-active
  (let [out   (atom [])
        state {:render-model nil :ansi-style nil
               :terminal-caps {:color :truecolor}
               :input-area    (input-area/initial-state)}
        frame {:hiccup [:text {:text "x"}]
               :input  {:command :test/say :prompt "› "}}]
    (render/render-frame! state frame {:width 10 :height 3} #(swap! out conj %))
    (let [combined (str/join "" @out)]
      ;; Should include CSI ?25h (show cursor)
      (is (str/includes? combined "[?25h"))
      ;; And a cursor-position move sequence \033[<row>;<col>H
      (is (re-find #"\[\d+;\d+H" combined)))))

(deftest render-frame-hides-cursor-when-transitioning-away
  (let [out   (atom [])
        state {:render-model nil :ansi-style nil
               :terminal-caps {:color :truecolor}
               :input-area    (input-area/initial-state)
               :cursor-shown? true}
        frame {:hiccup [:text {:text "no input"}]}]      ; no :input
    (render/render-frame! state frame {:width 10 :height 3} #(swap! out conj %))
    (let [combined (str/join "" @out)]
      ;; CSI ?25l (hide cursor)
      (is (str/includes? combined "[?25l")))))

;; ──────────────────────────────────────────────────────────────────────
;; post-input-submission! — POSTs the configured command with buffered
;; text bound at :text (or :input-key override)
;; ──────────────────────────────────────────────────────────────────────

(defn- mock-http-client [captured]
  ;; A stub object — the with-redefs below intercepts `http/post-edn`,
  ;; so the actual client instance is irrelevant. Keep a non-nil
  ;; sentinel to satisfy the :http-client opt.
  ::stub)

(deftest input-submission-posts-command-with-text
  (let [captured (atom nil)]
    (with-redefs [ai.obney.grain.tui-client.http/post-edn
                  (fn [opts] (reset! captured opts) {:status 200 :body {:ok true}})]
      (#'client/post-input-submission!
        {:base-url "http://example.test"
         :http-client (mock-http-client captured)
         :session-id (random-uuid)}
        {:command :chat/say :prompt "› "}
        "hello there"))
    (let [{:keys [url body]} @captured]
      (is (= "http://example.test/tui/command/chat/say" url))
      (is (= "hello there"   (-> body :inputs :text)))
      (is (some? (:session body))))))

(deftest input-key-override-routes-to-non-default-slot
  (let [captured (atom nil)]
    (with-redefs [ai.obney.grain.tui-client.http/post-edn
                  (fn [opts] (reset! captured opts) {:status 200 :body {:ok true}})]
      (#'client/post-input-submission!
        {:base-url "http://example.test"
         :http-client ::stub
         :session-id (random-uuid)}
        {:command :chat/say :prompt "› " :input-key :body}
        "hello"))
    (is (= "hello" (-> @captured :body :inputs :body)))
    (is (nil?      (-> @captured :body :inputs :text)))))
