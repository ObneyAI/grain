(ns ai.obney.grain.event-store-v2.core.in-memory
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v2.interface.protocol :as p]
            [ai.obney.grain.event-store-v2.core :refer [->event]]
            [cognitect.anomalies :as anom]
            [clojure.set :as set]
            #?@(:clj [[com.brunobonacci.mulog :as u]
                      [clj-uuid :as uuid]]
                :cljs [["uuid" :as uuid]])))

(defn start
  [_config]
  #?(:clj (ref {:events []})
     :cljs (atom {:events []})))

(defn stop
  [state]
  #?(:clj (dosync (ref-set state nil))
     :cljs (reset! state nil)))

#?(:clj (defn- read-single
          [event-store {:keys [tags types as-of after] :as args}]
          (u/trace
           ::read
           [:args args
            :metric/name "GrainReadEvents"]
           (let [filtered-events (->> (-> event-store :state deref :events)
                                      (filter
                                       (fn [event]
                                         (and
                                          (or (not tags)
                                              (set/subset? tags (:event/tags event)))
                                          (or (not types)
                                              (contains? types (:event/type event)))
                                          (cond
                                            as-of (or (uuid/< (:event/id event) as-of)
                                                      (uuid/= (:event/id event) as-of))
                                            after (uuid/> (:event/id event) after)
                                            :else true)))))]
             (reify
               ;; Support streaming reduction with init value
               clojure.lang.IReduceInit
               (reduce [_ f init]
                 (reduce f init filtered-events))
               ;; Support streaming reduction without init value
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
                     (f)  ; Empty collection case
                     reduced-result)))))))

   :cljs (defn- read-single
           [event-store {:keys [tags types as-of after] :as _args}]
           (let [filtered-events (->> (-> event-store :state deref :events)
                                      (filter
                                       (fn [event]
                                         (and
                                          (or (not tags)
                                              (set/subset? tags (:event/tags event)))
                                          (or (not types)
                                              (contains? types (:event/type event)))
                                          (cond
                                            as-of (or (< (:event/id event) as-of)
                                                      (= (:event/id event) as-of))
                                            after (> (:event/id event) after)
                                            :else true)))))]
             (reify
               cljs.core/IReduce
               (-reduce [_ f]
                 (let [reduced-result
                       (reduce
                        (fn [acc event]
                          (if (= acc ::none)
                            event
                            (f acc event)))
                        ::none
                        filtered-events)]
                   (if (= reduced-result ::none)
                     (f)  ; Empty collection case
                     reduced-result)))
               (-reduce [_ f init]
                 (reduce f init filtered-events))))))

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
                    (sort-by :event/id #?(:clj (fn [a b]
                                                (cond (uuid/< a b) -1
                                                      (uuid/= a b) 0
                                                      :else 1))
                                          :cljs compare)))]
    #?(:clj (reify
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
                    reduced-result))))
       :cljs (reify
               cljs.core/IReduce
               (-reduce [_ f]
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
                     reduced-result)))
               (-reduce [_ f init]
                 (reduce f init merged))))))

(defn read
  [event-store args]
  (if (vector? args)
    (read-batch event-store args)
    (read-single event-store args)))

#?(:clj (defn append
          [event-store {{:keys [predicate-fn] :as cas} :cas
                        :keys [events tx-metadata]}]
          (u/trace
           ::append
           [:grain/event-ids (map :event/id events)
            :metric/name "GrainAppendEvents"]
           (let [tx (->event
                     {:type :grain/tx
                      :body {:event-ids (set (mapv :event/id events))
                             :metadata tx-metadata}})]
             (dosync
              (if cas
                (let [events* (read event-store cas)
                      pred-result (predicate-fn events*)]
                  (if pred-result
                    (alter (:state event-store) update :events into events)
                    (let [anomaly {::anom/category ::anom/conflict
                                   ::anom/message "CAS failed"
                                   :cas cas}]
                      (u/log :grain/cas-failed :anomaly anomaly)
                      anomaly)))
                (alter (:state event-store) update :events into (conj events tx)))))))
   
   :cljs (defn append
           [event-store {{:keys [predicate-fn] :as cas} :cas
                         :keys [events tx-metadata]}]
           (let [tx (->event
                     {:type :grain/tx
                      :body {:event-ids (set (mapv :event/id events))
                             :metadata tx-metadata}})]
             (if cas
               (let [events* (read event-store cas)
                     pred-result (predicate-fn events*)]
                 (if pred-result
                   (swap! (:state event-store) update :events into events)
                   (let [anomaly {::anom/category ::anom/conflict
                                  ::anom/message "CAS failed"
                                  :cas cas}]
                     anomaly)))
               (swap! (:state event-store) update :events into (conj events tx))))))

  (defrecord InMemoryEventStore [config]
    p/EventStore

    (start [this]
      (assoc this :state (start config)))

    (stop [this]
      (stop (:state this))
      (dissoc this :state))

    (append [this args]
      (append this args))

    (read [this args]
      (read this args)))

  (defmethod p/start-event-store :in-memory
    [config]
    (p/start (->InMemoryEventStore config)))