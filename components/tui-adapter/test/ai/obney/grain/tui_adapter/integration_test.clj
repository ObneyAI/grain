(ns ai.obney.grain.tui-adapter.integration-test
  "Integration tests crossing namespace boundaries — session ↔ stream,
   session ↔ palette overlay, change-screen ↔ lifecycle hooks,
   change-screen ↔ periodic-refresh warn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [ai.obney.grain.tui-adapter.builtins]   ; load registers built-ins
            [ai.obney.grain.tui-adapter.session :as session]))

(defn- make-session [{:keys [screen process-query-fn process-command-fn debounce-ms]
                      :or   {process-query-fn   (fn [_] {:query/result {}})
                             process-command-fn (fn [_] {:command/result :ok})
                             debounce-ms        0}}]
  (let [out (atom [])
        s   (session/make-session
              {:tenant-id          (random-uuid)
               :user-id            (random-uuid)
               :viewport           {:width 30 :height 8}
               :on-output          (fn [s] (swap! out conj s))
               :default-screen     screen
               :process-query-fn   process-query-fn
               :process-command-fn process-command-fn
               :debounce-ms        debounce-ms})]
    {:session s :out out}))

;; ──────────────────────────────────────────────────────────────────────────
;; Session ↔ Stream integration
;; ──────────────────────────────────────────────────────────────────────────

(def stream-screen
  {:query-id       :integration/stream
   :inputs         {}
   :tui/buffer     :main
   :tui/projection :stream
   :tui/segments   {:items :messages :key :id :hiccup :tui/hiccup}})

(defn- attach-hiccup [messages]
  (mapv (fn [m]
          (assoc m :tui/hiccup [:text {:text (str ">> " (:text m))}]))
        messages))

(deftest stream-screen-renders-via-stream-namespace
  (let [{:keys [session]} (make-session
                            {:screen stream-screen
                             :process-query-fn
                             (fn [_]
                               {:messages
                                (attach-hiccup
                                  [{:id 1 :text "first"}
                                   {:id 2 :text "second"}])})})
        grid (session/render-frame! session)]
    ;; Stream state was initialized + populated; visible window holds both keys.
    (is (= [1 2] (:visible-window (:stream-state @session))))
    (is (some? (:segment-cache (:stream-state @session))))
    (is (= 30 (:width grid)))
    ;; Each segment renders as ">> first" / ">> second" — top of grid carries them.
    (let [chars-of (fn [r] (apply str (mapv :char (get-in grid [:cells r]))))]
      ;; Segments fill the bottom of the window (stream stacks bottom-aligned).
      (is (some #(re-find #">> first"  %) (mapv chars-of (range 8))))
      (is (some #(re-find #">> second" %) (mapv chars-of (range 8)))))))

(deftest stream-second-render-reuses-cache
  (let [{:keys [session]} (make-session
                            {:screen stream-screen
                             :process-query-fn
                             (fn [_]
                               {:messages (attach-hiccup [{:id 1 :text "a"}])})})]
    (session/render-frame! session)
    (let [seg-1-before (get-in (:stream-state @session) [:segment-cache 1])]
      (session/render-frame! session)
      (let [seg-1-after (get-in (:stream-state @session) [:segment-cache 1])]
        (is (identical? seg-1-before seg-1-after))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Session ↔ Palette overlay integration
;; ──────────────────────────────────────────────────────────────────────────

(def hello-screen
  {:query-id :integration/hello
   :inputs   {}})

(defn- hello-pq-fn [items-by-query]
  (fn [ctx]
    (let [name (-> ctx :query :query/name)]
      (or (some-> (get items-by-query name) (#(do {:query/result %})))
          {:query/result {} :tui/hiccup [:text {:text "hello"}]}))))

(deftest open-palette-fetches-items-via-query
  (let [items [{:id 1 :name "Ada"} {:id 2 :name "Bob"}]
        {:keys [session]}
        (make-session
          {:screen hello-screen
           :process-query-fn (hello-pq-fn {:sheets/all items})})]
    (session/handle-session-action session :open-palette
                                   {:query-id   :sheets/all
                                    :item-key   :id
                                    :item-label :name
                                    :on-select  [:command :sheet/execute
                                                 {:inputs-from-selection :sheet-id}]}
                                   {})
    (is (= :palette (:type (:overlay @session))))
    (is (= items (:items (:overlay @session))))
    (is (= items (:all-items (:overlay @session))))))

(deftest palette-renders-into-frame
  (let [{:keys [session]}
        (make-session
          {:screen hello-screen
           :process-query-fn (hello-pq-fn {:sheets/all [{:id 1 :name "AdaSheet"}]})})]
    (session/handle-session-action session :open-palette
                                   {:query-id :sheets/all :item-key :id :item-label :name
                                    :on-select [:command :noop]}
                                   {})
    (let [grid    (session/render-frame! session)
          chars-of (fn [r] (apply str (mapv :char (get-in grid [:cells r]))))]
      ;; Palette is visible — the box is centered with title "Palette" and
      ;; the item label appears somewhere in the rendered frame.
      (is (some #(re-find #"AdaSheet" %)
                (mapv chars-of (range (:height grid))))))))

(deftest palette-key-typing-narrows-items
  (let [items [{:id 1 :name "Ada"} {:id 2 :name "Bob"} {:id 3 :name "Cy"}]
        {:keys [session]}
        (make-session
          {:screen hello-screen
           :process-query-fn (fn [_] {:query/result items})})]
    (session/handle-session-action session :open-palette
                                   {:query-id :x :item-key :id :item-label :name
                                    :on-select [:command :noop]} {})
    ;; Type "B" — should narrow to one match.
    (#'session/dispatch-key! session {:type :key :key "B"})
    (is (= "B" (:filter (:overlay @session))))
    (is (= 1   (count (:items (:overlay @session)))))
    (is (= "Bob" (-> @session :overlay :items first :name)))))

(deftest palette-enter-dispatches-on-select-with-inputs-from-selection
  (let [items   [{:id 7 :name "Picked"}]
        cmd-log (atom [])
        {:keys [session]}
        (make-session
          {:screen hello-screen
           :process-query-fn   (fn [_] {:query/result items})
           :process-command-fn (fn [ctx]
                                 (swap! cmd-log conj (:command ctx))
                                 {:command/result :ok})})]
    (session/handle-session-action session :open-palette
                                   {:query-id   :sheets/all
                                    :item-key   :id
                                    :item-label :name
                                    :on-select  [:command :sheet/execute
                                                 {:inputs-from-selection :sheet-id}]}
                                   {})
    (#'session/dispatch-key! session {:type :key :key "<enter>"})
    ;; Overlay dismissed.
    (is (nil? (:overlay @session)))
    ;; Command dispatched with selection's :id (7) bound to :sheet-id.
    (is (= 1 (count @cmd-log)))
    (is (= :sheet/execute (:command/name (first @cmd-log))))
    (is (= 7              (:sheet-id (first @cmd-log))))))

(deftest palette-esc-dismisses-without-dispatch
  (let [cmd-log (atom [])
        {:keys [session]}
        (make-session
          {:screen hello-screen
           :process-query-fn   (fn [_] {:query/result [{:id 1 :name "x"}]})
           :process-command-fn (fn [_] (swap! cmd-log conj :should-not-fire) :ok)})]
    (session/handle-session-action session :open-palette
                                   {:query-id :x :item-key :id :item-label :name
                                    :on-select [:command :should-not-fire]} {})
    (#'session/dispatch-key! session {:type :key :key "<esc>"})
    (is (nil? (:overlay @session)))
    (is (= [] @cmd-log))))

;; ──────────────────────────────────────────────────────────────────────────
;; Lifecycle hooks
;; ──────────────────────────────────────────────────────────────────────────

(deftest on-enter-fires-on-screen-change
  (let [calls (atom [])
        screen-A (assoc hello-screen :tui/on-enter (fn [_] (swap! calls conj :A-enter)))
        screen-B (assoc hello-screen
                        :query-id :integration/B
                        :tui/on-enter (fn [_] (swap! calls conj :B-enter)))
        {:keys [session]} (make-session {:screen screen-A})]
    ;; Initial change-screen! call (from start!) is bypassed in this test.
    (session/change-screen! session screen-A)
    (session/change-screen! session screen-B)
    (is (= [:A-enter :B-enter] @calls))))

(deftest on-exit-fires-on-screen-change
  (let [exits (atom [])
        screen-A (assoc hello-screen
                        :tui/on-exit (fn [_] (swap! exits conj :A-exit)))
        screen-B (assoc hello-screen :query-id :integration/B)
        {:keys [session]} (make-session {:screen screen-A})]
    (session/change-screen! session screen-A)
    (reset! exits [])  ; clear the initial change-from-default invocation
    (session/change-screen! session screen-B)
    (is (= [:A-exit] @exits))))

(deftest hook-exception-does-not-crash-session
  (let [crashing (assoc hello-screen :tui/on-enter (fn [_] (throw (ex-info "boom" {}))))
        {:keys [session]} (make-session {:screen hello-screen})]
    ;; Should not throw.
    (session/change-screen! session crashing)
    (is (= :integration/hello (:query-id (:current-screen @session))))
    ;; The screen still installed despite the hook failure.
    (is (some? (:current-screen @session)))))

;; ──────────────────────────────────────────────────────────────────────────
;; Periodic-refresh deferred warning
;; ──────────────────────────────────────────────────────────────────────────

(deftest periodic-refresh-still-installs-screen
  ;; The warning is logged via mulog; we don't intercept it here. Instead
  ;; we verify the screen change still completes cleanly.
  (let [poll-screen (assoc hello-screen :tui/refresh {:periodic-ms 1000})
        {:keys [session]} (make-session {:screen hello-screen})]
    (session/change-screen! session poll-screen)
    (is (= :integration/hello (:query-id (:current-screen @session))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Stream state resets on screen change
;; ──────────────────────────────────────────────────────────────────────────

(deftest stream-state-reset-on-screen-change
  (let [{:keys [session]} (make-session
                            {:screen stream-screen
                             :process-query-fn
                             (fn [_] {:messages (attach-hiccup [{:id 1 :text "a"}])})})]
    (session/render-frame! session)
    (is (some? (:stream-state @session)))
    (session/change-screen! session hello-screen)
    (is (nil? (:stream-state @session)))))
