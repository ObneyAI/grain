(ns ai.obney.grain.event-store-v3.interface.schemas
  (:require [ai.obney.grain.schema-util.interface :refer [register!]]
            [clj-uuid :as uuid]))

(defn- as-of-or-after [x] (not (and (:as-of x) (:after x))))

(defn- uuid-v7? [x] (and (uuid? x) (= 7 (uuid/get-version x))))

(defn- same-tenant-id?
  "Validates that all queries in a batch have the same :tenant-id."
  [queries]
  (apply = (map :tenant-id queries)))

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
   ::tenant-id :uuid

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
     [:tenant-id ::tenant-id]
     [:tags  {:optional true} ::tags]
     [:types {:optional true} ::types]
     [:as-of {:optional true}  ::id]
     [:after {:optional true} ::id]]]

   ::batch-read-args
   [:and
    [:vector {:min 1} ::single-read-args]
    [:fn {:error/message "All queries in a batch must have the same :tenant-id"}
     same-tenant-id?]]

   ::read-args
   [:or ::single-read-args ::batch-read-args]

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
    [:tenant-id ::tenant-id]
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
    [:metadata {:optional true} [:map]]]

   :grain/todo-processor-checkpoint
   [:map
    [:processor/name :keyword]
    [:triggered-by ::id]]

   :grain/todo-processor-effect-failure
   [:map
    [:processor/name :keyword]
    [:triggered-by ::id]
    [:error/message :string]]})
