(ns ai.obney.grain.todo-processor-v2.commit-order-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as tp]
            [clj-uuid :as uuid])
  (:import [java.io File]))

(defschemas commit-order-proof-schemas
  {:commit-order/item [:map [:n :int]]})

(defn- eventually?
  [pred]
  (loop [remaining 200]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do (Thread/sleep 10) (recur (dec remaining))))))

(defn- append-one! [store tenant-id event]
  (let [result (es/append store {:tenant-id tenant-id :events [event]})]
    (when-not (and (vector? result) (= 1 (count result)))
      (throw (ex-info "Test append failed" {:result result})))
    (first result)))

(defn- latest-checkpoint [store tenant-id]
  (first (into [] (es/read store {:tenant-id tenant-id
                                  :types #{:grain/todo-processor-checkpoint}
                                  :reverse? true :limit 1}))))

(deftest delayed-construction-is-not-lost-behind-checkpoint
  (testing "An event constructed first but appended after a durable checkpoint is still processed"
    (let [db-file (File/createTempFile "grain-commit-order-" ".sqlite")
          _ (.delete db-file)
          store (es/start {:conn {:type :sqlite
                                  :database-file (.getAbsolutePath db-file)}})
          tenant-id (random-uuid)
          delayed (es/->event {:type :commit-order/item :body {:n 1}})
          immediate (es/->event {:type :commit-order/item :body {:n 2}})
          seen (atom [])
          processor-name :commit-order/proof
          old-registry @tp/processor-registry*]
      (try
        (is (not-any? #(contains? delayed %) [:event/id :event/timestamp])
            "Construction cannot assign a position before the event commits")
        (reset! tp/processor-registry*
                {processor-name
                 {:topics #{:commit-order/item}
                  :handler-fn (fn [{:keys [event]}]
                                (swap! seen conj (:event/id event))
                                {:result/events []})}})
        (let [persisted-first (append-one! store tenant-id immediate)
              first-id (:event/id persisted-first)
              poller (tp/start-tenant-poller
                      {:event-store store
                       :tenant-ids #{tenant-id}
                       :poll-interval-ms 10
                       :batch-size 100})]
          (try
            (is (eventually? #(and (= [first-id] @seen)
                                  (= first-id (:triggered-by (latest-checkpoint store tenant-id)))))
                "Wait for durable progress, not just handler invocation")
            (let [checkpoint (latest-checkpoint store tenant-id)
                  persisted-delayed (append-one! store tenant-id delayed)
                  delayed-id (:event/id persisted-delayed)
                  expected [first-id delayed-id]]
              (is (uuid/< first-id delayed-id))
              (is (and checkpoint (uuid/< (:event/id checkpoint) delayed-id))
                  "The delayed event's position is beyond the committed checkpoint")
              (is (eventually? #(and (= expected @seen)
                                    (= delayed-id (:triggered-by (latest-checkpoint store tenant-id))))))
              (let [stored (into [] (es/read store {:tenant-id tenant-id :types #{:commit-order/item}}))]
                (is (= expected (mapv :event/id stored)))
                (is (= [2 1] (mapv :n stored)) "Commit order wins over construction order")))
            (finally
              (tp/stop-tenant-poller poller))))
        (finally
          (reset! tp/processor-registry* old-registry)
          (es/stop store)
          (doseq [suffix ["" "-wal" "-shm"]]
            (.delete (File. (str (.getAbsolutePath db-file) suffix)))))))))
