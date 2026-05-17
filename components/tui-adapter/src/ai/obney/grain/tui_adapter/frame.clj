(ns ai.obney.grain.tui-adapter.frame
  "Frame production — the v0.8 §3 'unit of presentation'.

   A Frame is a pure-data description of what should be on screen right
   now: the hiccup (snapshot), or the stream segments, or the per-region
   hiccup (layout), plus the active overlay, plus enough metadata for any
   consumer to render. It is *post-handler, pre-layout*: the query has
   run and produced its return value; layout-to-cells has not happened
   yet.

   Two consumers in the v0.8 spec:

     - Local-topology rendering (this codebase): the session layer
       consumes a frame, runs `layout/render-element` against its
       viewport, diffs to ANSI. See `session/compute-screen-grid`.

     - Remote-topology delivery (v0.8 §4.3, Phase 3 of v0.8 plan): the
       HTTP+SSE transport serializes a frame as EDN/JSON and pushes it
       to a thin client, which lays it out at its own viewport.

   Per §6.4, the handler return is the source of presentation. Hiccup
   keys (`:tui/hiccup`, `:tui/regions`, per-segment hiccup) live on the
   handler return; this namespace reads them and packages them into a
   Frame.

   The Frame's shape:

     {:screen   {:query-id <kw> :inputs <map>}
      :metadata {:buffer      :alt|:main
                 :projection  :snapshot|:stream
                 :segments-spec {:items ... :key ... :hiccup ...}}
      :hiccup         <hiccup-or-nil>      ; populated when snapshot + no layout
      :segments       <vec-or-nil>         ; populated when :stream
      :regions        <map-or-nil>         ; populated when :tui/layout declared
      :layout         <layout-tree-or-nil> ; from screen's :tui/layout
      :keymap         <map-or-nil>         ; from screen's :tui/keymap
      :region-keymaps <map-or-nil>         ; from screen's :tui/region-keymaps
      :input          <map-or-nil>         ; from screen's :tui/input
      :overlay        <overlay-state-or-nil>
      :error          <{:headline :message}-or-nil>}

   Exactly one of `:hiccup`, `:segments`, `:regions`, `:error` is the
   *primary content*; the others are nil. `:overlay` is independent and
   may be present alongside any primary content. `:keymap`/`:input`/
   `:layout`/`:region-keymaps` are screen-level facts that travel
   alongside so remote clients can resolve keystrokes without a
   separate metadata fetch."
  (:require [ai.obney.grain.tui-adapter.element-registry :as er]))

(defn- anomaly?
  "True when `result` carries a Cognitect anomaly category."
  [result]
  (and (map? result)
       (contains? result :cognitect.anomalies/category)))

(defn- screen-chrome
  "Lift the screen-level chrome (keymap, region-keymaps, input area,
   layout tree) onto the frame. These keys are independent of the
   handler return — they're static facts about the screen — but
   travel in the frame so remote clients can resolve keys / lay out
   regions without a separate registry fetch."
  [screen]
  {:keymap         (:tui/keymap         screen)
   :region-keymaps (:tui/region-keymaps screen)
   :input          (:tui/input          screen)
   :layout         (:tui/layout         screen)})

(defn- error-frame
  "Build a frame whose primary content is an error. Used for both
   thrown-exception query failures and returned-anomaly results.

   Local renderer translates `:error` into the standard error hiccup;
   remote clients render it themselves. Either way, no `:hiccup` /
   `:segments` / `:regions` is set, so consumers can match on `:error`
   first."
  [screen overlay headline message]
  (merge
    {:screen   {:query-id (:query-id screen) :inputs (:inputs screen)}
     :metadata {:buffer     (:tui/buffer screen)
                :projection (:tui/projection screen)}
     :hiccup   nil
     :segments nil
     :regions  nil
     :overlay  overlay
     :error    {:headline headline :message message}}
    (screen-chrome screen)))

(defn produce-frame
  "Pure: given a session and a query result, return a Frame describing
   what should be on screen for this cycle.

   The session need not be an atom — accepts a plain map snapshot so
   tests can construct a frame from fixtures without setting up a full
   session lifecycle. (`session/compute-screen-grid` derefs its atom
   before calling in.)

   `result` is the handler return map (from `process-query-fn`). It may
   carry `:query/error` (thrown), an anomaly, or presentation keys
   (`:tui/hiccup`, `:tui/regions`, segments at the path declared by
   `:tui/segments :items`)."
  [session-snapshot result]
  (let [{:keys [current-screen overlay]} session-snapshot
        screen  {:query-id (:query-id current-screen)
                 :inputs   (:inputs current-screen)}
        meta    {:buffer        (:tui/buffer current-screen)
                 :projection    (:tui/projection current-screen)
                 :segments-spec (:tui/segments current-screen)}]
    (cond
      (:query/error result)
      (error-frame current-screen overlay "Query error" (:query/error result))

      (anomaly? result)
      (error-frame current-screen overlay
                   (str "Query failed (" (:cognitect.anomalies/category result) ")")
                   (:cognitect.anomalies/message result))

      (= :stream (:tui/projection current-screen))
      (merge
        {:screen   screen
         :metadata meta
         :hiccup   nil
         :segments (let [{:keys [items]} (:tui/segments current-screen)]
                     (vec
                       (cond
                         (keyword? items) (get result items)
                         (vector? items)  (get-in result items)
                         (fn? items)      (items result)
                         :else            [])))
         :regions  nil
         :overlay  overlay
         :error    nil}
        (screen-chrome current-screen))

      :else
      (merge
        {:screen   screen
         :metadata meta
         :hiccup   (:tui/hiccup result)
         :segments nil
         :regions  (:tui/regions result)
         :overlay  overlay
         :error    nil}
        (screen-chrome current-screen)))))

;; ─────────────────────────────────────────────────────────────────────
;; Frame predicates — helpers for consumers branching on primary content
;; ─────────────────────────────────────────────────────────────────────

(defn error?   [frame] (some? (:error   frame)))
(defn stream?  [frame] (some? (:segments frame)))
(defn regions? [frame] (some? (:regions  frame)))
(defn snapshot? [frame]
  (and (not (error? frame))
       (not (stream? frame))
       (not (regions? frame))))

;; ─────────────────────────────────────────────────────────────────────
;; Custom-element resolution (v0.8 §7.6.8)
;; ─────────────────────────────────────────────────────────────────────
;;
;; In remote topology, application-registered cell-rendering elements
;; must travel as opaque cells in the frame — the thin client doesn't
;; have the application's `:render` functions. The server walks the
;; hiccup tree, finds any element whose tag is *not* a built-in, calls
;; the registry's `:render`/`:preferred-size`, and replaces the node
;; with `[:cells {:grid <CellGrid>}]`. Built-in elements pass through
;; untouched; the client's local layout engine handles them.

(def built-in-tags
  "The closed set of substrate-level element tags (§7.1–§7.5 + the
   internal `:cells` embedding leaf added for §7.6.8). Anything outside
   this set is an application registration and gets resolved server-side
   before frame serialization."
  #{:text :line :gap
    :row :col :box :pad :weighted
    :list :table :scroll
    :turn :fold :status :progress :spinner
    :input
    :input-slot
    :cells})

(defn- node? [x]
  (and (vector? x) (keyword? (first x))))

(defn- node-tag [node]
  (when (node? node) (first node)))

(defn- node-attrs [node]
  (when (node? node)
    (let [a (second node)]
      (if (map? a) a {}))))

(defn- node-children [node]
  (when (node? node)
    (let [body (next node)]
      (if (map? (first body)) (rest body) body))))

(defn- preferred-size-for
  "Read the registered element's preferred size, falling back to a
   tiny default so the resolver can still produce a CellGrid when an
   element has no explicit preferred-size."
  [entry attrs children]
  (let [pf (:preferred-size entry)
        ps (try
             (try (pf attrs children) (catch clojure.lang.ArityException _ (pf attrs)))
             (catch Exception _ nil))]
    (or ps {:width 1 :height 1})))

(defn resolve-custom-elements
  "Walk a hiccup tree, replacing application-registered (non-built-in)
   elements with `[:cells {:grid <CellGrid>}]` markers built from the
   element's registered `:render`. Built-in elements (§7.1–§7.5) pass
   through with their children recursively resolved.

   Bare strings and non-hiccup data pass through unchanged.

   Container children of a resolved custom element are *not* visited:
   the element's own `:render` already produced its CellGrid, so the
   substrate doesn't recurse into its children. This matches how
   `layout/render-container` and `layout/render-leaf` already treat the
   registry — registered elements own their sub-rendering."
  [node]
  (cond
    (string? node)
    node

    (not (node? node))
    node

    :else
    (let [tag           (node-tag node)
          attrs         (node-attrs node)
          children      (vec (node-children node))
          had-attrs?    (and (>= (count node) 2) (map? (second node)))]
      (if (contains? built-in-tags tag)
        (let [resolved-children (mapv resolve-custom-elements children)]
          (if had-attrs?
            (into [tag attrs] resolved-children)
            (into [tag] resolved-children)))
        ;; Application-registered: resolve to a [:cells {:grid <CellGrid>}].
        (if-let [entry (er/lookup tag)]
          (let [{:keys [render attrs-validator]} entry
                box  (preferred-size-for entry attrs children)
                _    (when attrs-validator
                       (when-not (attrs-validator attrs)
                         (throw (ex-info "Custom element attrs failed validation during resolution"
                                         {:tag tag :attrs attrs}))))
                grid (try
                       (render attrs box)
                       (catch clojure.lang.ArityException _
                         ;; container-style signature
                         (render attrs children box (fn [_ _] nil))))]
            [:cells {:grid grid}])
          (throw (ex-info "Unknown hiccup tag during resolution"
                          {:tag tag :registered (er/list-elements)})))))))

(defn resolve-frame
  "Apply `resolve-custom-elements` to every hiccup-bearing slot in a
   frame: `:hiccup`, each segment's hiccup at the segments-spec's
   `:hiccup` path, each value in `:regions`, and the overlay's
   `:content`. Returns the frame with all custom elements pre-resolved
   to embedded CellGrids — safe for EDN/JSON serialization over SSE."
  [frame]
  (let [hiccup-path (or (-> frame :metadata :segments-spec :hiccup)
                        :tui/hiccup)]
    (cond-> frame
      (:hiccup frame)
      (update :hiccup resolve-custom-elements)

      (:regions frame)
      (update :regions update-vals resolve-custom-elements)

      (:segments frame)
      (update :segments
              (fn [segs]
                (mapv (fn [seg]
                        (if-let [h (get seg hiccup-path)]
                          (assoc seg hiccup-path (resolve-custom-elements h))
                          seg))
                      segs)))

      (and (:overlay frame) (-> frame :overlay :content))
      (update-in [:overlay :content] resolve-custom-elements))))
