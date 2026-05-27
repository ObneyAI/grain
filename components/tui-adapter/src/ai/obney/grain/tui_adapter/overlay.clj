(ns ai.obney.grain.tui-adapter.overlay
  "Toast / modal / palette overlays.

   Toasts and modals are simple — the session stores their hiccup in
   `:overlay` and the renderer composites them via `cells/overlay`.

   Palettes are richer: a substrate-owned filter input + scrollable
   list driven by a query result, configured at the call site by
   `[:session :open-palette {...}]`. This namespace owns the palette's
   render function and key handling so the session loop doesn't need to
   know about palette internals."
  (:require [clojure.string :as str]
            [ai.obney.grain.tui-adapter.layout :as layout]))

;; ─────────────────────────────────────────────────────────────────────
;; Palette items — fetch + filter
;; ─────────────────────────────────────────────────────────────────────

(defn fetch-palette-items
  "Run the palette's query via `process-query-fn` and return its items.
   The palette's `:config` declares `:query-id`, `:inputs`, and the
   `:item-key`/`:item-label` for each item."
  [{:keys [process-query-fn base-context tenant-id user-id]}
   {:keys [query-id inputs] :as _config}]
  (let [ctx (merge base-context
                   {:tenant-id tenant-id
                    :user-id   user-id
                    :query     (merge {:query/name query-id} (or inputs {}))})
        result (try
                 (process-query-fn ctx)
                 (catch Exception _ {}))]
    (vec (or (:query/result result) []))))

(defn filter-items
  "Filter `items` by `filter-text` against the `:item-label` path on each
   item (case-insensitive substring match). Empty filter returns all
   items unchanged."
  [items item-label filter-text]
  (if (str/blank? filter-text)
    items
    (let [needle (str/lower-case filter-text)]
      (filterv (fn [it]
                 (let [label (-> (get it item-label "")
                                 str
                                 str/lower-case)]
                   (str/includes? label needle)))
               items))))

;; ─────────────────────────────────────────────────────────────────────
;; Palette render
;; ─────────────────────────────────────────────────────────────────────

(defn palette-hiccup
  "Render a palette overlay's contents as hiccup. The substrate composites
   this onto the screen via `cells/overlay` at a centered location."
  [{:keys [config filter selected items] :as _palette-state}]
  (let [item-label (:item-label config)
        details    (:details config)
        filterable? (not= false (:filterable? config))
        rows       (vec
                     (map-indexed
                       (fn [i it]
                         [:text {:bold? (= i selected)
                                 :fg    (when (= i selected) :cyan)}
                                (str (get it item-label ""))])
                       items))]
    [:box {:title (or (:title config) "Palette")}
     (into [:col]
           (concat
            (mapv (fn [{:keys [label value style]}]
                    [:row
                     [:text {:dim? true} (format "%-7s" (str label))]
                     [:text (or style {}) (str value)]])
                  details)
            (when filterable?
              [[:row
                [:text {:dim? true} "/ "]
                [:input {:value filter :cursor (count filter)}]]])
            [[:list {:items rows :selected selected}]
             [:text {:dim? true}
              (or (:help config) "Enter select   ↑/↓ move   Esc dismiss")]]))]))

;; ─────────────────────────────────────────────────────────────────────
;; Palette key handling — returns updated palette state or :dismiss / :select
;; ─────────────────────────────────────────────────────────────────────

(defn handle-palette-key
  "Process a key event against the palette's filter/selection. Returns:
     {:state :update :palette {...}}     — update palette state
     {:state :select :item it}           — user pressed Enter
     {:state :dismiss}                   — user pressed Esc
     nil                                 — ignore"
  [palette-state key]
  (case key
    "<esc>"   {:state :dismiss}
    "<enter>" {:state :select :item (nth (:items palette-state) (:selected palette-state) nil)}
    "<up>"    {:state :update
               :palette (update palette-state :selected
                                #(max 0 (dec %)))}
    "<down>"  {:state :update
               :palette (update palette-state :selected
                                #(min (max 0 (dec (count (:items palette-state)))) (inc %)))}
    "<backspace>"
    (when (not= false (-> palette-state :config :filterable?))
      (let [new-filter (subs (:filter palette-state)
                             0
                             (max 0 (dec (count (:filter palette-state)))))
            new-items  (filter-items (:all-items palette-state)
                                     (-> palette-state :config :item-label)
                                     new-filter)]
        {:state :update
         :palette (assoc palette-state
                         :filter   new-filter
                         :items    new-items
                         :selected 0)}))
    ;; Optional number shortcut — select the corresponding visible item.
    (if (and (-> palette-state :config :number-shortcuts?)
             (string? key)
             (= 1 (count key))
             (Character/isDigit (.charAt ^String key 0)))
      (let [idx (dec (Long/parseLong key))]
        (when (<= 0 idx (dec (count (:items palette-state))))
          {:state :select :item (nth (:items palette-state) idx)}))
      ;; Single printable character — append to filter
    (if (and (string? key) (= 1 (count key))
             (not= false (-> palette-state :config :filterable?))
             (let [c (int (.charAt ^String key 0))]
               (and (>= c 32) (<= c 126))))
      (let [new-filter (str (:filter palette-state) key)
            new-items  (filter-items (:all-items palette-state)
                                     (-> palette-state :config :item-label)
                                     new-filter)]
        {:state :update
         :palette (assoc palette-state
                         :filter   new-filter
                         :items    new-items
                         :selected 0)})
      nil))))

;; ─────────────────────────────────────────────────────────────────────
;; Toast positioning convenience
;; ─────────────────────────────────────────────────────────────────────

(defn toast-position
  "Compute the (x, y) origin for a toast overlay of size (w, h) inside
   a viewport of size (vw, vh). Top-right by §13.2 convention."
  [w h vw vh]
  [(max 0 (- vw w 1)) 1])

(defn modal-position
  "Compute the centered (x, y) origin for a modal overlay."
  [w h vw vh]
  [(max 0 (quot (- vw w) 2))
   (max 0 (quot (- vh h) 2))])
