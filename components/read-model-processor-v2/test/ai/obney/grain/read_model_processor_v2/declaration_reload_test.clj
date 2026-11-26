(ns ai.obney.grain.read-model-processor-v2.declaration-reload-test
  "Re-evaluating a defreadmodel at its own definition site (the same reducer
   Var) replaces the registration, whatever changed. A different Var claiming a
   registered name with a docstring is a conflict while the registered Var is
   still interned. Registrations that do not come from defreadmodel Vars keep
   comparing declarative values."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(defn- declare-in
  "Evaluates a read-model declaration form with ns-sym as the current namespace."
  [ns-sym form]
  (binding [*ns* (create-ns ns-sym)]
    (refer-clojure)
    (alias 'rmp 'ai.obney.grain.read-model-processor-v2.interface)
    (eval form)))

(defn- with-clean-registry [names f]
  (let [previous @rmp/read-model-registry*]
    (try
      (f)
      (finally
        (swap! rmp/read-model-registry* #(apply dissoc % names))
        (swap! rmp/read-model-registry* merge (select-keys previous names))))))

(defn- customers-form [docstring version]
  `(rmp/defreadmodel :reload-test ~'customers
     {:events #{:reload-test/customer-registered :reload-test/customer-closed}
      :version ~version
      :key :customer-id
      :include? (fn [customer#] (not (:closed? customer#)))
      :indexes {:name (rmp/order-key [:last-name :first-name])}
      :searches {:text (rmp/search-text [:first-name :last-name])}}
     ~docstring
     [~'customer ~'event]
     (merge ~'customer (select-keys ~'event [:customer-id :first-name :last-name]))))

(defn- registered [rm-name]
  (get @rmp/read-model-registry* rm-name))

(deftest re-evaluating-a-keyed-model-with-function-options-is-accepted
  (with-clean-registry [:reload-test/customers]
    (fn []
      (declare-in 'reload-test.customers (customers-form "One customer per id." 1))
      (testing "unchanged source"
        (is (some? (declare-in 'reload-test.customers (customers-form "One customer per id." 1)))))
      (testing "changed docstring"
        (declare-in 'reload-test.customers (customers-form "Customers, keyed by id." 1))
        (is (= "Customers, keyed by id." (:definition/description (registered :reload-test/customers)))))
      (testing "changed options"
        (declare-in 'reload-test.customers (customers-form "Customers, keyed by id." 2))
        (is (= 2 (:version (registered :reload-test/customers))))))))

(deftest re-evaluating-an-ordinary-model-refreshes-its-reducer
  (with-clean-registry [:reload-test/totals]
    (fn []
      (let [form (fn [docstring increment]
                   `(rmp/defreadmodel :reload-test ~'totals
                      {:events #{:reload-test/added} :partition-fn (fn [x#] x#) :entity-id-fn :id}
                      ~docstring
                      [~'state ~'_event]
                      (update ~'state :n (fnil + 0) ~increment)))]
        (declare-in 'reload-test.totals (form "Totals." 1))
        (declare-in 'reload-test.totals (form "Running totals." 5))
        (let [entry (registered :reload-test/totals)]
          (is (= "Running totals." (:definition/description entry)))
          (is (= {:n 5} ((:reducer-fn entry) {} {}))))))))

(deftest a-recreated-namespace-redeclares-its-model
  (with-clean-registry [:reload-test/customers]
    (fn []
      (declare-in 'reload-test.refreshed (customers-form "One customer per id." 1))
      (remove-ns 'reload-test.refreshed)
      (is (some? (declare-in 'reload-test.refreshed (customers-form "One customer per id." 1)))
          "tools.namespace refresh recreates the Var under the same symbol"))))

(deftest a-model-moved-to-another-namespace-can-be-redeclared
  (with-clean-registry [:reload-test/customers]
    (fn []
      (testing "after the old namespace is removed, as tools.namespace does on a file rename"
        (declare-in 'reload-test.old-home (customers-form "One customer per id." 1))
        (remove-ns 'reload-test.old-home)
        (declare-in 'reload-test.new-home (customers-form "One customer per id." 1))
        (is (= 'reload-test.new-home/reload-test-customers
               (symbol (:reducer-fn (registered :reload-test/customers))))))
      (testing "after the old Var is unmapped from a namespace that still exists"
        (ns-unmap 'reload-test.new-home 'reload-test-customers)
        (declare-in 'reload-test.third-home (customers-form "One customer per id." 1))
        (is (= 'reload-test.third-home/reload-test-customers
               (symbol (:reducer-fn (registered :reload-test/customers)))))))))

(deftest another-namespace-claiming-the-name-conflicts
  (with-clean-registry [:reload-test/customers]
    (fn []
      (declare-in 'reload-test.owner (customers-form "One customer per id." 1))
      (testing "even with an identical declaration"
        (let [thrown (try (declare-in 'reload-test.intruder (customers-form "One customer per id." 1))
                          nil
                          (catch Exception e e))
              cause (some #(when (= "Conflicting read model definition" (ex-message %)) %)
                          (take-while some? (iterate ex-cause thrown)))]
          (is (some? cause))
          (is (= :reload-test/customers (:read-model/name (ex-data cause))))))
      (testing "the original registration is kept"
        (is (= 'reload-test.owner/reload-test-customers
               (symbol (:reducer-fn (registered :reload-test/customers)))))))))

(deftest models-without-docstrings-keep-legacy-last-wins-registration
  (with-clean-registry [:reload-test/plain]
    (fn []
      (let [form '(rmp/defreadmodel :reload-test plain {:events #{:reload-test/e}} [s _e] s)]
        (declare-in 'reload-test.plain-a form)
        (declare-in 'reload-test.plain-b form)
        (is (= 'reload-test.plain-b/reload-test-plain
               (symbol (:reducer-fn (registered :reload-test/plain)))))))))

(deftest non-var-registrations-compare-declarative-values
  (with-clean-registry [:reload-test/direct]
    (fn []
      (let [definition {:definition/description "Direct."
                        :definition/source {:ns "test" :file "test.clj" :line 1}
                        :definition/value {:description "Direct." :options {}}}]
        (rmp/register-declared! :reload-test/direct identity {} definition)
        (is (some? (rmp/register-declared! :reload-test/direct identity {} definition)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Conflicting read model definition"
                              (rmp/register-declared! :reload-test/direct identity {}
                                                      (assoc-in definition [:definition/value :description] "Other."))))))))
