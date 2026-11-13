(ns ai.obney.grain.control-plane.write-failure-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.control-plane.core :as control]
            [ai.obney.grain.event-store-v3.interface :as es]
            [com.brunobonacci.mulog.core :as mulog-core]
            [cognitect.anomalies :as anom]))

(def busy {::anom/category ::anom/busy ::anom/message "Write retries exhausted" :event-store/attempts 4})

(defn- capture [f]
  (let [logs (atom [])]
    (with-redefs [mulog-core/log* (fn [_ event pairs]
                                  (swap! logs conj (assoc (apply hash-map pairs) :mulog/event-name event))
                                  nil)]
      {:result (f) :logs @logs})))

;; Allium: CoordinationWrites.FailedWriteIsNotSuccess.
(deftest lease-writes-report-failure-and-succeed-on-next-cycle
  (let [attempts (atom 0)
        ctx {:event-store :test-store}
        change {:node-id (random-uuid) :tenant-id (random-uuid)}
        run #(control/emit-lease-changes! ctx [change] [change])]
    (with-redefs [es/append (fn [& _] (if (= 1 (swap! attempts inc)) busy []))]
      (let [{:keys [result logs]} (capture run)]
        (is (= busy result))
        (is (= 1 @attempts) "No additional control-plane retry loop")
        (is (some #(= busy (:anomaly %)) logs))
        (is (not-any? :metric/name logs)))
      (let [{:keys [result logs]} (capture run)]
        (is (nil? result) "Successful lease-write API remains unchanged")
        (is (= 2 @attempts))
        (is (= #{"LeaseReleased" "LeaseAcquired"} (set (keep :metric/name logs))))))))

;; Allium: CoordinationWrites.FailedWriteIsNotSuccess.
(deftest heartbeats-only-count-committed-writes
  (let [attempts (atom 0)
        heartbeat (#'control/heartbeat-handler {:ctx {:event-store :test-store} :node-id (random-uuid) :metadata {}})]
    (with-redefs [es/append (fn [& _] (if (= 1 (swap! attempts inc)) busy []))]
      (let [{:keys [logs]} (capture #(heartbeat nil))]
        (is (= 1 @attempts))
        (is (some #(= busy (:anomaly %)) logs))
        (is (not-any? :metric/name logs)))
      (let [{:keys [logs]} (capture #(heartbeat nil))]
        (is (= 2 @attempts))
        (is (= ["HeartbeatEmitted"] (vec (keep :metric/name logs))))))))

;; Includes the immediate startup heartbeat and shutdown departure paths.
(deftest direct-lifecycle-writes-expose-exhaustion
  (with-redefs [es/append (constantly busy)]
    (doseq [write [#(control/emit-heartbeat! {:event-store :test-store} (random-uuid) {})
                   #(control/emit-node-departed! {:event-store :test-store} (random-uuid))]]
      (let [{:keys [result logs]} (capture write)]
        (is (= busy result))
        (is (some #(= busy (:anomaly %)) logs))))))
