(ns ai.obney.grain.tui-adapter.cells
  "CellGrid primitives — the universal output type of all TUI rendering.

   A CellGrid is plain Clojure data:

       {:width  n
        :height n
        :cells  [[Cell ...]   ; row 0
                 [Cell ...]   ; row 1
                 ...]}

   A Cell is a map of :char (single grapheme; defaults to \" \"), :fg, :bg,
   :bold?, :italic?, :underline?, :dim?. Colors are :default, named keywords,
   or [:rgb r g b].

   This namespace holds the constructors and combinators specified in §7.6.1
   of the TUI adapter spec. All functions are pure data → pure data; no I/O,
   no side effects, no closures over mutable state.")

(def blank-cell
  "The default cell. Single space, default foreground/background, no styling."
  {:char       " "
   :fg         :default
   :bg         :default
   :bold?      false
   :italic?    false
   :underline? false
   :dim?       false})

(defn blank
  "A blank CellGrid of the given dimensions. Every cell is `blank-cell`.
   Width and height of 0 produce an empty grid (no rows, no cells)."
  [width height]
  {:width  width
   :height height
   :cells  (vec (repeat height (vec (repeat width blank-cell))))})

(defn text-row
  "A 1-row CellGrid of the given width displaying `s`. Truncates `s` if
   longer than width; right-pads with `blank-cell` if shorter. `style-attrs`
   is merged into every cell (`:fg`, `:bg`, `:bold?`, etc.).

   Width 0 produces an empty 0×1 grid."
  [width style-attrs s]
  (let [base    (merge blank-cell style-attrs)
        s       (or s "")
        chars   (vec (take width s))
        n-chars (count chars)
        padding (- width n-chars)
        row     (-> []
                    (into (map (fn [c] (assoc base :char (str c)))) chars)
                    (into (repeat padding base)))]
    {:width  width
     :height 1
     :cells  [row]}))

(defn runs-row
  "A 1-row CellGrid of the given width displaying styled `runs`
   (`[{:text s :fg ... :bold? ...} ...]`). Each cell takes its run's
   style merged over `blank-cell`. Truncates past `width`; right-pads
   with `blank-cell` if shorter.

   Width 0 produces an empty 0×1 grid."
  [width runs]
  (let [cells   (into []
                      (comp (mapcat (fn [run]
                                      (let [base (merge blank-cell
                                                        (select-keys run [:fg :bg :bold? :italic? :underline? :dim?]))]
                                        (map (fn [c] (assoc base :char (str c)))
                                             (:text run)))))
                            (take width))
                      runs)
        padding (- width (count cells))
        row     (into cells (repeat padding blank-cell))]
    {:width  width
     :height 1
     :cells  [row]}))

(defn- pad-row
  "Right-pad a single row vector to `target-width` with `blank-cell`."
  [row target-width]
  (let [n (count row)]
    (if (>= n target-width)
      (vec (take target-width row))
      (into row (repeat (- target-width n) blank-cell)))))

(defn- pad-rows-to-height
  "Bottom-pad `cells` to `target-height` with rows of `blank-cell` of
   `width` columns."
  [cells width target-height]
  (let [n (count cells)]
    (if (>= n target-height)
      (vec (take target-height cells))
      (into cells (repeat (- target-height n) (vec (repeat width blank-cell)))))))

(defn stack
  "Vertical concatenation of CellGrids. Result width is the maximum of
   the inputs' widths; narrower grids are right-padded with `blank-cell`.
   Zero arguments returns a 0×0 blank grid."
  [& grids]
  (if (empty? grids)
    (blank 0 0)
    (let [w    (apply max 0 (map :width grids))
          rows (vec (mapcat (fn [g]
                              (mapv #(pad-row % w) (:cells g)))
                            grids))]
      {:width  w
       :height (count rows)
       :cells  rows})))

(defn beside
  "Horizontal concatenation of CellGrids. Result height is the maximum of
   the inputs' heights; shorter grids are bottom-padded with rows of
   `blank-cell`. Zero arguments returns a 0×0 blank grid."
  [& grids]
  (if (empty? grids)
    (blank 0 0)
    (let [h        (apply max 0 (map :height grids))
          padded   (map (fn [g]
                          (update g :cells pad-rows-to-height (:width g) h))
                        grids)
          rows     (if (zero? h)
                     []
                     (apply mapv
                            (fn [& row-parts] (vec (apply concat row-parts)))
                            (map :cells padded)))]
      {:width  (reduce + (map :width padded))
       :height h
       :cells  (vec rows)})))

(defn overlay
  "Place `over` onto `base` with its top-left at (`x`, `y`). Cells in
   `over` that fall outside `base`'s bounds are clipped silently.
   Returns a new grid with `base`'s dimensions."
  [base over x y]
  (let [{bw :width bh :height base-cells :cells} base
        {ow :width oh :height over-cells :cells} over]
    (if (or (>= x bw) (>= y bh)
            (<= (+ x ow) 0) (<= (+ y oh) 0))
      base
      (let [new-cells
            (vec
              (map-indexed
                (fn [row-idx base-row]
                  (if (or (< row-idx y) (>= row-idx (+ y oh)))
                    base-row
                    (let [over-row (get over-cells (- row-idx y))]
                      (vec
                        (map-indexed
                          (fn [col-idx base-cell]
                            (if (or (< col-idx x) (>= col-idx (+ x ow)))
                              base-cell
                              (get over-row (- col-idx x) base-cell)))
                          base-row)))))
                base-cells))]
        {:width bw :height bh :cells new-cells}))))

(defn with-style
  "Merge `style-attrs` into every cell of `grid`. The overlay style wins
   over the base cell's existing style for the keys present in `style-attrs`."
  [grid style-attrs]
  (update grid :cells
          (fn [rows]
            (mapv (fn [row]
                    (mapv #(merge % style-attrs) row))
                  rows))))

(defn clip
  "Clip `grid` to fit within a `{:width w :height h}` box. If `grid` is
   already within bounds, returns it unchanged. If oversize in either
   dimension, truncates to the box from the top-left corner."
  [grid {:keys [width height]}]
  (let [{gw :width gh :height cells :cells} grid]
    (if (and (<= gw width) (<= gh height))
      grid
      (let [new-h    (min gh height)
            new-w    (min gw width)
            clipped  (->> cells
                          (take new-h)
                          (mapv (fn [row] (vec (take new-w row)))))]
        {:width  new-w
         :height new-h
         :cells  clipped}))))

(defn truncation-glyph
  "Produce a substitute CellGrid for content that doesn't fit its assigned
   `box`. Per §11.3, uses `…` for single-row underfit and a stack of `▼`
   markers for multi-row underfit. Always returns a grid that fits within
   `box` (clamping to box dimensions if smaller)."
  [{:keys [width height]}]
  (cond
    (or (zero? width) (zero? height))
    (blank width height)

    (= 1 height)
    (text-row width {:fg :default :dim? true} "…")

    :else
    (let [glyph-row (text-row width {:fg :default :dim? true} "▼")]
      (apply stack (repeat height glyph-row)))))
