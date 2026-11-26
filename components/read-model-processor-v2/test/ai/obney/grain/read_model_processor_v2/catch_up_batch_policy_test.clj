(ns ai.obney.grain.read-model-processor-v2.catch-up-batch-policy-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]))

(defn- recorded-batches
  "Run f while recording the event count of every applied catch-up batch."
  [f]
  (let [apply-var #'core/keyed-apply-batch! apply-batch @apply-var calls (atom [])]
    ;; The 7-argument arity delegates to the 8-argument one through the var,
    ;; so only the 8-argument call is one applied batch.
    (with-redefs-fn {apply-var (fn [& args]
                                 (when (= 8 (count args)) (swap! calls conj (count (nth args 6))))
                                 (apply apply-batch args))}
      (fn [] {:value (f) :batches @calls}))))

(defn- deltas [n] (mapv (fn [i] {:owner (str (mod i 11)) :delta (- (mod i 13) 6)}) (range n)))

(defn- expected-totals [events]
  (into {} (map (fn [[id evs]] [id (fixture/keyed-total (reduce fixture/keyed-history-step {} evs))]))
        (group-by :owner events)))

(deftest cold-catch-up-starts-at-cold-events-and-doubles
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step
                                           {:result fixture/keyed-total :indexes {:total :total}})
            events (deltas 5000)]
        (fixture/keyed-append! events)
        (let [{:keys [value batches]} (recorded-batches #(fixture/keyed-results ctx model))]
          (is (= (expected-totals events) value))
          ;; 1024 cold, then doubling to the 4096 ceiling; the tail is partial.
          (is (= [1024 2048 1928] batches)))
        (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through (rmp/progress ctx model))))))))

(deftest warm-catch-up-starts-small-and-grows
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            first-events (deltas 3)
            near-head (deltas 100)
            far-behind (deltas 9000)]
        (fixture/keyed-append! first-events)
        (rmp/progress ctx model)
        (fixture/keyed-append! near-head)
        (is (= [100] (:batches (recorded-batches #(rmp/value ctx model "0"))))
            "A near-head catch-up is one batch, exactly as before")
        (fixture/keyed-append! far-behind)
        (let [{:keys [batches]} (recorded-batches #(rmp/value ctx model "0"))]
          (is (= [128 256 512 1024 2048 4096 936] batches)))
        (is (= (expected-totals (concat first-events near-head far-behind))
               (fixture/keyed-results ctx model)))))))

(deftest byte-bound-caps-batch-growth
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :read-model/catch-up {:max-bytes 1})
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            events (deltas 1000)]
        (rmp/progress ctx model)
        (fixture/keyed-append! [{:owner "0" :delta 1}])
        (rmp/progress ctx model)
        (fixture/keyed-append! events)
        (let [{:keys [batches]} (recorded-batches #(rmp/value ctx model "0"))]
          (is (= (concat (repeat 7 128) [104]) batches)
              "Observed bytes per event keep every batch at the :min-events floor"))
        (is (= (expected-totals (cons {:owner "0" :delta 1} events)) (fixture/keyed-results ctx model)))))))

(deftest explicit-batch-size-stays-fixed-and-policy-is-validated
  (fixture/keyed-fixture
    (fn []
      (let [model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            events (deltas 700)]
        (fixture/keyed-append! events)
        (let [{:keys [batches]} (recorded-batches
                                  #(rmp/value (assoc fixture/*keyed-ctx* :read-model/batch-size 200) model "0"))]
          (is (= [200 200 200 100] batches)))
        (doseq [bad [{:min-events 0} {:max-events 64} {:cold-events 9000} {:max-bytes 0} {:min-events 1.5}]]
          (let [other (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catch-up policy"
                                  (rmp/value (assoc fixture/*keyed-ctx* :read-model/catch-up bad) other "0"))
                (pr-str bad))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catch-up policy must be a map"
                              (rmp/value (assoc fixture/*keyed-ctx* :read-model/catch-up 5) model "0")))
        (let [{:keys [batches]} (recorded-batches
                                  #(let [other (fixture/keyed-register! fixture/keyed-history-step
                                                                        {:result fixture/keyed-total})]
                                     (rmp/value (assoc fixture/*keyed-ctx* :read-model/catch-up
                                                       {:min-events 50 :cold-events 50 :max-events 100 :max-bytes nil})
                                                other "0")))]
          (is (= [50 100 100 100 100 100 100 50] batches)))))))

(deftest results-are-identical-across-policies
  (fixture/keyed-fixture
    (fn []
      (let [events (deltas 3000)]
        (fixture/keyed-append! events)
        (let [results (for [ctx-overrides [{:read-model/batch-size 1}
                                           {:read-model/batch-size 128}
                                           {}
                                           {:read-model/catch-up {:min-events 7 :cold-events 7 :max-events 3000}}
                                           {:read-model/catch-up {:max-bytes 4096}}]]
                        (let [model (fixture/keyed-register! fixture/keyed-history-step
                                                             {:result fixture/keyed-total
                                                              :indexes {:total :total}})
                              ctx (merge fixture/*keyed-ctx* ctx-overrides)]
                          {:values (fixture/keyed-results ctx model)
                           :page (mapv :key (:entries (rmp/page ctx model {:index :total :direction :desc :limit 11})))
                           :state (rmp/state ctx model "3")}))]
          (is (= (expected-totals events) (:values (first results))))
          (is (apply = results)))))))

(deftest next-batch-limit-grows-holds-and-halves-by-written-bytes
  (let [next-limit #'core/keyed-next-batch-limit
        policy {:min-events 128 :max-events 8192 :max-bytes 1000 :max-map-fill 0.5}
        roomy {:map-bytes 1000000 :high-water-bytes 10000}]
    (is (= 2048 (next-limit policy 1024 {:events 1024 :bytes 1000} roomy)) "within budget: double")
    (is (= 8192 (next-limit policy 8192 {:events 8192 :bytes 10} roomy)) "capped at :max-events")
    (is (= 1024 (next-limit policy 1024 {:events 1024 :bytes 4000} roomy)) "over budget: hold")
    (is (= 512 (next-limit policy 1024 {:events 1024 :bytes 4001} roomy)) "over four times: halve")
    (is (= 128 (next-limit policy 200 {:events 200 :bytes 99999} roomy)) "never below :min-events")
    (is (= 2048 (next-limit policy 1024 nil nil)) "no feedback, no space report: double")
    (is (= 2048 (next-limit (assoc policy :max-bytes nil) 1024 {:bytes 99999} roomy)) "no byte bound")
    (testing "store space"
      (is (= 128 (next-limit policy 1024 {:bytes 10} {:map-bytes 1000000 :high-water-bytes 500001}))
          "file past :max-map-fill of the map: back to :min-events")
      (is (= 128 (next-limit policy 1024 {:bytes 1000} {:map-bytes 1000000 :high-water-bytes 996001}))
          "tail under four times the last batch's bytes")
      (is (= 2048 (next-limit (assoc policy :max-map-fill nil) 1024 {:bytes 1000}
                              {:map-bytes 1000000 :high-water-bytes 999999}))
          "nil :max-map-fill ignores space"))))

(defn- map-full-exception []
  (let [c (.getDeclaredConstructor org.lmdbjava.Env$MapFullException (into-array Class []))]
    (.setAccessible c true)
    (.newInstance c (object-array 0))))

(deftest map-full-batch-is-retried-in-halves-and-later-batches-stay-small
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step
                                           {:result fixture/keyed-total :indexes {:total :total}})
            events (deltas 5000)
            apply-var #'core/keyed-apply-batch! apply-batch @apply-var attempts (atom [])]
        (fixture/keyed-append! events)
        ;; A store whose map cannot take a transaction of more than 300 events.
        (with-redefs-fn {apply-var (fn [& args]
                                     (when (= 8 (count args))
                                       (swap! attempts conj (count (nth args 6)))
                                       (when (> (count (nth args 6)) 300) (throw (map-full-exception))))
                                     (apply apply-batch args))}
          #(is (= (expected-totals events) (fixture/keyed-results ctx model))))
        (is (= [1024 512 256 256 512 256 256] (take 7 @attempts))
            "1024 fails, halves until parts fit, then the next batch does not exceed the size that fit")
        (is (every? #(<= % 256) (drop 3 (remove #{512 1024} @attempts))))
        (is (= 5000 (reduce + (filter #(<= % 300) @attempts))) "every event applied exactly once")
        (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through (rmp/progress ctx model))))
        (let [m2 (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})
              c2 (assoc ctx :read-model/batch-size 128)]
          (is (= (mapv :key (:entries (rmp/page ctx model {:index :total :direction :desc :limit 11})))
                 (mapv :key (:entries (rmp/page c2 m2 {:index :total :direction :desc :limit 11}))))))))))

(deftest map-full-at-min-events-and-fixed-size-are-not-retried
  (fixture/keyed-fixture
    (fn []
      (let [model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            apply-var #'core/keyed-apply-batch! apply-batch @apply-var attempts (atom [])]
        (fixture/keyed-append! (deltas 300))
        (with-redefs-fn {apply-var (fn [& args]
                                     (when (= 8 (count args))
                                       (swap! attempts conj (count (nth args 6)))
                                       (throw (map-full-exception)))
                                     (apply apply-batch args))}
          (fn []
            (is (thrown? org.lmdbjava.Env$MapFullException
                         (rmp/value (assoc fixture/*keyed-ctx* :read-model/batch-size 128) model "0")))
            (is (= [128] @attempts) "an explicit batch size keeps today's behaviour: no retry")
            (reset! attempts [])
            (is (thrown? org.lmdbjava.Env$MapFullException (rmp/value fixture/*keyed-ctx* model "0")))
            (is (= [300 150 75] (take 3 @attempts)))
            (is (every? #(>= % 64) @attempts) "halving stops at :min-events")))))))

(deftest store-reports-space-in-transactions
  (fixture/keyed-fixture
    (fn []
      (let [space (kv/transact (:cache fixture/*keyed-ctx*) :read #((:space %)))]
        (is (= (* 64 1024 1024) (:map-bytes space)))
        (is (<= 0 (:high-water-bytes space) (:map-bytes space)))))))

(deftest input-bytes-cut-batches-of-large-events
  (fixture/keyed-fixture
    (fn []
      (let [blob (apply str (repeat 10000 "x"))
            events (mapv (fn [i] {:owner (str (mod i 7)) :delta 1 :blob blob}) (range 600))
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            ;; ~20 KB estimated per event; 1 MiB budget -> about 52 events per batch
            ctx (assoc fixture/*keyed-ctx* :read-model/catch-up {:max-input-bytes (* 1024 1024)})]
        (fixture/keyed-append! events)
        (let [{:keys [value batches]} (recorded-batches #(fixture/keyed-results ctx model))]
          (is (= (expected-totals events) value))
          (is (every? #(<= % 53) batches) (pr-str batches))
          (is (= 600 (reduce + batches))))
        (testing "an explicit batch size is not cut by input bytes"
          (let [m2 (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
            (is (= [200 200 200] (:batches (recorded-batches
                                            #(rmp/value (assoc ctx :read-model/batch-size 200) m2 "0")))))))))))

(deftest catch-up-policy-rejects-unknown-keys-and-is-validated-with-batch-size
  (fixture/keyed-fixture
    (fn []
      (let [model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
        (fixture/keyed-append! (deltas 10))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown keys"
                              (rmp/value (assoc fixture/*keyed-ctx* :read-model/catch-up {:max-event 1}) model "0")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catch-up policy requires"
                              (rmp/value (assoc fixture/*keyed-ctx* :read-model/batch-size 64
                                                :read-model/catch-up {:min-events -5}) model "0")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"catch-up policy requires"
                              (rmp/value (assoc fixture/*keyed-ctx* :read-model/catch-up {:max-map-fill 1.5}) model "0")))
        (is (= {:total (reduce + (map :delta (filter #(= "0" (:owner %)) (deltas 10))))}
               (rmp/value (assoc fixture/*keyed-ctx* :read-model/batch-size 64
                                 :read-model/catch-up {:min-events 8 :cold-events 8 :max-events 8}) model "0")))))))

;; Adopted from the OPT-2 audit (SCRATCH/opt/OPT-2/audit/audit_opt2_test.clj): the default policy's
;; cold failure paths.
(defn- failing-read-after [k]
  (let [read-events es/read]
    (fn [& args]
      (let [events (into [] (apply read-events args))]
        (concat (take k events) (lazy-seq (throw (ex-info "source failed" {}))))))))

(deftest source-failure-default-policy-cold-129
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
        (rmp/progress ctx model)
        (fixture/keyed-append! (vec (repeat 129 {:owner "a" :delta 1})))
        (with-redefs [es/read (failing-read-after 129)]
          (is (thrown-with-msg? Exception #"source failed" (rmp/value ctx model "a"))))
        (let [p (with-redefs-fn {#'core/keyed-catch-up! (fn [& _] nil)} #(rmp/progress ctx model))]
          (is (nil? (:checked-through p))))
        (let [{:keys [value batches]} (recorded-batches #(rmp/value ctx model "a"))]
          (is (= {:total 129} value))
          (is (= [129] batches)))))))

(deftest source-failure-mid-catch-up-with-large-batches
  ;; INT (OPT-2 x OPT-4): a 5,000-event cold backlog would be deferred into a
  ;; staging namespace by default; this test pins the non-deferred cold path
  ;; it was written for. The deferred variant follows.
  (fixture/keyed-fixture
    (fn []
      (let [ctx (assoc fixture/*keyed-ctx* :read-model/deferred-build-threshold 0)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})
            events (deltas 5000)]
        (fixture/keyed-append! events)
        (with-redefs [es/read (failing-read-after 4000)]
          (let [{:keys [value batches]} (recorded-batches #(try (rmp/value ctx model "0")
                                                                (catch Throwable t {:thrown (ex-message t)})))]
            (is (:thrown value))
            (is (= [1024 2048] batches))))
        (let [p (with-redefs-fn {#'core/keyed-catch-up! (fn [& _] nil)} #(rmp/progress ctx model))]
          (is (nil? (:checked-through p)) "no checkpoint published after a source failure")
          (is (some? (:watermark p))))
        (let [{:keys [value batches]} (recorded-batches #(fixture/keyed-results ctx model))]
          (is (= (expected-totals events) value))
          (is (= 1928 (reduce + batches))))
        (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through (rmp/progress ctx model))))
        (let [m2 (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})
              c2 (assoc ctx :read-model/batch-size 128)]
          (is (= (fixture/keyed-results ctx model) (fixture/keyed-results c2 m2)))
          (is (= (mapv :key (:entries (rmp/page ctx model {:index :total :direction :desc :limit 11})))
                 (mapv :key (:entries (rmp/page c2 m2 {:index :total :direction :desc :limit 11}))))))))))

(deftest source-failure-mid-deferred-catch-up-keeps-staged-batches
  ;; INT (OPT-2 x OPT-4): with the default threshold the same failure commits
  ;; the adaptive batches [1024 2048] into the deferred build's staging
  ;; namespace. The model namespace stays untouched (no watermark, no
  ;; checkpoint: readers never see a partial build), and the retry resumes
  ;; from the staged watermark, applying only the remaining 1,928 events.
  (fixture/keyed-fixture
    (fn []
      (let [ctx fixture/*keyed-ctx*
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})
            events (deltas 5000)]
        (fixture/keyed-append! events)
        (with-redefs [es/read (failing-read-after 4000)]
          (let [{:keys [value batches]} (recorded-batches #(try (rmp/value ctx model "0")
                                                                (catch Throwable t {:thrown (ex-message t)})))]
            (is (:thrown value))
            (is (= [1024 2048] batches))))
        (let [p (with-redefs-fn {#'core/keyed-catch-up! (fn [& _] nil)} #(rmp/progress ctx model))]
          (is (nil? (:checked-through p)) "no checkpoint published after a source failure")
          (is (nil? (:watermark p)) "staged batches are not visible in the model namespace"))
        (let [{:keys [value batches]} (recorded-batches #(fixture/keyed-results ctx model))]
          (is (= (expected-totals events) value))
          (is (= 1928 (reduce + batches))))
        (is (= (es/head (:event-store ctx) (:tenant-id ctx)) (:checked-through (rmp/progress ctx model))))
        (let [m2 (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total :indexes {:total :total}})
              c2 (assoc ctx :read-model/batch-size 128 :read-model/deferred-build-threshold 0)]
          (is (= (fixture/keyed-results ctx model) (fixture/keyed-results c2 m2)))
          (is (= (mapv :key (:entries (rmp/page ctx model {:index :total :direction :desc :limit 11})))
                 (mapv :key (:entries (rmp/page c2 m2 {:index :total :direction :desc :limit 11}))))))))))

(deftest undecided-buffer-respects-the-input-budget
  ;; INT audit condition 1: below the deferral threshold (default 1,024 events) a
  ;; cold backlog used to be buffered whole, whatever its size. The deferral
  ;; decision is now also taken once the buffered input reaches :max-input-bytes,
  ;; so a large-payload backlog smaller than the threshold holds at most about one
  ;; budget of input before its first commit, and its batches are cut at the budget.
  (fixture/keyed-fixture
    (fn []
      (let [blob (apply str (repeat 10000 "x"))
            events (mapv (fn [i] {:owner (str (mod i 7)) :delta 1 :blob blob}) (range 600))
            run (fn [extra]
                  (let [model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
                        ;; ~20 KB estimated per event; 1 MiB budget -> about 52 events
                        ctx (merge (assoc fixture/*keyed-ctx* :read-model/catch-up {:max-input-bytes (* 1024 1024)}) extra)
                        consumed (atom 0) first-commit (atom nil) applied (atom [])
                        read-events es/read apply-var #'core/keyed-apply-batch! apply-batch @apply-var]
                    (with-redefs-fn {#'es/read (fn [& args]
                                                 (eduction (map (fn [e] (swap! consumed inc) e)) (apply read-events args)))
                                     apply-var (fn [& args]
                                                 (when (= 8 (count args))
                                                   (swap! first-commit #(or % @consumed))
                                                   (swap! applied conj [(count (nth args 6))
                                                                        (some? (::core/deferred (first args)))]))
                                                 (apply apply-batch args))}
                      (fn []
                        {:values (fixture/keyed-results ctx model)
                         :first-commit @first-commit :applied @applied
                         :progress (rmp/progress ctx model)}))))]
        (fixture/keyed-append! events)
        (testing "default threshold: deferred at the input budget, staged batches cut at the budget"
          (let [{:keys [values first-commit applied progress]} (run {})]
            (is (= (expected-totals events) values))
            (is (<= first-commit 60) (pr-str first-commit))
            (is (every? (fn [[n staged?]] (and staged? (<= n 53))) applied) (pr-str applied))
            (is (= 600 (reduce + (map first applied))))
            (is (= (es/head (:event-store fixture/*keyed-ctx*) (:tenant-id fixture/*keyed-ctx*))
                   (:checked-through progress)))))
        (testing "deferral disabled: the same cut points in the model namespace"
          (let [{:keys [values first-commit applied]} (run {:read-model/deferred-build-threshold 0})]
            (is (= (expected-totals events) values))
            (is (<= first-commit 60) (pr-str first-commit))
            (is (every? (fn [[n staged?]] (and (not staged?) (<= n 53))) applied) (pr-str applied))))
        (testing "an explicit batch size has no input budget: only the threshold decides"
          (let [{:keys [values applied]} (run {:read-model/batch-size 200
                                                ;; explicit, so the case does not depend on the default
                                                :read-model/deferred-build-threshold 1024})]
            (is (= (expected-totals events) values))
            (is (= [[200 false] [200 false] [200 false]] applied) (pr-str applied))))))))
