(ns ai.obney.grain.tui-adapter.element-registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.tui-adapter.element-registry :as er]))

;; Each test starts with a clean registry; the prior contents (including any
;; built-ins loaded by other test namespaces) are restored afterwards. This
;; way running these tests doesn't pollute the registry for unrelated tests.

(defn- snapshot-and-restore [t]
  (let [saved @er/registry*]
    (try
      (er/clear!)
      (t)
      (finally
        (reset! er/registry* saved)))))

(use-fixtures :each snapshot-and-restore)

(def noop-render (fn [_attrs _box] {:width 0 :height 0 :cells []}))

;; ──────────────────────────────────────────────────────────────────────────
;; register-element! basic mechanics
;; ──────────────────────────────────────────────────────────────────────────

(deftest register-element-basic
  (er/register-element! :foo {:render noop-render
                              :attrs  [:map]})
  (is (some? (er/lookup :foo))))

(deftest element-info-strips-render-and-validator
  (er/register-element! :foo {:render noop-render
                              :attrs  [:map]
                              :doc    "doc"})
  (let [info (er/element-info :foo)]
    (is (= "doc" (:doc info)))
    (is (not (contains? info :render)))
    (is (not (contains? info :attrs-validator)))))

(deftest element-info-nil-for-unregistered
  (is (nil? (er/element-info :nope))))

(deftest list-elements-returns-sorted-set
  (er/register-element! :b {:render noop-render :attrs [:map]})
  (er/register-element! :a {:render noop-render :attrs [:map]})
  (er/register-element! :c {:render noop-render :attrs [:map]})
  (let [ls (er/list-elements)]
    (is (= [:a :b :c] (vec ls)))
    (is (= (sorted-set :a :b :c) ls))))

(deftest tag-must-be-keyword
  (is (thrown? clojure.lang.ExceptionInfo
               (er/register-element! "foo" {:render noop-render :attrs [:map]}))))

;; ──────────────────────────────────────────────────────────────────────────
;; Required-field validation
;; ──────────────────────────────────────────────────────────────────────────

(deftest missing-render-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (er/register-element! :foo {:attrs [:map]}))))

(deftest missing-attrs-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (er/register-element! :foo {:render noop-render}))))

(deftest invalid-malli-attrs-schema-throws-at-register-time
  ;; Malli vector schemas with a non-existent type should fail compile.
  (is (thrown? clojure.lang.ExceptionInfo
               (er/register-element! :foo {:render noop-render
                                           :attrs  [:not-a-real-type]}))))

;; ──────────────────────────────────────────────────────────────────────────
;; Defaults
;; ──────────────────────────────────────────────────────────────────────────

(deftest defaults-applied
  (er/register-element! :foo {:render noop-render :attrs [:map]})
  (let [info (er/element-info :foo)]
    (is (= {:width 1 :height 1} (:min-size info)))
    (is (false? (:focusable? info)))
    (is (= {} (:keymap info)))
    (is (true? (:stream-stable? info)))
    (is (false? (:container? info)))
    (is (fn? (:preferred-size info)))
    (is (= {:width 0 :height 0} ((:preferred-size info) {})))))

(deftest explicit-options-override-defaults
  (er/register-element! :foo {:render noop-render
                              :attrs  [:map]
                              :focusable? true
                              :stream-stable? false
                              :min-size {:width 5 :height 2}})
  (let [info (er/element-info :foo)]
    (is (true? (:focusable? info)))
    (is (false? (:stream-stable? info)))
    (is (= {:width 5 :height 2} (:min-size info)))))

;; ──────────────────────────────────────────────────────────────────────────
;; attrs-validator caching
;; ──────────────────────────────────────────────────────────────────────────

(deftest attrs-validator-compiled-once-and-stable
  (er/register-element! :foo {:render noop-render
                              :attrs  [:map [:x :int]]})
  (let [v1 (:attrs-validator (er/lookup :foo))
        v2 (:attrs-validator (er/lookup :foo))]
    (is (identical? v1 v2))
    (is (true?  (v1 {:x 1})))
    (is (false? (v1 {:x "not-int"})))))

;; ──────────────────────────────────────────────────────────────────────────
;; Sugar map form for :attrs
;; ──────────────────────────────────────────────────────────────────────────

(deftest sugar-attrs-map-rewritten-to-malli-map
  (er/register-element! :foo {:render noop-render
                              :attrs  {:data [:vector :int]
                                       :fg   {:optional? true :default :default}}})
  (let [v (:attrs-validator (er/lookup :foo))]
    (is (true?  (v {:data [1 2 3]})))
    (is (true?  (v {:data [1 2 3] :fg :red})))
    (is (false? (v {:data ["nope"]})))))

;; ──────────────────────────────────────────────────────────────────────────
;; Double-registration warns and replaces
;; ──────────────────────────────────────────────────────────────────────────

(deftest double-registration-replaces-entry
  ;; The mulog warning is best-tested via inline-publisher integration; for
  ;; unit purposes, asserting that the second registration takes effect is
  ;; the load-bearing contract.
  (er/register-element! :foo {:render noop-render :attrs [:map] :doc "v1"})
  (er/register-element! :foo {:render noop-render :attrs [:map] :doc "v2"})
  (is (= "v2" (:doc (er/element-info :foo)))))

;; ──────────────────────────────────────────────────────────────────────────
;; defelement macro expansion
;; ──────────────────────────────────────────────────────────────────────────

(deftest defelement-macro-expands-to-register-element-call
  (let [expanded (macroexpand-1 '(ai.obney.grain.tui-adapter.element-registry/defelement
                                   :foo {:render f :attrs [:map]}))]
    ;; The macro should be a call to register-element! with the same args.
    (is (seq? expanded))
    (is (= 'ai.obney.grain.tui-adapter.element-registry/register-element!
           (first expanded)))
    (is (= :foo (second expanded)))))

(deftest defelement-macro-actually-registers
  (er/defelement :sugar-test
    {:render noop-render
     :attrs  [:map]
     :doc    "via macro"})
  (is (= "via macro" (:doc (er/element-info :sugar-test)))))

;; ──────────────────────────────────────────────────────────────────────────
;; clear! / unregister!
;; ──────────────────────────────────────────────────────────────────────────

(deftest unregister-removes-tag
  (er/register-element! :foo {:render noop-render :attrs [:map]})
  (er/unregister! :foo)
  (is (nil? (er/element-info :foo))))

(deftest clear-removes-everything
  (er/register-element! :foo {:render noop-render :attrs [:map]})
  (er/register-element! :bar {:render noop-render :attrs [:map]})
  (er/clear!)
  (is (= #{} (er/list-elements))))
