(ns ai.obney.grain.read-model-processor-v2.routed-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.keyed-test :as f]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(use-fixtures :each f/fixture)

(defn register! [step opts]
  (let [name (keyword "routed-test" (str (random-uuid)))]
    (rmp/register-read-model! name step (merge {:events #{:keyed/change} :version 1
                                              :route :inputs} opts))
    name))

(deftest routed-order-empty-events-tenancy-and-restart
  (let [route-calls (atom 0)
        route (fn [event] (swap! route-calls inc) (:inputs event))
        model (register! f/history-step {:route route :result f/total
                                         :indexes {:total (rmp/order-key [:total])}})
        _ (f/append! [{:inputs [["a" {:delta 1}] ["b" {:delta 8}] ["a" {:delta 2}]]}])]
    (is (= {"a" {:total 3} "b" {:total 8}} (f/results f/*ctx* model)))
    (is (= {:history [1 2]} (rmp/state f/*ctx* model "a")))
    (f/append! [{:inputs [["a" {:delta 4}]]} {:inputs []}])
    (is (= {"a" {:total 7} "b" {:total 8}} (f/results f/*ctx* model)))
    (let [progress (rmp/progress f/*ctx* model) before (f/stored-bytes)]
      (is (:watermark progress))
      (is (= 3 @route-calls) "Empty routes are checkpointed and never reprocessed on warm reads")
      (is (= ["b"] (f/page-keys f/*ctx* model {:index :total :page 2 :limit 1})))
      (is (= :indexed (get-in (rmp/query f/*ctx* {:from {:item model} :orders {:total [:item/total]}}
                                         {:order :total :limit 1}) [:work :strategy])))
      (is (true? (= before (f/stored-bytes))))
      (let [cache (:cache f/*ctx*)]
        (kv/stop cache)
        (let [reopened (kv/start (lmdb/->KV-Store-LMDB (:config cache)))
              ctx (assoc f/*ctx* :cache reopened)]
          (reset! f/*cache-handle* reopened)
          (is (= progress (rmp/progress ctx model)))
          (is (= {:total 7} (rmp/value ctx model "a")))
          (is (= 3 @route-calls))
          (is (= {} (f/results (assoc ctx :tenant-id (random-uuid)) model))))))))

(deftest malformed-routes-are-errors-and-key-remains-strict
  (f/append! [{:owner nil}])
  (doseq [bad [nil {} #{["a" {}]} [["a"]] [["a" {} :extra]] [[nil {}]]]]
    (let [model (register! f/history-step {:route (constantly bad)})
          before (f/stored-bytes)]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/value f/*ctx* model "a")) (pr-str bad))
      (is (true? (= before (f/stored-bytes))))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rmp/value f/*ctx* (f/register! f/history-step {}) "a")))
  (doseq [opts [{:key :owner} {:route nil} {:route 123}]]
    (is (thrown? clojure.lang.ExceptionInfo (register! f/history-step opts)))))

(deftest failures-roll-back-every-owner-index-and-checkpoint
  (doseq [failure [:routing :reduction :index]]
    (let [fail? (atom false)
          route (fn [event]
                  (map-indexed (fn [i entry]
                                 (when (and @fail? (= failure :routing) (= 1 i))
                                   (throw (ex-info "Routing failed part way through" {})))
                                 entry) (if (= failure (:scenario event)) (:inputs event) [])))
          step (fn [state input]
                 (when (and @fail? (= failure :reduction) (= 13 (:delta input)))
                   (throw (ex-info "Reducer failed" {})))
                 (f/history-step state input))
          opts {:route route :result f/total :searches {:text #(str (:total %))}
                :indexes {:total (fn [value]
                                   (when (and @fail? (= failure :index) (= 13 (:total value)))
                                     (throw (ex-info "Index failed after another owner" {})))
                                   (:total value))}}
          model (register! step opts)]
      (f/append! [{:scenario failure :inputs [["baseline" {:delta 1}]]}])
      (f/results f/*ctx* model)
      (let [before (f/stored-bytes)]
        (f/append! [{:scenario failure :inputs [["a" {:delta 2}] ["b" {:delta 13}]]}])
        (reset! fail? true)
        (is (thrown? clojure.lang.ExceptionInfo (rmp/value f/*ctx* model "a")) (str failure))
        (is (true? (= before (f/stored-bytes))) "Includes every state, result, index, count and checkpoint")
        (reset! fail? false)
        (let [recovered (f/results f/*ctx* model)
              fresh (register! step opts)]
          (is (= recovered (f/results f/*ctx* fresh)))
          (is (= 13 (:total (get recovered "b")))))))))
