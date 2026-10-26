(ns ai.obney.grain.read-model-processor-v2.scoped-test
  (:require [clojure.test :refer :all]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]))

(defn with-stores [f]
  (doseq [type [:in-memory :sqlite]]
    (testing (name type)
      (fixture/keyed-fixture
        (fn []
          (if (= type :in-memory) (f fixture/*keyed-ctx*)
            (let [store (es/start {:conn {:type :sqlite :database-file (str fixture/*keyed-directory* "/events.db")}})]
              (try (f (assoc fixture/*keyed-ctx* :event-store store))
                   (finally (es/stop store))))))))))

(defn append! [ctx events]
  (let [result (es/append (:event-store ctx)
                         {:tenant-id (:tenant-id ctx)
                          :events (mapv (fn [{:keys [tags type] :as body}]
                                          (es/->event {:type (or type :keyed/change) :tags tags
                                                      :body (dissoc body :tags :type)})) events)})]
    (is (not (:cognitect.anomalies/category result)))
    result))

(defn rows [ctx model]
  (into {} (map (juxt :key :value)) (rmp/values ctx model)))

(deftest student-scope-materializes-only-matching-events-and-has-complete-counts
  (with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid) seen (atom [])
            model (fixture/keyed-register!
                    (fn [state e]
                      (swap! seen conj (:owner e))
                      (when (= "bad" (:owner e)) (throw (ex-info "Unrelated failure" {})))
                      (-> state (assoc :user (:user e)) (update :total (fnil + 0) (:delta e))))
                    {:indexes {:user :user}})
            scoped (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (append! ctx (into [{:owner "app-1" :user a :delta 1 :tags #{[:User a]}}
                           {:owner "app-2" :user a :delta 2 :tags #{[:User a]}}
                           {:owner "app-1" :user a :delta 3 :tags #{[:User a]}}
                           {:owner "bad" :user b :delta 1 :tags #{[:User b]}}]
                          (map #(hash-map :owner (str "other-" %) :user b :delta 1 :tags #{[:User b]}) (range 50))))
        ;; This is the production shape: event scope, then User-index selection.
        (is (= {"app-1" {:user a :total 4} "app-2" {:user a :total 2}}
               (into {} (map (juxt :key :value)) (rmp/values scoped model {:index :user :equals a}))))
        (is (= ["app-1" "app-2" "app-1"] @seen))
        (let [page (rmp/page scoped model {:index :user :equals a :limit 1 :page 2})]
          (is (= 2 (:total page)))
          (is (= ["app-2"] (mapv :key (:entries page)))))
        (is (= :absent (rmp/value scoped model "other-0" :absent)))
        (is (= :absent (rmp/state scoped model "other-0" :absent)))
        (reset! seen [])
        (append! ctx [{:owner "other-0" :user b :delta 5 :tags #{[:User b]}}])
        (is (= {:user a :total 4} (rmp/value scoped model "app-1")))
        (is (= [] @seen))
        (append! ctx [{:owner "app-1" :user a :delta 5 :tags #{[:User a]}}])
        (is (= {:user a :total 9} (rmp/value scoped model "app-1")))
        (is (= ["app-1"] @seen))))))

(deftest query-unions-deduplicate-events-and-progress-is-independent
  (with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! (fn [s e] (update s :total (fnil + 0) (:delta e))) {})
            narrow (rmp/scoped-context ctx {:tags #{[:User a]}})
            broad (rmp/scoped-context ctx {:queries [{:tags #{[:User a]}}
                                                    {:tags #{[:Application b]}}
                                                    {:tags #{[:User a]}}]})]
        (append! ctx [{:owner "same" :delta 1 :tags #{[:User a] [:Application b]}}
                     {:owner "same" :delta 2 :tags #{[:Application b]}}
                     {:owner "outside" :delta 7 :tags #{[:User (random-uuid)]}}
                     {:owner "same" :delta 100 :type :keyed/other :tags #{[:User a]}}])
        (is (= {:total 1} (rmp/value narrow model "same")))
        (is (= {"same" {:total 3}} (rows broad model)))
        (is (= {"same" {:total 3} "outside" {:total 7}} (rows ctx model)))
        (is (= {:total 1} (rmp/value narrow model "same")))
        (append! ctx [{:owner "same" :delta 4 :tags #{[:User a] [:Application b]}}])
        (is (= {:total 7} (rmp/value broad model "same")))
        (is (= {:total 5} (rmp/value narrow model "same")))
        (is (= {:total 7} (rmp/value ctx model "same")))
        (is (= :absent (rmp/value (rmp/scoped-context ctx {:queries [{:types #{:keyed/other} :tags #{[:User a]}}]})
                                    model "same" :absent)))))))

(deftest empty-and-invalid-scopes-never-trigger-global-replay
  (with-stores
    (fn [ctx]
      (let [model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})]
        (append! ctx [{:owner "a" :delta 1 :tags #{[:User (random-uuid)]}}])
        (with-redefs [es/read (fn [& _] (throw (ex-info "Unexpected replay" {})))]
          (doseq [scope [{:tags #{}} {:queries []} {:queries [{:types #{}}]}]]
            (is (= {} (rows (rmp/scoped-context ctx scope) model)))))
        (doseq [scope [{:tags nil} {:tags #{[:User "not-a-UUID"]}} {:queries [{:limit 1}]}
                      {:queries [{:after (random-uuid)}]} {:queries [{:tenant-id (random-uuid)}]}
                      {:queries [{:reverse? true}]} {:tags #{} :queries []} {}]]
          (is (thrown? clojure.lang.ExceptionInfo (rmp/scoped-context ctx scope))))
        (is (= {:total 1} (rmp/value (rmp/scoped-context (rmp/scoped-context ctx {:tags #{}}) nil) model "a")))))))

(deftest scoped-caches-survive-restart-and-equal-tag-sets-reuse-progress
  (with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            scope {:tags (into #{} [[:User a] [:Application b]])}]
        (append! ctx [{:owner "a" :delta 1 :tags #{[:User a] [:Application b]}}])
        (is (= {:total 1} (rmp/value (rmp/scoped-context ctx scope) model "a")))
        (kv/stop (:cache ctx))
        (reset! fixture/*keyed-cache-handle* nil)
        (let [cache (kv/start (lmdb/->KV-Store-LMDB (:config (:cache ctx))))
              ctx (assoc ctx :cache cache)]
          (reset! fixture/*keyed-cache-handle* cache)
          (with-redefs [es/read (fn [& _] (throw (ex-info "Warm scope replayed" {})))]
            (is (= {:total 1} (rmp/value (rmp/scoped-context ctx {:tags (into #{} [[:Application b] [:User a]])}) model "a"))))
          (append! ctx [{:owner "a" :delta 2 :tags #{[:User a] [:Application b]}}])
          (is (= {:total 3} (rmp/value (rmp/scoped-context ctx scope) model "a"))))))))

(deftest sessions-capture-scopes-and-share-the-tenant-head
  (with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            model (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            ca (rmp/scoped-context ctx {:tags #{[:User a]}})
            cb (rmp/scoped-context ctx {:queries {:tags #{[:User b]}}})
            head es/head calls (atom 0)]
        (append! ctx [{:owner "same" :delta 1 :tags #{[:User a]}}
                     {:owner "same" :delta 2 :tags #{[:User b]}}])
        (with-redefs [es/head (fn [& args] (swap! calls inc) (apply head args))]
          (rmp/read-session [[ca [model]] [cb [model]]]
            (fn [[pa pb]]
              (is (= {:total 1} (rmp/value pa model "same")))
              (is (= {:total 2} (rmp/value pb model "same")))
              (is (thrown? clojure.lang.ExceptionInfo (rmp/value (rmp/scoped-context pa nil) model "same")))
              (append! ctx [{:owner "same" :delta 3 :tags #{[:User a]}}])
              (is (= {:total 1} (rmp/value pa model "same"))))))
        (is (= 1 @calls))
        (is (= {:total 4} (rmp/value ca model "same")))))))

(deftest composed-query-keeps-all-bindings-in-the-event-scope
  (with-stores
    (fn [ctx]
      (let [a (random-uuid) b (random-uuid)
            root (fixture/keyed-register! (fn [_ e] {:total (:delta e)})
                                          {:indexes {:total (rmp/order-key [:total])}})
            related (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            definition {:from {:application root}
                        :related {:ledger {:model related :key [:id :application]}}
                        :orders {:total [:application/total]}}
            ctx (rmp/scoped-context ctx {:tags #{[:User a]}})]
        (append! ctx [{:owner "a" :delta 1 :tags #{[:User a]}}
                     {:owner "b" :delta 2 :tags #{[:User b]}}])
        (let [result (rmp/query ctx definition {:order :total})]
          (is (= 1 (:total result)))
          (is (= [{:key "a" :application {:total 1} :ledger {:total 1}}] (:rows result))))))))

(deftest large-scopes-prepare-once-without-reusing-freshness-across-sessions
  (with-stores
    (fn [ctx]
      (let [tags (into #{} (repeatedly 318 #(vector :PaymentPlan (random-uuid))))
            outside [:User (random-uuid)]
            scope {:tags tags} other-scope {:tags #{outside}}
            a (rmp/scoped-context ctx scope)
            equal-a (rmp/scoped-context ctx {:tags (into #{} (reverse (vec tags)))})
            b (rmp/scoped-context ctx other-scope)
            other (assoc a :tenant-id (random-uuid))
            one (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            two (fixture/keyed-register! fixture/keyed-history-step {:result fixture/keyed-total})
            identity-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-namespace-identity)
            canonical-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'canonical-cache-value)
            catch-up-var (ns-resolve 'ai.obney.grain.read-model-processor-v2.core 'keyed-catch-up!)
            originals (mapv deref [identity-var canonical-var catch-up-var])
            counts (atom {})]
        (append! ctx [{:owner "plan" :delta 7 :tags tags}
                      {:owner "plan" :delta 20 :tags #{outside}}])
        (append! other [{:owner "plan" :delta 90 :tags tags}])
        (with-redefs-fn
          {identity-var (fn [& args]
                          (swap! counts update :identities (fnil inc 0))
                          (apply (nth originals 0) args))
           canonical-var (fn [value]
                           (when (contains? #{scope other-scope} value)
                             (swap! counts update :scope-serializations (fnil inc 0)))
                           ((nth originals 1) value))
           catch-up-var (fn [& args]
                          (swap! counts update :catch-ups (fnil inc 0))
                          (apply (nth originals 2) args))}
          #(rmp/read-session [[a [one one two]] [(assoc equal-a :read-model/batch-size 1) [one]]
                             [b [one]] [other [one]]]
             (fn [[pa duplicate pb other-tenant]]
               (dotimes [_ 14]
                 (is (= {:total 7} (rmp/value pa one "plan")))
                 (is (= {:total 7} (rmp/value pa two "plan")))
                 (is (= {:total 7} (rmp/value duplicate one "plan")))
                 (is (= {:total 20} (rmp/value pb one "plan")))
                 (is (= {:total 90} (rmp/value other-tenant one "plan")))))))
        (is (= {:identities 7 :scope-serializations 2 :catch-ups 4} @counts)
            "Work depends on distinct requested identities/scopes, not duplicate models, contexts or reads")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"batch size must be positive"
              (rmp/read-session [[a [one]] [(assoc equal-a :read-model/batch-size 0) [one]]]
                (constantly nil)))
            "Sharing catch-up must not hide invalid configuration in a duplicate request")
        (with-redefs [es/read (fn [& _] (throw (ex-info "Warm scope replayed" {})))]
          (is (= {:total 7} (rmp/value equal-a one "plan"))))
        (append! ctx [{:owner "plan" :delta 3 :tags tags}])
        (is (= {:total 10} (rmp/value a one "plan")))
        (is (= {:total 20} (rmp/value b one "plan")))
        (is (= {:total 90} (rmp/value other one "plan")))))))
