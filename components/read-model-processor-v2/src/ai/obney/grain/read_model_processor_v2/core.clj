(ns ai.obney.grain.read-model-processor-v2.core
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [clojure.data.fressian :as fressian]
            [clojure.walk :as walk]
            [com.brunobonacci.mulog :as u])
  (:import [java.io ByteArrayInputStream]))

(defn fressian-encode [data]
  (let [^java.nio.ByteBuffer buf (fressian/write data)
        arr (byte-array (.remaining buf))]
    (.get buf arr)
    arr))

(defn fressian-decode [bytes]
  (walk/postwalk
   (fn [x]
     (cond
       (instance? java.util.Set x) (set x)
       (and (instance? java.util.List x) (not (vector? x))) (vec x)
       :else x))
   (fressian/read (ByteArrayInputStream. bytes))))

(defn format-key
  [n v]
  (.getBytes (format "%s-%s" n v)))

(defn format-scoped-key [n v scope]
  (if scope
    (.getBytes (format "%s-%s-%s" n v (Integer/toHexString (hash scope))))
    (format-key n v)))

(defn- add-watermark [query watermark]
  (if watermark
    (if (vector? query)
      (mapv #(assoc % :after watermark) query)
      (assoc query :after watermark))
    query))

(defn- inject-tenant-id [query tenant-id]
  (if (vector? query)
    (mapv #(assoc % :tenant-id tenant-id) query)
    (assoc query :tenant-id tenant-id)))

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
  [{:keys [event-store cache tenant-id]}
   {:keys [f query name version scope]}]
  (u/with-context {:read-model/query query
                   :read-model/name name
                   :read-model/version version})
  (let [cache-key (format-scoped-key name version (if scope [tenant-id scope] tenant-id))
        query (inject-tenant-id query tenant-id)]
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
