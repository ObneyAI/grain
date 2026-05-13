(ns ai.obney.grain.tui-adapter.input-area
  "Sticky-input state machine + renderer (spec v0.8 §6.2).

   A screen may declare `:tui/input` to opt into a substrate-provided
   text entry area pinned to the bottom of the canvas. The substrate
   handles line editing, cursor management, history navigation, and
   submit-to-command dispatch.

   This namespace is pure: state in, state out. The hosting layer
   (local session or thin client) owns *where* the state lives,
   *when* `handle-key` runs, and *how* a submission turns into a
   Grain command. Both reuse this code so the editing model is
   identical client-side and server-side.

   Key vocabulary consumed (as emitted by `tui-adapter.input`):

     {:type :key :key \"a\"}           ; printable char
     {:type :key :key \"<enter>\"}     ; named keys
     {:type :key :key \"<backspace>\"}
     {:type :key :key \"<left>\"} / \"<right>\" / \"<home>\" / \"<end>\"
                                       ; movement
     {:type :key :key \"<up>\"} / \"<down>\"
                                       ; history (single-line) or
                                       ; cursor-by-line (multi-line)
     {:type :key :key \"<delete>\"}    ; forward delete
     {:type :key :key \"C-a\"} ... \"C-z\"
                                       ; control keys; subset is
                                       ; absorbed (a/e/k/u/w/d), rest
                                       ; passes through.
     {:type :paste :string \"...\"}    ; bracketed-paste arrives as
                                       ; a single event; insert as-is.

   Single-line mode:
     - <enter>       → submit
     - C-d           → passthrough
     - <up> / <down> → history navigation

   Multi-line mode (`:multiline? true`):
     - <enter>       → insert newline
     - C-d           → submit
     - <up> / <down> → move cursor by line; at first/last line, falls
                        through to history navigation."
  (:require [clojure.string :as str]
            [ai.obney.grain.tui-adapter.cells :as cells]))

(def ^:const default-history-max 200)

;; ─────────────────────────────────────────────────────────────────────
;; State
;; ─────────────────────────────────────────────────────────────────────

(defn initial-state
  "The empty input-area state. Hosting layers create one when a screen
   with `:tui/input` becomes current."
  []
  {:value         ""
   :cursor        0
   :history       []
   :history-index nil
   :history-snap  nil})

;; ─────────────────────────────────────────────────────────────────────
;; String helpers
;; ─────────────────────────────────────────────────────────────────────

(defn- insert-at [s idx ins]
  (str (subs s 0 idx) ins (subs s idx)))

(defn- printable?
  "True when `key` is a printable single character. Distinguishes from
   named keys (`<enter>`), modifier-prefixed keys (`C-a`, `M-x`,
   `S-<tab>`), and the empty string."
  [key]
  (and (string? key)
       (pos? (count key))
       (not (.startsWith ^String key "<"))
       (not (.startsWith ^String key "C-"))
       (not (.startsWith ^String key "M-"))
       (not (.startsWith ^String key "S-"))))

;; ─────────────────────────────────────────────────────────────────────
;; Line/column helpers (multi-line cursor navigation)
;; ─────────────────────────────────────────────────────────────────────

(defn cursor->line-col
  "Translate a character index in `value` into `[line col]` where
   newlines split lines. `\"ab\\ncd\"` with cursor 4 → `[1 1]`."
  [value cursor]
  (let [pre (subs value 0 (min cursor (count value)))
        ;; -1 to keep trailing empty strings (so cursor right after a
        ;; trailing \n lands on the next line).
        lines-before (str/split pre #"\n" -1)
        line (dec (count lines-before))
        col  (count (last lines-before))]
    [line col]))

(defn line-col->cursor
  "Translate a `[line col]` pair back into a character index in
   `value`. Clamps line and col to valid ranges."
  [value [line col]]
  (let [lines (str/split value #"\n" -1)
        line  (max 0 (min line (dec (count lines))))
        len-of-line (count (nth lines line))
        col   (max 0 (min col len-of-line))
        before-lines (take line lines)
        offset (+ (reduce + (map count before-lines))
                  line ; one \n per line break before us
                  col)]
    offset))

;; ─────────────────────────────────────────────────────────────────────
;; Edit primitives (each returns a new state; reset history scrolling)
;; ─────────────────────────────────────────────────────────────────────

(defn- clear-history-scroll [state]
  (assoc state :history-index nil :history-snap nil))

(defn- handle-printable [state s]
  (let [{:keys [value cursor]} state]
    (-> state
        (assoc :value (insert-at value cursor s)
               :cursor (+ cursor (count s)))
        clear-history-scroll)))

(defn- handle-backspace [state]
  (let [{:keys [value cursor]} state]
    (if (zero? cursor)
      state
      (-> state
          (assoc :value (str (subs value 0 (dec cursor))
                             (subs value cursor))
                 :cursor (dec cursor))
          clear-history-scroll))))

(defn- handle-delete [state]
  (let [{:keys [value cursor]} state]
    (if (>= cursor (count value))
      state
      (-> state
          (assoc :value (str (subs value 0 cursor)
                             (subs value (inc cursor))))
          clear-history-scroll))))

(defn- handle-left  [state] (update state :cursor (fn [c] (max 0 (dec c)))))
(defn- handle-right [state]
  (let [{:keys [value cursor]} state]
    (assoc state :cursor (min (count value) (inc cursor)))))

(defn- handle-home  [state] (assoc state :cursor 0))
(defn- handle-end   [state] (assoc state :cursor (count (:value state))))

(defn- kill-to-end [state]
  (let [{:keys [value cursor]} state]
    (-> state
        (assoc :value (subs value 0 cursor))
        clear-history-scroll)))

(defn- kill-to-start [state]
  (let [{:keys [value cursor]} state]
    (-> state
        (assoc :value (subs value cursor) :cursor 0)
        clear-history-scroll)))

(defn- kill-word-backward
  "Emacs-style C-w: delete from the cursor back through any trailing
   whitespace, then through the contiguous non-whitespace run."
  [state]
  (let [{:keys [value cursor]} state
        before (subs value 0 cursor)
        after  (subs value cursor)
        trimmed (-> before
                    (str/replace #"\s+$" "")
                    (str/replace #"\S+$" ""))]
    (-> state
        (assoc :value (str trimmed after) :cursor (count trimmed))
        clear-history-scroll)))

;; ─────────────────────────────────────────────────────────────────────
;; History navigation
;; ─────────────────────────────────────────────────────────────────────

(defn- history-up [state]
  (let [{:keys [value history history-index]} state
        n (count history)]
    (cond
      (zero? n)         state
      (nil? history-index)
      (let [idx (dec n)]
        (assoc state
               :history-snap value
               :history-index idx
               :value (nth history idx)
               :cursor (count (nth history idx))))
      (pos? history-index)
      (let [idx (dec history-index)]
        (assoc state
               :history-index idx
               :value (nth history idx)
               :cursor (count (nth history idx))))
      :else state)))

(defn- history-down [state]
  (let [{:keys [history history-index history-snap]} state
        n (count history)]
    (cond
      (nil? history-index) state
      (= history-index (dec n))
      (assoc state
             :history-index nil
             :value (or history-snap "")
             :cursor (count (or history-snap ""))
             :history-snap nil)
      :else
      (let [idx (inc history-index)]
        (assoc state
               :history-index idx
               :value (nth history idx)
               :cursor (count (nth history idx)))))))

;; ─────────────────────────────────────────────────────────────────────
;; Multi-line vertical cursor movement (with fall-through to history)
;; ─────────────────────────────────────────────────────────────────────

(defn- multiline-up [state]
  (let [{:keys [value cursor]} state
        [line col] (cursor->line-col value cursor)]
    (if (zero? line)
      (history-up state)
      (assoc state :cursor (line-col->cursor value [(dec line) col])))))

(defn- multiline-down [state]
  (let [{:keys [value cursor]} state
        [line col] (cursor->line-col value cursor)
        lines (str/split value #"\n" -1)
        last-line (dec (count lines))]
    (if (>= line last-line)
      (history-down state)
      (assoc state :cursor (line-col->cursor value [(inc line) col])))))

;; ─────────────────────────────────────────────────────────────────────
;; Submit
;; ─────────────────────────────────────────────────────────────────────

(defn- submit [state history-max]
  (let [{:keys [value history]} state
        text         value
        new-history  (let [h (conj (vec history) text)]
                       (if (> (count h) history-max)
                         (vec (drop (- (count h) history-max) h))
                         h))]
    {:state {:value "" :cursor 0
             :history new-history
             :history-index nil
             :history-snap nil}
     :submission text
     :passthrough? false}))

;; ─────────────────────────────────────────────────────────────────────
;; Public: handle-key
;; ─────────────────────────────────────────────────────────────────────

(defn handle-key
  "Dispatch a single input event against the state. Returns

     {:state new-state
      :submission <string-or-nil>
      :passthrough? <bool>}

   - `:submission` is non-nil exactly when this event triggered a
     submit; the returned `:state` already has its buffer cleared and
     the submission pushed onto history.
   - `:passthrough? true` means the key was not absorbed; the host
     should fall back to the screen-level keymap resolver (so binds
     like `<esc>` and unbound Ctrl-combos still work).

   `opts`:
     `:multiline?`     boolean; default false.
     `:history-max`    cap on the history ring; default 200."
  [state event {:keys [multiline? history-max]
                :or   {multiline? false history-max default-history-max}}]
  (let [no-sub (fn [st] {:state st :submission nil :passthrough? false})
        pass   {:state state :submission nil :passthrough? true}
        type   (:type event)
        key    (:key  event)]
    (cond
      (= type :paste)
      (no-sub (handle-printable state (or (:string event) "")))

      (not= type :key)
      pass

      :else
      (case key
        "<enter>"     (if multiline?
                        (no-sub (handle-printable state "\n"))
                        (submit state history-max))
        "C-d"         (if multiline?
                        (submit state history-max)
                        pass)
        "<backspace>" (no-sub (handle-backspace state))
        "<delete>"    (no-sub (handle-delete state))
        "<left>"      (no-sub (handle-left state))
        "<right>"     (no-sub (handle-right state))
        "<home>"      (no-sub (handle-home state))
        "<end>"       (no-sub (handle-end state))
        "C-a"         (no-sub (handle-home state))
        "C-e"         (no-sub (handle-end state))
        "C-k"         (no-sub (kill-to-end state))
        "C-u"         (no-sub (kill-to-start state))
        "C-w"         (no-sub (kill-word-backward state))
        "<up>"        (no-sub (if multiline?
                                (multiline-up state)
                                (history-up state)))
        "<down>"      (no-sub (if multiline?
                                (multiline-down state)
                                (history-down state)))
        ;; Anything else: printable → insert; non-edit-key → passthrough.
        (if (printable? key)
          (no-sub (handle-printable state key))
          pass)))))

;; ─────────────────────────────────────────────────────────────────────
;; Renderer
;; ─────────────────────────────────────────────────────────────────────

(defn- single-line-render
  "Render single-line mode. Horizontally scrolls the buffer to keep the
   cursor on-screen. Returns `{:grid g :cursor-pos [col row]}`."
  [state {:keys [prompt width]}]
  (let [{:keys [value cursor]} state
        prompt-w (count prompt)
        avail    (max 1 (- width prompt-w))
        scroll   (max 0 (- cursor (dec avail)))
        end      (min (count value) (+ scroll avail))
        visible  (subs value scroll end)
        row      (str prompt visible)]
    {:grid       (cells/text-row width {} row)
     :cursor-pos [(min (dec width) (+ prompt-w (- cursor scroll))) 0]}))

(defn- multiline-render
  "Render multi-line mode. Stacks each line of the buffer into its own
   row. The prompt is shown on row 0 only; subsequent rows have no
   prompt indent (keeps it simple — could be improved). When the
   buffer exceeds `:height`, scrolls vertically to keep the cursor
   line visible."
  [state {:keys [prompt width height]}]
  (let [{:keys [value cursor]} state
        prompt-w (count prompt)
        lines    (str/split value #"\n" -1)
        [c-line c-col] (cursor->line-col value cursor)
        ;; Vertical scroll: keep cursor line in [scroll, scroll+height)
        scroll   (max 0 (- c-line (dec height)))
        visible-lines (subvec (vec lines) scroll (min (count lines) (+ scroll height)))
        grids    (vec (map-indexed
                        (fn [i ln]
                          (let [line-index (+ scroll i)
                                prefix     (if (zero? line-index) prompt "")
                                prefix-w   (count prefix)
                                line-avail (max 1 (- width prefix-w))
                                trimmed    (if (> (count ln) line-avail)
                                             (subs ln 0 line-avail)
                                             ln)
                                row        (str prefix trimmed)]
                            (cells/text-row width {} row)))
                        visible-lines))
        ;; Pad with blank rows if buffer is shorter than height.
        padded   (let [missing (- height (count grids))]
                   (if (pos? missing)
                     (apply cells/stack (concat grids (repeat missing (cells/blank width 1))))
                     (apply cells/stack grids)))
        cur-row  (- c-line scroll)
        cur-col  (+ (if (zero? c-line) prompt-w 0) c-col)]
    {:grid       (or padded (cells/blank width height))
     :cursor-pos [(min (dec width) cur-col)
                  (max 0 (min (dec height) cur-row))]}))

(defn- rule-row [width]
  (cells/text-row width {:dim? true} (apply str (repeat width "─"))))

(defn- chrome-grid
  "The 2-row chrome under the prompt: a horizontal rule, then a hint
   row. Both styled `:dim?` so they don't compete visually with the
   buffer cursor above."
  [width hint]
  (cells/stack
    (rule-row width)
    (cells/text-row width {:dim? true} (str "  " (or hint "")))))

(defn- prompt-rows
  "How many rows the *prompt portion* of the input area occupies
   (excluding chrome). Single-line: always 1. Multi-line: lines in
   the buffer, capped at `:max-rows`."
  [state {:keys [multiline? max-rows] :or {multiline? false max-rows 5}}]
  (cond
    (nil? state)       1
    (not multiline?)   1
    :else
    (let [lines (inc (count (filter #(= % \newline) (:value state ""))))]
      (max 1 (min max-rows lines)))))

(defn preferred-height
  "Compute how many rows the input area should occupy given `state`
   and the screen's `:tui/input` config map.

   The chrome always includes a rule above the prompt (1 row) so the
   input area visually separates from the content above it. With
   `:hint` declared, two more rows are reserved (rule + hint) below
   the prompt.

   - Single-line, no hint: 2 (rule + prompt).
   - Single-line + :hint:  4 (rule + prompt + rule + hint).
   - Multi-line: 1 + prompt-rows + (2 if :hint else 0)."
  [state {:keys [hint] :as cfg}]
  (+ 1 ; top rule
     (prompt-rows state cfg)
     (if hint 2 0)))

(defn render
  "Render the input-area state into a CellGrid of `width × height`.
   Returns `{:grid <CellGrid> :cursor-pos [col row]}`. The cursor pos
   is in screen coords relative to the input area's box; the caller
   translates that into ANSI cursor-positioning.

   Chrome structure (top → bottom):
     row 0        — horizontal rule (separator from content above)
     row 1..p     — prompt + buffer (p rows; 1 for single-line)
     row p+1      — horizontal rule (when `:hint` is declared)
     row p+2      — hint text (when `:hint` is declared)

   `opts`:
     :prompt     prompt string (e.g. \"> \"); default \"\".
     :width      box width; required.
     :height     box height; required.
     :multiline? boolean; default false.
     :hint       optional dim-styled hint string rendered below the
                 prompt. When declared, adds a rule + hint row."
  [state {:keys [prompt width height multiline? hint]
          :or   {prompt "" multiline? false}
          :as   opts}]
  (let [top-rule-h   1
        bottom-h     (if hint 2 0)
        prompt-h     (max 1 (- height top-rule-h bottom-h))
        prompt-opts  (assoc opts :prompt prompt :width width
                            :height prompt-h :multiline? multiline?)
        prompt-result (if (or (not multiline?) (= 1 prompt-h))
                        (single-line-render state prompt-opts)
                        (multiline-render state prompt-opts))
        prompt-grid  (:grid prompt-result)
        full-grid    (apply cells/stack
                            (cond-> [(rule-row width) prompt-grid]
                              hint (conj (chrome-grid width hint))))
        ;; Cursor row was relative to the prompt grid (0-indexed within
        ;; the prompt portion). Shift by 1 to account for the top rule.
        [c-col c-row] (:cursor-pos prompt-result)]
    {:grid       full-grid
     :cursor-pos [c-col (inc c-row)]}))
