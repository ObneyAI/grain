(ns ai.obney.grain.event-notifier-postgres.integration-test
  "Integration tests for the optional Postgres LISTEN/NOTIFY bridge.
   Signals are supplied explicitly: event-store append no longer emits NOTIFY
   since processors switched to polling (a524e61).
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
  {:test/notify-event [:map [:n :int]]})

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

(defn- notify! [connection tenant-id]
  (jdbc/execute! connection ["SELECT pg_notify('grain_events', ?)" (str tenant-id)]))

(defn- append! [tenant-id values]
  (let [result (es/append *event-store*
                         {:tenant-id tenant-id
                          :events (mapv #(es/->event {:type :test/notify-event :body {:n %}}) values)})]
    (when-not (vector? result)
      (throw (ex-info "Test append failed" {:result result})))
    result))

(defn- take-message [ch]
  (async/alt!! ch ([value] value) (async/timeout 5000) ::timeout))

(defn- eventually? [pred]
  (let [deadline (+ (System/nanoTime) 5000000000)]
    (loop []
      (cond
        (pred) true
        (> (System/nanoTime) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(deftest listener-receives-notification-only-after-commit
  (testing "A real Postgres notification is delivered after its transaction commits"
    (let [tenant-id (uuid/v4)
          latch (CountDownLatch. 1)
          received (atom nil)
          listener (notifier/start-listener
                    {:connection-config (:conn (pg-config))
                     :on-notification (fn [notification]
                                        (reset! received notification)
                                        (.countDown latch))})]
      (try
        ;; start-listener commits LISTEN before returning; no startup sleep.
        (jdbc/with-transaction [tx (pg-pool)]
          (notify! tx tenant-id)
          (is (false? (.await latch 100 TimeUnit/MILLISECONDS))
              "An uncommitted signal must not be delivered"))
        (is (.await latch 5 TimeUnit/SECONDS)
            "Should receive notification within 5 seconds")
        ;; Verify payload contains the tenant-id
        (is (= (str tenant-id) @received))
        (finally
          (notifier/stop-listener listener))))))

(deftest listener-preserves-tenant-payloads
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
        (notify! (pg-pool) tenant-a)
        (notify! (pg-pool) tenant-b)
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
      (try
        (is (.isAlive ^Thread (:thread listener)))
        (finally (notifier/stop-listener listener)))
      (is (.isClosed ^java.sql.Connection (:connection listener)))
      (is (not (.isAlive ^Thread (:thread listener)))))))

;; =====================================
;; Event notifier bridge: NOTIFY -> read events -> local pubsub
;; =====================================

(deftest bridge-catches-up-incrementally-on-explicit-signals
  (testing "Signals catch up committed events once, with tenant identity and without transaction markers"
    (let [tenant-id (uuid/v4)
          backlog (append! tenant-id [1 2])
          ps-inst (pubsub/start {:type :core-async :topic-fn :event/type})
          sub-chan (async/chan 10)
          _ (doseq [topic [:test/notify-event :grain/tx]]
              (pubsub/sub ps-inst {:topic topic :sub-chan sub-chan}))
          bridge (notifier/start-bridge
                  {:connection-config (:conn (pg-config))
                   :event-store *event-store*
                   :event-pubsub ps-inst})]
      (try
        (notify! (pg-pool) tenant-id)
        (is (= (mapv #(assoc % :grain/tenant-id tenant-id) backlog)
               [(take-message sub-chan) (take-message sub-chan)]))
        (let [[next-event] (append! tenant-id [3])]
          (notify! (pg-pool) tenant-id)
          (is (= (assoc next-event :grain/tenant-id tenant-id) (take-message sub-chan)))
          (is (= (:event/id next-event) (get @(:watermarks bridge) tenant-id))))
        ;; Await processing of the duplicate signal before checking the channel.
        (notify! (pg-pool) tenant-id)
        (is (eventually? #(pos? @(get-in bridge [:stats :empty-read-count]))))
        (is (nil? (async/poll! sub-chan)) "No duplicate domain events or transaction markers")
        (finally
          (notifier/stop-bridge bridge)
          (async/close! sub-chan)
          (pubsub/stop ps-inst))))))
