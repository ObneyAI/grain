(ns ai.obney.grain.tui-adapter.text-wrap-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.text-wrap :as tw]))

(defn- flat-line
  "Concatenate a visual line of runs back to its plain string."
  [line]
  (apply str (map :text line)))

(defn- flat-lines [lines] (mapv flat-line lines))

;; ──────────────────────────────────────────────────────────────────────────
;; 1. Parity — a single unstyled run wraps exactly like the string path
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-parity-with-string-wrap
  ;; Property: for strings with no leading/trailing/repeated whitespace,
  ;; the runs path reproduces `visual-lines` byte-for-byte.
  (doseq [w [0 1 3 5 8 12 40]
          s ["alpha beta gamma"
             "hello world this is a wrap test"
             "abcdefghijkl"
             "a"
             ""
             "one two"
             "a\nb"
             "supercalifragilisticexpialidocious wrap"]]
    (is (= (tw/visual-lines w s)
           (flat-lines (tw/visual-lines-runs w [{:text s}])))
        (str "width " w " text " (pr-str s)))))

(deftest runs-parity-line-counts
  (doseq [w [1 5 10]
          s ["alpha beta gamma" "abcdefghijkl" ""]]
    (is (= (tw/visual-line-count w s)
           (tw/visual-line-count-runs w [{:text s}])))))

;; ──────────────────────────────────────────────────────────────────────────
;; 2. Styled runs flow as one paragraph across wrap points
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-span-wrapping-across-breaks
  (let [lines (tw/visual-lines-runs 13 [{:text "red words " :fg :red}
                                        {:text "and green tail" :fg :green}])]
    (is (= ["red words and" "green tail"] (flat-lines lines)))
    ;; First line carries both styles, fragment by fragment.
    (is (= [{:text "red words " :fg :red}
            {:text "and" :fg :green}]
           (first lines)))
    ;; Second line is entirely green; the wrap-point space was consumed.
    (is (= [{:text "green tail" :fg :green}] (second lines)))
    (is (not (string/starts-with? (flat-line (second lines)) " ")))))

(deftest runs-wrap-point-space-consumed
  (let [lines (tw/visual-lines-runs 9 [{:text "red words " :fg :red}
                                       {:text "and green tail" :fg :green}])]
    (is (= ["red words" "and green" "tail"] (flat-lines lines)))
    (is (every? #(not (string/ends-with? (flat-line %) " ")) lines))
    (is (every? #(not (string/starts-with? (flat-line %) " ")) lines))))

;; ──────────────────────────────────────────────────────────────────────────
;; 3. Words crossing a style boundary never split at the boundary
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-style-boundary-mid-word
  (let [lines (tw/visual-lines-runs 4 [{:text "bo" :bold? true}
                                       {:text "ld more"}])]
    ;; "bold" is one word despite the style boundary — never wrapped there.
    (is (= ["bold" "more"] (flat-lines lines)))
    (is (= [{:text "bo" :bold? true} {:text "ld"}] (first lines)))
    (is (= [{:text "more"}] (second lines)))))

;; ──────────────────────────────────────────────────────────────────────────
;; 4. Oversize tokens hard-wrap; chunks keep their run's style
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-hard-wrap-inside-a-run
  (let [lines (tw/visual-lines-runs 4 [{:text "x "}
                                       {:text "abcdefghij" :fg :red}])]
    (is (= ["x" "abcd" "efgh" "ij"] (flat-lines lines)))
    (doseq [line (rest lines)]
      (is (= :red (:fg (first line)))
          "every hard-wrapped chunk carries the run's style"))))

(deftest runs-hard-wrap-across-style-boundary
  ;; One oversize word spanning two runs: chunks split per style.
  (let [lines (tw/visual-lines-runs 4 [{:text "abc"}
                                       {:text "defgh ij" :fg :red}])]
    (is (= ["abcd" "efgh" "ij"] (flat-lines lines)))
    (is (= [{:text "abc"} {:text "d" :fg :red}] (first lines)))
    (is (= [{:text "efgh" :fg :red}] (second lines)))))

;; ──────────────────────────────────────────────────────────────────────────
;; 5. Whitespace fidelity — indentation and internal spaces survive
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-preserve-leading-indent-and-internal-spaces
  (is (= ["    x  y"]
         (flat-lines (tw/visual-lines-runs 10 [{:text "    x  y"}]))))
  ;; Indent preserved on the first visual line; the continuation line
  ;; starts with the word, not carried whitespace.
  (is (= ["  abc" "def"]
         (flat-lines (tw/visual-lines-runs 6 [{:text "  abc def"}])))))

;; ──────────────────────────────────────────────────────────────────────────
;; 6. Continuation prefix
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-continuation
  (testing "prepended after the first visual line of each logical line"
    (let [lines (tw/visual-lines-runs 7
                                      [{:text "aaa bbb ccc\nddd eee fff"}]
                                      "> ")]
      (is (= ["aaa bbb" "> ccc" "ddd eee" "> fff"] (flat-lines lines)))))
  (testing "a runs continuation keeps its style"
    (let [lines (tw/visual-lines-runs 7
                                      [{:text "aaa bbb ccc"}]
                                      [{:text "> " :dim? true}])]
      (is (= ["aaa bbb" "> ccc"] (flat-lines lines)))
      (is (= {:text "> " :dim? true} (first (second lines))))))
  (testing "reduces the wrap width for continuation lines"
    ;; Width 7, continuation of 4 → continuations fit only 3 columns.
    (is (= ["aaa bbb" "--> ccc" "--> ddd"]
           (flat-lines (tw/visual-lines-runs 7 [{:text "aaa bbb ccc ddd"}] "--> ")))))
  (testing "ignored entirely when its length >= width"
    (is (= (flat-lines (tw/visual-lines-runs 4 [{:text "aaa bbb"}]))
           (flat-lines (tw/visual-lines-runs 4 [{:text "aaa bbb"}] "...."))
           (flat-lines (tw/visual-lines-runs 4 [{:text "aaa bbb"}] "longer-than-width"))))))

;; ──────────────────────────────────────────────────────────────────────────
;; 7. Blank lines, empty runs, embedded newlines
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-blank-and-empty
  (is (= [[{:text ""}]] (tw/visual-lines-runs 5 [{:text ""}])))
  (is (= [[{:text ""}]] (tw/visual-lines-runs 5 [])))
  ;; "\n\n" produces a blank logical line of one empty-text run.
  (is (= [[{:text "a"}] [{:text ""}] [{:text "b"}]]
         (tw/visual-lines-runs 5 [{:text "a\n\nb"}])))
  ;; A run ending in \n yields a trailing blank visual line
  ;; ((split s #"\n" -1) semantics).
  (is (= [[{:text "a"}] [{:text ""}]]
         (tw/visual-lines-runs 5 [{:text "a\n"}])))
  ;; Newlines may sit at run boundaries.
  (is (= [[{:text "a" :bold? true}] [{:text "b"}]]
         (tw/visual-lines-runs 5 [{:text "a\n" :bold? true} {:text "b"}])))
  (is (= 1 (tw/visual-line-count-runs 5 [])))
  (is (= 3 (tw/visual-line-count-runs 5 [{:text "a\n\nb"}]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Adjacent equal-style chars re-merge into sparse runs
;; ──────────────────────────────────────────────────────────────────────────

(deftest runs-equal-styles-remerge
  (let [lines (tw/visual-lines-runs 20 [{:text "one "}
                                        {:text "two"}
                                        {:text " three" :fg :red}
                                        {:text " four" :fg :red}])]
    (is (= [[{:text "one two"} {:text " three four" :fg :red}]]
           lines)
        "adjacent equal-style fragments merge; absent style keys stay absent")))
