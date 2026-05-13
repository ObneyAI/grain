(ns ai.obney.grain.tui-client.sse
  "Minimal SSE consumer over JDK `java.net.http.HttpClient`.

   SSE wire format (https://html.spec.whatwg.org/multipage/server-sent-events.html):

     event: <name>
     data: <payload-line-1>
     data: <payload-line-2>

   followed by a blank line to dispatch. Comments (`: ...`), `id`, and
   `retry` fields are tolerated and ignored. Lines that don't match any
   known field are skipped.

   The consumer streams response lines via `HttpResponse$BodyHandlers/ofLines`
   and accumulates each event in-thread; on dispatch it pushes
   `{:name <string> :data <string>}` onto the supplied channel and
   resets the accumulator.

   The thin client uses this to receive `tui-frame`, `tui-toast`, and
   `tui-session` events from the v0.8 §4.3 server."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util.stream Stream)))

(defn ^HttpClient default-http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn- accumulate [acc line]
  (cond
    ;; Dispatch: blank line.
    (= "" line)
    (let [{:keys [name data]} acc]
      (if (or name data)
        (let [evt {:name (or name "message")
                   :data (str/join "\n" (or data []))}]
          (assoc acc :emit evt :name nil :data nil))
        (assoc acc :emit nil)))

    ;; Comment / keepalive — ignore.
    (or (str/starts-with? line ":") (str/starts-with? line ": "))
    (assoc acc :emit nil)

    ;; event: <name>
    (str/starts-with? line "event:")
    (assoc acc
           :name  (str/trim (subs line (count "event:")))
           :emit  nil)

    ;; data: <payload>
    (str/starts-with? line "data:")
    (let [payload (subs line (count "data:"))
          ;; Strip exactly one leading space per spec
          payload (cond-> payload (str/starts-with? payload " ") (subs 1))]
      (-> acc
          (update :data (fnil conj []) payload)
          (assoc :emit nil)))

    ;; id:, retry:, unknown → ignore
    :else (assoc acc :emit nil)))

(defn- open-stream
  "Issue the GET and return a Stream<String> of response body lines, or
   throw a runtime exception with the HTTP status when the response is
   not 200."
  ^Stream [^HttpClient client ^URI uri]
  (let [req (-> (HttpRequest/newBuilder uri)
                (.header "Accept" "text/event-stream")
                (.header "Cache-Control" "no-cache")
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofLines))]
    (when (not= 200 (.statusCode resp))
      (throw (ex-info "SSE GET failed"
                      {:status (.statusCode resp) :uri (str uri)})))
    (.body resp)))

(defn start!
  "Open an SSE stream to `url` and pump parsed events onto `event-ch`.

   Returns `{:thread t :stop! (fn []), :running? <volatile>}`. Call
   `(stop!)` to interrupt the consumer thread; the JDK HttpClient
   stream gets closed via the InterruptedException path. The thread is
   a daemon so it doesn't prevent JVM shutdown if `stop!` isn't called.

   `:on-error` is called with the exception when the stream dies
   abnormally. The caller is responsible for any reconnect logic.

   `:http-client` is optional — pass a shared HttpClient to avoid
   re-allocating per-stream."
  [{:keys [url event-ch http-client on-error]}]
  (let [running? (volatile! true)
        client   (or http-client (default-http-client))
        thread
        (Thread.
          ^Runnable
          (fn []
            (try
              (let [^Stream lines (open-stream client (URI. url))
                    iter          (.iterator lines)]
                (loop [acc {}]
                  (when (and @running? (.hasNext iter))
                    (let [line (.next iter)
                          acc' (accumulate acc line)]
                      (when-let [evt (:emit acc')]
                        (async/>!! event-ch evt))
                      (recur (dissoc acc' :emit))))))
              (catch InterruptedException _
                nil)
              (catch Throwable t
                (u/log ::sse-stream-error :url url :error t)
                (when on-error (on-error t)))
              (finally
                (vreset! running? false))))
          "tui-client-sse")
        _ (.setDaemon thread true)
        _ (.start thread)]
    {:thread   thread
     :running? running?
     :stop!    (fn []
                 (vreset! running? false)
                 (.interrupt thread))}))

;; ─────────────────────────────────────────────────────────────────────
;; Test seam — drive accumulate over a fixture line-seq
;; ─────────────────────────────────────────────────────────────────────

(defn parse-lines
  "For tests: feed a seq of lines through the accumulator and return the
   sequence of `{:name :data}` events emitted (in dispatch order)."
  [lines]
  (loop [acc {} remaining lines events []]
    (if (empty? remaining)
      events
      (let [acc' (accumulate acc (first remaining))
            evt  (:emit acc')]
        (recur (dissoc acc' :emit)
               (rest remaining)
               (cond-> events evt (conj evt)))))))
