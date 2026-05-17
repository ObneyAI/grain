(ns ai.obney.grain.tui-adapter.transport.stdio-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.grain.tui-adapter.transport.stdio :as stdio]))

;; The stdio transport opens a real JLine Terminal — we can't easily
;; exercise it in unit tests (no PTY in CI). Most coverage of stdio
;; behaviour lives in the e2e harness (step 16). This namespace tests
;; the pure helpers only.

;; ──────────────────────────────────────────────────────────────────────────
;; detect-color-depth
;; ──────────────────────────────────────────────────────────────────────────

;; Note: detect-color-depth reads env vars. We can't easily mutate the
;; JVM's env vars portably; we rely on whatever env the test suite runs
;; under. The test asserts that a valid value is returned.

(deftest color-depth-returns-valid-value
  (let [d (stdio/detect-color-depth)]
    (is (#{:truecolor :c256 :c16 :mono} d))))

;; ──────────────────────────────────────────────────────────────────────────
;; Caps override precedence (terminal-theming composition)
;; ──────────────────────────────────────────────────────────────────────────

(deftest explicit-color-override-wins
  ;; An explicit :color (transport/app opt) beats env + detection — the
  ;; escape hatch for a lying COLORTERM.
  (is (= :c16  (stdio/resolve-color-depth {:color :c16})))
  (is (= :mono (stdio/resolve-color-depth {:color :mono})))
  (is (= :c256 (stdio/resolve-color-depth {:color :c256}))))

(deftest no-override-falls-back-to-env-or-detection
  (let [d (stdio/resolve-color-depth nil)]
    (is (#{:truecolor :c256 :c16 :mono} d)))
  ;; env-color-override only ever yields a valid depth or nil.
  (is (contains? #{:truecolor :c256 :c16 :mono nil}
                 (stdio/env-color-override))))

;; ──────────────────────────────────────────────────────────────────────────
;; ANSI lifecycle helpers — assert byte sequences are well-formed
;; ──────────────────────────────────────────────────────────────────────────

(def ESC "")

(deftest enter-tui-emits-alt-screen-and-hide-cursor
  (let [out (atom "")]
    (stdio/enter-tui! (fn [s] (swap! out str s)))
    (is (str/includes? @out (str ESC "[?1049h")))
    (is (str/includes? @out (str ESC "[?25l")))
    (is (str/includes? @out (str ESC "[2J")))))

(deftest leave-tui-restores-cursor-and-leaves-alt-screen
  (let [out (atom "")]
    (stdio/leave-tui! (fn [s] (swap! out str s)))
    (is (str/includes? @out (str ESC "[?1049l")))
    (is (str/includes? @out (str ESC "[?25h")))
    (is (str/includes? @out (str ESC "[0m")))))
