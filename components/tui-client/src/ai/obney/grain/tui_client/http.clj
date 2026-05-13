(ns ai.obney.grain.tui-client.http
  "JDK `java.net.http.HttpClient` POST helper for the thin TUI client.

   Two endpoints in the v0.8 §4.3 protocol that the client posts to:
     POST /tui/session              — open or resume a session
     POST /tui/command/<ns>/<name>  — dispatch a Grain command

   Both expect EDN bodies and EDN responses (the wire format we chose
   for the v0.8 plan)."
  (:require [clojure.edn :as edn])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers
                          HttpRequest$BodyPublishers)
           (java.time Duration)
           (java.nio.charset StandardCharsets)))

(defn ^HttpClient default-http-client []
  (-> (HttpClient/newBuilder)
      (.connectTimeout (Duration/ofSeconds 10))
      (.build)))

(defn post-edn
  "POST `body` (a Clojure data structure, EDN-encoded by pr-str) to
   `url`. Returns `{:status int :body <parsed-edn-or-nil>}`. Parses the
   response body as EDN; non-EDN responses arrive as `:body :raw-string`
   keyed under `:raw`."
  [{:keys [http-client url body]}]
  (let [client (or http-client (default-http-client))
        bytes  (.getBytes ^String (pr-str body) StandardCharsets/UTF_8)
        req    (-> (HttpRequest/newBuilder (URI. url))
                   (.header "Content-Type" "application/edn")
                   (.header "Accept" "application/edn")
                   (.POST (HttpRequest$BodyPublishers/ofByteArray bytes))
                   (.build))
        resp   (.send client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)
        raw    (.body resp)
        parsed (try (edn/read-string raw) (catch Exception _ nil))]
    {:status status
     :body   parsed
     :raw    raw}))
