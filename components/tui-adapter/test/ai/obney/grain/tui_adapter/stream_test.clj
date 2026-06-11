(ns ai.obney.grain.tui-adapter.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]   ; loads :text etc.
            [ai.obney.grain.tui-adapter.stream :as stream]))

;; Per spec v0.7 each segment carries its own pre-rendered hiccup at
;; `(:hiccup segments-spec)` (default `:tui/hiccup`). Test segments below
;; build that field directly.
(defn- mk-seg [m]
  (assoc m :tui/hiccup [:text {:text (str (:text m))}]))

;; ──────────────────────────────────────────────────────────────────────────
;; extract-segments + segment-key
;; ──────────────────────────────────────────────────────────────────────────

(deftest extract-with-keyword-path
  (is (= [{:n 1} {:n 2}]
         (stream/extract-segments {:items [{:n 1} {:n 2}]} {:items :items}))))

(deftest extract-with-vector-path
  (is (= [{:n 1}]
         (stream/extract-segments {:a {:b [{:n 1}]}} {:items [:a :b]}))))

(deftest extract-with-fn
  (is (= [1 2 3]
         (stream/extract-segments {:nums [1 2 3]} {:items (fn [r] (:nums r))}))))

(deftest segment-key-keyword
  (is (= 42 (stream/segment-key {:key :id} {:id 42}))))

(deftest segment-key-vector
  (is (= 42 (stream/segment-key {:key [:meta :id]} {:meta {:id 42}}))))

;; ──────────────────────────────────────────────────────────────────────────
;; compute-visible-window — last N that fit
;; ──────────────────────────────────────────────────────────────────────────

(deftest visible-window-all-fit
  (let [segs [{:h 1} {:h 1} {:h 1}]]
    (is (= segs (stream/compute-visible-window segs 5 :h)))))

(deftest visible-window-budget-truncates
  (let [segs [{:h 1} {:h 1} {:h 1} {:h 1} {:h 1}]
        win  (stream/compute-visible-window segs 3 :h)]
    (is (= 3 (count win)))
    ;; oldest→newest order preserved; tail = last 3
    (is (= [{:h 1} {:h 1} {:h 1}] (vec win)))))

(deftest visible-window-zero-budget
  (is (= [] (stream/compute-visible-window [{:h 1}] 0 :h))))

;; ──────────────────────────────────────────────────────────────────────────
;; refresh-cache — reuse on hash-equal, evict off-window
;; ──────────────────────────────────────────────────────────────────────────

(deftest cache-reuses-when-hash-unchanged
  (let [seg   (mk-seg {:id 1 :text "a"})
        pairs [[1 seg]]
        c1 (stream/refresh-cache {} pairs :tui/hiccup {:width 5})
        c2 (stream/refresh-cache c1 pairs :tui/hiccup {:width 5})]
    ;; Same map identity for entry on hash hit.
    (is (identical? (get c1 1) (get c2 1)))))

(deftest cache-rerenders-on-hash-change
  (let [pairs1 [[1 (mk-seg {:id 1 :text "a"})]]
        pairs2 [[1 (mk-seg {:id 1 :text "b"})]]
        c1 (stream/refresh-cache {} pairs1 :tui/hiccup {:width 5})
        c2 (stream/refresh-cache c1 pairs2 :tui/hiccup {:width 5})]
    (is (not (identical? (get c1 1) (get c2 1))))))

(deftest cache-evicts-off-window-keys
  (let [pairs1 [[1 (mk-seg {:id 1 :text "a"})] [2 (mk-seg {:id 2 :text "b"})]]
        pairs2 [[2 (mk-seg {:id 2 :text "b"})]]
        c1 (stream/refresh-cache {} pairs1 :tui/hiccup {:width 5})
        c2 (stream/refresh-cache c1 pairs2 :tui/hiccup {:width 5})]
    (is (contains? c1 1))
    (is (not (contains? c2 1)))
    (is (contains? c2 2))))

;; ──────────────────────────────────────────────────────────────────────────
;; detect-violation
;; ──────────────────────────────────────────────────────────────────────────

(deftest no-prior-keys-no-violation
  (is (nil? (stream/detect-violation [] [1 2 3]))))

(deftest append-no-violation
  (is (nil? (stream/detect-violation [1 2] [1 2 3]))))

(deftest reorder-violation
  (is (= :reorder (stream/detect-violation [1 2 3] [1 3 2]))))

(deftest drop-violation
  (is (= :drop (stream/detect-violation [1 2 3] [1 2]))))

(deftest replace-key-is-reorder
  (is (= :reorder (stream/detect-violation [1 2 3] [1 9 3]))))

;; ──────────────────────────────────────────────────────────────────────────
;; render-stream end-to-end
;; ──────────────────────────────────────────────────────────────────────────

(def ^:private result-of (fn [items] {:items (mapv mk-seg items)}))
(def ^:private spec      {:items :items :key :id :hiccup :tui/hiccup})

(deftest render-stream-fills-from-empty-state
  (let [{:keys [state grid]}
        (stream/render-stream (stream/empty-stream-state)
                              (result-of [{:id 1 :text "a"}
                                          {:id 2 :text "b"}])
                              spec
                              {:width 5 :height 4})]
    (is (= 5 (:width grid)))
    (is (= 4 (:height grid)))
    (is (= [1 2] (:visible-window state)))
    (is (= [1 2] (:last-keys state)))))

(deftest render-stream-keeps-cache-across-calls
  (let [s0 (stream/empty-stream-state)
        r1 (stream/render-stream s0 (result-of [{:id 1 :text "a"}])
                                  spec {:width 5 :height 4})
        r2 (stream/render-stream (:state r1)
                                  (result-of [{:id 1 :text "a"} {:id 2 :text "b"}])
                                  spec {:width 5 :height 4})]
    ;; The first segment's cache entry should be reused (same hash).
    (is (identical? (get-in r1 [:state :segment-cache 1])
                    (get-in r2 [:state :segment-cache 1])))))

(deftest render-stream-violation-triggers-rerender
  (let [s0 (stream/empty-stream-state)
        ;; Two segments first, then a reordered second frame.
        r1 (stream/render-stream s0 (result-of [{:id 1 :text "a"}
                                                {:id 2 :text "b"}])
                                  spec {:width 5 :height 4})
        r2 (stream/render-stream (:state r1)
                                  (result-of [{:id 1 :text "a"}
                                              {:id 2 :text "B"}])
                                  spec {:width 5 :height 4})]
    ;; New segment :id 2 has different hash → cache miss; that's the
    ;; normal stream-stable cache-bust path. (Reorder requires actual
    ;; key reorder; this test exercises hash invalidation.)
    (is (not (identical? (get-in r1 [:state :segment-cache 2])
                         (get-in r2 [:state :segment-cache 2]))))))

(deftest render-stream-visible-window-truncates
  (let [s0 (stream/empty-stream-state)
        ;; Five 1-row segments in a 3-tall viewport — only last 3 visible.
        r  (stream/render-stream s0
                                  (result-of (mapv (fn [i] {:id i :text (str i)})
                                                   [1 2 3 4 5]))
                                  spec {:width 5 :height 3})]
    (is (= [3 4 5] (:visible-window (:state r))))
    ;; Off-window segments not in cache.
    (is (not (contains? (get-in r [:state :segment-cache]) 1)))
    (is (not (contains? (get-in r [:state :segment-cache]) 2)))))

;; ──────────────────────────────────────────────────────────────────────────
;; styled-runs :text segments — heights from wrapped visual lines
;; ──────────────────────────────────────────────────────────────────────────

(deftest render-stream-runs-segment-height-counts-wrapped-lines
  ;; A runs :text segment wrapping to 3 visual lines at width 6 must
  ;; occupy 3 rows of the window budget (a runs child used to count as
  ;; height 1 and get clipped).
  (let [runs-seg {:id 2 :tui/hiccup [:text [{:text "alpha " :bold? true}
                                            {:text "beta gamma"}]]}
        r (stream/render-stream (stream/empty-stream-state)
                                {:items [(mk-seg {:id 1 :text "a"}) runs-seg]}
                                spec
                                {:width 6 :height 3})]
    (is (= [2] (:visible-window (:state r)))
        "the 3-line runs segment fills the 3-row budget; the older 1-line segment is evicted")
    (is (= 3 (get-in r [:state :segment-cache 2 :height])))
    ;; The grid shows all three wrapped rows.
    (let [row-str (fn [i] (apply str (map :char (get-in r [:grid :cells i]))))]
      (is (= "alpha " (row-str 0)))
      (is (= "beta  " (row-str 1)))
      (is (= "gamma " (row-str 2))))))

(deftest render-stream-runs-segment-with-continuation-height
  ;; Width 7, continuation "> " (2) → "aaa bbb" + "> ccc" = 2 lines.
  (let [seg {:id 1 :tui/hiccup [:text {:continuation "> "}
                                [{:text "aaa bbb ccc"}]]}
        r   (stream/render-stream (stream/empty-stream-state)
                                  {:items [seg]}
                                  spec
                                  {:width 7 :height 4})]
    (is (= 2 (get-in r [:state :segment-cache 1 :height])))))
