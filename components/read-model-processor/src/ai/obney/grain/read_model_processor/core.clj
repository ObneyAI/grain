(ns ai.obney.grain.read-model-processor.core
  (:require [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.data.fressian :as fressian]
            [com.brunobonacci.mulog :as u])
  (:import [java.io ByteArrayInputStream]))

(defn fressian-encode [data]
  (let [^java.nio.ByteBuffer buf (fressian/write data)
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn fressian-decode [bytes]
  (fressian/read (ByteArrayInputStream. bytes)))

(defn format-key
  [n v]
  (.getBytes (format "%s-%s" n v)))

(defn format-scoped-key [n v scope]
  (if scope
    (.getBytes (format "%s-%s-%s" n v (Integer/toHexString (hash scope))))
    (format-key n v)))

(defn- add-watermark [query watermark]
  (if (vector? query)
    (mapv #(assoc % :after watermark) query)
    (assoc query :after watermark)))

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
  [{:keys [event-store cache]}
   {:keys [f query name version scope]}]
  (u/with-context {:read-model/query query
                   :read-model/name name
                   :read-model/version version})
  (let [cache-key (format-scoped-key name version scope)]
    (u/trace
     ::read-model-processed
     [:metric/name "ReadModelProcessed" :metric/resolution :high]
     (if-let [v (kv/get! cache {:k cache-key})]
       (u/trace
        ::cache-hit
        [:metric/name "ReadModelCacheHit" :metric/resolution :high]
        (let [{:keys [data watermark]} (fressian-decode v)
              events (es/read event-store (add-watermark query watermark))
              {:keys [state event-count new-watermark]} (process-events data events f)
              _ (when (>= event-count 10)
                  (kv/put! cache
                           {:k cache-key
                            :v (fressian-encode {:data state
                                                :watermark new-watermark})}))]
          state))
       (u/trace
        ::cache-miss
        [:metric/name "ReadModelCacheMiss" :metric/resolution :high]
        (let [events (es/read event-store query)
              {:keys [state _event-count new-watermark]} (process-events {} events f)
              _ (kv/put! cache
                         {:k cache-key
                          :v (fressian-encode {:data state
                                              :watermark new-watermark})})]
          state))))))


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