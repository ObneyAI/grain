(ns ai.obney.grain.todo-processor-v2.checkpoint-initialization-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-v3.interface.protocol :as p]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.todo-processor-v2.core :as core]
            [clj-uuid :as uuid]
            [cognitect.anomalies :as anom]))

(defschemas events {:test/initialization-source [:map [:n :int]]})
(def ^:dynamic *store* nil)
(def processor :test/initialized)

(use-fixtures :each
  (fn [f]
    (let [store (es/start {:conn {:type :in-memory}})]
      (binding [*store* store]
        (try (f) (finally (es/stop store)))))))

(defn- append-source! [tenant n]
  (first (es/append *store*
                   {:tenant-id tenant
                    :events [(es/->event {:type :test/initialization-source :body {:n n}})]})))

(defn- checkpoints [tenant proc]
  (into [] (es/read *store* {:tenant-id tenant
                           :types #{:grain/todo-processor-checkpoint}
                           :tags #{[:processor (core/processor-name->uuid proc)]}})))

(defn- initialize! [tenant boundary]
  (tp/initialize-checkpoint! *store* tenant processor boundary))

(defn- intercept-store [store read-fn append-fn]
  (reify p/EventStore
    (start [_] store)
    (stop [_] nil)
    (tenants [_] (p/tenants store))
    (read [_ args] (read-fn args))
    (append [_ args] (append-fn args))))

(deftest initializes-event-or-transaction-boundary-and-retries-without-writing
  (doseq [use-head? [false true]]
    (let [tenant (uuid/v4)
          event (append-source! tenant 1)
          boundary (if use-head? (es/head *store* tenant) (:event/id event))]
      (is (= {:processor/name processor :triggered-by boundary :checkpoint/status :initialized}
             (initialize! tenant boundary)))
      (let [head (es/head *store* tenant)]
        (is (= :already-initialized (:checkpoint/status (initialize! tenant boundary))))
        (is (= head (es/head *store* tenant))))
      (let [cps (checkpoints tenant processor)]
        (is (= 1 (count cps)))
        (is (= :initial (:checkpoint/kind (first cps))))
        (is (= boundary (:triggered-by (first cps))))))))

(deftest invalid-input-and-foreign-or-uncommitted-boundaries-never-write
  (let [tenant (uuid/v4)
        other (uuid/v4)
        foreign (:event/id (append-source! other 1))
        own (:event/id (append-source! tenant 1))
        head (es/head *store* tenant)]
    (doseq [[tid proc boundary] [[nil processor own]
                               ["tenant" processor own]
                               [tenant nil own]
                               [tenant "processor" own]
                               [tenant processor nil]
                               [tenant processor (uuid/v4)]
                               [tenant processor foreign]
                               [tenant processor (uuid/v7)]]]
      (is (= ::anom/incorrect
             (::anom/category (tp/initialize-checkpoint! *store* tid proc boundary)))))
    (is (= head (es/head *store* tenant)))
    (is (empty? (checkpoints tenant processor)))))

(deftest never-rewinds-or-advances-an-existing-checkpoint
  (let [tenant (uuid/v4)
        before (:event/id (append-source! tenant 1))
        initial (:event/id (append-source! tenant 2))
        after (:event/id (append-source! tenant 3))]
    (initialize! tenant initial)
    (doseq [boundary [before after]]
      (is (= ::anom/conflict (::anom/category (initialize! tenant boundary)))))
    (is (= 1 (count (checkpoints tenant processor))))
    (is (nil? (#'core/append-with-checkpoint *store* tenant processor after [])))
    (doseq [boundary [initial after]]
      (is (= ::anom/conflict (::anom/category (initialize! tenant boundary)))))
    (is (= after (:triggered-by (last (checkpoints tenant processor)))))))

(deftest normal-checkpoint-at-same-boundary-is-not-an-initialization-retry
  (let [tenant (uuid/v4)
        boundary (:event/id (append-source! tenant 1))]
    (#'core/append-with-checkpoint *store* tenant processor boundary [])
    (is (= ::anom/conflict (::anom/category (initialize! tenant boundary))))
    (is (= 1 (count (checkpoints tenant processor))))))

(deftest initialization-is-isolated-by-tenant-and-processor
  (let [tenant (uuid/v4)
        other (uuid/v4)
        boundary (:event/id (append-source! tenant 1))
        other-boundary (:event/id (append-source! other 1))]
    (initialize! tenant boundary)
    (is (empty? (checkpoints other processor)))
    (is (empty? (checkpoints tenant :test/other)))
    (is (= :initialized (:checkpoint/status (initialize! other other-boundary))))
    (is (= :initialized (:checkpoint/status
                        (tp/initialize-checkpoint! *store* tenant :test/other boundary))))))

(deftest concurrent-initializers-use-empty-history-cas
  (doseq [same-boundary? [true false]]
    (let [tenant (uuid/v4)
          first-id (:event/id (append-source! tenant 1))
          second-id (:event/id (append-source! tenant 2))
          ready (java.util.concurrent.CountDownLatch. 2)
          release (promise)
          store (intercept-store
                 *store* #(p/read *store* %)
                 (fn [args]
                   (.countDown ready)
                   (when-not (deref release 5000 false)
                     (throw (ex-info "Timed out awaiting concurrent append" {})))
                   (p/append *store* args)))
          boundaries [first-id (if same-boundary? first-id second-id)]
          tasks (mapv (fn [boundary]
                        (future (tp/initialize-checkpoint! store tenant processor boundary)))
                      boundaries)]
      (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
      (deliver release true)
      (let [results (mapv #(deref % 5000 :timeout) tasks)]
        (is (= 1 (count (filter #(= :initialized (:checkpoint/status %)) results))))
        (if same-boundary?
          (is (= 1 (count (filter #(= :already-initialized (:checkpoint/status %)) results))))
          (is (= 1 (count (filter #(= ::anom/conflict (::anom/category %)) results))))))
      (is (= 1 (count (checkpoints tenant processor)))))))

(deftest normal-processing-winning-the-race-cannot-be-overwritten
  (let [tenant (uuid/v4)
        boundary (:event/id (append-source! tenant 1))
        store (intercept-store
               *store* #(p/read *store* %)
               (fn [args]
                 (#'core/append-with-checkpoint *store* tenant processor boundary [])
                 (p/append *store* args)))]
    (is (= ::anom/conflict
           (::anom/category (tp/initialize-checkpoint! store tenant processor boundary))))
    (is (= 1 (count (checkpoints tenant processor))))
    (is (nil? (:checkpoint/kind (first (checkpoints tenant processor)))))))

(deftest storage-failures-are-not-treated-as-empty-history-or-success
  (let [tenant (uuid/v4)
        boundary (:event/id (append-source! tenant 1))
        failure {::anom/category ::anom/unavailable ::anom/message "injected"}]
    (doseq [stage [:checkpoint-read :boundary-read :append :conflict-read]
            throws? [false true]]
      (let [reads (atom 0)
            appends (atom 0)
            fail (fn [] (if throws? (throw (ex-info "injected" {})) failure))
            store (intercept-store
                   *store*
                   (fn [args]
                     (let [n (swap! reads inc)]
                       (if (or (and (= stage :checkpoint-read) (= n 1))
                               (and (= stage :boundary-read) (= n 2))
                               (and (= stage :conflict-read) (= n 3)))
                         (fail)
                         (p/read *store* args))))
                   (fn [_]
                     (swap! appends inc)
                     (if (= stage :conflict-read)
                       {::anom/category ::anom/conflict}
                       (fail))))]
        (if throws?
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"injected"
                               (tp/initialize-checkpoint! store tenant processor boundary)))
          (is (= failure (tp/initialize-checkpoint! store tenant processor boundary))))
        (is (= (if (#{:append :conflict-read} stage) 1 0) @appends))
        (is (empty? (checkpoints tenant processor)))))))

(deftest retry-does-not-require-retained-boundary-and-reads-are-bounded
  (let [tenant (uuid/v4)
        boundary (:event/id (append-source! tenant 1))
        reads (atom [])
        store (intercept-store
               *store*
               (fn [args]
                 (swap! reads conj args)
                 (is (= true (:reverse? args)))
                 (is (= 1 (:limit args)))
                 (if (:as-of args) [] (p/read *store* args)))
               #(p/append *store* %))]
    (initialize! tenant boundary)
    (is (= :already-initialized
           (:checkpoint/status (tp/initialize-checkpoint! store tenant processor boundary))))
    (is (= 1 (count @reads)))
    (is (every? :types @reads))))

(deftest poller-start-and-restart-skip-baseline-and-process-later-events
  (let [tenant (uuid/v4)
        before (append-source! tenant 1)
        boundary (es/head *store* tenant)
        calls (atom [])
        registry @tp/processor-registry*]
    (try
      (initialize! tenant boundary)
      (reset! tp/processor-registry*
              {processor {:topics #{:test/initialization-source}
                          :handler-fn (fn [{:keys [event]}]
                                        (swap! calls conj (:n event)) {})}})
      (doseq [n [2 3]]
        (let [after (append-source! tenant n)
              poller (tp/start-tenant-poller {:event-store *store* :tenant-ids #{tenant}
                                            :poll-interval-ms 10 :batch-size 1})
              deadline (+ (System/nanoTime) 5000000000)]
          (try
            (loop []
              (when (and (not= (:event/id after)
                               (:triggered-by (last (checkpoints tenant processor))))
                         (< (System/nanoTime) deadline))
                (Thread/sleep 10)
                (recur)))
            (is (= (:event/id after) (:triggered-by (last (checkpoints tenant processor)))))
            (finally (tp/stop-tenant-poller poller)))))
      (is (= [2 3] @calls))
      (is (= 1 (:n before)))
      (finally (reset! tp/processor-registry* registry)))))
