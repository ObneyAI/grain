(ns ai.obney.grain.read-model-processor-v2.telemetry-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.telemetry :as telemetry]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.event-store-v3.interface :as es]
            [com.brunobonacci.mulog.core :as mulog-core]
            [com.brunobonacci.mulog.buffer :as buffer]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]))

(defn- capture [f]
  (let [events (atom [])]
    (with-redefs [telemetry/emit! #(swap! events conj %)] (f events))))
(defn- named [events name] (filter #(= name (:metric/name %)) @events))
(defn- total [events name] (reduce + 0 (map :metric/value (named events name))))

(deftest public-read-failure-preserves-the-exception
  (capture
   (fn [events]
     (let [error (ex-info "storage" {})]
       (with-redefs [core/keyed-point (fn [& _] (throw error))]
         (rmp/register-read-model! ::items merge {:events #{:keyed/change} :key :owner})
         (is (identical? error (try (rmp/value {} ::items "a") (catch Throwable t t)))))
       (is (= 1 (count (named events "ReadModelReadDuration"))))
       (is (= 1 (total events "ReadModelReadFailed")))))))

(deftest lazy-read-attempts-own-consumption-and-distinguish-consumer-errors
  (capture
   (fn [events]
     (with-redefs [core/keyed-values (fn [& _] (reify clojure.lang.IReduceInit
                                               (reduce [_ rf init] (reduce rf init [1 2 3]))))]
       (rmp/register-read-model! ::items merge {:events #{:keyed/change} :key :owner})
       (let [result (rmp/values {} ::items)]
         (is (empty? (named events "ReadModelReadDuration"))))
       (let [result (rmp/values {} ::items)
             error (ex-info "consumer" {})]
         (is (= [1 2 3] (into [] result)))
         (is (= [1] (transduce (take 1) conj [] result)))
         (is (identical? error (try (reduce (fn [_ _] (throw error)) [] result)
                                   (catch Throwable t t))))
         (is (= 3 (count (named events "ReadModelReadDuration"))))
         (is (zero? (total events "ReadModelReadFailed")))
         (is (= "consumer-error" (:read-model-outcome (last (named events "ReadModelReadDuration"))))))))))

(deftest routed-events-count-source-events-once-and-preserve-optimized-paths
  (fixture/keyed-fixture
   (fn []
     (rmp/register-read-model! ::routed merge
       {:events #{:keyed/change} :route :inputs :indexes {:score :score}})
     (fixture/keyed-append! [{:inputs [["a" {:score 1}] ["b" {:score 2}] ["c" {:score 3}]]}])
     (capture
      (fn [events]
        (is (= 3 (count (into [] (rmp/values fixture/*keyed-ctx* ::routed)))))
        (is (= 1 (total events "ReadModelCommittedEvents")))
        (is (= 1 (total events "ReadModelCommittedBatches")))
        (is (pos? (total events "ReadModelEncodedBytesWritten")))
        (is (= 1 (count (named events "ReadModelCatchUpDuration")))))))))

(defn- cv [sym] (ns-resolve 'ai.obney.grain.read-model-processor-v2.core sym))

(deftest failed-transactions-and-map-full-splits-count-only-physical-commits
  (fixture/keyed-fixture
   (fn []
     (rmp/register-read-model! ::split merge {:events #{:keyed/change} :key :owner})
     (fixture/keyed-append! (mapv #(hash-map :owner (str %) :v %) (range 5)))
     (let [transact kv/transact]
       (capture
        (fn [events]
          (with-redefs-fn
            {#'kv/transact (fn [cache mode f]
                            (transact cache mode
                              (fn [tx]
                                (let [result (f tx)]
                                  (when (and (= :write mode) (map? result) (> (:events result 0) 2))
                                    (throw (ex-info "controlled map full after writes" {::map-full true})))
                                  result))))
             (cv 'keyed-map-full?) #(boolean (::map-full (ex-data %)))}
            #(is (= 5 (count (into [] (rmp/values (assoc fixture/*keyed-ctx* :read-model/catch-up {:min-events 1 :cold-events 5 :max-events 5})
                                                  ::split))))))
          (is (= 5 (total events "ReadModelCommittedEvents")))
          (is (= 3 (total events "ReadModelCommittedBatches")))
          (is (= 2 (total events "ReadModelBatchBackoff")))
          (is (every? #(= "live" (:read-model-target %)) (named events "ReadModelCommittedEvents")))))))))

(deftest rollback-tail-never-inflates-committed-work
  (fixture/keyed-fixture
   (fn []
     (let [failure (ex-info "bad reducer" {})]
       (rmp/register-read-model! ::rollback
         (fn [state event] (if (= 2 (:v event)) (throw failure) (merge state event)))
         {:events #{:keyed/change} :key :owner})
       (fixture/keyed-append! (mapv #(hash-map :owner (str %) :v %) (range 5)))
       (capture
        (fn [events]
          (is (identical? failure
                (try (into [] (rmp/values (assoc fixture/*keyed-ctx* :read-model/batch-size 2) ::rollback))
                     (catch Throwable t t))))
          (is (= 2 (total events "ReadModelCommittedEvents")))
          (is (= 1 (total events "ReadModelCommittedBatches")))
          (is (= 1 (total events "ReadModelCatchUpFailed")))
          (is (= 1 (total events "ReadModelReadFailed")))))))))

(deftest owned-cold-builds-include-publication-and-use-staging-counters
  (fixture/keyed-fixture
   (fn []
     (rmp/register-read-model! ::cold merge
       {:events #{:keyed/change} :key :owner :indexes {:v :v}})
     (fixture/keyed-append! (mapv #(hash-map :owner (str %) :v %) (range 12)))
     (capture
      (fn [events]
        (is (= 12 (count (into [] (rmp/values (assoc fixture/*keyed-ctx*
                                                   :read-model/batch-size 3
                                                   :read-model/deferred-build-threshold 4) ::cold)))))
        (is (= 12 (total events "ReadModelCommittedEvents")))
        (is (= 4 (total events "ReadModelCommittedBatches")))
        (is (every? #(= "staging" (:read-model-target %)) (named events "ReadModelCommittedEvents")))
        (is (= 1 (count (named events "ReadModelColdBuildDuration"))))
        (is (= "success" (:read-model-outcome (first (named events "ReadModelColdBuildDuration")))))
        (is (zero? (total events "ReadModelColdBuildFailed"))))))))

(deftest read-session-and-nested-reads-attribute-error-origin-once
  (let [failure (ex-info "same original throwable" {})]
    (doseq [nested-processor? [false true]]
      (capture
       (fn [events]
         (with-redefs [core/keyed-read-session (fn [_ _ f] (f [{}]))
                       core/keyed-values (fn [& _] (reify clojure.lang.IReduceInit
                                                       (reduce [_ rf init] (rf init 1))))
                       core/keyed-point (fn [& _] (throw failure))]
           (rmp/register-read-model! ::items merge {:events #{:keyed/change} :key :owner})
           (is (identical? failure
                 (try (rmp/read-session {} [::items]
                        (fn [ctx] (reduce (fn [_ _]
                                           (if nested-processor? (rmp/value {} ::items "x") (throw failure)))
                                         [] (rmp/values ctx ::items))))
                      (catch Throwable t t))))
           (is (= 1 (count (named events "ReadModelReadDuration")))))
         (is (= (if nested-processor? 1 0) (total events "ReadModelReadFailed"))))))))

(deftest independent-future-read-does-not-inherit-another-threads-attempt
  (capture
   (fn [events]
     (with-redefs [core/keyed-read-session (fn [_ _ f] (f [{}]))
                   core/keyed-point (fn [& _] :value)]
       (rmp/register-read-model! ::items merge {:events #{:keyed/change} :key :owner})
       (is (= :value (rmp/read-session {} [::items]
                      (fn [_] (deref (future (rmp/value {} ::items "x")) 10000 ::timeout)))))
       (is (= 2 (count (named events "ReadModelReadDuration")))))
     (is (= #{"read-session" "value"} (set (map :read-model-operation (named events "ReadModelReadDuration"))))))))

(deftest public-validation-and-cancellation-preserve-contracts
  (rmp/register-read-model! ::items merge {:events #{:keyed/change} :key :owner})
  (doseq [f [#(rmp/project {} ::items) #(rmp/index-groups {} ::items :identity nil)
             #(rmp/index-values {} ::items [] nil)
             #(rmp/key-page {} ::items {:sort-value 12})]]
    (capture (fn [events]
               (is (thrown? clojure.lang.ExceptionInfo (f)))
               (is (= 1 (count (named events "ReadModelReadDuration"))))
               (is (= 1 (total events "ReadModelReadFailed"))))))
  (capture (fn [events]
             (let [failure (InterruptedException. "cancel")]
               (with-redefs [core/keyed-point (fn [& _] (throw failure))]
                 (is (identical? failure (try (rmp/value {} ::items "a") (catch Throwable t t)))))
               (is (zero? (total events "ReadModelReadFailed")))
               (is (= "cancelled" (:read-model-outcome (first (named events "ReadModelReadDuration")))))))))

(deftest ordinary-scope-dimensions-never-contain-scope-values
  (capture
   (fn [events]
     (with-redefs [core/p (fn [& _] :state)]
       (rmp/p {} {:name ::ordinary :scope {:tags #{[:secret "secret-tenant"]}}})
       (rmp/p {} {:name ::partition :partition-fn identity :entity-id-fn :id :partition-key "secret-partition"}))
     (let [[scoped partitioned] (named events "ReadModelReadDuration")]
       (is (= "ordinary" (:read-model-kind scoped)))
       (is (= "scoped" (:read-model-scope scoped)))
       (is (= "partitioned" (:read-model-kind partitioned)))
       (is (= "partition" (:read-model-scope partitioned)))
       (is (not (.contains (pr-str @events) "secret")))))))

(defn- reader! [f]
  (let [state (atom :pending) done (promise)]
    {:state state :done done
     :task (future
             (when (compare-and-set! state :pending :running)
               (try (f) (finally (reset! state :finished) (deliver done true)))))}))

(defn- close-readers! [readers]
  (doseq [{:keys [task state done]} readers]
    (when-not (realized? task) (future-cancel task))
    ;; A task cancelled before its body starts cannot touch the fixture.
    (when (compare-and-set! state :pending :cancelled) (deliver done true)))
  (doseq [{:keys [done]} readers]
    (is (true? (deref done 2000 false)) "Reader exited before fixture shutdown")))

(deftest failed-single-flight-owner-is-not-counted-once-per-follower
  (fixture/keyed-fixture
    (fn []
      (rmp/register-read-model! ::flight merge {:events #{:keyed/change} :key :owner})
      (fixture/keyed-append! [{:owner "a" :v 1}])
      (let [step @(cv 'keyed-catch-up-step!) owned telemetry/owned!
            first? (atom true) entered (promise) release (promise) waiting (promise)
            failure (ex-info "first owner failed" {})]
        (capture
          (fn [events]
            (with-redefs-fn
              {(cv 'keyed-catch-up-step!)
               (fn [& args]
                 (if (compare-and-set! first? true false)
                   (do (deliver entered true) (deref release 10000 nil) (throw failure))
                   (apply step args)))
               #'telemetry/owned! (fn [duration failure dims f]
                                   (when (= "ReadModelWaitDuration" duration) (deliver waiting true))
                                   (owned duration failure dims f))}
              (fn []
                (let [readers (atom [])]
                  (try
                    (let [owner (reader! #(try (into [] (rmp/values fixture/*keyed-ctx* ::flight))
                                              (catch Throwable t t)))]
                      (swap! readers conj owner)
                      (is (deref entered 10000 nil))
                      (let [followers (vec (repeatedly 3 #(reader! (fn [] (into [] (rmp/values fixture/*keyed-ctx* ::flight))))))]
                        (swap! readers into followers)
                        (is (deref waiting 10000 nil))
                        (deliver release true)
                        (is (identical? failure (deref (:task owner) 10000 ::timeout)))
                        (doseq [f followers] (is (= 1 (count (deref (:task f) 10000 [])))))))
                    (finally
                      (deliver release true)
                      (close-readers! @readers))))))
            (is (= 1 (total events "ReadModelCatchUpFailed")))
            (is (= 2 (count (named events "ReadModelCatchUpDuration"))))
            (is (= 4 (count (named events "ReadModelReadDuration"))))
            (is (seq (named events "ReadModelWaitDuration")))
            (is (= 1 (total events "ReadModelCommittedEvents")))))))))

(deftest staged-publication-failure-resumes-without-recounting-committed-events
  (fixture/keyed-fixture
   (fn []
     (rmp/register-read-model! ::resumed merge {:events #{:keyed/change} :key :owner :indexes {:v :v}})
     (fixture/keyed-append! (mapv #(hash-map :owner (str %) :v %) (range 12)))
     (let [publish @(cv 'deferred-publish!) first? (atom true)
           failure (ex-info "publication failed" {})
           ctx (assoc fixture/*keyed-ctx* :read-model/batch-size 3 :read-model/deferred-build-threshold 4)]
       (capture
        (fn [events]
          (with-redefs-fn {(cv 'deferred-publish!)
                          (fn [& args]
                            (if (compare-and-set! first? true false) (throw failure) (apply publish args)))}
            (fn []
              (is (identical? failure (try (into [] (rmp/values ctx ::resumed)) (catch Throwable t t))))
              (is (= 12 (count (into [] (rmp/values ctx ::resumed)))))))
          (is (= 12 (total events "ReadModelCommittedEvents")))
          (is (= 1 (total events "ReadModelColdBuildFailed")))
          (is (= ["failure" "success"] (mapv :read-model-outcome (named events "ReadModelColdBuildDuration"))))))))))

(deftest staged-supersession-restarts-without-counting-a-failed-build
  (fixture/keyed-fixture
   (fn []
     (rmp/register-read-model! ::superseded merge {:events #{:keyed/change} :key :owner :indexes {:v :v}})
     (fixture/keyed-append! (mapv #(hash-map :owner (str %) :v %) (range 12)))
     (capture
      (fn [events]
        (with-redefs-fn {(cv 'deferred-publish!) (constantly nil)}
          #(is (= 12 (count (into [] (rmp/values (assoc fixture/*keyed-ctx*
                                                     :read-model/batch-size 3
                                                     :read-model/deferred-build-threshold 4) ::superseded))))))
        (is (= ["superseded"] (mapv :read-model-outcome (named events "ReadModelColdBuildDuration"))))
        (is (zero? (total events "ReadModelColdBuildFailed")))
        (is (= 1 (count (named events "ReadModelCatchUpDuration")))))))))

(deftest planner-errors-and-user-query-call-errors-remain-distinct
  (fixture/keyed-fixture
   (fn []
     (let [{:keys [definition]} (fixture/read-query-setup) failure (ex-info "user callback" {})]
       (fixture/keyed-append! [{:owner "a" :company-id "missing" :customer {:name "Anna" :company "missing"}}])
       (doseq [[definition request outcome]
               [[definition {:order :name} "failure"]
                [(assoc-in definition [:orders :computed] [[:call str :company/name]]) {:order :computed} "failure"]
                [definition {:order :computed} "failure"]
                [(assoc-in definition [:orders :computed] [[:call (fn [] (throw failure))]]) {:order :computed} "consumer-error"]]]
         (capture
          (fn [events]
            (let [result (try (rmp/query fixture/*keyed-ctx* definition request) (catch Throwable t t))]
              (is (instance? clojure.lang.ExceptionInfo result))
              (when (= "consumer-error" outcome) (is (identical? failure result))))
            (is (= [outcome] (mapv :read-model-outcome (named events "ReadModelReadDuration"))))
            (is (= (if (= "failure" outcome) 1 0) (total events "ReadModelReadFailed"))))))))))

(defn- raw-events [logger]
  (mapv (fn [[_ pairs]]
          (into {} (mapcat #(if (sequential? %) (map vec (partition 2 %)) %) pairs)))
        (buffer/items @logger)))

(deftest ordinary-cache-tier-timers-include-source-work-and-model-context
  (fixture/test-fixture
    (fn []
      (let [ctx (fixture/make-context) args (fixture/make-args) read es/read]
        (fixture/append-test-events! 1)
        (rmp/p ctx args)
        (doseq [[new-events metric expected] [[0 "ReadModelL1Revalidated" 1] [1 "ReadModelL1Stale" 2]]]
          (fixture/append-test-events! new-events)
          (let [logger (atom (buffer/ring-buffer 100)) entered (promise) release (promise)]
            (binding [mulog-core/*default-logger* logger]
              (with-redefs [es/read (fn [& args]
                                     (deliver entered (System/nanoTime))
                                     (deref release 10000 nil)
                                     (apply read args))]
                (let [reader (reader! #(rmp/p ctx args))]
                  (try
                    (let [started (deref entered 10000 nil)]
                      (is started)
                      (deliver release (System/nanoTime))
                      (is (= {:count expected} (deref (:task reader) 10000 ::timeout)))
                      (let [event (first (filter #(= metric (:metric/name %)) (raw-events logger)))]
                        (is (= :test/counter (:read-model/name event)))
                        (is (= 1 (:read-model/version event)))
                        (is (>= (:mulog/duration event) (- @release started)))))
                    (finally
                      (deliver release (System/nanoTime))
                      (close-readers! [reader]))))))))))))

(deftest deferred-owner-waits-are-timed-separately
  (let [first? (atom true)]
    (capture
     (fn [events]
       (with-redefs-fn {(cv 'deferred-owned?) (fn [& _] (compare-and-set! first? true false))
                       #'kv/transact (fn [& _] nil)}
         #((cv 'deferred-await!) {::core/metric-model ::waiting} :identity))
       (is (= ["cold-build"] (mapv :read-model-wait-kind (named events "ReadModelWaitDuration"))))
       (is (= 1 (count (named events "ReadModelWaitDuration"))))
       (is (empty? (named events "ReadModelCatchUpDuration")))))))
