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
  (ref {:events [] :tenants {}}))

(defn stop
  [state]
  (dosync (ref-set state nil)))

(defn- matches-tenant? [tenant-id event]
  (= tenant-id (:grain/tenant-id event)))

(defn- strip-tenant-id [event]
  (dissoc event :grain/tenant-id))

(defn- compare-event-ids [a b]
  (cond (uuid/< a b) -1
        (uuid/= a b) 0
        :else 1))

(defn- read-single
  [event-store {:keys [tenant-id tags types as-of after reverse? limit] :as args}]
  (let [filtered-events (cond->> (->> (-> event-store :state deref :events)
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
                                      (map strip-tenant-id)
                                      (sort-by :event/id compare-event-ids))
                          reverse? reverse
                          limit    (take limit))]
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
                    (sort-by :event/id compare-event-ids))]
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

(defn- strictly-increasing-after?
  [last-id events]
  (reduce (fn [previous event]
            (let [event-id (:event/id event)]
              (if (and event-id
                       (or (nil? previous) (uuid/< previous event-id)))
                event-id
                (reduced false))))
          last-id
          events))

(defn- assign-commit-ordered-ids
  [last-id events]
  (if (strictly-increasing-after? last-id events)
    events
    (loop [remaining events previous last-id assigned []]
      (if-let [event (first remaining)]
        (let [event-id (loop [candidate (uuid/v7)]
                         (if (or (nil? previous) (uuid/< previous candidate))
                           candidate
                           (recur (uuid/v7))))]
          (recur (next remaining) event-id
                 (conj assigned (assoc event :event/id event-id))))
        assigned))))

(defn append
  [event-store {{:keys [predicate-fn] :as cas} :cas
                :keys [tenant-id events tx-metadata]}]
  (u/trace
   ::append
   [:grain/event-ids (map :event/id events)
    :metric/name "GrainAppendEvents"]
   (dosync
    (let [last-id (get-in @(:state event-store)
                          [:tenants tenant-id :tenant/last-event-id])
          events (assign-commit-ordered-ids last-id events)
          tx (first (assign-commit-ordered-ids
                     (:event/id (last events))
                     [(->event
                       {:type :grain/tx
                        :body {:event-ids (set (mapv :event/id events))
                               :metadata tx-metadata}})]))]
      (if cas
        (let [events* (read event-store (assoc cas :tenant-id tenant-id))
              pred-result (predicate-fn events*)]
          (if pred-result
            (do
              (alter (:state event-store)
                     (fn [s]
                       (-> s
                           (update :events into (tag-events-with-tenant tenant-id events))
                           (assoc-in [:tenants tenant-id :tenant/last-event-id]
                                     (:event/id (last events))))))
              events)
            (let [anomaly {::anom/category ::anom/conflict
                           ::anom/message "CAS failed"
                           :cas cas}]
              (u/log :grain/cas-failed :anomaly anomaly)
              anomaly)))
        (do
          (alter (:state event-store)
                 (fn [s]
                   (-> s
                       (update :events into (tag-events-with-tenant tenant-id (conj events tx)))
                       (assoc-in [:tenants tenant-id :tenant/last-event-id]
                                 (:event/id tx)))))
          events))))))

(defn tenants
  [event-store]
  (-> event-store :state deref :tenants))

(defrecord InMemoryEventStore [config]
  p/EventStore

  (start [this]
    (assoc this :state (start config)))

  (stop [this]
    (stop (:state this))
    (dissoc this :state))

  (tenants [this]
    (tenants this))

  (append [this args]
    (append this args))

  (read [this args]
    (read this args)))

(defmethod p/start-event-store :in-memory
  [config]
  (p/start (->InMemoryEventStore config)))
