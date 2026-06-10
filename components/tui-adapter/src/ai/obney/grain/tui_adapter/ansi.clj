(ns ai.obney.grain.tui-adapter.ansi
  "ANSI byte emission for diff runs.

   Threads `current-style` across runs so consecutive same-style cells emit
   no SGR. Color downgrade (§13.4) lives here, keyed off `:terminal-caps`
   (`{:color :truecolor|:c256|:c16|:mono :alt-screen? bool ...}`).

   The diff layer is capability-ignorant; this layer is the single
   authority on byte emission."
  (:require [clojure.string :as str]))

(def ^String CSI "[")

;; ─────────────────────────────────────────────────────────────────────
;; Cursor positioning
;; ─────────────────────────────────────────────────────────────────────

(defn cursor-position
  "ANSI escape that moves the cursor to (`row`, `col`) — both 0-indexed
   on the input, but emitted as 1-indexed CSI parameters."
  [row col]
  (str CSI (inc row) ";" (inc col) "H"))

(defn save-cursor    [] (str CSI "s"))
(defn restore-cursor [] (str CSI "u"))
(defn enter-alt-screen [] (str CSI "?1049h"))
(defn leave-alt-screen [] (str CSI "?1049l"))
(defn hide-cursor [] (str CSI "?25l"))
(defn show-cursor [] (str CSI "?25h"))

;; DECSCUSR — cursor shape. 0/1 = default, 2 = steady block, 3 = blinking
;; underline, 4 = steady underline, 5 = blinking bar, 6 = steady bar.
(defn cursor-style-block         [] (str CSI "2 q"))
(defn cursor-style-bar           [] (str CSI "6 q"))
(defn cursor-style-default-reset [] (str CSI "0 q"))

;; DECSTBM — set top and bottom margins (scroll region). Both 1-indexed
;; per the spec. `\n` at the bottom of the region scrolls only within
;; the region; rows below the region stay pinned. Used to keep the
;; sticky input chrome fixed at the bottom while new stream segments
;; scroll above.
(defn set-scroll-region
  "Set the terminal scroll region to rows `top..bottom` (both 0-indexed
   here; emitted 1-indexed per the CSI convention)."
  [top bottom]
  (str CSI (inc top) ";" (inc bottom) "r"))

(defn reset-scroll-region
  "Reset the scroll region to the full screen."
  []
  (str CSI "r"))

;; SD — scroll down within the scroll region (CSI Ps T): content moves
;; down, blank rows appear at the region top, and rows pushed past the
;; region bottom are discarded. Used to let the transcript reclaim a
;; reserved overlay lane without leaving a blank band behind.
(defn scroll-down
  "Scroll the current scroll region down by `n` rows."
  [n]
  (str CSI n "T"))

;; Line erase. `CSI K` (= `CSI 0 K`) clears from the cursor to the end
;; of the line. Used when redrawing a row in place.
(defn erase-line-to-eol [] (str CSI "K"))
(defn erase-line        [] (str CSI "2K"))
(defn reset-style [] (str CSI "0m"))
(defn clear-screen [] (str CSI "2J"))

;; ─────────────────────────────────────────────────────────────────────
;; Color quantization
;; ─────────────────────────────────────────────────────────────────────

(def ^:private named-color-codes
  ;; Standard 16-color ANSI palette → SGR foreground base codes.
  ;; Background is +10. Bright colors are +60 (fg) / +70 (bg).
  {:black          30 :red            31 :green          32 :yellow         33
   :blue           34 :magenta        35 :cyan           36 :white          37
   :bright-black   90 :bright-red     91 :bright-green   92 :bright-yellow  93
   :bright-blue    94 :bright-magenta 95 :bright-cyan    96 :bright-white   97
   :default        39})

;; xterm 256-color named-color → 256 cube index for downgrade.
(def ^:private named-color-256
  {:black 0 :red 1 :green 2 :yellow 3 :blue 4 :magenta 5 :cyan 6 :white 7
   :bright-black 8 :bright-red 9 :bright-green 10 :bright-yellow 11
   :bright-blue 12 :bright-magenta 13 :bright-cyan 14 :bright-white 15
   :default nil})

(defn- rgb->256
  "Quantize an `[r g b]` triple (0-255) into the xterm 256-color cube
   (16-231) plus optional grayscale ramp (232-255). Picks the nearest
   palette entry by simple Euclidean distance in the 6-cube."
  [r g b]
  (letfn [(level [v]
            (cond (< v 48)  0
                  (< v 115) 1
                  :else     (int (/ (+ v 35) 40))))]
    (let [r-i (level r)
          g-i (level g)
          b-i (level b)]
      (+ 16 (* 36 r-i) (* 6 g-i) b-i))))

(defn- rgb->16
  "Quantize an `[r g b]` triple to the nearest of the 16 ANSI base colors.
   Brutally simple: thresholds at 96 to choose dim vs bright."
  [r g b]
  (let [bright? (or (>= r 192) (>= g 192) (>= b 192))
        offset  (if bright? 8 0)]
    (cond
      (and (< r 64) (< g 64) (< b 64))     (+ offset 0)   ; black
      (and (>= r 128) (< g 128) (< b 128)) (+ offset 1)   ; red
      (and (< r 128) (>= g 128) (< b 128)) (+ offset 2)   ; green
      (and (>= r 128) (>= g 128) (< b 128)) (+ offset 3)  ; yellow
      (and (< r 128) (< g 128) (>= b 128)) (+ offset 4)   ; blue
      (and (>= r 128) (< g 128) (>= b 128)) (+ offset 5)  ; magenta
      (and (< r 128) (>= g 128) (>= b 128)) (+ offset 6)  ; cyan
      :else                                  (+ offset 7))))

(defn- color-sgr
  "SGR parameters for a color value (`:fg` or `:bg`) given the negotiated
   capability. Returns `nil` when the color is `:default` and no override
   is needed (the caller will emit the corresponding `39`/`49` reset)."
  [color caps base bright-base]
  ;; Conservative default: if caps carry no :color, assume 256 (widely
  ;; supported, no truecolor flood) rather than optimistic truecolor.
  (let [depth (:color caps :c256)]
    (cond
      (= color :default)
      ;; SGR 39 (fg-default) when base=38; SGR 49 (bg-default) when base=48.
      [(+ base 1)]

      (= depth :mono)
      nil

      (and (vector? color) (= 4 (count color)) (= :rgb (first color)))
      (let [[_ r g b] color]
        (case depth
          :truecolor [base 2 r g b]
          :c256      [base 5 (rgb->256 r g b)]
          :c16       [(+ (case base 38 30 48 40) (rgb->16 r g b))]))

      (keyword? color)
      (case depth
        :truecolor (let [code (named-color-codes color (named-color-codes :default))]
                     [(if (= base 48) (+ code 10) code)])
        :c256      [base 5 (or (named-color-256 color) 7)]
        :c16       (let [code (named-color-codes color (named-color-codes :default))]
                     [(if (= base 48) (+ code 10) code)]))

      :else
      nil)))

;; ─────────────────────────────────────────────────────────────────────
;; SGR delta computation
;; ─────────────────────────────────────────────────────────────────────

(def ^:private default-style
  {:fg :default :bg :default
   :bold? false :italic? false :underline? false :dim? false})

(defn sgr-for
  "Return the SGR escape sequence (or empty string) needed to transition
   the terminal from `current` style to `target` style under `caps`. Only
   the differing fields are emitted. Returns the escape string and the
   new `current` style, as a `[escape new-current]` pair."
  [current target caps]
  (let [current     (merge default-style current)
        target      (merge default-style target)
        params      (transient [])
        ;; If any boolean style needs to FLIP OFF (e.g. bold true → false),
        ;; emit a full reset and re-emit everything that's true. This is
        ;; the simplest correct approach — terminals have no "bold off"
        ;; that doesn't also affect dim.
        needs-reset? (or (and (:bold?      current) (not (:bold?      target)))
                         (and (:italic?    current) (not (:italic?    target)))
                         (and (:underline? current) (not (:underline? target)))
                         (and (:dim?       current) (not (:dim?       target))))
        effective-current (if needs-reset? default-style current)
        _ (when needs-reset? (conj! params 0))
        ;; Booleans
        _ (when (and (not (:bold?      effective-current)) (:bold?      target)) (conj! params 1))
        _ (when (and (not (:dim?       effective-current)) (:dim?       target)) (conj! params 2))
        _ (when (and (not (:italic?    effective-current)) (:italic?    target)) (conj! params 3))
        _ (when (and (not (:underline? effective-current)) (:underline? target)) (conj! params 4))
        ;; Colors
        _ (when (not= (:fg effective-current) (:fg target))
            (when-let [ps (color-sgr (:fg target) caps 38 38)]
              (doseq [p ps] (conj! params p))))
        _ (when (not= (:bg effective-current) (:bg target))
            (when-let [ps (color-sgr (:bg target) caps 48 48)]
              (doseq [p ps] (conj! params p))))
        ps (persistent! params)]
    (if (empty? ps)
      ["" target]
      [(str CSI (str/join ";" ps) "m") target])))

;; ─────────────────────────────────────────────────────────────────────
;; Emit a sequence of runs
;; ─────────────────────────────────────────────────────────────────────

(defn- cell-style [c]
  (select-keys c [:fg :bg :bold? :italic? :underline? :dim?]))

(defn emit
  "Reduces a sequence of diff `runs` (each `{:row :col :cells [Cell ...]}`)
   into a single output string, threading `current-style` across runs and
   issuing minimum-byte SGR sequences. Returns `[output-string new-style]`.

   `caps` is the terminal capability map; `current-style` is the style
   the terminal is believed to be in before this batch (carried across
   frames by the session)."
  [runs caps current-style]
  (let [sb (StringBuilder.)]
    (loop [rs    runs
           style (or current-style default-style)]
      (if (empty? rs)
        [(.toString sb) style]
        (let [{:keys [row col cells]} (first rs)
              _ (.append sb (cursor-position row col))
              [final-style] (loop [cs cells
                                   st style]
                              (if (empty? cs)
                                [st]
                                (let [c        (first cs)
                                      target   (cell-style c)
                                      [esc nx] (sgr-for st target caps)]
                                  (when (seq esc) (.append sb esc))
                                  (.append sb (str (:char c " ")))
                                  (recur (rest cs) nx))))]
          (recur (rest rs) final-style))))))

(defn emit-cells
  "Emit a sequence of cells as ANSI styled text *without* any cursor
   positioning. Used by the main-buffer append renderer where the
   terminal cursor is already where we want to write. Threads
   `current-style` across the cells and returns `[bytes new-style]`."
  [cells caps current-style]
  (let [sb (StringBuilder.)]
    (loop [cs (seq cells)
           st (or current-style default-style)]
      (if (empty? cs)
        [(.toString sb) st]
        (let [c        (first cs)
              target   (cell-style c)
              [esc nx] (sgr-for st target caps)]
          (when (seq esc) (.append sb esc))
          (.append sb (str (:char c " ")))
          (recur (rest cs) nx))))))
