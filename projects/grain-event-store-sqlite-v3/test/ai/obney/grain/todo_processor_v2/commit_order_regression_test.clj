(ns ai.obney.grain.todo-processor-v2.commit-order-regression-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [ai.obney.grain.todo-processor-v2.core :as tp]
            [clj-uuid :as uuid])
  (:import [java.io File]
           [java.util UUID]))

(defschemas commit-order-proof-schemas
  {:commit-order/item [:map [:n :int]]})

(defn- eventually?
  [pred]
  (loop [remaining 200]
    (cond
      (pred) true
      (zero? remaining) false
      :else (do (Thread/sleep 10) (recur (dec remaining))))))

(deftest delayed-lower-id-event-is-not-lost-behind-checkpoint
  (testing "append repairs caller ID order at the tenant serialization boundary"
    (let [db-file (File/createTempFile "grain-commit-order-" ".sqlite")
          _ (.delete db-file)
          store (es/start {:conn {:type :sqlite
                                  :database-file (.getAbsolutePath db-file)}})
          tenant-id (random-uuid)
          low-id (UUID/fromString "01900000-0000-7000-8000-000000000001")
          high-id (UUID/fromString "01900000-0000-7000-8000-000000000002")
          low (assoc (es/->event {:type :commit-order/item :body {:n 1}})
                     :event/id low-id)
          high (assoc (es/->event {:type :commit-order/item :body {:n 2}})
                      :event/id high-id)
          seen (atom [])
          processor-name :commit-order/proof
          old-registry @tp/processor-registry*]
      (try
        (es/append store {:tenant-id tenant-id :events [high]})
        (reset! tp/processor-registry*
                {processor-name
                 {:topics #{:commit-order/item}
                  :handler-fn (fn [{:keys [event]}]
                                (swap! seen conj (:event/id event))
                                {:result/events []})}})
        (let [poller (tp/start-tenant-poller
                      {:event-store store
                       :tenant-ids #{tenant-id}
                       :poll-interval-ms 10
                       :batch-size 100})]
          (try
            (is (eventually? #(= [high-id] @seen)))
            (let [[persisted-low] (es/append store
                                    {:tenant-id tenant-id :events [low]})]
              (is (uuid/< high-id (:event/id persisted-low)))
              (is (eventually? #(= [high-id (:event/id persisted-low)] @seen)))
              (is (= [high-id (:event/id persisted-low)]
                     (mapv :event/id
                           (es/read store {:tenant-id tenant-id
                                           :types #{:commit-order/item}})))))
            (finally
              (tp/stop-tenant-poller poller))))
        (finally
          (reset! tp/processor-registry* old-registry)
          (es/stop store)
          (.delete db-file))))))
