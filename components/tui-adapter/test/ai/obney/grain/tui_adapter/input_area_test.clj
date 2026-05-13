(ns ai.obney.grain.tui-adapter.input-area-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.input-area :as ia]))

(def s0 (ia/initial-state))

(defn- key-evt [k] {:type :key :key k})

(defn- press
  "Drive a sequence of key strings (or events) through `handle-key`,
   returning the final state. Discards passthrough/submission events
   for terseness — for those, use `step` instead."
  [state opts & keys]
  (reduce (fn [st k]
            (:state (ia/handle-key st (if (map? k) k (key-evt k)) opts)))
          state keys))

(defn- step [state opts k]
  (ia/handle-key state (if (map? k) k (key-evt k)) opts))

;; ─────────────────────────────────────────────────────────────────────
;; Initial state + invariants
;; ─────────────────────────────────────────────────────────────────────

(deftest initial-state-shape
  (is (= {:value "" :cursor 0 :history [] :history-index nil :history-snap nil}
         (ia/initial-state))))

;; ─────────────────────────────────────────────────────────────────────
;; Printable insertion
;; ─────────────────────────────────────────────────────────────────────

(deftest insert-printable-at-end
  (let [st (press s0 {} "h" "i")]
    (is (= "hi" (:value st)))
    (is (= 2   (:cursor st)))))

(deftest insert-printable-at-middle
  (let [st (-> (press s0 {} "h" "o")
               (press {} "<left>")
               (press {} "i"))]
    (is (= "hio" (:value st)))
    (is (= 2    (:cursor st)))))

(deftest insert-printable-at-start
  (let [st (-> (press s0 {} "i")
               (press {} "<home>")
               (press {} "x"))]
    (is (= "xi" (:value st)))
    (is (= 1   (:cursor st)))))

(deftest utf8-char-inserted
  (let [st (press s0 {} "é")]
    (is (= "é" (:value st)))
    (is (= 1   (:cursor st)))))

;; ─────────────────────────────────────────────────────────────────────
;; Deletion: backspace + <delete>
;; ─────────────────────────────────────────────────────────────────────

(deftest backspace-at-start-noop
  (let [st (press s0 {} "<backspace>")]
    (is (= s0 st))))

(deftest backspace-removes-prior-char
  (let [st (press s0 {} "a" "b" "<backspace>")]
    (is (= "a" (:value st)))
    (is (= 1   (:cursor st)))))

(deftest delete-at-end-noop
  (let [st (press s0 {} "a" "<delete>")]
    (is (= "a" (:value st)))
    (is (= 1   (:cursor st)))))

(deftest delete-removes-next-char
  (let [st (-> (press s0 {} "a" "b")
               (press {} "<home>")
               (press {} "<delete>"))]
    (is (= "b" (:value st)))
    (is (= 0   (:cursor st)))))

;; ─────────────────────────────────────────────────────────────────────
;; Cursor movement
;; ─────────────────────────────────────────────────────────────────────

(deftest left-clamps-at-zero
  (let [st (press s0 {} "<left>" "<left>" "<left>")]
    (is (= 0 (:cursor st)))))

(deftest right-clamps-at-end
  (let [st (press s0 {} "a" "<right>" "<right>")]
    (is (= 1 (:cursor st)))))

(deftest home-and-end
  (let [filled (press s0 {} "h" "e" "l" "l" "o")
        at-start (press filled {} "<home>")
        at-end   (press at-start {} "<end>")]
    (is (= 0 (:cursor at-start)))
    (is (= 5 (:cursor at-end)))))

(deftest c-a-and-c-e-mirror-home-and-end
  (let [filled (press s0 {} "h" "i")
        ca     (press filled {} "C-a")
        ce     (press ca     {} "C-e")]
    (is (= 0 (:cursor ca)))
    (is (= 2 (:cursor ce)))))

;; ─────────────────────────────────────────────────────────────────────
;; Kill keys
;; ─────────────────────────────────────────────────────────────────────

(deftest c-k-kills-to-end
  (let [st (-> (press s0 {} "a" "b" "c")
               (press {} "<left>" "<left>")  ; cursor at 1
               (press {} "C-k"))]
    (is (= "a" (:value st)))
    (is (= 1   (:cursor st)))))

(deftest c-u-kills-to-start
  (let [st (-> (press s0 {} "a" "b" "c")
               (press {} "<left>")           ; cursor at 2
               (press {} "C-u"))]
    (is (= "c" (:value st)))
    (is (= 0   (:cursor st)))))

(deftest c-w-kills-word-backward
  (testing "word + whitespace removed"
    (let [st (-> (press s0 {} "h" "e" "l" "l" "o" " " "w" "o" "r" "l" "d")
                 (press {} "C-w"))]
      (is (= "hello " (:value st)))
      (is (= 6 (:cursor st)))))
  (testing "trailing whitespace alone is consumed"
    (let [st (-> (press s0 {} "a" " " " " " ")
                 (press {} "C-w"))]
      (is (= "" (:value st)))
      (is (= 0 (:cursor st))))))

;; ─────────────────────────────────────────────────────────────────────
;; Submit — single-line: <enter> ; multi-line: C-d
;; ─────────────────────────────────────────────────────────────────────

(deftest enter-submits-single-line
  (let [r (step (press s0 {} "h" "i") {} "<enter>")]
    (is (= "hi" (:submission r)))
    (is (= ""   (-> r :state :value)))
    (is (= 0    (-> r :state :cursor)))
    (is (= ["hi"] (-> r :state :history)))))

(deftest enter-inserts-newline-in-multiline
  (let [r (step (press s0 {:multiline? true} "a")
                {:multiline? true} "<enter>")]
    (is (nil? (:submission r)))
    (is (= "a\n" (-> r :state :value)))))

(deftest c-d-submits-multiline
  (let [r (step (press s0 {:multiline? true} "a" "b")
                {:multiline? true} "C-d")]
    (is (= "ab" (:submission r)))
    (is (= ""   (-> r :state :value)))))

(deftest c-d-is-passthrough-single-line
  (let [r (step (press s0 {} "a") {} "C-d")]
    (is (true? (:passthrough? r)))
    (is (nil?  (:submission r)))))

(deftest submit-caps-history
  ;; With history-max 3 and 5 submits, only the last 3 survive.
  (let [opts {:history-max 3}
        st   (reduce (fn [st i]
                       (-> (ia/handle-key st (key-evt (str i)) opts)
                           :state
                           (#(:state (ia/handle-key % (key-evt "<enter>") opts)))))
                     s0
                     [1 2 3 4 5])]
    (is (= ["3" "4" "5"] (:history st)))))

;; ─────────────────────────────────────────────────────────────────────
;; History navigation
;; ─────────────────────────────────────────────────────────────────────

(deftest up-after-submit-recalls
  (let [submitted (-> (press s0 {} "h" "i")
                      (press {} "<enter>"))   ; history: ["hi"]
        recalled  (press submitted {} "<up>")]
    (is (= "hi" (:value recalled)))
    (is (= 2   (:cursor recalled)))
    (is (= 0   (:history-index recalled)))))

(deftest up-multiple-times-walks-back
  (let [st (-> (press s0 {} "o" "n" "e")
               (press {} "<enter>")
               (press {} "t" "w" "o")
               (press {} "<enter>")
               (press {} "<up>")     ; "two"
               (press {} "<up>"))]   ; "one"
    (is (= "one" (:value st)))
    (is (= 0     (:history-index st)))))

(deftest down-past-newest-restores-buffer-snapshot
  (let [submitted (-> (press s0 {} "a")
                      (press {} "<enter>"))
        ;; Type something fresh
        typed     (press submitted {} "b")
        ;; Scroll up then back down
        up        (press typed {} "<up>")
        down      (press up    {} "<down>")]
    (is (= "b" (:value down)))
    (is (nil?  (:history-index down)))))

(deftest up-on-empty-history-is-noop
  (let [st (press s0 {} "<up>")]
    (is (= s0 st))))

(deftest edit-clears-history-scrolling
  (let [submitted (-> (press s0 {} "a")
                      (press {} "<enter>"))
        up        (press submitted {} "<up>")
        edited    (press up        {} "x")]
    (is (= "ax" (:value edited)))
    (is (nil?  (:history-index edited)))))

;; ─────────────────────────────────────────────────────────────────────
;; Paste
;; ─────────────────────────────────────────────────────────────────────

(deftest paste-bulk-inserts
  (let [r (step s0 {} {:type :paste :string "hello world"})]
    (is (= "hello world" (-> r :state :value)))
    (is (= 11           (-> r :state :cursor)))))

(deftest paste-at-cursor
  (let [r (-> (press s0 {} "X" "Y")
              (press {} "<left>")
              (step  {} {:type :paste :string "AB"}))]
    (is (= "XABY" (-> r :state :value)))))

;; ─────────────────────────────────────────────────────────────────────
;; Passthrough — unhandled keys
;; ─────────────────────────────────────────────────────────────────────

(deftest unrecognized-named-key-is-passthrough
  (let [r (step s0 {} "<f1>")]
    (is (true? (:passthrough? r)))
    (is (= s0  (:state r)))))

(deftest c-c-is-passthrough
  (let [r (step (press s0 {} "a") {} "C-c")]
    (is (true? (:passthrough? r)))))

(deftest non-key-event-is-passthrough
  (let [r (ia/handle-key s0 {:type :resize :size [80 24]} {})]
    (is (true? (:passthrough? r)))))

;; ─────────────────────────────────────────────────────────────────────
;; Multi-line cursor navigation
;; ─────────────────────────────────────────────────────────────────────

(deftest multiline-up-at-first-line-falls-back-to-history
  (let [submitted (-> (press s0 {} "h" "i") (press {} "<enter>"))
        ;; Empty buffer, multi-line
        recalled  (press submitted {:multiline? true} "<up>")]
    (is (= "hi" (:value recalled)))))

(deftest multiline-up-on-second-line-moves-cursor
  ;; Buffer: "abc\ndef", cursor on second line at col 1
  (let [opts {:multiline? true}
        st (-> (press s0 opts "a" "b" "c" "<enter>" "d" "e" "f")
               (press opts "<home>")   ; cursor at start of line 1
               (press opts "<right>")) ; cursor at col 1 of line 1
        up (press st opts "<up>")]
    ;; Cursor should be on line 0 at col 1 (between a and b).
    (is (= [0 1] (ia/cursor->line-col (:value up) (:cursor up))))))

(deftest multiline-down-at-last-line-falls-back-to-history
  (let [submitted (-> (press s0 {:multiline? true} "x")
                      (step {:multiline? true} "C-d"))
        st (:state submitted)
        ;; Buffer is empty, history has "x"; up recalls
        recalled (press st {:multiline? true} "<up>")
        ;; Going down should restore snapshot ("")
        downed   (press recalled {:multiline? true} "<down>")]
    (is (= "" (:value downed)))))

(deftest cursor-line-col-roundtrip
  (let [v "abc\ndef\nghi"]
    (doseq [i (range (inc (count v)))]
      (let [lc (ia/cursor->line-col v i)
            back (ia/line-col->cursor v lc)]
        (is (= i back)
            (str "round-trip failed at cursor=" i " (line-col=" lc ")"))))))

;; ─────────────────────────────────────────────────────────────────────
;; Render
;; ─────────────────────────────────────────────────────────────────────

(defn- chars-of [grid r]
  (apply str (mapv :char (get-in grid [:cells r]))))

;; Chrome layout: row 0 is always a top rule; the prompt sits on row 1.
;; If `:hint` is declared, rows 2 and 3 carry a rule + hint.

(deftest render-single-line-prompt-and-buffer
  (let [st (press s0 {} "h" "i")
        {:keys [grid cursor-pos]} (ia/render st {:prompt "› " :width 10 :height 2})]
    ;; row 0 = top rule; row 1 = prompt + buffer
    (is (= "──────────" (chars-of grid 0)))
    (is (= "› hi      " (chars-of grid 1)))
    ;; cursor after "› hi" at row 1
    (is (= [4 1] cursor-pos))))

(deftest render-single-line-cursor-at-start
  (let [{:keys [cursor-pos]} (ia/render s0 {:prompt "› " :width 10 :height 2})]
    (is (= [2 1] cursor-pos))))

(deftest render-single-line-horizontal-scroll
  ;; Buffer wider than available; cursor at end → buffer scrolls so the
  ;; cursor sits at the rightmost column. Top rule on row 0, prompt+buffer
  ;; on row 1.
  (let [st  (reduce (fn [s ch] (:state (ia/handle-key s (key-evt (str ch)) {})))
                    s0
                    "abcdefghijklmnop")     ; 16 chars
        {:keys [grid cursor-pos]} (ia/render st {:prompt "" :width 5 :height 2})]
    (is (= "─────" (chars-of grid 0)))
    (is (= "mnop " (chars-of grid 1)))
    (is (= [4 1]   cursor-pos))))

(deftest render-single-line-cursor-mid-buffer
  ;; Cursor not at end → no scroll, buffer shown from the start.
  (let [st  (-> (reduce (fn [s ch] (:state (ia/handle-key s (key-evt (str ch)) {})))
                        s0 "abcdef")
                (press {} "<home>")
                (press {} "<right>"))   ; cursor at position 1
        {:keys [grid cursor-pos]} (ia/render st {:prompt "" :width 5 :height 2})]
    (is (= "abcde" (chars-of grid 1)))
    (is (= [1 1]   cursor-pos))))

(deftest render-multiline-stacks-lines
  ;; Top rule + 3 prompt rows = 4 total
  (let [st (press s0 {:multiline? true} "a" "<enter>" "b" "<enter>" "c")
        {:keys [grid cursor-pos]} (ia/render st {:prompt "" :width 5 :height 4
                                                  :multiline? true})]
    (is (= "─────" (chars-of grid 0)))
    (is (= "a    " (chars-of grid 1)))
    (is (= "b    " (chars-of grid 2)))
    (is (= "c    " (chars-of grid 3)))
    ;; Cursor after "c" — line 2 within prompt (rows 1..3), so row 3 overall
    (is (= [1 3] cursor-pos))))

;; ─────────────────────────────────────────────────────────────────────
;; preferred-height
;; ─────────────────────────────────────────────────────────────────────

(deftest preferred-height-single-line-base
  ;; 1 top rule + 1 prompt row = 2 (no hint)
  (is (= 2 (ia/preferred-height s0 {})))
  (is (= 2 (ia/preferred-height (press s0 {} "a" "b" "c") {}))))

(deftest preferred-height-single-line-with-hint
  ;; 1 top rule + 1 prompt + 2 chrome (rule + hint) = 4
  (is (= 4 (ia/preferred-height s0 {:hint "x"}))))

(deftest preferred-height-multiline-grows-with-newlines
  (let [st (press s0 {:multiline? true} "a" "<enter>" "b")]
    ;; 1 top rule + 2 prompt rows = 3
    (is (= 3 (ia/preferred-height st {:multiline? true})))))

(deftest preferred-height-caps-at-max-rows
  (let [st (press s0 {:multiline? true} "a" "<enter>" "b" "<enter>" "c" "<enter>" "d"
                  "<enter>" "e" "<enter>" "f")]
    ;; 1 top rule + 5 prompt rows (capped) = 6
    (is (= 6 (ia/preferred-height st {:multiline? true :max-rows 5})))))
