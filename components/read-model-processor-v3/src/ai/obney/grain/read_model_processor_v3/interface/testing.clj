(ns ai.obney.grain.read-model-processor-v3.interface.testing
  "Committed-state inspection without event catch-up, for failure/restart tests."
  (:require [ai.obney.grain.read-model-processor-v3.core :as core]
            [ai.obney.grain.read-model-processor-v3.lifetime :as lifetime]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]))

(defn committed
  [context model]
  (let [rt (:runtime (:projection-store context))]
    (lifetime/with-request rt
      #(let [s (#'core/scoped-state rt model (get @rmp/read-model-registry* model)
                                    {:tenant-id (:tenant-id context) :scope nil})
             v (#'core/projection-value s)]
         {:data (if (map? v)
                  (into {} v)
                  v) :watermark (:watermark s)}))))

(defn release-store!
  "Close a test store and wait for unreachable result pins before deleting files.
   Fails if the test still retains a committed result. Not an application API."
  [store]
  (rmp/close-store! store)
  (loop [attempt 0]
    (System/gc)
    (lifetime/maintain-snapshots! (:runtime store) false)
    (when-not (:released? (rmp/store-status store))
      (when (>= attempt 100)
        (throw (ex-info "Test still retains a committed projection" {})))
      (Thread/sleep 20)
      (recur (inc attempt)))))
