(ns ai.obney.grain.tui-adapter.frame-test
  "Pure tests for frame/produce-frame — the v0.8 Frame producer.

   The producer is a pure function over (session-snapshot, handler-result)
   so these tests don't spin up a session loop. They feed synthetic
   snapshots/results and assert on the returned Frame map directly."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.tui-adapter.frame :as frame]))

(defn- snapshot-screen
  ([] (snapshot-screen {}))
  ([extra]
   (merge {:query-id       :test/hello
           :inputs         {}
           :tui/buffer     :alt
           :tui/projection :snapshot}
          extra)))

(defn- stream-screen
  ([] (stream-screen {}))
  ([extra]
   (merge {:query-id       :test/feed
           :inputs         {}
           :tui/buffer     :main
           :tui/projection :stream
           :tui/segments   {:items :msgs :key :id :hiccup :tui/hiccup}}
          extra)))

;; ──────────────────────────────────────────────────────────────────────
;; Snapshot path
;; ──────────────────────────────────────────────────────────────────────

(deftest snapshot-hiccup-lands-on-frame
  (let [session {:current-screen (snapshot-screen)}
        result  {:query/result {:n 1}
                 :tui/hiccup   [:text {:text "hi"}]}
        frm     (frame/produce-frame session result)]
    (is (frame/snapshot? frm))
    (is (= [:text {:text "hi"}] (:hiccup frm)))
    (is (nil? (:segments frm)))
    (is (nil? (:regions frm)))
    (is (nil? (:error frm)))))

(deftest snapshot-missing-hiccup-yields-nil-hiccup-not-error
  ;; §6.4: a snapshot handler returning no :tui/hiccup is a warning, not
  ;; an error — the local renderer renders an empty screen and logs.
  (let [session {:current-screen (snapshot-screen)}
        result  {:query/result {}}                 ; no :tui/hiccup
        frm     (frame/produce-frame session result)]
    (is (frame/snapshot? frm))
    (is (nil? (:hiccup frm)))
    (is (nil? (:error frm)))))

(deftest snapshot-with-layout-puts-regions-on-frame
  (let [screen  (snapshot-screen {:tui/layout [:col [:region :a] [:region :b]]})
        session {:current-screen screen}
        result  {:tui/regions {:a [:text {:text "A"}]
                               :b [:text {:text "B"}]}}
        frm     (frame/produce-frame session result)]
    (is (frame/regions? frm))
    (is (= {:a [:text {:text "A"}]
            :b [:text {:text "B"}]}
           (:regions frm)))
    (is (nil? (:hiccup frm)))))

;; ──────────────────────────────────────────────────────────────────────
;; Stream path
;; ──────────────────────────────────────────────────────────────────────

(deftest stream-extracts-segments-from-items-path-keyword
  (let [session {:current-screen (stream-screen)}
        result  {:msgs [{:id 1 :tui/hiccup [:text {:text "a"}]}
                        {:id 2 :tui/hiccup [:text {:text "b"}]}]}
        frm     (frame/produce-frame session result)]
    (is (frame/stream? frm))
    (is (= 2 (count (:segments frm))))
    (is (= 1 (-> frm :segments first :id)))))

(deftest stream-extracts-segments-from-vector-path
  (let [session {:current-screen (stream-screen {:tui/segments
                                                  {:items [:wrap :items]
                                                   :key   :id
                                                   :hiccup :tui/hiccup}})}
        result  {:wrap {:items [{:id 1 :tui/hiccup [:text]}]}}
        frm     (frame/produce-frame session result)]
    (is (= 1 (count (:segments frm))))
    (is (= 1 (-> frm :segments first :id)))))

(deftest stream-empty-segments-still-valid-stream-frame
  (let [session {:current-screen (stream-screen)}
        result  {:msgs []}
        frm     (frame/produce-frame session result)]
    (is (frame/stream? frm))
    (is (= [] (:segments frm)))))

(deftest stream-frame-carries-segments-spec-in-metadata
  ;; Remote-topology clients (and the local stream renderer) need the
  ;; segments-spec to know which path on each segment holds the hiccup.
  (let [session {:current-screen (stream-screen)}
        result  {:msgs [{:id 1 :tui/hiccup [:text]}]}
        frm     (frame/produce-frame session result)]
    (is (= {:items :msgs :key :id :hiccup :tui/hiccup}
           (-> frm :metadata :segments-spec)))))

;; ──────────────────────────────────────────────────────────────────────
;; Error path
;; ──────────────────────────────────────────────────────────────────────

(deftest thrown-query-error-builds-error-frame
  (let [session {:current-screen (snapshot-screen)}
        result  {:query/error "boom"}
        frm     (frame/produce-frame session result)]
    (is (frame/error? frm))
    (is (= "Query error" (-> frm :error :headline)))
    (is (= "boom"        (-> frm :error :message)))
    (is (nil? (:hiccup frm)))
    (is (nil? (:segments frm)))))

(deftest returned-anomaly-builds-error-frame
  (let [session {:current-screen (snapshot-screen)}
        result  {:cognitect.anomalies/category :cognitect.anomalies/fault
                 :cognitect.anomalies/message  "validation failed"}
        frm     (frame/produce-frame session result)]
    (is (frame/error? frm))
    (is (re-find #"fault" (-> frm :error :headline)))
    (is (= "validation failed" (-> frm :error :message)))))

(deftest anomaly-on-stream-screen-still-yields-error-frame
  ;; Error path is independent of projection — a streaming query that
  ;; returns an anomaly should surface as an error frame, not as an
  ;; empty stream.
  (let [session {:current-screen (stream-screen)}
        result  {:cognitect.anomalies/category :cognitect.anomalies/conflict}
        frm     (frame/produce-frame session result)]
    (is (frame/error? frm))
    (is (not (frame/stream? frm)))))

;; ──────────────────────────────────────────────────────────────────────
;; Overlay
;; ──────────────────────────────────────────────────────────────────────

(deftest overlay-flows-through-frame
  (let [session {:current-screen (snapshot-screen)
                 :overlay {:type :toast :content [:text "hi"] :id 1}}
        result  {:tui/hiccup [:text "main"]}
        frm     (frame/produce-frame session result)]
    (is (= :toast (-> frm :overlay :type)))
    (is (= 1      (-> frm :overlay :id)))
    ;; primary content still snapshot
    (is (= [:text "main"] (:hiccup frm)))))

(deftest overlay-coexists-with-error
  (let [session {:current-screen (snapshot-screen)
                 :overlay {:type :toast :content [:text "transient"]}}
        result  {:query/error "boom"}
        frm     (frame/produce-frame session result)]
    (is (frame/error? frm))
    (is (= :toast (-> frm :overlay :type)))))

;; ──────────────────────────────────────────────────────────────────────
;; Metadata
;; ──────────────────────────────────────────────────────────────────────

(deftest metadata-carries-buffer-and-projection
  (let [session {:current-screen (snapshot-screen)}
        frm     (frame/produce-frame session {:tui/hiccup [:text "x"]})]
    (is (= :alt      (-> frm :metadata :buffer)))
    (is (= :snapshot (-> frm :metadata :projection)))))

(deftest screen-block-carries-query-id-and-inputs
  (let [session {:current-screen (snapshot-screen {:inputs {:n 7}})}
        frm     (frame/produce-frame session {:tui/hiccup [:text "x"]})]
    (is (= :test/hello (-> frm :screen :query-id)))
    (is (= {:n 7}      (-> frm :screen :inputs)))))
