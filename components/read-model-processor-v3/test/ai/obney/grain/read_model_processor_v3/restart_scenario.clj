(ns ai.obney.grain.read-model-processor-v3.restart-scenario
  "Fresh-process recovery through real SQLite events and LMDB projections."
  (:require [ai.obney.grain.read-model-processor-v3.interface :as rmp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-sqlite-v3.interface]
            [datahike.api :as d]))
(es/defevent :v3-restart/change "Recovery test event."
  {:schema [:map [:id :string] [:name :string]]})
(def tenant #uuid "82c23b68-67fc-49fb-8500-9d607bfc0187")
(defn check! [message passed?]
  (when-not passed? (throw (ex-info message {}))) (println "PASS" message))
(defn -main [phase directory]
  (let [events (es/start {:conn {:type :sqlite :database-file (str directory "/events.sqlite")}})
        store (rmp/open-store {:storage-dir (str directory "/projections") :pin-ttl-ms 1000})
        context {:projection-store store :event-store events :tenant-id tenant}
        calls (atom 0)
        append! (fn [name]
                  (es/append events {:tenant-id tenant :events [(es/->event {:type :v3-restart/change
                                                                           :body {:id "a" :name name}})]}))]
    (rmp/register-read-model! :v3-restart/students
      (fn [state event] (swap! calls inc) (assoc state (:id event) {:name (:name event)}))
      {:version 1 :events #{:v3-restart/change}
       :schema [:map-of :string [:map [:name :string]]]
       :indexes {:name {:fields [:name]}}})
    (try
      (case phase
        "seed" (do (append! "Before")
                   (check! "seed state" (= {"a" {:name "Before"}} (rmp/project context :v3-restart/students)))
                   (check! "one reducer invocation" (= 1 @calls)))
        "interrupt" (do
                      (check! "reopen without replay" (= "Before" (get-in (rmp/record context :v3-restart/students "a") [:item :value :name])))
                      (check! "durable watermark restored" (zero? @calls))
                      (append! "After")
                      (let [transact d/transact]
                        (with-redefs [d/transact (fn [conn tx]
                                                 (let [result (transact conn tx)]
                                                   (when (some :projection/watermark tx)
                                                     (.halt (Runtime/getRuntime) 17)) result))]
                          (rmp/project context :v3-restart/students)))
                      (throw (ex-info "Crash hook did not execute" {})))
        "verify" (let [page (rmp/page context :v3-restart/students {:index :name :limit 25})]
                   (check! "committed data/index survived exit before pin" (= [{:id "a" :value {:name "After"}}] (:items page)))
                   (check! "watermark survived exit" (uuid? (:watermark page)))
                   (check! "committed event not replayed" (zero? @calls))
                   (rmp/collect! store)
                   (check! "current head survives orphan cleanup" (= "After" (get-in (rmp/record context :v3-restart/students "a") [:item :value :name])))))
      (finally (rmp/close-store! store) (es/stop events)))
    (shutdown-agents)
    (System/exit 0)))
