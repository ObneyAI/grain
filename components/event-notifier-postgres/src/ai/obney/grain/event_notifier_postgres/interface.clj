(ns ai.obney.grain.event-notifier-postgres.interface
  (:require [ai.obney.grain.event-notifier-postgres.core :as core]))

(defn start-listener [config]
  (core/start-listener config))

(defn stop-listener [listener]
  (core/stop-listener listener))

(defn start-bridge [config]
  (core/start-bridge config))

(defn stop-bridge [bridge]
  (core/stop-bridge bridge))
