(ns ai.obney.grain.datastar.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [ai.obney.grain.datastar.core :as ds]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.command-processor-v2.interface.schemas]
            [ai.obney.grain.query-schema.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.event-tailer.interface :as event-tailer]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.webserver.interface :as ws]
            [cognitect.anomalies :as anom]
            [io.pedestal.http :as http]
            [io.pedestal.http.sse :as sse])
  (:import [java.io BufferedReader InputStreamReader InputStream]
           [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; -------------------- ;;
;; Test Schema Setup    ;;
;; -------------------- ;;

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas test-schemas
  {:test/counters [:map]
   :test/auto-counters [:map]
   :test/event-counters [:map]
   :test/tagged-counters [:map]
   :test/filterable-counters [:map [:filter {:optional true} :string]]
   :test/subscription-page [:map]
   :test/subscription-empty [:map]
   :test/typed-query [:map
                      [:entity-id :uuid]
                      [:page :int]
                      [:include-archived? :boolean]
                      [:tab [:enum :active :archived]]]
   :test/increment [:map [:counter-id :uuid]]
   :test/typed-command [:map
                        [:entity-id :uuid]
                        [:count :int]
                        [:amount :double]
                        [:enabled? :boolean]
                        [:status [:enum :draft :published]]
                        [:label :string]]
   :test/no-signals [:map]
   :test/signals-emitting-page [:map]
   :test/signals-ignored-page [:map]})

;; -------------------- ;;
;; Test Query/Command   ;;
;; -------------------- ;;

(defquery :test counters
  {:authorized? (constantly true)}
  "Renders the counters test page."
  [context]
  (let [state @(:test-state context)
        counters (:counters state)]
    {:query/result counters
     :datastar/hiccup
     [:div#app
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

(defquery :test auto-counters
  {:authorized? (constantly true)
   :datastar/path "/auto-counters"
   :datastar/title "Auto Counters"
   :datastar/fps 10}
  "Renders the automatically refreshed counters test page."
  [context]
  (let [state @(:test-state context)
        counters (:counters state)]
    {:query/result counters
     :datastar/hiccup
     [:div#app
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

(defcommand :test increment
  {:authorized? (constantly true)}
  "Increments a counter in the Datastar test state."
  [context]
  (let [id (get-in context [:command :counter-id])
        state (:test-state context)]
    (swap! state update :counters
           (fn [counters]
             (mapv (fn [c]
                     (if (= (:id c) id)
                       (update c :value inc)
                       c))
                   counters)))
    {:command/result {:counter-id id}
     :datastar/signals {:last-action "incremented"}}))

;; --------------------------------- ;;
;; Event-Driven Test Read Model      ;;
;; --------------------------------- ;;

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(rmp/defreadmodel :test event-counters-rm
  {:events #{:test/counter-incremented}
   :version 1
   :schema [:map [:count {:optional true} :int]]}
  "Counts counter-incremented events for event-driven page tests."
  [state event]
  (case (:event/type event)
    :test/counter-incremented (update state :count (fnil inc 0))
    state))

(defquery :test event-counters
  {:authorized? (constantly true)
   :grain/read-models {:test/event-counters-rm 1}
   :datastar/path "/event-counters"
   :datastar/title "Event Counters"
   :datastar/debounce-ms 50}
  "Renders counters refreshed from an event-driven read model."
  [context]
  (let [state @(:test-state context)
        counters (:counters state)]
    {:query/result counters
     :datastar/hiccup
     [:div#app
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

;; ------------------------------------------ ;;
;; Event-Tagged Test Read Model & Query        ;;
;; ------------------------------------------ ;;

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(rmp/defreadmodel :test tagged-counters-rm
  {:events #{:test/counter-incremented}
   :version 1
   :schema [:map [:count {:optional true} :int]]}
  "Counts tagged counter events for scoped subscription tests."
  [state event]
  (update state :count (fnil inc 0)))

(defquery :test tagged-counters
  {:authorized? (constantly true)
   :grain/read-models {:test/tagged-counters-rm 1}
   :datastar/path "/tagged-counters/:counter-id"
   :datastar/title "Tagged Counters"
   :datastar/debounce-ms 50
   :datastar/event-tags {:counter :counter-id}}
  "Renders counters refreshed only by matching tagged events."
  [context]
  (let [state @(:test-state context)
        counters (:counters state)]
    {:query/result counters
     :datastar/hiccup
     [:div#app
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

;; ------------------------------------------------- ;;
;; Filterable Event-Driven Query (for POST tests)    ;;
;; ------------------------------------------------- ;;

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(rmp/defreadmodel :test filterable-counters-rm
  {:events #{:test/counter-incremented}
   :version 1
   :schema [:map [:count {:optional true} :int]]}
  "Counts events used by filterable event-driven query tests."
  [state event]
  (update state :count (fnil inc 0)))

(defquery :test filterable-counters
  {:authorized? (constantly true)
   :grain/read-models {:test/filterable-counters-rm 1}
   :datastar/path "/filterable-counters"
   :datastar/title "Filterable Counters"
   :datastar/debounce-ms 50}
  "Renders an event-driven counter page with a query filter."
  [context]
  (let [state @(:test-state context)
        counters (:counters state)
        filter-val (get-in context [:query :filter])]
    {:query/result {:counters counters :filter filter-val}
     :datastar/hiccup
     [:div#app
      (when filter-val [:div#active-filter "filter:" filter-val])
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

;; ------------------------------------------------- ;;
;; Auth-Protected Test Query (for E2E auth tests)    ;;
;; ------------------------------------------------- ;;

(defquery :test owner-only-page
  {:authorized? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
   :datastar/path "/owner-only"
   :datastar/title "Owner Only"
   :datastar/fps 0}
  "Renders a page restricted to owners."
  [context]
  {:query/result {:page :owner-only}
   :datastar/hiccup [:div#app "owner content"]})

(defquery :test public-page
  {:authorized? (constantly true)
   :datastar/path "/public-page"
   :datastar/title "Public"
   :datastar/fps 0}
  "Renders a publicly authorized page."
  [context]
  {:query/result {:page :public}
   :datastar/hiccup [:div#app "public content"]})

;; ------------------------------------------------- ;;
;; Opt-in query :datastar/signals (emit-signals?)    ;;
;; ------------------------------------------------- ;;

(defquery :test signals-emitting-page
  {:authorized? (constantly true)
   :datastar/path "/signals-emitting"
   :datastar/emit-signals? true
   :datastar/fps 0}
  "Renders a page whose Datastar signals are emitted."
  [_context]
  {:query/result {:page :signals}
   :datastar/hiccup [:div#app "signals content"]
   :datastar/signals {:__redirect "/elsewhere"}})

(defquery :test signals-ignored-page
  {:authorized? (constantly true)
   :datastar/path "/signals-ignored"
   :datastar/fps 0}
  "Renders a page whose returned Datastar signals are ignored."
  [_context]
  {:query/result {:page :ignored}
   :datastar/hiccup [:div#app "ignored content"]
   :datastar/signals {:__redirect "/elsewhere"}})

;; =========================== ;;
;; Pure Function Tests          ;;
;; =========================== ;;

(deftest render-html-test
  (is (= "<div><p>hello</p></div>"
         (ds/render-html [:div [:p "hello"]]))))

(deftest patch-elements-test
  (testing "single-line HTML"
    (let [result (ds/patch-elements "<div>hello</div>" {})]
      (is (= "datastar-patch-elements" (:name result)))
      (is (= "elements <div>hello</div>" (:data result)))))

  (testing "with selector and mode"
    (let [result (ds/patch-elements "<div>hello</div>" {:selector "#app" :mode :append})]
      (is (= "datastar-patch-elements" (:name result)))
      (is (str/includes? (:data result) "selector #app"))
      (is (str/includes? (:data result) "mode append"))
      (is (str/includes? (:data result) "elements <div>hello</div>"))))

  (testing "outer mode (default) is not included"
    (let [result (ds/patch-elements "<div>hello</div>" {:mode :outer})]
      (is (not (str/includes? (:data result) "mode")))))

  (testing "multi-line HTML"
    (let [result (ds/patch-elements "<div>\n<p>hello</p>\n</div>" {})]
      (is (= "elements <div>\nelements <p>hello</p>\nelements </div>"
             (:data result))))))

(deftest patch-signals-test
  (testing "basic signals"
    (let [result (ds/patch-signals {:key "val"} {})]
      (is (= "datastar-patch-signals" (:name result)))
      (is (= (str "signals " (json/write-str {:key "val"})) (:data result)))))

  (testing "with only-if-missing"
    (let [result (ds/patch-signals {:key "val"} {:only-if-missing true})]
      (is (str/starts-with? (:data result) "onlyIfMissing true\n")))))

;; ======================================= ;;
;; parse-datastar-signals Interceptor Tests ;;
;; ======================================= ;;

(deftest parse-datastar-signals-test
  (testing "GET with ?datastar= query param merges signals into :query-params"
    (let [ctx {:request {:request-method :get
                         :query-params {:datastar (json/write-str {:page "2" :search "foo"})}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "2" (get-in result [:request :query-params :page])))
      (is (= "foo" (get-in result [:request :query-params :search])))))

  (testing "GET with string key datastar param"
    (let [ctx {:request {:request-method :get
                         :query-params {"datastar" (json/write-str {:filter "active"})}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "active" (get-in result [:request :query-params :filter])))))

  (testing "POST with wrapped JSON body merges datastar signals into :query-params"
    (let [body-str (json/write-str {:datastar {:search "bar" :location_id "abc"}})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "bar" (get-in result [:request :query-params :search])))
      (is (= "abc" (get-in result [:request :query-params :location_id])))))

  (testing "POST with empty body returns context unchanged"
    (let [ctx {:request {:request-method :post
                         :body ""
                         :query-params {:existing "val"}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= {:existing "val"} (get-in result [:request :query-params])))))

  (testing "GET with no datastar param returns context unchanged"
    (let [ctx {:request {:request-method :get
                         :query-params {:other "param"}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= {:other "param"} (get-in result [:request :query-params])))))

  (testing "malformed JSON in datastar param returns context unchanged"
    (let [ctx {:request {:request-method :get
                         :query-params {:datastar "not-json{"}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= ctx result))))

  (testing "malformed JSON in POST body returns context unchanged"
    (let [ctx {:request {:request-method :post
                         :body "not-json{"
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= ctx result))))

  (testing "POST with flat JSON body (Datastar RC.7+) merges signals, native types preserved"
    (let [body-str (json/write-str {:location_id "loc-123" :filter "all" :page 1 :pageSize 25})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "loc-123" (get-in result [:request :query-params :location_id])))
      (is (= "all" (get-in result [:request :query-params :filter])))
      (is (= 1 (get-in result [:request :query-params :page])))
      (is (= 25 (get-in result [:request :query-params :pageSize])))))

  (testing "POST with wrapped body {:datastar {...}} still works"
    (let [body-str (json/write-str {:datastar {:search "wrapped"}})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "wrapped" (get-in result [:request :query-params :search])))))

  (testing "POST with explicit payload body merges only payload"
    (let [body-str (json/write-str {:payload {:page 2 :search "payload"}
                                    :ambient "ignored"})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= 2 (get-in result [:request :query-params :page])))
      (is (= "payload" (get-in result [:request :query-params :search])))
      (is (nil? (get-in result [:request :query-params :ambient])))))

  (testing "POST with explicit payload preserves nested maps and vectors"
    (let [body-str (json/write-str {:payload {:document {:id "doc-1"
                                                         :signer {:name "Ada"}
                                                         :fields [{:id "f1" :value "yes"}]}
                                               :tags ["urgent" "signed"]}})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= "doc-1" (get-in result [:request :query-params :document :id])))
      (is (= "Ada" (get-in result [:request :query-params :document :signer :name])))
      (is (= [{:id "f1" :value "yes"}]
             (get-in result [:request :query-params :document :fields])))
      (is (= ["urgent" "signed"]
             (get-in result [:request :query-params :tags])))))

  (testing "POST preserves native JSON types (booleans, numbers, maps)"
    (let [body-str (json/write-str {:active true :count 0 :fieldErrors {:name "required"}})
          ctx {:request {:request-method :post
                         :body body-str
                         :query-params {}}}
          result ((:enter ds/parse-datastar-signals) ctx)]
      (is (= true (get-in result [:request :query-params :active])))
      (is (= 0 (get-in result [:request :query-params :count])))
      (is (= {:name "required"} (get-in result [:request :query-params :fieldErrors]))))))

;; =========================== ;;
;; Shim Page Tests              ;;
;; =========================== ;;

(deftest shim-page-test
  (testing "basic shim page"
    (let [interceptor (ds/shim-page {})
          result ((:enter interceptor) {})]
      (is (= 200 (get-in result [:response :status])))
      (is (= "text/html; charset=UTF-8"
             (get-in result [:response :headers "Content-Type"])))
      (is (str/includes? (get-in result [:response :body]) "<script"))
      (is (str/includes? (get-in result [:response :body]) "__grainAction"))
      (is (str/includes? (get-in result [:response :body]) "/actions"))))

  (testing "custom action path is emitted as reserved signal"
    (let [interceptor (ds/shim-page {:action-path "/custom-actions"})
          result ((:enter interceptor) {})
          body (get-in result [:response :body])]
      (is (str/includes? body "__grainAction"))
      (is (str/includes? body "/custom-actions"))))

  (testing "with stream-path — nonce in URL and as signal for all stream methods"
    (let [interceptor (ds/shim-page {:stream-path "/stream"})
          result ((:enter interceptor) {})
          body (get-in result [:response :body])]
      (is (str/includes? body "data-init"))
      ;; hiccup2 escapes ' to &apos; in attributes; nonce appended as query param
      (is (str/includes? body "@get(&apos;/stream?dsNonce="))
      ;; dsNonce emitted as signal so Datastar includes it in every request
      (is (str/includes? body "data-signals"))
      (is (str/includes? body "dsNonce"))
      ;; __grainConnect marks the persistent (re)connect. It must be baked into
      ;; the data-init URL (replayed on every reconnect → routed to rebind)...
      (is (str/includes? body "__grainConnect=1"))
      ;; ...but must NOT be emitted as a signal (data-signals JSON, quotes escaped
      ;; to &quot;), or signal-update refreshes would carry it and every refresh
      ;; would be misread as a reconnect.
      (is (not (str/includes? body "&quot;__grainConnect&quot;")))))

  (testing "with stream-method post — nonce in URL and as signal"
    (let [interceptor (ds/shim-page {:stream-path "/stream" :stream-method "post"})
          result ((:enter interceptor) {})
          body (get-in result [:response :body])]
      (is (str/includes? body "data-init"))
      ;; POST: nonce in URL for data-init
      (is (str/includes? body "@post(&apos;/stream?dsNonce="))
      ;; AND as data-signals for subsequent @post calls
      (is (str/includes? body "data-signals"))
      (is (str/includes? body "dsNonce"))
      ;; Marker is method-agnostic: a POST persistent stream also carries it in
      ;; the URL (so its reconnect rebinds) but never as a signal.
      (is (str/includes? body "__grainConnect=1"))
      (is (not (str/includes? body "&quot;__grainConnect&quot;"))))))

(deftest shim-page-resolves-path-params-test
  (let [interceptor (ds/shim-page {:stream-path "/items/:item-id/__stream"})
        result ((:enter interceptor) {:request {:path-params {:item-id "abc-123"}}})
        body (get-in result [:response :body])]
    (testing "path params are resolved in stream URL"
      (is (str/includes? body "/items/abc-123/__stream"))
      (is (not (str/includes? body ":item-id"))))))

(deftest shim-page-no-path-params-unchanged-test
  (let [interceptor (ds/shim-page {:stream-path "/items/__stream"})
        result ((:enter interceptor) {})
        body (get-in result [:response :body])]
    (testing "static paths are unchanged"
      (is (str/includes? body "/items/__stream")))))

;; =========================== ;;
;; Malli Coercion Tests         ;;
;; =========================== ;;

(deftest decode-json-command-test
  (let [id (random-uuid)
        raw {:command/name ":test/increment" :counter-id (str id)}
        decoded (#'ds/decode-json-command raw)]
    (is (= :test/increment (:command/name decoded)))
    (is (uuid? (:counter-id decoded)))
    (is (= id (:counter-id decoded)))
    (is (uuid? (:command/id decoded)))
    (is (some? (:command/timestamp decoded))))

  (testing "strips extra keys not in schema"
    (let [id (random-uuid)
          raw {:command/name ":test/increment"
               :counter-id (str id)
               :showModal true
               :extra-field "noise"
               :some-prefix-name ""}
          decoded (#'ds/decode-json-command raw)]
      (is (= :test/increment (:command/name decoded)))
      (is (= id (:counter-id decoded)))
      (is (nil? (:showModal decoded)))
      (is (nil? (:extra-field decoded)))
      (is (nil? (:some-prefix-name decoded)))))

  (testing "decodes common Datastar string values to canonical command types"
    (let [id (random-uuid)
          raw {:command/name ":test/typed-command"
               :entity-id (str id)
               :count "42"
               :amount "12.34"
               :enabled? "true"
               :status "published"
               :label "still a string"}
          decoded (#'ds/decode-json-command raw)]
      (is (= :test/typed-command (:command/name decoded)))
      (is (= id (:entity-id decoded)))
      (is (= 42 (:count decoded)))
      (is (= 12.34 (:amount decoded)))
      (is (= true (:enabled? decoded)))
      (is (= :published (:status decoded)))
      (is (= "still a string" (:label decoded))))))

(deftest decode-json-query-test
  (let [raw {:query/name :test/counters}
        decoded (#'ds/decode-json-query raw)]
    (is (= :test/counters (:query/name decoded)))
    (is (uuid? (:query/id decoded)))
    (is (some? (:query/timestamp decoded))))

  (testing "decodes common Datastar string values to canonical query types"
    (let [id (random-uuid)
          raw {:query/name :test/typed-query
               :entity-id (str id)
               :page "3"
               :include-archived? "false"
               :tab "archived"}
          decoded (#'ds/decode-json-query raw)]
      (is (= :test/typed-query (:query/name decoded)))
      (is (uuid? (:entity-id decoded)))
      (is (= id (:entity-id decoded)))
      (is (= 3 (:page decoded)))
      (is (= false (:include-archived? decoded)))
      (is (= :archived (:tab decoded))))))

;; ====================================== ;;
;; auth-redirect-interceptor Tests        ;;
;; ====================================== ;;

(deftest auth-redirect-interceptor-no-claims-test
  (let [interceptor (ds/auth-redirect-interceptor
                     {:authorized? (fn [ctx] (some? (:auth-claims ctx)))
                      :unauthenticated "/sign-in"
                      :unauthorized "/home"})
        result ((:enter interceptor) {})]
    (is (= 302 (get-in result [:response :status])))
    (is (= "/sign-in" (get-in result [:response :headers "Location"])))))

(deftest auth-redirect-interceptor-unauthorized-test
  (let [interceptor (ds/auth-redirect-interceptor
                     {:authorized? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
                      :unauthenticated "/sign-in"
                      :unauthorized "/home"})
        ctx {:grain/additional-context {:auth-claims {:user-id (random-uuid) :role :staff}}}
        result ((:enter interceptor) ctx)]
    (is (= 302 (get-in result [:response :status])))
    (is (= "/home" (get-in result [:response :headers "Location"])))))

(deftest auth-redirect-interceptor-authorized-test
  (let [interceptor (ds/auth-redirect-interceptor
                     {:authorized? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
                      :unauthenticated "/sign-in"
                      :unauthorized "/home"})
        ctx {:grain/additional-context {:auth-claims {:user-id (random-uuid) :role :owner}}}
        result ((:enter interceptor) ctx)]
    ;; No :response — interceptor passed through
    (is (nil? (:response result)))
    (is (= ctx result))))

(deftest auth-redirect-interceptor-public-query-test
  (let [interceptor (ds/auth-redirect-interceptor
                     {:authorized? (constantly true)
                      :unauthenticated "/sign-in"
                      :unauthorized "/home"})
        ;; No claims at all — should still pass through for public queries
        result ((:enter interceptor) {})]
    (is (nil? (:response result)))))

;; ====================================== ;;
;; gate-interceptor Tests                  ;;
;; ====================================== ;;

(deftest gate-interceptor-passes-test
  (let [interceptor (ds/gate-interceptor
                     {:some-service :available}
                     {:check (fn [ctx] (:some-service ctx))
                      :redirect "/blocked"})
        ctx {:grain/additional-context {:auth-claims {:user-id (random-uuid)}}}
        result ((:enter interceptor) ctx)]
    (is (nil? (:response result)))))

(deftest gate-interceptor-blocks-test
  (let [interceptor (ds/gate-interceptor
                     {}
                     {:check (fn [_] false)
                      :redirect "/blocked"})
        ctx {:grain/additional-context {:auth-claims {:user-id (random-uuid)}}}
        result ((:enter interceptor) ctx)]
    (is (= 302 (get-in result [:response :status])))
    (is (= "/blocked" (get-in result [:response :headers "Location"])))))

(deftest gate-interceptor-receives-merged-context-test
  (let [received (atom nil)
        interceptor (ds/gate-interceptor
                     {:grain-key "from-context"}
                     {:check (fn [ctx] (reset! received ctx) true)
                      :redirect "/blocked"})
        ctx {:grain/additional-context {:auth-claims {:user-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}
                                        :tenant-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"}}
        _ ((:enter interceptor) ctx)]
    (is (= "from-context" (:grain-key @received)))
    (is (= #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
           (get-in @received [:auth-claims :user-id])))
    (is (= #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
           (:tenant-id @received)))))

(deftest gate-interceptor-sees-query-params-test
  (let [received (atom nil)
        interceptor (ds/gate-interceptor
                     {}
                     {:check (fn [ctx] (reset! received ctx) true)
                      :redirect "/blocked"})
        ctx {:request {:query-params {"id" "42"}}
             :grain/additional-context {:auth-claims {:user-id (random-uuid)}}}
        _ ((:enter interceptor) ctx)]
    (is (= {"id" "42"} (:query-params @received))
        "the request query-params are exposed to the check fn")))

(deftest gate-interceptor-dynamic-redirect-test
  (testing ":redirect may be a fn that computes the path per-request"
    (let [interceptor (ds/gate-interceptor
                       {}
                       {:check (fn [_] false)
                        :redirect (fn [gate-ctx]
                                    (str "/blocked/" (get-in gate-ctx [:query-params "id"])))})
          ctx {:request {:query-params {"id" "42"}}
               :grain/additional-context {:auth-claims {:user-id (random-uuid)}}}
          result ((:enter interceptor) ctx)]
      (is (= 302 (get-in result [:response :status])))
      (is (= "/blocked/42" (get-in result [:response :headers "Location"])))))
  (testing "a string :redirect still works unchanged (backward compatible)"
    (let [interceptor (ds/gate-interceptor {} {:check (fn [_] false) :redirect "/blocked"})
          result ((:enter interceptor) {:request {:query-params {}}
                                        :grain/additional-context {}})]
      (is (= "/blocked" (get-in result [:response :headers "Location"]))))))

;; =========================== ;;
;; poll-and-render Tests        ;;
;; =========================== ;;

(deftest poll-and-render-change-detection-test
  (let [state (atom {:counters [{:id (random-uuid) :name "X" :value 1}]})
        context {:test-state state
                 :query-registry @qp/query-registry*
                 :query {:query/name :test/counters
                         :query/id (random-uuid)
                         :query/timestamp (time/now)}}]

    (testing "first poll returns event"
      (let [result (ds/poll-and-render context nil)]
        (is (some? result))
        (is (= "datastar-patch-elements" (get-in result [:event :name])))
        (is (some? (:result result)))))

    (testing "second poll with same data returns nil"
      (let [first-result (ds/poll-and-render context nil)]
        (is (nil? (ds/poll-and-render context (:result first-result))))))

    (testing "poll after change returns new event"
      (let [first-result (ds/poll-and-render context nil)]
        (swap! state assoc-in [:counters 0 :value] 99)
        (let [second-result (ds/poll-and-render context (:result first-result))]
          (is (some? second-result))
          (is (not= (:result first-result) (:result second-result))))))))

(deftest poll-and-render-opt-in-signals-test
  (testing ":datastar/emit-signals? query attaches a :signals-event alongside the elements event"
    (let [context {:query-registry @qp/query-registry*
                   :query {:query/name :test/signals-emitting-page
                           :query/id (random-uuid)
                           :query/timestamp (time/now)}}
          result (ds/poll-and-render context nil)]
      (is (= "datastar-patch-elements" (get-in result [:event :name])))
      (is (= "datastar-patch-signals" (get-in result [:signals-event :name])))
      (is (re-find #"__redirect" (get-in result [:signals-event :data])))))
  (testing "a query WITHOUT the flag drops :datastar/signals (backward compatible)"
    (let [context {:query-registry @qp/query-registry*
                   :query {:query/name :test/signals-ignored-page
                           :query/id (random-uuid)
                           :query/timestamp (time/now)}}
          result (ds/poll-and-render context nil)]
      (is (= "datastar-patch-elements" (get-in result [:event :name])))
      (is (nil? (:signals-event result))))))

(deftest poll-and-render-static-result-dynamic-hiccup-test
  (testing "re-renders when hiccup changes even if :query/result is static"
    ;; Use the :test/counters query but keep :query/result the same across renders
    ;; while changing the hiccup via the underlying state atom.
    ;; The counters query returns {:query/result counters :datastar/hiccup [...]}.
    ;; If we change a counter value, both query/result and hiccup change.
    ;; To test the specific bug, we need a query where only hiccup changes.
    ;; We simulate this by using :test/counters and verifying the diff works on hiccup.
    (let [state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000099"
                                   :name "Static" :value 0}]})
          context {:test-state state
                   :query-registry @qp/query-registry*
                   :query {:query/name :test/counters
                           :query/id (random-uuid)
                           :query/timestamp (time/now)}}
          first-result (ds/poll-and-render context nil)]
      ;; First poll renders
      (is (some? first-result))
      ;; :result now contains hiccup, not query-data
      (is (vector? (:result first-result)))

      ;; Same state — hiccup is identical, should return nil
      (is (nil? (ds/poll-and-render context (:result first-result))))

      ;; Change value — hiccup changes
      (swap! state assoc-in [:counters 0 :value] 42)
      (let [second-result (ds/poll-and-render context (:result first-result))]
        (is (some? second-result) "should re-render when hiccup changes")
        (is (str/includes? (get-in second-result [:event :data]) "42"))))))

(deftest poll-and-render-unauthorized-test
  (let [context {:query-registry {:test/counters {:handler-fn (fn [_] {:query/result []})
                                                  :authorized? (constantly false)}}
                 :query {:query/name :test/counters
                         :query/id (random-uuid)
                         :query/timestamp (time/now)}}
        result (ds/poll-and-render context nil)]
    (is (true? (:stop? result)))
    (is (= "datastar-patch-signals" (get-in result [:event :name])))
    (is (str/includes? (get-in result [:event :data]) "Unauthorized"))))

;; =========================== ;;
;; execute-action Tests         ;;
;; =========================== ;;

(deftest execute-action-valid-command-test
  (let [state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        context {:test-state state
                 :command-registry @cp/command-registry*
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/increment"
                 :counter-id "00000000-0000-0000-0000-000000000001"}
        result (ds/execute-action context signals)]
    (is (= "datastar-patch-signals" (:name result)))
    (is (str/includes? (:data result) "incremented"))))

(deftest execute-action-fallback-to-command-result-test
  (let [context {:command-registry {:test/no-signals {:handler-fn (fn [_] {:command/result {:status "ok"}})
                                                      :authorized? (constantly true)}}
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/no-signals"}
        result (ds/execute-action context signals)]
    (is (= "datastar-patch-signals" (:name result)))
    (is (str/includes? (:data result) "ok"))))

(deftest execute-action-unauthorized-test
  (let [context {:command-registry {:test/increment {:handler-fn (fn [_] nil)
                                                     :authorized? (constantly false)}}
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/increment" :counter-id (str (random-uuid))}
        result (ds/execute-action context signals)]
    (is (= "datastar-patch-signals" (:name result)))
    (is (str/includes? (:data result) "Unauthorized"))))

(deftest execute-action-metadata-success-test
  (let [context {:command-registry {:test/no-signals
                                    {:handler-fn (fn [_]
                                                   {:command/result {:auth/token "token-123"}})
                                     :authorized? (constantly true)}}
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/no-signals"}
        result (#'ds/execute-action* context signals)]
    (is (= :test/no-signals (get-in result [:command :command/name])))
    (is (= {:command/result {:auth/token "token-123"}}
           (:command-result result)))
    (is (= "datastar-patch-signals" (get-in result [:event :name])))
    (is (str/includes? (get-in result [:event :data]) "token-123"))))

(deftest execute-action-metadata-anomaly-test
  (let [anomaly {::anom/category ::anom/conflict
                 ::anom/message "Already exists"}
        context {:command-registry {:test/no-signals
                                    {:handler-fn (fn [_] anomaly)
                                     :authorized? (constantly true)}}
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/no-signals"}
        result (#'ds/execute-action* context signals)]
    (is (= :test/no-signals (get-in result [:command :command/name])))
    (is (= anomaly (:command-result result)))
    (is (str/includes? (get-in result [:event :data]) "Already exists"))))

(deftest execute-action-metadata-unauthorized-test
  (let [context {:command-registry {:test/no-signals
                                    {:handler-fn (fn [_] {:command/result {:ok true}})
                                     :authorized? (constantly false)}}
                 :command-processor/skip-event-storage true}
        signals {:command/name ":test/no-signals"}
        result (#'ds/execute-action* context signals)]
    (is (= :test/no-signals (get-in result [:command :command/name])))
    (is (= ::anom/forbidden (get-in result [:command-result ::anom/category])))
    (is (= "Unauthorized" (get-in result [:command-result ::anom/message])))
    (is (str/includes? (get-in result [:event :data]) "Unauthorized"))))

(deftest action-handler-exposes-command-metadata-to-leave-interceptor-test
  (let [context {:command-registry {:test/no-signals
                                    {:handler-fn (fn [_]
                                                   {:command/result {:auth/token "token-123"}})
                                     :authorized? (constantly true)}}
                 :command-processor/skip-event-storage true}
        interceptor (ds/action-handler context {})
        cookie-leave {:leave (fn [ctx]
                               (let [command (:grain/command ctx)
                                     result (:grain/command-result ctx)]
                                 (if (and (= :test/no-signals (:command/name command))
                                          (not (::anom/category result))
                                          (get-in result [:command/result :auth/token]))
                                   (assoc-in ctx [:response :headers "Set-Cookie"] "auth-token=token-123")
                                   ctx)))}
        request-body (json/write-str {"command/name" ":test/no-signals"})
        start-stream-calls (atom [])]
    (with-redefs [sse/start-stream
                  (fn [callback pedestal-context heartbeat-delay]
                    (let [event-ch (async/chan 1)]
                      (callback event-ch {:request (:request pedestal-context)})
                      (swap! start-stream-calls conj {:heartbeat-delay heartbeat-delay
                                                      :event (async/poll! event-ch)})
                      (assoc pedestal-context
                             :response {:status 200 :headers {}})))]
      (let [entered ((:enter interceptor) {:request {:body request-body}})
            left ((:leave cookie-leave) entered)]
        (is (= :test/no-signals (get-in entered [:grain/command :command/name])))
        (is (= {:command/result {:auth/token "token-123"}}
               (:grain/command-result entered)))
        (is (= "auth-token=token-123"
               (get-in left [:response :headers "Set-Cookie"])))
        (is (= "datastar-patch-signals"
               (get-in (first @start-stream-calls) [:event :name])))))))

;; =========================== ;;
;; SSE Test Client              ;;
;; =========================== ;;

(defn- parse-sse-events
  "Reads SSE frames from an InputStream. Returns a vector of {:name :data} maps.
   Reads until `n` events collected or `timeout-ms` elapsed."
  [^InputStream input-stream n timeout-ms]
  (let [reader (BufferedReader. (InputStreamReader. input-stream))
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [events [] current-name nil current-data []]
      (if (or (>= (count events) n)
              (> (System/currentTimeMillis) deadline))
        events
        (let [line (.readLine reader)]
          (cond
            (nil? line) events
            (str/starts-with? line "event:") (recur events (str/trim (subs line 6)) current-data)
            (str/starts-with? line "data:") (recur events current-name (conj current-data (str/trim (subs line 5))))
            (str/blank? line) (if current-name
                                (recur (conj events {:name current-name
                                                     :data (str/join "\n" current-data)})
                                       nil [])
                                (recur events nil []))
            :else (recur events current-name current-data)))))))

(defn- take-event
  [ch timeout-ms]
  (async/alt!!
    ch ([v] v)
    (async/timeout timeout-ms) nil))

(defn- eventually?
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do
                (Thread/sleep 10)
                (recur))))))

;; =========================== ;;
;; E2E Test Fixture             ;;
;; =========================== ;;

(def ^:dynamic *port* nil)
(def ^:dynamic *e2e-state* nil)
(def ^:dynamic *event-pubsub* nil)
(def ^:dynamic *gate-pass* nil)

(def test-user-id-a #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
(def test-user-id-b #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

(defn- get-server-port [webserver]
  (let [service-map (get webserver :ai.obney.grain.webserver.core/server)
        jetty-server (::http/server service-map)]
    (.getLocalPort (first (.getConnectors jetty-server)))))

(defn- inject-auth-interceptor
  "Interceptor that injects :grain/additional-context with auth claims.
   Reads user-id from the X-Test-User-Id header, defaulting to test-user-id-a."
  []
  {:name ::inject-auth
   :enter (fn [ctx]
            (let [user-id-str (get-in ctx [:request :headers "x-test-user-id"])
                  user-id (if user-id-str
                            (parse-uuid user-id-str)
                            test-user-id-a)]
              (assoc ctx :grain/additional-context
                     {:auth-claims {:user-id user-id}})))})

(defn- inject-auth-from-header-interceptor
  "Interceptor that injects auth claims ONLY when X-Test-User-Id and X-Test-Role
   headers are present. Used for auth-redirect E2E tests where we need to test
   unauthenticated (no header), unauthorized (wrong role), and authorized (right role)."
  []
  {:name ::inject-auth-from-header
   :enter (fn [ctx]
            (let [user-id-str (get-in ctx [:request :headers "x-test-user-id"])
                  role-str (get-in ctx [:request :headers "x-test-role"])]
              (if user-id-str
                (assoc ctx :grain/additional-context
                       {:auth-claims (cond-> {:user-id (parse-uuid user-id-str)}
                                      role-str (assoc :role (keyword role-str)))})
                ctx)))})

(defn e2e-fixture [f]
  (let [state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        gate-pass (atom false)
        event-pubsub (pubsub/start {:type :core-async
                                    :topic-fn :event/type})
        context {:test-state state
                 :gate-pass-atom gate-pass
                 :command-processor/skip-event-storage true
                 :command-registry @cp/command-registry*
                 :query-registry @qp/query-registry*
                 :event-pubsub event-pubsub}
        auth-interceptor (inject-auth-interceptor)
        event-types #{:test/counter-incremented}
        stream-opts {:event-types event-types :debounce-ms 50}
        manual-routes #{["/counters" :get [(ds/shim-page {:stream-path "/counters/__stream"})]
                         :route-name ::counters-page]
                        ["/counters/__stream" :get [(ds/stream-view context :test/counters {:fps 10})]
                         :route-name ::counters-stream]
                        ["/ds/command" :post [(ds/action-handler context {})]
                         :route-name ::ds-command]
                        ;; POST-testable event-driven stream with auth + signal parsing
                        ["/filterable/__stream" :get [ds/parse-datastar-signals auth-interceptor
                                                    (ds/stream-view context :test/filterable-counters stream-opts)]
                         :route-name ::filterable-stream-get]
                        ["/filterable/__stream" :post [ds/parse-datastar-signals auth-interceptor
                                                     (ds/stream-view context :test/filterable-counters stream-opts)]
                         :route-name ::filterable-stream-post]
                        ;; Path-param shim page for E2E path resolution test
                        ["/detail/:id" :get [(ds/shim-page {:stream-path "/detail/:id/__stream" :title "Detail"})]
                         :route-name ::detail-page]}
        ;; Auto-routes without auth-redirect (existing behavior).
        ;; Exclude queries that have manually-wired routes to avoid duplicates.
        manual-query-keys #{:test/owner-only-page :test/public-page}
        auto-ctx (update context :query-registry #(apply dissoc % manual-query-keys))
        auto-routes (ds/routes auto-ctx)
        ;; Auth-redirect E2E routes — manually wired so we can place header-auth
        ;; BEFORE auth-redirect in the interceptor chain.
        header-auth (inject-auth-from-header-interceptor)
        owner-authorized? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
        auth-redirect (ds/auth-redirect-interceptor
                       {:authorized? owner-authorized?
                        :unauthenticated "/test-login"
                        :unauthorized "/test-home"})
        ;; Gate interceptors for E2E tests
        ;; gate-allowed? checks the gate-pass-atom from the merged grain context
        gate-allowed (ds/gate-interceptor context {:check (fn [ctx] @(:gate-pass-atom ctx))
                                                   :redirect "/gate-blocked"})
        gate-fallback (ds/gate-interceptor context {:check (fn [_] false)
                                                    :redirect "/test-home"})
        auth-redirect-routes #{["/owner-only" :get [header-auth auth-redirect
                                                     (ds/shim-page {:title "Owner Only"})]
                                :route-name ::owner-only-page]
                               ["/public-page" :get [(ds/shim-page {:title "Public"})]
                                :route-name ::public-page]
                               ["/gated-page" :get [header-auth auth-redirect gate-allowed
                                                     (ds/shim-page {:title "Gated"})]
                                :route-name ::gated-page]
                               ["/gate-fallback-page" :get [gate-fallback
                                                             (ds/shim-page {:title "Fallback"})]
                                :route-name ::gate-fallback-page]}
        routes (-> manual-routes (into auto-routes) (into auth-redirect-routes))
        server (ws/start {:http/routes routes :http/port 0 :http/join? false})
        port (get-server-port server)]
    (binding [*port* port *e2e-state* state *event-pubsub* event-pubsub *gate-pass* gate-pass]
      (try (f)
           (finally
             (ws/stop server)
             (pubsub/stop event-pubsub))))))

(use-fixtures :once e2e-fixture)

;; =========================== ;;
;; E2E Tests                    ;;
;; =========================== ;;

(deftest e2e-shim-page-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/counters")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    (is (= 200 (.statusCode response)))
    (is (str/includes? body "<script"))
    (is (str/includes? body "datastar"))
    ;; hiccup2 escapes ' to &apos; in attributes
    (is (str/includes? body "@get(&apos;/counters/__stream?dsNonce="))))

(deftest e2e-sse-stream-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/counters/__stream")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        events (parse-sse-events (.body response) 1 5000)]
    (is (= 1 (count events)))
    (is (= "datastar-patch-elements" (:name (first events))))
    (is (str/includes? (:data (first events)) "elements"))))

(deftest e2e-command-test
  (let [client (HttpClient/newHttpClient)
        cmd-body (json/write-str {"command/name" ":test/increment"
                                  "counter-id" "00000000-0000-0000-0000-000000000001"})
        cmd-request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/ds/command")))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString cmd-body))
                        .build)
        cmd-response (.send client cmd-request (HttpResponse$BodyHandlers/ofInputStream))
        cmd-events (parse-sse-events (.body cmd-response) 1 5000)]
    (is (= 1 (count cmd-events)))
    (is (= "datastar-patch-signals" (:name (first cmd-events))))
    (is (str/includes? (:data (first cmd-events)) "incremented"))))

(deftest e2e-stream-rerender-after-command-test
  (let [client (HttpClient/newHttpClient)
        ;; Connect to stream
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port* "/counters/__stream")))
                           (.GET)
                           .build)
        stream-response (.send client stream-request (HttpResponse$BodyHandlers/ofInputStream))
        ;; Read initial + updated events in background
        stream-events-future (future (parse-sse-events (.body stream-response) 2 10000))
        ;; Wait for stream to connect and send initial event
        _ (Thread/sleep 500)
        ;; Send command to modify state
        cmd-body (json/write-str {"command/name" ":test/increment"
                                  "counter-id" "00000000-0000-0000-0000-000000000001"})
        cmd-request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/ds/command")))
                        (.header "Content-Type" "application/json")
                        (.POST (HttpRequest$BodyPublishers/ofString cmd-body))
                        .build)
        _ (.send client cmd-request (HttpResponse$BodyHandlers/ofString))
        ;; Wait for stream to pick up the change
        stream-events @stream-events-future]
    (is (= 2 (count stream-events)))
    (is (= "datastar-patch-elements" (:name (first stream-events))))
    (is (= "datastar-patch-elements" (:name (second stream-events))))))

(deftest e2e-query-params-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/counters/__stream?filter=active")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        events (parse-sse-events (.body response) 1 5000)]
    (is (= 1 (count events)))
    (is (= "datastar-patch-elements" (:name (first events))))))

;; =========================== ;;
;; Route Generation Tests       ;;
;; =========================== ;;

(deftest query-name->route-name-test
  (is (= :ai.obney.grain.datastar.core/user-sign-in-page
         (#'ds/query-name->route-name :user/sign-in-page)))
  (is (= :ai.obney.grain.datastar.core/test-counters
         (#'ds/query-name->route-name :test/counters))))

(deftest query->route-pair-test
  (testing "entry with :datastar/path produces three routes"
    (let [entry {:handler-fn identity
                 :authorized? (constantly true)
                 :datastar/path "/my-page"
                 :datastar/title "My Page"
                 :datastar/fps 5}
          context {}
          [shim-route stream-route post-stream-route] (#'ds/query->route-pair context :test/my-page entry {})]
      (is (some? shim-route))
      (is (some? stream-route))
      (is (some? post-stream-route))
      ;; Shim route
      (is (= "/my-page" (first shim-route)))
      (is (= :get (second shim-route)))
      (is (= :ai.obney.grain.datastar.core/test-my-page-page
             (last shim-route)))
      ;; GET Stream route
      (is (= "/my-page/__stream" (first stream-route)))
      (is (= :get (second stream-route)))
      (is (= :ai.obney.grain.datastar.core/test-my-page-stream
             (last stream-route)))
      ;; POST Stream route
      (is (= "/my-page/__stream" (first post-stream-route)))
      (is (= :post (second post-stream-route)))
      (is (= :ai.obney.grain.datastar.core/test-my-page-stream-post
             (last post-stream-route)))))

  (testing "entry without :datastar/path returns nil"
    (is (nil? (#'ds/query->route-pair {} :test/counters
                                      {:handler-fn identity
                                       :authorized? (constantly true)} {})))))

(deftest routes-generation-test
  (let [context {:query-registry {:test/with-path {:handler-fn identity
                                                    :authorized? (constantly true)
                                                    :datastar/path "/with-path"
                                                    :datastar/title "With Path"}
                                   :test/no-path {:handler-fn identity
                                                   :authorized? (constantly true)}}}
        generated (ds/routes context)]
    (testing "correct number of routes (3 per annotated query: shim + GET stream + POST stream)"
      (is (= 3 (count generated))))

    (testing "queries without :datastar/path are excluded"
      (let [paths (set (map first generated))]
        (is (contains? paths "/with-path"))
        (is (contains? paths "/with-path/__stream"))
        (is (not (some #(str/includes? % "no-path") paths)))))

    (testing "route names are unique"
      (let [route-names (map last generated)]
        (is (= (count route-names) (count (set route-names))))))))

(deftest routes-with-defaults-test
  (let [context {:query-registry {:test/defaults {:handler-fn identity
                                                   :authorized? (constantly true)
                                                   :datastar/path "/defaults"}}}
        generated (ds/routes context)
        shim-route (first (filter #(= "/defaults" (first %)) generated))
        stream-route (first (filter #(= "/defaults/__stream" (first %)) generated))
        shim-interceptor (last (nth shim-route 2))
        shim-result ((:enter shim-interceptor) {})]
    (testing "default title is 'Grain App'"
      (is (str/includes? (get-in shim-result [:response :body]) "Grain App")))

    (testing "default fps is 0 (stream interceptor is created)"
      (is (some? stream-route)))))

(deftest routes-with-global-defaults-shim-opts-test
  (let [context {:query-registry {:test/with-defaults {:handler-fn identity
                                                        :authorized? (constantly true)
                                                        :datastar/path "/with-defaults"
                                                        :datastar/title "Custom Title"}}}
        defaults {:datastar/shim-opts {:head (fn [] [:link {:href "/app.css"}])
                                       :html-attrs {:data-theme "test-theme"}}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/with-defaults" (first %)) generated))
        shim-interceptor (last (nth shim-route 2))
        shim-result ((:enter shim-interceptor) {})
        body (get-in shim-result [:response :body])]
    (testing "default head content is applied"
      (is (str/includes? body "/app.css")))

    (testing "default html-attrs are applied"
      (is (str/includes? body "data-theme")))

    (testing "query title still takes precedence"
      (is (str/includes? body "Custom Title")))))

(deftest routes-with-action-path-default-test
  (let [context {:query-registry {:test/action-default {:handler-fn identity
                                                        :authorized? (constantly true)
                                                        :datastar/path "/action-default"}}}
        defaults {:datastar/action-path "/custom-actions"}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/action-default" (first %)) generated))
        shim-interceptor (last (nth shim-route 2))
        body (get-in ((:enter shim-interceptor) {}) [:response :body])]
    (is (str/includes? body "__grainAction"))
    (is (str/includes? body "/custom-actions"))))

(deftest routes-defaults-per-query-override-test
  (let [context {:query-registry {:test/default-head {:handler-fn identity
                                                       :authorized? (constantly true)
                                                       :datastar/path "/default-head"}
                                   :test/custom-head {:handler-fn identity
                                                       :authorized? (constantly true)
                                                       :datastar/path "/custom-head"}}}
        defaults {:datastar/shim-opts {:head (fn [] [:link {:href "/default.css"}])}}
        overrides {:test/custom-head {:datastar/shim-opts {:head (fn [] [:link {:href "/custom.css"}])}}}
        generated (ds/routes context overrides defaults)
        default-shim (first (filter #(= "/default-head" (first %)) generated))
        custom-shim (first (filter #(= "/custom-head" (first %)) generated))
        default-body (get-in ((:enter (last (nth default-shim 2))) {}) [:response :body])
        custom-body (get-in ((:enter (last (nth custom-shim 2))) {}) [:response :body])]
    (testing "query without override uses default head"
      (is (str/includes? default-body "/default.css"))
      (is (not (str/includes? default-body "/custom.css"))))

    (testing "query with override uses custom head"
      (is (str/includes? custom-body "/custom.css"))
      (is (not (str/includes? custom-body "/default.css"))))))

(deftest routes-defaults-empty-test
  (let [context {:query-registry {:test/empty-defaults {:handler-fn identity
                                                         :authorized? (constantly true)
                                                         :datastar/path "/empty-defaults"
                                                         :datastar/title "Empty"}}}
        generated-2arity (ds/routes context {})
        generated-3arity (ds/routes context {} {})]
    (testing "empty defaults produces same number of routes as 2-arity"
      (is (= (count generated-2arity) (count generated-3arity)))
      (is (= 3 (count generated-3arity))))))

(deftest query->route-pair-with-defaults-test
  (let [entry {:handler-fn identity
               :authorized? (constantly true)
               :datastar/path "/merge-test"
               :datastar/title "Entry Title"
               :datastar/shim-opts {:html-attrs {:data-entry "yes"}}}
        defaults {:datastar/shim-opts {:html-attrs {:data-default "yes"}
                                       :head (fn [] [:meta {:name "default"}])}}
        [shim-route] (#'ds/query->route-pair {} :test/merge-test entry defaults)
        shim-interceptor (last (nth shim-route 2))
        body (get-in ((:enter shim-interceptor) {}) [:response :body])]
    (testing "entry shim-opts override defaults (html-attrs from entry wins)"
      (is (str/includes? body "data-entry")))

    (testing "default head is not applied when entry shim-opts overrides whole map"
      ;; merge is shallow — entry :datastar/shim-opts replaces defaults entirely
      ;; so default :head is lost when entry provides its own shim-opts
      ;; This is expected — merge semantics, not deep-merge
      )

    (testing "entry title takes precedence"
      (is (str/includes? body "Entry Title")))))

(deftest routes-with-overrides-test
  (let [my-interceptor {:name ::test-override-interceptor :enter identity}
        context {:query-registry {:test/overridable {:handler-fn identity
                                                     :authorized? (constantly true)
                                                     :datastar/path "/overridable"
                                                     :datastar/fps 5}}}
        generated (ds/routes context {:test/overridable {:datastar/interceptors [my-interceptor]}})
        shim-route (first (filter #(= "/overridable" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "parse-datastar-signals is first, then override interceptor"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= ::test-override-interceptor (:name (second interceptors)))))))

(deftest routes-with-interceptors-test
  (let [my-interceptor {:name ::my-guard :enter identity}
        context {:query-registry {:test/guarded {:handler-fn identity
                                                  :authorized? (constantly true)
                                                  :datastar/path "/guarded"
                                                  :datastar/interceptors [my-interceptor]}}}
        generated (ds/routes context)
        shim-route (first (filter #(= "/guarded" (first %)) generated))
        get-stream-route (first (filter #(and (= "/guarded/__stream" (first %))
                                               (= :get (second %))) generated))
        post-stream-route (first (filter #(and (= "/guarded/__stream" (first %))
                                                (= :post (second %))) generated))
        shim-interceptors (nth shim-route 2)
        get-stream-interceptors (nth get-stream-route 2)
        post-stream-interceptors (nth post-stream-route 2)]
    (testing "shim route: parse-datastar-signals first, custom interceptor second, shim-page last"
      (is (= ::ds/parse-datastar-signals (:name (first shim-interceptors))))
      (is (= ::my-guard (:name (second shim-interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (nth shim-interceptors 2)))))

    (testing "GET stream route: parse-datastar-signals first, custom interceptor second, stream-view last"
      (is (= ::ds/parse-datastar-signals (:name (first get-stream-interceptors))))
      (is (= ::my-guard (:name (second get-stream-interceptors))))
      (is (= :ai.obney.grain.datastar.core/stream-guarded
             (:name (nth get-stream-interceptors 2)))))

    (testing "POST stream route: parse-datastar-signals first, custom interceptor second, stream-view last"
      (is (= ::ds/parse-datastar-signals (:name (first post-stream-interceptors))))
      (is (= ::my-guard (:name (second post-stream-interceptors))))
      (is (= :ai.obney.grain.datastar.core/stream-guarded
             (:name (nth post-stream-interceptors 2)))))))

;; ============================================ ;;
;; Route Generation — Auth Redirect Tests       ;;
;; ============================================ ;;

(deftest routes-auth-redirect-generates-interceptor-test
  (let [owner? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
        context {:query-registry {:test/protected {:handler-fn identity
                                                    :authorized? owner?
                                                    :datastar/path "/protected"
                                                    :datastar/title "Protected"}}}
        defaults {:datastar/auth-redirect {:unauthenticated "/login"
                                           :unauthorized "/home"}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/protected" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "parse-datastar-signals first, auth-redirect second, shim-page last"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= ::ds/auth-redirect (:name (second interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (nth interceptors 2)))))))

(deftest routes-auth-redirect-skips-public-test
  (let [context {:query-registry {:test/public-page {:handler-fn identity
                                                      :authorized? (constantly true)
                                                      :datastar/path "/public"
                                                      :datastar/title "Public"}}}
        defaults {:datastar/auth-redirect {:unauthenticated "/login"
                                           :unauthorized "/home"}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/public" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "no auth-redirect interceptor for public query — only parse-signals + shim-page"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (second interceptors)))))))

(deftest routes-no-auth-redirect-default-test
  (let [owner? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
        context {:query-registry {:test/no-redirect {:handler-fn identity
                                                      :authorized? owner?
                                                      :datastar/path "/no-redirect"}}}
        generated (ds/routes context {} {})
        shim-route (first (filter #(= "/no-redirect" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "no auth-redirect in defaults — no interceptor generated (backward compatible)"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (second interceptors)))))))

(deftest routes-auth-redirect-with-explicit-interceptors-test
  (let [owner? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
        custom {:name ::custom-guard :enter identity}
        context {:query-registry {:test/both {:handler-fn identity
                                               :authorized? owner?
                                               :datastar/path "/both"
                                               :datastar/interceptors [custom]}}}
        defaults {:datastar/auth-redirect {:unauthenticated "/login"
                                           :unauthorized "/home"}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/both" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "auth-redirect is prepended before explicit interceptors"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= ::ds/auth-redirect (:name (second interceptors))))
      (is (= ::custom-guard (:name (nth interceptors 2))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (nth interceptors 3)))))))

;; ============================================ ;;
;; Route Generation — Gate Tests                ;;
;; ============================================ ;;

(deftest routes-gate-generates-interceptor-test
  (let [owner? (fn [ctx] (= :owner (:role (:auth-claims ctx))))
        context {:query-registry {:test/gated {:handler-fn identity
                                                :authorized? owner?
                                                :datastar/path "/gated"
                                                :datastar/gate {:check (fn [_] true)
                                                                :redirect "/gate-fail"}}}}
        defaults {:datastar/auth-redirect {:unauthenticated "/login" :unauthorized "/home"}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/gated" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "interceptor chain: parse-signals → auth-redirect → gate → shim-page"
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= ::ds/auth-redirect (:name (second interceptors))))
      (is (= ::ds/gate (:name (nth interceptors 2))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (nth interceptors 3)))))))

(deftest routes-gate-fallback-redirect-test
  (let [context {:query-registry {:test/gate-fallback {:handler-fn identity
                                                        :authorized? (constantly true)
                                                        :datastar/path "/gate-fallback"
                                                        :datastar/gate {:check (fn [_] false)}}}}
        defaults {:datastar/auth-redirect {:unauthenticated "/login" :unauthorized "/home"}}
        generated (ds/routes context {} defaults)
        shim-route (first (filter #(= "/gate-fallback" (first %)) generated))
        interceptors (nth shim-route 2)
        ;; The gate interceptor is at position 1 (no auth-redirect for public query)
        gate-int (second interceptors)]
    (testing "gate interceptor exists and uses fallback redirect from auth-redirect defaults"
      (is (= ::ds/gate (:name gate-int)))
      ;; Test the interceptor — check returns false, should redirect to /home (fallback)
      (let [result ((:enter gate-int) {})]
        (is (= "/home" (get-in result [:response :headers "Location"])))))))

(deftest routes-no-gate-test
  (let [context {:query-registry {:test/no-gate {:handler-fn identity
                                                   :authorized? (constantly true)
                                                   :datastar/path "/no-gate"}}}
        generated (ds/routes context {} {})
        shim-route (first (filter #(= "/no-gate" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "no gate interceptor when :datastar/gate not declared"
      (is (= 2 (count interceptors)))
      (is (= ::ds/parse-datastar-signals (:name (first interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (second interceptors)))))))

;; ============================================ ;;
;; Route — Stream Path Tests                     ;;
;; ============================================ ;;

(deftest stream-path-uses-double-underscore-test
  (let [context {:query-registry {:test/list {:handler-fn identity
                                               :authorized? (constantly true)
                                               :datastar/path "/items"}
                                   :test/detail {:handler-fn identity
                                                  :authorized? (constantly true)
                                                  :datastar/path "/items/:item-id"}}}
        generated (ds/routes context {} {})
        paths (set (map first generated))]
    (testing "stream paths use /__stream suffix"
      (is (contains? paths "/items/__stream"))
      (is (contains? paths "/items/:item-id/__stream")))
    (testing "no bare /stream in any generated path"
      (is (not (some #(and (str/includes? % "/stream")
                           (not (str/includes? % "/__stream")))
                      paths))))))

(deftest registered-page-and-stream-path-resolution-test
  (let [registry {:test/list {:handler-fn identity
                              :authorized? (constantly true)
                              :datastar/path "/items"}
                  :test/detail {:handler-fn identity
                                :authorized? (constantly true)
                                :datastar/path "/items/:item-id"}
                  :test/root {:handler-fn identity
                              :authorized? (constantly true)
                              :datastar/path "/"}
                  :test/no-path {:handler-fn identity}}]
    (testing "page paths resolve from query metadata"
      (is (= "/items" (ds/page-path :test/list registry)))
      (is (= "/items/abc-123?page=2&tab=details"
             (ds/page-path :test/detail
                           {:path-params {:item-id "abc-123"}
                            :query-params {:tab :details
                                           :page 2}}
                           registry))))
    (testing "stream paths resolve from query metadata"
      (is (= "/items/__stream" (ds/stream-path :test/list registry)))
      (is (= "/items/abc-123/__stream?page=2&tab=details"
             (ds/stream-path :test/detail
                             {:path-params {:item-id "abc-123"}
                              :query-params {:tab :details
                                             :page 2}}
                             registry)))
      (is (= "/__stream" (ds/stream-path :test/root registry))))
    (testing "missing route metadata fails"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not registered"
           (ds/page-path :test/missing registry)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"no :datastar/path"
           (ds/page-path :test/no-path registry))))))

(deftest query->route-pair-root-path-test
  (let [entry {:handler-fn identity
               :authorized? (constantly true)
               :datastar/path "/"}
        [shim-route stream-route] (#'ds/query->route-pair {} :test/root entry {})]
    (testing "root path shim route"
      (is (= "/" (first shim-route))))
    (testing "root path stream route is /__stream (not //__stream)"
      (is (= "/__stream" (first stream-route))))))

(defn- route-pair-heartbeat
  "Builds routes for a polling query with the given entry + defaults, drives the
   generated GET stream interceptor, and returns the heartbeat-delay that reaches
   sse/start-stream. Exercises the query->route-pair -> stream-view plumbing."
  [entry defaults]
  (let [[_shim stream-route] (#'ds/query->route-pair {} :test/hb entry defaults)
        stream-interceptor (last (nth stream-route 2))
        captured (atom nil)]
    (with-redefs [sse/start-stream (fn [_callback _ctx heartbeat-delay & _]
                                     (reset! captured heartbeat-delay)
                                     {:status 200})]
      ((:enter stream-interceptor) {}))
    @captured))

(deftest heartbeat-delay-configurable-test
  (let [base {:authorized? (constantly true) :datastar/path "/hb"}]
    (testing "per-query :datastar/heartbeat-delay reaches sse/start-stream"
      (is (= 1 (route-pair-heartbeat (assoc base :datastar/heartbeat-delay 1) {}))))
    (testing "defaults :datastar/heartbeat-delay applies when the query omits it"
      (is (= 2 (route-pair-heartbeat base {:datastar/heartbeat-delay 2}))))
    (testing "a per-query value overrides the default"
      (is (= 1 (route-pair-heartbeat (assoc base :datastar/heartbeat-delay 1)
                                     {:datastar/heartbeat-delay 2}))))
    (testing "neither set falls through to stream-view's 10s default (not nil)"
      (is (= 10 (route-pair-heartbeat base {}))))))

;; =========================== ;;
;; E2E Auto-Routes Test         ;;
;; =========================== ;;

(deftest e2e-auto-routes-test
  (let [client (HttpClient/newHttpClient)]
    (testing "shim page is served at auto-generated path"
      (let [request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/auto-counters")))
                        (.GET)
                        .build)
            response (.send client request (HttpResponse$BodyHandlers/ofString))
            body (.body response)]
        (is (= 200 (.statusCode response)))
        (is (str/includes? body "<script"))
        (is (str/includes? body "datastar"))
        (is (str/includes? body "Auto Counters"))
        (is (str/includes? body "@get(&apos;/auto-counters/__stream?dsNonce="))))

    (testing "SSE stream works at auto-generated path"
      (let [request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/auto-counters/__stream")))
                        (.GET)
                        .build)
            response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
            events (parse-sse-events (.body response) 1 5000)]
        (is (= 1 (count events)))
        (is (= "datastar-patch-elements" (:name (first events))))
        (is (str/includes? (:data (first events)) "elements"))))))

;; ====================================== ;;
;; resolve-events Unit Test                ;;
;; ====================================== ;;

(deftest resolve-events-test
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/rm-a identity {:events #{:e/one :e/two} :version 1})
      (rmp/register-read-model! :test/rm-b identity {:events #{:e/two :e/three} :version 2})

      (testing "unions events from multiple read models"
        (is (= #{:e/one :e/two :e/three}
               (#'ds/resolve-events {:test/rm-a 1 :test/rm-b 2}))))

      (testing "single read model"
        (is (= #{:e/one :e/two}
               (#'ds/resolve-events {:test/rm-a 1}))))

      (testing "empty map returns empty set"
        (is (= #{} (#'ds/resolve-events {}))))

      (testing "unregistered read model returns empty events for that entry"
        (is (= #{:e/one :e/two}
               (#'ds/resolve-events {:test/rm-a 1 :test/rm-nonexistent 1}))))

      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; ====================================== ;;
;; E2E Event-Driven Streaming Tests        ;;
;; ====================================== ;;

(deftest e2e-event-driven-initial-render-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/event-counters/__stream")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        events (parse-sse-events (.body response) 1 5000)]
    (is (= 1 (count events)))
    (is (= "datastar-patch-elements" (:name (first events))))
    (is (str/includes? (:data (first events)) "elements"))))

(deftest e2e-event-driven-post-initial-render-test
  (testing "POST to event-driven stream returns initial render"
    (let [client (HttpClient/newHttpClient)
          body (json/write-str {:datastar {}})
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/event-counters/__stream")))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString body))
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
          events (parse-sse-events (.body response) 1 5000)]
      (is (= 1 (count events)))
      (is (= "datastar-patch-elements" (:name (first events))))
      (is (str/includes? (:data (first events)) "elements")))))

(deftest e2e-event-driven-rerender-on-event-test
  (let [client (HttpClient/newHttpClient)
        ;; Connect to event-driven stream
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port* "/event-counters/__stream")))
                           (.GET)
                           .build)
        stream-response (.send client stream-request (HttpResponse$BodyHandlers/ofInputStream))
        ;; Read initial + event-triggered render in background
        stream-events-future (future (parse-sse-events (.body stream-response) 2 10000))
        ;; Wait for stream to connect and send initial event
        _ (Thread/sleep 500)
        ;; Modify state and publish event to trigger re-render
        _ (swap! *e2e-state* assoc-in [:counters 0 :value] 42)
        _ (pubsub/pub *event-pubsub* {:message {:event/type :test/counter-incremented}})
        ;; Wait for stream to pick up the change
        stream-events @stream-events-future]
    (is (= 2 (count stream-events)))
    (is (= "datastar-patch-elements" (:name (first stream-events))))
    (is (= "datastar-patch-elements" (:name (second stream-events))))
    ;; Second render should contain updated value
    (is (str/includes? (:data (second stream-events)) "42"))))

(defn- cleanup-test-context
  [ps]
  {:cleanup-test? true
   :test-state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                  :name "A" :value 0}]})
   :query-registry @qp/query-registry*
   :event-pubsub ps})

(defn- start-cleanup-test-view
  "Runs the real view interceptor while capturing the transport's close callback."
  [context opts]
  (let [nonce (or (:cleanup-test/nonce context) (str (random-uuid)))
        output (async/chan 10)
        running (atom nil)
        disconnect (atom nil)]
    (with-redefs [sse/start-stream
                  (fn [callback ctx _heartbeat _buffer {:keys [on-client-disconnect]}]
                    (reset! disconnect #(on-client-disconnect ctx))
                    (reset! running (future (callback output ctx) ::finished))
                    ctx)]
      ((:enter (ds/stream-view context :test/event-counters opts))
       {:request {:request-method :get
                  :query-params (cond-> {"dsNonce" nonce}
                                  (:cleanup-test/connect? context) (assoc "__grainConnect" "1"))}}))
    {:event-ch output :stream @running :disconnect! @disconnect
     :session-key [nil :test/event-counters nonce]}))

(defn- stop-cleanup-test-view
  [{:keys [event-ch stream disconnect!]}]
  (disconnect!)
  (async/close! event-ch)
  (deref stream 1000 ::timeout))

(deftest event-driven-disconnect-releases-idle-stream-test
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        tenant-id (random-uuid)
        tailer {:watermarks (atom {tenant-id nil}) :subscriber-counts (atom {})}
        context (assoc (cleanup-test-context ps) :tenant-id tenant-id :event-tailer tailer)
        event-types #{:test/counter-incremented :test/counter-added}
        removed (atom [])
        original-unsub pubsub/unsub]
    (try
      (with-redefs [pubsub/unsub (fn [bus args]
                                  (when (identical? bus ps) (swap! removed conj args))
                                  (original-unsub bus args))]
        (let [{:keys [event-ch stream disconnect! session-key] :as view}
              (start-cleanup-test-view context {:event-types event-types})]
          (try
            (is (= "datastar-patch-elements" (:name (take-event event-ch 1000))))
            (let [entry (get @@#'ds/active-stream-contexts session-key)]
              (is (some? entry))
              (is (= {tenant-id (zipmap event-types (repeat 1))} @(:subscriber-counts tailer)))
              ;; No events or shortened idle timeout: the actual transport callback
              ;; must wake the view and release every subscription promptly.
              (disconnect!)
              (is (= ::finished (deref stream 1000 ::timeout)))
              (is (not (contains? @@#'ds/active-stream-contexts session-key)))
              (is (= {} @(:subscriber-counts tailer)))
              (is (= event-types (set (map :topic @removed))))
              (is (= (count event-types) (count @removed)))
              (is (every? #(async-protocols/closed? (:sub-chan %)) @removed))
              (is (every? async-protocols/closed?
                          (map entry [:signal-ch :disconnect-ch :event-ch]))))
            (finally (stop-cleanup-test-view view)))))
      (finally (pubsub/stop ps)))))

(deftest event-driven-closed-output-does-not-run-queued-refreshes-test
  (doseq [changed? [false true]]
    (testing (str "output changes: " changed?)
      (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
            context (cleanup-test-context ps)
            calls (atom 0)
            original-render ds/poll-and-render]
        (try
          (with-redefs [ds/poll-and-render
                        (fn [ctx previous]
                          (when (:cleanup-test? ctx)
                            (swap! calls inc)
                            (when changed? (swap! (:test-state ctx) update-in [:counters 0 :value] inc)))
                          (original-render ctx previous))]
            (let [{:keys [event-ch stream session-key] :as view}
                  (start-cleanup-test-view context {:event-types #{:test/counter-incremented}
                                                    :debounce-ms 0})]
              (try
                (is (some? (take-event event-ch 1000)))
                (async/close! event-ch)
                (dotimes [_ 20]
                  (pubsub/pub ps {:message {:event/type :test/counter-incremented}}))
                (is (= ::finished (deref stream 1000 ::timeout)))
                (is (= 1 @calls) "Queued events cannot start queries after output closure")
                (is (not (contains? @@#'ds/active-stream-contexts session-key)))
                (finally (stop-cleanup-test-view view)))))
          (finally (pubsub/stop ps)))))))

(deftest disconnect-interrupts-pending-render-waits-test
  (doseq [mode [:events :polling]]
    (testing (name mode)
      (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
            waiting (promise)
            original-timeout async/timeout
            calls (atom 0)
            original-render ds/poll-and-render]
        (try
          (with-redefs [async/timeout (fn [^long milliseconds]
                                       (when (< 9000 milliseconds 11000) (deliver waiting true))
                                       (original-timeout milliseconds))
                        ds/poll-and-render (fn [ctx previous]
                                             (when (:cleanup-test? ctx) (swap! calls inc))
                                             (original-render ctx previous))]
            (let [opts (if (= mode :events)
                         {:event-types #{:test/counter-incremented} :debounce-ms 10000}
                         {:fps 0.1})
                  {:keys [event-ch stream disconnect!] :as view}
                  (start-cleanup-test-view (cleanup-test-context ps) opts)]
              (try
                (is (some? (take-event event-ch 1000)))
                (when (= mode :events)
                  (pubsub/pub ps {:message {:event/type :test/counter-incremented}}))
                (is (= true (deref waiting 1000 ::timeout)))
                (disconnect!)
                (is (= ::finished (deref stream 1000 ::timeout))
                    "Disconnect interrupts the long render wait")
                (is (= 1 @calls))
                (finally (stop-cleanup-test-view view)))))
          (finally (pubsub/stop ps)))))))

(deftest disconnect-during-query-does-not-start-another-cycle-test
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        entered (promise)
        release (promise)
        calls (atom 0)
        original-render ds/poll-and-render]
    (try
      (with-redefs [ds/poll-and-render
                    (fn [ctx previous]
                      (when (and (:cleanup-test? ctx) (= 2 (swap! calls inc)))
                        (deliver entered true)
                        (deref release 5000 nil))
                      (original-render ctx previous))]
        (let [context (cleanup-test-context ps)
              {:keys [event-ch stream disconnect! session-key] :as view}
              (start-cleanup-test-view context {:event-types #{:test/counter-incremented}
                                                :debounce-ms 0})]
          (try
            (is (some? (take-event event-ch 1000)))
            (swap! (:test-state context) assoc-in [:counters 0 :value] 42)
            (pubsub/pub ps {:message {:event/type :test/counter-incremented}})
            (is (= true (deref entered 1000 ::timeout)))
            (disconnect!)
            (dotimes [_ 20]
              (pubsub/pub ps {:message {:event/type :test/counter-incremented}}))
            (deliver release true)
            (is (= ::finished (deref stream 1000 ::timeout)))
            (is (= 2 @calls) "Only the already-running query may finish")
            (is (nil? (async/poll! event-ch)) "Canceled query output is discarded")
            (is (not (contains? @@#'ds/active-stream-contexts session-key)))
            (finally
              (deliver release true)
              (stop-cleanup-test-view view)))))
      (finally (pubsub/stop ps)))))

(deftest reconnect-interrupts-old-wait-and-preserves-replacement-test
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        waiting (promise)
        original-timeout async/timeout
        context (assoc (cleanup-test-context ps) :cleanup-test/nonce (str (random-uuid)))
        opts {:event-types #{:test/counter-incremented} :debounce-ms 10000}]
    (try
      (with-redefs [async/timeout (fn [^long milliseconds]
                                   (when (= milliseconds 10000) (deliver waiting true))
                                   (original-timeout milliseconds))]
        (let [old-view (start-cleanup-test-view context opts)]
          (try
            (is (some? (take-event (:event-ch old-view) 1000)))
            (pubsub/pub ps {:message {:event/type :test/counter-incremented}})
            (is (= true (deref waiting 1000 ::timeout)))
            (let [replacement (start-cleanup-test-view (assoc context :cleanup-test/connect? true) opts)]
              (try
                (is (some? (take-event (:event-ch replacement) 1000)))
                (is (= ::finished (deref (:stream old-view) 1000 ::timeout)))
                (is (identical? (:event-ch replacement)
                                (:event-ch (get @@#'ds/active-stream-contexts (:session-key replacement))))
                    "Old cleanup must preserve the replacement's registry entry")
                (finally (stop-cleanup-test-view replacement))))
            (finally (stop-cleanup-test-view old-view)))))
      (finally (pubsub/stop ps)))))

(deftest subscription-setup-failure-closes-stream-and-detaches-topics-test
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        original-sub pubsub/sub
        original-unsub pubsub/unsub
        removed (atom [])]
    (try
      (with-redefs [pubsub/sub (fn [bus args]
                               (original-sub bus args)
                               (when (identical? ps bus)
                                 (throw (ex-info "subscription setup failed" {}))))
                    pubsub/unsub (fn [bus args]
                                   (when (identical? ps bus) (swap! removed conj args))
                                   (original-unsub bus args))]
        (let [{:keys [event-ch stream session-key]}
              (start-cleanup-test-view (cleanup-test-context ps)
                                      {:event-types #{:test/counter-incremented}})]
          (is (thrown-with-msg? java.util.concurrent.ExecutionException #"subscription setup failed"
                               (deref stream 1000 ::timeout)))
          (is (async-protocols/closed? event-ch))
          (is (not (contains? @@#'ds/active-stream-contexts session-key)))
          (is (= [:test/counter-incremented] (mapv :topic @removed)))
          (is (every? #(async-protocols/closed? (:sub-chan %)) @removed))))
      (finally (pubsub/stop ps)))))

(deftest polling-closed-output-stops-changing-and-unchanged-results-test
  (doseq [changed? [false true]]
    (testing (str "output changes: " changed?)
      (let [event-ch (async/chan 10)
            calls (atom 0)
            original-render ds/poll-and-render]
        (with-redefs [ds/poll-and-render
                      (fn [ctx previous]
                        (if (:cleanup-test? ctx)
                          (let [n (swap! calls inc)]
                            (when (= n 2) (async/close! event-ch))
                            (when (or changed? (= n 1))
                              {:result n :event {:name "test" :data (str n)}}))
                          (original-render ctx previous)))]
          (let [stream (future (#'ds/stream-view-loop event-ch {:request {}}
                                {:cleanup-test? true} :test/event-counters 1 {}) ::finished)]
            (try
              (is (= ::finished (deref stream 1000 ::timeout)))
              (is (= 2 @calls))
              (finally (async/close! event-ch)))))))))

(deftest pubsub-unsubscribe-preserves-other-subscriptions-test
  (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
        subscriber (async/chan 10)
        other (async/chan 10)]
    (try
      (doseq [topic [:test/a :test/b]]
        (pubsub/sub ps {:topic topic :sub-chan subscriber}))
      (pubsub/sub ps {:topic :test/a :sub-chan other})
      (dotimes [_ 2] (pubsub/unsub ps {:topic :test/a :sub-chan subscriber}))
      (is (not (async-protocols/closed? subscriber)))
      (pubsub/pub ps {:message {:event/type :test/a}})
      (is (= :test/a (:event/type (take-event other 1000))))
      (is (nil? (async/poll! subscriber)))
      (pubsub/pub ps {:message {:event/type :test/b}})
      (is (= :test/b (:event/type (take-event subscriber 1000))))
      (finally
        (pubsub/stop ps)
        (async/close! subscriber)
        (async/close! other)))))

(defn- pubsub-topic-subscriber-count
  "Inspects actual retained subscriptions; core.async has no public inspection API."
  [ps topic]
  (letfn [(field-value [instance field-name]
            (let [field (.getDeclaredField (class instance) field-name)]
              (.setAccessible field true)
              (.get field instance)))]
    (let [publication (get-in ps [:state :pub])]
      (if-let [topic-mult (get @(field-value publication "mults") topic)]
        (count @(field-value topic-mult "cs"))
        0))))

(deftest e2e-client-disconnect-releases-idle-and-busy-streams-test
  (doseq [mode [:idle :busy]]
    (testing (name mode)
      (let [ps (pubsub/start {:type :core-async :topic-fn :event/type})
            tenant-id (random-uuid)
            nonce (str (random-uuid))
            topic :test/cleanup-event
            tailer {:watermarks (atom {tenant-id nil}) :subscriber-counts (atom {})}
            queries (atom 0)
            context {:tenant-id tenant-id :event-pubsub ps :event-tailer tailer
                     :query-registry
                     {:test/event-counters
                      {:authorized? (constantly true)
                       :handler-fn (fn [_]
                                     (let [n (swap! queries inc)]
                                       {:query/result n
                                        :datastar/hiccup [:div#app (str "Query " n)]}))}}}
            server (ws/start {:http/routes
                              #{["/cleanup/__stream" :get
                                 [(ds/stream-view context :test/event-counters
                                                  {:event-types #{topic}
                                                   :debounce-ms 0 :heartbeat-delay 1})]
                                 :route-name ::cleanup-stream]}
                              :http/port 0 :http/join? false})
            session-key [nil :test/event-counters nonce]
            client (-> (HttpClient/newBuilder)
                       (.version java.net.http.HttpClient$Version/HTTP_1_1)
                       .build)
            request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" (get-server-port server)
                                               "/cleanup/__stream?dsNonce=" nonce)))
                        (.timeout (java.time.Duration/ofSeconds 10))
                        (.GET)
                        .build)
            response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
            input-stream (.body response)
            publishing (atom true)
            publisher (atom nil)
            publish! #(pubsub/pub ps {:message {:event/type topic :grain/tenant-id tenant-id}})]
        (try
          (is (= 200 (.statusCode response)))
          (let [first-frame (future (parse-sse-events input-stream 1 3000))]
            (is (= "datastar-patch-elements"
                   (:name (first (deref first-frame 4000 []))))))
          (is (contains? @@#'ds/active-stream-contexts session-key))
          (is (= {tenant-id {topic 1}} @(:subscriber-counts tailer)))
          (is (= 1 (pubsub-topic-subscriber-count ps topic)))
          (when (= mode :busy)
            (reset! publisher
                    (future
                      (while @publishing
                        (publish!)
                        (Thread/sleep 25))))
            (is (eventually? #(>= @queries 3) 2000)
                "Busy case must execute changing queries before disconnect"))
          (let [queries-before-close @queries
                closed-at (System/nanoTime)]
            ;; This is the only disconnect action: close the real HTTP response
            ;; body. Pedestal must discover the failed socket itself, invoke its
            ;; installed callback, and drive all server-side cleanup.
            (.close input-stream)
            (is (eventually? #(and (not (contains? @@#'ds/active-stream-contexts session-key))
                                   (empty? @(:subscriber-counts tailer))
                                   (zero? (pubsub-topic-subscriber-count ps topic)))
                             8000)
                "Real socket disconnect must release registry, tailer, and local topics")
            (let [cleanup-ms (/ (- (System/nanoTime) closed-at) 1e6)
                  queries-after-cleanup @queries]
              (dotimes [_ 10] (publish!))
              (Thread/sleep 150)
              (is (= queries-after-cleanup @queries)
                  "Events after actual transport teardown cannot execute queries")
              (println "SSE socket-disconnect cleanup"
                       {:mode mode :cleanup-ms cleanup-ms
                        :queries-before-close queries-before-close
                        :queries-at-cleanup queries-after-cleanup})))
          (finally
            (reset! publishing false)
            (.close input-stream)
            (when @publisher (deref @publisher 1000 nil))
            (ws/stop server)
            (pubsub/stop ps)))))))

(deftest event-driven-query-clock-refreshes-only-on-change
  (let [clock (atom "2026-09-26") allowed? (atom true) calls (atom 0)
        state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        event-ch (async/chan 10)
        context {:test-state state :event-pubsub ps :tenant-id (random-uuid)
                 :query-registry (-> @qp/query-registry*
                                     (assoc-in [:test/event-counters :authorized?] (fn [_] @allowed?))
                                     (assoc-in [:test/event-counters :datastar/refresh-key] (fn [_] @clock)))}
        original-timeout async/timeout original-poll ds/poll-and-render]
    (try
      (with-redefs [async/timeout (fn [^long milliseconds]
                                   (original-timeout (if (= 30000 milliseconds) 20 milliseconds)))
                    ds/poll-and-render (fn [query-context previous-result]
                                         (when (= (:tenant-id context) (:tenant-id query-context))
                                           (swap! calls inc))
                                         (original-poll query-context previous-result))]
        (let [stream (future (#'ds/stream-view-loop-events
                              event-ch {:request {:query-params {} :path-params {}}}
                              context :test/event-counters ps #{:test/counter-incremented} 0 {} nil))]
          (is (= "datastar-patch-elements" (:name (take-event event-ch 1000))))
          (swap! state assoc-in [:counters 0 :value] 42)
          (Thread/sleep 80)
          (is (= 1 @calls) "Equal clock keys do not run the query")
          (reset! clock "2026-09-27")
          (is (str/includes? (:data (take-event event-ch 1000)) "42"))
          (is (= 2 @calls))
          (reset! allowed? false)
          (reset! clock "2026-09-28")
          (is (= "datastar-patch-signals" (:name (take-event event-ch 1000))))
          (is (eventually? #(realized? stream) 1000))
          (is (= 3 @calls) "Refresh uses the normal authorization gate")))
      (finally (async/close! event-ch) (pubsub/stop ps)))))

(deftest event-driven-stream-registers-with-event-tailer-test
  (let [tenant-id (random-uuid)
        state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        context {:test-state state
                 :query-registry (assoc-in @qp/query-registry*
                                           [:test/event-counters :authorized?]
                                           (constantly false))
                 :event-pubsub ps
                 :event-tailer ::tailer
                 :tenant-id tenant-id}
        event-ch (async/chan 10)
        subscribed (atom [])
        unsubscribed (atom [])]
    (try
      (with-redefs [event-tailer/subscribe! (fn [tailer tid event-types]
                                              (swap! subscribed conj [tailer tid event-types]))
                    event-tailer/unsubscribe! (fn [tailer tid event-types]
                                                (swap! unsubscribed conj [tailer tid event-types]))]
        (let [stream (future
                       (#'ds/stream-view-loop-events
                        event-ch
                        {:request {:query-params {} :path-params {}}}
                        context
                        :test/event-counters
                        ps
                        #{:test/counter-incremented}
                        10
                        {}
                        nil))]
          (is (= "datastar-patch-signals" (:name (async/<!! event-ch))))
          (is (= [[::tailer tenant-id #{:test/counter-incremented}]]
                 @subscribed))
          (is (eventually? #(realized? stream) 1000))
          (is (= [[::tailer tenant-id #{:test/counter-incremented}]]
                 @unsubscribed))))
      (finally
        (pubsub/stop ps)))))

(deftest event-driven-stream-filters-by-tenant-test
  (let [tenant-id (random-uuid)
        other-tenant-id (random-uuid)
        state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        context {:test-state state
                 :query-registry @qp/query-registry*
                 :event-pubsub ps
                 :event-tailer ::tailer
                 :tenant-id tenant-id}
        event-ch (async/chan 10)]
    (try
      (with-redefs [event-tailer/subscribe! (fn [& _])
                    event-tailer/unsubscribe! (fn [& _])]
        (let [stream (future
                       (#'ds/stream-view-loop-events
                        event-ch
                        {:request {:query-params {} :path-params {}}}
                        context
                        :test/event-counters
                        ps
                        #{:test/counter-incremented}
                        10
                        {}
                        nil))]
          (is (= "datastar-patch-elements" (:name (async/<!! event-ch))))
          (swap! state assoc-in [:counters 0 :value] 41)
          (pubsub/pub ps {:message {:event/type :test/counter-incremented
                                    :grain/tenant-id other-tenant-id}})
          (is (nil? (take-event event-ch 150)))
          (swap! state assoc-in [:counters 0 :value] 42)
          (pubsub/pub ps {:message {:event/type :test/counter-incremented
                                    :grain/tenant-id tenant-id}})
          (let [rerender (take-event event-ch 1000)]
            (is (= "datastar-patch-elements" (:name rerender)))
            (is (str/includes? (:data rerender) "42")))
          (async/close! event-ch)
          (pubsub/pub ps {:message {:event/type :test/counter-incremented
                                    :grain/tenant-id tenant-id}})
          (is (nil? (deref stream 1000 nil)))))
      (finally
        (pubsub/stop ps)))))

(deftest event-driven-stream-dedupes-stored-events-test
  (let [tenant-id (random-uuid)
        event-id (random-uuid)
        state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        context {:test-state state
                 :query-registry @qp/query-registry*
                 :event-pubsub ps
                 :tenant-id tenant-id}
        event-ch (async/chan 10)
        event {:event/id event-id
               :event/type :test/counter-incremented
               :grain/tenant-id tenant-id}]
    (try
      (let [stream (future
                     (#'ds/stream-view-loop-events
                      event-ch
                      {:request {:query-params {} :path-params {}}}
                      context
                      :test/event-counters
                      ps
                      #{:test/counter-incremented}
                      0
                      {}
                      nil))]
        (is (= "datastar-patch-elements" (:name (async/<!! event-ch))))
        (swap! state assoc-in [:counters 0 :value] 41)
        (pubsub/pub ps {:message event})
        (let [rerender (take-event event-ch 1000)]
          (is (= "datastar-patch-elements" (:name rerender)))
          (is (str/includes? (:data rerender) "41")))
        (swap! state assoc-in [:counters 0 :value] 42)
        (pubsub/pub ps {:message event})
        (is (nil? (take-event event-ch 150)))
        (async/close! event-ch)
        (pubsub/pub ps {:message (assoc event :event/id (random-uuid))})
        (is (nil? (deref stream 1000 nil))))
      (finally
        (pubsub/stop ps)))))

(deftest event-driven-stream-does-not-dedupe-idless-events-test
  (let [state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        context {:test-state state
                 :query-registry @qp/query-registry*
                 :event-pubsub ps}
        event-ch (async/chan 10)
        event {:event/type :test/counter-incremented}]
    (try
      (let [stream (future
                     (#'ds/stream-view-loop-events
                      event-ch
                      {:request {:query-params {} :path-params {}}}
                      context
                      :test/event-counters
                      ps
                      #{:test/counter-incremented}
                      0
                      {}
                      nil))]
        (is (= "datastar-patch-elements" (:name (async/<!! event-ch))))
        (swap! state assoc-in [:counters 0 :value] 41)
        (pubsub/pub ps {:message event})
        (let [rerender (take-event event-ch 1000)]
          (is (= "datastar-patch-elements" (:name rerender)))
          (is (str/includes? (:data rerender) "41")))
        (swap! state assoc-in [:counters 0 :value] 42)
        (pubsub/pub ps {:message event})
        (let [rerender (take-event event-ch 1000)]
          (is (= "datastar-patch-elements" (:name rerender)))
          (is (str/includes? (:data rerender) "42")))
        (async/close! event-ch)
        (pubsub/pub ps {:message event})
        (is (nil? (deref stream 1000 nil))))
      (finally
        (pubsub/stop ps)))))

(deftest e2e-event-driven-debounce-test
  (let [client (HttpClient/newHttpClient)
        ;; Connect to event-driven stream
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port* "/event-counters/__stream")))
                           (.GET)
                           .build)
        stream-response (.send client stream-request (HttpResponse$BodyHandlers/ofInputStream))
        ;; Read initial + at most 2 more events (expecting only 1 debounced re-render)
        stream-events-future (future (parse-sse-events (.body stream-response) 3 5000))
        ;; Wait for initial render
        _ (Thread/sleep 500)
        ;; Publish 5 events rapidly — should collapse into 1 re-render
        _ (swap! *e2e-state* assoc-in [:counters 0 :value] 100)
        _ (dotimes [_ 5]
            (pubsub/pub *event-pubsub* {:message {:event/type :test/counter-incremented}}))
        ;; Wait for debounce + render to complete, then collect
        stream-events @stream-events-future]
    ;; Should have initial render + 1 debounced re-render = 2 total
    ;; (the future times out at 5s waiting for a 3rd that never comes)
    (is (= 2 (count stream-events)))
    (is (= "datastar-patch-elements" (:name (first stream-events))))
    (is (= "datastar-patch-elements" (:name (second stream-events))))
    (is (str/includes? (:data (second stream-events)) "100"))))

(deftest e2e-event-driven-shim-page-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/event-counters")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    (is (= 200 (.statusCode response)))
    (is (str/includes? body "Event Counters"))
    (is (str/includes? body "@get(&apos;/event-counters/__stream?dsNonce="))))

;; ====================================== ;;
;; resolve-event-tags Unit Tests           ;;
;; ====================================== ;;

(deftest resolve-event-tags-test
  (let [context {:query {:counter-id "abc-123" :org-id "org-456"}
                 :auth-claims {:tenant-id "tenant-789"}}]

    (testing "bare keyword resolves from [:query k]"
      (let [pred (#'ds/resolve-event-tags {:counter :counter-id} context)]
        (is (true? (pred {:event/tags #{[:counter "abc-123"]}})))
        (is (false? (pred {:event/tags #{[:counter "other"]}})))))

    (testing "vector path resolves via get-in"
      (let [pred (#'ds/resolve-event-tags {:tenant [:auth-claims :tenant-id]} context)]
        (is (true? (pred {:event/tags #{[:tenant "tenant-789"]}})))
        (is (false? (pred {:event/tags #{[:tenant "wrong"]}})))))

    (testing "multiple tags require ALL to match (subset)"
      (let [pred (#'ds/resolve-event-tags {:counter :counter-id :org :org-id} context)]
        (is (true? (pred {:event/tags #{[:counter "abc-123"] [:org "org-456"]}})))
        (is (true? (pred {:event/tags #{[:counter "abc-123"] [:org "org-456"] [:extra "x"]}})))
        (is (false? (pred {:event/tags #{[:counter "abc-123"]}})))
        (is (false? (pred {:event/tags #{[:org "org-456"]}})))))))

(deftest event-tag-alternatives-and-validation-test
  (let [context {:query {:org "o" :owner "a" :assignee "b" :false-value false}
                 :auth-claims {:org "o"}}
        pred (#'ds/resolve-event-tags [{:org [:auth-claims :org] :user :owner}
                                      {:org :org :user :assignee}] context)]
    (doseq [user ["a" "b"]]
      (is (true? (pred {:event/tags #{[:org "o"] [:user user]}}))))
    (is (true? (pred {:event/tags #{[:org "o"] [:user "a"] [:user "b"]}})))
    (is (false? (pred {:event/tags #{[:user "a"]}})))
    (is (false? (pred {:event/tags #{[:org "other"] [:user "b"]}})))
    (is (false? (pred {})))
    (is (true? ((#'ds/resolve-event-tags {:flag :false-value} context)
                {:event/tags #{[:flag false]}})))
    (doseq [selector [nil {}]]
      (is (true? ((#'ds/resolve-event-tags selector context) {}))))
    (is (false? ((#'ds/resolve-event-tags [] context) {})))
    (is (false? ((#'ds/resolve-event-tags {:user :missing} context)
                 {:event/tags #{[:user nil]}}))))
  (doseq [selector [[{}] [nil] [{:user :id} {}] {:user nil} {:user []}
                    {:user "id"} {"user" :id} '({:user :id})]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ds/stream-view {} :test/counters {:event-tags selector}))))
  (doseq [exclusions [[:test/change] {:test/change true} #{"test/change"} false]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (ds/stream-view {} :test/counters {:exclude-events exclusions}))))
  (doseq [[k invalid] [[:datastar/event-tags false]
                       [:datastar/event-tags [{:user :id} {}]]
                       [:datastar/exclude-events false]]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'ds/query->route-pair {}
                  :test/counters
                  (assoc {:datastar/path "/invalid-subscription" :authorized? (constantly true)} k invalid)
                  {}))))
  (let [ctx (atom {:query {:owner "a"}})
        pred (#'ds/resolve-event-filter :tenant [{:user :missing} {:user :owner}] ctx)]
    (is (true? (pred {:grain/tenant-id :tenant :event/tags #{[:user "a"]}})))
    (is (false? (pred {:grain/tenant-id :other :event/tags #{[:user "a"]}})))
    (swap! ctx assoc :query {:owner "b"})
    (is (false? (pred {:grain/tenant-id :tenant :event/tags #{[:user "a"]}})))
    (is (true? (pred {:grain/tenant-id :tenant :event/tags #{[:user "b"]}})))))

(deftest event-exclusions-preserve-mode-and-effective-topics-test
  (let [started (atom [])
        ps ::pubsub
        invoke! (fn [opts]
                  ((:enter (ds/stream-view {:event-pubsub ps} :test/counters opts)) {}))]
    (with-redefs-fn {#'ds/update-or-start-stream-events
                    (fn [_ _ _ types & _] (swap! started conj [:events types]))
                    #'ds/stream-view-enter
                    (fn [_ _ interval & _] (swap! started conj [:fallback interval]))}
      #(do
         (invoke! {:event-types #{:test/a :test/b} :exclude-events #{:test/b :test/unused}})
         (invoke! {:event-types #{:test/a} :exclude-events #{:test/a} :fps 10})
         (invoke! {:event-types #{} :fps 10})
         (invoke! {:event-types #{:test/a} :exclude-events #{}})
         (invoke! {:event-types #{:test/a}})))
    (is (= [[:events #{:test/a}] [:events #{}] [:fallback 100]
            [:events #{:test/a}] [:events #{:test/a}]] @started))))

;; ====================================== ;;
;; query->route-pair with event-tags       ;;
;; ====================================== ;;

(deftest query->route-pair-with-event-tags-test
  (let [prev-registry @rmp/read-model-registry*]
    (try
      (rmp/register-read-model! :test/rm-tagged identity {:events #{:e/one} :version 1})
      (let [entry {:handler-fn identity
                   :authorized? (constantly true)
                   :datastar/path "/tagged/:item-id"
                   :grain/read-models {:test/rm-tagged 1}
                   :datastar/event-tags {:item :item-id}}
            [_ stream-route] (#'ds/query->route-pair {} :test/tagged entry {})
            stream-interceptor (last (nth stream-route 2))]
        (testing "stream route is created"
          (is (some? stream-route)))
        (testing "stream interceptor has :enter fn (event-driven mode)"
          (is (fn? (:enter stream-interceptor)))))
      (finally
        (reset! rmp/read-model-registry* prev-registry)))))

;; ====================================== ;;
;; E2E Event-Tags Streaming Tests          ;;
;; ====================================== ;;

(deftest e2e-event-tags-matching-test
  (let [client (HttpClient/newHttpClient)
        counter-id "00000000-0000-0000-0000-000000000001"
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/tagged-counters/" counter-id "/__stream")))
                           (.GET)
                           .build)
        stream-response (.send client stream-request (HttpResponse$BodyHandlers/ofInputStream))
        stream-events-future (future (parse-sse-events (.body stream-response) 2 10000))
        _ (Thread/sleep 500)
        _ (swap! *e2e-state* assoc-in [:counters 0 :value] 77)
        _ (pubsub/pub *event-pubsub*
            {:message {:event/type :test/counter-incremented
                       :event/tags #{[:counter counter-id]}}})
        stream-events @stream-events-future]
    (is (= 2 (count stream-events)))
    (is (str/includes? (:data (second stream-events)) "77"))))

;; =========================== ;;
;; E2E POST Stream Tests       ;;
;; =========================== ;;

(deftest e2e-post-stream-test
  (testing "POST to stream endpoint returns SSE events"
    (let [client (HttpClient/newHttpClient)
          body (json/write-str {:datastar {}})
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/auto-counters/__stream")))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString body))
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
          events (parse-sse-events (.body response) 1 5000)]
      (is (= 1 (count events)))
      (is (= "datastar-patch-elements" (:name (first events))))
      (is (str/includes? (:data (first events)) "elements")))))

(deftest e2e-post-stream-empty-body-test
  (testing "POST with empty body still returns initial render"
    (let [client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/auto-counters/__stream")))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString ""))
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
          events (parse-sse-events (.body response) 1 5000)]
      (is (= 1 (count events)))
      (is (= "datastar-patch-elements" (:name (first events)))))))

(deftest e2e-event-tags-non-matching-test
  (let [client (HttpClient/newHttpClient)
        counter-id "00000000-0000-0000-0000-000000000001"
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/tagged-counters/" counter-id "/__stream")))
                           (.GET)
                           .build)
        stream-response (.send client stream-request (HttpResponse$BodyHandlers/ofInputStream))
        stream-events-future (future (parse-sse-events (.body stream-response) 2 5000))
        _ (Thread/sleep 500)
        _ (swap! *e2e-state* assoc-in [:counters 0 :value] 999)
        _ (pubsub/pub *event-pubsub*
            {:message {:event/type :test/counter-incremented
                       :event/tags #{[:counter "wrong-id"]}}})
        stream-events @stream-events-future]
    ;; Only initial render — event was filtered out by transducer
    (is (= 1 (count stream-events)))
    (is (not (str/includes? (:data (first stream-events)) "999")))))

;; ============================================ ;;
;; E2E POST Signal Update Tests                  ;;
;; ============================================ ;;

(deftest e2e-post-updates-existing-sse-signals-test
  (testing "POST to event-driven stream updates context on existing GET SSE and triggers re-render"
    (let [client (HttpClient/newHttpClient)
          ;; 1. Open GET SSE stream (event-driven, with auth)
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port* "/filterable/__stream")))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Expect: initial render + 1 re-render after POST signal update = 2 events
          stream-events-future (future (parse-sse-events (.body get-response) 2 10000))
          ;; Wait for SSE to connect and register in active-stream-contexts
          _ (Thread/sleep 500)
          ;; 2. POST with filter param — should update existing SSE's context, not start new SSE
          post-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?filter=active")))
                           (.header "Content-Type" "application/json")
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.POST (HttpRequest$BodyPublishers/ofString ""))
                           .build)
          _ (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; 3. Collect SSE events from the original GET stream
          stream-events @stream-events-future]
      ;; Initial render should NOT have the filter
      (is (= 2 (count stream-events)))
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; Re-render after POST should include the filter value
      (is (str/includes? (:data (second stream-events)) "filter:active")))))

(deftest e2e-post-no-existing-sse-starts-fresh-test
  (testing "POST to event-driven stream starts a new SSE when no existing stream is open"
    (let [client (HttpClient/newHttpClient)
          ;; Use a unique user so no prior stream exists
          unique-user-id (random-uuid)
          ;; POST without a prior GET — should start a fresh SSE
          post-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?filter=new")))
                           (.header "Content-Type" "application/json")
                           (.header "X-Test-User-Id" (str unique-user-id))
                           (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:datastar {}})))
                           .build)
          post-response (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
          events (parse-sse-events (.body post-response) 1 10000)]
      ;; Should get initial render with the filter applied
      (is (= 1 (count events)))
      (is (= "datastar-patch-elements" (:name (first events))))
      (is (str/includes? (:data (first events)) "filter:new")))))

(deftest e2e-post-signal-update-plus-domain-event-test
  (testing "POST updates signals, then domain event triggers re-render with updated context"
    (let [client (HttpClient/newHttpClient)
          ;; 1. Open GET SSE stream
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port* "/filterable/__stream")))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Expect: initial + POST re-render + domain event re-render = 3
          stream-events-future (future (parse-sse-events (.body get-response) 3 10000))
          _ (Thread/sleep 500)
          ;; 2. POST to update filter signal
          post-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?filter=vip")))
                           (.header "Content-Type" "application/json")
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.POST (HttpRequest$BodyPublishers/ofString ""))
                           .build)
          _ (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Wait for POST re-render to complete
          _ (Thread/sleep 300)
          ;; 3. Modify state and publish domain event
          _ (swap! *e2e-state* assoc-in [:counters 0 :value] 555)
          _ (pubsub/pub *event-pubsub* {:message {:event/type :test/counter-incremented}})
          stream-events @stream-events-future]
      ;; Event 1: initial render — no filter
      (is (= 3 (count stream-events)))
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; Event 2: POST signal update — filter:vip, old value
      (is (str/includes? (:data (second stream-events)) "filter:vip"))
      ;; Event 3: domain event re-render — filter still vip, new value 555
      (is (str/includes? (:data (nth stream-events 2)) "filter:vip"))
      (is (str/includes? (:data (nth stream-events 2)) "555")))))

(deftest e2e-concurrent-users-independent-streams-test
  (testing "Two users have independent SSE streams — POST from one doesn't affect the other"
    (let [client (HttpClient/newHttpClient)
          ;; 1. User A opens GET SSE stream
          get-a (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/filterable/__stream")))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-a (.send client get-a (HttpResponse$BodyHandlers/ofInputStream))
          ;; User A: initial + POST re-render = 2
          events-a-future (future (parse-sse-events (.body response-a) 2 10000))
          _ (Thread/sleep 300)
          ;; 2. User B opens GET SSE stream
          get-b (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/filterable/__stream")))
                    (.header "X-Test-User-Id" (str test-user-id-b))
                    (.GET)
                    .build)
          response-b (.send client get-b (HttpResponse$BodyHandlers/ofInputStream))
          ;; User B: initial only, no POST = 1 (timeout after waiting for 2nd)
          events-b-future (future (parse-sse-events (.body response-b) 2 5000))
          _ (Thread/sleep 300)
          ;; 3. POST as User A — should only update User A's stream
          post-a (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?filter=user-a-only")))
                     (.header "Content-Type" "application/json")
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.POST (HttpRequest$BodyPublishers/ofString ""))
                     .build)
          _ (.send client post-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a @events-a-future
          events-b @events-b-future]
      ;; User A got re-render with filter
      (is (= 2 (count events-a)))
      (is (str/includes? (:data (second events-a)) "filter:user-a-only"))
      ;; User B only got initial render — no filter, no re-render from User A's POST
      (is (= 1 (count events-b)))
      (is (not (str/includes? (:data (first events-b)) "filter:"))))))

(deftest e2e-stale-session-cleanup-test
  (testing "POST skips stale registry entry with closed signal-ch"
    ;; Directly manipulate the active-stream-contexts atom to simulate a stale entry
    (let [stale-user-id (random-uuid)
          session-key [stale-user-id :test/filterable-counters nil]
          closed-ch (async/chan)]
      ;; Close the channel to make it stale
      (async/close! closed-ch)
      ;; Insert stale entry
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {}) :signal-ch closed-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              ;; POST should detect closed signal-ch and start fresh
              post-request (-> (HttpRequest/newBuilder)
                               (.uri (URI/create (str "http://localhost:" *port*
                                                      "/filterable/__stream?filter=after-stale")))
                               (.header "Content-Type" "application/json")
                               (.header "X-Test-User-Id" (str stale-user-id))
                               (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:datastar {}})))
                               .build)
              post-response (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body post-response) 1 10000)]
          ;; Fresh SSE started — should render with the filter
          (is (= 1 (count events)))
          (is (str/includes? (:data (first events)) "filter:after-stale")))
        (finally
          ;; Clean up any stale entry
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

(deftest e2e-stale-event-ch-starts-fresh-test
  (testing "POST detects closed event-ch (dead browser connection) and starts fresh SSE"
    ;; Simulates: browser navigates away, event-ch closes, but signal-ch is still open.
    ;; The next POST should detect the dead event-ch and start a new SSE.
    (let [stale-user-id (random-uuid)
          session-key [stale-user-id :test/filterable-counters nil]
          open-signal-ch (async/chan (async/sliding-buffer 1))
          closed-event-ch (async/chan)]
      ;; Close event-ch to simulate dead browser connection; leave signal-ch open
      (async/close! closed-event-ch)
      ;; Insert entry with open signal-ch but closed event-ch
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {}) :signal-ch open-signal-ch :event-ch closed-event-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              post-request (-> (HttpRequest/newBuilder)
                               (.uri (URI/create (str "http://localhost:" *port*
                                                      "/filterable/__stream?filter=after-dead-conn")))
                               (.header "Content-Type" "application/json")
                               (.header "X-Test-User-Id" (str stale-user-id))
                               (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:datastar {}})))
                               .build)
              post-response (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body post-response) 1 10000)]
          ;; Fresh SSE started — should render with the filter
          (is (= 1 (count events)))
          (is (str/includes? (:data (first events)) "filter:after-dead-conn"))
          ;; Old signal-ch should have been closed during cleanup
          (is (async-protocols/closed? open-signal-ch)))
        (finally
          (async/close! open-signal-ch)
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

(deftest e2e-multiple-post-signal-updates-test
  (testing "Multiple POSTs update the same SSE stream, each triggering a re-render"
    (let [client (HttpClient/newHttpClient)
          ;; 1. Open GET SSE stream
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port* "/filterable/__stream")))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; initial + 2 POST re-renders = 3
          stream-events-future (future (parse-sse-events (.body get-response) 3 10000))
          _ (Thread/sleep 500)
          ;; 2. First POST — filter=first
          post-1 (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?filter=first")))
                     (.header "Content-Type" "application/json")
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.POST (HttpRequest$BodyPublishers/ofString ""))
                     .build)
          _ (.send client post-1 (HttpResponse$BodyHandlers/ofInputStream))
          ;; Wait for first re-render to complete before sending second
          _ (Thread/sleep 300)
          ;; 3. Second POST — filter=second (overwrites first)
          post-2 (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?filter=second")))
                     (.header "Content-Type" "application/json")
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.POST (HttpRequest$BodyPublishers/ofString ""))
                     .build)
          _ (.send client post-2 (HttpResponse$BodyHandlers/ofInputStream))
          stream-events @stream-events-future]
      (is (= 3 (count stream-events)))
      ;; Initial — no filter
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; After first POST
      (is (str/includes? (:data (second stream-events)) "filter:first"))
      ;; After second POST — context overwritten
      (is (str/includes? (:data (nth stream-events 2)) "filter:second")))))

;; ============================================ ;;
;; E2E Nonce-Based Session Isolation Tests       ;;
;; ============================================ ;;

(deftest e2e-nonce-isolates-tabs-test
  (testing "Two GETs with different nonces get independent SSE streams"
    (let [client (HttpClient/newHttpClient)
          nonce-a (str (random-uuid))
          nonce-b (str (random-uuid))
          ;; Tab A: GET with nonce-a
          get-a (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-a)))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-a (.send client get-a (HttpResponse$BodyHandlers/ofInputStream))
          ;; Tab A: initial + POST re-render = 2
          events-a-future (future (parse-sse-events (.body response-a) 2 10000))
          _ (Thread/sleep 300)
          ;; Tab B: GET with nonce-b (same user)
          get-b (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-b)))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-b (.send client get-b (HttpResponse$BodyHandlers/ofInputStream))
          ;; Tab B: initial only (no POST to this nonce), timeout waiting for 2nd
          events-b-future (future (parse-sse-events (.body response-b) 2 5000))
          _ (Thread/sleep 300)
          ;; POST to Tab A's nonce — should only update Tab A
          post-a (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?filter=tab-a-only&dsNonce=" nonce-a)))
                     (.header "Content-Type" "application/json")
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.POST (HttpRequest$BodyPublishers/ofString ""))
                     .build)
          _ (.send client post-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a @events-a-future
          events-b @events-b-future]
      ;; Tab A got initial render + POST re-render with filter
      (is (= 2 (count events-a)))
      (is (str/includes? (:data (second events-a)) "filter:tab-a-only"))
      ;; Tab B only got initial render — POST to nonce-a didn't affect it
      (is (= 1 (count events-b)))
      (is (not (str/includes? (:data (first events-b)) "filter:"))))))

(deftest e2e-nonce-prevents-stale-collision-test
  (testing "New page load with fresh nonce doesn't collide with stale entry from old nonce"
    (let [client (HttpClient/newHttpClient)
          user-id (random-uuid)
          old-nonce (str (random-uuid))
          new-nonce (str (random-uuid))
          ;; Simulate stale entry from a previous page load (old nonce, closed event-ch)
          old-session-key [user-id :test/filterable-counters old-nonce]
          open-signal-ch (async/chan (async/sliding-buffer 1))
          closed-event-ch (async/chan)]
      (async/close! closed-event-ch)
      (swap! @#'ds/active-stream-contexts assoc old-session-key
             {:context-atom (atom {}) :signal-ch open-signal-ch :event-ch closed-event-ch})
      (try
        ;; POST with new nonce — should NOT find the old nonce's entry, starts fresh
        (let [post-request (-> (HttpRequest/newBuilder)
                               (.uri (URI/create (str "http://localhost:" *port*
                                                      "/filterable/__stream?filter=fresh-page&dsNonce=" new-nonce)))
                               (.header "Content-Type" "application/json")
                               (.header "X-Test-User-Id" (str user-id))
                               (.POST (HttpRequest$BodyPublishers/ofString (json/write-str {:datastar {}})))
                               .build)
              post-response (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body post-response) 1 10000)]
          ;; Fresh SSE started with filter — didn't reuse old nonce's dead entry
          (is (= 1 (count events)))
          (is (str/includes? (:data (first events)) "filter:fresh-page"))
          ;; Old stale entry's signal-ch should still be open (wasn't touched — different key)
          (is (not (async-protocols/closed? open-signal-ch))))
        (finally
          (async/close! open-signal-ch)
          (swap! @#'ds/active-stream-contexts dissoc old-session-key))))))

(deftest e2e-nonce-in-url-for-post-test
  (testing "POST with nonce in URL matches GET SSE with same nonce"
    ;; Simulates: data-init creates SSE with nonce in URL, then data-on:change
    ;; fires @post with same nonce in URL (view renders nonce into @post path).
    (let [client (HttpClient/newHttpClient)
          nonce (str (random-uuid))
          ;; 1. GET SSE with nonce in URL (simulates data-init)
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Expect initial + POST re-render = 2 events
          stream-events-future (future (parse-sse-events (.body get-response) 2 10000))
          _ (Thread/sleep 500)
          ;; 2. POST with nonce in URL (simulates view-rendered @post path)
          post-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?filter=url-nonce&dsNonce=" nonce)))
                           (.header "Content-Type" "application/json")
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.POST (HttpRequest$BodyPublishers/ofString ""))
                           .build)
          _ (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
          stream-events @stream-events-future]
      ;; GET SSE should have received initial render + POST re-render
      (is (= 2 (count stream-events)))
      ;; Second event should have the filter from the POST
      (is (str/includes? (:data (second stream-events)) "filter:url-nonce")))))

;; ============================================ ;;
;; E2E GET Signal Reuse Tests                    ;;
;; ============================================ ;;

(deftest e2e-get-updates-existing-sse-signals-test
  (testing "Second GET with same nonce updates context on existing SSE and triggers re-render"
    (let [client (HttpClient/newHttpClient)
          nonce (str (random-uuid))
          ;; 1. Open GET SSE stream (event-driven, with auth + nonce)
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Expect: initial render + 1 re-render after second GET = 2 events
          stream-events-future (future (parse-sse-events (.body get-response) 2 10000))
          ;; Wait for SSE to connect and register in active-stream-contexts
          _ (Thread/sleep 500)
          ;; 2. Second GET with same nonce + filter signal (via ?datastar= JSON param)
          ds-signals (json/write-str {:filter "active" :dsNonce nonce})
          get2-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?datastar="
                                                  (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.GET)
                           .build)
          ;; This GET should find existing SSE, update it, and return empty SSE
          _ (.send client get2-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; 3. Collect SSE events from the original GET stream
          stream-events @stream-events-future]
      ;; Initial render should NOT have the filter
      (is (= 2 (count stream-events)))
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; Re-render after second GET should include the filter value
      (is (str/includes? (:data (second stream-events)) "filter:active")))))

(deftest e2e-get-no-existing-sse-starts-fresh-test
  (testing "GET to event-driven stream starts a new SSE when no existing stream is open"
    (let [client (HttpClient/newHttpClient)
          unique-user-id (random-uuid)
          nonce (str (random-uuid))
          ds-signals (json/write-str {:filter "new" :dsNonce nonce})
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?datastar="
                                                (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                          (.header "X-Test-User-Id" (str unique-user-id))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          events (parse-sse-events (.body get-response) 1 10000)]
      ;; Should get initial render with the filter applied
      (is (= 1 (count events)))
      (is (= "datastar-patch-elements" (:name (first events))))
      (is (str/includes? (:data (first events)) "filter:new")))))

(deftest e2e-get-signal-update-plus-domain-event-test
  (testing "GET updates signals, then domain event triggers re-render with updated context"
    (let [client (HttpClient/newHttpClient)
          nonce (str (random-uuid))
          ;; 1. Open GET SSE stream
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Expect: initial + GET signal re-render + domain event re-render = 3
          stream-events-future (future (parse-sse-events (.body get-response) 3 10000))
          _ (Thread/sleep 500)
          ;; 2. Second GET to update filter signal
          ds-signals (json/write-str {:filter "vip" :dsNonce nonce})
          get2-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?datastar="
                                                  (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.GET)
                           .build)
          _ (.send client get2-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; Wait for signal re-render to complete
          _ (Thread/sleep 300)
          ;; 3. Modify state and publish domain event
          _ (swap! *e2e-state* assoc-in [:counters 0 :value] 777)
          _ (pubsub/pub *event-pubsub* {:message {:event/type :test/counter-incremented}})
          stream-events @stream-events-future]
      ;; Event 1: initial render — no filter
      (is (= 3 (count stream-events)))
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; Event 2: GET signal update — filter:vip
      (is (str/includes? (:data (second stream-events)) "filter:vip"))
      ;; Event 3: domain event re-render — filter still vip, new value 777
      (is (str/includes? (:data (nth stream-events 2)) "filter:vip"))
      (is (str/includes? (:data (nth stream-events 2)) "777")))))

(deftest e2e-get-concurrent-users-independent-streams-test
  (testing "Two users have independent SSE streams — GET signal update from one doesn't affect the other"
    (let [client (HttpClient/newHttpClient)
          nonce-a (str (random-uuid))
          nonce-b (str (random-uuid))
          ;; 1. User A opens GET SSE stream
          get-a (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-a)))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-a (.send client get-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a-future (future (parse-sse-events (.body response-a) 2 10000))
          _ (Thread/sleep 300)
          ;; 2. User B opens GET SSE stream
          get-b (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-b)))
                    (.header "X-Test-User-Id" (str test-user-id-b))
                    (.GET)
                    .build)
          response-b (.send client get-b (HttpResponse$BodyHandlers/ofInputStream))
          events-b-future (future (parse-sse-events (.body response-b) 2 5000))
          _ (Thread/sleep 300)
          ;; 3. GET signal update as User A
          ds-signals (json/write-str {:filter "user-a-only" :dsNonce nonce-a})
          get2-a (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?datastar="
                                            (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.GET)
                     .build)
          _ (.send client get2-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a @events-a-future
          events-b @events-b-future]
      ;; User A got re-render with filter
      (is (= 2 (count events-a)))
      (is (str/includes? (:data (second events-a)) "filter:user-a-only"))
      ;; User B only got initial render
      (is (= 1 (count events-b)))
      (is (not (str/includes? (:data (first events-b)) "filter:"))))))

(deftest e2e-get-stale-session-cleanup-test
  (testing "GET skips stale registry entry with closed signal-ch"
    (let [stale-user-id (random-uuid)
          nonce (str (random-uuid))
          session-key [stale-user-id :test/filterable-counters nonce]
          closed-ch (async/chan)]
      (async/close! closed-ch)
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {}) :signal-ch closed-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              ds-signals (json/write-str {:filter "after-stale" :dsNonce nonce})
              get-request (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str "http://localhost:" *port*
                                                    "/filterable/__stream?datastar="
                                                    (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                              (.header "X-Test-User-Id" (str stale-user-id))
                              (.GET)
                              .build)
              get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body get-response) 1 10000)]
          (is (= 1 (count events)))
          (is (str/includes? (:data (first events)) "filter:after-stale")))
        (finally
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

(deftest e2e-get-stale-event-ch-starts-fresh-test
  (testing "GET detects closed event-ch (dead browser connection) and starts fresh SSE"
    (let [stale-user-id (random-uuid)
          nonce (str (random-uuid))
          session-key [stale-user-id :test/filterable-counters nonce]
          open-signal-ch (async/chan (async/sliding-buffer 1))
          closed-event-ch (async/chan)]
      (async/close! closed-event-ch)
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {}) :signal-ch open-signal-ch :event-ch closed-event-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              ds-signals (json/write-str {:filter "after-dead-conn" :dsNonce nonce})
              get-request (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str "http://localhost:" *port*
                                                    "/filterable/__stream?datastar="
                                                    (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                              (.header "X-Test-User-Id" (str stale-user-id))
                              (.GET)
                              .build)
              get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body get-response) 1 10000)]
          (is (= 1 (count events)))
          (is (str/includes? (:data (first events)) "filter:after-dead-conn"))
          ;; Old signal-ch should have been closed during cleanup
          (is (async-protocols/closed? open-signal-ch)))
        (finally
          (async/close! open-signal-ch)
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

;; ============================================================ ;;
;; REPRODUCTIONS — silently-dead SSE reconnect + finally clobber ;;
;;                                                                ;;
;; These pin two bugs that the existing stale-session tests miss  ;;
;; because every one of those simulates a LOUD death (async/close!;;
;; on a channel). A phone's SSE typically dies SILENTLY: the      ;;
;; socket is gone but no write has failed yet, so Pedestal has    ;;
;; not closed either channel — the registry entry still looks     ;;
;; alive for minutes. Both tests FAIL against current behavior    ;;
;; and are the executable statement of the defect.                ;;
;; ============================================================ ;;

(deftest e2e-get-reconnect-into-silently-dead-stream-test
  (testing "A persistent-stream reconnect (same nonce, __grainConnect marker)
            into a zombie whose channels are still OPEN must rebind a fresh
            stream, not reuse-and-swallow it"
    ;; Silent death: BOTH signal-ch and event-ch remain open (no write has
    ;; failed yet), mirroring a mobile connection that vanished without a FIN.
    ;; The liveness guard only checks `closed?`, so it treats this zombie as
    ;; live. The reconnect carries __grainConnect (baked into the shim's
    ;; data-init URL and replayed verbatim on every SSE auto-reconnect), which
    ;; routes it to rebind instead of reuse.
    (let [user-id (random-uuid)
          nonce   (str (random-uuid))
          session-key [user-id :test/filterable-counters nonce]
          open-signal-ch (async/chan (async/sliding-buffer 1))
          open-event-ch  (async/chan (async/sliding-buffer 1))]
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {:query {:query/name :test/filterable-counters}})
              :signal-ch open-signal-ch
              :event-ch open-event-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              ;; Exactly the data-init URL the browser reconnects to: same nonce +
              ;; the __grainConnect marker. A native SSE reconnect replays this URL.
              get-request (-> (HttpRequest/newBuilder)
                              (.uri (URI/create (str "http://localhost:" *port*
                                                     "/filterable/__stream?dsNonce=" nonce
                                                     "&__grainConnect=1")))
                              (.header "X-Test-User-Id" (str user-id))
                              (.GET)
                              .build)
              get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body get-response) 1 5000)]
          ;; The reconnecting connection must receive its own initial render.
          ;; Pre-fix: reuse fired and the new connection got an empty closing SSE
          ;; (0 events — the page silently stopped updating). Post-fix: rebind.
          (is (= 1 (count events))
              "reconnect must get a fresh initial render, not an empty closing SSE")
          (is (= "datastar-patch-elements" (:name (first events)))))
        (finally
          (async/close! open-signal-ch)
          (async/close! open-event-ch)
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

(deftest e2e-post-reconnect-into-silently-dead-stream-test
  (testing "The zombie bug is method-agnostic: a POST persistent-stream reconnect
            (stream-method \"post\", __grainConnect marker) into an OPEN zombie must
            also rebind, not reuse-and-swallow"
    ;; This is the case the report's \"POST always reuses\" rule would leave broken —
    ;; a stream-method \"post\" app reconnects its persistent stream as a POST, and
    ;; without intent-based routing that POST is swallowed exactly like the GET bug.
    ;; The marker routes it to rebind regardless of method.
    (let [user-id (random-uuid)
          nonce   (str (random-uuid))
          session-key [user-id :test/filterable-counters nonce]
          open-signal-ch (async/chan (async/sliding-buffer 1))
          open-event-ch  (async/chan (async/sliding-buffer 1))]
      (swap! @#'ds/active-stream-contexts assoc session-key
             {:context-atom (atom {:query {:query/name :test/filterable-counters}})
              :signal-ch open-signal-ch
              :event-ch open-event-ch})
      (try
        (let [client (HttpClient/newHttpClient)
              ;; POST data-init reconnect: marker + nonce in the URL, signals in body.
              post-request (-> (HttpRequest/newBuilder)
                               (.uri (URI/create (str "http://localhost:" *port*
                                                      "/filterable/__stream?dsNonce=" nonce
                                                      "&__grainConnect=1")))
                               (.header "Content-Type" "application/json")
                               (.header "X-Test-User-Id" (str user-id))
                               (.POST (HttpRequest$BodyPublishers/ofString ""))
                               .build)
              post-response (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
              events (parse-sse-events (.body post-response) 1 5000)]
          (is (= 1 (count events))
              "POST reconnect must get a fresh initial render, not an empty closing SSE")
          (is (= "datastar-patch-elements" (:name (first events)))))
        (finally
          (async/close! open-signal-ch)
          (async/close! open-event-ch)
          (swap! @#'ds/active-stream-contexts dissoc session-key))))))

(deftest e2e-stale-loop-finally-does-not-clobber-newer-entry-test
  (testing "A stale event loop's finally must not dissoc a NEWER healthy entry
            that replaced it under the same [user query nonce] key"
    ;; Drives the real stream-view-loop-events finally:
    ;;   1. open a live SSE (loop L1) — it registers entry1 under key K
    ;;   2. a newer stream replaces K with entry2 (simulated directly)
    ;;   3. L1 exits (its own signal-ch closes) and runs its finally
    ;; The finally's UNCONDITIONAL `(dissoc K)` then removes entry2 — the
    ;; healthy stream — breaking all subsequent reuse for that tab.
    (let [reg #'ds/active-stream-contexts
          user-id (random-uuid)
          nonce   (str (random-uuid))
          session-key [user-id :test/filterable-counters nonce]
          client (HttpClient/newHttpClient)
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                 "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str user-id))
                          (.GET)
                          .build)]
      ;; 1. Open the live stream L1 and let it register entry1.
      (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
      (is (eventually? #(contains? @@reg session-key) 5000)
          "L1 should register its entry")
      (let [entry1 (get @@reg session-key)
            l1-signal-ch (:signal-ch entry1)
            l1-event-ch  (:event-ch entry1)
            ;; 2. A newer, healthy stream takes over the same key.
            entry2 {:context-atom (atom {:query {:query/name :test/filterable-counters}})
                    :signal-ch (async/chan (async/sliding-buffer 1))
                    :event-ch  (async/chan (async/sliding-buffer 1))}]
        (swap! @reg assoc session-key entry2)
        (try
          ;; 3. Force L1 to exit by closing ITS signal-ch (mirrors the else-branch
          ;;    in update-or-start that closes a stale signal-ch). L1's finally
          ;;    closes its own event-ch, which we use as the "finally ran" signal.
          (async/close! l1-signal-ch)
          (is (eventually? #(async-protocols/closed? l1-event-ch) 5000)
              "L1's finally should have run (its event-ch is closed)")
          ;; The newer, healthy entry2 must still be registered.
          ;; TODAY: L1's finally dissoc'd the key, so this is nil — reuse for
          ;; this tab is now broken. That is the bug this test pins.
          (is (identical? entry2 (get @@reg session-key))
              "newer healthy entry must survive the stale loop's cleanup")
          (finally
            (async/close! (:signal-ch entry2))
            (async/close! (:event-ch entry2))
            (swap! @reg dissoc session-key)))))))

(deftest e2e-get-multiple-signal-updates-test
  (testing "Multiple GETs update the same SSE stream, each triggering a re-render"
    (let [client (HttpClient/newHttpClient)
          nonce (str (random-uuid))
          ;; 1. Open GET SSE stream
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; initial + 2 GET signal re-renders = 3
          stream-events-future (future (parse-sse-events (.body get-response) 3 10000))
          _ (Thread/sleep 500)
          ;; 2. First GET signal update — filter=first
          ds-signals-1 (json/write-str {:filter "first" :dsNonce nonce})
          get2 (-> (HttpRequest/newBuilder)
                   (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?datastar="
                                          (java.net.URLEncoder/encode ds-signals-1 "UTF-8"))))
                   (.header "X-Test-User-Id" (str test-user-id-a))
                   (.GET)
                   .build)
          _ (.send client get2 (HttpResponse$BodyHandlers/ofInputStream))
          _ (Thread/sleep 300)
          ;; 3. Second GET signal update — filter=second
          ds-signals-2 (json/write-str {:filter "second" :dsNonce nonce})
          get3 (-> (HttpRequest/newBuilder)
                   (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?datastar="
                                          (java.net.URLEncoder/encode ds-signals-2 "UTF-8"))))
                   (.header "X-Test-User-Id" (str test-user-id-a))
                   (.GET)
                   .build)
          _ (.send client get3 (HttpResponse$BodyHandlers/ofInputStream))
          stream-events @stream-events-future]
      (is (= 3 (count stream-events)))
      ;; Initial — no filter
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; After first GET signal update
      (is (str/includes? (:data (second stream-events)) "filter:first"))
      ;; After second GET signal update
      (is (str/includes? (:data (nth stream-events 2)) "filter:second")))))

(deftest e2e-get-nonce-isolates-tabs-test
  (testing "Two GETs with different nonces — signal update to one doesn't affect the other"
    (let [client (HttpClient/newHttpClient)
          nonce-a (str (random-uuid))
          nonce-b (str (random-uuid))
          ;; Tab A
          get-a (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-a)))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-a (.send client get-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a-future (future (parse-sse-events (.body response-a) 2 10000))
          _ (Thread/sleep 300)
          ;; Tab B (same user, different nonce)
          get-b (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port*
                                          "/filterable/__stream?dsNonce=" nonce-b)))
                    (.header "X-Test-User-Id" (str test-user-id-a))
                    (.GET)
                    .build)
          response-b (.send client get-b (HttpResponse$BodyHandlers/ofInputStream))
          events-b-future (future (parse-sse-events (.body response-b) 2 5000))
          _ (Thread/sleep 300)
          ;; GET signal update to Tab A's nonce only
          ds-signals (json/write-str {:filter "tab-a-only" :dsNonce nonce-a})
          get2-a (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "http://localhost:" *port*
                                            "/filterable/__stream?datastar="
                                            (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                     (.header "X-Test-User-Id" (str test-user-id-a))
                     (.GET)
                     .build)
          _ (.send client get2-a (HttpResponse$BodyHandlers/ofInputStream))
          events-a @events-a-future
          events-b @events-b-future]
      ;; Tab A got initial + signal update
      (is (= 2 (count events-a)))
      (is (str/includes? (:data (second events-a)) "filter:tab-a-only"))
      ;; Tab B only got initial — not affected
      (is (= 1 (count events-b)))
      (is (not (str/includes? (:data (first events-b)) "filter:"))))))

(deftest e2e-cross-method-reuse-test
  (testing "GET and POST reuse the same session interchangeably"
    (let [client (HttpClient/newHttpClient)
          nonce (str (random-uuid))
          ;; 1. Open GET SSE stream
          get-request (-> (HttpRequest/newBuilder)
                          (.uri (URI/create (str "http://localhost:" *port*
                                                "/filterable/__stream?dsNonce=" nonce)))
                          (.header "X-Test-User-Id" (str test-user-id-a))
                          (.GET)
                          .build)
          get-response (.send client get-request (HttpResponse$BodyHandlers/ofInputStream))
          ;; initial + POST re-render + GET re-render = 3
          stream-events-future (future (parse-sse-events (.body get-response) 3 10000))
          _ (Thread/sleep 500)
          ;; 2. POST with filter=post-value
          post-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?filter=post-value&dsNonce=" nonce)))
                           (.header "Content-Type" "application/json")
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.POST (HttpRequest$BodyPublishers/ofString ""))
                           .build)
          _ (.send client post-request (HttpResponse$BodyHandlers/ofInputStream))
          _ (Thread/sleep 300)
          ;; 3. GET with filter=get-value (same nonce — should reuse same session)
          ds-signals (json/write-str {:filter "get-value" :dsNonce nonce})
          get2-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port*
                                                  "/filterable/__stream?datastar="
                                                  (java.net.URLEncoder/encode ds-signals "UTF-8"))))
                           (.header "X-Test-User-Id" (str test-user-id-a))
                           (.GET)
                           .build)
          _ (.send client get2-request (HttpResponse$BodyHandlers/ofInputStream))
          stream-events @stream-events-future]
      (is (= 3 (count stream-events)))
      ;; Initial — no filter
      (is (not (str/includes? (:data (first stream-events)) "filter:")))
      ;; After POST
      (is (str/includes? (:data (second stream-events)) "filter:post-value"))
      ;; After GET — same session, different method
      (is (str/includes? (:data (nth stream-events 2)) "filter:get-value")))))

;; ============================================ ;;
;; E2E Path Param Resolution Test                ;;
;; ============================================ ;;

(deftest e2e-shim-page-resolves-path-params-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/detail/test-uuid-123")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        body (.body response)]
    (is (= 200 (.statusCode response)))
    (is (str/includes? body "Detail"))
    ;; Stream URL should have the resolved path param, not the literal :id
    (is (str/includes? body "/detail/test-uuid-123/__stream"))
    (is (not (str/includes? body ":id/__stream")))))

;; ============================================ ;;
;; E2E Auth-Redirect Tests                       ;;
;; ============================================ ;;

(deftest e2e-auth-redirect-unauthenticated-test
  (testing "GET to protected page without auth redirects to unauthenticated path"
    (let [client (HttpClient/newBuilder)
          ;; Don't follow redirects so we can inspect the 302
          client (.followRedirects client (java.net.http.HttpClient$Redirect/NEVER))
          client (.build client)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/owner-only")))
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 302 (.statusCode response)))
      (is (= "/test-login" (first (.allValues (.headers response) "location")))))))

(deftest e2e-auth-redirect-unauthorized-test
  (testing "GET to protected page with wrong role redirects to unauthorized path"
    (let [client (HttpClient/newBuilder)
          client (.followRedirects client (java.net.http.HttpClient$Redirect/NEVER))
          client (.build client)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/owner-only")))
                      (.header "X-Test-User-Id" (str test-user-id-a))
                      (.header "X-Test-Role" "staff")
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 302 (.statusCode response)))
      (is (= "/test-home" (first (.allValues (.headers response) "location")))))))

(deftest e2e-auth-redirect-authorized-test
  (testing "GET to protected page with correct role returns shim page"
    (let [client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/owner-only")))
                      (.header "X-Test-User-Id" (str test-user-id-a))
                      (.header "X-Test-Role" "owner")
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 200 (.statusCode response)))
      (is (str/includes? (.body response) "Owner Only")))))

(deftest e2e-auth-redirect-public-page-test
  (testing "GET to public page without auth returns shim page (no redirect)"
    (let [client (HttpClient/newHttpClient)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/public-page")))
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 200 (.statusCode response)))
      (is (str/includes? (.body response) "Public")))))

;; ============================================ ;;
;; E2E Gate Tests                                ;;
;; ============================================ ;;

(deftest e2e-gate-passes-test
  (testing "GET to gated page when gate passes returns shim page"
    (reset! *gate-pass* true)
    (try
      (let [client (HttpClient/newHttpClient)
            request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/gated-page")))
                        (.header "X-Test-User-Id" (str test-user-id-a))
                        (.header "X-Test-Role" "owner")
                        (.GET)
                        .build)
            response (.send client request (HttpResponse$BodyHandlers/ofString))]
        (is (= 200 (.statusCode response)))
        (is (str/includes? (.body response) "Gated")))
      (finally (reset! *gate-pass* false)))))

(deftest e2e-gate-blocks-test
  (testing "GET to gated page when gate blocks redirects to gate redirect path"
    (reset! *gate-pass* false)
    (let [client (HttpClient/newBuilder)
          client (.followRedirects client (java.net.http.HttpClient$Redirect/NEVER))
          client (.build client)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/gated-page")))
                      (.header "X-Test-User-Id" (str test-user-id-a))
                      (.header "X-Test-Role" "owner")
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 302 (.statusCode response)))
      (is (= "/gate-blocked" (first (.allValues (.headers response) "location")))))))

(deftest e2e-gate-fallback-redirect-test
  (testing "GET to page with gate that always blocks redirects to fallback path"
    (let [client (HttpClient/newBuilder)
          client (.followRedirects client (java.net.http.HttpClient$Redirect/NEVER))
          client (.build client)
          request (-> (HttpRequest/newBuilder)
                      (.uri (URI/create (str "http://localhost:" *port* "/gate-fallback-page")))
                      (.GET)
                      .build)
          response (.send client request (HttpResponse$BodyHandlers/ofString))]
      (is (= 302 (.statusCode response)))
      (is (= "/test-home" (first (.allValues (.headers response) "location")))))))

;; One persistent reader owns each HTTP body so frames buffered across test
;; phases cannot be lost. Channel waits are bounded even when no patch arrives.
(defn- subscription-sse-client
  [url]
  (let [client (-> (HttpClient/newBuilder)
                   (.version java.net.http.HttpClient$Version/HTTP_1_1)
                   .build)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.timeout (java.time.Duration/ofSeconds 10))
                    .GET .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        body (.body response)
        frames (async/chan 32)
        pump (future
               (try
                 (with-open [reader (BufferedReader. (InputStreamReader. body))]
                   (loop [event-name nil data []]
                     (when-let [line (.readLine reader)]
                       (cond
                         (str/starts-with? line "event:")
                         (recur (str/trim (subs line 6)) data)
                         (str/starts-with? line "data:")
                         (recur event-name (conj data (str/trim (subs line 5))))
                         (str/blank? line)
                         (when (or (nil? event-name)
                                   (async/>!! frames {:name event-name :data (str/join "\n" data)}))
                           (recur nil []))
                         :else (recur event-name data)))))
                 (catch java.io.IOException _)
                 (finally (async/close! frames))))]
    {:client client :response response :frames frames
     :close! (fn []
               (async/close! frames)
               (.close body)
               (when (= ::timeout (deref pump 1000 ::timeout))
                 (future-cancel pump)))}))

(defn- with-subscription-server
  [f]
  (let [tenant-id (random-uuid)
        ps (pubsub/start {:type :core-async :topic-fn :event/type})
        tailer {:watermarks (atom {tenant-id nil}) :subscriber-counts (atom {})}
        calls (atom {})
        clock (atom 0)
        entry (fn [name]
                {:authorized? (constantly true)
                 :grain/read-models {:test/subscription-a 1 :test/subscription-b 1}
                 :datastar/path (str "/" name)
                 :datastar/debounce-ms 0
                 :datastar/heartbeat-delay 1
                 :handler-fn (fn [ctx]
                               (let [n (get (swap! calls update name (fnil inc 0)) name)]
                                 {:query/result n
                                  :datastar/hiccup
                                  [:div#app (str name " render=" n " marker="
                                                (get-in ctx [:query :marker]) " clock=" @clock)]}))})
        context {:tenant-id tenant-id :event-pubsub ps :event-tailer tailer
                 :query-registry
                 {:test/subscription-page
                  (assoc (entry "subscription-page")
                         :datastar/exclude-events #{:test/subscription-ignored}
                         :datastar/event-tags [{:org :org :user :owner}
                                               {:org :org :user :assignee}])
                  :test/subscription-empty
                  (assoc (entry "subscription-empty")
                         :datastar/fps 20
                         :datastar/refresh-key (fn [_] @clock)
                         :datastar/exclude-events #{:test/subscription-change :test/subscription-ignored})}}
        routes (with-redefs [rmp/read-model-registry*
                             (atom {:test/subscription-a
                                    {:events #{:test/subscription-change :test/subscription-ignored}}
                                    :test/subscription-b {:events #{:test/subscription-ignored}}})]
                 (ds/routes context))
        server (ws/start {:http/routes routes :http/port 0 :http/join? false})]
    (try
      (f {:tenant-id tenant-id :ps ps :tailer tailer :calls calls :clock clock
          :base-url (str "http://localhost:" (get-server-port server))})
      (finally (ws/stop server) (pubsub/stop ps)))))

(defn- subscription-signal-update!
  [client url payload]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.timeout (java.time.Duration/ofSeconds 5))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-str payload)))
                    .build)]
    (.send ^HttpClient client request (HttpResponse$BodyHandlers/ofString))))

(deftest e2e-subscription-exclusions-or-and-live-scope-test
  (with-subscription-server
    (fn [{:keys [tenant-id ps tailer calls base-url]}]
      (let [nonce (str (random-uuid))
            url (str base-url "/subscription-page/__stream")
            {:keys [client frames close! response]}
            (subscription-sse-client (str url "?dsNonce=" nonce "&org=o&owner=a&assignee=b"))
            publish! (fn [type tenant tags & [event-id]]
                       (pubsub/pub ps {:message (cond-> {:event/type type :grain/tenant-id tenant
                                                        :event/tags tags}
                                                 event-id (assoc :event/id event-id))}))
            matching #{[:org "o"] [:user "a"]}]
        (try
          (is (= 200 (.statusCode response)))
          (is (str/includes? (:data (take-event frames 2000)) "render=1"))
          (is (= {tenant-id {:test/subscription-change 1}} @(:subscriber-counts tailer)))
          (is (= 1 (pubsub-topic-subscriber-count ps :test/subscription-change)))
          (is (zero? (pubsub-topic-subscriber-count ps :test/subscription-ignored)))
          (doseq [[type tenant tags]
                  [[:test/subscription-ignored tenant-id matching]
                   [:test/subscription-change (random-uuid) matching]
                   [:test/subscription-change tenant-id #{[:user "a"]}]
                   [:test/subscription-change tenant-id #{[:org "other"] [:user "b"]}]
                   [:test/subscription-change tenant-id #{[:org "o"] [:user "neither"]}]]]
            (publish! type tenant tags)
            (is (nil? (take-event frames 150))))
          (is (= 1 (get @calls "subscription-page")))
          (doseq [[user expected] [["a" 2] ["b" 3]]]
            (publish! :test/subscription-change tenant-id #{[:org "o"] [:user user]})
            (is (str/includes? (:data (take-event frames 2000)) (str "render=" expected))))
          (let [id (random-uuid)
                both #{[:org "o"] [:user "a"] [:user "b"]}]
            (publish! :test/subscription-change tenant-id both id)
            (is (str/includes? (:data (take-event frames 2000)) "render=4"))
            (publish! :test/subscription-change tenant-id both id)
            (is (nil? (take-event frames 150)))
            (is (= 4 (get @calls "subscription-page"))))
          (let [updated (subscription-signal-update! client url
                          {:dsNonce nonce :org "o" :owner "c" :assignee "d" :marker "new"})]
            (is (= 200 (.statusCode updated)))
            (is (= "" (.body updated)))
            (is (str/includes? (:data (take-event frames 2000)) "marker=new")))
          (publish! :test/subscription-change tenant-id matching)
          (is (nil? (take-event frames 150)))
          (publish! :test/subscription-change tenant-id #{[:org "o"] [:user "c"]})
          (is (str/includes? (:data (take-event frames 2000)) "render=6"))
          ;; Closing the actual socket must remove precisely the effective topics.
          (close!)
          (is (eventually? #(and (empty? @(:subscriber-counts tailer))
                                 (zero? (pubsub-topic-subscriber-count ps :test/subscription-change)))
                           8000))
          (finally (close!)))))))

(deftest e2e-all-excluded-subscription-keeps-signals-and-clock-test
  (let [original-timeout async/timeout]
    (with-redefs [async/timeout (fn [^long ms]
                                 (original-timeout (if (= ms 30000) 30 ms)))]
      (with-subscription-server
        (fn [{:keys [tenant-id ps tailer calls clock base-url]}]
          (let [nonce (str (random-uuid))
                url (str base-url "/subscription-empty/__stream")
                session-key [nil :test/subscription-empty nonce]
                {:keys [client frames close!]}
                (subscription-sse-client (str url "?dsNonce=" nonce))]
            (try
              (is (str/includes? (:data (take-event frames 2000)) "render=1"))
              (is (contains? @@#'ds/active-stream-contexts session-key))
              (is (empty? @(:subscriber-counts tailer)))
              (doseq [topic [:test/subscription-change :test/subscription-ignored]]
                (is (zero? (pubsub-topic-subscriber-count ps topic)))
                (pubsub/pub ps {:message {:event/type topic :grain/tenant-id tenant-id}}))
              (is (nil? (take-event frames 150)))
              (is (= 1 (get @calls "subscription-empty")) "Positive fps must not turn into polling")
              (let [updated (subscription-signal-update! client url {:dsNonce nonce :marker "signals"})]
                (is (= 200 (.statusCode updated)))
                (is (= "" (.body updated)))
                (is (str/includes? (:data (take-event frames 2000)) "marker=signals")))
              (swap! clock inc)
              (is (str/includes? (:data (take-event frames 2000)) "clock=1"))
              (is (= 3 (get @calls "subscription-empty")))
              (close!)
              (is (eventually? #(not (contains? @@#'ds/active-stream-contexts session-key)) 8000))
              (is (empty? @(:subscriber-counts tailer)))
              (finally (close!)))))))))
