(ns grain-input-demo
  "Demo for the v0.8 §6.2 sticky input area (`:tui/input`).

   Echo chamber: type a message, press Enter, the message is committed
   as a Grain command → event → projected into a read model → the
   screen re-renders reactively via pubsub. The bottom row of the
   canvas is a sticky input area handled by the substrate: line
   editing (backspace/arrows/C-w/C-a/C-e/C-k/C-u), history navigation
   (Up/Down), submit on Enter.

   Run with:

     clojure -M:dev -m grain-input-demo

   - Type a message, Enter to send.
   - Up/Down to recall prior messages.
   - <esc> to quit. (Note: `q` and other printable keys go to the
     input buffer — that's the documented `:tui/input` semantics.
     Use <esc> or Ctrl-combos for screen-level binds.)

   Exercises end-to-end:
     [keystroke] → input-area state machine → on Enter:
       process-command-fn → events → pubsub →
       per-screen subscription wakes →
       query re-runs → frame produced → diff → ANSI → repaint."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp :refer [defcommand]]
            [ai.obney.grain.command-processor-v2.interface.schemas]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.query-processor.interface :as qp :refer [defquery]]
            [ai.obney.grain.query-schema.interface]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.tui-adapter.system :as tui-system]
            [integrant.core :as ig]))

;; ─────────────────────────────────────────────────────────────────────
;; Schemas
;; ─────────────────────────────────────────────────────────────────────

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas demo-schemas
  {;; The text the user typed. Bounded so a runaway paste can't grow
   ;; unbounded events.
   :demo/text [:and :string [:fn #(<= 1 (count %) 500)]]

   ;; The :demo/send command — :text comes from the substrate-managed
   ;; input area on submit. The TUI adapter binds buffered text at
   ;; `:text` by default (overridable via `:tui/input :input-key`).
   :demo/send
   [:map {:closed true}
    [:command/name      [:= :demo/send]]
    [:command/id        :uuid]
    [:command/timestamp :time/offset-date-time]
    [:text              :demo/text]]

   ;; The query has no extra params.
   :demo/messages
   [:map {:closed true}
    [:query/name      [:= :demo/messages]]
    [:query/id        :uuid]
    [:query/timestamp :time/offset-date-time]]

   ;; The event records what was said and when, plus a stable :id so
   ;; the read-model can shape each message as a streamable segment.
   :demo/message-sent
   [:map
    [:demo/id       :uuid]
    [:demo/text     :demo/text]
    [:demo/sent-at  :time/offset-date-time]]})

;; ─────────────────────────────────────────────────────────────────────
;; Read model — append every message to a list, capped at the last 100.
;; ─────────────────────────────────────────────────────────────────────

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(rmp/defreadmodel :demo messages-rm
  {:events  #{:demo/message-sent}
   :version 1}
  [state event]
  (let [msgs (vec (conj (or (:messages state) [])
                        {:id   (:demo/id event)
                         :text (:demo/text event)
                         :at   (:demo/sent-at event)}))]
    ;; Bound at 1000 — main-buffer streaming pushes older messages into
    ;; the terminal's scrollback regardless. Keeping the projection
    ;; bounded just prevents the read-model state from growing forever.
    (assoc state :messages (vec (take-last 1000 msgs)))))

;; ─────────────────────────────────────────────────────────────────────
;; Command — emit a :demo/message-sent event carrying the user's text
;; ─────────────────────────────────────────────────────────────────────

(defcommand :demo send
  {:authorized? (constantly true)}
  [context]
  (let [text (get-in context [:command :text])]
    {:command-result/events [(es/->event {:type :demo/message-sent
                                          :body {:demo/id      (random-uuid)
                                                 :demo/text    text
                                                 :demo/sent-at (time/now)}})]
     :command/result        {:demo/text text}}))

;; ─────────────────────────────────────────────────────────────────────
;; Query — projects the message list + builds the screen hiccup
;; ─────────────────────────────────────────────────────────────────────

(defn- format-time [t]
  ;; Produce HH:MM:SS from an OffsetDateTime stringified.
  ;; The full string looks like "2026-05-12T15:23:01.234Z"; we slice the
  ;; time portion. Safe for missing/short values.
  (let [s (str t)]
    (if (and (string? s) (>= (count s) 19))
      (subs s 11 19)
      s)))

(defn- message-row [{:keys [text at]}]
  [:row
   [:text {:dim? true} (format-time at)]
   [:gap 2]
   [:text text]])

(defquery :demo messages
  {:authorized?       (constantly true)
   :grain/read-models {:demo/messages-rm 1}

   ;; Spec §6.3 transcript pattern: each message becomes a stream
   ;; segment that emits as a terminal-scrolled row above the sticky
   ;; input area, flowing into the terminal's real scrollback.
   :tui/buffer        :main
   :tui/projection    :stream
   :tui/segments      {:items :messages :key :id :hiccup :tui/hiccup}

   ;; Substrate-rendered sticky input at the bottom of the visible
   ;; window. Survives any number of messages above.
   :tui/input
   {:command :demo/send
    :prompt  "> "
    :hint    "Type a message and press Enter  ·  ↑/↓ history  ·  <esc> to quit"}

   ;; Printable keys go to the input buffer; <esc> quits.
   :tui/keymap
   {"<esc>" [:session :quit]
    "C-l"   [:session :refresh]}}

  [context]
  (let [state (rmp/project context :demo/messages-rm)
        msgs  (or (:messages state) [])]
    {:query/result {:messages msgs :count (count msgs)}
     ;; Intro chrome — printed once on the first frame, then it
     ;; naturally scrolls into terminal scrollback as messages
     ;; accumulate above the sticky input.
     :tui/hiccup
     [:col
      [:text {:bold? true :fg :cyan} " Grain Input Demo — echo chamber"]
      [:text {:dim? true}            " Type below; older messages scroll into your terminal's history."]
      [:line]]
     ;; Per-segment hiccup — each message is its own append-emitted row.
     :messages
     (mapv (fn [m]
             (assoc m :tui/hiccup
                    [:row
                     [:text {:dim? true} (format-time (:at m))]
                     [:gap 2]
                     [:text (:text m)]]))
           msgs)}))

;; The stdio transport reads the screen's :tui/* metadata directly from
;; this map (it doesn't look up the query registry). The HTTP transport
;; does look up the registry. To keep both paths happy, we inline the
;; same metadata that's on the defquery above.
(def messages-screen
  {:query-id          :demo/messages
   :inputs            {}
   :grain/read-models {:demo/messages-rm 1}
   :tui/buffer        :main
   :tui/projection    :stream
   :tui/segments      {:items :messages :key :id :hiccup :tui/hiccup}
   :tui/input         {:command :demo/send
                       :prompt  "> "
                       :hint    "Type a message and press Enter  ·  ↑/↓ history  ·  <esc> to quit"}
   :tui/keymap
   {"<esc>" [:session :quit]
    "C-l"   [:session :refresh]}})

;; ─────────────────────────────────────────────────────────────────────
;; Integrant components — Grain infrastructure + stdio transport
;; ─────────────────────────────────────────────────────────────────────

(defmethod ig/init-key ::tenant-id [_ _]
  (random-uuid))

(defmethod ig/init-key ::cache-dir [_ {:keys [tenant-id]}]
  (str "/tmp/grain-input-demo-" tenant-id))

(defmethod ig/halt-key! ::cache-dir [_ ^String dir]
  (let [f (java.io.File. dir)]
    (when (.exists f)
      (run! #(.delete ^java.io.File %) (reverse (file-seq f))))))

(defmethod ig/init-key ::event-pubsub [_ _]
  (pubsub/start {:type :core-async :topic-fn :event/type}))

(defmethod ig/halt-key! ::event-pubsub [_ ps]
  (pubsub/stop ps))

(defmethod ig/init-key ::event-store [_ {:keys [event-pubsub]}]
  (es/start {:conn {:type :in-memory} :event-pubsub event-pubsub}))

(defmethod ig/halt-key! ::event-store [_ store]
  (es/stop store))

(defmethod ig/init-key ::cache [_ {:keys [cache-dir]}]
  (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "demo"})))

(defmethod ig/halt-key! ::cache [_ cache]
  (kv/stop cache)
  (rmp/l1-clear!))

(defmethod ig/init-key ::base-context
  [_ {:keys [event-store event-pubsub cache tenant-id]}]
  {:event-store      event-store
   :event-pubsub     event-pubsub
   :cache            cache
   :tenant-id        tenant-id
   :command-registry @cp/command-registry*
   :query-registry   @qp/query-registry*})

(defn- enrich-query [ctx]
  (update ctx :query merge {:query/id        (random-uuid)
                            :query/timestamp (time/now)}))

(defn- enrich-command [ctx]
  (update ctx :command merge {:command/id        (random-uuid)
                              :command/timestamp (time/now)}))

(defmethod ig/init-key ::process-query-fn
  [_ {:keys [base-context]}]
  (fn [ctx]
    (qp/process-query (enrich-query (merge base-context ctx)))))

(defmethod ig/init-key ::process-command-fn
  [_ {:keys [base-context]}]
  (fn [ctx]
    (cp/process-command (enrich-command (merge base-context ctx)))))

(defmethod ig/init-key ::tenant-resolver
  [_ {:keys [tenant-id]}]
  (constantly tenant-id))

(defmethod ig/init-key ::user-resolver
  [_ _]
  (constantly nil))

;; ─────────────────────────────────────────────────────────────────────
;; System
;; ─────────────────────────────────────────────────────────────────────

(def system-config
  {::tenant-id        {}
   ::cache-dir        {:tenant-id (ig/ref ::tenant-id)}
   ::event-pubsub     {}
   ::event-store      {:event-pubsub (ig/ref ::event-pubsub)}
   ::cache            {:cache-dir    (ig/ref ::cache-dir)}
   ::base-context     {:event-store  (ig/ref ::event-store)
                       :event-pubsub (ig/ref ::event-pubsub)
                       :cache        (ig/ref ::cache)
                       :tenant-id    (ig/ref ::tenant-id)}
   ::process-query-fn   {:base-context (ig/ref ::base-context)}
   ::process-command-fn {:base-context (ig/ref ::base-context)}
   ::tenant-resolver    {:tenant-id   (ig/ref ::tenant-id)}
   ::user-resolver      {}
   ::tui-system/tui-registry {}
   ::tui-system/tui-stdio-transport
   {:registry           (ig/ref ::tui-system/tui-registry)
    :event-pubsub       (ig/ref ::event-pubsub)
    :base-context       (ig/ref ::base-context)
    :default-screen     messages-screen
    :process-query-fn   (ig/ref ::process-query-fn)
    :process-command-fn (ig/ref ::process-command-fn)
    :tenant-resolver    (ig/ref ::tenant-resolver)
    :user-resolver      (ig/ref ::user-resolver)
    :debounce-ms        50}})

;; ─────────────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────────────

(defonce ^:private system* (atom nil))

(defn start!
  "Initialize the full system via Integrant. Returns the running system map."
  []
  (when @system*
    (throw (ex-info "Demo already running — call (stop!) first" {})))
  (reset! system* (ig/init system-config))
  @system*)

(defn stop!
  ([]
   (when-let [s @system*] (stop! s)))
  ([system]
   (try (ig/halt! system) (catch Exception _ nil))
   (reset! system* nil)
   :stopped))

(defn -main
  "Boot the system, join the session loop thread, then halt. Designed
   for `clojure -M:dev -m grain-input-demo`."
  [& _args]
  (let [system  (start!)
        handle  (get system ::tui-system/tui-stdio-transport)
        ^Thread loop-thread (:loop-thread @(:session handle))]
    (.join loop-thread)
    (stop! system)
    (System/exit 0)))

(comment
  (def s (start!))
  (stop!))
