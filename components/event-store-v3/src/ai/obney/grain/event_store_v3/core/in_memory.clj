(ns ai.obney.grain.event-store-v3.core.in-memory
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v3.interface.protocol :as p]
            [ai.obney.grain.event-store-v3.core :refer [->event]]
            [cognitect.anomalies :as anom]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as u]
            [clj-uuid :as uuid]))

(defn start
  [_config]
  (ref {:events [] :tenants #{}}))

(defn stop
  [state]
  (dosync (ref-set state nil)))

(defn- matches-tenant? [tenant-id event]
  (= tenant-id (:grain/tenant-id event)))

(defn- strip-tenant-id [event]
  (dissoc event :grain/tenant-id))

(defn- read-single
  [event-store {:keys [tenant-id tags types as-of after] :as args}]
  (let [filtered-events (->> (-> event-store :state deref :events)
                              (filter
                               (fn [event]
                                 (and
                                  (matches-tenant? tenant-id event)
                                  (or (not tags)
                                      (set/subset? tags (:event/tags event)))
                                  (or (not types)
                                      (contains? types (:event/type event)))
                                  (cond
                                    as-of (or (uuid/< (:event/id event) as-of)
                                              (uuid/= (:event/id event) as-of))
                                    after (uuid/> (:event/id event) after)
                                    :else true))))
                              (map strip-tenant-id))]
     (reify
       clojure.lang.IReduceInit
       (reduce [_ f init]
         (reduce f init filtered-events))
       clojure.lang.IReduce
       (reduce [_ f]
         (let [reduced-result
               (reduce
                (fn [acc event]
                  (if (= acc ::none)
                    event
                    (f acc event)))
                ::none
                filtered-events)]
           (if (= reduced-result ::none)
             (f)
             reduced-result))))))

(defn- read-batch
  [event-store queries]
  (let [merged (->> queries
                    (mapcat #(into [] (read-single event-store %)))
                    (reduce (fn [acc event]
                              (if (contains? (::seen acc) (:event/id event))
                                acc
                                (-> acc
                                    (update ::seen conj (:event/id event))
                                    (update ::events conj event))))
                            {::seen #{} ::events []})
                    ::events
                    (sort-by :event/id (fn [a b]
                                         (cond (uuid/< a b) -1
                                               (uuid/= a b) 0
                                               :else 1))))]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init]
        (reduce f init merged))
      clojure.lang.IReduce
      (reduce [_ f]
        (let [reduced-result
              (reduce
               (fn [acc event]
                 (if (= acc ::none)
                   event
                   (f acc event)))
               ::none
               merged)]
          (if (= reduced-result ::none)
            (f)
            reduced-result))))))

(defn read
  [event-store args]
  (if (vector? args)
    (read-batch event-store args)
    (read-single event-store args)))

(defn- tag-events-with-tenant [tenant-id events]
  (mapv #(assoc % :grain/tenant-id tenant-id) events))

(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [tenant-id events tx-metadata]}]
  (u/trace
   ::append
   [:grain/event-ids (map :event/id events)
    :metric/name "GrainAppendEvents"]
   (let [tx (->event
             {:type :grain/tx
              :body {:event-ids (set (mapv :event/id events))
                     :metadata tx-metadata}})]
     (dosync
      (alter (:state event-store) update :tenants conj tenant-id)
      (if cas
        (let [events* (read event-store (assoc cas :tenant-id tenant-id))
              pred-result (predicate-fn events*)]
          (if pred-result
            (alter (:state event-store) update :events into
                   (tag-events-with-tenant tenant-id events))
            (let [anomaly {::anom/category ::anom/conflict
                           ::anom/message "CAS failed"
                           :cas cas}]
              (u/log :grain/cas-failed :anomaly anomaly)
              anomaly)))
        (alter (:state event-store) update :events into
               (tag-events-with-tenant tenant-id (conj events tx))))))))

(defn tenant-ids
  [event-store]
  (-> event-store :state deref :tenants))

(defrecord InMemoryEventStore [config]
  p/EventStore

  (start [this]
    (assoc this :state (start config)))

  (stop [this]
    (stop (:state this))
    (dissoc this :state))

  (tenant-ids [this]
    (tenant-ids this))

  (append [this args]
    (append this args))

  (read [this args]
    (read this args)))

(defmethod p/start-event-store :in-memory
  [config]
  (p/start (->InMemoryEventStore config)))
