(ns ai.obney.grain.tui-adapter.input-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.input :as input]))

(defn- one [bytes]
  (let [[evs _] (input/feed (input/make-parser) bytes)]
    evs))

(def ESC 0x1B)

;; ──────────────────────────────────────────────────────────────────────────
;; Plain ASCII
;; ──────────────────────────────────────────────────────────────────────────

(deftest single-printable-char
  (is (= [{:type :key :key "a"}] (one [(int \a)]))))

(deftest multiple-printables-buffer
  (is (= [{:type :key :key "a"} {:type :key :key "b"}]
         (one [(int \a) (int \b)]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Control keys
;; ──────────────────────────────────────────────────────────────────────────

(deftest enter-key
  (is (= [{:type :key :key "<enter>"}] (one [0x0D]))))

(deftest tab-key
  (is (= [{:type :key :key "<tab>"}] (one [0x09]))))

(deftest shift-tab-key
  (is (= [{:type :key :key "<backtab>"}] (one [ESC 0x5B 0x5A]))))

(deftest backspace-del
  (is (= [{:type :key :key "<backspace>"}] (one [0x7F]))))

(deftest ctrl-letter
  (is (= [{:type :key :key "C-a"}] (one [0x01])))
  (is (= [{:type :key :key "C-z"}] (one [0x1A]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Arrow keys (CSI)
;; ──────────────────────────────────────────────────────────────────────────

(deftest arrow-up
  (is (= [{:type :key :key "<up>"}] (one [ESC 0x5B 0x41]))))

(deftest arrow-down
  (is (= [{:type :key :key "<down>"}] (one [ESC 0x5B 0x42]))))

(deftest arrow-right
  (is (= [{:type :key :key "<right>"}] (one [ESC 0x5B 0x43]))))

(deftest arrow-left
  (is (= [{:type :key :key "<left>"}] (one [ESC 0x5B 0x44]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Function keys via SS3 (ESC O X)
;; ──────────────────────────────────────────────────────────────────────────

(deftest f1-via-ss3
  (is (= [{:type :key :key "<f1>"}] (one [ESC 0x4F 0x50]))))

(deftest f4-via-ss3
  (is (= [{:type :key :key "<f4>"}] (one [ESC 0x4F 0x53]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Meta/Alt
;; ──────────────────────────────────────────────────────────────────────────

(deftest meta-letter
  (is (= [{:type :key :key "M-x"}] (one [ESC (int \x)]))))

;; ──────────────────────────────────────────────────────────────────────────
;; UTF-8 multi-byte
;; ──────────────────────────────────────────────────────────────────────────

(deftest utf8-two-byte
  ;; é = U+00E9 = C3 A9
  (is (= [{:type :key :key "é"}] (one [0xC3 0xA9]))))

(deftest utf8-three-byte
  ;; ▶ = U+25B6 = E2 96 B6
  (is (= [{:type :key :key "▶"}] (one [0xE2 0x96 0xB6]))))

;; ──────────────────────────────────────────────────────────────────────────
;; Bracketed paste
;; ──────────────────────────────────────────────────────────────────────────

(deftest bracketed-paste
  ;; ESC[200~ "hi" ESC[201~
  (let [start [ESC 0x5B 0x32 0x30 0x30 0x7E]
        body  [(int \h) (int \i)]
        end   [ESC 0x5B 0x32 0x30 0x31 0x7E]
        evs   (one (concat start body end))]
    (is (= [{:type :paste :string "hi"}] evs))))

;; ──────────────────────────────────────────────────────────────────────────
;; Bytes split across reads
;; ──────────────────────────────────────────────────────────────────────────

(deftest split-arrow-across-feeds
  (let [p0 (input/make-parser)
        [e1 p1] (input/feed p0 [ESC])
        [e2 p2] (input/feed p1 [0x5B])
        [e3 _]  (input/feed p2 [0x41])]
    (is (= [] e1))
    (is (= [] e2))
    (is (= [{:type :key :key "<up>"}] e3))))

;; ──────────────────────────────────────────────────────────────────────────
;; Unrecognised escape — no crash
;; ──────────────────────────────────────────────────────────────────────────

(deftest unrecognised-csi-dropped
  (let [evs (one [ESC 0x5B 0x51])]
    (is (= [] evs))))

;; ──────────────────────────────────────────────────────────────────────────
;; Synthesized resize / focus
;; ──────────────────────────────────────────────────────────────────────────

(deftest resize-event-shape
  (is (= {:type :resize :size [80 24]} (input/resize-event 80 24))))

(deftest focus-event-shape
  (is (= {:type :focus :in? true}  (input/focus-event true)))
  (is (= {:type :focus :in? false} (input/focus-event false))))

;; ──────────────────────────────────────────────────────────────────────────
;; Lone-ESC timeout flush
;; ──────────────────────────────────────────────────────────────────────────

(deftest lone-esc-flush
  (testing "a bare ESC is held pending, then flushed as <esc>"
    (let [[evs parser] (input/feed (input/make-parser) [0x1B])]
      (is (= [] evs))
      (is (input/pending? parser))
      (let [[evs parser] (input/flush-lone-esc parser)]
        (is (= [{:type :key :key "<esc>"}] evs))
        (is (not (input/pending? parser))))))
  (testing "a partial CSI sequence is NOT flushed"
    (let [[evs parser] (input/feed (input/make-parser) [0x1B 0x5B])]
      (is (= [] evs))
      (let [[evs parser'] (input/flush-lone-esc parser)]
        (is (= [] evs))
        (is (= parser parser')
            "incomplete escape sequences keep waiting for bytes"))))
  (testing "flush on an empty parser is a no-op"
    (let [[evs _] (input/flush-lone-esc (input/make-parser))]
      (is (= [] evs))))
  (testing "ESC followed by a key still decodes as Meta, not <esc>+key"
    (let [[evs _] (input/feed (input/make-parser) [0x1B 0x78])]
      (is (= [{:type :key :key "M-x"}] evs)))))
