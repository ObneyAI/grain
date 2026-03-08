(ns ^:deprecated ai.obney.grain.todo-processor.interface
  "DEPRECATED: Use ai.obney.grain.todo-processor-v2.interface instead."
  (:require [ai.obney.grain.todo-processor.core :as core]))

(defn start
  [config]
  (core/start config))

(defn stop 
  [todo-processor]
  (core/stop todo-processor))