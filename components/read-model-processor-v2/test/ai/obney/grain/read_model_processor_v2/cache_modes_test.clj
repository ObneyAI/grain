(ns ai.obney.grain.read-model-processor-v2.cache-modes-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.l1-cache :as l1]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface.protocol :as kp]
            [ai.obney.grain.fressian-util.interface :as fressian]))

(es/defevent :cache-modes/add "Add one to a chosen entity." {:schema [:map [:entity :int]]})

(deftest tier-selection-and-checkpoint-boundary
  ;; BC2: disabling a tier must remove its work, not just discard its output.
  (doseq [mode [:none :l1 :l2 :both]]
    (testing (str mode)
      (l1/clear!)
      (let [store (es/start {:conn {:type :in-memory}})
            tenant (random-uuid)
            values (atom {})
            writes (atom 0)
            reads (atom 0)
            folds (atom 0)
            encodes (atom 0)
            encode fressian/encode
            get-entry l1/get-entry
            cache (reify kp/KVStore
                    (start [this] this) (stop [_])
                    (get! [_ {:keys [k]}] (swap! reads inc) (get @values (vec k)))
                    (read-snapshot [_ f]
                      (let [snapshot @values]
                        (f (fn [{:keys [k]}] (swap! reads inc) (get snapshot (vec k))))))
                    (put! [_ {:keys [k v]}] (swap! writes inc) (swap! values assoc (vec k) v))
                    (put-batch! [_ {:keys [entries]}]
                      (swap! writes inc)
                      (swap! values into (map (fn [{:keys [k v]}] [(vec k) v]) entries))))
            append! (fn [n] (es/append store {:tenant-id tenant :events
                                             (mapv #(es/->event {:type :cache-modes/add :body {:entity %}})
                                                   (range n))}))
            args {:name :cache-modes/model :version 1 :query {:types #{:cache-modes/add}}
                  :cache-mode mode :checkpoint-threshold 3
                  :f (fn [s e] (swap! folds inc) (update s (:entity e) (fnil inc 0)))}
            project #(rmp/p {:event-store store :cache cache :tenant-id tenant} args)]
        (try
          (with-redefs [fressian/encode (fn [v] (swap! encodes inc) (encode v))
                        l1/get-entry (fn [k]
                                       (when (#{:none :l2} mode)
                                         (throw (ex-info "Disabled L1 was consulted" {})))
                                       (get-entry k))]
            (append! 2)
            (is (= {0 1, 1 1} (project)))
            (is (= {0 1, 1 1} (project)))
            (is (= (if (= mode :none) 4 2) @folds))
            (is (= (if (#{:l1 :both} mode) 1 0) (:entries (l1/stats))))
            (append! 3)
            (is (= {0 2, 1 2, 2 1} (project)))
            (if (#{:l2 :both} mode)
              (is (= 2 @writes) "bootstrap and threshold checkpoint")
              (do (is (zero? @writes)) (is (zero? @reads)) (is (zero? @encodes)))))
          (finally (l1/clear!) (es/stop store)))))))

(deftest tuning-validation
  (doseq [args [{:cache-mode :typo} {:checkpoint-threshold 0}
                {:segment-count 0} {:segment-threshold -1}]]
    (is (thrown? clojure.lang.ExceptionInfo (rmp/p {} args)))))
