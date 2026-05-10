(ns ai.obney.grain.tui-adapter.loop-test
  "Loop-driven tests — spawn the actual `run-loop!` thread and drive
   it via `input-ch` / `sub-chan` like the real transport does.

   The unit and integration tests in this brick call dispatch and
   render functions directly, bypassing the live loop. That left three
   classes of bug uncovered:

   1. Lossy input draining — the loop dispatched the first event and
      `(drain-channel input-ch)`'d the rest. Direct-dispatch tests
      never noticed because they push one event at a time.

   2. Anomaly returns vs. thrown exceptions — `query-failure-...` in
      session_test only tests the THROWN path. A handler that RETURNS
      a Cognitect anomaly took a different code path through
      compute-screen-grid and rendered with nil result.

   3. Input backpressure — `chan 32` + blocking `>!!` from JLine could
      wedge the input pump. No test ever pushed more than a handful
      of events.

   Tests in this namespace exercise the live loop end-to-end so those
   bugs would surface as test failures."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.builtins]
            [ai.obney.grain.tui-adapter.session :as session]))

;; ──────────────────────────────────────────────────────────────────────────
;; Harness — spawn run-loop! on a thread, return a stop fn.
;; ──────────────────────────────────────────────────────────────────────────

(defn- start-loop-thread!
  "Spawn `session/run-loop!` on a daemon thread. Returns the thread."
  [session]
  (let [t (Thread. ^Runnable #(session/run-loop! session)
                   "tui-loop-test")]
    (.setDaemon t true)
    (.start t)
    t))

(defn- await-condition!
  "Poll until `(pred)` returns truthy or `timeout-ms` elapses. Returns
   true when the predicate fired, false on timeout."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- stop-loop! [session ^Thread t]
  (swap! session assoc :running? false)
  (async/close! (:input-ch @session))
  (.join t 1000))

;; ──────────────────────────────────────────────────────────────────────────
;; Test 1 — every queued input event is dispatched (lossy-drain regression)
;; ──────────────────────────────────────────────────────────────────────────

(deftest dispatches-every-queued-input-event
  (testing "Holding down a key under autorepeat dispatches each press,
            not just the first per loop iteration."
    (let [n-events  100
          calls     (atom 0)
          screen    {:query-id   :test/loop
                     :inputs     {}
                     :tui/render (fn [_] [:text {:text "ok"}])
                     :tui/keymap {"a" [:command :test/bump]}}
          session   (session/make-session
                      {:tenant-id          (random-uuid)
                       :viewport           {:width 5 :height 1}
                       :on-output          (fn [_])
                       :default-screen     screen
                       :process-query-fn   (fn [_] {:query/result {}})
                       :process-command-fn (fn [_]
                                             (swap! calls inc)
                                             {:command/result :ok})
                       :debounce-ms        0})
          loop-t    (start-loop-thread! session)]
      (try
        ;; Push N events as fast as possible — emulates autorepeat
        ;; flooding the buffer between loop iterations.
        (dotimes [_ n-events]
          (async/>!! (:input-ch @session) {:type :key :key "a"}))
        ;; Wait for the loop to drain and dispatch all of them.
        (let [reached? (await-condition! #(>= @calls n-events) 5000)]
          (is reached?
              (str "expected " n-events " dispatches, got " @calls
                   " — loop is dropping queued input events"))
          (is (= n-events @calls)))
        (finally
          (stop-loop! session loop-t))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Test 2 — anomaly RETURN paints an error frame (not blank)
;; ──────────────────────────────────────────────────────────────────────────

(deftest returned-anomaly-renders-error-frame
  (testing "A query handler that returns a Cognitect anomaly map (without
            throwing) must render the failure as an error frame, not as
            a render-fn invocation against a nil result."
    (let [out     (atom [])
          screen  {:query-id   :test/anom
                   :inputs     {}
                   :tui/render (fn [_]
                                 ;; If this fn is reached the test fails
                                 ;; — anomaly results must not call render.
                                 [:text {:text "RENDER-CALLED-WHEN-IT-SHOULD-NOT-BE"}])}
          session (session/make-session
                    {:tenant-id        (random-uuid)
                     :viewport         {:width 60 :height 5}
                     :on-output        (fn [s] (swap! out conj s))
                     :default-screen   screen
                     :process-query-fn (fn [_]
                                         {:cognitect.anomalies/category :cognitect.anomalies/fault
                                          :cognitect.anomalies/message  "deliberate test failure"})
                     :debounce-ms      0})]
      (try
        (session/render-frame! session)
        (let [combined (apply str @out)]
          (is (str/includes? combined "Query failed")
              "expected the error frame headline to surface in the output bytes")
          (is (str/includes? combined "deliberate test failure")
              "expected the anomaly's message to surface in the output bytes")
          (is (not (str/includes? combined "RENDER-CALLED-WHEN-IT-SHOULD-NOT-BE"))
              "render-fn must NOT be invoked when the query returned an anomaly"))
        (finally
          (session/stop! session))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Test 3 — loop survives an input burst without wedging
;; ──────────────────────────────────────────────────────────────────────────

(deftest loop-survives-input-burst
  (testing "Pushing far more events than the input buffer holds does not
            wedge the loop or the input pump. Sliding-buffer semantics
            mean some events are dropped, but the loop must keep running
            and SOME dispatches must land."
    (let [calls   (atom 0)
          screen  {:query-id   :test/burst
                   :inputs     {}
                   :tui/render (fn [_] [:text {:text "ok"}])
                   :tui/keymap {"a" [:command :test/bump]}}
          session (session/make-session
                    {:tenant-id          (random-uuid)
                     :viewport           {:width 5 :height 1}
                     :on-output          (fn [_])
                     :default-screen     screen
                     :process-query-fn   (fn [_] {:query/result {}})
                     :process-command-fn (fn [_]
                                           (swap! calls inc)
                                           {:command/result :ok})
                     :debounce-ms        0})
          loop-t  (start-loop-thread! session)]
      (try
        ;; Push 5000 events — well past the 1024 sliding buffer.
        ;; A blocking-chan implementation here would deadlock on the
        ;; >!! puts once the loop fell behind; sliding never blocks.
        (dotimes [_ 5000]
          (async/>!! (:input-ch @session) {:type :key :key "a"}))
        (await-condition! #(pos? @calls) 5000)
        (is (pos? @calls)
            "loop must dispatch at least some events from the burst")
        (is (true? (:running? @session))
            "loop must still be running after the burst")
        ;; And we can still send a final event that gets processed.
        (let [before @calls]
          (async/>!! (:input-ch @session) {:type :key :key "a"})
          (await-condition! #(> @calls before) 2000)
          (is (> @calls before)
              "loop must remain responsive after a burst"))
        (finally
          (stop-loop! session loop-t))))))

;; ──────────────────────────────────────────────────────────────────────────
;; Test 4 — quit during a burst (the user-facing failure mode)
;; ──────────────────────────────────────────────────────────────────────────

(deftest quit-event-survives-input-burst
  (testing "After a flood of input events, a [:session :quit] event
            mixed in must still be observed by the loop. This is the
            exact failure the user reported: holding * past 200 events
            then pressing q with the lossy-drain bug would silently
            discard the q press along with all the buffered *s."
    (let [calls   (atom 0)
          screen  {:query-id   :test/quit-burst
                   :inputs     {}
                   :tui/render (fn [_] [:text {:text "ok"}])
                   :tui/keymap {"a" [:command :test/bump]
                                "q" [:session :quit]}}
          session (session/make-session
                    {:tenant-id          (random-uuid)
                     :viewport           {:width 5 :height 1}
                     :on-output          (fn [_])
                     :default-screen     screen
                     :process-query-fn   (fn [_] {:query/result {}})
                     :process-command-fn (fn [_]
                                           (swap! calls inc)
                                           {:command/result :ok})
                     :debounce-ms        0})
          loop-t  (start-loop-thread! session)]
      (try
        (dotimes [_ 200]
          (async/>!! (:input-ch @session) {:type :key :key "a"}))
        (async/>!! (:input-ch @session) {:type :key :key "q"})
        (let [quit? (await-condition! #(false? (:running? @session)) 5000)]
          (is quit?
              "the q keystroke after 200 a's must flip :running? false
               — if the loop were dropping queued input the user could
               not quit"))
        (finally
          (stop-loop! session loop-t))))))
