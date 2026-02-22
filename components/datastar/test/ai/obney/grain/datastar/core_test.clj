(ns ai.obney.grain.datastar.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async]
            [ai.obney.grain.datastar.core :as ds]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.command-processor.interface :as cp :refer [defcommand]]
            [ai.obney.grain.command-processor.interface.schemas]
            [ai.obney.grain.query-schema.interface]
            [ai.obney.grain.read-model-processor.interface :as rmp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.webserver.interface :as ws]
            [io.pedestal.http :as http])
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
   :test/increment [:map [:counter-id :uuid]]
   :test/no-signals [:map]})

;; -------------------- ;;
;; Test Query/Command   ;;
;; -------------------- ;;

(defquery :test counters
  {:authorized? (constantly true)}
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
   :version 1}
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
  [context]
  (let [state @(:test-state context)
        counters (:counters state)]
    {:query/result counters
     :datastar/hiccup
     [:div#app
      (for [c counters]
        [:div {:id (str (:id c))}
         (:name c) ": " (:value c)])]}))

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
      (is (str/includes? (get-in result [:response :body]) "<script"))))

  (testing "with stream-path"
    (let [interceptor (ds/shim-page {:stream-path "/stream"})
          result ((:enter interceptor) {})]
      (is (str/includes? (get-in result [:response :body]) "data-init"))
      ;; hiccup2 escapes ' to &apos; in attributes
      (is (str/includes? (get-in result [:response :body]) "@get(&apos;/stream&apos;)")))))

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
    (is (some? (:command/timestamp decoded)))))

(deftest decode-json-query-test
  (let [raw {:query/name :test/counters}
        decoded (#'ds/decode-json-query raw)]
    (is (= :test/counters (:query/name decoded)))
    (is (uuid? (:query/id decoded)))
    (is (some? (:query/timestamp decoded)))))

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

;; =========================== ;;
;; E2E Test Fixture             ;;
;; =========================== ;;

(def ^:dynamic *port* nil)
(def ^:dynamic *e2e-state* nil)
(def ^:dynamic *event-pubsub* nil)

(defn- get-server-port [webserver]
  (let [service-map (get webserver :ai.obney.grain.webserver.core/server)
        jetty-server (::http/server service-map)]
    (.getLocalPort (first (.getConnectors jetty-server)))))

(defn e2e-fixture [f]
  (let [state (atom {:counters [{:id #uuid "00000000-0000-0000-0000-000000000001"
                                 :name "A" :value 0}]})
        event-pubsub (pubsub/start {:type :core-async
                                    :topic-fn :event/type})
        context {:test-state state
                 :command-processor/skip-event-storage true
                 :command-registry @cp/command-registry*
                 :query-registry @qp/query-registry*
                 :event-pubsub event-pubsub}
        manual-routes #{["/counters" :get [(ds/shim-page {:stream-path "/counters/stream"})]
                         :route-name ::counters-page]
                        ["/counters/stream" :get [(ds/stream-view context :test/counters {:fps 10})]
                         :route-name ::counters-stream]
                        ["/ds/command" :post [(ds/action-handler context {})]
                         :route-name ::ds-command]}
        auto-routes (ds/routes context)
        routes (into manual-routes auto-routes)
        server (ws/start {:http/routes routes :http/port 0 :http/join? false})
        port (get-server-port server)]
    (binding [*port* port *e2e-state* state *event-pubsub* event-pubsub]
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
    (is (str/includes? body "@get(&apos;/counters/stream&apos;)"))))

(deftest e2e-sse-stream-test
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str "http://localhost:" *port* "/counters/stream")))
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
                           (.uri (URI/create (str "http://localhost:" *port* "/counters/stream")))
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
                    (.uri (URI/create (str "http://localhost:" *port* "/counters/stream?filter=active")))
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
  (testing "entry with :datastar/path produces two routes"
    (let [entry {:handler-fn identity
                 :authorized? (constantly true)
                 :datastar/path "/my-page"
                 :datastar/title "My Page"
                 :datastar/fps 5}
          context {}
          [shim-route stream-route] (#'ds/query->route-pair context :test/my-page entry)]
      (is (some? shim-route))
      (is (some? stream-route))
      ;; Shim route
      (is (= "/my-page" (first shim-route)))
      (is (= :get (second shim-route)))
      (is (= :ai.obney.grain.datastar.core/test-my-page-page
             (last shim-route)))
      ;; Stream route
      (is (= "/my-page/stream" (first stream-route)))
      (is (= :get (second stream-route)))
      (is (= :ai.obney.grain.datastar.core/test-my-page-stream
             (last stream-route)))))

  (testing "entry without :datastar/path returns nil"
    (is (nil? (#'ds/query->route-pair {} :test/counters
                                      {:handler-fn identity
                                       :authorized? (constantly true)})))))

(deftest routes-generation-test
  (let [context {:query-registry {:test/with-path {:handler-fn identity
                                                    :authorized? (constantly true)
                                                    :datastar/path "/with-path"
                                                    :datastar/title "With Path"}
                                   :test/no-path {:handler-fn identity
                                                   :authorized? (constantly true)}}}
        generated (ds/routes context)]
    (testing "correct number of routes (2 per annotated query)"
      (is (= 2 (count generated))))

    (testing "queries without :datastar/path are excluded"
      (let [paths (set (map first generated))]
        (is (contains? paths "/with-path"))
        (is (contains? paths "/with-path/stream"))
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
        stream-route (first (filter #(= "/defaults/stream" (first %)) generated))
        shim-interceptor (last (nth shim-route 2))
        shim-result ((:enter shim-interceptor) {})]
    (testing "default title is 'Grain App'"
      (is (str/includes? (get-in shim-result [:response :body]) "Grain App")))

    (testing "default fps is 30 (stream interceptor is created)"
      (is (some? stream-route)))))

(deftest routes-with-overrides-test
  (let [my-interceptor {:name ::test-override-interceptor :enter identity}
        context {:query-registry {:test/overridable {:handler-fn identity
                                                     :authorized? (constantly true)
                                                     :datastar/path "/overridable"
                                                     :datastar/fps 5}}}
        generated (ds/routes context {:test/overridable {:datastar/interceptors [my-interceptor]}})
        shim-route (first (filter #(= "/overridable" (first %)) generated))
        interceptors (nth shim-route 2)]
    (testing "override interceptors are prepended"
      (is (= ::test-override-interceptor (:name (first interceptors)))))))

(deftest routes-with-interceptors-test
  (let [my-interceptor {:name ::my-guard :enter identity}
        context {:query-registry {:test/guarded {:handler-fn identity
                                                  :authorized? (constantly true)
                                                  :datastar/path "/guarded"
                                                  :datastar/interceptors [my-interceptor]}}}
        generated (ds/routes context)
        shim-route (first (filter #(= "/guarded" (first %)) generated))
        stream-route (first (filter #(= "/guarded/stream" (first %)) generated))
        shim-interceptors (nth shim-route 2)
        stream-interceptors (nth stream-route 2)]
    (testing "shim route has custom interceptor before shim-page"
      (is (= ::my-guard (:name (first shim-interceptors))))
      (is (= :ai.obney.grain.datastar.core/shim-page (:name (second shim-interceptors)))))

    (testing "stream route has custom interceptor before stream-view"
      (is (= ::my-guard (:name (first stream-interceptors))))
      (is (= :ai.obney.grain.datastar.core/stream-guarded
             (:name (second stream-interceptors)))))))

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
        (is (str/includes? body "@get(&apos;/auto-counters/stream&apos;)"))))

    (testing "SSE stream works at auto-generated path"
      (let [request (-> (HttpRequest/newBuilder)
                        (.uri (URI/create (str "http://localhost:" *port* "/auto-counters/stream")))
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
                    (.uri (URI/create (str "http://localhost:" *port* "/event-counters/stream")))
                    (.GET)
                    .build)
        response (.send client request (HttpResponse$BodyHandlers/ofInputStream))
        events (parse-sse-events (.body response) 1 5000)]
    (is (= 1 (count events)))
    (is (= "datastar-patch-elements" (:name (first events))))
    (is (str/includes? (:data (first events)) "elements"))))

(deftest e2e-event-driven-rerender-on-event-test
  (let [client (HttpClient/newHttpClient)
        ;; Connect to event-driven stream
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port* "/event-counters/stream")))
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

(deftest e2e-event-driven-debounce-test
  (let [client (HttpClient/newHttpClient)
        ;; Connect to event-driven stream
        stream-request (-> (HttpRequest/newBuilder)
                           (.uri (URI/create (str "http://localhost:" *port* "/event-counters/stream")))
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
    (is (str/includes? body "@get(&apos;/event-counters/stream&apos;)"))))
