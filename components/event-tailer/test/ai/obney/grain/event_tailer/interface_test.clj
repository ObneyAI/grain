(ns ai.obney.grain.event-tailer.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :as async]
            [ai.obney.grain.event-tailer.interface :as tailer]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]))

(defschemas event-tailer-test-schemas
  {:tailer/alpha [:map [:n :int]]
   :tailer/beta [:map [:n :int]]
   :tailer/gamma [:map [:n :int]]})

(defn- start-store []
  (es/start {:conn {:type :in-memory}}))

(defn- start-pubsub []
  (pubsub/start {:type :core-async :topic-fn :event/type}))

(defn- append-event! [store tenant-id event-type n]
  (let [event (es/->event {:type event-type :body {:n n}})]
    (es/append store {:tenant-id tenant-id :events [event]})
    event))

(defn- take-msg
  ([ch] (take-msg ch 1000))
  ([ch timeout-ms]
   (async/alt!!
     ch ([v] v)
     (async/timeout timeout-ms) nil)))

(deftest pre-existing-events-do-not-replay-test
  (let [tenant-id (random-uuid)
        store (start-store)
        ps (start-pubsub)
        ch (async/chan 10)
        t (tailer/start {:event-store store
                         :event-pubsub ps
                         :poll-interval-ms 25})]
    (try
      (pubsub/sub ps {:topic :tailer/alpha :sub-chan ch})
      (append-event! store tenant-id :tailer/alpha 1)
      (tailer/subscribe! t tenant-id #{:tailer/alpha})
      (is (nil? (take-msg ch 150)))
      (append-event! store tenant-id :tailer/alpha 2)
      (let [msg (take-msg ch)]
        (is (= :tailer/alpha (:event/type msg)))
        (is (= tenant-id (:grain/tenant-id msg)))
        (is (= 2 (:n msg))))
      (finally
        (tailer/stop t)
        (async/close! ch)
        (pubsub/stop ps)
        (es/stop store)))))

(deftest overlapping-subscriptions-dedupe-published-events-test
  (let [tenant-id (random-uuid)
        store (start-store)
        ps (start-pubsub)
        ch (async/chan 10)
        t (tailer/start {:event-store store
                         :event-pubsub ps
                         :poll-interval-ms 25})]
    (try
      (pubsub/sub ps {:topic :tailer/alpha :sub-chan ch})
      (tailer/subscribe! t tenant-id #{:tailer/alpha})
      (tailer/subscribe! t tenant-id #{:tailer/alpha})
      (append-event! store tenant-id :tailer/alpha 1)
      (is (= 1 (:n (take-msg ch))))
      (is (nil? (take-msg ch 150)))
      (is (= {tenant-id {:tailer/alpha 2}} @(:subscriber-counts t)))
      (finally
        (tailer/stop t)
        (async/close! ch)
        (pubsub/stop ps)
        (es/stop store)))))

(deftest unsubscribe-decrements-and-removes-inactive-interest-test
  (let [tenant-a (random-uuid)
        tenant-b (random-uuid)
        store (start-store)
        ps (start-pubsub)
        t (tailer/start {:event-store store
                         :event-pubsub ps
                         :poll-interval-ms 100})]
    (try
      (tailer/subscribe! t tenant-a #{:tailer/alpha :tailer/beta})
      (tailer/subscribe! t tenant-a #{:tailer/alpha})
      (tailer/subscribe! t tenant-b #{:tailer/alpha})
      (tailer/unsubscribe! t tenant-a #{:tailer/alpha})
      (is (= {tenant-a {:tailer/alpha 1
                        :tailer/beta 1}
              tenant-b {:tailer/alpha 1}}
             @(:subscriber-counts t)))
      (tailer/unsubscribe! t tenant-a #{:tailer/alpha :tailer/beta})
      (is (= {tenant-b {:tailer/alpha 1}} @(:subscriber-counts t)))
      (finally
        (tailer/stop t)
        (pubsub/stop ps)
        (es/stop store)))))

(deftest no-active-subscriptions-does-not-read-test
  (let [read-calls (atom [])
        t (with-redefs [es/read (fn [_ args]
                                  (swap! read-calls conj args)
                                  [])
                        es/tenants (constantly {})]
            (tailer/start {:event-store ::store
                           :event-pubsub ::pubsub
                           :poll-interval-ms 25}))]
    (try
      (Thread/sleep 100)
      (is (empty? @read-calls))
      (finally
        (tailer/stop t)))))

(deftest active-types-are-passed-to-read-test
  (let [tenant-id (random-uuid)
        read-calls (atom [])]
    (with-redefs [es/read (fn [_ args]
                            (swap! read-calls conj args)
                            [])
                  es/tenants (constantly {tenant-id {:tenant/last-event-id nil}})]
      (let [t (tailer/start {:event-store ::store
                             :event-pubsub ::pubsub
                             :poll-interval-ms 25})]
        (try
          (tailer/subscribe! t tenant-id #{:tailer/alpha :tailer/beta})
          (Thread/sleep 100)
          (is (some #(= {:tenant-id tenant-id
                         :types #{:tailer/alpha :tailer/beta}}
                       %)
                    @read-calls))
          (finally
            (tailer/stop t)))))))

(deftest batch-size-limits-each-tick-and-continues-test
  (let [tenant-id (random-uuid)
        store (start-store)
        ps (start-pubsub)
        ch (async/chan 10)
        t (tailer/start {:event-store store
                         :event-pubsub ps
                         :poll-interval-ms 25
                         :batch-size 2})]
    (try
      (pubsub/sub ps {:topic :tailer/alpha :sub-chan ch})
      (tailer/subscribe! t tenant-id #{:tailer/alpha})
      (append-event! store tenant-id :tailer/alpha 1)
      (append-event! store tenant-id :tailer/alpha 2)
      (append-event! store tenant-id :tailer/alpha 3)
      (is (= [1 2 3]
             (mapv :n [(take-msg ch) (take-msg ch) (take-msg ch)])))
      (finally
        (tailer/stop t)
        (async/close! ch)
        (pubsub/stop ps)
        (es/stop store)))))

(deftest read-errors-do-not-advance-watermark-test
  (let [tenant-id (random-uuid)
        initial-watermark (random-uuid)]
    (with-redefs [es/read (fn [_ _] (throw (ex-info "boom" {})))
                  es/tenants (constantly {tenant-id {:tenant/last-event-id initial-watermark}})]
      (let [t (tailer/start {:event-store ::store
                             :event-pubsub ::pubsub
                             :poll-interval-ms 25})]
        (try
          (tailer/subscribe! t tenant-id #{:tailer/alpha})
          (Thread/sleep 100)
          (is (= {tenant-id initial-watermark} @(:watermarks t)))
          (finally
            (tailer/stop t)))))))

(deftest stop-prevents-further-publishes-test
  (let [tenant-id (random-uuid)
        store (start-store)
        ps (start-pubsub)
        ch (async/chan 10)
        t (tailer/start {:event-store store
                         :event-pubsub ps
                         :poll-interval-ms 25})]
    (try
      (pubsub/sub ps {:topic :tailer/alpha :sub-chan ch})
      (tailer/subscribe! t tenant-id #{:tailer/alpha})
      (tailer/stop t)
      (append-event! store tenant-id :tailer/alpha 1)
      (is (nil? (take-msg ch 150)))
      (finally
        (async/close! ch)
        (pubsub/stop ps)
        (es/stop store)))))
