(ns ai.obney.grain.event-store-v3.interface.schemas
  (:require [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.schema-util.interface :refer [register!]]
            [ai.obney.grain.event-store-v3.interface.event-definition :as definitions]
            [clj-uuid :as uuid]))

(defn- as-of-or-after [x] (not (and (:as-of x) (:after x))))

(defn- uuid-v7? [x] (and (uuid? x) (= 7 (uuid/get-version x))))

(defn- no-persistence-metadata? [event]
  (not (or (contains? event :event/id)
           (contains? event :event/timestamp))))

(defn- body-has-no-persistence-metadata? [{:keys [body]}]
  (not (or (contains? body :event/id)
           (contains? body :event/timestamp))))

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

   ::appendable-event
   [:and
    [:fn {:error/message "Event IDs and timestamps are assigned by append"}
     no-persistence-metadata?]
    [:map
     [:event/tags ::tags]
     [:event/type ::type]]]

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
     [:after {:optional true} ::id]
     [:reverse? {:optional true} :boolean]
     [:limit {:optional true} pos-int?]]]

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
     [:reverse? {:optional true} :boolean]
     [:limit {:optional true} pos-int?]
     [:predicate-fn fn?]]]

   ::append-args
   [:map
    [:tenant-id ::tenant-id]
    [:events [:vector ::appendable-event]]
    [:tx-metadata {:optional true} [:map]]
    [:cas {:optional true} ::cas]]

   ::->event-args
   [:and
    [:fn {:error/message "Event body cannot supply persistence metadata"}
     body-has-no-persistence-metadata?]
    [:map
     [:type ::type]
     [:tags {:optional true} ::tags]
     [:body {:optional true} [:map]]]]})

(definitions/defevent :grain/tx
  "The reified transaction containing the exact event IDs committed atomically."
  {:schema [:map
            [:event-ids [:set ::id]]
            [:metadata {:optional true} [:map]]]})

(definitions/defevent :grain/todo-processor-checkpoint
  "A todo processor durably recorded progress through a tenant event stream."
  {:schema [:map
            [:processor/name :keyword]
            [:triggered-by ::id]
            [:checkpoint/kind {:optional true} :keyword]
            [:checkpoint/from {:optional true} ::id]]
   :history {:retain-at-least "PT1H"
             :keep-latest-per {:tags #{:processor}}}})

(definitions/defevent :grain/todo-processor-effect-failure
  "A todo processor effect failed while handling a triggering event."
  {:schema [:map
            [:processor/name :keyword]
            [:triggered-by ::id]
            [:error/message :string]]})

(event-model/defeventmodel :grain
  {:description "Grain event-store infrastructure."
   :events
   {:grain/tx
    {:description "The reified transaction containing committed event IDs."
     :schema [:map
              [:event-ids [:set ::id]]
              [:metadata {:optional true} [:map]]]}
    :grain/todo-processor-checkpoint
    {:description "A todo processor durably recorded stream progress."
     :schema [:map
              [:processor/name :keyword]
              [:triggered-by ::id]
              [:checkpoint/kind {:optional true} :keyword]
              [:checkpoint/from {:optional true} ::id]]}
    :grain/todo-processor-effect-failure
    {:description "A todo processor effect failed."
     :schema [:map
              [:processor/name :keyword]
              [:triggered-by ::id]
              [:error/message :string]]}}})
