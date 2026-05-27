(ns ai.obney.grain.tui-adapter.overlay-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]   ; loads built-in elements
            [ai.obney.grain.tui-adapter.layout :as layout]
            [ai.obney.grain.tui-adapter.overlay :as overlay]))

(def ^:private query-fn-counter (atom 0))

(defn- mock-process-query [items]
  (fn [_ctx]
    (swap! query-fn-counter inc)
    {:query/result items}))

;; ──────────────────────────────────────────────────────────────────────────
;; fetch-palette-items
;; ──────────────────────────────────────────────────────────────────────────

(deftest fetch-returns-items-from-query
  (reset! query-fn-counter 0)
  (let [items   [{:id 1 :name "Ada"} {:id 2 :name "Bob"}]
        session {:process-query-fn (mock-process-query items)
                 :tenant-id        :t :user-id :u :base-context {}}
        result  (overlay/fetch-palette-items session
                                              {:query-id :sheets/all
                                               :item-key :id :item-label :name})]
    (is (= 2 (count result)))
    (is (= 1 @query-fn-counter))))

(deftest fetch-handles-thrown-query
  (let [session {:process-query-fn (fn [_] (throw (ex-info "boom" {})))
                 :tenant-id :t :user-id :u :base-context {}}]
    (is (= [] (overlay/fetch-palette-items session {:query-id :x :item-key :id :item-label :n})))))

;; ──────────────────────────────────────────────────────────────────────────
;; filter-items
;; ──────────────────────────────────────────────────────────────────────────

(deftest filter-empty-returns-all
  (let [items [{:n "A"} {:n "B"}]]
    (is (= items (overlay/filter-items items :n "")))
    (is (= items (overlay/filter-items items :n nil)))))

(deftest filter-substring-case-insensitive
  (let [items [{:n "Ada"} {:n "Bob"} {:n "Cyrus"}]]
    (is (= [{:n "Ada"}]   (overlay/filter-items items :n "ada")))
    (is (= [{:n "Bob"}]   (overlay/filter-items items :n "BO")))
    (is (= [{:n "Cyrus"}] (overlay/filter-items items :n "y")))))

(deftest filter-no-match-empty
  (is (= [] (overlay/filter-items [{:n "A"} {:n "B"}] :n "zzz"))))

;; ──────────────────────────────────────────────────────────────────────────
;; palette-hiccup renders to a CellGrid via the layout engine
;; ──────────────────────────────────────────────────────────────────────────

(deftest palette-hiccup-renders
  (let [palette {:config {:item-label :n}
                 :filter ""
                 :selected 0
                 :items [{:n "Ada"} {:n "Bob"}]}
        hiccup  (overlay/palette-hiccup palette)
        grid    (layout/render-element hiccup {:width 20 :height 6})]
    (is (= 20 (:width grid)))
    (is (= 6  (:height grid)))))

(deftest non-filterable-palette-renders-details-without-filter-input
  (let [palette {:config {:item-label :n
                          :filterable? false
                          :details [{:label "Tool" :value "fs/list"}
                                    {:label "Target" :value "."}]}
                 :filter ""
                 :selected 0
                 :items [{:n "Yes"} {:n "No"}]}
        hiccup  (overlay/palette-hiccup palette)
        rendered (pr-str hiccup)]
    (is (string? rendered))
    (is (re-find #"fs/list" rendered))
    (is (not (re-find #":input" rendered)))))

;; ──────────────────────────────────────────────────────────────────────────
;; handle-palette-key — navigation, filter, select, dismiss
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private base-palette
  {:config    {:item-label :n :item-key :id}
   :filter    ""
   :selected  0
   :items     [{:id 1 :n "Ada"} {:id 2 :n "Bob"} {:id 3 :n "Cy"}]
   :all-items [{:id 1 :n "Ada"} {:id 2 :n "Bob"} {:id 3 :n "Cy"}]})

(deftest esc-dismisses
  (is (= {:state :dismiss}
         (overlay/handle-palette-key base-palette "<esc>"))))

(deftest enter-selects-current
  (let [r (overlay/handle-palette-key base-palette "<enter>")]
    (is (= :select (:state r)))
    (is (= {:id 1 :n "Ada"} (:item r)))))

(deftest enter-selects-after-arrow-down
  (let [p1 (-> (overlay/handle-palette-key base-palette "<down>") :palette)
        r2 (overlay/handle-palette-key p1 "<enter>")]
    (is (= 1 (:selected p1)))
    (is (= {:id 2 :n "Bob"} (:item r2)))))

(deftest down-stops-at-end
  (let [p1 (-> (overlay/handle-palette-key base-palette "<down>") :palette)
        p2 (-> (overlay/handle-palette-key p1 "<down>") :palette)
        p3 (-> (overlay/handle-palette-key p2 "<down>") :palette)]
    (is (= 2 (:selected p3)))))

(deftest up-stops-at-start
  (is (= 0 (:selected (:palette (overlay/handle-palette-key base-palette "<up>"))))))

(deftest filter-typing-narrows-items
  (let [p1 (-> (overlay/handle-palette-key base-palette "B") :palette)]
    (is (= "B" (:filter p1)))
    (is (= 1 (count (:items p1))))
    (is (= "Bob" (-> p1 :items first :n)))
    (is (= 0 (:selected p1)))))

(deftest backspace-shortens-filter
  (let [p1 (-> (overlay/handle-palette-key base-palette "B") :palette)
        p2 (-> (overlay/handle-palette-key p1 "<backspace>") :palette)]
    (is (= "" (:filter p2)))
    (is (= 3 (count (:items p2))))))

(deftest non-filterable-palette-ignores-typing
  (let [palette (assoc-in base-palette [:config :filterable?] false)]
    (is (nil? (overlay/handle-palette-key palette "B")))
    (is (nil? (overlay/handle-palette-key palette "<backspace>")))))

(deftest unrecognised-key-ignored
  (is (nil? (overlay/handle-palette-key base-palette "<f7>"))))

;; ──────────────────────────────────────────────────────────────────────────
;; toast-position / modal-position
;; ──────────────────────────────────────────────────────────────────────────

(deftest toast-position-top-right
  (is (= [89 1] (overlay/toast-position 10 1 100 24))))

(deftest modal-position-centered
  (is (= [45 11] (overlay/modal-position 10 2 100 24))))
