(ns ai.obney.grain.datastar.html-rendering-test
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as hiccup]
            [hiccup.compiler :as compiler]
            [hiccup.util :as util]
            [ai.obney.grain.datastar.interface :as datastar]))

(deftype CustomHtml [text]
  compiler/HtmlRenderer
  (render-html [_] (str "<custom>" text "</custom>")))

(defn original-html [value] (str (hiccup/html value)))

(def rendering-cases
  [nil "&<>\"'" :qualified/name 'qualified/name 12 true false
   (util/raw-string "<strong>raw & content</strong>") (CustomHtml. "direct")
   [:div] [:br] [:br {}] [:br nil] [:br "content"] [:div {} ()]
   [:svg [:path]]
   [:div#identity.first.second {:id "override" :class ["third" :fourth]
                              :title "&<>\"'" :hidden true :disabled false
                              :style {:z-index 1 :background "<&"}}
    "\u0000\u00e9\u20ac\ud83d\ude00\ud800\udfff" [:span "child"]]
   [:a {:href (java.net.URI. "/relative") :data-values [1 :x nil]} "Link"]
   (list [:span "one"] nil [:span "two"])
   [:main (map (fn [n] [:p {:data-n n} n]) (range 5))]])

(deftest upstream-html-parity
  (doseq [mode [:html :xhtml :xml :sgml] escape? [true false]]
    (binding [util/*html-mode* mode util/*escape-strings?* escape?
              util/*base-url* "https://example.test/base/"]
      (doseq [value rendering-cases]
        (is (= (original-html value) (datastar/render-html value))
            (str mode " escape=" escape? " " (type value)))))))

(deftest lazy-content-is-consumed-once-and-in-order
  (let [seen (atom [])
        value [:div (map (fn [n] (swap! seen conj n) [:span n]) (range 10))]]
    (is (= (original-html [:div (map #(vector :span %) (range 10))])
           (datastar/render-html value)))
    (is (= (vec (range 10)) @seen))))

(deftest errors-preserve-upstream-exceptions-and-bindings
  (doseq [value [[42 "invalid"] [:div {:style {:x (Object.)}} [false]]]]
    (let [failure (fn [f] (try (f value) nil (catch Throwable e [(class e) (.getMessage e)])))]
      (is (= (failure original-html) (failure datastar/render-html)))))
  (is (= "<div>after &amp; failure</div>"
         (datastar/render-html [:div "after & failure"]))))

(deftest existing-and-later-custom-protocol-dispatch-remains-authoritative
  (testing "Direct implementations use their own renderer"
    (is (= "<custom>direct</custom>" (datastar/render-html (CustomHtml. "direct")))))
  (doseq [[type value] [[clojure.lang.PersistentVector [:div "vector"]]
                      [clojure.lang.PersistentList (list [:div "sequence"])]
                      [(class (next (rest [:div {:id "x"} "child"])))
                       [:div {:id "x"} "child"]]]]
    (let [old (get (:impls compiler/HtmlRenderer) type)]
      (try
        (extend type compiler/HtmlRenderer {:render-html (fn [_] "<extension/>")})
        (is (= (original-html value) (datastar/render-html value)))
        (finally
          ;; Removing a temporary concrete extension requires restoring the
          ;; protocol map and method caches, rather than leaving an empty impl.
          (alter-var-root #'compiler/HtmlRenderer update :impls
                          #(if old (assoc % type old) (dissoc % type)))
          (#'clojure.core/-reset-methods compiler/HtmlRenderer)))))
  (doseq [[type value] [[clojure.lang.IPersistentVector [:div "overridden"]]
                      [clojure.lang.ISeq [:div {:id "x"} "child"]]]]
    (let [old (get (:impls compiler/HtmlRenderer) type)]
      (try
        (extend type compiler/HtmlRenderer {:render-html (fn [_] "<generic-extension/>")})
        (is (= (original-html value) (datastar/render-html value)))
        (finally (extend type compiler/HtmlRenderer old))))))

(deftype CustomValue [value]
  compiler/HtmlRenderer
  (render-html [_] value))

(deftest custom-values-preserve-upstream-contextual-coercion
  (doseq [returned [nil false 42 (StringBuilder. "custom")]]
    (let [value (CustomValue. returned)]
      (doseq [tree [value (list value) [:div value]]]
        (is (= (original-html tree) (datastar/render-html tree)))))))

(deftest errors-stop-lazy-content-before-later-side-effects
  (let [run (fn [render]
              (let [seen (atom [])
                    content (lazy-seq
                              (cons [42 "invalid"]
                                    (lazy-seq (swap! seen conj :later)
                                              (list [:span "later"]))))]
                (try (render [:div content]) (catch IllegalArgumentException _))
                @seen))]
    (is (= [] (run original-html) (run datastar/render-html)))))

(deftype SwitchRenderer []
  compiler/HtmlRenderer
  (render-html [_]
    (extend clojure.lang.IPersistentVector compiler/HtmlRenderer
            {:render-html (fn [_] "<changed/>")})
    "<switch/>"))

(deftest a-mid-tree-extension-invalidates-only-this-renders-dispatch-cache
  (let [old (get (:impls compiler/HtmlRenderer) clojure.lang.IPersistentVector)
        run (fn [render]
              (try
                (render [:div [:p "first"] (SwitchRenderer.) [:p "second"]])
                (finally (extend clojure.lang.IPersistentVector compiler/HtmlRenderer old))))]
    (is (= "<div><p>first</p><switch/><changed/></div>"
           (run original-html) (run datastar/render-html)))))
