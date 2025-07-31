(ns ai.obney.grain.event-store-v2.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.event-store-v2.interface.schemas :as schemas]
            [ai.obney.grain.event-store-v2.interface.protocol :as p :refer [start-event-store]]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.time.interface :as time]
            [malli.core :as mc]
            [cognitect.anomalies :as anom]
            #?@(:clj [[clj-uuid :as uuid]
                      [com.brunobonacci.mulog :as u]]
                :cljs [[cljs.core :refer [ExceptionInfo]]
                       ["uuid" :refer [v7]]]))
  #?(:clj (:import [clojure.lang ExceptionInfo])))

(defmethod start-event-store :default
  [{{:keys [type]} :conn}]
  (throw (ex-info (str "Unsupported event store type: " type) {:type type})))

(defn start
  [config]
  (assoc-in (start-event-store config)
            [:config :event-pubsub]
            (:event-pubsub config)))

(defn stop
  [event-store]
  (p/stop event-store))

(defn append
  [{{:keys [event-pubsub]} :config
    :as event-store}
   {:keys [events] :as args}]
  (let [validation-errors
        (or

         ;; Invalid arguments
         (when-let [validation-error (mc/explain ::schemas/append-args args)]
           {::anom/category ::anom/incorrect
            ::anom/message "Invalid arguments"
            :explain/data validation-error})

         ;; Schema validation issues
         (try (->> events
                   (mapv #(mc/explain [:and ::schemas/event (:event/type %)] %))
                   (filterv (complement nil?)))
              (catch ExceptionInfo _
                {::anom/category ::anom/fault
                 ::anom/message "One or more event schemas are not defined for :event/type"
                 ::event-names (set (map :event/name events))})))]
    (cond
      (anomaly? validation-errors)
      validation-errors

      (seq validation-errors)
      (do
        #?(:clj (u/log ::validation-errors :validation-errors validation-errors))
        {::anom/category ::anom/fault
         ::anom/message "Invalid Event(s): Failed Schema Validation"
         :error/explain validation-errors})

      :else
      (let [result (p/append event-store args)]
        (if (anomaly? result)
          result
          (when event-pubsub
            (run! #(pubsub/pub event-pubsub {:message %}) events)))))))

(defn read
  [event-store args]
  (if-let [validation-error (mc/explain ::schemas/read-args args)]
    {::anom/category ::anom/incorrect
     ::anom/message "Invalid arguments"
     :explain/data validation-error}
    (p/read event-store args)))

(defn ->event
  [{:keys [type body tags] :or {tags #{}} :as args}]
  (if-let [validation-error (mc/explain ::schemas/->event-args args)]
    {::anom/category ::anom/incorrect
     ::anom/message "Invalid arguments"
     :explain/data validation-error}
    (merge
     {:event/id #?(:clj (uuid/v7)
                   :cljs (uuid (v7)))
      :event/timestamp (time/now)
      :event/type type
      :event/tags tags}
     body)))

(comment
  

  
  
  "")