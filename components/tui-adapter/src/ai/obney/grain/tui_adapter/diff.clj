(ns ai.obney.grain.tui-adapter.diff
  "Row-major run-length diff between two CellGrids.

   Result is a sequence of `{:row r :col c :cells [Cell ...]}` runs — one
   per maximal contiguous span of cells in `next` that differs from `prev`.

   Special cases:
   - `prev` is nil (first frame): every row of `next` is a single full-row run.
   - `prev` and `next` have different shapes (resize): defensive full-frame
     redraw — every row of `next` is a full-row run.
   - `prev` equals `next`: empty sequence.

   The diff is the input to `ansi/emit`. The diff layer is capability-
   ignorant; color/style downgrade happens at the emit layer."
  )

(defn- row-diff
  "Diff a single row of cells. Returns a vec of runs at the given row index.
   Each run is `{:row r :col c :cells [Cell ...]}`."
  [row-idx prev-row next-row]
  (let [n (count next-row)]
    (loop [i        0
           run-col  nil
           run-buf  []
           runs     (transient [])]
      (cond
        (= i n)
        (persistent!
          (if (seq run-buf)
            (conj! runs {:row row-idx :col run-col :cells run-buf})
            runs))

        :else
        (let [pc (when prev-row (nth prev-row i nil))
              nc (nth next-row i)]
          (if (= pc nc)
            ;; Cell unchanged — close any open run.
            (recur (inc i)
                   nil
                   []
                   (if (seq run-buf)
                     (conj! runs {:row row-idx :col run-col :cells run-buf})
                     runs))
            ;; Cell changed — extend (or start) a run.
            (if (some? run-col)
              (recur (inc i) run-col (conj run-buf nc) runs)
              (recur (inc i) i [nc] runs))))))))

(defn diff
  "Diff `prev` against `next`. Both are CellGrids (or `prev` is nil for the
   first frame). Returns a lazy seq of `{:row r :col c :cells [Cell ...]}`
   runs covering only the changed cells of `next`."
  [prev next]
  (let [{nw :width nh :height ncells :cells} next
        same-shape? (and prev
                         (= nw (:width prev))
                         (= nh (:height prev)))
        prev-cells  (when same-shape? (:cells prev))]
    (cond
      (zero? nh)
      []

      ;; First frame OR shape mismatch — emit every row as one full run.
      (not same-shape?)
      (vec (map-indexed (fn [r row]
                          {:row r :col 0 :cells row})
                        ncells))

      ;; Shapes match — per-row run-length diff.
      :else
      (vec
        (mapcat (fn [r]
                  (row-diff r (nth prev-cells r) (nth ncells r)))
                (range nh))))))
