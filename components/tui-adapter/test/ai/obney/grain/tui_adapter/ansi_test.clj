(ns ai.obney.grain.tui-adapter.ansi-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.ansi :as ansi]))

(def ESC "")
(def CSI (str ESC "["))

(def ^:private truecolor-caps {:color :truecolor})
(def ^:private c256-caps      {:color :c256})
(def ^:private c16-caps       {:color :c16})
(def ^:private mono-caps      {:color :mono})

;; Default style — private in `ansi.clj`, re-declared here for test convenience.
(def default-style*
  {:fg :default :bg :default
   :bold? false :italic? false :underline? false :dim? false})

;; ──────────────────────────────────────────────────────────────────────────
;; Cursor positioning
;; ──────────────────────────────────────────────────────────────────────────

(deftest cursor-position-is-1-indexed
  (is (= (str CSI "1;1H") (ansi/cursor-position 0 0)))
  (is (= (str CSI "5;10H") (ansi/cursor-position 4 9))))

;; ──────────────────────────────────────────────────────────────────────────
;; sgr-for — minimum SGR delta
;; ──────────────────────────────────────────────────────────────────────────

(deftest no-change-emits-empty
  (let [[esc _] (ansi/sgr-for default-style* default-style* truecolor-caps)]
    (is (= "" esc))))

(deftest add-bold-emits-only-bold-on
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :bold? true)
                              truecolor-caps)]
    (is (= (str CSI "1m") esc))))

(deftest remove-bold-emits-reset-and-re-applies
  (let [from (assoc default-style* :bold? true :fg :red)
        to   (assoc default-style* :fg :red)
        [esc _] (ansi/sgr-for from to truecolor-caps)]
    ;; Reset emitted, then fg :red re-emitted.
    (is (str/includes? esc "0"))
    (is (str/includes? esc "31"))))

(deftest fg-color-named
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :fg :red)
                              truecolor-caps)]
    (is (= (str CSI "31m") esc))))

(deftest fg-color-rgb-truecolor
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :fg [:rgb 255 0 128])
                              truecolor-caps)]
    (is (= (str CSI "38;2;255;0;128m") esc))))

(deftest fg-color-rgb-256
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :fg [:rgb 255 0 0])
                              c256-caps)]
    (is (re-find #"38;5;\d+m$" esc))))

(deftest mono-drops-color
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :fg :red)
                              mono-caps)]
    (is (= "" esc))))

(deftest mono-keeps-bold
  (let [[esc _] (ansi/sgr-for default-style*
                              (assoc default-style* :bold? true)
                              mono-caps)]
    (is (= (str CSI "1m") esc))))

;; ──────────────────────────────────────────────────────────────────────────
;; emit — multi-run output
;; ──────────────────────────────────────────────────────────────────────────

(defn- cell [c style]
  (merge cells/blank-cell {:char c} style))

(deftest emit-single-run
  (let [runs [{:row 0 :col 0
               :cells [(cell "a" {}) (cell "b" {})]}]
        [out _] (ansi/emit runs truecolor-caps default-style*)]
    (is (= (str CSI "1;1Hab") out))))

(deftest emit-style-change-mid-run
  (let [red (assoc default-style* :fg :red)
        runs [{:row 0 :col 0
               :cells [(cell "a" {}) (cell "b" red)]}]
        [out _] (ansi/emit runs truecolor-caps default-style*)]
    (is (str/includes? out (str CSI "31m")))
    (is (str/includes? out "a"))
    (is (str/includes? out "b"))))

(deftest emit-threads-style-across-runs
  (let [red (assoc default-style* :fg :red)
        runs [{:row 0 :col 0 :cells [(cell "a" red)]}
              {:row 1 :col 0 :cells [(cell "b" red)]}]
        [out _] (ansi/emit runs truecolor-caps default-style*)
        sgr-count (count (re-seq (re-pattern (str ESC "\\[31m")) out))]
    (is (= 1 sgr-count))))

(deftest emit-returns-final-style
  (let [red (assoc default-style* :fg :red)
        runs [{:row 0 :col 0 :cells [(cell "a" red)]}]
        [_ end-style] (ansi/emit runs truecolor-caps default-style*)]
    (is (= :red (:fg end-style)))))

(deftest emit-multi-row-positions-cursor
  (let [runs [{:row 0 :col 0 :cells [(cell "a" {})]}
              {:row 5 :col 3 :cells [(cell "b" {})]}]
        [out _] (ansi/emit runs truecolor-caps default-style*)]
    (is (str/includes? out (str CSI "1;1H")))
    (is (str/includes? out (str CSI "6;4H")))))
