(ns ai.obney.grain.tui-adapter.builtins-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]   ; load registers built-ins
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [ai.obney.grain.tui-adapter.layout :as layout]))

;; Built-ins are registered at namespace load via the defonce block in
;; `builtins.clj`. Tests do NOT clear the registry — they rely on it.

(defn- chars-of [grid row]
  (mapv :char (get-in grid [:cells row])))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.6.6 honesty assertion — built-ins live in the same registry
;; ──────────────────────────────────────────────────────────────────────────

(deftest honesty-every-builtin-in-registry
  (let [expected #{:text :line :gap :row :col :box :pad :weighted
                   :list :table :scroll :turn :fold :status :progress
                   :spinner :input}]
    (doseq [tag expected]
      (is (some? (er/element-info tag))
          (str tag " must be registered (§7.6.6 honesty)")))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.1 :text
;; ──────────────────────────────────────────────────────────────────────────

(deftest text-plain
  (let [g (layout/render-element [:text {:text "hi"}] {:width 5 :height 1})]
    (is (= ["h" "i" " " " " " "] (chars-of g 0)))))

(deftest text-via-string-promotion
  ;; A bare string in a :col promotes to [:text "..."]; the renderer accepts
  ;; the {:text "..."} form. Test the promotion path directly.
  (let [g (layout/render-element "abc" {:width 5 :height 1})]
    (is (= ["a" "b" "c" " " " "] (chars-of g 0)))))

(deftest text-styled
  (let [g    (layout/render-element [:text {:text "x" :fg :red :bold? true}]
                                    {:width 1 :height 1})
        cell (get-in g [:cells 0 0])]
    (is (= "x" (:char cell)))
    (is (= :red (:fg cell)))
    (is (true? (:bold? cell)))))

(deftest text-empty-string
  (let [g (layout/render-element [:text {:text ""}] {:width 3 :height 1})]
    (is (= [" " " " " "] (chars-of g 0)))))

(deftest text-truncates-when-longer-than-width
  (let [g (layout/render-element [:text {:text "abcdefgh"}] {:width 4 :height 1})]
    (is (= ["a" "b" "c" "d"] (chars-of g 0)))))

(deftest text-wraps-on-word-boundaries
  (let [g (layout/render-element [:text {:text "alpha beta gamma"}]
                                 {:width 10 :height 2})]
    (is (= ["a" "l" "p" "h" "a" " " "b" "e" "t" "a"] (chars-of g 0)))
    (is (= ["g" "a" "m" "m" "a" " " " " " " " " " "] (chars-of g 1)))))

(deftest text-hard-wraps-long-tokens
  (let [g (layout/render-element [:text {:text "abcdefghijkl"}]
                                 {:width 5 :height 3})]
    (is (= ["a" "b" "c" "d" "e"] (chars-of g 0)))
    (is (= ["f" "g" "h" "i" "j"] (chars-of g 1)))
    (is (= ["k" "l" " " " " " "] (chars-of g 2)))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.1 :text — styled-runs child
;; ──────────────────────────────────────────────────────────────────────────

(deftest text-runs-preferred-size
  (is (= {:width 5 :height 1}
         (layout/child-preferred-size [:text [{:text "abc"} {:text "de" :fg :red}]])))
  (is (= {:width 2 :height 1}
         (layout/child-preferred-size [:text {:fg :red} [{:text "hi"}]]))))

(deftest text-runs-render-per-cell-styles
  (let [g (layout/render-element [:text [{:text "ab" :bold? true}
                                         {:text "c" :fg :red}]]
                                 {:width 5 :height 1})]
    (is (= ["a" "b" "c" " " " "] (chars-of g 0)))
    (is (true?  (:bold? (get-in g [:cells 0 0]))))
    (is (true?  (:bold? (get-in g [:cells 0 1]))))
    (is (false? (:bold? (get-in g [:cells 0 2]))))
    (is (= :red (:fg (get-in g [:cells 0 2]))))
    ;; Padding cells are blank-default.
    (is (= :default (:fg (get-in g [:cells 0 3]))))
    (is (false? (:bold? (get-in g [:cells 0 3]))))))

(deftest text-runs-base-style-merges-under-run-styles
  (let [g (layout/render-element [:text {:fg :red :bold? true}
                                  [{:text "a"} {:text "b" :fg :blue}]]
                                 {:width 2 :height 1})]
    ;; Run without its own :fg inherits the element base style.
    (is (= :red (:fg (get-in g [:cells 0 0]))))
    (is (true? (:bold? (get-in g [:cells 0 0]))))
    ;; A run's own sparse keys win over the base.
    (is (= :blue (:fg (get-in g [:cells 0 1]))))
    ;; ...but unset keys still inherit.
    (is (true? (:bold? (get-in g [:cells 0 1]))))))

(deftest text-runs-wrap-as-one-paragraph
  ;; Two runs flow together: "bold " (bold) + "plain text" wraps at the
  ;; word boundary, styles correct per cell on both rows.
  (let [g (layout/render-element [:text [{:text "bold " :bold? true}
                                         {:text "plain text"}]]
                                 {:width 10 :height 2})]
    (is (= ["b" "o" "l" "d" " " "p" "l" "a" "i" "n"] (chars-of g 0)))
    (is (= ["t" "e" "x" "t" " " " " " " " " " " " "] (chars-of g 1)))
    (is (true?  (:bold? (get-in g [:cells 0 0]))))
    (is (true?  (:bold? (get-in g [:cells 0 4]))))
    (is (false? (:bold? (get-in g [:cells 0 5]))))
    (is (false? (:bold? (get-in g [:cells 1 0]))))))

(deftest text-runs-continuation-attr
  (let [g (layout/render-element [:text {:continuation [{:text "> " :dim? true}]}
                                  [{:text "aaa bbb ccc"}]]
                                 {:width 7 :height 2})]
    (is (= ["a" "a" "a" " " "b" "b" "b"] (chars-of g 0)))
    (is (= [">" " " "c" "c" "c" " " " "] (chars-of g 1)))
    (is (true? (:dim? (get-in g [:cells 1 0]))))
    (is (false? (:dim? (get-in g [:cells 1 2]))))))

(deftest text-runs-string-path-unchanged
  ;; Regression pin: a plain string child renders exactly as before even
  ;; though the runs branch exists.
  (let [g  (layout/render-element [:text {:fg :red} "alpha beta"]
                                  {:width 5 :height 2})
        g' (layout/render-element [:text {:text "alpha beta" :fg :red}]
                                  {:width 5 :height 2})]
    (is (= g g'))
    (is (= ["a" "l" "p" "h" "a"] (chars-of g 0)))
    (is (= ["b" "e" "t" "a" " "] (chars-of g 1)))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.1 :line
;; ──────────────────────────────────────────────────────────────────────────

(deftest line-fills-width
  (doseq [w [1 5 80]]
    (let [g (layout/render-element [:line] {:width w :height 1})]
      (is (= w (:width g)))
      (is (every? #(= "─" (:char %)) (first (:cells g)))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.1 :gap
;; ──────────────────────────────────────────────────────────────────────────

(deftest gap-zero
  (let [g (layout/render-element [:gap 0] {:width 0 :height 0})]
    (is (= 0 (:width g)))))

(deftest gap-one-cell
  (let [g (layout/render-element [:gap 1] {:width 1 :height 1})]
    (is (= 1 (:width g)))
    (is (= " " (:char (get-in g [:cells 0 0]))))))

(deftest gap-ten-cells
  (let [g (layout/render-element [:gap 10] {:width 10 :height 1})]
    (is (= 10 (:width g)))
    (is (every? #(= " " (:char %)) (first (:cells g))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.2 :row / :col
;; ──────────────────────────────────────────────────────────────────────────

(deftest row-empty-children
  (let [g (layout/render-element [:row] {:width 3 :height 1})]
    (is (= 3 (:width g)))))

(deftest row-mixed-fixed-and-weighted
  (let [g (layout/render-element [:row
                                   [:gap 2]
                                   [:text {:text "abcd"}]
                                   [:gap 2]]
                                  {:width 8 :height 1})]
    (is (= 8 (:width g)))
    (is (= ["  " "abcd" "  "]
           [(apply str (mapv :char (subvec (first (:cells g)) 0 2)))
            (apply str (mapv :char (subvec (first (:cells g)) 2 6)))
            (apply str (mapv :char (subvec (first (:cells g)) 6 8)))]))))

(deftest col-stacks-vertically
  (let [g (layout/render-element [:col
                                   [:text {:text "aaa"}]
                                   [:text {:text "bbb"}]]
                                  {:width 3 :height 2})]
    (is (= ["a" "a" "a"] (chars-of g 0)))
    (is (= ["b" "b" "b"] (chars-of g 1)))))

(deftest weighted-distributes-by-weight
  (let [g (layout/render-element [:row {:weights [1 3]}
                                   [:text {:text "AAAAAAAAAAAA"}]
                                   [:text {:text "BBBBBBBBBBBB"}]]
                                  {:width 12 :height 1})]
    (is (= 12 (:width g)))
    ;; weights [1 3] → 3 cols / 9 cols
    (is (= ["A" "A" "A"] (subvec (chars-of g 0) 0 3)))
    (is (every? #(= "B" %) (subvec (chars-of g 0) 3 12)))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.2 :pad
;; ──────────────────────────────────────────────────────────────────────────

(deftest pad-shrinks-child-box
  (let [g (layout/render-element [:pad {:t 1 :l 1 :r 1 :b 1}
                                   [:text {:text "X"}]]
                                  {:width 3 :height 3})]
    ;; The "X" should land at row 1, col 1
    (is (= "X" (:char (get-in g [:cells 1 1]))))
    (is (= " " (:char (get-in g [:cells 0 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.2 :box (border)
;; ──────────────────────────────────────────────────────────────────────────

(deftest box-with-border
  (let [g (layout/render-element [:box {:border? true}
                                   [:text {:text "X"}]]
                                  {:width 3 :height 3})]
    ;; Borders on row 0, col 0/2; row 2, col 0/2; child at (1,1)
    (is (= "┌" (:char (get-in g [:cells 0 0]))))
    (is (= "┐" (:char (get-in g [:cells 0 2]))))
    (is (= "└" (:char (get-in g [:cells 2 0]))))
    (is (= "┘" (:char (get-in g [:cells 2 2]))))
    (is (= "│" (:char (get-in g [:cells 1 0]))))
    (is (= "X" (:char (get-in g [:cells 1 1]))))))

(deftest box-without-border
  (let [g (layout/render-element [:box {:border? false}
                                   [:text {:text "ab"}]]
                                  {:width 2 :height 1})]
    (is (= ["a" "b"] (chars-of g 0)))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.3 :list
;; ──────────────────────────────────────────────────────────────────────────

(deftest list-empty
  (let [g (layout/render-element [:list {:items []}] {:width 5 :height 2})]
    (is (= 2 (:height g)))))

(deftest list-shows-items-with-marker
  (let [g (layout/render-element [:list {:items [[:text {:text "a"}]
                                                  [:text {:text "b"}]]
                                          :selected 1}]
                                  {:width 5 :height 2})]
    ;; Row 0 unselected: prefix "  ", row 1 selected: prefix "▶ "
    (is (= "  " (apply str (subvec (chars-of g 0) 0 2))))
    (is (= "▶ " (apply str (subvec (chars-of g 1) 0 2))))
    (is (= "a"  (:char (get-in g [:cells 0 2]))))
    (is (= "b"  (:char (get-in g [:cells 1 2]))))))

(deftest list-advertises-item-count-to-column-layout
  (let [g (layout/render-element [:col
                                  [:text {:text "top"}]
                                  [:list {:items [[:text {:text "one"}]
                                                   [:text {:text "two"}]
                                                   [:text {:text "three"}]]
                                          :selected 0}]
                                  [:text {:text "bottom"}]]
                                 {:width 12 :height 5})]
    (is (= "top" (apply str (take 3 (chars-of g 0)))))
    (is (re-find #"one" (apply str (chars-of g 1))))
    (is (re-find #"two" (apply str (chars-of g 2))))
    (is (re-find #"three" (apply str (chars-of g 3))))
    (is (re-find #"bottom" (apply str (chars-of g 4))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.3 :table
;; ──────────────────────────────────────────────────────────────────────────

(deftest table-renders-header-and-rows
  (let [g (layout/render-element
            [:table {:columns [{:key :name :title "Name"}
                               {:key :age  :title "Age"}]
                     :rows [{:name "Ada" :age 30}
                            {:name "Bob" :age 25}]}]
            {:width 10 :height 3})]
    ;; Row 0 = header "Name      Age" (split into two columns of width 5 each)
    (is (re-find #"Name" (apply str (chars-of g 0))))
    (is (re-find #"Ada"  (apply str (chars-of g 1))))
    (is (re-find #"Bob"  (apply str (chars-of g 2))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.3 :scroll
;; ──────────────────────────────────────────────────────────────────────────

(deftest scroll-shows-down-marker-when-overflowing
  (let [g (layout/render-element
            [:scroll {:offset 0}
             [:text {:text "1"}]
             [:text {:text "2"}]
             [:text {:text "3"}]
             [:text {:text "4"}]]
            {:width 5 :height 2})]
    ;; 4 items, height 2, offset 0 → "1" and "2" visible, ▼ marker at last col of last row
    (is (= "1" (:char (get-in g [:cells 0 0]))))
    (is (= "▼" (:char (get-in g [:cells 1 4]))))))

(deftest scroll-shows-up-marker-when-scrolled-down
  (let [g (layout/render-element
            [:scroll {:offset 2}
             [:text {:text "1"}]
             [:text {:text "2"}]
             [:text {:text "3"}]
             [:text {:text "4"}]]
            {:width 5 :height 2})]
    (is (= "▲" (:char (get-in g [:cells 0 4]))))
    (is (= "3" (:char (get-in g [:cells 0 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.4 :turn
;; ──────────────────────────────────────────────────────────────────────────

(deftest turn-renders-role-header-and-body
  (doseq [role [:user :assistant :system]]
    (let [g (layout/render-element
              [:turn {:role role}
               [:text {:text "x"}]]
              {:width 5 :height 2})]
      (is (= 5 (:width g)))
      (is (= 2 (:height g)))
      ;; Body row 1, col 0
      (is (= "x" (:char (get-in g [:cells 1 0])))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.4 :fold
;; ──────────────────────────────────────────────────────────────────────────

(deftest fold-collapsed
  (let [g (layout/render-element [:fold {:summary "more"} [:text {:text "x"}]]
                                  {:width 8 :height 2})]
    (is (= "▶" (:char (get-in g [:cells 0 0]))))
    ;; Body not visible — row 1 is blank because fold returned only the head row
    ))

(deftest fold-expanded
  (let [g (layout/render-element [:fold {:summary "more" :expanded? true}
                                   [:text {:text "x"}]]
                                  {:width 8 :height 2})]
    (is (= "▼" (:char (get-in g [:cells 0 0]))))
    (is (= "x" (:char (get-in g [:cells 1 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.4 :status
;; ──────────────────────────────────────────────────────────────────────────

(deftest status-states
  (doseq [[state glyph] [[:pending "◌"]
                         [:running "●"]
                         [:done    "✓"]
                         [:failed  "✗"]]]
    (let [g (layout/render-element [:status {:state state :label "go"}]
                                    {:width 10 :height 1})]
      (is (= glyph (:char (get-in g [:cells 0 0])))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.4 :progress
;; ──────────────────────────────────────────────────────────────────────────

(deftest progress-zero
  (let [g (layout/render-element [:progress {:value 0.0}] {:width 10 :height 1})]
    (is (every? #(= "░" (:char %)) (first (:cells g))))))

(deftest progress-full
  (let [g (layout/render-element [:progress {:value 1.0}] {:width 10 :height 1})]
    (is (every? #(= "█" (:char %)) (first (:cells g))))))

(deftest progress-half
  (let [g (layout/render-element [:progress {:value 0.5}] {:width 10 :height 1})]
    (is (= 5 (count (filter #(= "█" (:char %)) (first (:cells g))))))
    (is (= 5 (count (filter #(= "░" (:char %)) (first (:cells g))))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.4 :spinner
;; ──────────────────────────────────────────────────────────────────────────

(deftest spinner-uses-phase
  ;; Phase 0 → first frame "⠋"
  (let [g (layout/render-element [:spinner {:label "load" :phase 0}]
                                  {:width 10 :height 1})]
    (is (= "⠋" (:char (get-in g [:cells 0 0])))))
  ;; Phase 1 → second frame "⠙"
  (let [g (layout/render-element [:spinner {:label "load" :phase 1}]
                                  {:width 10 :height 1})]
    (is (= "⠙" (:char (get-in g [:cells 0 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; §7.5 :input
;; ──────────────────────────────────────────────────────────────────────────

(deftest input-empty-shows-placeholder
  (let [g (layout/render-element [:input {:value "" :placeholder "type here"}]
                                  {:width 12 :height 1})]
    (is (= "t" (:char (get-in g [:cells 0 0]))))
    (is (true? (:dim? (get-in g [:cells 0 0]))))))

(deftest input-with-value
  (let [g (layout/render-element [:input {:value "hi"}] {:width 5 :height 1})]
    (is (= ["h" "i" " " " " " "] (chars-of g 0)))))

(deftest input-cursor-is-marked
  (let [g (layout/render-element [:input {:value "hi" :cursor 1}]
                                  {:width 5 :height 1})]
    (is (true? (:underline? (get-in g [:cells 0 1]))))))
