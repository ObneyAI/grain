(ns ai.obney.grain.event-tailer.interface
  (:require [ai.obney.grain.event-tailer.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop
  [tailer]
  (core/stop tailer))

(defn subscribe!
  [tailer tenant-id event-types]
  (core/subscribe! tailer tenant-id event-types))

(defn unsubscribe!
  [tailer tenant-id event-types]
  (core/unsubscribe! tailer tenant-id event-types))
