(ns grain-tui-demo
  "Manual demo for the Grain TUI adapter.

   Boots a counter app:
     - Read model: counts how many `:demo/ticked` events have arrived.
     - Query: returns the count + last-tick timestamp.
     - The query declares `:grain/read-models {:demo/counter 1}` so the
       TUI adapter auto-subscribes; events update the screen reactively.
     - A todo processor (here implemented inline as a periodic-task) emits
       `:demo/ticked` events at a fixed rate.
     - Keymap: `+` increments via a command, `q` quits.

   Run from the development project:

     (in-ns 'grain-tui-demo)
     (def demo (start!))
     ;; ...
     (stop! demo)

   Or as a CLI:

     clojure -M:dev -e \"(require 'grain-tui-demo) (grain-tui-demo/start!)\""
  (:require [clojure.core.async :as async]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.tui-adapter.interface :as tui]
            [ai.obney.grain.tui-adapter.transport.stdio :as stdio]))

;; ─────────────────────────────────────────────────────────────────────
;; Mock state — keeps the demo self-contained (no event store needed)
;; ─────────────────────────────────────────────────────────────────────

(defonce ^:private demo-state (atom {:count 0 :last-tick "—"}))

(defn- process-query-fn [_ctx]
  {:query/result @demo-state})

(defn- process-command-fn [{:keys [command]}]
  (case (:command/name command)
    :demo/increment
    (do (swap! demo-state update :count inc)
        {:command/result :ok})

    :demo/decrement
    (do (swap! demo-state update :count dec)
        {:command/result :ok})

    {:command/result :unknown}))

;; ─────────────────────────────────────────────────────────────────────
;; The screen
;; ─────────────────────────────────────────────────────────────────────

(def counter-screen
  {:query-id :demo/counter
   :inputs   {}
   :grain/read-models {:demo/counter 1}
   :tui/buffer     :alt
   :tui/projection :snapshot
   :tui/render
   (fn [{:keys [count last-tick]}]
     [:col
      [:text {:bold? true :fg :cyan} "Grain TUI Demo — counter"]
      [:line]
      [:row
       [:text "Count: "]
       [:text {:bold? true :fg :yellow} (str count)]]
      [:text {:dim? true} (str "Last tick: " last-tick)]
      [:line]
      [:text {:dim? true} "[+] increment   [-] decrement   [q] quit"]])
   :tui/keymap
   {"q" [:session :quit]
    "+" [:command :demo/increment]
    "=" [:command :demo/increment]
    "-" [:command :demo/decrement]}})

;; ─────────────────────────────────────────────────────────────────────
;; Lifecycle
;; ─────────────────────────────────────────────────────────────────────

(defn start!
  "Start the demo. Returns a handle suitable for `stop!`."
  []
  (reset! demo-state {:count 0 :last-tick "—"})
  (let [pubsub (pubsub/start {:type   :core-async
                              :config {:topic-fn :event/type}})
        ;; Tick generator: emit a :demo/ticked event every 2 seconds.
        ticker (async/thread
                 (loop []
                   (Thread/sleep 2000)
                   (try
                     (pubsub/pub pubsub
                                 {:message {:event/type :demo/ticked
                                            :event/at   (str (java.time.Instant/now))}})
                     (swap! demo-state assoc :last-tick (str (java.time.Instant/now)))
                     (catch Exception _ nil))
                   (when @demo-state (recur))))
        handle (stdio/start-stdio-session
                 {:default-screen     counter-screen
                  :process-query-fn   process-query-fn
                  :process-command-fn process-command-fn
                  :event-pubsub       pubsub
                  :base-context       {}
                  :tenant-resolver    (constantly (random-uuid))
                  :user-resolver      (constantly nil)
                  :debounce-ms        50})]
    (assoc handle :pubsub pubsub :ticker ticker)))

(defn stop!
  [{:keys [pubsub] :as handle}]
  (try (stdio/stop-stdio-session handle) (catch Exception _ nil))
  (when pubsub (pubsub/stop pubsub))
  (reset! demo-state nil)
  :stopped)

(comment
  ;; Run from REPL:
  (def demo (start!))
  (stop! demo))
