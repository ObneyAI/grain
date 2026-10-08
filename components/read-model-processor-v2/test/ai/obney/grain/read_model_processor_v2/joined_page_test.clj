(ns ai.obney.grain.read-model-processor-v2.joined-page-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.keyed-test :as fixture]))

(use-fixtures :each fixture/fixture)
(def shape [:keys :total :total-pages :page :page-size])
(defn models []
  [(fixture/register! merge {:include? :member?})
   (fixture/register! merge {:include? :ledger? :indexes {:balance :balance}
                             :filtered-indexes #{:balance}})])
(defn expected [rows {:keys [direction page limit missing-value] :as options}]
  (let [candidates (when (contains? options :keys) (set (:keys options)))
        value #(if (:ledger? %) (:balance %) missing-value)
        ordered (vec (sort (fn [a b]
                             (let [c (compare (value a) (value b))]
                               (if (zero? c) (compare (str (:owner a)) (str (:owner b)))
                                 (if (= :desc direction) (- c) c))))
                           (filter #(and (:member? %) (or (nil? candidates) (candidates (:owner %)))) rows)))
        total (count ordered) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min page pages) offset (* (dec page) limit)]
    {:keys (mapv :owner (subvec ordered offset (min total (+ offset limit))))
     :total total :total-pages pages :page page :page-size limit}))

(deftest joined-pages-match-independent-left-join-oracle
  (let [[members ledger] (models)
        rows (mapv (fn [i] {:owner (str i) :member? (not (zero? (mod i 11)))
                            :ledger? (not (zero? (mod i 3)))
                            :balance (nth [-100 0 100 200] (mod i 4))}) (range 257))
        check! (fn [rows]
                 (rmp/read-session fixture/*ctx* [members ledger]
                   (fn [ctx]
                     (doseq [direction [:asc :desc] page [1 2 99] missing [-250 0 100 250]
                             selection [{} {:keys []} {:keys (concat (map :owner (take-nth 2 rows)) ["ghost" "2" "2"])}]
                             :let [options (merge {:index :balance :direction direction :page page :limit 25
                                                  :missing-value missing} selection)]]
                       (is (= (expected rows options)
                              (select-keys (rmp/joined-page ctx members ledger options) shape)))))))]
    (fixture/append! rows)
    (check! rows)
    (let [changed (mapv #(-> % (update :member? not) (update :ledger? not) (assoc :balance 0)) (take 80 rows))]
      (fixture/append! changed)
      (check! (into changed (drop 80 rows))))
    (fixture/append! rows)
    (check! rows)
    (let [seen (atom []) ctx (assoc fixture/*ctx* :read-model/observer #(swap! seen conj %))]
      (rmp/joined-page ctx members ledger {:index :balance :missing-value 0 :page 4})
      (is (not-any? #(#{:state-decoded :result-decoded :sort-key-read :event-applied} (:operation %)) @seen)))
    (is (= 0 (:total (rmp/joined-page (assoc fixture/*ctx* :tenant-id (random-uuid)) members ledger
                                      {:index :balance :missing-value 0}))))))

(deftest completely-absent-ledgers-and-empty-populations
  (let [members (fixture/register! merge {:include? :member?})
        ledger (fixture/register! merge {:events #{:keyed/other} :indexes {:balance :balance}
                                        :filtered-indexes #{:balance}})
        rows (mapv #(hash-map :owner (str %) :member? true) (range 53))]
    (is (= 0 (:total (rmp/joined-page fixture/*ctx* members ledger {:index :balance :missing-value 0}))))
    (fixture/append! rows)
    (doseq [direction [:asc :desc] page [1 2 999]
            selection [{} {:keys (reverse (map :owner rows))}]
            :let [opts (merge {:index :balance :missing-value 0 :direction direction :page page :limit 25} selection)]]
      (is (= (expected rows opts)
             (select-keys (rmp/joined-page fixture/*ctx* members ledger opts) shape))))
    (is (thrown? Exception (rmp/joined-page fixture/*ctx* members ledger {:index :balance})))
    (is (thrown? Exception (rmp/joined-page fixture/*ctx* members ledger
                                           {:index :balance :missing-value 0 :sort-value identity})))))

(deftest membership-and-ordering-share-snapshot
  (let [[members ledger] (models) ctx fixture/*ctx*
        opts {:index :balance :missing-value 0 :page 2 :limit 1}]
    (fixture/append! [{:owner "a" :member? true :ledger? true :balance -100}
                     {:owner "b" :member? true :ledger? false :balance 500}
                     {:owner "c" :member? true :ledger? true :balance 100}])
    (rmp/read-session ctx [members ledger]
      (fn [prepared]
        (is (= ["b"] (:keys (rmp/joined-page prepared members ledger opts))))
        (let [writer (future
                       (fixture/append! ctx [{:owner "b" :member? true :ledger? true :balance 200}])
                       (rmp/read-session ctx [members ledger] (constantly nil)))]
          (is (not= ::timeout (deref writer 20000 ::timeout)))
          (is (= ["b"] (:keys (rmp/joined-page prepared members ledger opts)))))))
    (is (= ["c"] (:keys (rmp/joined-page ctx members ledger opts))))))
