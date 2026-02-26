(ns ai.obney.grain.datastar.core
  (:require [hiccup2.core :as h]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.string :as string]
            [io.pedestal.http.sse :as sse]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.command-processor.interface :as cp]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.read-model-processor.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [cognitect.anomalies :as anom]
            [malli.core :as mc]
            [malli.transform :as mt]
            [com.brunobonacci.mulog :as u]))

;; --------------- ;;
;; HTML Rendering  ;;
;; --------------- ;;

(defn render-html [hiccup]
  (str (h/html hiccup)))

;; ---------------------- ;;
;; SSE Event Formatters   ;;
;; ---------------------- ;;

(defn patch-elements [html opts]
  (let [{:keys [selector mode]} opts
        html-lines (string/split-lines html)
        element-lines (mapv #(str "elements " %) html-lines)
        data-lines (cond-> []
                     selector (conj (str "selector " selector))
                     (and mode (not= mode :outer)) (conj (str "mode " (name mode)))
                     true (into element-lines))]
    {:name "datastar-patch-elements"
     :data (string/join "\n" data-lines)}))

(defn patch-signals [signals opts]
  (let [{:keys [only-if-missing]} opts
        json-str (json/write-str signals)
        data-lines (cond-> []
                     only-if-missing (conj "onlyIfMissing true")
                     true (conj (str "signals " json-str)))]
    {:name "datastar-patch-signals"
     :data (string/join "\n" data-lines)}))

;; -------------------- ;;
;; Malli JSON Coercion  ;;
;; -------------------- ;;

(defn- decode-json-query
  "Coerces query params from string values into proper EDN types
   using the query's registered Malli schema + json-transformer."
  [raw-query]
  (let [query-name (:query/name raw-query)
        query-schema (try (when query-name (mc/schema query-name))
                          (catch Exception _ nil))
        decoded (if query-schema
                  (mc/decode query-schema raw-query (mt/json-transformer))
                  raw-query)]
    (assoc decoded
           :query/name query-name
           :query/id (random-uuid)
           :query/timestamp (time/now))))

(defn- parse-signals [request]
  (when-let [body (:body request)]
    (let [body-str (if (string? body) body (slurp body))]
      (when (seq body-str)
        (json/read-str body-str :key-fn keyword)))))

(defn- decode-json-command
  "Coerces a raw JSON-parsed command map into proper EDN types using
   the command's registered Malli schema + json-transformer."
  [raw-command]
  (let [command-name (let [n (:command/name raw-command)]
                       (cond
                         (keyword? n) n
                         (string? n)  (keyword (cond-> n (string/starts-with? n ":") (subs 1)))
                         :else n))
        raw-command (assoc raw-command :command/name command-name)
        command-schema (try (when command-name (mc/schema command-name))
                            (catch Exception _ nil))
        decoded (if command-schema
                  (mc/decode command-schema raw-command
                            (mt/transformer (mt/json-transformer)
                                            (mt/strip-extra-keys-transformer)))
                  raw-command)]
    (assoc decoded
           :command/name command-name
           :command/id (random-uuid)
           :command/timestamp (time/now))))

;; --------------- ;;
;; Query Polling   ;;
;; --------------- ;;

(defn poll-and-render
  "Single poll cycle. Returns {:event sse-event :result query-data} if changed, nil if unchanged."
  [query-context prev-result]
  (let [query-registry (or (:query-registry query-context) @qp/query-registry*)
        query-name (get-in query-context [:query :query/name])
        authorized? (get-in query-registry [query-name :authorized?])]
    (if (and authorized? (not (true? (authorized? query-context))))
      {:event (patch-signals {:error "Unauthorized"} {})
       :result prev-result
       :stop? true}
      (let [result (qp/process-query query-context)
            query-data (when-not (anomaly? result) (:query/result result))]
        (when (not= query-data prev-result)
          (when-let [hiccup (:datastar/hiccup result)]
            {:event (patch-elements (render-html hiccup) {})
             :result query-data}))))))

;; ------------------------ ;;
;; Interceptor Factories    ;;
;; ------------------------ ;;

(defn- stream-view-loop
  "Polling loop for stream-view. Runs on SSE thread.
   When interval-ms is nil (fps 0), renders once and closes."
  [event-ch sse-ctx context query-name interval-ms additional-context]
  (let [request (:request sse-ctx)
        raw-query (merge {:query/name query-name}
                         (:query-params request)
                         (:path-params request))
        decoded-query (decode-json-query raw-query)
        query-context (merge context additional-context {:query decoded-query})]
    (try
      (if-not interval-ms
        ;; One-shot mode: render once and close
        (let [poll-result (poll-and-render query-context nil)]
          (when poll-result
            (async/>!! event-ch (:event poll-result))))
        ;; Polling mode: loop until channel closes or client disconnects
        (loop [prev-result nil]
          (let [start-time (System/currentTimeMillis)
                poll-result (poll-and-render query-context prev-result)]
            (cond
              (:stop? poll-result)
              (async/>!! event-ch (:event poll-result))

              poll-result
              (let [sent? (async/alt!!
                            [[event-ch (:event poll-result)]] true
                            :default false)]
                (when sent?
                  (let [elapsed (- (System/currentTimeMillis) start-time)
                        sleep-ms (max 0 (- interval-ms elapsed))]
                    (when (pos? sleep-ms) (Thread/sleep sleep-ms))
                    (recur (:result poll-result)))))

              :else
              (when-not (async-protocols/closed? event-ch)
                (let [elapsed (- (System/currentTimeMillis) start-time)
                      sleep-ms (max 0 (- interval-ms elapsed))]
                  (when (pos? sleep-ms) (Thread/sleep sleep-ms))
                  (recur prev-result)))))))
      (catch Exception e (u/log ::stream-view-error :error e))
      (finally (async/close! event-ch)))))

(defn- stream-view-enter
  "Enter fn for stream-view interceptor (polling mode)."
  [context query-name interval-ms heartbeat-delay pedestal-context]
  (let [additional-context (:grain/additional-context pedestal-context)]
    (sse/start-stream
      (fn [event-ch sse-ctx]
        (stream-view-loop event-ch sse-ctx context query-name interval-ms additional-context))
      pedestal-context
      heartbeat-delay)))

;; ----------------------------- ;;
;; Event-Driven Streaming        ;;
;; ----------------------------- ;;

(defn- resolve-events
  "Resolves :grain/read-models map to a union of their :events sets.
   read-models is a map of {rm-name version, ...}.
   The version is declarative — multiple versions can co-exist."
  [read-models]
  (let [registry @rmp/read-model-registry*]
    (into #{}
          (mapcat (fn [[rm-name _version]]
                    (:events (get registry rm-name))))
          read-models)))

(defn- resolve-event-tags
  "Resolves a {:tag-key :query-key-or-path} map against query-context into
   a predicate (fn [event] -> boolean) suitable for a channel transducer.
   Bare keyword values resolve from [:query k]; vectors are full get-in paths."
  [event-tags query-context]
  (let [resolved (into #{}
                       (map (fn [[tag-key path]]
                              [tag-key (if (vector? path)
                                         (get-in query-context path)
                                         (get-in query-context [:query path]))]))
                       event-tags)]
    (fn [event]
      (set/subset? resolved (:event/tags event)))))

(defn- subscribe-to-events
  "Creates a sliding-buffer channel and subscribes it to each event type via pubsub.
   When event-filter-fn is provided, applies it as a transducer on the channel.
   Returns the subscription channel."
  [event-pubsub event-types event-filter-fn]
  (let [buf (async/sliding-buffer 64)
        sub-chan (if event-filter-fn
                  (async/chan buf (filter event-filter-fn))
                  (async/chan buf))]
    (doseq [event-type event-types]
      (pubsub/sub event-pubsub {:topic event-type :sub-chan sub-chan}))
    sub-chan))

(defn- drain-channel
  "Non-blocking drain of all buffered messages on ch. Returns count drained."
  [ch]
  (loop [n 0]
    (if-let [_ (async/poll! ch)]
      (recur (inc n))
      n)))

(defn- stream-view-loop-events
  "Event-driven loop for stream-view. Subscribes to event types, re-renders on events.
   Sub-chan is created BEFORE initial render to avoid missing events."
  [event-ch sse-ctx context query-name event-pubsub event-types debounce-ms additional-context event-tags]
  (let [request (:request sse-ctx)
        raw-query (merge {:query/name query-name}
                         (:query-params request)
                         (:path-params request))
        decoded-query (decode-json-query raw-query)
        query-context (merge context additional-context {:query decoded-query})
        event-filter-fn (when event-tags
                          (resolve-event-tags event-tags query-context))
        sub-chan (subscribe-to-events event-pubsub event-types event-filter-fn)]
    (try
      ;; Initial render
      (let [initial (poll-and-render query-context nil)]
        (when initial
          (async/>!! event-ch (:event initial)))
        (when (:stop? initial)
          (throw (ex-info "stop" {:stop true})))
        ;; Event loop
        (loop [prev-result (:result initial)]
          (let [[val _port] (async/alts!! [sub-chan (async/timeout 30000)])]
            (cond
              ;; sub-chan closed (pubsub shutting down)
              (and (nil? val) (async-protocols/closed? sub-chan))
              nil

              ;; Timeout — liveness check
              (nil? val)
              (if (async-protocols/closed? event-ch)
                nil
                (recur prev-result))

              ;; Event received — debounce, drain, re-render
              :else
              (do
                (when (pos? debounce-ms) (Thread/sleep (long debounce-ms)))
                (drain-channel sub-chan)
                (let [poll-result (poll-and-render query-context prev-result)]
                  (cond
                    (:stop? poll-result)
                    (async/>!! event-ch (:event poll-result))

                    poll-result
                    (let [sent? (async/alt!!
                                  [[event-ch (:event poll-result)]] true
                                  :default false)]
                      (if sent?
                        (recur (:result poll-result))
                        nil))

                    :else
                    (recur prev-result))))))))
      (catch Exception e
        (when-not (= "stop" (.getMessage e))
          (u/log ::stream-view-events-error :error e)))
      (finally
        (async/close! sub-chan)
        (async/close! event-ch)))))

(defn- stream-view-enter-events
  "Enter fn for stream-view interceptor (event-driven mode)."
  [context query-name event-pubsub event-types debounce-ms heartbeat-delay event-tags pedestal-context]
  (let [additional-context (:grain/additional-context pedestal-context)]
    (sse/start-stream
      (fn [event-ch sse-ctx]
        (stream-view-loop-events event-ch sse-ctx context query-name
                                 event-pubsub event-types debounce-ms additional-context event-tags))
      pedestal-context
      heartbeat-delay)))

(defn stream-view
  "Interceptor factory. Streams :datastar/hiccup via SSE.

   Modes:
   - Event-driven: when :event-types is non-empty AND :event-pubsub is in context,
     re-renders only when relevant events fire.
   - Polling: when :fps is positive, polls at the given FPS.
   - One-shot: when :fps is 0 or nil, renders once and closes."
  [context query-name opts]
  (let [{:keys [fps heartbeat-delay event-types debounce-ms event-tags]
         :or {fps 30 heartbeat-delay 10 debounce-ms 50}} opts
        event-pubsub (:event-pubsub context)]
    (if (and (seq event-types) event-pubsub)
      ;; Event-driven mode
      {:name (keyword "ai.obney.grain.datastar.core" (str "stream-" (name query-name)))
       :enter #(stream-view-enter-events context query-name event-pubsub event-types
                                         debounce-ms heartbeat-delay event-tags %)}
      ;; Polling / one-shot mode (unchanged)
      (let [interval-ms (when (and fps (pos? fps))
                          (long (/ 1000.0 fps)))]
        {:name (keyword "ai.obney.grain.datastar.core" (str "stream-" (name query-name)))
         :enter #(stream-view-enter context query-name interval-ms heartbeat-delay %)}))))

;; --------------- ;;
;; Shim Page       ;;
;; --------------- ;;

(def ^:private default-datastar-url
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.0-RC.7/bundles/datastar.js")

(defn- shim-page-enter
  "Enter fn for shim-page interceptor."
  [opts context]
  (let [{:keys [title stream-path body head datastar-url html-attrs]
         :or {title "Grain App" datastar-url default-datastar-url}} opts
        query-string (get-in context [:request :query-string])
        effective-stream-path (when stream-path
                                (if (and query-string (seq query-string))
                                  (str stream-path "?" query-string)
                                  stream-path))
        page-html (str "<!DOCTYPE html>"
                       (h/html
                         [:html (or html-attrs {})
                          [:head
                           [:meta {:charset "UTF-8"}]
                           [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                           [:title title]
                           [:script {:type "module" :src datastar-url}]
                           (when head head)]
                          [:body (when effective-stream-path
                                  {:data-init (str "@get('" effective-stream-path "')")})
                           (when body body)
                           (when effective-stream-path
                             [:div {:id "app"}])]]))]
    (assoc context :response
           {:status 200
            :headers {"Content-Type" "text/html; charset=UTF-8"}
            :body page-html})))

(defn shim-page
  "Interceptor factory. HTML shell with Datastar JS.
   Forwards the request's query string to the stream-path URL so that
   query params (e.g. ?error=...) are available to the stream-view query."
  [opts]
  {:name ::shim-page
   :enter #(shim-page-enter opts %)})

;; ---------------------- ;;
;; Command Action Handler ;;
;; ---------------------- ;;

(defn execute-action
  "Executes a command from raw signals. Returns an SSE event map."
  [context signals]
  (let [command (decode-json-command signals)
        command-context (assoc context :command command)
        command-registry (or (:command-registry command-context)
                             @cp/command-registry*)
        authorized? (get-in command-registry
                           [(:command/name command) :authorized?])]
    (if (and authorized? (not (true? (authorized? command-context))))
      (patch-signals {:error "Unauthorized"} {})
      (let [result (cp/process-command command-context)]
        (cond
          (anomaly? result)
          (patch-signals (cond-> {:error (::anom/message result)}
                           (:error/explain result)
                           (assoc :fieldErrors (:error/explain result)))
                         {})

          (:datastar/signals result)
          (patch-signals (:datastar/signals result) {})

          (:command/result result)
          (patch-signals (:command/result result) {}))))))

(defn- action-handler-enter
  "Enter fn for action-handler interceptor."
  [context heartbeat-delay pedestal-context]
  (let [signals (parse-signals (:request pedestal-context))
        additional-context (:grain/additional-context pedestal-context)
        context-with-auth (merge context additional-context)]
    (sse/start-stream
      (fn [event-ch _sse-ctx]
        (try
          (when-let [event (execute-action context-with-auth signals)]
            (async/>!! event-ch event))
          (catch Exception e (u/log ::action-handler-error :error e))
          (finally (async/close! event-ch))))
      pedestal-context
      heartbeat-delay)))

(defn action-handler
  "Interceptor factory. Processes commands from Datastar signals."
  [context opts]
  (let [{:keys [heartbeat-delay] :or {heartbeat-delay 10}} opts]
    {:name ::action-handler
     :enter #(action-handler-enter context heartbeat-delay %)}))

;; ---------------------- ;;
;; Auto-Route Generation  ;;
;; ---------------------- ;;

(defn- query-name->route-name
  "Converts :user/sign-in-page to ::user-sign-in-page"
  [query-name]
  (keyword "ai.obney.grain.datastar.core"
           (str (namespace query-name) "-" (name query-name))))

(defn- query->route-pair
  "Given a query-name and its registry entry, returns [shim-route stream-route].
   Returns nil if the entry has no :datastar/path."
  [context query-name entry]
  (when-let [path (:datastar/path entry)]
    (let [stream-path (str path "/stream")
          title (or (:datastar/title entry) "Grain App")
          fps (:datastar/fps entry 30)
          interceptors (or (:datastar/interceptors entry) [])
          shim-opts (merge {:title title :stream-path stream-path}
                           (:datastar/shim-opts entry))
          route-base (query-name->route-name query-name)
          read-models (:grain/read-models entry)
          event-types (when (seq read-models) (resolve-events read-models))
          debounce-ms (:datastar/debounce-ms entry 50)
          event-tags (:datastar/event-tags entry)
          stream-opts (cond-> {:fps fps}
                        (seq event-types) (assoc :event-types event-types
                                                 :debounce-ms debounce-ms)
                        event-tags (assoc :event-tags event-tags))]
      [;; Shim page route
       [path :get (into interceptors [(shim-page shim-opts)])
        :route-name (keyword (namespace route-base)
                             (str (name route-base) "-page"))]
       ;; Stream route
       [stream-path :get (into interceptors [(stream-view context query-name stream-opts)])
        :route-name (keyword (namespace route-base)
                             (str (name route-base) "-stream"))]])))

(defn routes
  "Scans the query registry for entries with :datastar/path and generates
   Pedestal route pairs (shim-page + stream-view) for each.

   Optional overrides map: {query-name {:datastar/fps 2 :datastar/interceptors [...]}}"
  ([context] (routes context {}))
  ([context overrides]
   (let [registry (or (:query-registry context) @qp/query-registry*)]
     (into #{}
           (comp
            (filter (fn [[_ entry]] (:datastar/path entry)))
            (mapcat (fn [[qname entry]]
                      (let [merged (merge entry (get overrides qname))]
                        (query->route-pair context qname merged))))
            (remove nil?))
           registry))))
