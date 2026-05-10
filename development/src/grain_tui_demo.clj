(ns grain-tui-demo
  "Manual demo for the Grain TUI adapter — wired through real Grain
   primitives (defschemas / defcommand / defquery / defreadmodel + the
   actual command-processor-v2, query-processor, read-model-processor-v2,
   event-store-v3, pubsub) and composed via Integrant.

   What this exercises end-to-end:

     [user keystroke]
        -> session/dispatch! -> cp/process-command
        -> handler returns {:command-result/events [...]}
        -> event-store/append publishes events to pubsub (keyed by :event/type)
        -> tui session's per-screen sub-chan (subscribed via :grain/read-models)
           wakes up
        -> session re-runs qp/process-query
        -> query calls (rmp/project ctx :demo/counter-rm) which projects
           events through the read-model reducer
        -> rendered hiccup -> CellGrid -> diff -> ANSI

   Run with:

     clojure -M:dev -m grain-tui-demo

   Press +/=/-/*/  to change the counter, q to quit."
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
            [integrant.core :as ig]
            [malli.core :as m]))

;; ─────────────────────────────────────────────────────────────────────
;; Schemas — every command, query, event, and read-model state is shaped
;; ─────────────────────────────────────────────────────────────────────

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defschemas demo-schemas
  {;; A single keystroke changes the counter by a bounded delta.
   :demo/delta [:and :int [:>= -10] [:<= 10] [:not= 0]]

   ;; The :demo/change command carries the delta as input.
   :demo/change
   [:map {:closed true}
    [:command/name      [:= :demo/change]]
    [:command/id        :uuid]
    [:command/timestamp :time/offset-date-time]
    [:demo/delta        :demo/delta]]

   ;; The :demo/counter query has no extra params; the base :query/* keys
   ;; come from query-schema.interface and are checked separately.
   :demo/counter [:map {:closed true}
                  [:query/name      [:= :demo/counter]]
                  [:query/id        :uuid]
                  [:query/timestamp :time/offset-date-time]]

   ;; The event carries the same delta the command supplied. The event
   ;; store validates this against [:and ::schemas/event :demo/counter-changed],
   ;; so the base event fields (:event/id, :event/timestamp, :event/type,
   ;; :event/tags) are also required.
   :demo/counter-changed
   [:map
    [:demo/delta :demo/delta]]

   ;; The shape of the read-model state. Validated by the query handler
   ;; before it returns — a misbehaving reducer would surface here rather
   ;; than as garbled output. Grain's read-model-processor doesn't validate
   ;; state automatically, so this is the only thing pinning it down.
   ;; Bounds are wide enough that normal keyboard autorepeat won't trip
   ;; them; demonstrate validation by registering a sillier reducer.
   :demo/counter-state
   [:map {:closed true}
    [:count       :int]
    [:event-count [:and :int [:>= 0]]]]})

;; ─────────────────────────────────────────────────────────────────────
;; Read model — sums deltas from every counter-changed event
;; ─────────────────────────────────────────────────────────────────────

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(rmp/defreadmodel :demo counter-rm
  {:events  #{:demo/counter-changed}
   :version 1}
  [state event]
  (-> state
      (update :count       (fnil + 0) (:demo/delta event))
      (update :event-count (fnil inc 0))))

;; ─────────────────────────────────────────────────────────────────────
;; Command — emits a :demo/counter-changed event carrying the input delta
;; ─────────────────────────────────────────────────────────────────────

(defcommand :demo change
  {:authorized? (constantly true)}
  [context]
  (let [delta (get-in context [:command :demo/delta])]
    {:command-result/events [(es/->event {:type :demo/counter-changed
                                          :body {:demo/delta delta}})]
     :command/result        {:demo/delta delta}}))

;; ─────────────────────────────────────────────────────────────────────
;; Query — projects the read model into the screen's view-data
;; ─────────────────────────────────────────────────────────────────────

(defquery :demo counter
  {:authorized?       (constantly true)
   :grain/read-models {:demo/counter-rm 1}}
  [context]
  (let [raw   (rmp/project context :demo/counter-rm)
        state (merge {:count 0 :event-count 0} raw)]
    (if (m/validate :demo/counter-state state)
      {:query/result (assoc state :last-query (str (time/now)))}
      {:cognitect.anomalies/category :cognitect.anomalies/fault
       :cognitect.anomalies/message  "Read-model state failed schema validation"
       :error/explain                (m/explain :demo/counter-state state)})))

;; ─────────────────────────────────────────────────────────────────────
;; Screen — TUI metadata that the adapter consumes
;; ─────────────────────────────────────────────────────────────────────

(def counter-screen
  {:query-id          :demo/counter
   :inputs            {}
   :grain/read-models {:demo/counter-rm 1}
   :tui/buffer        :alt
   :tui/projection    :snapshot
   :tui/render
   (fn [{:keys [count event-count last-query]}]
     [:col
      [:text {:bold? true :fg :cyan} "Grain TUI Demo — counter"]
      [:line]
      [:row
       [:text "Count: "]
       [:text {:bold? true :fg :yellow} (str count)]
       [:text {:dim? true} (str "   (events projected: " event-count ")")]]
      [:text {:dim? true} (str "(query last ran: " last-query ")")]
      [:line]
      [:text {:dim? true}
       "[+] +1   [=] +1   [-] -1   [*] +5   [/] -5   [q] quit"]])
   :tui/keymap
   {"q" [:session :quit]
    "+" [:command :demo/change {:inputs {:demo/delta 1}}]
    "=" [:command :demo/change {:inputs {:demo/delta 1}}]
    "-" [:command :demo/change {:inputs {:demo/delta -1}}]
    "*" [:command :demo/change {:inputs {:demo/delta 5}}]
    "/" [:command :demo/change {:inputs {:demo/delta -5}}]}})

;; ─────────────────────────────────────────────────────────────────────
;; Integrant components — Grain infrastructure + TUI transport
;; ─────────────────────────────────────────────────────────────────────

(defmethod ig/init-key ::tenant-id [_ _]
  (random-uuid))

(defmethod ig/init-key ::cache-dir [_ {:keys [tenant-id]}]
  (str "/tmp/grain-tui-demo-" tenant-id))

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
;; System configuration
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
    :default-screen     counter-screen
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
   (when-let [s @system*]
     (stop! s)))
  ([system]
   (try (ig/halt! system) (catch Exception _ nil))
   (reset! system* nil)
   :stopped))

(defn -main
  "Boot the system, join the session loop thread, then halt. Designed
   for `clojure -M:dev -m grain-tui-demo`."
  [& _args]
  (let [system  (start!)
        handle  (get system ::tui-system/tui-stdio-transport)
        ^Thread loop-thread (:loop-thread @(:session handle))]
    (.join loop-thread)
    (stop! system)
    (System/exit 0)))

(comment
  ;; Run from REPL:
  (def s (start!))
  (stop!))
