(ns ai.obney.grain.event-store-v2.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [register!]]
            #?@(:clj  [[clj-uuid :as uuid]]
                :cljs [["uuid" :as uuid*]])))

(defn- as-of-or-after [x] (not (and (:as-of x) (:after x))))

#?(:clj (defn- uuid-v7? [x] (and (uuid? x) (= 7 (uuid/get-version x))))
   :cljs (defn uuid-v7? [x]
           (let [x* (str x)]
             (boolean
              (and (uuid*/validate x*)
                   (= (uuid*/version x*) 7))))))

(register!
  {::entity-type :keyword
   ::entity-id :uuid
   ::tag [:tuple ::entity-type ::entity-id]
   ::tags [:set ::tag]
   ::type :keyword
   ::types [:set ::type]
   ::uuid-v7 [:fn
              {:error/message "Must be UUID v7"}
              uuid-v7?]
   ::id ::uuid-v7
   ::timestamp [:time/offset-date-time]

   ::event [:map
            [:event/id ::id]
            [:event/timestamp ::timestamp]
            [:event/tags ::tags]
            [:event/type ::type]]

   ::as-of-or-after
   [:fn {:error/message "Cannot supply both :as-of and :after"} as-of-or-after]

   ::single-read-args
   [:and
    ::as-of-or-after
    [:map
     [:tags  {:optional true} ::tags]
     [:types {:optional true} ::types]
     [:as-of {:optional true}  ::id]
     [:after {:optional true} ::id]]]

   ::read-args
   [:or ::single-read-args [:vector {:min 1} ::single-read-args]]

   ::cas
   [:and
    ::as-of-or-after
    [:map
     [:tags  {:optional true} ::tags]
     [:types {:optional true} ::types]
     [:as-of {:optional true} ::id]
     [:after {:optional true} ::id]
     [:predicate-fn fn?]]]

   ::append-args
   [:map
    [:events [:vector ::event]]
    [:tx-metadata {:optional true} [:map]]
    [:cas {:optional true} ::cas]]

   ::->event-args
   [:map
    [:type ::type]
    [:tags {:optional true} ::tags]
    [:body {:optional true} [:map]]]

   :grain/tx
   [:map
    [:event-ids [:set ::id]]
    [:metadata {:optional true} [:map]]]})
