(ns ai.obney.grain.tui-adapter.input-slot-test
  "Unit tests for the pure input-slot helper: sentinel grid, slot-box
   scan, sentinel stripping, and input placement."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.input-slot :as is*]))

(def ^:private marker :ai.obney.grain.tui-adapter.input-slot/sentinel)

(defn- sentinel-at? [grid x y]
  (true? (get-in grid [:cells y x marker])))

(defn- any-sentinel? [grid]
  (some (fn [row] (some #(get % marker) row)) (:cells grid)))

(deftest sentinel-grid-is-marked-blank
  (let [g (is*/sentinel-grid 3 2)]
    (is (= 3 (:width g)))
    (is (= 2 (:height g)))
    (is (every? true? (for [y (range 2) x (range 3)] (sentinel-at? g x y))))
    ;; Visible char is a blank space — invisible to ANSI, only the
    ;; private key marks it.
    (is (= " " (get-in g [:cells 0 0 :char])))))

(deftest find-slot-box-locates-rectangle
  ;; Blank 10x5 canvas with a 4x2 sentinel block at (3,1).
  (let [canvas (cells/overlay (cells/blank 10 5)
                              (is*/sentinel-grid 4 2) 3 1)]
    (is (= {:x 3 :y 1 :w 4 :h 2} (is*/find-slot-box canvas)))))

(deftest find-slot-box-nil-when-no-sentinel
  (is (nil? (is*/find-slot-box (cells/blank 8 3)))))

(deftest find-slot-box-first-rectangle-when-multiple
  ;; Two separate slots → take the first (topmost-leftmost) rectangle,
  ;; preserving the one-input-per-screen invariant (R5).
  (let [canvas (-> (cells/blank 12 6)
                   (cells/overlay (is*/sentinel-grid 3 1) 1 1)
                   (cells/overlay (is*/sentinel-grid 3 1) 1 4))
        box    (is*/find-slot-box canvas)]
    (is (= {:x 1 :y 1 :w 3 :h 1} box))))

(deftest strip-sentinels-removes-marker
  (let [canvas (cells/overlay (cells/blank 6 2)
                              (is*/sentinel-grid 6 1) 0 0)]
    (is (any-sentinel? canvas))
    (let [stripped (is*/strip-sentinels canvas)]
      (is (not (any-sentinel? stripped)))
      ;; Stripped cells are exactly blank-cell (R2: diff compares whole
      ;; cell maps; a residual key would corrupt diffing).
      (is (= cells/blank-cell (get-in stripped [:cells 0 0]))))))

(deftest place-input-overlays-and-strips
  (let [canvas (cells/overlay (cells/blank 10 4)
                              (is*/sentinel-grid 6 2) 2 1)
        box    (is*/find-slot-box canvas)
        input  (cells/text-row 6 {} "PROMPT")
        {:keys [grid origin]} (is*/place-input canvas box input)]
    (is (= [2 1] origin))
    (is (not (any-sentinel? grid)))
    ;; Input landed at the slot origin.
    (is (= "PROMPT"
           (apply str (map :char (subvec (get-in grid [:cells 1]) 2 8)))))
    ;; Slot row beyond the 1-row input is stripped to blanks (R4: app
    ;; sizes the slot; uncovered rows must not keep the marker).
    (is (= cells/blank-cell (get-in grid [:cells 2 2])))))

(deftest place-input-nil-box-strips-only
  (let [canvas (cells/overlay (cells/blank 5 2)
                              (is*/sentinel-grid 5 1) 0 0)
        {:keys [grid origin]} (is*/place-input canvas nil
                                               (cells/text-row 5 {} "x"))]
    (is (nil? origin))
    (is (not (any-sentinel? grid)))))
