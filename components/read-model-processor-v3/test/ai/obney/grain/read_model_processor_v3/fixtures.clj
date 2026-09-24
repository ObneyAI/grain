(ns ai.obney.grain.read-model-processor-v3.fixtures
  (:require [clojure.test :refer [is]] [clojure.java.io :as io]
            [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.read-model-processor-v3.lifetime :as life]
            [ai.obney.grain.event-store-v3.interface :as es]))
(def ^:dynamic *context* nil)
(def ^:dynamic *directory* nil)
(defn error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:error (ex-data e)))))
(defn release! [store]
  (rmp/close-store! store)
  (loop [n 0]
    (System/gc)
    (life/maintain-snapshots! (:runtime store) false)
    (when-not (:released? (rmp/store-status store))
      (when (> n 100) (throw (ex-info "Test retained an unexpected committed result" {})))
      (Thread/sleep 20) (recur (inc n)))))
(defn fixture [f]
  (let [dir (str "/tmp/grain-rmp-v3-test-" (random-uuid))
        store (rmp/open-store {:storage-dir dir :backend :file})
        events (es/start {:conn {:type :in-memory}}) registry @rmp/read-model-registry*]
    (try
      (binding [*context* {:projection-store store :event-store events :tenant-id (random-uuid)}
                *directory* dir] (f))
      (finally
        (reset! rmp/read-model-registry* registry)
        (release! store) (es/stop events)
        (doseq [file (reverse (file-seq (io/file dir)))] (io/delete-file file true))))))
