(ns ai.obney.grain.tui-adapter.frame-serialize-test
  "Tests for the v0.8 §7.6.8 frame-serialization path:
   - the `:cells` built-in (pre-rendered grid embedding)
   - `frame/resolve-custom-elements` (walks hiccup, resolves app-registered
     elements server-side into `[:cells {:grid ...}]` leaves)
   - `frame/resolve-frame` (applies the resolver to every hiccup-bearing
     slot in a Frame)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.tui-adapter.builtins]   ; loads built-ins
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [ai.obney.grain.tui-adapter.frame :as frame]
            [ai.obney.grain.tui-adapter.layout :as layout]))

;; A fixture custom element. Built once at test load; safe to re-register
;; because the registry tolerates redefinition (logs a warning).
(er/defelement :test/sparkline
  {:doc            "Test fixture: paints a single-row grid of the given width filled with '#'."
   :attrs          [:map [:width :int] [:fg {:optional true} :any]]
   :preferred-size (fn [{:keys [width]}] {:width width :height 1})
   :min-size       {:width 1 :height 1}
   :render
   (fn [{:keys [width fg]} _box]
     (cells/text-row width (cond-> {} fg (assoc :fg fg))
                     (apply str (repeat width "#"))))})

;; ──────────────────────────────────────────────────────────────────────
;; :cells built-in
;; ──────────────────────────────────────────────────────────────────────

(deftest cells-element-renders-its-grid
  (let [grid    (cells/text-row 5 {:fg :green} "abcde")
        result  (layout/render-element [:cells {:grid grid}] {:width 5 :height 1})]
    (is (= 5 (:width result)))
    (is (= 1 (:height result)))
    (is (= "a" (get-in result [:cells 0 0 :char])))
    (is (= :green (get-in result [:cells 0 0 :fg])))))

(deftest cells-element-clips-oversized-grid
  (let [grid   (cells/text-row 10 {} "abcdefghij")
        result (layout/render-element [:cells {:grid grid}] {:width 4 :height 1})]
    (is (= 4 (:width result)))
    (is (= "d" (get-in result [:cells 0 3 :char])))))

(deftest cells-element-respects-preferred-size
  ;; Inside a :row, the :cells preferred-size should be honored.
  (let [grid (cells/text-row 3 {} "abc")
        result (layout/render-element
                 [:row [:cells {:grid grid}] [:text "X"]]
                 {:width 10 :height 1})]
    ;; "abc" fills cols 0-2, "X" appears at col 3
    (is (= "a" (get-in result [:cells 0 0 :char])))
    (is (= "b" (get-in result [:cells 0 1 :char])))
    (is (= "c" (get-in result [:cells 0 2 :char])))
    (is (= "X" (get-in result [:cells 0 3 :char])))))

;; ──────────────────────────────────────────────────────────────────────
;; resolve-custom-elements — walks hiccup, resolves app elements only
;; ──────────────────────────────────────────────────────────────────────

(deftest resolver-passes-built-ins-through-unchanged
  (let [h [:row [:text {:fg :red} "hi"] [:gap 1]]]
    (is (= h (frame/resolve-custom-elements h)))))

(deftest resolver-replaces-custom-element-with-cells-marker
  (let [resolved (frame/resolve-custom-elements
                   [:test/sparkline {:width 4 :fg :green}])]
    (is (= :cells (first resolved)))
    (is (map?     (second resolved)))
    (is (contains? (second resolved) :grid))
    (let [grid (-> resolved second :grid)]
      (is (= 4 (:width grid)))
      (is (= 1 (:height grid))))))

(deftest resolver-recurses-into-built-in-containers
  ;; The custom element is nested inside a built-in :row; resolver should
  ;; find it and replace it in place while leaving the row intact.
  ;; Input has no attrs on :row, so output keeps no attrs on :row.
  (let [h        [:row
                  [:text "label "]
                  [:test/sparkline {:width 5}]
                  [:text " end"]]
        resolved (frame/resolve-custom-elements h)]
    (is (= :row             (first resolved)))
    (is (= [:text "label "] (nth resolved 1)))
    (is (= :cells           (first (nth resolved 2))))
    (is (= [:text " end"]   (nth resolved 3)))))

(deftest resolver-passes-bare-strings-through
  (is (= "hi" (frame/resolve-custom-elements "hi"))))

(deftest resolver-handles-nested-custom-elements
  ;; Two custom elements at different depths. Input has no attrs on
  ;; :col / :row, so output keeps no attrs at those nodes.
  ;; Expected: [:col [:row [:cells {:grid w=3}]] [:cells {:grid w=4}]]
  (let [h        [:col
                  [:row [:test/sparkline {:width 3}]]
                  [:test/sparkline {:width 4 :fg :blue}]]
        resolved (frame/resolve-custom-elements h)
        outer-spark (-> resolved (nth 2) second :grid)
        inner-spark (-> resolved (nth 1) (nth 1) second :grid)]
    (is (= 4 (:width outer-spark)))
    (is (= 3 (:width inner-spark)))))

(deftest resolver-validates-custom-attrs
  ;; Bad attrs surface as an ex-info during resolution, not as a silent pass-through.
  (is (thrown? Exception
               (frame/resolve-custom-elements
                 [:test/sparkline {:width "not-an-int"}]))))

(deftest resolver-rejects-unknown-tags
  (is (thrown-with-msg? Exception #"Unknown hiccup tag"
                        (frame/resolve-custom-elements [:totally/bogus]))))

;; ──────────────────────────────────────────────────────────────────────
;; resolve-frame — applies the resolver to all hiccup-bearing slots
;; ──────────────────────────────────────────────────────────────────────

(deftest resolve-frame-resolves-snapshot-hiccup
  ;; Input :row has no attrs → output :row has no attrs.
  ;; Resolved layout: [:row [:cells {:grid ...}] [:text "ok"]]
  ;;   index 0: :row, index 1: [:cells ...], index 2: [:text "ok"]
  (let [frm      {:screen   {:query-id :x :inputs {}}
                  :metadata {:buffer :alt :projection :snapshot}
                  :hiccup   [:row [:test/sparkline {:width 3}] [:text "ok"]]
                  :segments nil :regions nil :overlay nil :error nil}
        resolved (frame/resolve-frame frm)]
    (is (= :cells (-> resolved :hiccup (nth 1) first)))))

(deftest resolve-frame-resolves-each-region
  (let [frm      {:screen   {:query-id :x :inputs {}}
                  :metadata {:buffer :alt :projection :snapshot}
                  :hiccup   nil
                  :segments nil
                  :regions  {:a [:test/sparkline {:width 2}]
                             :b [:text "plain"]}
                  :overlay  nil :error nil}
        resolved (frame/resolve-frame frm)]
    (is (= :cells (-> resolved :regions :a first)))
    (is (= [:text "plain"] (-> resolved :regions :b)))))

(deftest resolve-frame-resolves-each-segment-hiccup
  ;; Segments carry hiccup at the path declared by the segments-spec
  ;; (default :tui/hiccup). The resolver should walk each segment's
  ;; hiccup and leave other segment data alone.
  (let [frm      {:screen   {:query-id :x :inputs {}}
                  :metadata {:buffer        :main
                             :projection    :stream
                             :segments-spec {:items :ignored
                                             :key   :id
                                             :hiccup :tui/hiccup}}
                  :hiccup   nil
                  :segments [{:id 1 :tui/hiccup [:test/sparkline {:width 2}]}
                             {:id 2 :tui/hiccup [:text "two"]}]
                  :regions  nil :overlay nil :error nil}
        resolved (frame/resolve-frame frm)
        s1 (first (:segments resolved))
        s2 (second (:segments resolved))]
    ;; Per-segment data (e.g. :id) is preserved
    (is (= 1 (:id s1)))
    (is (= 2 (:id s2)))
    ;; First segment's sparkline was resolved
    (is (= :cells (-> s1 :tui/hiccup first)))
    ;; Second segment's plain text passed through
    (is (= [:text "two"] (:tui/hiccup s2)))))

(deftest resolve-frame-resolves-overlay-content
  (let [frm      {:screen   {:query-id :x :inputs {}}
                  :metadata {:buffer :alt :projection :snapshot}
                  :hiccup   [:text "main"]
                  :segments nil :regions nil
                  :overlay  {:type :modal :content [:test/sparkline {:width 5}]}
                  :error    nil}
        resolved (frame/resolve-frame frm)]
    (is (= :cells (-> resolved :overlay :content first)))))

(deftest resolve-frame-leaves-error-frames-untouched
  ;; Error frames have no primary hiccup; resolver should no-op cleanly.
  (let [frm      {:screen   {:query-id :x :inputs {}}
                  :metadata {:buffer :alt :projection :snapshot}
                  :hiccup   nil :segments nil :regions nil :overlay nil
                  :error    {:headline "Query error" :message "boom"}}
        resolved (frame/resolve-frame frm)]
    (is (= frm resolved))))
