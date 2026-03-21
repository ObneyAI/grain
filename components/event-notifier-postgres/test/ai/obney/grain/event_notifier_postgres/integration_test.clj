(ns ai.obney.grain.event-notifier-postgres.integration-test
  "Integration tests for Postgres LISTEN/NOTIFY event notification.
   Requires PG_EVENT_STORE_TESTS=true and a running Postgres instance."
  (:require [clojure.test :refer :all]
            [clojure.core.async :as async]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-postgres-v3.core :as pg-core]
            [ai.obney.grain.event-notifier-postgres.core :as notifier]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [next.jdbc :as jdbc]
            [clj-uuid :as uuid])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defschemas test-schemas
  {:test/notify-event [:map]})

;; -------------------- ;;
;; Config & Dynamic Var ;;
;; -------------------- ;;

(def ^:dynamic *event-store* nil)

(defn pg-config []
  {:conn {:type          :postgres
          :server-name   (or (System/getenv "PG_HOST") "localhost")
          :port-number   (or (System/getenv "PG_PORT") "5432")
          :username      (or (System/getenv "PG_USER") "postgres")
          :password      (or (System/getenv "PG_PASSWORD") "password")
          :database-name (or (System/getenv "PG_DATABASE") "obneyai")}})

(defn pg-pool []
  (get-in *event-store* [:state ::pg-core/connection-pool]))

;; ---------- ;;
;; Fixtures   ;;
;; ---------- ;;

(defn once-fixture [f]
  (if (= "true" (System/getenv "PG_EVENT_STORE_TESTS"))
    (let [store (es/start (pg-config))]
      (binding [*event-store* store]
        (try
          (f)
          (finally
            (es/stop store)))))
    (println "SKIPPING event-notifier-postgres integration tests (set PG_EVENT_STORE_TESTS=true to enable)")))

(defn each-fixture [f]
  (jdbc/execute! (pg-pool) ["TRUNCATE grain.tenants CASCADE"])
  (f))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

;; =====================================
;; NOTIFY is fired on append
;; =====================================

(deftest pg-notify-fires-on-append
  (testing "Appending events fires pg_notify on the grain_events channel"
    (let [tenant-id (uuid/v4)
          latch (CountDownLatch. 1)
          received (atom nil)
          listener (notifier/start-listener
                    {:connection-config (:conn (pg-config))
                     :on-notification (fn [notification]
                                        (reset! received notification)
                                        (.countDown latch))})]
      (try
        ;; Give listener time to establish LISTEN
        (Thread/sleep 200)
        ;; Append an event
        (es/append *event-store*
          {:tenant-id tenant-id
           :events [(es/->event {:type :test/notify-event :body {}})]})
        ;; Wait for notification (max 5 seconds)
        (is (.await latch 5 TimeUnit/SECONDS)
            "Should receive notification within 5 seconds")
        ;; Verify payload contains the tenant-id
        (is (= (str tenant-id) @received))
        (finally
          (notifier/stop-listener listener))))))

(deftest pg-notify-contains-tenant-id
  (testing "Notification payload is the tenant-id string"
    (let [tenant-a (uuid/v4)
          tenant-b (uuid/v4)
          notifications (atom [])
          latch (CountDownLatch. 2)
          listener (notifier/start-listener
                    {:connection-config (:conn (pg-config))
                     :on-notification (fn [notification]
                                        (swap! notifications conj notification)
                                        (.countDown latch))})]
      (try
        (Thread/sleep 200)
        ;; Append to two different tenants
        (es/append *event-store*
          {:tenant-id tenant-a
           :events [(es/->event {:type :test/notify-event :body {}})]})
        (es/append *event-store*
          {:tenant-id tenant-b
           :events [(es/->event {:type :test/notify-event :body {}})]})
        ;; Wait for both notifications
        (is (.await latch 5 TimeUnit/SECONDS))
        (is (= #{(str tenant-a) (str tenant-b)} (set @notifications)))
        (finally
          (notifier/stop-listener listener))))))

(deftest listener-start-stop-clean
  (testing "Listener starts and stops without error"
    (let [listener (notifier/start-listener
                    {:connection-config (:conn (pg-config))
                     :on-notification (fn [_] nil)})]
      (Thread/sleep 200)
      (is (some? listener))
      (notifier/stop-listener listener))))

;; =====================================
;; Event notifier bridge: NOTIFY -> read events -> local pubsub
;; =====================================

(deftest bridge-publishes-events-to-local-pubsub
  (testing "On NOTIFY, bridge reads new events from store and publishes to local pubsub"
    (let [tenant-id (uuid/v4)
          received-events (atom [])
          latch (CountDownLatch. 1)
          ps-inst (pubsub/start {:type :core-async :topic-fn :event/type})
          sub-chan (async/chan 10)
          _ (pubsub/sub ps-inst {:topic :test/notify-event :sub-chan sub-chan})
          bridge (notifier/start-bridge
                  {:connection-config (:conn (pg-config))
                   :event-store *event-store*
                   :event-pubsub ps-inst})]
      (try
        (Thread/sleep 200)
        ;; Consumer thread to drain pubsub
        (future
          (loop []
            (when-let [event (async/<!! sub-chan)]
              (swap! received-events conj event)
              (.countDown latch)
              (recur))))
        ;; Append an event (triggers pg_notify inside the transaction)
        (es/append *event-store*
          {:tenant-id tenant-id
           :events [(es/->event {:type :test/notify-event :body {}})]})
        ;; Wait for the bridge to pick it up
        (is (.await latch 5 TimeUnit/SECONDS)
            "Bridge should publish event to local pubsub within 5 seconds")
        (is (= 1 (count @received-events)))
        (is (= :test/notify-event (:event/type (first @received-events))))
        (is (= tenant-id (:grain/tenant-id (first @received-events))))
        (finally
          (notifier/stop-bridge bridge)
          (async/close! sub-chan)
          (pubsub/stop ps-inst))))))
