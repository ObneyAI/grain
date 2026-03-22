(ns ai.obney.grain.todo-processor-v2.interface
  (:require [ai.obney.grain.todo-processor-v2.core :as core]))

(def processor-registry* core/processor-registry*)

(defn register-processor!
  [processor-name config]
  (core/register-processor! processor-name config))

(defn start
  [config]
  (core/start config))

(defn stop
  [todo-processor]
  (core/stop todo-processor))

(defn start-polling
  [config]
  (core/start-polling config))

(defn stop-polling
  [polling-processor]
  (core/stop-polling polling-processor))
