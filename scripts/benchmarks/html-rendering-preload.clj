;; Verify the conservative guard when an upstream category was overridden
;; before Grain was loaded. Run separately from the Polylith test JVM.
(require '[hiccup.compiler :as compiler] '[hiccup2.core :as hiccup])
(def kind (keyword (or (first *command-line-args*) "vector")))
(def category (case kind :vector clojure.lang.IPersistentVector :sequence clojure.lang.ISeq))
(def calls (atom 0))
(extend category compiler/HtmlRenderer
        {:render-html (fn [_] (swap! calls inc) "<preload-extension/>")})
(require '[ai.obney.grain.datastar.core :as datastar])
(def value (case kind :vector [:div "custom"] :sequence (list [:div "custom"])))
(def baseline (str (hiccup/html value)))
(def actual (datastar/render-html value))
(assert (= baseline "<preload-extension/>" actual))
(assert (= 2 @calls))
(prn {:category kind :parity true :custom-dispatches @calls})
(shutdown-agents)
