(ns ai.obney.grain.event-retention.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.event-retention.interface :as retention]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.event-store-v3.interface.compaction :as compaction]
            [ai.obney.grain.time.interface :as time]
            [clj-uuid :as uuid])
  (:import [java.time OffsetDateTime ZoneOffset]))

(event-store/defevent :retention-test/ephemeral
  "Ephemeral test state"
  {:schema [:map [:value :int]]
   :history {:retain-at-least "P1D"}})

(event-store/defevent :retention-test/keyed
  "Per-node ephemeral test state"
  {:schema [:map [:value :int]]
   :history {:retain-at-least "P1D"
             :keep-latest-per {:tags #{:node}}}})

(def ^:dynamic *store*)
(def ^:dynamic *admin*)
(def ^:dynamic *tenant*)

(use-fixtures
  :each
  (fn [f]
    (let [store (event-store/start {:conn {:type :in-memory}})]
      (binding [*store* store
                *admin* (retention/administration store)
                *tenant* (uuid/v4)]
        (try (f) (finally (event-store/stop store)))))))

(defn append-at! [instant type tags value]
  (with-redefs [time/now (constantly instant)]
    (first (event-store/append
            *store*
            {:tenant-id *tenant*
             :events [(event-store/->event
                       {:type type :tags tags :body {:value value}})]}))))

(defn events-of [type]
  (into [] (event-store/read *store* {:tenant-id *tenant* :types #{type}})))

(deftest activation-is-explicit-durable-and-idempotent
  (is (nil? (retention/active-activation *store* :retention-test/ephemeral)))
  (let [first-activation (retention/activate! *admin* :retention-test/ephemeral)
        second-activation (retention/activate! *admin* :retention-test/ephemeral)]
    (is (= first-activation second-activation))
    (is (= (:history/normalized
            (event-store/event-definition :retention-test/ephemeral))
           (:policy first-activation)))
    (is (= 1 (count (into []
                          (event-store/read
                           *store*
                           {:tenant-id compaction/system-tenant-id
                            :types #{compaction/policy-activated-type}})))))
  (retention/deactivate! *admin* :retention-test/ephemeral)
  (is (nil? (retention/active-activation *store* :retention-test/ephemeral)))))

(deftest compaction-is-atomic-and-receipted
  (let [old (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 3)
        recent (OffsetDateTime/now ZoneOffset/UTC)
        deleted (append-at! old :retention-test/ephemeral #{} 1)
        retained (append-at! recent :retention-test/ephemeral #{} 2)]
    (retention/activate! *admin* :retention-test/ephemeral)
    (is (= 1 (:eligible-count
              (retention/estimate *admin* :retention-test/ephemeral *tenant* 10))))
    (let [receipt (retention/compact! *admin* :retention-test/ephemeral *tenant* 10)]
      (is (= #{(:event/id deleted)} (:retention/deleted-event-ids receipt)))
      (is (= [(:event/id retained)] (mapv :event/id
                                         (events-of :retention-test/ephemeral))))
      (is (= 1 (count (events-of compaction/compaction-receipt-type))))
      (is (some #(and (= :grain/tx (:event/type %))
                      (contains? (:event-ids %) (:event/id receipt)))
                (into [] (event-store/read *store* {:tenant-id *tenant*})))))))

(deftest keyed-policy-preserves-newest-and-fails-closed-on-malformed-tags
  (let [old (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 3)
        node-id (uuid/v4)
        first-event (append-at! old :retention-test/keyed #{[:node node-id]} 1)
        newest-event (append-at! (.plusSeconds old 1)
                                 :retention-test/keyed #{[:node node-id]} 2)
        malformed (append-at! old :retention-test/keyed #{} 3)]
    (testing "activation preflight rejects malformed stored keys"
      (let [assessment (retention/assess *admin* :retention-test/keyed)]
        (is (false? (:safe? assessment)))
        (is (= (:event/id malformed) (:event/id (first (:findings assessment)))))))
    (testing "pure backend selection is conservative"
      (let [policy (:history/normalized
                    (event-store/event-definition :retention-test/keyed))
            eligible (compaction/eligible-events
                      [first-event newest-event malformed]
                      :retention-test/keyed policy (OffsetDateTime/now ZoneOffset/UTC) 10)]
        (is (= [(:event/id first-event)] (mapv :event/id eligible)))))))

(deftest boot-guard-detects-loaded-policy-drift
  (retention/activate! *admin* :retention-test/ephemeral)
  (is (= {:valid? true :findings []}
         (retention/verify-at-boot! *admin*)))
  (with-redefs [event-store/event-definition
                (fn [event-type]
                  (when (= event-type :retention-test/ephemeral)
                    {:event/type event-type
                     :history/normalized {:retain-at-least {:seconds 1 :nanos 0}}}))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boot guard"
          (retention/verify-at-boot! *admin*)))))

(deftest injected-clock-compresses-live-retention-time
  (let [recorded-at (OffsetDateTime/now ZoneOffset/UTC)
        clock* (atom recorded-at)
        admin (retention/administration *store* {:clock #(deref clock*)})
        first-event (append-at! recorded-at :retention-test/ephemeral #{} 1)
        second-event (append-at! (.plusSeconds recorded-at 1)
                                 :retention-test/ephemeral #{} 2)]
    (retention/activate! admin :retention-test/ephemeral)
    (is (zero? (:eligible-count
                (retention/estimate admin :retention-test/ephemeral *tenant* 10))))
    (reset! clock* (.plusDays recorded-at 2))
    (is (= 2 (:eligible-count
              (retention/estimate admin :retention-test/ephemeral *tenant* 10))))
    (let [receipt (retention/compact! admin :retention-test/ephemeral *tenant* 10)]
      (is (= #{(:event/id first-event) (:event/id second-event)}
             (:retention/deleted-event-ids receipt))))))
