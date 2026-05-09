(ns ai.obney.grain.tui-adapter.layout
  "Hiccup → CellGrid resolution.

   The dispatcher walks a hiccup tree, looks each tag up in the element
   registry, validates attrs against the registered Malli schema, calls
   `:render` with an assigned bounding box (leaf signature) or with
   `(attrs children box render-child-fn)` (container signature, when
   `:container? true`), and clips the result to the box.

   Container elements like `:row`/`:col`/`:weighted` allocate width/height
   to children using a three-pass algorithm:
     1. Sum fixed widths (children whose `:preferred-size` reports a non-zero
        width when in a row, or non-zero height when in a column).
     2. Distribute remaining axis to weighted children (default weight 1;
        explicit `:weighted {:weights [..]}` overrides).
     3. Each child renders into its allocated sub-box. Children below their
        `:min-size` get a truncation glyph in their slot.

   This namespace knows nothing about transports, sessions, diffing, or
   ANSI emission."
  (:require [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [com.brunobonacci.mulog :as u]))

;; ─────────────────────────────────────────────────────────────────────
;; Hiccup normalization
;; ─────────────────────────────────────────────────────────────────────

(defn- string-node? [n] (string? n))

(defn normalize-node
  "Promote a bare string to `[:text \"...\"]`. Split a hiccup vector into
   `{:tag :attrs :children}`. The attrs map is optional — `[:foo \"x\"]`
   yields `{:tag :foo :attrs {} :children [\"x\"]}`. Sequences in the body
   are flattened (one level), matching Reagent/hiccup convention."
  [node]
  (cond
    (string-node? node)
    {:tag :text :attrs {:text node} :children []}

    (and (vector? node) (keyword? (first node)))
    (let [[tag & rst] node
          [attrs children] (if (map? (first rst))
                             [(first rst) (rest rst)]
                             [{} rst])
          ;; flatten one level of sequence content (lazy seq, list, vector seq)
          children (vec (mapcat (fn [c]
                                  (if (and (seq? c) (not (vector? c)))
                                    c
                                    [c]))
                                children))]
      {:tag tag :attrs attrs :children children})

    :else
    (throw (ex-info "Unrecognized hiccup node" {:node node}))))

;; ─────────────────────────────────────────────────────────────────────
;; Box helpers
;; ─────────────────────────────────────────────────────────────────────

(defn- under-min?
  "True when `box` is smaller than `min-size` in either dimension."
  [{:keys [width height]} {min-w :width min-h :height}]
  (or (< width  min-w)
      (< height min-h)))

(defn- empty-box? [{:keys [width height]}]
  (or (zero? width) (zero? height)))

;; ─────────────────────────────────────────────────────────────────────
;; Allocator — distribute a 1D axis across children
;; ─────────────────────────────────────────────────────────────────────

(defn allocate-1d
  "Distribute `total` units across `n` children given:
     - `fixed-sizes`  a vector of the fixed size each child wants (0 if flexible).
     - `weights`      a vector of the weight for each child.
   Returns a vector of allocated sizes (same length as children).

   Algorithm:
     1. Sum fixed sizes. If sum >= total, fixed children get their requested
        sizes (clipped to the remaining budget in order); flexible children get 0.
     2. Otherwise, distribute `total - sum-fixed` to flexible children in
        proportion to `weights`, with rounding accumulating so the total
        matches exactly."
  [total fixed-sizes weights]
  (let [n            (count fixed-sizes)
        sum-fixed    (reduce + fixed-sizes)
        flex-indices (vec (keep-indexed (fn [i fx] (when (zero? fx) i)) fixed-sizes))
        flex-weights (mapv weights flex-indices)
        sum-w        (reduce + flex-weights)]
    (cond
      ;; No room for anything
      (zero? total)
      (vec (repeat n 0))

      ;; Fixed alone exhausts (or overflows) the budget — flex children get 0,
      ;; fixed children get clipped left-to-right.
      (>= sum-fixed total)
      (persistent!
        (loop [out    (transient (vec (repeat n 0)))
               i      0
               budget total]
          (cond
            (or (>= i n) (zero? budget))
            out

            :else
            (let [fx (nth fixed-sizes i)]
              (if (pos? fx)
                (let [give (min fx budget)]
                  (recur (assoc! out i give) (inc i) (- budget give)))
                (recur out (inc i) budget))))))

      ;; No flex children — fixed take their requested sizes, remainder unused.
      (zero? sum-w)
      (vec fixed-sizes)

      ;; General case — fixed get their share, flex split the remainder by weight.
      :else
      (let [remaining   (- total sum-fixed)
            base-allocs (mapv (fn [w] (quot (* w remaining) sum-w)) flex-weights)
            allocated   (reduce + base-allocs)
            leftover    (- remaining allocated)
            ;; Distribute leftover one cell at a time to the highest-weight slots
            ;; (ties broken by index order).
            order       (->> (map-indexed (fn [i w] [w i]) flex-weights)
                             (sort-by (fn [[w i]] [(- w) i]))
                             (mapv second))
            adjusted    (loop [allocs base-allocs left leftover ord order]
                          (if (or (zero? left) (empty? ord))
                            allocs
                            (recur (update allocs (first ord) inc)
                                   (dec left)
                                   (rest ord))))]
        ;; Splice flex allocs back into a full vector aligned with original indices.
        (loop [out (vec fixed-sizes) i 0]
          (if (>= i (count flex-indices))
            out
            (recur (assoc out (nth flex-indices i) (nth adjusted i))
                   (inc i))))))))

(defn -weights-or-defaults
  "Return the weights vector for the children of a `:row`/`:col`. If `attrs`
   has `:weights [w1 w2 ...]`, use that (padding/truncating to child count).
   Otherwise: weight 0 for any child whose `fixed-sizes` slot is non-zero
   (i.e. fixed-size children don't compete for the flex pool), and weight 1
   for every other child. Public so built-in containers in `builtins.clj`
   can reuse it; the leading `-` indicates a substrate-internal helper."
  [attrs n fixed-sizes]
  (let [explicit (:weights attrs)]
    (if (vector? explicit)
      (mapv (fn [i] (or (get explicit i) 1)) (range n))
      (mapv (fn [fx] (if (zero? fx) 1 0)) fixed-sizes))))

;; ─────────────────────────────────────────────────────────────────────
;; Render dispatch
;; ─────────────────────────────────────────────────────────────────────

(declare render-element)

(defn- render-leaf
  "Resolve a leaf element. Validates attrs, checks min-size, calls
   `:render` with `(attrs box)`, clips the result to the box."
  [{:keys [render attrs-validator min-size] :as _entry} attrs box]
  (when-not (attrs-validator attrs)
    (throw (ex-info "Element attrs failed validation"
                    {:attrs attrs :box box})))
  (cond
    (empty-box? box)
    (cells/blank (:width box) (:height box))

    (under-min? box min-size)
    (cells/truncation-glyph box)

    :else
    (let [grid (render attrs box)]
      (cells/clip grid box))))

(defn- render-container
  "Resolve a container element. The container's `:render` receives
   `(attrs children box render-child-fn)` — the substrate's recursive
   dispatcher is passed as the last argument so the container can
   render its children into sub-boxes of its choosing."
  [{:keys [render attrs-validator min-size] :as _entry} attrs children box]
  (when-not (attrs-validator attrs)
    (throw (ex-info "Element attrs failed validation"
                    {:attrs attrs :box box})))
  (cond
    (empty-box? box)
    (cells/blank (:width box) (:height box))

    (under-min? box min-size)
    (cells/truncation-glyph box)

    :else
    (let [grid (render attrs children box render-element)]
      (cells/clip grid box))))

(defn render-element
  "Resolve `node` against the element registry, allocate it `box`, return
   a CellGrid clipped to `box`. Throws when `node`'s tag is not registered
   or when its attrs fail validation."
  [node box]
  (let [{:keys [tag attrs children]} (normalize-node node)
        entry                        (er/lookup tag)]
    (when-not entry
      (throw (ex-info "Unknown element"
                      {:tag tag :registered (er/list-elements)})))
    (if (:container? entry)
      (render-container entry attrs children box)
      (render-leaf entry attrs box))))

;; ─────────────────────────────────────────────────────────────────────
;; Helpers exposed for built-in containers
;; ─────────────────────────────────────────────────────────────────────

(defn child-preferred-size
  "Return the `:preferred-size` of a child node by consulting its registered
   element. Returns `{:width 0 :height 0}` when the child is unrecognised
   or has no preference (e.g. weighted children).

   The element's `:preferred-size` function receives `attrs` (per the spec
   §7.6.2 contract). For positional-arg shorthands like `[:gap 2]`, the
   substrate exposes the children to `:preferred-size` via a 2-arg arity
   when the function supports it; legacy 1-arg fns continue to work."
  [child]
  (try
    (let [{:keys [tag attrs children]} (normalize-node child)
          entry                         (er/lookup tag)]
      (if entry
        (let [pf (:preferred-size entry)]
          (try
            (pf attrs children)
            (catch clojure.lang.ArityException _
              (pf attrs))))
        {:width 0 :height 0}))
    (catch Exception _
      {:width 0 :height 0})))
