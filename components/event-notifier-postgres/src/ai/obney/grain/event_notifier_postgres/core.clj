(ns ai.obney.grain.event-notifier-postgres.core
  "Postgres LISTEN/NOTIFY bridge for cross-instance event notification.
   Uses a dedicated JDBC connection (not from the pool) to LISTEN on
   the grain_events channel."
  (:require [com.brunobonacci.mulog :as u]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.pubsub.interface :as pubsub])
  (:import [java.sql DriverManager]
           [org.postgresql PGConnection]))

(defn- make-pg-url
  [{:keys [server-name port-number database-name]}]
  (format "jdbc:postgresql://%s:%s/%s" server-name port-number database-name))

(defn- open-listen-connection
  "Opens a raw JDBC connection and issues LISTEN grain_events."
  [{:keys [server-name port-number database-name username password] :as config}]
  (let [url (make-pg-url config)
        props (doto (java.util.Properties.)
                (.setProperty "user" username)
                (.setProperty "password" password))
        conn (DriverManager/getConnection url props)]
    ;; Disable auto-commit for LISTEN
    (.setAutoCommit conn false)
    (let [stmt (.createStatement conn)]
      (.execute stmt "LISTEN grain_events")
      (.close stmt))
    (.commit conn)
    conn))

(defn start-listener
  "Start listening on the grain_events channel.
   config keys:
     :connection-config - Postgres connection map (server-name, port-number, etc.)
     :on-notification   - fn called with the notification payload string

   Returns a listener map that can be passed to stop-listener."
  [{:keys [connection-config on-notification]}]
  (let [conn (open-listen-connection connection-config)
        pg-conn (.unwrap conn PGConnection)
        running (atom true)
        thread (Thread.
                (fn []
                  (u/log ::listener-started)
                  (while @running
                    (try
                      ;; Poll for notifications with 100ms timeout
                      (let [notifications (.getNotifications pg-conn 100)]
                        (when notifications
                          (doseq [n notifications]
                            (try
                              (on-notification (.getParameter n))
                              (catch Throwable t
                                (u/log ::notification-handler-error :exception t))))))
                      (catch Throwable t
                        (when @running
                          (u/log ::listener-poll-error :exception t)))))))]
    (.setDaemon thread true)
    (.setName thread "grain-event-notifier")
    (.start thread)
    {:connection conn
     :thread thread
     :running running}))

(defn stop-listener
  "Stop the LISTEN connection and background thread."
  [{:keys [connection thread running]}]
  (when running
    (reset! running false))
  (when thread
    (.join thread 2000))
  (when (and connection (not (.isClosed connection)))
    (.close connection))
  (u/log ::listener-stopped))

(defn start-bridge
  "Start a LISTEN/NOTIFY bridge that reads new events from the event store
   and publishes them to the local pubsub when a notification arrives.

   On each notification:
   1. Parse tenant-id from the payload
   2. Read all events for that tenant since the last known event
   3. Publish each event to the local pubsub with :grain/tenant-id attached

   config keys:
     :connection-config - Postgres connection map
     :event-store       - the event store instance
     :event-pubsub      - the local pubsub instance"
  [{:keys [connection-config event-store event-pubsub]}]
  (let [;; Track last-seen event id per tenant for incremental reads
        watermarks (atom {})
        notify-count (atom 0)
        read-count (atom 0)
        publish-count (atom 0)
        empty-read-count (atom 0)
        trace-log (atom [])
        listener (start-listener
                  {:connection-config connection-config
                   :on-notification
                   (fn [tenant-id-str]
                     (try
                       (swap! notify-count inc)
                       (let [tenant-id (java.util.UUID/fromString tenant-id-str)
                             last-id (get @watermarks tenant-id)
                             read-args (cond-> {:tenant-id tenant-id}
                                         last-id (assoc :after last-id))
                             events (into []
                                      (remove #(= :grain/tx (:event/type %)))
                                      (es/read event-store read-args))
                             last-event-id (when (seq events) (:event/id (last events)))]
                         (swap! read-count inc)
                         (if (seq events)
                           (do
                             (swap! watermarks assoc tenant-id last-event-id)
                             (swap! publish-count + (count events))
                             ;; Trace: record what we read
                             (swap! trace-log conj
                                    {:tenant (str tenant-id)
                                     :watermark-before (str last-id)
                                     :watermark-after (str last-event-id)
                                     :events-read (count events)
                                     :event-ids (mapv #(str (:event/id %)) events)})
                             (doseq [event events]
                               (pubsub/pub event-pubsub
                                           {:message (assoc event :grain/tenant-id tenant-id)})))
                           (swap! empty-read-count inc)))
                       (catch Throwable t
                         (u/log ::bridge-error :tenant-id tenant-id-str :exception t))))})]
    {:listener listener
     :watermarks watermarks
     :stats {:notify-count notify-count
             :read-count read-count
             :publish-count publish-count
             :empty-read-count empty-read-count}
     :trace-log trace-log}))

(defn stop-bridge
  "Stop the bridge."
  [{:keys [listener]}]
  (when listener
    (stop-listener listener)))
