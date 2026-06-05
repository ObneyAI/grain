(ns ai.obney.grain.datastar.ui-test
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.datastar.core :as ds]
            [ai.obney.grain.datastar.ui :as ui]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas datastar-ui-test-schemas
  {:ui-test/complete-task [:map [:task-id :uuid]]
   :ui-test/create-campus [:map
                           [:campus-name :string]
                           [:is-virtual :boolean]
                           [:state {:optional true} :string]]
   :ui-test/select-plan [:map
                         [:application-id :uuid]
                         [:duration-weeks :int]
                         [:deposit-amount-cents :int]]
   :ui-test/optional-command [:map
                              [:required-id :uuid]
                              [:note {:optional true} :string]]})

(defn- attrs
  [node]
  (second node))

(deftest hiccup-returns-plain-hiccup
  (let [task-id #uuid "00000000-0000-0000-0000-000000000001"
        out (ui/hiccup
             [:button {:class "btn"
                       :on/click (ui/dispatch :ui-test/complete-task
                                   {:task-id task-id})}
              "Complete"])]
    (is (= :button (first out)))
    (is (= "btn" (:class (attrs out))))
    (is (contains? (attrs out) :data-on:click))
    (is (string/includes? (:data-on:click (attrs out)) "@post($__grainAction"))
    (is (string/includes? (:data-on:click (attrs out)) "\"command/name\": \"ui-test/complete-task\""))
    (is (string/includes? (:data-on:click (attrs out)) "\"task-id\": \"00000000-0000-0000-0000-000000000001\""))
    (is (= "Complete" (nth out 2)))
    (is (string/includes? (ds/render-html out) "data-on:click"))))

(deftest ir-preserves-meaning-before-lowering
  (let [source (ui/with-signal-scope {:key #uuid "00000000-0000-0000-0000-000000000010"
                                      :prefix "plan"}
                 (ui/with-signals [duration (ui/signal {:name "duration-weeks" :init 12 :type :int})]
                   [:input {:class "input"
                            :bind/value duration
                            :on/input (ui/set-signal! duration (ui/num duration))}]))
        ir-node (ui/ir source)
        signal (first (:signals ir-node))]
    (is (= :element (:op ir-node)))
    (is (= :input (:tag ir-node)))
    (is (= "duration-weeks" (:semantic-name signal)))
    (is (string/starts-with? (:resolved-name signal) "plan-duration-weeks__"))
    (is (= :int (:type signal)))
    (is (= :value (ffirst (:bindings ir-node))))
    (is (= :input (ffirst (:events ir-node))))))

(deftest automatic-signal-scope-is-deterministic-and-bracket-safe
  (let [scope {:key #uuid "00000000-0000-0000-0000-000000000011" :prefix "plan"}
        make-node #(ui/with-signal-scope scope
                     (ui/with-signals [duration (ui/signal {:name "duration-weeks" :init 12})]
                       [:input {:bind/value duration
                                :bind/text (ui/sig duration)}]))
        out-a (ui/hiccup (make-node))
        out-b (ui/hiccup (make-node))
        signals-a (:data-signals (attrs out-a))
        bind-a (:data-bind (attrs out-a))
        text-a (:data-text (attrs out-a))]
    (is (= out-a out-b))
    (is (string/includes? signals-a "plan-duration-weeks__"))
    (is (= bind-a (re-find #"plan-duration-weeks__[a-z0-9]+" signals-a)))
    (is (= (str "$[\"" bind-a "\"]") text-a))))

(deftest raw-datastar-attrs-pass-through
  (let [out (ui/hiccup
             [:div {"data-signals__ifmissing" "{'open': false}"
                    "data-on-signal-patch__filter-key__fieldErrors" "console.log($fieldErrors)"
                    "data-effect" "window.__effect && window.__effect(el)"
                    "data-indicator" "__submitting"
                    "data-attr:disabled" "$__submitting"
                    :data-ignore-morph true
                    :data-init "window.__initRichtext(el)"
                    :data-on:input__debounce.500ms "@post('/custom')"}
              "x"])
        a (attrs out)]
    (is (= "{'open': false}" (get a "data-signals__ifmissing")))
    (is (= "console.log($fieldErrors)" (get a "data-on-signal-patch__filter-key__fieldErrors")))
    (is (= "window.__effect && window.__effect(el)" (get a "data-effect")))
    (is (= "__submitting" (get a "data-indicator")))
    (is (= "$__submitting" (get a "data-attr:disabled")))
    (is (true? (:data-ignore-morph a)))
    (is (= "window.__initRichtext(el)" (:data-init a)))
    (is (= "@post('/custom')" (:data-on:input__debounce.500ms a)))))

(deftest generated-signals-do-not-rewrite-raw-data-signals
  (let [out (ui/hiccup
             (ui/with-signals [open? (ui/signal {:init false})]
               [:div {:data-signals "{'raw-open': false}"
                      :bind/show open?}
                "Modal"]))
        a (attrs out)]
    (is (= "{'raw-open': false}" (:data-signals a)))
    (is (string/includes? (get a "data-signals__ifmissing") "\"open?\":false"))))

(deftest payload-dispatch-supports-custom-action-target
  (let [out (ui/hiccup
             (ui/with-signals [name (ui/signal {:name "campus-name" :init ""})
                               virtual? (ui/signal {:name "is-virtual" :init false})]
               [:form {:on/submit (ui/dispatch :ui-test/create-campus
                                    {:campus-name name
                                     :is-virtual virtual?}
                                    {:post "/actions"})}
                [:input {:bind/value name}]
                [:input {:type "checkbox" :bind/value virtual?}]]))
        submit (:data-on:submit (attrs out))]
    (is (string/includes? submit "@post(\"/actions\", {payload:"))
    (is (string/includes? submit "\"command/name\": \"ui-test/create-campus\""))
    (is (string/includes? submit "\"campus-name\": $[\"campus-name\""))
    (is (string/includes? submit "\"is-virtual\": $[\"is-virtual\""))
    (is (not (string/includes? submit "$['command/name']")))
    (is (not (string/includes? submit "$[\"command/name\"]")))))

(deftest refresh-posts-explicit-query-payload
  (let [out (ui/hiccup
             (ui/with-signals [page (ui/signal {:init 1})
                               search (ui/signal {:init ""})
                               unrelated (ui/signal {:init "client-only"})]
               [:button {:on/click (ui/refresh "/admin/graduation-pending/__stream"
                                    {:page page
                                     :search search})}
                "Refresh"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "@post(\"/admin/graduation-pending/__stream\", {payload:"))
    (is (string/includes? click "\"page\": $page"))
    (is (string/includes? click "\"search\": $search"))
    (is (string/includes? click "\"dsNonce\": $dsNonce"))
    (is (not (string/includes? click "client-only")))
    (is (not (string/includes? click "unrelated")))))

(deftest refresh-can-omit-reusable-stream-nonce
  (let [out (ui/hiccup
             (ui/with-signals [page (ui/signal {:init 1})]
               [:button {:on/click (ui/refresh "/one-shot/__stream"
                                    {:page page}
                                    {:include-nonce? false})}
                "Refresh"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "@post(\"/one-shot/__stream\", {payload:"))
    (is (string/includes? click "\"page\": $page"))
    (is (not (string/includes? click "dsNonce")))))

(deftest dispatch-schema-validation
  (testing "missing required command key fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing required command keys"
         (ui/hiccup
          [:button {:on/click (ui/dispatch :ui-test/complete-task {})}
           "bad"]))))
  (testing "unknown command key fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"keys not present"
         (ui/hiccup
          [:button {:on/click (ui/dispatch :ui-test/complete-task
                               {:task-id #uuid "00000000-0000-0000-0000-000000000001"
                                :extra "nope"})}
           "bad"]))))
  (testing "optional command key may be omitted"
    (is (some?
         (ui/hiccup
          [:button {:on/click (ui/dispatch :ui-test/optional-command
                               {:required-id #uuid "00000000-0000-0000-0000-000000000002"})}
           "ok"])))))

(deftest expressions-and-effects-lower
  (let [out (ui/hiccup
             (ui/with-signals [title (ui/signal {:init "Old"})]
               [:input {:bind/value title
                        :on/blur (ui/when! (ui/changed? title "Old")
                                   (ui/dispatch :ui-test/create-campus
                                     {:campus-name (ui/trimmed title)
                                      :is-virtual false}))
                        :on/keydown (ui/on-keys {:Enter (ui/blur!)
                                                 :Escape (ui/do!
                                                          (ui/reset! title)
                                                          (ui/blur!))})}]))
        a (attrs out)]
    (is (string/includes? (:data-on:blur a) ".trim()"))
    (is (string/includes? (:data-on:blur a) "if ("))
    (is (string/includes? (:data-on:keydown a) "evt.key === \"Enter\""))
    (is (string/includes? (:data-on:keydown a) "evt.key === \"Escape\""))
    (is (string/includes? (:data-on:keydown a) "el.blur();"))))

(deftest static-interpretation-removes-checked-behavior
  (let [ir-node (ui/ir
                 [:a {:href "/task"
                      :class "link"
                      :on/click (ui/dispatch :ui-test/complete-task
                                  {:task-id #uuid "00000000-0000-0000-0000-000000000001"})
                      :data-on:input__debounce.500ms "@post('/raw')"}
                  "Task"])
        static-node (ui/static ir-node {:strip-href? true :strip-raw-events? true})
        a (attrs static-node)]
    (is (= :a (first static-node)))
    (is (= "link" (:class a)))
    (is (not (contains? a :href)))
    (is (not (contains? a :data-on:click)))
    (is (not (contains? a :data-on:input__debounce.500ms)))
    (is (= "Task" (nth static-node 2)))))
