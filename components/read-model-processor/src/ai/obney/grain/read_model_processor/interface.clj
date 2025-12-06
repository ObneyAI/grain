(ns ai.obney.grain.read-model-processor.interface)

(defn p
  "Project a Read Model.
   
   The Read Model Processor transparently manages
   incremental snapshotting and caching."
  [{:keys [_file-store _event-store] :as _context}
   {:keys [_f _query _name _version] :as _args}])