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
   :ui-test/submit-document [:map
                             [:document :map]
                             [:ordered :vector]
                             [:choices :set]
                             [:nil-value nil?]
                             [:flag? :boolean]
                             [:quantity :int]]
   :ui-test/optional-command [:map
                              [:required-id :uuid]
                              [:note {:optional true} :string]]})

(defn- attrs
  [node]
  (second node))

(defn- data-signal-keys
  [node]
  (->> (:data-signals (attrs node))
       (re-seq #"\"([^\"]+)\":")
       (mapv second)))

(def test-query-registry
  {:ui-test/search-page {:handler-fn identity
                         :authorized? (constantly true)
                         :datastar/path "/search"}
   :ui-test/changed-page {:handler-fn identity
                          :authorized? (constantly true)
                          :datastar/path "/changed"}
   :ui-test/graduation-pending-page {:handler-fn identity
                                     :authorized? (constantly true)
                                     :datastar/path "/admin/graduation-pending"}
   :ui-test/one-shot-page {:handler-fn identity
                           :authorized? (constantly true)
                           :datastar/path "/one-shot"}
   :ui-test/student-detail-page {:handler-fn identity
                                 :authorized? (constantly true)
                                 :datastar/path "/admin/students/:student-id"}})

(defn- hiccup
  [source]
  (ui/hiccup source {:query-registry test-query-registry}))

(deftest hiccup-returns-plain-hiccup
  (let [task-id #uuid "00000000-0000-0000-0000-000000000001"
        out (hiccup
             [:button {:class "btn"
                       :on/click {:effect (ui/dispatch :ui-test/complete-task
                                            {:task-id task-id})}}
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
                 (ui/with-signals [duration {:name "duration-weeks" :init 12}]
                   [:input {:class "input"
                            :bind/value duration
                            :on/input {:effect (ui/set-signal duration (ui/num duration))}}]))
        ir-node (ui/ir source)
        signal (first (:signals ir-node))]
    (is (= :element (:op ir-node)))
    (is (= :input (:tag ir-node)))
    (is (= "duration-weeks" (:semantic-name signal)))
    (is (string/starts-with? (:resolved-name signal) "plan-duration-weeks__"))
    (is (= 12 (:init signal)))
    (is (= :value (ffirst (:bindings ir-node))))
    (is (= :input (ffirst (:events ir-node))))))

(deftest automatic-signal-scope-is-deterministic-and-bracket-safe
  (let [scope {:key #uuid "00000000-0000-0000-0000-000000000011" :prefix "plan"}
        make-node #(ui/with-signal-scope scope
                     (ui/with-signals [duration {:name "duration-weeks" :init 12}]
                       [:input {:bind/value duration
                                :bind/text duration}]))
        out-a (hiccup (make-node))
        out-b (hiccup (make-node))
        signals-a (:data-signals (attrs out-a))
        bind-a (:data-bind (attrs out-a))
        text-a (:data-text (attrs out-a))]
    (is (= out-a out-b))
    (is (string/includes? signals-a "plan-duration-weeks__"))
    (is (= bind-a (re-find #"plan-duration-weeks__[a-z0-9]+" signals-a)))
    (is (= (str "$[\"" bind-a "\"]") text-a))))

(deftest unscoped-signals-get-automatic-compiler-scopes
  (let [make-node #(ui/with-signals [query {:init ""}]
                     [:input {:bind/value query
                              :bind/text query}])
        out-a (ui/hiccup (make-node))
        out-b (ui/hiccup (make-node))
        signal-name (first (data-signal-keys out-a))]
    (is (= out-a out-b))
    (is (string/starts-with? signal-name "query__"))
    (is (= signal-name (:data-bind (attrs out-a))))
    (is (= (ui/signal-ref signal-name) (:data-text (attrs out-a))))))

(deftest sibling-signal-scopes-do-not-collide
  (let [out (hiccup
             [:div
              (ui/with-signals [query {:init ""}]
                [:input {:bind/value query}])
              (ui/with-signals [query {:init ""}]
                [:input {:bind/value query}])])
        left (nth out 2)
        right (nth out 3)
        left-name (first (data-signal-keys left))
        right-name (first (data-signal-keys right))]
    (is (string/starts-with? left-name "query__"))
    (is (string/starts-with? right-name "query__"))
    (is (not= left-name right-name))
    (is (= left-name (:data-bind (attrs left))))
    (is (= right-name (:data-bind (attrs right))))))

(deftest parent-signal-scope-applies-to-child-references
  (let [out (hiccup
             (ui/with-signals [query {:init ""}]
               [:div
                [:input {:bind/value query}]
                [:button {:on/click {:effect (ui/refresh :ui-test/search-page
                                                {:q query})}}
                 "Search"]]))
        signal-name (first (data-signal-keys out))
        input (nth out 2)
        button (nth out 3)
        click (:data-on:click (attrs button))]
    (is (= signal-name (:data-bind (attrs input))))
    (is (string/includes? click (str "\"q\": " (ui/signal-ref signal-name))))))

(deftest nested-signals-shadow-by-lexical-handle
  (let [out (hiccup
             (ui/with-signals [open? {:init false}]
               [:div
                [:input {:bind/value open?}]
                (ui/with-signals [open? {:init true}]
                  [:input {:bind/value open?}])
                [:input {:bind/value open?}]]))
        parent-name (first (data-signal-keys out))
        first-input (nth out 2)
        nested-input (nth out 3)
        last-input (nth out 4)
        nested-name (first (data-signal-keys nested-input))]
    (is (string/starts-with? parent-name "open?__"))
    (is (string/starts-with? nested-name "open?__"))
    (is (not= parent-name nested-name))
    (is (= parent-name (:data-bind (attrs first-input))))
    (is (= nested-name (:data-bind (attrs nested-input))))
    (is (= parent-name (:data-bind (attrs last-input))))))

(deftest signal-resolution-covers-checked-effect-and-expression-trees
  (let [out (hiccup
             (ui/with-signals [title {:init "Old"}]
               [:input {:bind/value title
                        :bind/text (ui/js "String(" title ")")
                        :on/input {:effect (ui/effects
                                             (ui/set-signal title (ui/trimmed title))
                                             (ui/when-effect (ui/present? title)
                                               (ui/dispatch :ui-test/create-campus
                                                 {:campus-name title
                                                  :is-virtual false}))
                                             (ui/choose-effect (ui/changed? title "Old")
                                               (ui/refresh :ui-test/changed-page
                                                 {:title title})
                                               (ui/reset-signal title)))}
                        :on/keydown {:effect (ui/on-keys {:Escape (ui/reset-signal title)})}}]))
        signal-name (first (data-signal-keys out))
        a (attrs out)
        input (:data-on:input a)
        keydown (:data-on:keydown a)]
    (is (string/includes? (:data-text a) (ui/signal-ref signal-name)))
    (is (string/includes? input (str (ui/signal-ref signal-name)
                                     " = "
                                     (ui/signal-ref signal-name)
                                     ".trim();")))
    (is (string/includes? input (str "\"campus-name\": " (ui/signal-ref signal-name))))
    (is (string/includes? input (str "\"title\": " (ui/signal-ref signal-name))))
    (is (string/includes? keydown (str (ui/signal-ref signal-name) " = \"Old\";")))))

(deftest raw-datastar-attrs-pass-through
  (let [out (hiccup
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

(deftest checked-events-require-explicit-event-maps
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"must use a map with :effect"
       (ui/hiccup
        [:button {:on/click (ui/action "$open = true;")}
         "bad"])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"must include :effect"
       (ui/hiccup
        [:button {:on/click {:modifiers {:prevent true}}}
         "bad"])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"may only contain :effect and :modifiers"
       (ui/hiccup
        [:button {:on/click {:effect (ui/action "$open = true;")
                             :target :modal}}
         "bad"]))))

(deftest checked-event-modifiers-lower-generically
  (let [debounced (ui/hiccup
                   [:input {:on/input {:effect (ui/action "@post('/search')")
                                       :modifiers {:debounce "300ms"}}}])
        submit (ui/hiccup
                [:form {:on/submit {:effect (ui/action "@post('/save')")
                                    :modifiers {:prevent true}}}])
        keydown (ui/hiccup
                 [:input {:on/keydown {:effect (ui/action "$open = false;")
                                       :modifiers {:window true}}}])
        click (ui/hiccup
               [:button {:on/click {:effect (ui/action "$open = false;")
                                    :modifiers {:stop true}}}
                "Close"])]
    (is (= "@post('/search')" (:data-on:input__debounce.300ms (attrs debounced))))
    (is (= "@post('/save')" (:data-on:submit__prevent (attrs submit))))
    (is (= "$open = false;" (:data-on:keydown__window (attrs keydown))))
    (is (= "$open = false;" (:data-on:click__stop (attrs click))))))

(deftest checked-event-modifier-validation
  (testing "false and nil modifiers are omitted"
    (let [out (ui/hiccup
               [:button {:on/click {:effect (ui/action "$open = true;")
                                    :modifiers {:prevent false
                                                :stop nil}}}
                "Open"])]
      (is (= "$open = true;" (:data-on:click (attrs out))))
      (is (not (contains? (attrs out) :data-on:click__prevent)))
      (is (not (contains? (attrs out) :data-on:click__stop)))))
  (testing "modifier output is deterministic by lowered name"
    (let [out (ui/hiccup
               [:input {:on/input {:effect (ui/action "@post('/search')")
                                   :modifiers {:prevent true
                                               :debounce "300ms"}}}])]
      (is (= "@post('/search')"
             (:data-on:input__debounce.300ms__prevent (attrs out))))))
  (testing "duplicate lowered modifier names fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"duplicate lowered names"
         (ui/hiccup
          [:button {:on/click {:effect (ui/action "$open = true;")
                               :modifiers {:mod/prevent true
                                           :prevent true}}}
           "bad"]))))
  (testing "invalid modifier map fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"must be a map"
         (ui/hiccup
          [:button {:on/click {:effect (ui/action "$open = true;")
                               :modifiers [[:prevent true]]}}
           "bad"]))))
  (testing "invalid modifier names fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"modifier names"
         (ui/hiccup
          [:button {:on/click {:effect (ui/action "$open = true;")
                               :modifiers {"" true}}}
           "bad"]))))
  (testing "invalid modifier values fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"modifier values"
         (ui/hiccup
          [:button {:on/click {:effect (ui/action "$open = true;")
                               :modifiers {:prevent []}}}
           "bad"])))))

(deftest with-signals-requires-option-maps
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Signal options must be a map"
       (ui/hiccup
        (ui/with-signals [open? false]
          [:div {:bind/show open?}
           "Modal"])))))

(deftest generated-signals-do-not-rewrite-raw-data-signals
  (let [out (ui/hiccup
             (ui/with-signals [open? {:init false}]
               [:div {:data-signals "{'raw-open': false}"
                      :bind/show open?}
                "Modal"]))
        a (attrs out)]
    (is (= "{'raw-open': false}" (:data-signals a)))
    (is (string/includes? (get a "data-signals__ifmissing") "\"open?__"))
    (is (string/includes? (get a "data-signals__ifmissing") "\":false"))))

(deftest payload-dispatch-supports-custom-action-target
  (let [out (ui/hiccup
             (ui/with-signals [name {:name "campus-name" :init ""}
                               virtual? {:name "is-virtual" :init false}]
               [:form {:on/submit {:effect (ui/dispatch :ui-test/create-campus
                                              {:campus-name name
                                               :is-virtual virtual?})}}
                [:input {:bind/value name}]
                [:input {:type "checkbox" :bind/value virtual?}]]))
        submit (:data-on:submit (attrs out))]
    (is (string/includes? submit "@post($__grainAction, {payload:"))
    (is (string/includes? submit "\"command/name\": \"ui-test/create-campus\""))
    (is (re-find #"\"campus-name\": \$\[\"campus-name__[a-z0-9]+\"\]" submit))
    (is (re-find #"\"is-virtual\": \$\[\"is-virtual__[a-z0-9]+\"\]" submit))
    (is (not (string/includes? submit "$['command/name']")))
    (is (not (string/includes? submit "$[\"command/name\"]")))))

(deftest refresh-posts-explicit-query-payload
  (let [out (hiccup
             (ui/with-signals [page {:init 1}
                               search {:init ""}
                               unrelated {:init "client-only"}]
               [:button {:on/click {:effect (ui/refresh :ui-test/graduation-pending-page
                                             {:page page
                                              :search search})}}
                "Refresh"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "@post(\"/admin/graduation-pending/__stream\", {payload:"))
    (is (re-find #"\"page\": \$page__[a-z0-9]+" click))
    (is (re-find #"\"search\": \$search__[a-z0-9]+" click))
    (is (string/includes? click "\"dsNonce\": $dsNonce"))
    (is (not (string/includes? click "client-only")))
    (is (not (string/includes? click "unrelated")))))

(deftest refresh-can-omit-reusable-stream-nonce
  (let [out (hiccup
             (ui/with-signals [page {:init 1}]
               [:button {:on/click {:effect (ui/refresh :ui-test/one-shot-page
                                             {:page page}
                                             {:include-nonce? false})}}
                "Refresh"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "@post(\"/one-shot/__stream\", {payload:"))
    (is (re-find #"\"page\": \$page__[a-z0-9]+" click))
    (is (not (string/includes? click "dsNonce")))))

(deftest dispatch-lowers-nested-payload-data
  (let [document-id #uuid "00000000-0000-0000-0000-000000000111"
        field-id #uuid "00000000-0000-0000-0000-000000000222"
        out (hiccup
             (ui/with-signals [signer-name {:init ""}
                               signer-email {:init ""}
                               field-value {:init ""}]
               [:button {:on/click
                         {:effect
                          (ui/dispatch :ui-test/submit-document
                            {:document {:id document-id
                                        :status :draft
                                        :signer {:name signer-name
                                                 :email signer-email}
                                        :fields [{:id field-id
                                                  :value (ui/trimmed field-value)}
                                                 {:id "static"
                                                  :value "ok"}]}
                             :ordered (list signer-name "literal" 7)
                             :choices #{"email" "sms"}
                             :nil-value nil
                             :flag? true
                             :quantity (ui/num field-value)})}}
                "Submit"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "\"command/name\": \"ui-test/submit-document\""))
    (is (string/includes? click "\"document\": {"))
    (is (string/includes? click "\"id\": \"00000000-0000-0000-0000-000000000111\""))
    (is (string/includes? click "\"status\": \"draft\""))
    (is (string/includes? click "\"signer\": {"))
    (is (re-find #"\"name\": \$\[\"signer-name__[a-z0-9]+\"\]" click))
    (is (re-find #"\"email\": \$\[\"signer-email__[a-z0-9]+\"\]" click))
    (is (string/includes? click "\"fields\": ["))
    (is (string/includes? click "\"id\": \"00000000-0000-0000-0000-000000000222\""))
    (is (re-find #"\"value\": \$\[\"field-value__[a-z0-9]+\"\]\.trim\(\)" click))
    (is (string/includes? click "\"ordered\": ["))
    (is (string/includes? click "\"literal\""))
    (is (string/includes? click "\"choices\": ["))
    (is (string/includes? click "\"email\""))
    (is (string/includes? click "\"sms\""))
    (is (string/includes? click "\"nil-value\": null"))
    (is (string/includes? click "\"flag?\": true"))
    (is (re-find #"\"quantity\": Number\(\$\[\"field-value__[a-z0-9]+\"\]\)" click))))

(deftest refresh-lowers-nested-payload-data
  (let [out (hiccup
             (ui/with-signals [page {:init 1}
                               search {:init ""}]
               [:button {:on/click
                         {:effect
                          (ui/refresh :ui-test/graduation-pending-page
                            {:filters {:search search
                                       :page page}
                             :include ["students" "documents"]})}}
                "Refresh"]))
        click (:data-on:click (attrs out))]
    (is (string/includes? click "@post(\"/admin/graduation-pending/__stream\", {payload:"))
    (is (re-find #"\"filters\": \{\"search\": \$search__[a-z0-9]+, \"page\": \$page__[a-z0-9]+\}" click))
    (is (string/includes? click "\"include\": [\"students\", \"documents\"]"))
    (is (string/includes? click "\"dsNonce\": $dsNonce"))))

(deftest payload-map-keys-must-be-static
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Payload map keys must be static literal values"
       (hiccup
        (ui/with-signals [dynamic-key {:init "name"}]
          [:button {:on/click {:effect (ui/refresh :ui-test/search-page
                                        {dynamic-key "bad"})}}
           "bad"])))))

(deftest checked-route-refs-resolve-from-query-metadata
  (let [out (hiccup
             [:div
              [:a {:href (ui/href :ui-test/student-detail-page
                         {:path-params {:student-id #uuid "00000000-0000-0000-0000-000000000099"}
                          :query-params {:tab :finance :page 2}})}
               "Student"]
              [:button {:on/click
                        {:effect (ui/refresh :ui-test/student-detail-page
                                   {:tab :finance}
                                   {:path-params {:student-id #uuid "00000000-0000-0000-0000-000000000099"}
                                    :query-params {:tab :finance :page 2}})}}
               "Refresh"]])
        link (nth out 2)
        button (nth out 3)
        click (:data-on:click (attrs button))]
    (is (= "/admin/students/00000000-0000-0000-0000-000000000099?page=2&tab=finance"
           (:href (attrs link))))
    (is (string/includes?
         click
         "@post(\"/admin/students/00000000-0000-0000-0000-000000000099/__stream?page=2&tab=finance\""))
    (is (string/includes? click "\"tab\": \"finance\""))))

(deftest checked-route-refs-reject-literal-and-missing-routes
  (testing "refresh string paths fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"registered query keyword"
         (hiccup
          [:button {:on/click {:effect (ui/refresh "/literal/__stream" {})}}
           "bad"]))))
  (testing "unknown query routes fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"not registered"
         (hiccup
          [:button {:on/click {:effect (ui/refresh :ui-test/missing-page {})}}
           "bad"]))))
  (testing "queries without datastar paths fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"no :datastar/path"
         (ui/hiccup
          [:a {:href (ui/href :ui-test/no-path)} "bad"]
          {:query-registry {:ui-test/no-path {:handler-fn identity}}}))))
  (testing "literal dispatch post paths fail"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"reserved signal ref"
         (hiccup
          [:button {:on/click {:effect (ui/dispatch :ui-test/complete-task
                                        {:task-id #uuid "00000000-0000-0000-0000-000000000001"}
                                        {:post "/actions"})}}
           "bad"])))))

(deftest dispatch-schema-validation
  (testing "missing required command key fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing required command keys"
         (ui/hiccup
          [:button {:on/click {:effect (ui/dispatch :ui-test/complete-task {})}}
           "bad"]))))
  (testing "unknown command key fails"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"keys not present"
         (ui/hiccup
          [:button {:on/click {:effect (ui/dispatch :ui-test/complete-task
                                        {:task-id #uuid "00000000-0000-0000-0000-000000000001"
                                         :extra "nope"})}}
           "bad"]))))
  (testing "optional command key may be omitted"
    (is (some?
         (ui/hiccup
          [:button {:on/click {:effect (ui/dispatch :ui-test/optional-command
                                        {:required-id #uuid "00000000-0000-0000-0000-000000000002"})}}
           "ok"])))))

(deftest expressions-and-effects-lower
  (let [out (ui/hiccup
             (ui/with-signals [title {:init "Old"}]
               [:input {:bind/value title
                        :on/blur {:effect (ui/when-effect (ui/changed? title "Old")
                                            (ui/dispatch :ui-test/create-campus
                                              {:campus-name (ui/trimmed title)
                                               :is-virtual false}))}
                        :on/keydown {:effect (ui/on-keys {:Enter (ui/blur)
                                                          :Escape (ui/effects
                                                                   (ui/reset-signal title)
                                                                   (ui/blur))})}}]))
        a (attrs out)]
    (is (string/includes? (:data-on:blur a) ".trim()"))
    (is (string/includes? (:data-on:blur a) "if ("))
    (is (string/includes? (:data-on:keydown a) "evt.key === \"Enter\""))
    (is (string/includes? (:data-on:keydown a) "evt.key === \"Escape\""))
    (is (string/includes? (:data-on:keydown a) "el.blur();"))))

(deftest effects-sequence-actions-with-statement-separators
  (let [task-id #uuid "00000000-0000-0000-0000-000000000001"
        out (hiccup
             (ui/with-signals [title {:init ""}]
               [:div
                [:button#dispatch-reset
                 {:on/click {:effect (ui/effects
                                       (ui/dispatch :ui-test/complete-task
                                         {:task-id task-id})
                                       (ui/reset-signal title))}}
                 "dispatch reset"]
                [:button#dispatch-set
                 {:on/click {:effect (ui/effects
                                       (ui/dispatch :ui-test/complete-task
                                         {:task-id task-id})
                                       (ui/set-signal title "Done"))}}
                 "dispatch set"]
                [:button#refresh-reset
                 {:on/click {:effect (ui/effects
                                       (ui/refresh :ui-test/changed-page
                                         {:title title})
                                       (ui/reset-signal title))}}
                 "refresh reset"]
                [:button#when-nested
                 {:on/click {:effect (ui/when-effect (ui/present? title)
                                      (ui/effects
                                       (ui/dispatch :ui-test/complete-task
                                         {:task-id task-id})
                                       (ui/reset-signal title)))}}
                 "when nested"]
                [:button#choose-nested
                 {:on/click {:effect (ui/choose-effect (ui/present? title)
                                      (ui/effects
                                       (ui/dispatch :ui-test/complete-task
                                         {:task-id task-id})
                                       (ui/set-signal title "Done"))
                                      (ui/effects
                                       (ui/refresh :ui-test/changed-page
                                         {:title title})
                                       (ui/reset-signal title)))}}
                 "choose nested"]]))
        signal-ref (ui/signal-ref (first (data-signal-keys out)))
        dispatch-reset (:data-on:click (attrs (nth out 2)))
        dispatch-set (:data-on:click (attrs (nth out 3)))
        refresh-reset (:data-on:click (attrs (nth out 4)))
        when-nested (:data-on:click (attrs (nth out 5)))
        choose-nested (:data-on:click (attrs (nth out 6)))]
    (is (string/includes? dispatch-reset (str "}); " signal-ref " = \"\";")))
    (is (string/includes? dispatch-set (str "}); " signal-ref " = \"Done\";")))
    (is (string/includes? refresh-reset (str "}); " signal-ref " = \"\";")))
    (is (string/includes? when-nested (str "}); " signal-ref " = \"\";")))
    (is (string/includes? choose-nested (str "}); " signal-ref " = \"Done\";")))
    (is (string/includes? choose-nested (str "}); " signal-ref " = \"\";")))))

(deftest static-interpretation-removes-checked-behavior
  (let [ir-node (ui/ir
                 [:a {:href "/task"
                      :class "link"
                      :on/click {:effect (ui/dispatch :ui-test/complete-task
                                          {:task-id #uuid "00000000-0000-0000-0000-000000000001"})}
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
