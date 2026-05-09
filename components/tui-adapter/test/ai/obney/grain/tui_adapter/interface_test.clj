(ns ai.obney.grain.tui-adapter.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.interface :as iface]
            [ai.obney.grain.tui-adapter.session :as session]))

;; ──────────────────────────────────────────────────────────────────────────
;; Element registration via interface
;; ──────────────────────────────────────────────────────────────────────────

(deftest interface-defelement-registers
  (iface/defelement :iface-test/sparkline
    {:doc    "Test element"
     :attrs  [:map]
     :render (fn [_ _] {:width 0 :height 0 :cells []})})
  (is (some? (iface/element-info :iface-test/sparkline))))

(deftest list-elements-includes-builtins
  ;; Built-ins are loaded by requiring the interface.
  (let [tags (iface/list-elements)]
    (is (contains? tags :text))
    (is (contains? tags :row))
    (is (contains? tags :col))
    (is (contains? tags :box))))

(deftest element-info-strips-render
  (let [info (iface/element-info :text)]
    (is (not (contains? info :render)))
    (is (not (contains? info :attrs-validator)))))

;; ──────────────────────────────────────────────────────────────────────────
;; CellGrid re-exports
;; ──────────────────────────────────────────────────────────────────────────

(deftest cellgrid-helpers-available
  (is (= 1 (:width (iface/blank 1 1))))
  (is (= 3 (:width (iface/text-row 3 {} "abc"))))
  (is (= 6 (:width (iface/beside (iface/blank 3 1) (iface/blank 3 1))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Session registry helpers
;; ──────────────────────────────────────────────────────────────────────────

(defn- mk-session-with-screen [query-id tenant-id user-id]
  (let [s (session/make-session
            {:tenant-id    tenant-id
             :user-id      user-id
             :viewport     {:width 5 :height 1}
             :on-output    (fn [_])
             :default-screen {:query-id query-id :inputs {}}
             :process-query-fn (fn [_] {:query/result {}})})]
    s))

(deftest sessions-on-screen-filters-by-query-id
  (let [registry {:sessions-atom (atom {})}
        s1 (mk-session-with-screen :foo (random-uuid) :u1)
        s2 (mk-session-with-screen :bar (random-uuid) :u2)
        s3 (mk-session-with-screen :foo (random-uuid) :u3)]
    (swap! (:sessions-atom registry) assoc :s1 s1 :s2 s2 :s3 s3)
    (is (= 2 (count (iface/sessions-on-screen registry :foo))))
    (is (= 1 (count (iface/sessions-on-screen registry :bar))))
    (is (= 0 (count (iface/sessions-on-screen registry :nope))))))

(deftest sessions-for-tenant
  (let [t1 (random-uuid)
        t2 (random-uuid)
        registry {:sessions-atom (atom {})}
        s1 (mk-session-with-screen :foo t1 :u1)
        s2 (mk-session-with-screen :foo t1 :u2)
        s3 (mk-session-with-screen :foo t2 :u3)]
    (swap! (:sessions-atom registry) assoc :s1 s1 :s2 s2 :s3 s3)
    (is (= 2 (count (iface/sessions-for-tenant registry t1))))
    (is (= 1 (count (iface/sessions-for-tenant registry t2))))))

(deftest sessions-for-user
  (let [registry {:sessions-atom (atom {})}
        s1 (mk-session-with-screen :foo (random-uuid) :u1)
        s2 (mk-session-with-screen :foo (random-uuid) :u1)
        s3 (mk-session-with-screen :foo (random-uuid) :u2)]
    (swap! (:sessions-atom registry) assoc :s1 s1 :s2 s2 :s3 s3)
    (is (= 2 (count (iface/sessions-for-user registry :u1))))
    (is (= 1 (count (iface/sessions-for-user registry :u2))))))

(deftest refresh-screen-pushes-onto-input-ch
  (let [s (mk-session-with-screen :foo (random-uuid) :u1)]
    (iface/refresh-screen! s)
    ;; A synthetic event is now in the input channel — read it back.
    (let [ev (clojure.core.async/poll! (:input-ch @s))]
      (is (= :synthetic (:type ev)))
      (is (= :refresh   (:reason ev))))))

(deftest broadcast-toast-installs-overlay
  (let [s (mk-session-with-screen :foo (random-uuid) :u1)]
    (iface/broadcast-toast! s {:message "hi" :level :info :ttl-ms 5000})
    (is (= :toast (:type (:overlay @s))))
    (session/stop! s)))
