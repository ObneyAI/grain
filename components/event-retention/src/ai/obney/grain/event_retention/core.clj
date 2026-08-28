(ns ai.obney.grain.event-retention.core
  (:require [ai.obney.grain.event-retention.events :as events]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.event-store-v3.interface.compaction :as compaction]
            [ai.obney.grain.time.interface :as time]
            [cognitect.anomalies :as anom]
            [malli.core :as m])
  (:import [java.time Clock OffsetDateTime ZoneOffset]))

(defn- retention-now [{:keys [clock]}]
  (cond
    (nil? clock) (time/now)
    (instance? Clock clock) (OffsetDateTime/ofInstant (.instant ^Clock clock)
                                                     ZoneOffset/UTC)
    (fn? clock) (clock)
    :else (throw (ex-info "Retention clock must be a java.time.Clock or function"
                          {:clock clock}))))

(defn- definition! [event-type]
  (let [definition (event-store/event-definition event-type)]
    (when-not definition
      (throw (ex-info "Event type has no registered definition"
                      {:event/type event-type})))
    (when-not (:history/normalized definition)
      (throw (ex-info "Event type promises complete history"
                      {:event/type event-type})))
    (when (contains? compaction/protected-event-types event-type)
      (throw (ex-info "Protected event types cannot have bounded history"
                      {:event/type event-type})))
    definition))

(defn lifecycle-events [store event-type]
  (into []
        (event-store/read store
                          {:tenant-id compaction/system-tenant-id
                           :tags #{(compaction/policy-tag event-type)}
                           :types #{compaction/policy-activated-type
                                    compaction/policy-deactivated-type}})))

(defn active-activation [store event-type]
  (compaction/active-activation (lifecycle-events store event-type) event-type))

(defn- malformed-retention-key? [event tag-names]
  (and tag-names (nil? (compaction/retention-key event tag-names))))

(defn assess
  "Inspect existing stored events for schema and retention-key violations.
   The optional consumer-assessment function supplies findings from the event
   model validator for declared processors/projections."
  [{:keys [event-store consumer-assessment]} event-type]
  (let [{:keys [schema history/normalized]} (definition! event-type)
        tag-names (get-in normalized [:keep-latest-per :tags])
        stored-findings
        (mapcat
         (fn [tenant-id]
           (keep (fn [event]
                   (cond
                     (not (m/validate schema event))
                     {:type :stored-event/schema-mismatch
                      :event/id (:event/id event)
                      :tenant-id tenant-id}

                     (malformed-retention-key? event tag-names)
                     {:type :stored-event/malformed-retention-key
                      :event/id (:event/id event)
                      :tenant-id tenant-id}))
                 (into [] (event-store/read event-store
                                            {:tenant-id tenant-id
                                             :types #{event-type}}))))
         (keys (event-store/tenants event-store)))
        consumer-findings (if consumer-assessment
                            (or (consumer-assessment event-type normalized) [])
                            [])
        findings (vec (concat stored-findings consumer-findings))]
    {:safe? (empty? findings) :findings findings}))

(defn activate!
  "Explicitly activate the currently loaded normalized policy. The caller does
   not supply policy data. Equal activation is idempotent by value."
  [{:keys [event-store] :as admin} event-type]
  (let [policy (:history/normalized (definition! event-type))
        assessment (assess admin event-type)]
    (when-not (:safe? assessment)
      (throw (ex-info "Retention activation preflight failed" assessment)))
    (loop []
      (if-let [active (active-activation event-store event-type)]
        (if (= policy (:policy active))
          active
          (throw (ex-info "A different policy is active; deactivate it first"
                          {:event/type event-type :active active :loaded policy})))
        (let [result (event-store/append
                      event-store
                      {:tenant-id compaction/system-tenant-id
                       :events [(events/->activated event-type policy)]
                       :tx-metadata {:grain/operation :retention-activation}
                       :cas {:tags #{(compaction/policy-tag event-type)}
                             :types #{compaction/policy-activated-type
                                      compaction/policy-deactivated-type}
                             :predicate-fn
                             #(nil? (compaction/active-activation % event-type))}})]
          (if (= ::anom/conflict (::anom/category result))
            (recur)
            (let [persisted (first result)]
              {:event/type event-type
               :policy policy
               :activation/id (:event/id persisted)
               :activated-at (:event/timestamp persisted)})))))))

(defn deactivate!
  [admin event-type]
  (let [store (:event-store admin)]
    (loop []
      (if-let [active (active-activation store event-type)]
        (let [result (event-store/append
                      store
                      {:tenant-id compaction/system-tenant-id
                       :events [(events/->deactivated active)]
                       :tx-metadata {:grain/operation :retention-deactivation}
                       :cas {:tags #{(compaction/policy-tag event-type)}
                             :types #{compaction/policy-activated-type
                                      compaction/policy-deactivated-type}
                             :predicate-fn
                             #(= active
                                 (compaction/active-activation % event-type))}})]
          (if (= ::anom/conflict (::anom/category result))
            (recur)
            (assoc active :active? false)))
        nil))))

(defn estimate [admin event-type tenant-id limit]
  (let [activation (active-activation (:event-store admin) event-type)]
    (when-not activation
      (throw (ex-info "Retention policy is not active" {:event/type event-type})))
    (compaction/estimate (:event-store admin)
                         {:activation activation
                          :tenant-id tenant-id
                          :limit limit
                          :evaluated-at (retention-now admin)})))

(defn compact! [admin event-type tenant-id limit]
  (let [definition (definition! event-type)
        activation (active-activation (:event-store admin) event-type)]
    (when-not (and activation
                   (= (:history/normalized definition) (:policy activation)))
      (throw (ex-info "Loaded and active retention policies do not match"
                      {:event/type event-type})))
    (compaction/compact! (:event-store admin)
                         {:activation activation
                          :tenant-id tenant-id
                          :limit limit
                          :evaluated-at (retention-now admin)})))

(defn status
  "Report durable availability facts for one tenant and event type."
  [{:keys [event-store]} event-type tenant-id]
  (let [active (active-activation event-store event-type)
        retained (into [] (event-store/read event-store
                                            {:tenant-id tenant-id
                                             :types #{event-type}
                                             :limit 1}))
        receipts (into [] (event-store/read
                           event-store
                           {:tenant-id tenant-id
                            :types #{compaction/compaction-receipt-type}
                            :tags #{(compaction/policy-tag event-type)}
                            :reverse? true
                            :limit 1}))
        relevant-receipts (filter #(= event-type (:retention/event-type %)) receipts)
        latest-receipt (first relevant-receipts)]
    {:event/type event-type
     :tenant-id tenant-id
     :active-activation active
     :earliest-retained (select-keys (first retained)
                                     [:event/id :event/timestamp])
     :latest-compaction (when latest-receipt
                          {:event/id (:event/id latest-receipt)
                           :event/timestamp (:event/timestamp latest-receipt)
                           :deleted-count (count (:retention/deleted-event-ids
                                                 latest-receipt))})
     :history-truncated? (boolean latest-receipt)}))

(defn verify-at-boot!
  "Fail closed when durable active policies do not exactly match loaded bounded
   definitions. This is registry reconciliation only; it never scans tenants."
  [{:keys [event-store]}]
  (let [lifecycle (into []
                        (event-store/read
                         event-store
                         {:tenant-id compaction/system-tenant-id
                          :types #{compaction/policy-activated-type
                                   compaction/policy-deactivated-type}}))
        active-types (->> lifecycle
                          (map :retention/event-type)
                          set)
        findings (keep (fn [event-type]
                         (when-let [active (compaction/active-activation lifecycle event-type)]
                           (let [loaded (:history/normalized
                                         (event-store/event-definition event-type))]
                             (when (not= loaded (:policy active))
                               {:type :active-policy/loaded-definition-mismatch
                                :event/type event-type
                                :active (:policy active)
                                :loaded loaded}))))
                       active-types)]
    (when (seq findings)
      (throw (ex-info "Retention boot guard failed" {:findings (vec findings)})))
    {:valid? true :findings []}))
