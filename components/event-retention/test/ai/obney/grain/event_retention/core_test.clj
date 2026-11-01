(ns ai.obney.grain.event-retention.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.event-retention.interface :as retention]
            [ai.obney.grain.event-store-v3.interface :as event-store]
            [ai.obney.grain.event-store-v3.interface.backend :as backend]
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

(event-store/defevent :retention-test/permanent
  "Permanent test fact"
  {:schema [:map [:value :int]]})

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
    (let [estimate (retention/estimate *admin* :retention-test/ephemeral *tenant* 10)
          receipt (retention/compact! *admin* :retention-test/ephemeral *tenant* 10)]
      (is (= [(:event/id deleted)] (:eligible-event-ids estimate)))
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

(deftest retention-removes-transactions-only-after-the-last-member
  (let [old (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 3)
        admin (retention/administration *store*)
        other-tenant (uuid/v4)
        node (uuid/v4)
        event (fn [type tags value]
                (event-store/->event {:type type :tags tags :body {:value value}}))
        append! (fn [tenant events]
                  (with-redefs [time/now (constantly old)]
                    (event-store/append *store* {:tenant-id tenant :events events
                                          :tx-metadata {:source "retention-test"}})))
        read-all (fn [tenant]
                   (into [] (event-store/read *store* {:tenant-id tenant})))
        _ (append! *tenant* [])
        _ (append! *tenant* [(event :retention-test/ephemeral #{} 0)
                              (event :retention-test/permanent #{} 0)])
        members (append! *tenant* [(event :retention-test/ephemeral #{} 1)
                                   (event :retention-test/ephemeral #{} 2)
                                   (event :retention-test/keyed #{[:node node]} 3)])
        marker (last (read-all *tenant*))
        _ (append! *tenant* [(event :retention-test/keyed #{[:node node]} 4)])
        _ (append! other-tenant [(event :retention-test/ephemeral #{} 5)])
        original-markers (filterv #(= :grain/tx (:event/type %)) (read-all *tenant*))
        other-before (read-all other-tenant)]
    (retention/activate! admin :retention-test/ephemeral)
    (retention/activate! admin :retention-test/keyed)
    (let [system-before (read-all compaction/system-tenant-id)]
      (testing "partial deletion preserves the original markers and metadata"
        (dotimes [_ 3]
          (is (zero? (:eligible-transaction-count
                      (retention/estimate admin :retention-test/ephemeral *tenant* 1))))
          (is (= #{} (:retention/deleted-transaction-ids
                       (retention/compact! admin :retention-test/ephemeral *tenant* 1))))
          (is (every? (set (read-all *tenant*)) original-markers))))
      (testing "another policy removes the last member and its marker together"
        (let [estimate (retention/estimate admin :retention-test/keyed *tenant* 1)
              receipt (retention/compact! admin :retention-test/keyed *tenant* 1)
              remaining (read-all *tenant*)
              receipt-tx (last remaining)]
          (is (= [(:event/id marker)] (:eligible-transaction-ids estimate)))
          (is (= 1 (:eligible-transaction-count estimate)))
          (is (= #{(:event/id (last members))} (:retention/deleted-event-ids receipt)))
          (is (= #{(:event/id marker)} (:retention/deleted-transaction-ids receipt)))
          (is (not-any? #(= (:event/id marker) (:event/id %)) remaining))
          (is (every? (set remaining) (remove #{marker} original-markers)))
          (is (= #{(:event/id receipt)} (:event-ids receipt-tx)))
          (is (= (:event/id receipt-tx) (event-store/head *store* *tenant*)))
          (is (= 1 (get-in (retention/status admin :retention-test/keyed *tenant*)
                           [:latest-compaction :deleted-transaction-count])))
          (is (nil? (retention/compact! admin :retention-test/keyed *tenant* 1)))
          (is (= remaining (read-all *tenant*)))
          ;; Every permanent receipt, including earlier batches, retains its tx.
          (doseq [r (filter #(= compaction/compaction-receipt-type (:event/type %)) remaining)]
            (is (some #(contains? (:event-ids %) (:event/id r)) remaining)))))
      (is (= other-before (read-all other-tenant)))
      (is (= system-before (read-all compaction/system-tenant-id))))))

(deftest retention-failure-preserves-events-markers-and-watermark
  (let [old (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 3)
        admin (retention/administration *store*)]
    (with-redefs [time/now (constantly old)]
      (event-store/append *store*
                   {:tenant-id *tenant*
                    :events [(event-store/->event {:type :retention-test/ephemeral :body {:value 1}})]}))
    (retention/activate! admin :retention-test/ephemeral)
    (let [before (into [] (event-store/read *store* {:tenant-id *tenant*}))
          head (event-store/head *store* *tenant*)]
      (with-redefs [backend/prepare-append (fn [& _] (throw (ex-info "injected receipt failure" {})))]
        (is (thrown-with-msg? Exception #"injected receipt failure"
              (retention/compact! admin :retention-test/ephemeral *tenant* 10))))
      (is (= before (into [] (event-store/read *store* {:tenant-id *tenant*}))))
      (is (= head (event-store/head *store* *tenant*)))
      (let [receipt (retention/compact! admin :retention-test/ephemeral *tenant* 10)]
        (is (= #{(:event/id (last before))} (:retention/deleted-transaction-ids receipt)))
        (is (= 2 (count (into [] (event-store/read *store* {:tenant-id *tenant*})))))))))

(deftest retention-removes-a-shared-marker-once-per-batch
  (let [old (.minusDays (OffsetDateTime/now ZoneOffset/UTC) 3)
        admin (retention/administration *store*)
        members (with-redefs [time/now (constantly old)]
                  (event-store/append *store*
                               {:tenant-id *tenant*
                                :events (mapv (fn [n]
                                                (event-store/->event {:type :retention-test/ephemeral
                                                                :body {:value n}}))
                                              (range 3))}))
        marker-id (event-store/head *store* *tenant*)]
    (retention/activate! admin :retention-test/ephemeral)
    (let [estimate (retention/estimate admin :retention-test/ephemeral *tenant* 3)
          receipt (retention/compact! admin :retention-test/ephemeral *tenant* 3)
          remaining (into [] (event-store/read *store* {:tenant-id *tenant*}))]
      (is (= 3 (:eligible-count estimate)))
      (is (= 1 (:eligible-transaction-count estimate)))
      (is (= [marker-id] (:eligible-transaction-ids estimate)))
      (is (= (set (map :event/id members)) (:retention/deleted-event-ids receipt)))
      (is (= #{marker-id} (:retention/deleted-transaction-ids receipt)))
      (is (= [compaction/compaction-receipt-type :grain/tx] (mapv :event/type remaining)))
      (is (uuid/> (event-store/head *store* *tenant*) marker-id)))))

(deftest transaction-ownership-must-be-proven-before-compaction
  (let [event-id (uuid/v7)]
    (doseq [marker [nil {:event/id (uuid/v7) :event/type :grain/tx :event-ids #{}}
                    {:event/id (uuid/v7) :event/type :retention-test/ephemeral
                     :event-ids #{event-id}}]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no matching transaction marker"
            (compaction/removable-transaction-ids
             #{event-id} (constantly marker)
             (fn [_] (throw (AssertionError. "Must validate ownership first")))))))))
