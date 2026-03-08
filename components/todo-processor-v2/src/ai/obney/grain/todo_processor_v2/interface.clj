(ns ai.obney.grain.todo-processor-v2.interface
  (:require [ai.obney.grain.todo-processor-v2.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop
  [todo-processor]
  (core/stop todo-processor))
