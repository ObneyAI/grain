(ns ai.obney.grain.read-model-processor.interface
  (:require [ai.obney.grain.read-model-processor.core :as core]))

(defn p
  "Project a Read Model.

   The Read Model Processor transparently manages
   incremental snapshotting and caching."
  [{:keys [_event-store] :as context}
   {:keys [_f _query _name _version] :as args}]
  (core/p context args))