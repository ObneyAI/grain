(ns ai.obney.grain.tui-adapter.builtins
  "Built-in hiccup elements §7.1–§7.5 registered as `defelement`s.

   Per §7.6.6 (honesty requirement), built-ins go through the same
   registry as application registrations — there is no privileged path.
   This namespace is required by the Integrant component so first start
   guarantees registration. Top-level forms are wrapped in a defonce
   block so REPL reloads are idempotent."
  (:require [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [ai.obney.grain.tui-adapter.input-slot :as input-slot]
            [ai.obney.grain.tui-adapter.layout :as layout]
            [ai.obney.grain.tui-adapter.text-wrap :as text-wrap]
            [clojure.string :as string]))

;; ─────────────────────────────────────────────────────────────────────
;; Helpers — string-aware preferred-size, character helpers
;; ─────────────────────────────────────────────────────────────────────

(defn- text-attrs->styles
  "Extract the style map from a :text attrs map."
  [attrs]
  (select-keys attrs [:fg :bg :bold? :italic? :underline? :dim?]))

(defn- styled-text-row
  "Convenience: build a single-row CellGrid with `s` styled by `style-attrs`,
   padded/truncated to `width`."
  [width style-attrs s]
  (cells/text-row width style-attrs (or s "")))

(defn- text-visual-lines
  [width s]
  (text-wrap/visual-lines width s))

(defn- styled-text-grid
  [width height style-attrs s]
  (let [lines (text-visual-lines width s)
        rows (mapv #(styled-text-row width style-attrs %) lines)
        grid (if (seq rows)
               (apply cells/stack rows)
               (cells/blank width 0))]
    (cells/clip grid {:width width :height height})))

;; ─────────────────────────────────────────────────────────────────────
;; The registration block — defonce so reloading the namespace doesn't
;; spam mulog warnings. Production callers should require this ns from
;; the Integrant component to ensure registration happens once.
;; ─────────────────────────────────────────────────────────────────────

(defonce ^:private registered
  (do

    ;; ──────────────────────────────────────────────────────────────────
    ;; §7.1 Text and styling
    ;; ──────────────────────────────────────────────────────────────────

    (er/defelement :text
      {:doc   "Plain or styled text. Hiccup forms: [:text \"...\"] or [:text {:fg :red ...} \"...\"]. The text content lives in children; the :text attr is also accepted (used by the substrate's string-promotion path)."
       :attrs [:map
               [:text     {:optional true} :string]
               [:fg       {:optional true} :any]
               [:bg       {:optional true} :any]
               [:bold?    {:optional true} :boolean]
               [:italic?  {:optional true} :boolean]
               [:underline? {:optional true} :boolean]
               [:dim?     {:optional true} :boolean]]
       :container? true
       :preferred-size (fn [{:keys [text]} children]
                         (let [s (or text (apply str (filter string? children)))]
                           {:width (count (or s "")) :height 1}))
       :min-size       {:width 1 :height 1}
       :stream-stable? true
       :render
       (fn [attrs children {:keys [width height]} _render-child]
         (let [s (or (:text attrs)
                     (apply str (filter string? children)))]
           (styled-text-grid width (or height 1) (text-attrs->styles attrs) s)))})

    (er/defelement :line
      {:doc   "Horizontal rule across the bounding box."
       :attrs [:map
               [:fg {:optional true} :any]]
       :preferred-size (constantly {:width 0 :height 1})
       :min-size       {:width 1 :height 1}
       :stream-stable? true
       :render
       (fn [attrs {:keys [width]}]
         (styled-text-row width
                          (assoc (text-attrs->styles attrs) :dim? true)
                          (apply str (repeat width "─"))))})

    (er/defelement :gap
      {:doc   "n blank cells. Hiccup form: [:gap n]. n is positional (lives in children per hiccup convention)."
       :attrs [:map]
       :container? true
       :preferred-size (fn [_attrs children]
                         (let [n (or (first (filter number? children)) 0)]
                           {:width n :height n}))
       :min-size       {:width 0 :height 0}
       :stream-stable? true
       :render
       (fn [_attrs children {:keys [width height]} _render-child]
         (let [n (or (first (filter number? children)) 0)]
           (cells/blank (min width n) (min height n))))})

    ;; ──────────────────────────────────────────────────────────────────
    ;; §7.2 Layout — containers
    ;; ──────────────────────────────────────────────────────────────────

    (er/defelement :row
      {:doc        "Horizontal layout. Children share the row."
       :attrs      [:map [:weights {:optional true} [:vector :int]]]
       :container? true
       :preferred-size (constantly {:width 0 :height 0})
       :render
       (fn [attrs children {:keys [width height]} render-child]
         (let [n             (count children)
               explicit-w?   (vector? (:weights attrs))
               fixed         (if explicit-w?
                               (vec (repeat n 0))
                               (mapv (fn [c] (:width (layout/child-preferred-size c))) children))
               weights       (layout/-weights-or-defaults attrs n fixed)
               allocs        (layout/allocate-1d width fixed weights)
               sub-grids     (mapv (fn [c w] (render-child c {:width w :height height}))
                                   children allocs)
               composed      (if (seq sub-grids)
                               (apply cells/beside sub-grids)
                               (cells/blank width height))]
           composed))})

    (er/defelement :col
      {:doc        "Vertical layout. Children share the column."
       :attrs      [:map [:weights {:optional true} [:vector :int]]]
       :container? true
       :preferred-size (constantly {:width 0 :height 0})
       :render
       (fn [attrs children {:keys [width height]} render-child]
         (let [n            (count children)
               explicit-w?  (vector? (:weights attrs))
               fixed        (if explicit-w?
                              (vec (repeat n 0))
                              (mapv (fn [c] (:height (layout/child-preferred-size c))) children))
               weights      (layout/-weights-or-defaults attrs n fixed)
               allocs       (layout/allocate-1d height fixed weights)
               sub-grids    (mapv (fn [c h] (render-child c {:width width :height h}))
                                  children allocs)
               composed     (if (seq sub-grids)
                              (apply cells/stack sub-grids)
                              (cells/blank width height))]
           composed))})

    (er/defelement :weighted
      {:doc        "Proportional sizing. Same as :row by default; if the parent is :col, behaves as a column. The :weights vector controls each child's share."
       :attrs      [:map [:weights {:optional true} [:vector :int]]]
       :container? true
       :preferred-size (constantly {:width 0 :height 0})
       :render
       ;; In MVS, :weighted as a top-level container behaves like :row.
       ;; Inside a :row/:col, the parent reads :weights from attrs anyway.
       (fn [attrs children {:keys [width height]} render-child]
         (let [n         (count children)
               fixed     (vec (repeat n 0))
               weights   (layout/-weights-or-defaults attrs n fixed)
               allocs    (layout/allocate-1d width fixed weights)
               sub-grids (mapv (fn [c w] (render-child c {:width w :height height}))
                               children allocs)]
           (apply cells/beside sub-grids)))})

    (er/defelement :pad
      {:doc        "Padding. Attrs: {:t n :r n :b n :l n}."
       :attrs      [:map
                    [:t {:optional true} :int]
                    [:r {:optional true} :int]
                    [:b {:optional true} :int]
                    [:l {:optional true} :int]]
       :container? true
       :render
       (fn [{:keys [t r b l] :or {t 0 r 0 b 0 l 0}}
            children
            {:keys [width height]}
            render-child]
         (let [inner-w (max 0 (- width l r))
               inner-h (max 0 (- height t b))
               child   (first children)
               inner   (if child
                         (render-child child {:width inner-w :height inner-h})
                         (cells/blank inner-w inner-h))
               ;; Compose into a blank canvas at the right offset.
               base    (cells/blank width height)]
           (cells/overlay base inner l t)))})

    (er/defelement :box
      {:doc        "Bordered container. Attrs: {:title \"...\" :border? bool :width n :height n}."
       :attrs      [:map
                    [:title   {:optional true} :string]
                    [:border? {:optional true} :boolean]
                    [:width   {:optional true} :int]
                    [:height  {:optional true} :int]]
       :container? true
       :preferred-size (fn [{:keys [width height]}]
                         {:width  (or width 0) :height (or height 0)})
       :render
       (fn [{:keys [title border?] :or {border? true}}
            children
            {:keys [width height]}
            render-child]
         (let [bw       (if border? 1 0)
               inner-w  (max 0 (- width  (* 2 bw)))
               inner-h  (max 0 (- height (* 2 bw)))
               child    (first children)
               inner    (if child
                          (render-child child {:width inner-w :height inner-h})
                          (cells/blank inner-w inner-h))
               canvas   (cells/blank width height)
               canvas   (if (and border? (>= width 2) (>= height 2))
                          (let [horiz   (apply str (repeat (- width 2) "─"))
                                top-str (cond
                                          (and title (>= width (+ 4 (count title))))
                                          (let [pad (- width 2 (+ 2 (count title)))
                                                left-pad (quot pad 2)
                                                right-pad (- pad left-pad)]
                                            (str "┌"
                                                 (apply str (repeat left-pad "─"))
                                                 " " title " "
                                                 (apply str (repeat right-pad "─"))
                                                 "┐"))
                                          :else
                                          (str "┌" horiz "┐"))
                                bot-str (str "└" horiz "┘")
                                top     (cells/text-row width {:dim? true} top-str)
                                bottom  (cells/text-row width {:dim? true} bot-str)
                                vert    (cells/text-row 1 {:dim? true} "│")]
                            (as-> canvas c
                              (cells/overlay c top    0 0)
                              (cells/overlay c bottom 0 (dec height))
                              (reduce (fn [g y]
                                        (-> g
                                            (cells/overlay vert 0 y)
                                            (cells/overlay vert (dec width) y)))
                                      c
                                      (range 1 (dec height)))))
                          canvas)]
           (cells/overlay canvas inner bw bw)))})

    ;; ──────────────────────────────────────────────────────────────────
    ;; §7.3 Data display
    ;; ──────────────────────────────────────────────────────────────────

    (er/defelement :list
      {:doc   "Vertical list of pre-rendered hiccup items. Attrs:
              {:items [hiccup ...] :selected idx?}. Per spec §7.3 the
              substrate handles scrolling, selection indicator, and
              focus; items past the visible height are clipped from
              the bottom (use `:main` + `:stream` for the
              chat / transcript / log-tail pattern where new content
              should flow into terminal scrollback instead)."
       :attrs [:map
               [:items    [:sequential :any]]
               [:selected {:optional true} [:maybe :int]]]
       :container? true
       :preferred-size (fn [{:keys [items]} _children]
                         {:width 0 :height (count (or items []))})
       :render
       (fn [{:keys [items selected]} _children {:keys [width height]} render-child]
         (let [items     (vec items)
               n         (count items)
               sel-idx   (when (and selected (< -1 selected n)) selected)
               item-rows (vec
                           (map-indexed
                             (fn [i item]
                               (let [marker (if (= i sel-idx) "▶ " "  ")
                                     marker-w (count marker)
                                     prefix   (cells/text-row marker-w
                                                              (if (= i sel-idx)
                                                                {:bold? true}
                                                                {:dim? true})
                                                              marker)
                                     rendered (render-child item {:width  (max 0 (- width marker-w))
                                                                  :height 1})]
                                 (cells/beside prefix rendered)))
                             items))
               visible   (vec (take height item-rows))]
           (apply cells/stack
                  (concat visible
                          (repeat (max 0 (- height (count visible)))
                                  (cells/blank width 1))))))})

    (er/defelement :table
      {:doc   "Tabular display. Attrs: {:columns [{:key :title :width?}] :rows [{key value, ...}] :selected idx?}."
       :attrs [:map
               [:columns  [:sequential [:map
                                        [:key :keyword]
                                        [:title {:optional true} :string]
                                        [:width {:optional true} :int]]]]
               [:rows     [:sequential :map]]
               [:selected {:optional true} [:maybe :int]]]
       :render
       (fn [{:keys [columns rows selected]} {:keys [width height]}]
         (let [columns   (vec columns)
               rows      (vec rows)
               sel-idx   (when (and selected (< -1 selected (count rows))) selected)
               n-cols    (count columns)
               ;; Distribute width across columns: explicit :width first, rest equal.
               fixed     (mapv (fn [c] (or (:width c) 0)) columns)
               weights   (mapv (fn [w] (if (zero? w) 1 0)) fixed)
               col-w     (layout/allocate-1d width fixed weights)
               header    (apply cells/beside
                                (mapv (fn [c w]
                                        (cells/text-row w {:bold? true} (or (:title c) (name (:key c)))))
                                      columns col-w))
               row-grids (mapv
                           (fn [i r]
                             (let [styles (if (= i sel-idx) {:bold? true} {})]
                               (apply cells/beside
                                      (mapv (fn [c w]
                                              (cells/text-row w styles (str (get r (:key c) ""))))
                                            columns col-w))))
                           (range)
                           rows)
               body      (vec (take (max 0 (dec height)) row-grids))
               filler    (max 0 (- height 1 (count body)))]
           (apply cells/stack
                  (concat [header] body (repeat filler (cells/blank width 1))))))})

    (er/defelement :scroll
      {:doc   "Scrollable region. Attrs: {:height n :offset n}."
       :attrs [:map
               [:height {:optional true} :int]
               [:offset {:optional true} :int]]
       :container? true
       :render
       (fn [{:keys [height offset] :or {offset 0}} children {:keys [width] outer-h :height} render-child]
         (let [h         (or height outer-h)
               sub-grids (mapv (fn [c] (render-child c {:width width :height 1})) children)
               total     (count sub-grids)
               start     (max 0 (min offset (max 0 (- total h))))
               end       (min total (+ start h))
               visible   (subvec sub-grids start end)
               canvas    (apply cells/stack
                                (concat visible
                                        (repeat (max 0 (- h (count visible)))
                                                (cells/blank width 1))))
               canvas    (if (pos? start)
                           (cells/overlay canvas
                                          (cells/text-row 1 {:dim? true} "▲")
                                          (max 0 (dec width)) 0)
                           canvas)
               canvas    (if (< end total)
                           (cells/overlay canvas
                                          (cells/text-row 1 {:dim? true} "▼")
                                          (max 0 (dec width)) (max 0 (dec h)))
                           canvas)]
           canvas))})

    ;; ──────────────────────────────────────────────────────────────────
    ;; §7.4 Streaming and interaction
    ;; ──────────────────────────────────────────────────────────────────

    (er/defelement :turn
      {:doc   "A single turn in a stream. Attrs: {:role :user|:assistant|:system}."
       :attrs [:map [:role [:enum :user :assistant :system]]]
       :container? true
       :render
       (fn [{:keys [role]} children {:keys [width height]} render-child]
         (let [tag-text   (case role
                            :user      "You"
                            :assistant "AI"
                            :system    "Sys")
               tag-styles (case role
                            :user      {:fg :cyan   :bold? true}
                            :assistant {:fg :green  :bold? true}
                            :system    {:fg :yellow :bold? true})
               header     (cells/text-row width tag-styles tag-text)
               body       (apply cells/stack
                                 (mapv (fn [c]
                                         (render-child c {:width width :height 1}))
                                       children))]
           (cells/clip (cells/stack header body) {:width width :height height})))})

    (er/defelement :fold
      {:doc   "Collapsible inline block. Attrs: {:summary \"...\" :expanded? bool}."
       :attrs [:map
               [:summary   :string]
               [:expanded? {:optional true} :boolean]]
       :container? true
       :render
       (fn [{:keys [summary expanded?]} children {:keys [width height]} render-child]
         (let [arrow (if expanded? "▼ " "▶ ")
               head  (cells/text-row width {:dim? true} (str arrow summary))]
           (if expanded?
             (let [body (apply cells/stack
                               (mapv (fn [c] (render-child c {:width width :height 1}))
                                     children))]
               (cells/clip (cells/stack head body) {:width width :height height}))
             head)))})

    (er/defelement :status
      {:doc   "Status indicator. Attrs: {:state :pending|:running|:done|:failed :label \"...\"}."
       :attrs [:map
               [:state [:enum :pending :running :done :failed]]
               [:label {:optional true} :string]]
       :preferred-size (fn [{:keys [label]}]
                         {:width (+ 4 (count (or label ""))) :height 1})
       :render
       (fn [{:keys [state label]} {:keys [width]}]
         (let [[glyph styles] (case state
                                :pending ["◌" {:dim? true}]
                                :running ["●" {:fg :yellow}]
                                :done    ["✓" {:fg :green}]
                                :failed  ["✗" {:fg :red :bold? true}])
               text (str glyph " " (or label ""))]
           (cells/text-row width styles text)))})

    (er/defelement :progress
      {:doc   "Progress bar. Attrs: {:value 0.0-1.0 :label?}."
       :attrs [:map
               [:value :double]
               [:label {:optional true} :string]]
       :preferred-size (constantly {:width 0 :height 1})
       :render
       (fn [{:keys [value label]} {:keys [width]}]
         (let [v        (-> value (max 0.0) (min 1.0))
               label-s  (or label "")
               ;; Reserve 6 cols for label or "100% " when present
               label-w  (if (seq label-s) (+ 1 (count label-s)) 0)
               bar-w    (max 0 (- width label-w))
               filled   (int (* bar-w v))
               empty-w  (max 0 (- bar-w filled))
               bar-text (apply str
                               (concat (repeat filled "█")
                                       (repeat empty-w "░")))
               combined (str bar-text (when (seq label-s) (str " " label-s)))]
           (cells/text-row width {} combined)))})

    (er/defelement :spinner
      {:doc   "Animated indicator. Attrs: {:label? :phase n}. Substrate animates by re-injecting :phase."
       :attrs [:map
               [:label {:optional true} :string]
               [:phase {:optional true} :int]]
       :preferred-size (fn [{:keys [label]}]
                         {:width (+ 2 (count (or label ""))) :height 1})
       :stream-stable? false
       :render
       (fn [{:keys [label phase] :or {phase 0}} {:keys [width]}]
         (let [frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]
               glyph  (nth frames (mod phase (count frames)))]
           (cells/text-row width {:fg :cyan} (str glyph " " (or label "")))))})

    ;; ──────────────────────────────────────────────────────────────────
    ;; §7.5 Input
    ;; ──────────────────────────────────────────────────────────────────

    ;; ──────────────────────────────────────────────────────────────────
    ;; Substrate-internal: pre-rendered cell embedding (v0.8 §7.6.8)
    ;; ──────────────────────────────────────────────────────────────────
    ;;
    ;; `:cells` is the seam by which application-registered cell-rendering
    ;; elements travel through a frame: the server resolves a custom
    ;; element to its CellGrid, then wraps the grid in `[:cells {:grid g}]`
    ;; so the rest of the pipeline (layout, serialization, client) treats
    ;; it as a first-class hiccup leaf. Built-in elements never produce
    ;; `:cells` directly; the frame serializer does.

    (er/defelement :cells
      {:doc            "Pre-rendered CellGrid embedded as a hiccup leaf. Used by the v0.8 frame serializer to ship application-registered elements over the wire as opaque cells."
       :attrs          [:map [:grid :any]]
       :preferred-size (fn [{:keys [grid]}]
                         {:width  (:width  grid 0)
                          :height (:height grid 0)})
       :min-size       {:width 1 :height 1}
       :stream-stable? true
       :render
       (fn [{:keys [grid]} box]
         (cells/clip grid box))})

    (er/defelement :input
      {:doc   "Text input area. Attrs: {:value \"...\" :cursor n :placeholder?}."
       :attrs [:map
               [:value       {:optional true} :string]
               [:cursor      {:optional true} :int]
               [:placeholder {:optional true} :string]]
       :preferred-size (constantly {:width 0 :height 1})
       :render
       (fn [{:keys [value cursor placeholder]} {:keys [width]}]
         (let [v       (or value "")
               showing (if (and (empty? v) placeholder)
                         placeholder
                         v)
               styles  (if (and (empty? v) placeholder)
                         {:dim? true}
                         {})
               base    (cells/text-row width styles showing)]
           ;; Highlight cursor cell with reverse styling (use :underline? as a stand-in
           ;; for "this is the cursor" — terminal capability for true reverse video
           ;; is handled by the ANSI emitter).
           (if (and cursor (number? cursor) (<= 0 cursor) (< cursor width))
             (let [cur-cell (-> (get-in (:cells base) [0 cursor])
                                (assoc :underline? true))
                   row      (assoc (first (:cells base)) cursor cur-cell)]
               (assoc base :cells [row]))
             base)))})

    ;; ──────────────────────────────────────────────────────────────────
    ;; Placeable input area marker (`:tui/input {:placement :slot}`)
    ;; ──────────────────────────────────────────────────────────────────
    ;;
    ;; Fills its allocated box with sentinel cells. After the screen grid
    ;; is composed, the compositor (`session/render-frame-alt!` locally,
    ;; `tui-client.render` remotely) scans for them via
    ;; `input-slot/find-slot-box`, overlays the real input area there, and
    ;; strips the markers before diff. Sentinels are created at layout
    ;; time and never serialized — the wire only carries `[:input-slot]`.

    (er/defelement :input-slot
      {:doc   "Marks where a :tui/input {:placement :slot} input area renders. Attrs: {:height n} — rows to reserve (default 1; size up for :multiline?). Width fills the allocated box."
       :attrs [:map [:height {:optional true} :int]]
       :preferred-size (fn [{:keys [height]}] {:width 0 :height (or height 1)})
       :min-size       {:width 1 :height 1}
       :stream-stable? true
       :render
       (fn [_attrs {:keys [width height]}]
         (input-slot/sentinel-grid width height))})

    :registered))
