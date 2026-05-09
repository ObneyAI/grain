(ns ai.obney.grain.tui-adapter.diff-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.diff :as diff]))

(defn- cell [c]
  (assoc cells/blank-cell :char c))

(defn- grid-of [rows]
  {:width  (count (first rows))
   :height (count rows)
   :cells  (mapv (fn [row] (mapv cell row)) rows)})

;; ──────────────────────────────────────────────────────────────────────────
;; nil prev = first frame
;; ──────────────────────────────────────────────────────────────────────────

(deftest nil-prev-emits-full-row-runs
  (let [g (grid-of [["a" "b"] ["c" "d"]])
        runs (diff/diff nil g)]
    (is (= 2 (count runs)))
    (is (= {:row 0 :col 0 :cells (mapv cell ["a" "b"])} (nth runs 0)))
    (is (= {:row 1 :col 0 :cells (mapv cell ["c" "d"])} (nth runs 1)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Identity
;; ──────────────────────────────────────────────────────────────────────────

(deftest equal-grids-emit-no-runs
  (let [g (grid-of [["a" "b"] ["c" "d"]])]
    (is (= [] (diff/diff g g)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Single change
;; ──────────────────────────────────────────────────────────────────────────

(deftest single-cell-change-one-run
  (let [a (grid-of [["a" "b" "c"]])
        b (grid-of [["a" "X" "c"]])
        runs (diff/diff a b)]
    (is (= 1 (count runs)))
    (is (= {:row 0 :col 1 :cells [(cell "X")]} (first runs)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Contiguous changes
;; ──────────────────────────────────────────────────────────────────────────

(deftest contiguous-changes-coalesce-into-one-run
  (let [a (grid-of [["a" "b" "c" "d" "e"]])
        b (grid-of [["a" "X" "Y" "Z" "e"]])
        runs (diff/diff a b)]
    (is (= 1 (count runs)))
    (is (= {:row 0 :col 1 :cells (mapv cell ["X" "Y" "Z"])} (first runs)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Non-contiguous changes
;; ──────────────────────────────────────────────────────────────────────────

(deftest non-contiguous-changes-on-row-emit-multiple-runs
  (let [a (grid-of [["a" "b" "c" "d" "e"]])
        b (grid-of [["X" "b" "Y" "d" "Z"]])
        runs (diff/diff a b)]
    (is (= 3 (count runs)))
    (is (= {:row 0 :col 0 :cells [(cell "X")]} (nth runs 0)))
    (is (= {:row 0 :col 2 :cells [(cell "Y")]} (nth runs 1)))
    (is (= {:row 0 :col 4 :cells [(cell "Z")]} (nth runs 2)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Multi-row changes
;; ──────────────────────────────────────────────────────────────────────────

(deftest multi-row-changes
  (let [a (grid-of [["a" "b"] ["c" "d"]])
        b (grid-of [["X" "b"] ["c" "Y"]])
        runs (diff/diff a b)]
    (is (= 2 (count runs)))
    (is (= 0 (:row (first runs))))
    (is (= 1 (:row (second runs))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Resize → defensive full-frame
;; ──────────────────────────────────────────────────────────────────────────

(deftest shape-mismatch-emits-full-frame
  (let [a (grid-of [["a" "b"]])
        b (grid-of [["X" "Y" "Z"] ["1" "2" "3"]])
        runs (diff/diff a b)]
    (is (= 2 (count runs)))
    (is (= {:row 0 :col 0 :cells (mapv cell ["X" "Y" "Z"])} (nth runs 0)))
    (is (= {:row 1 :col 0 :cells (mapv cell ["1" "2" "3"])} (nth runs 1)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Empty
;; ──────────────────────────────────────────────────────────────────────────

(deftest both-empty-grids-no-runs
  (let [g (cells/blank 0 0)]
    (is (= [] (diff/diff g g)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Style-only change
;; ──────────────────────────────────────────────────────────────────────────

(deftest style-only-change-detected
  (let [a {:width 1 :height 1 :cells [[(assoc cells/blank-cell :char "x")]]}
        b {:width 1 :height 1 :cells [[(assoc cells/blank-cell :char "x" :fg :red)]]}
        runs (diff/diff a b)]
    (is (= 1 (count runs)))
    (is (= :red (-> runs first :cells first :fg)))))
