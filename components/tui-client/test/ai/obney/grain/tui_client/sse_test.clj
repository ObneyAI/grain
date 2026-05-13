(ns ai.obney.grain.tui-client.sse-test
  "Tests for the SSE accumulator. We don't drive the HTTP path here —
   that's covered in the Phase 6 e2e test against a live server. These
   tests feed line sequences through `parse-lines` and assert on the
   emitted event sequence."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-client.sse :as sse]))

(deftest emits-named-event-on-blank-line
  (is (= [{:name "tui-frame" :data "{:hiccup [:text \"x\"]}"}]
         (sse/parse-lines
           ["event: tui-frame"
            "data: {:hiccup [:text \"x\"]}"
            ""]))))

(deftest emits-message-event-when-name-omitted
  (is (= [{:name "message" :data "hello"}]
         (sse/parse-lines ["data: hello" ""]))))

(deftest ignores-comment-lines
  (is (= [{:name "ping" :data "ok"}]
         (sse/parse-lines [": heartbeat"
                           "event: ping"
                           "data: ok"
                           ""]))))

(deftest accumulates-multiple-data-lines
  (is (= [{:name "tui-frame" :data "line1\nline2"}]
         (sse/parse-lines ["event: tui-frame"
                           "data: line1"
                           "data: line2"
                           ""]))))

(deftest emits-multiple-events
  (is (= [{:name "a" :data "1"}
          {:name "b" :data "2"}]
         (sse/parse-lines ["event: a" "data: 1" ""
                           "event: b" "data: 2" ""]))))

(deftest ignores-id-and-retry-lines
  (is (= [{:name "x" :data "y"}]
         (sse/parse-lines ["id: 42"
                           "retry: 1000"
                           "event: x"
                           "data: y"
                           ""]))))

(deftest blank-line-with-no-buffer-emits-nothing
  (is (= [] (sse/parse-lines ["" "" ""]))))

(deftest strips-one-leading-space-from-data
  ;; SSE spec: the parser should remove the first space character after
  ;; `data:` if present. So `data:hi` and `data: hi` both yield "hi".
  (is (= [{:name "message" :data "hi"}]
         (sse/parse-lines ["data:hi" ""])))
  (is (= [{:name "message" :data "hi"}]
         (sse/parse-lines ["data: hi" ""])))
  (is (= [{:name "message" :data " hi"}]   ; two spaces → one leading kept
         (sse/parse-lines ["data:  hi" ""]))))
