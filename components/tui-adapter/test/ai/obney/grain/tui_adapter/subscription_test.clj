(ns ai.obney.grain.tui-adapter.subscription-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.core.async :as async]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.tui-adapter.subscription :as sub]))

;; Each test starts with a clean read-model registry.

(defn- snapshot-then-restore
  "Snapshot the read-model registry, run the test body, restore."
  [t]
  (let [saved @rmp/read-model-registry*]
    (try
      (reset! rmp/read-model-registry* {})
      (t)
      (finally
        (reset! rmp/read-model-registry* saved)))))

(use-fixtures :each snapshot-then-restore)

;; ──────────────────────────────────────────────────────────────────────────
;; resolve-event-types-from-read-models
;; ──────────────────────────────────────────────────────────────────────────

(deftest resolve-empty-registry
  (is (= #{} (sub/resolve-event-types-from-read-models {:foo/rm 1}))))

(deftest resolve-single-read-model
  (rmp/register-read-model! :foo/rm (fn [s _] s)
                            {:events #{:foo/a :foo/b}})
  (is (= #{:foo/a :foo/b}
         (sub/resolve-event-types-from-read-models {:foo/rm 1}))))

(deftest resolve-multiple-read-models-unions
  (rmp/register-read-model! :foo/rm (fn [s _] s) {:events #{:foo/a}})
  (rmp/register-read-model! :bar/rm (fn [s _] s) {:events #{:bar/x :foo/a}})
  (is (= #{:foo/a :bar/x}
         (sub/resolve-event-types-from-read-models {:foo/rm 1 :bar/rm 1}))))

(deftest resolve-missing-read-model-skipped
  (rmp/register-read-model! :foo/rm (fn [s _] s) {:events #{:foo/a}})
  (is (= #{:foo/a}
         (sub/resolve-event-types-from-read-models {:foo/rm 1 :missing/rm 1}))))

;; ──────────────────────────────────────────────────────────────────────────
;; resolve-event-tags
;; ──────────────────────────────────────────────────────────────────────────

(deftest event-tags-bare-keyword-resolves-from-query
  (let [pred (sub/resolve-event-tags {:conv-id :conversation-id}
                                     {:query {:conversation-id 42}})]
    (is (true?  (pred {:event/tags #{[:conv-id 42]}})))
    (is (false? (pred {:event/tags #{[:conv-id 99]}})))))

(deftest event-tags-vector-uses-full-path
  (let [pred (sub/resolve-event-tags {:tenant [:auth-claims :tenant-id]}
                                     {:auth-claims {:tenant-id "t1"}})]
    (is (true? (pred {:event/tags #{[:tenant "t1"]}})))))

(deftest event-tags-subset-semantics
  (let [pred (sub/resolve-event-tags {:k :id} {:query {:id 1}})]
    ;; Event with extra unrelated tags still passes
    (is (true? (pred {:event/tags #{[:k 1] [:other "data"]}})))
    ;; Event missing the required tag fails
    (is (false? (pred {:event/tags #{[:other "data"]}})))))

(deftest event-tags-multiple-keys-all-required
  (let [pred (sub/resolve-event-tags {:a :id-a :b :id-b}
                                     {:query {:id-a 1 :id-b 2}})]
    (is (true?  (pred {:event/tags #{[:a 1] [:b 2]}})))
    (is (false? (pred {:event/tags #{[:a 1]}})))))

;; ──────────────────────────────────────────────────────────────────────────
;; subscribe-to-events
;; ──────────────────────────────────────────────────────────────────────────

(defrecord MockPubsub [calls-atom]
  Object
  (toString [_] "MockPubsub"))

(defn- mock-pubsub-impl
  "Pubsub impl that records (sub) calls without doing real distribution."
  []
  (let [calls (atom [])]
    {:calls calls
     :pubsub (reify
               clojure.lang.ILookup
               (valAt [_ k] (when (= k ::calls) calls)))}))

;; The real `pubsub/sub` calls into the protocol, which our reify mock
;; doesn't implement. Instead, monkey-patch via with-redefs.

(deftest subscribe-creates-sliding-buffer-channel
  (let [calls (atom [])]
    (with-redefs [ai.obney.grain.pubsub.interface/sub
                  (fn [_ args] (swap! calls conj args))]
      (let [ch (sub/subscribe-to-events :mock #{:foo/a :foo/b} nil)]
        (is (some? ch))
        (is (= 2 (count @calls)))
        (is (= #{:foo/a :foo/b} (set (map :topic @calls))))
        (is (every? (fn [c] (= ch (:sub-chan c))) @calls))))))

(deftest subscribe-with-no-event-types
  (with-redefs [ai.obney.grain.pubsub.interface/sub (fn [_ _] :noop)]
    (let [ch (sub/subscribe-to-events :mock #{} nil)]
      (is (some? ch))
      ;; Channel exists but no subs were created (no event types).
      )))

(deftest subscribe-with-filter-applies-transducer
  ;; Use a real channel without going through pubsub/sub — test the filter
  ;; transducer path directly by constructing one and feeding events.
  (with-redefs [ai.obney.grain.pubsub.interface/sub (fn [_ _] nil)]
    (let [ch (sub/subscribe-to-events :mock #{:foo/a}
                                       (fn [ev] (= 1 (:n ev))))]
      (async/>!! ch {:n 1})
      (async/>!! ch {:n 2})
      (async/>!! ch {:n 1})
      (Thread/sleep 50)
      (is (= {:n 1} (async/<!! ch)))
      (is (= {:n 1} (async/<!! ch)))
      (async/close! ch))))

;; ──────────────────────────────────────────────────────────────────────────
;; drain-channel
;; ──────────────────────────────────────────────────────────────────────────

(deftest drain-empty-returns-zero
  (let [ch (async/chan 16)]
    (is (= 0 (sub/drain-channel ch)))
    (async/close! ch)))

(deftest drain-non-empty-returns-count
  (let [ch (async/chan 16)]
    (async/>!! ch :a)
    (async/>!! ch :b)
    (async/>!! ch :c)
    (is (= 3 (sub/drain-channel ch)))
    (is (= 0 (sub/drain-channel ch)))
    (async/close! ch)))

;; ──────────────────────────────────────────────────────────────────────────
;; subscribe-screen — convenience
;; ──────────────────────────────────────────────────────────────────────────

(deftest subscribe-screen-no-read-models-returns-nil
  (let [ch (sub/subscribe-screen :mock {:tui/buffer :alt} {})]
    (is (nil? ch))))

(deftest subscribe-screen-with-read-models
  (rmp/register-read-model! :foo/rm (fn [s _] s) {:events #{:foo/a}})
  (let [calls (atom [])]
    (with-redefs [ai.obney.grain.pubsub.interface/sub
                  (fn [_ args] (swap! calls conj args))]
      (let [ch (sub/subscribe-screen :mock
                                      {:grain/read-models {:foo/rm 1}}
                                      {})]
        (is (some? ch))
        (is (= 1 (count @calls)))
        (is (= :foo/a (:topic (first @calls))))))))

(deftest subscribe-screen-with-event-tags-applies-filter
  (rmp/register-read-model! :foo/rm (fn [s _] s) {:events #{:foo/a}})
  (with-redefs [ai.obney.grain.pubsub.interface/sub (fn [_ _] nil)]
    (let [ch (sub/subscribe-screen :mock
                                    {:grain/read-models {:foo/rm 1}
                                     :tui/event-tags    {:cid :conversation-id}}
                                    {:query {:conversation-id 42}})]
      ;; Push events; only the tagged one should pass.
      (async/>!! ch {:event/tags #{[:cid 42]}})
      (async/>!! ch {:event/tags #{[:cid 99]}})
      (async/>!! ch {:event/tags #{[:cid 42] [:other 1]}})
      (Thread/sleep 50)
      (is (= #{[:cid 42]}      (:event/tags (async/<!! ch))))
      (is (= #{[:cid 42] [:other 1]} (:event/tags (async/<!! ch))))
      (async/close! ch))))
