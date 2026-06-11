(ns ai.obney.grain.tui-adapter.text-wrap
  "Terminal text wrapping helpers shared by preferred-size and render paths."
  (:require [clojure.string :as string]))

(defn- hard-wrap
  [width s]
  (if (or (zero? width) (empty? s))
    [""]
    (mapv #(apply str %) (partition-all width s))))

(defn- wrap-words
  [width s]
  (loop [words (seq (string/split s #"\s+"))
         line  ""
         out   []]
    (cond
      (nil? words)
      (if (empty? line) out (conj out line))

      (empty? line)
      (let [word (first words)]
        (if (> (count word) width)
          (recur (next words) "" (into out (hard-wrap width word)))
          (recur (next words) word out)))

      :else
      (let [word (first words)
            candidate (str line " " word)]
        (if (<= (count candidate) width)
          (recur (next words) candidate out)
          (if (> (count word) width)
            (recur (next words) "" (into (conj out line) (hard-wrap width word)))
            (recur (next words) word (conj out line))))))))

(defn visual-lines
  "Return terminal visual lines for `s` at `width`.

   Wraps on word boundaries where possible. Tokens longer than `width`
   fall back to hard wrapping so every rendered row fits the terminal."
  [width s]
  (->> (string/split (or s "") #"\n" -1)
       (mapcat (fn [line]
                 (if (string/blank? line)
                   [""]
                   (wrap-words width line))))
       vec))

(defn visual-line-count
  [width s]
  (max 1 (count (visual-lines width s))))

;; ─────────────────────────────────────────────────────────────────────
;; Styled runs — whitespace-faithful word wrap over [{:text s & style}]
;; ─────────────────────────────────────────────────────────────────────
;;
;; A "run" is `{:text s}` plus any of the sparse style keys below. The
;; functions here wrap a vector of runs as ONE paragraph: words may span
;; run (style) boundaries and are never split at a style boundary; all
;; whitespace that fits is preserved verbatim (indentation matters);
;; whitespace at a wrap point is consumed. This is generic typography —
;; nothing here knows what produced the runs.

(def style-keys
  "The per-run style keys carried through wrapping."
  [:fg :bg :bold? :italic? :underline? :dim?])

(defn- runs->chars
  "Project runs to a flat vector of `{:char c :style sparse-style-map}`."
  [runs]
  (into []
        (mapcat (fn [run]
                  (let [style (select-keys run style-keys)]
                    (map (fn [c] {:char c :style style}) (:text run)))))
        runs))

(defn- chars->runs
  "Re-merge adjacent equal-style chars into `{:text s & style}` maps.
   Sparse: absent style keys stay absent. Empty input → one empty run."
  [chars]
  (if (empty? chars)
    [{:text ""}]
    (->> chars
         (partition-by :style)
         (mapv (fn [grp]
                 (merge {:text (apply str (map :char grp))}
                        (:style (first grp))))))))

(defn- logical-lines
  "Split a styled-char seq on newline chars with `(split s #\"\\n\" -1)`
   semantics: a trailing newline yields a trailing empty logical line;
   empty input yields one empty logical line."
  [chars]
  (loop [cs (seq chars) cur [] out []]
    (if (nil? cs)
      (conj out cur)
      (let [{:keys [char]} (first cs)]
        (if (= \newline char)
          (recur (next cs) [] (conj out cur))
          (recur (next cs) (conj cur (first cs)) out))))))

(defn- whitespace-styled-char? [{:keys [char]}]
  (Character/isWhitespace ^char char))

(defn- tokenize
  "Split a logical line of styled chars into maximal spans of whitespace
   and non-whitespace (words). Word spans cross run/style boundaries."
  [line-chars]
  (->> line-chars
       (partition-by whitespace-styled-char?)
       (mapv (fn [span]
               {:ws?   (whitespace-styled-char? (first span))
                :chars (vec span)}))))

(defn- hard-wrap-chunks
  "Chop `chars` into closed visual lines: the first chunk takes
   `first-width` columns (the still-open first visual line), subsequent
   chunks take `cont-width`. Width <= 0 degenerates to a single empty
   line per token, mirroring `hard-wrap`'s `[\"\"]` for width 0."
  [first? first-width cont-width chars]
  (loop [cs (seq chars) first? first? out []]
    (if (nil? cs)
      out
      (let [w (if first? first-width cont-width)]
        (if (pos? w)
          (recur (seq (drop w cs)) false (conj out (vec (take w cs))))
          [[]])))))

(defn- wrap-logical-line
  "Greedy whitespace-faithful wrap of one logical line (a vector of
   alternating ws/word spans). Returns a vector of visual lines, each a
   vector of styled chars. `first-width` applies to the first visual
   line, `cont-width` to the rest (they differ when a continuation
   prefix is in play)."
  [first-width cont-width spans]
  (loop [spans   (seq spans)
         line    []      ; committed chars on the open line
         pending nil     ; held inter-word whitespace (vector of chars)
         first?  true    ; open line is the logical line's first visual line
         out     []]
    (let [w (if first? first-width cont-width)]
      (if (nil? spans)
        ;; End of logical line: trailing whitespace is committed when it
        ;; fits, consumed otherwise (end-of-line acts as a wrap point).
        (let [line (if (and (seq pending)
                            (<= (+ (count line) (count pending)) w))
                     (into line pending)
                     line)]
          (if (or (seq line) (empty? out))
            (conj out line)
            out))
        (let [{:keys [ws? chars]} (first spans)]
          (cond
            ;; Whitespace at the start of a continuation line was consumed
            ;; by the wrap; never carry it.
            (and ws? (empty? line) (not first?))
            (recur (next spans) line nil first? out)

            ;; Leading whitespace of the logical line: preserved verbatim
            ;; (indentation matters). Oversize indents hard-chop.
            (and ws? (empty? line) first?)
            (if (<= (count chars) w)
              (recur (next spans) (vec chars) nil first? out)
              (recur (next spans) [] nil false
                     (into out (hard-wrap-chunks true first-width cont-width chars))))

            ;; Internal whitespace: hold; commit only if the following
            ;; word also fits (so a wrap point consumes it).
            ws?
            (recur (next spans) line (vec chars) first? out)

            ;; Word that fits on the open line (with any held whitespace).
            (<= (+ (count line) (count pending) (count chars)) w)
            (recur (next spans) (-> line (into pending) (into chars)) nil first? out)

            ;; Word that fits on a fresh continuation line: close the open
            ;; line (dropping held whitespace — consumed at the wrap point).
            (and (seq line) (<= (count chars) cont-width))
            (recur (next spans) (vec chars) nil false (conj out line))

            ;; Oversize token: flush the open line, hard-chop the token
            ;; into closed full-width lines, continue on a fresh line.
            :else
            (let [out    (if (seq line) (conj out line) out)
                  first? (and first? (empty? line))]
              (recur (next spans) [] nil false
                     (into out (hard-wrap-chunks first? first-width cont-width chars))))))))))

(defn- normalize-continuation
  "Continuation may be a string or a runs vector. Returns its styled
   chars, or nil when absent/empty."
  [continuation]
  (let [runs (cond
               (nil? continuation)    nil
               (string? continuation) [{:text continuation}]
               :else                  continuation)
        chars (when runs (runs->chars runs))]
    (when (seq chars) chars)))

(defn visual-lines-runs
  "Whitespace-faithful greedy word wrap over styled `runs`
   (`[{:text s, :fg/:bg/:bold?/:italic?/:underline?/:dim? ...} ...]`).

   Returns a vector of visual lines, each a vector of run maps (adjacent
   equal-style chars re-merged; absent style keys omitted). Semantics:

     - logical lines split on \\n across the concatenated runs
       (`(split s #\"\\n\" -1)` semantics — a run's text may contain \\n);
       a blank logical line yields a line of one empty-text run
     - leading whitespace of a logical line is preserved; internal
       whitespace is preserved verbatim when it fits
     - whitespace at a wrap point is consumed; continuation lines never
       start with carried whitespace
     - a word is a maximal non-whitespace span ACROSS run boundaries —
       wrapping never happens at a style boundary mid-word
     - tokens longer than the available width hard-chop into closed
       full-width lines (matching `wrap-words`)
     - `continuation` (string or runs) is prepended to every visual line
       after the first of each logical line; its visible length reduces
       the wrap width for those lines; ignored when its length >= width"
  ([width runs] (visual-lines-runs width runs nil))
  ([width runs continuation]
   (let [chars      (runs->chars (or runs []))
         cont-chars (normalize-continuation continuation)
         use-cont?  (and cont-chars (< (count cont-chars) width))
         cont-width (if use-cont? (- width (count cont-chars)) width)]
     (->> (logical-lines chars)
          (mapcat (fn [line-chars]
                    (->> (wrap-logical-line width cont-width (tokenize line-chars))
                         (map-indexed (fn [i vline]
                                        (chars->runs
                                          (if (and use-cont? (pos? i))
                                            (into (vec cont-chars) vline)
                                            vline)))))))
          vec))))

(defn visual-line-count-runs
  ([width runs] (visual-line-count-runs width runs nil))
  ([width runs continuation]
   (max 1 (count (visual-lines-runs width runs continuation)))))
