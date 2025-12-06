(ns ai.obney.grain.read-model-processor.core
  (:require #_[ai.obney.grain.file-store.interface :as fs]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.edn :as edn]
            [com.brunobonacci.mulog :as u]))

(defn format-key
  [n v]
  (.getBytes (format "%s-%s" n v)))

(defn process-events
  [state events f]
  (reduce
   (fn [acc event]
     (-> acc
         (update :state f event)
         (update :event-count inc)
         (assoc :new-watermark (:event/id event))))
   {:state state
    :event-count 0}
   events))

(defn p
  [{:keys [event-store #__file-store cache]}
   {:keys [f query name version]}]
  (u/with-context {:read-model/query query
                   :read-model/name name
                   :read-model/version version})
  (u/trace
   ::read-model-processed
   [:metric/name "ReadModelProcessed" :metric/resolution :high]
   (if-let [v (kv/get! cache {:k (format-key name version)})]
     (u/trace
      ::cache-hit
      [:metric/name "ReadModelCacheHit" :metric/resolution :high]
      (let [{:keys [data watermark]} (edn/read-string v)
            events (es/read event-store (assoc query :after watermark))
            {:keys [state event-count new-watermark]} (process-events data events f)
            _ (when (>= event-count 10)
                (kv/put! cache
                         {:k (format-key name version)
                          :v (-> {:data state
                                  :watermark new-watermark}
                                 str
                                 .getBytes)}))]
        state))
     (u/trace
      ::cache-miss
      [:metric/name "ReadModelCacheMiss" :metric/resolution :high]
      (let [events (es/read event-store query)
            {:keys [state _event-count new-watermark]} (process-events {} events f)
            _ (kv/put! cache
                       {:k (format-key name version)
                        :v (-> {:data state
                                :watermark new-watermark}
                               str
                               .getBytes)})]
        state)))))


(comment

  (require '[ai.obney.grain.kv-store-lmdb.interface :as lmdb])

  (def cache (kv/start
              (lmdb/->KV-Store-LMDB
               {:storage-dir "storage"
                :db-name "my-db"})))
  
  (kv/stop cache)

  (require '[ai.obney.grain.schema-util.interface :refer [defschemas]])

  (defschemas _
    {:test/counter-incremented
     [:map
      [:index :int]]})

  (def event-store (es/start {:conn {:type :in-memory}}))

  (def test-events
    (mapv #(es/->event
            {:type :test/counter-incremented
             :body {:index %}})
          (range 10000)))

  (es/append event-store {:events test-events})

  (defn f [state _event]
    (update state :count (fnil inc 0)))

  (time
   (p {:event-store event-store :cache cache}
      {:f f
       :query {:types #{:test/counter-incremented}}
       :name :my/counter
       :version 4}))


  "")