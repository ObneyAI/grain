(ns ai.obney.grain.tui-adapter.transport.http-e2e-test
  "End-to-end test for the v0.8 §4.3 wire protocol.

   Boots a real Pedestal/Jetty server with the tui-adapter's HTTP
   routes, drives it from the test thread using JDK HttpClient + a
   line-streamed SSE consumer, asserts that frames arrive over SSE
   and that commands round-trip correctly."
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.transport.http :as http]
            [ai.obney.grain.webserver.core :as webserver]
            [io.pedestal.http :as pedestal]
            [io.pedestal.http.route :as route])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
                          HttpRequest$BodyPublishers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.util.concurrent ExecutorService Executors TimeUnit)))

;; ──────────────────────────────────────────────────────────────────────
;; Test harness — boot a Pedestal server with the TUI routes
;; ──────────────────────────────────────────────────────────────────────

(def ^:dynamic *server* nil)
(def ^:dynamic *registry* nil)
(def ^:dynamic *base-url* nil)
(def ^:dynamic *executor* nil)
(def ^:dynamic *command-log* nil)

(def ^:private hello-screen
  {:query-id :hello/screen :inputs {}
   :tui/buffer :alt :tui/projection :snapshot})

(def ^:private hello-query-registry
  {:hello/screen {:tui/buffer :alt :tui/projection :snapshot}
   :counter/screen {:tui/buffer :alt :tui/projection :snapshot}})

(defn- find-free-port []
  (let [ss (java.net.ServerSocket. 0)]
    (try (.getLocalPort ss)
         (finally (.close ss)))))

(defn- start-server-on-port [port]
  (let [registry    {:sessions-atom (atom {})}
        command-log (atom [])
        counter     (atom 0)
        pq-fn       (fn [ctx]
                      (case (-> ctx :query :query/name)
                        :hello/screen   {:tui/hiccup [:text {:text "hello"}]}
                        :counter/screen {:tui/hiccup [:text {:text (str "count=" @counter)}]}
                        {:tui/hiccup [:text {:text "?"}]}))
        pc-fn       (fn [ctx]
                      (swap! command-log conj (:command ctx))
                      (case (-> ctx :command :command/name)
                        :counter/inc (do (swap! counter inc)
                                         {:command/result {:counter @counter}})
                        {:command/result :ok}))
        opts        {:registry           registry
                     :default-screen     hello-screen
                     :process-query-fn   pq-fn
                     :process-command-fn pc-fn
                     :base-context       {}
                     :event-pubsub       nil
                     :query-registry-fn  (fn [] hello-query-registry)
                     :tenant-resolver    (fn [_] :tenant-a)
                     :user-resolver      (fn [_] :user-a)
                     :debounce-ms        0}
        server      (webserver/start {:http/port   port
                                      :http/host   "127.0.0.1"
                                      :http/routes (http/routes opts)
                                      :http/join?  false})]
    {:server   server
     :registry registry
     :counter  counter
     :command-log command-log
     :base-url (str "http://127.0.0.1:" port)}))

(defn- stop-server [{:keys [server]}]
  (when server (webserver/stop server)))

(defn with-server [t]
  (let [port (find-free-port)
        h    (start-server-on-port port)]
    (binding [*server*   (:server h)
              *registry* (:registry h)
              *base-url* (:base-url h)
              *command-log* (:command-log h)
              *executor* (Executors/newSingleThreadExecutor)]
      (try
        (t)
        (finally
          (.shutdownNow ^ExecutorService *executor*)
          (stop-server h))))))

(use-fixtures :each with-server)

;; ──────────────────────────────────────────────────────────────────────
;; HTTP client helpers
;; ──────────────────────────────────────────────────────────────────────

(defn- ^HttpClient http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 5))
      (.build)))

(defn- post-edn [path body]
  (let [bytes (.getBytes ^String (pr-str body) StandardCharsets/UTF_8)
        req   (-> (HttpRequest/newBuilder (URI. (str *base-url* path)))
                  (.header "Content-Type" "application/edn")
                  (.POST (HttpRequest$BodyPublishers/ofByteArray bytes))
                  (.build))
        resp  (.send (http-client) req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (try (edn/read-string (.body resp)) (catch Exception _ nil))}))

(defn- consume-sse
  "Open an SSE stream to `path` and push parsed events onto `event-ch`
   via the tui-client's accumulator. Returns a `stop!` fn.

   We re-implement here rather than depend on tui-client because the
   tui-adapter is meant to be self-testable."
  [path event-ch]
  (let [client  (http-client)
        req     (-> (HttpRequest/newBuilder (URI. (str *base-url* path)))
                    (.header "Accept" "text/event-stream")
                    (.GET) (.build))
        running (volatile! true)
        thread  (Thread.
                  ^Runnable
                  (fn []
                    (try
                      (let [resp (.send client req (HttpResponse$BodyHandlers/ofLines))
                            iter (.iterator (.body resp))]
                        (loop [acc {}]
                          (when (and @running (.hasNext iter))
                            (let [line (.next iter)]
                              (cond
                                (= "" line)
                                (let [{n :name d :data} acc]
                                  (when (or n d)
                                    (async/>!! event-ch
                                               {:name (or n "message")
                                                :data (clojure.string/join "\n" (or d []))}))
                                  (recur {}))

                                (clojure.string/starts-with? line "event:")
                                (recur (assoc acc :name (clojure.string/trim (subs line (count "event:")))))

                                (clojure.string/starts-with? line "data:")
                                (recur (update acc :data (fnil conj [])
                                               (let [p (subs line (count "data:"))]
                                                 (cond-> p (clojure.string/starts-with? p " ") (subs 1)))))

                                :else
                                (recur acc))))))
                      (catch InterruptedException _ nil)
                      (catch Throwable _ nil))))]
    (.setDaemon thread true)
    (.start thread)
    (fn [] (vreset! running false) (.interrupt thread))))

(defn- take-event [event-ch timeout-ms]
  (let [[v _] (async/alts!! [event-ch (async/timeout timeout-ms)])]
    v))

;; ──────────────────────────────────────────────────────────────────────
;; Tests
;; ──────────────────────────────────────────────────────────────────────

(deftest post-session-returns-session-id-and-default-screen
  (let [{:keys [status body]} (post-edn "/tui/session" {})]
    (is (= 200 status))
    (is (uuid? (:session body)))
    (is (= :hello/screen (:default-screen body)))))

(deftest sse-stream-emits-initial-tui-frame
  (let [{:keys [body]} (post-edn "/tui/session" {})
        sid (:session body)
        event-ch (async/chan 8)
        stop!    (consume-sse (str "/tui/screen/hello/screen?session=" sid) event-ch)]
    (try
      (let [evt (take-event event-ch 5000)]
        (is (some? evt) "expected an SSE event within 5s")
        (is (= "tui-frame" (:name evt)))
        (let [frm (edn/read-string (:data evt))]
          (is (= :hello/screen (-> frm :screen :query-id)))
          (is (= [:text {:text "hello"}] (:hiccup frm)))))
      (finally (stop!)))))

(deftest post-command-roundtrips-and-server-tracks-it
  (let [{:keys [body]} (post-edn "/tui/session" {})
        sid (:session body)
        {:keys [status body]} (post-edn "/tui/command/counter/inc"
                                         {:inputs {} :session sid})]
    (is (= 200 status))
    (is (true? (:ok body)))
    (is (= 1 (count @*command-log*)))
    (is (= :counter/inc (-> @*command-log* first :command/name)))))

(deftest unknown-session-yields-404-on-command
  (let [{:keys [status]} (post-edn "/tui/command/counter/inc"
                                    {:inputs {} :session (random-uuid)})]
    (is (= 404 status))))

(deftest resume-returns-same-session
  (let [first-id (-> (post-edn "/tui/session" {}) :body :session)
        second   (-> (post-edn "/tui/session" {:resume first-id}) :body :session)]
    (is (= first-id second))))
