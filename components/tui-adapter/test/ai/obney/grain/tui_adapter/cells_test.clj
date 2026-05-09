(ns ai.obney.grain.tui-adapter.cells-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.cells :as cells]))

;; ──────────────────────────────────────────────────────────────────────────
;; blank
;; ──────────────────────────────────────────────────────────────────────────

(deftest blank-zero-by-zero
  (let [g (cells/blank 0 0)]
    (is (= 0 (:width g)))
    (is (= 0 (:height g)))
    (is (= [] (:cells g)))))

(deftest blank-one-by-one
  (let [g (cells/blank 1 1)]
    (is (= 1 (:width g)))
    (is (= 1 (:height g)))
    (is (= [[cells/blank-cell]] (:cells g)))))

(deftest blank-w-by-h
  (let [g (cells/blank 5 3)]
    (is (= 5 (:width g)))
    (is (= 3 (:height g)))
    (is (= 3 (count (:cells g))))
    (is (every? (fn [row] (= 5 (count row))) (:cells g)))
    (is (every? (fn [row] (every? #(= cells/blank-cell %) row)) (:cells g)))))

;; ──────────────────────────────────────────────────────────────────────────
;; text-row
;; ──────────────────────────────────────────────────────────────────────────

(deftest text-row-empty-string-pads
  (let [g (cells/text-row 5 {} "")]
    (is (= 5 (:width g)))
    (is (= 1 (:height g)))
    (is (= [(vec (repeat 5 cells/blank-cell))] (:cells g)))))

(deftest text-row-exact-width
  (let [g (cells/text-row 3 {} "abc")
        chars (mapv :char (first (:cells g)))]
    (is (= ["a" "b" "c"] chars))
    (is (= 3 (:width g)))))

(deftest text-row-truncates-when-longer-than-width
  (let [g (cells/text-row 3 {} "abcdef")
        chars (mapv :char (first (:cells g)))]
    (is (= ["a" "b" "c"] chars))
    (is (= 3 (:width g)))))

(deftest text-row-pads-when-shorter-than-width
  (let [g (cells/text-row 5 {} "ab")
        cells-row (first (:cells g))]
    (is (= ["a" "b" " " " " " "] (mapv :char cells-row)))))

(deftest text-row-applies-style-to-every-char
  (let [g (cells/text-row 4 {:fg :red :bold? true} "ab")
        row (first (:cells g))]
    ;; The actual chars carry the style; the padding cells also carry it
    ;; (since the style is merged into the base cell used for padding).
    (is (every? #(= :red (:fg %)) row))
    (is (every? #(true? (:bold? %)) row))))

(deftest text-row-multi-codepoint-string
  (let [g (cells/text-row 3 {} "abc")
        chars (mapv :char (first (:cells g)))]
    ;; MVP: assume 1 codepoint = 1 column. Wide-char (CJK) handling deferred.
    (is (= ["a" "b" "c"] chars))))

(deftest text-row-zero-width
  (let [g (cells/text-row 0 {} "abc")]
    (is (= 0 (:width g)))
    (is (= 1 (:height g)))
    (is (= [[]] (:cells g)))))

;; ──────────────────────────────────────────────────────────────────────────
;; stack
;; ──────────────────────────────────────────────────────────────────────────

(deftest stack-zero-grids
  (let [g (cells/stack)]
    (is (= 0 (:width g)))
    (is (= 0 (:height g)))))

(deftest stack-single-grid
  (let [a (cells/blank 3 1)
        g (cells/stack a)]
    (is (= 3 (:width g)))
    (is (= 1 (:height g)))
    (is (= (:cells a) (:cells g)))))

(deftest stack-two-same-width
  (let [a (cells/text-row 3 {} "aaa")
        b (cells/text-row 3 {} "bbb")
        g (cells/stack a b)]
    (is (= 3 (:width g)))
    (is (= 2 (:height g)))
    (is (= ["a" "a" "a"] (mapv :char (first (:cells g)))))
    (is (= ["b" "b" "b"] (mapv :char (second (:cells g)))))))

(deftest stack-mismatched-widths-pads-narrower
  (let [a (cells/text-row 5 {} "aaaaa")
        b (cells/text-row 2 {} "bb")
        g (cells/stack a b)]
    (is (= 5 (:width g)))
    (is (= 2 (:height g)))
    (is (= ["b" "b" " " " " " "] (mapv :char (second (:cells g)))))))

(deftest stack-zero-height-grid
  (let [a (cells/blank 3 0)
        b (cells/text-row 3 {} "bbb")
        g (cells/stack a b)]
    (is (= 3 (:width g)))
    (is (= 1 (:height g)))))

;; ──────────────────────────────────────────────────────────────────────────
;; beside
;; ──────────────────────────────────────────────────────────────────────────

(deftest beside-zero-grids
  (let [g (cells/beside)]
    (is (= 0 (:width g)))
    (is (= 0 (:height g)))))

(deftest beside-single-grid
  (let [a (cells/blank 1 3)
        g (cells/beside a)]
    (is (= 1 (:width g)))
    (is (= 3 (:height g)))))

(deftest beside-two-same-height
  (let [a (cells/text-row 2 {} "ab")
        b (cells/text-row 3 {} "cde")
        g (cells/beside a b)]
    (is (= 5 (:width g)))
    (is (= 1 (:height g)))
    (is (= ["a" "b" "c" "d" "e"] (mapv :char (first (:cells g)))))))

(deftest beside-mismatched-heights-pads-shorter
  (let [a (cells/stack (cells/text-row 2 {} "aa")
                       (cells/text-row 2 {} "AA"))      ; height 2
        b (cells/text-row 3 {} "bbb")                    ; height 1
        g (cells/beside a b)]
    (is (= 5 (:width g)))
    (is (= 2 (:height g)))
    (is (= ["a" "a" "b" "b" "b"] (mapv :char (first (:cells g)))))
    (is (= ["A" "A" " " " " " "] (mapv :char (second (:cells g)))))))

(deftest beside-zero-width-grid
  (let [a (cells/blank 0 1)
        b (cells/text-row 3 {} "bbb")
        g (cells/beside a b)]
    (is (= 3 (:width g)))
    (is (= 1 (:height g)))
    (is (= ["b" "b" "b"] (mapv :char (first (:cells g)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; overlay
;; ──────────────────────────────────────────────────────────────────────────

(deftest overlay-at-origin
  (let [base (cells/blank 5 3)
        over (cells/text-row 2 {} "ab")
        g    (cells/overlay base over 0 0)]
    (is (= 5 (:width g)))
    (is (= 3 (:height g)))
    (is (= "a" (:char (get-in (:cells g) [0 0]))))
    (is (= "b" (:char (get-in (:cells g) [0 1]))))
    (is (= " " (:char (get-in (:cells g) [0 2]))))
    (is (= " " (:char (get-in (:cells g) [1 0]))))))

(deftest overlay-mid-canvas
  (let [base (cells/blank 5 3)
        over (cells/text-row 2 {} "xy")
        g    (cells/overlay base over 2 1)]
    (is (= " " (:char (get-in (:cells g) [0 0]))))
    (is (= " " (:char (get-in (:cells g) [1 1]))))
    (is (= "x" (:char (get-in (:cells g) [1 2]))))
    (is (= "y" (:char (get-in (:cells g) [1 3]))))
    (is (= " " (:char (get-in (:cells g) [1 4]))))
    (is (= " " (:char (get-in (:cells g) [2 2]))))))

(deftest overlay-clips-right-edge
  (let [base (cells/blank 4 1)
        over (cells/text-row 3 {} "abc")
        g    (cells/overlay base over 2 0)]
    (is (= 4 (:width g)))
    (is (= "a" (:char (get-in (:cells g) [0 2]))))
    (is (= "b" (:char (get-in (:cells g) [0 3]))))
    ;; "c" would go to col 4 — outside; clipped.
    ))

(deftest overlay-clips-bottom-edge
  (let [base (cells/blank 2 2)
        over (cells/stack (cells/text-row 2 {} "ab")
                          (cells/text-row 2 {} "cd")
                          (cells/text-row 2 {} "ef"))
        g    (cells/overlay base over 0 1)]
    (is (= "a" (:char (get-in (:cells g) [1 0]))))
    (is (= "b" (:char (get-in (:cells g) [1 1]))))))

(deftest overlay-fully-off-canvas-is-no-op
  (let [base (cells/blank 3 3)
        over (cells/text-row 2 {} "ab")]
    (testing "x past right edge"
      (is (= base (cells/overlay base over 3 0))))
    (testing "y past bottom edge"
      (is (= base (cells/overlay base over 0 3))))
    (testing "x+ow past 0 (negative position)"
      (is (= base (cells/overlay base over -2 0))))))

(deftest overlay-exact-fit
  (let [base (cells/blank 2 1)
        over (cells/text-row 2 {} "ab")
        g    (cells/overlay base over 0 0)]
    (is (= ["a" "b"] (mapv :char (first (:cells g)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; with-style
;; ──────────────────────────────────────────────────────────────────────────

(deftest with-style-applies-to-every-cell
  (let [g  (cells/text-row 3 {} "abc")
        sg (cells/with-style g {:fg :red :bold? true})]
    (is (every? #(= :red (:fg %)) (first (:cells sg))))
    (is (every? :bold? (first (:cells sg))))))

(deftest with-style-does-not-mutate-input
  (let [g       (cells/text-row 3 {} "abc")
        original (:cells g)
        _        (cells/with-style g {:fg :red})]
    (is (= original (:cells g)))))

(deftest with-style-overlay-wins-over-base
  (let [g  (cells/text-row 1 {:fg :blue} "x")
        sg (cells/with-style g {:fg :red})]
    (is (= :red (:fg (get-in (:cells sg) [0 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; clip
;; ──────────────────────────────────────────────────────────────────────────

(deftest clip-within-box-is-noop
  (let [g (cells/text-row 3 {} "abc")]
    (is (= g (cells/clip g {:width 5 :height 1})))))

(deftest clip-oversize-width
  (let [g (cells/text-row 5 {} "abcde")
        c (cells/clip g {:width 3 :height 1})]
    (is (= 3 (:width c)))
    (is (= ["a" "b" "c"] (mapv :char (first (:cells c)))))))

(deftest clip-oversize-height
  (let [g (apply cells/stack (repeat 5 (cells/text-row 1 {} "x")))
        c (cells/clip g {:width 1 :height 2})]
    (is (= 2 (:height c)))))

(deftest clip-oversize-both-dimensions
  (let [g (apply cells/stack (repeat 4 (cells/text-row 4 {} "abcd")))
        c (cells/clip g {:width 2 :height 2})]
    (is (= 2 (:width c)))
    (is (= 2 (:height c)))
    (is (= ["a" "b"] (mapv :char (first (:cells c)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; truncation-glyph
;; ──────────────────────────────────────────────────────────────────────────

(deftest truncation-glyph-single-row
  (let [g (cells/truncation-glyph {:width 5 :height 1})]
    (is (= 5 (:width g)))
    (is (= 1 (:height g)))
    (is (= "…" (:char (get-in (:cells g) [0 0]))))))

(deftest truncation-glyph-multi-row
  (let [g (cells/truncation-glyph {:width 3 :height 2})]
    (is (= 3 (:width g)))
    (is (= 2 (:height g)))
    (is (= "▼" (:char (get-in (:cells g) [0 0]))))))

(deftest truncation-glyph-zero-dimensions
  (is (= (cells/blank 0 5) (cells/truncation-glyph {:width 0 :height 5})))
  (is (= (cells/blank 5 0) (cells/truncation-glyph {:width 5 :height 0}))))
