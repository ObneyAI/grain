(ns ai.obney.grain.event-retention.events
  (:require [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.event-store-v3.interface.compaction :as compaction]))

(event-store/defevent compaction/policy-activated-type
  "A normalized bounded-history policy was explicitly activated."
  {:schema [:map
            [:retention/event-type :qualified-keyword]
            [:retention/policy map?]]})

(event-store/defevent compaction/policy-deactivated-type
  "A bounded-history policy activation was explicitly deactivated."
  {:schema [:map
            [:retention/event-type :qualified-keyword]
            [:retention/activation-id uuid?]
            [:retention/policy map?]]})

(event-store/defevent compaction/compaction-receipt-type
  "Permanent evidence naming every event deleted by one atomic compaction."
  {:schema [:map
            [:retention/activation-id uuid?]
            [:retention/event-type :qualified-keyword]
            [:retention/policy map?]
            [:retention/tenant-id uuid?]
            [:retention/cutoff [:time/offset-date-time]]
            [:retention/deleted-event-ids [:set {:min 1} uuid?]]]})

(defn ->activated [event-type policy]
  (event-store/->event
   {:type compaction/policy-activated-type
    :tags #{(compaction/policy-tag event-type)}
    :body {:retention/event-type event-type
           :retention/policy policy}}))

(defn ->deactivated [{:keys [event/type policy activation/id]}]
  (event-store/->event
   {:type compaction/policy-deactivated-type
    :tags #{(compaction/policy-tag type)}
    :body {:retention/event-type type
           :retention/activation-id id
           :retention/policy policy}}))
