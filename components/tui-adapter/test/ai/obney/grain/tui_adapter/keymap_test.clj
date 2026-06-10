(ns ai.obney.grain.tui-adapter.keymap-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.keymap :as km]))

;; ──────────────────────────────────────────────────────────────────────────
;; valid-action? / assert-action!
;; ──────────────────────────────────────────────────────────────────────────

(deftest valid-action-shapes
  (is (km/valid-action? [:command :foo/bar]))
  (is (km/valid-action? [:command :foo/bar {:inputs {:k 1}}]))
  (is (km/valid-action? [:session :quit]))
  (is (km/valid-action? [:session :open-overlay {:type :modal}])))

(deftest invalid-action-shapes
  (is (not (km/valid-action? nil)))
  (is (not (km/valid-action? [:foo :bar])))           ; bad tag
  (is (not (km/valid-action? [:command])))             ; no name
  (is (not (km/valid-action? [:command "string"])))    ; name not keyword
  (is (not (km/valid-action? [:command :n :not-map]))) ; opts not map
  (is (not (km/valid-action? [:command :n {} :extra]))))

(deftest assert-action-throws-on-invalid
  (is (thrown? clojure.lang.ExceptionInfo
               (km/assert-action! [:foo :bar])))
  (is (= [:command :x] (km/assert-action! [:command :x]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Layered resolution priority
;; ──────────────────────────────────────────────────────────────────────────

(deftest layer-priority-overlay-wins-over-screen
  (let [overlay {"q" [:session :dismiss-overlay]}
        screen  {"q" [:session :quit]}
        stack   (km/build-stack {:overlay overlay :screen screen})
        result  (km/resolve-key stack [] "q")]
    (is (= [:session :dismiss-overlay] (:action result)))))

(deftest layer-priority-screen-wins-over-session
  (let [screen  {"x" [:command :screen/x]}
        session {"x" [:command :session/x]}
        stack   (km/build-stack {:screen screen :session session})
        result  (km/resolve-key stack [] "x")]
    (is (= [:command :screen/x] (:action result)))))

(deftest first-match-within-stack
  (let [layer {"a" [:command :foo]
               "b" [:command :bar]}
        stack (km/build-stack {:screen layer})
        ra    (km/resolve-key stack [] "a")
        rb    (km/resolve-key stack [] "b")]
    (is (= [:command :foo] (:action ra)))
    (is (= [:command :bar] (:action rb)))))

(deftest nil-layers-skipped
  (let [stack (km/build-stack {:overlay nil :screen {"q" [:session :quit]}})]
    (is (= 1 (count stack)))
    (is (= [:session :quit] (:action (km/resolve-key stack [] "q"))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Sequence chords
;; ──────────────────────────────────────────────────────────────────────────

(deftest sequence-chord-completes
  (let [layer {["g" "g"] [:session :scroll-top]}
        stack (km/build-stack {:screen layer})
        r1    (km/resolve-key stack [] "g")
        r2    (km/resolve-key stack (:buffer r1) "g")]
    (is (= :pending (:state r1)))
    (is (= ["g"] (:buffer r1)))
    (is (= :match (:state r2)))
    (is (= [:session :scroll-top] (:action r2)))))

(deftest sequence-chord-interrupted
  (let [layer {["g" "g"] [:session :scroll-top]
               "x"       [:command :do-x]}
        stack (km/build-stack {:screen layer})
        r1    (km/resolve-key stack [] "g")
        r2    (km/resolve-key stack (:buffer r1) "x")]
    (is (= :pending (:state r1)))
    ;; "x" doesn't continue the chord; sequence buffer cleared.
    (is (= :no-match (:state r2)))
    (is (= [] (:buffer r2)))))

(deftest single-key-only-matches-when-buffer-empty
  (let [layer {["g" "g"] [:session :scroll-top]
               "g"       [:command :one-g]}
        stack (km/build-stack {:screen layer})
        r1    (km/resolve-key stack [] "g")]
    ;; First "g": chord is a prefix → :pending; single-key "g" only matches
    ;; when buffer is empty AND no chord has it as prefix? Actually single-key
    ;; "g" exists too. resolve-in-layer first tries [g] (the candidate vector
    ;; of length 1), which matches the single-key entry "g". So actually we
    ;; expect :match here, since the candidate as a 1-vec collapses to "g".
    (is (= :match (:state r1)))
    (is (= [:command :one-g] (:action r1)))))

;; ──────────────────────────────────────────────────────────────────────────
;; build-inputs — input-derivation
;; ──────────────────────────────────────────────────────────────────────────

(deftest inputs-literal
  (is (= {:k 1} (km/build-inputs {:inputs {:k 1}} {}))))

(deftest inputs-from-selection
  (is (= {:item 42}
         (km/build-inputs {:inputs-from-selection :item}
                          {:selection 42}))))

(deftest inputs-from-focus
  (is (= {:where :sidebar}
         (km/build-inputs {:inputs-from-focus :where}
                          {:focus :sidebar}))))

(deftest inputs-from-prompt
  (is (= {:msg "hi"}
         (km/build-inputs {:inputs-from-prompt :msg}
                          {:input-area "hi"}))))

(deftest inputs-merged
  (is (= {:base 1 :sel 2 :focus 3 :prompt "hi"}
         (km/build-inputs
           {:inputs                 {:base 1}
            :inputs-from-selection  :sel
            :inputs-from-focus      :focus
            :inputs-from-prompt     :prompt}
           {:selection 2 :focus 3 :input-area "hi"}))))

;; ──────────────────────────────────────────────────────────────────────────
;; dispatch! routing
;; ──────────────────────────────────────────────────────────────────────────

(deftest dispatch-routes-command
  (let [calls (atom [])
        ctx   {:command-dispatcher (fn [n inputs _ctx] (swap! calls conj [n inputs]) :ok)
               :session-state      {:selection :picked}}]
    (km/dispatch! ctx [:command :foo {:inputs-from-selection :item}])
    (is (= [[:foo {:item :picked}]] @calls))))

(deftest dispatch-routes-session
  (let [calls (atom [])
        ctx   {:session-dispatcher (fn [a opts _ctx] (swap! calls conj [a opts]) :ok)}]
    (km/dispatch! ctx [:session :quit])
    (is (= [[:quit {}]] @calls))))

(deftest dispatch-unknown-tag-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (km/dispatch! {} [:bogus :x]))))

(deftest dispatch-command-fallback-on-fallthrough
  (testing "fallback session action fires when the command falls through"
    (let [calls (atom [])
          ctx   {:command-dispatcher (fn [n inputs _ctx]
                                       (swap! calls conj [:command n inputs])
                                       {:keymap/fallthrough? true})
                 :session-dispatcher (fn [a opts _ctx]
                                       (swap! calls conj [:session a opts])
                                       :ok)
                 :session-state      {}}]
      (km/dispatch! ctx [:command :cancel {:inputs {:session-id 1}
                                           :fallback [:session :quit]}])
      (is (= [[:command :cancel {:session-id 1}]
              [:session :quit {}]]
             @calls))))
  (testing "fallback does not fire when the command did work"
    (let [calls (atom [])
          ctx   {:command-dispatcher (fn [n inputs _ctx]
                                       (swap! calls conj [:command n inputs])
                                       {:command-result/events [:e]})
                 :session-dispatcher (fn [a opts _ctx]
                                       (swap! calls conj [:session a opts])
                                       :ok)
                 :session-state      {}}]
      (km/dispatch! ctx [:command :cancel {:fallback [:session :quit]}])
      (is (= [[:command :cancel nil]] @calls))))
  (testing "no fallback opt means the result is returned untouched"
    (let [ctx {:command-dispatcher (fn [_ _ _] {:keymap/fallthrough? true})
               :session-state      {}}]
      (is (= {:keymap/fallthrough? true}
             (km/dispatch! ctx [:command :cancel]))))))
