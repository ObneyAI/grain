(ns ai.obney.grain.tui-client.render-test
  "Tests for the frame → CellGrid → ANSI rendering path."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.frame :as frame]
            [ai.obney.grain.tui-adapter.input-area :as input-area]
            [ai.obney.grain.tui-adapter.session :as session]
            [ai.obney.grain.tui-client.render :as render]))

(def ^:private slot-marker
  :ai.obney.grain.tui-adapter.input-slot/sentinel)

(defn- has-sentinel? [g]
  (boolean (some (fn [row] (some #(get % slot-marker) row)) (:cells g))))

(defn- chars-of [grid r]
  (apply str (mapv :char (get-in grid [:cells r]))))

;; ──────────────────────────────────────────────────────────────────────
;; frame->grid — snapshot, stream, regions, error, overlay
;; ──────────────────────────────────────────────────────────────────────

(deftest snapshot-frame-renders-hiccup-into-viewport
  (let [frame {:hiccup [:text {:text "hello"}]}
        grid  (render/frame->grid frame {:width 10 :height 1})]
    (is (= 10 (:width grid)))
    (is (= 1 (:height grid)))
    (is (= "hello     " (chars-of grid 0)))))

(deftest error-frame-renders-error-block
  (let [frame {:error {:headline "Query error" :message "boom"}}
        grid  (render/frame->grid frame {:width 20 :height 3})]
    ;; The error hiccup is a :col of [headline, message, hint].
    (is (re-find #"Query error" (chars-of grid 0)))
    (is (re-find #"boom"        (chars-of grid 1)))))

(deftest regions-frame-stacks-region-hiccup
  (let [frame {:regions {:a [:text {:text "AAA"}]
                         :b [:text {:text "BBB"}]}}
        grid  (render/frame->grid frame {:width 5 :height 2})]
    ;; Order is insertion order — :a then :b.
    (is (re-find #"AAA" (chars-of grid 0)))
    (is (re-find #"BBB" (chars-of grid 1)))))

(deftest stream-frame-stacks-segment-hiccup
  (let [frame {:segments [{:id 1 :tui/hiccup [:text {:text "one"}]}
                          {:id 2 :tui/hiccup [:text {:text "two"}]}]
               :metadata {:segments-spec {:items :ignored :key :id :hiccup :tui/hiccup}}}
        grid  (render/frame->grid frame {:width 5 :height 2})]
    (is (re-find #"one" (chars-of grid 0)))
    (is (re-find #"two" (chars-of grid 1)))))

(deftest overlay-composites-onto-screen-grid
  ;; Use a toast overlay with simple text content. The frame's primary
  ;; content is :hiccup; the overlay should appear *over* it.
  (let [frame {:hiccup  [:text {:text "main content here"}]
               :overlay {:type :toast :content [:text {:text "TOAST"}]}}
        grid  (render/frame->grid frame {:width 30 :height 5})]
    ;; The toast lands in some row of the grid (overlay positioning is
    ;; deterministic but row depends on the toast-position helper; we
    ;; just check that "TOAST" appears somewhere).
    (let [all-rows (mapv #(chars-of grid %) (range 5))]
      (is (some #(re-find #"TOAST" %) all-rows)))))

;; ──────────────────────────────────────────────────────────────────────
;; render-frame! — diff + emit
;; ──────────────────────────────────────────────────────────────────────

(deftest render-frame-emits-bytes-on-first-paint
  (let [out   (atom [])
        state {:render-model nil :ansi-style nil
               :terminal-caps {:color :truecolor}}
        new-state
        (render/render-frame! state
                              {:hiccup [:text {:text "hi"}]}
                              {:width 5 :height 1}
                              #(swap! out conj %))]
    (is (pos? (count @out)))
    (is (some? (:render-model new-state)))
    ;; The emitted bytes should mention "hi".
    (is (some #(re-find #"hi" %) @out))))

(deftest render-frame-emits-nothing-when-unchanged
  (let [out   (atom [])
        st0   {:render-model nil :ansi-style nil
               :terminal-caps {:color :truecolor}}
        st1   (render/render-frame! st0
                                    {:hiccup [:text {:text "hi"}]}
                                    {:width 5 :height 1}
                                    #(swap! out conj %))
        _     (reset! out [])
        _st2  (render/render-frame! st1
                                    {:hiccup [:text {:text "hi"}]}
                                    {:width 5 :height 1}
                                    #(swap! out conj %))]
    (is (empty? @out)
        "idempotent frame should produce zero bytes on second paint")))

;; ──────────────────────────────────────────────────────────────────────
;; :cells leaf — pre-rendered grids embedded by the server's resolver
;; round-trip through layout client-side.
;; ──────────────────────────────────────────────────────────────────────

(deftest cells-leaf-roundtrips-from-server
  (let [server-grid (cells/text-row 5 {:fg :green} "abcde")
        frame       {:hiccup [:cells {:grid server-grid}]}
        rendered    (render/frame->grid frame {:width 5 :height 1})]
    (is (= "a" (get-in rendered [:cells 0 0 :char])))
    (is (= :green (get-in rendered [:cells 0 0 :fg])))))

;; ──────────────────────────────────────────────────────────────────────
;; :placement :slot client-side (B) + server/client parity (R3)
;; ──────────────────────────────────────────────────────────────────────

(def ^:private slot-hiccup
  [:col [:text {:text "HEADER"}]
        [:input-slot {:height 2}]
        [:text {:text "FOOTER"}]])

(deftest client-places-input-in-slot
  (let [frame {:hiccup slot-hiccup
               :input  {:prompt "> " :placement :slot}}
        g     (render/frame->grid frame {:width 30 :height 6}
                                  (input-area/initial-state))]
    (is (re-find #"HEADER" (chars-of g 0)))
    (is (re-find #"FOOTER" (chars-of g 3)))
    (is (re-find #"^> "    (chars-of g 2)))
    (is (not (has-sentinel? g)))))

(deftest server-client-slot-parity
  ;; The same screen + viewport + input-area state must produce an
  ;; identical grid whether composited server-side
  ;; (session/render-frame-alt!) or client-side (render/frame->grid from
  ;; the produced Frame). This is the R3 guarantee.
  (let [screen   {:query-id :p
                  :inputs   {}
                  :tui/input {:command :x :prompt "> " :placement :slot}}
        result   {:tui/hiccup slot-hiccup}
        vp       {:width 30 :height 6}
        ;; server
        out      (atom [])
        sess     (session/make-session
                   {:tenant-id        (random-uuid)
                    :user-id          (random-uuid)
                    :event-pubsub     nil
                    :viewport         vp
                    :on-output        (fn [s] (swap! out conj s))
                    :default-screen   screen
                    :process-query-fn (fn [_] result)
                    :debounce-ms      0})
        _        (session/render-frame! sess)
        server-g (:render-model @sess)
        ;; client
        fr       (frame/produce-frame {:current-screen screen :overlay nil}
                                      result)
        client-g (render/frame->grid fr vp (input-area/initial-state))]
    (is (not (has-sentinel? server-g)))
    (is (not (has-sentinel? client-g)))
    (is (= (:cells server-g) (:cells client-g))
        "server and client must composite the slot identically")))
