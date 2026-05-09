(ns ai.obney.grain.tui-adapter.layout-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [ai.obney.grain.tui-adapter.layout :as layout]))

(defn- snapshot-and-restore [t]
  (let [saved @er/registry*]
    (try
      (er/clear!)
      (t)
      (finally
        (reset! er/registry* saved)))))

(use-fixtures :each snapshot-and-restore)

;; Helpers ---------------------------------------------------------------------

(defn- text-leaf
  "Test-only :text leaf — fills its box with copies of `:c`."
  [c]
  {:render (fn [_attrs {:keys [width height]}]
             (apply cells/stack
                    (repeat height (cells/text-row width {} (apply str (repeat width c))))))
   :attrs  [:map]
   :preferred-size (constantly {:width 0 :height 0})})

(defn- fixed-width-leaf
  "Leaf that prefers `w` columns and 1 row."
  [c w]
  {:render (fn [_attrs {:keys [width height]}]
             (apply cells/stack
                    (repeat height (cells/text-row width {} (apply str (repeat width c))))))
   :attrs  [:map]
   :preferred-size (constantly {:width w :height 1})})

(defn- recording-row-container
  "A :row container that uses `layout/allocate-1d` for width allocation
   and `layout/render-element` to recurse. Heights are passed through
   from the parent's box."
  []
  {:render (fn [attrs children {:keys [width height]} render-child]
             (let [n           (count children)
                   fixed       (mapv (fn [c] (:width (layout/child-preferred-size c))) children)
                   weights     (mapv (fn [w c]
                                       (if (zero? w)
                                         (or (get-in (second (when (vector? c) c)) [:weight]) 1)
                                         0))
                                     fixed children)
                   ;; If a child has no fixed width AND no weight, default to weight 1.
                   weights     (mapv (fn [fx wt] (if (and (zero? fx) (zero? wt)) 1 wt))
                                     fixed weights)
                   allocs      (layout/allocate-1d width fixed weights)
                   sub-grids   (mapv (fn [c w]
                                       (render-child c {:width w :height height}))
                                     children allocs)]
               (apply cells/beside sub-grids)))
   :attrs       [:map [:weights {:optional true} [:vector :int]]]
   :container?  true})

;; ──────────────────────────────────────────────────────────────────────────
;; normalize-node
;; ──────────────────────────────────────────────────────────────────────────

(deftest normalize-string-promotes-to-text
  (let [n (layout/normalize-node "hello")]
    (is (= :text (:tag n)))
    (is (= "hello" (-> n :attrs :text)))))

(deftest normalize-vector-without-attrs
  (let [n (layout/normalize-node [:foo "x" "y"])]
    (is (= :foo (:tag n)))
    (is (= {} (:attrs n)))
    (is (= ["x" "y"] (:children n)))))

(deftest normalize-vector-with-attrs
  (let [n (layout/normalize-node [:foo {:k 1} "x"])]
    (is (= :foo (:tag n)))
    (is (= {:k 1} (:attrs n)))
    (is (= ["x"] (:children n)))))

(deftest normalize-flattens-one-level-of-seq
  (let [n (layout/normalize-node [:foo (list "a" "b") "c"])]
    (is (= ["a" "b" "c"] (:children n)))))

(deftest normalize-rejects-garbage
  (is (thrown? clojure.lang.ExceptionInfo
               (layout/normalize-node 42))))

;; ──────────────────────────────────────────────────────────────────────────
;; render-element — leaf basics
;; ──────────────────────────────────────────────────────────────────────────

(deftest unknown-tag-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (layout/render-element [:nope] {:width 5 :height 1}))))

(deftest leaf-receives-attrs-and-box
  (let [seen (atom nil)
        _ (er/register-element! :probe
            {:render (fn [attrs box]
                       (reset! seen [attrs box])
                       (cells/blank (:width box) (:height box)))
             :attrs [:map]})
        _ (layout/render-element [:probe {:k 1}] {:width 4 :height 2})]
    (is (= [{:k 1} {:width 4 :height 2}] @seen))))

(deftest invalid-attrs-throws
  (er/register-element! :strict
    {:render (fn [_a _b] (cells/blank 0 0))
     :attrs  [:map [:n :int]]})
  (is (thrown? clojure.lang.ExceptionInfo
               (layout/render-element [:strict {:n "not-int"}] {:width 5 :height 1}))))

(deftest underfit-substitutes-truncation-glyph
  (let [render-called? (atom false)]
    (er/register-element! :wide
      {:render (fn [_a _b] (reset! render-called? true) (cells/blank 0 0))
       :attrs  [:map]
       :min-size {:width 10 :height 1}})
    (let [g (layout/render-element [:wide] {:width 3 :height 1})]
      (is (false? @render-called?))
      ;; truncation glyph for 3x1 box uses '…'
      (is (= "…" (:char (get-in (:cells g) [0 0]))))
      (is (= 3 (:width g))))))

(deftest leaf-output-is-clipped-to-box
  (er/register-element! :oversize
    {:render (fn [_a _b]
               ;; Returns a 10x1 grid regardless of box.
               (cells/text-row 10 {} "abcdefghij"))
     :attrs  [:map]})
  (let [g (layout/render-element [:oversize] {:width 5 :height 1})]
    (is (= 5 (:width g)))
    (is (= ["a" "b" "c" "d" "e"] (mapv :char (first (:cells g)))))))

(deftest empty-box-returns-blank
  (er/register-element! :probe
    {:render (fn [_a _b] (throw (ex-info "should not be called" {})))
     :attrs  [:map]})
  (is (= 0 (:width (layout/render-element [:probe] {:width 0 :height 5}))))
  (is (= 0 (:height (layout/render-element [:probe] {:width 5 :height 0})))))

;; ──────────────────────────────────────────────────────────────────────────
;; Container dispatch
;; ──────────────────────────────────────────────────────────────────────────

(deftest container-receives-attrs-children-box-and-recurser
  (let [seen (atom nil)]
    (er/register-element! :container-probe
      {:render (fn [attrs children box render-child]
                 (reset! seen [attrs children box (boolean render-child)])
                 (cells/blank (:width box) (:height box)))
       :attrs  [:map]
       :container? true})
    (er/register-element! :inner
      {:render (fn [_a {:keys [width height]}]
                 (cells/blank width height))
       :attrs  [:map]})
    (layout/render-element [:container-probe {:k 1} [:inner] [:inner]]
                           {:width 4 :height 2})
    (is (= [{:k 1} [[:inner] [:inner]] {:width 4 :height 2} true] @seen))))

;; ──────────────────────────────────────────────────────────────────────────
;; allocate-1d — direct unit tests
;; ──────────────────────────────────────────────────────────────────────────

(deftest allocate-zero-total
  (is (= [0 0 0] (layout/allocate-1d 0 [0 0 0] [1 1 1]))))

(deftest allocate-three-fixed-fitting-exactly
  (is (= [10 20 70] (layout/allocate-1d 100 [10 20 70] [0 0 0]))))

(deftest allocate-two-weighted-equal
  (is (= [50 50] (layout/allocate-1d 100 [0 0] [1 1]))))

(deftest allocate-two-weighted-1-3
  (is (= [25 75] (layout/allocate-1d 100 [0 0] [1 3]))))

(deftest allocate-one-fixed-one-weighted
  (is (= [30 70] (layout/allocate-1d 100 [30 0] [0 1]))))

(deftest allocate-one-fixed-two-weighted-by-weight
  ;; fixed=20, remaining=80, weights [2 3] → 32 / 48
  (is (= [20 32 48] (layout/allocate-1d 100 [20 0 0] [0 2 3]))))

(deftest allocate-fixed-sum-overflows-budget
  ;; fixed=[40 40 40], total=80 → first two get 40 each, third gets 0
  (is (= [40 40 0] (layout/allocate-1d 80 [40 40 40] [0 0 0]))))

(deftest allocate-rounding-preserves-total
  ;; total=10, weights=[1 1 1] → 4/3/3 (or some distribution summing to 10)
  (let [r (layout/allocate-1d 10 [0 0 0] [1 1 1])]
    (is (= 10 (reduce + r)))
    (is (= 3 (count r)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Container with allocator — end-to-end
;; ──────────────────────────────────────────────────────────────────────────

(deftest row-container-allocates-fixed-and-weighted
  (er/register-element! :a (fixed-width-leaf "a" 3))
  (er/register-element! :b (text-leaf "b"))
  (er/register-element! :c (text-leaf "c"))
  (er/register-element! :rrow (recording-row-container))
  (let [g (layout/render-element [:rrow [:a] [:b] [:c]] {:width 11 :height 1})
        chars (mapv :char (first (:cells g)))]
    ;; :a fixed at 3 cols → "aaa"; remaining 8 split equally between :b and :c → 4/4
    (is (= ["a" "a" "a" "b" "b" "b" "b" "c" "c" "c" "c"] chars))))

(deftest row-truncation-when-allocated-below-min
  (er/register-element! :tiny
    {:render (fn [_ _] (throw (ex-info "should not render" {})))
     :attrs  [:map]
     :min-size {:width 5 :height 1}})
  (er/register-element! :rrow (recording-row-container))
  ;; Allocate :tiny only 2 columns — below its 5-col min — should get truncation
  (let [g (layout/render-element [:rrow [:tiny]] {:width 2 :height 1})]
    ;; First cell is the truncation glyph.
    (is (= "…" (:char (get-in (:cells g) [0 0]))))))

;; ──────────────────────────────────────────────────────────────────────────
;; child-preferred-size
;; ──────────────────────────────────────────────────────────────────────────

(deftest child-preferred-size-reads-from-registry
  (er/register-element! :sized
    {:render (fn [_ _] (cells/blank 0 0))
     :attrs  [:map]
     :preferred-size (constantly {:width 7 :height 2})})
  (is (= {:width 7 :height 2} (layout/child-preferred-size [:sized]))))

(deftest child-preferred-size-defaults-on-unknown
  (is (= {:width 0 :height 0} (layout/child-preferred-size [:not-registered]))))
