(ns ai.obney.grain.read-model-processor-v2.read-query-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.keyed-test :as fixture]
            [clojure.string :as str]))

(use-fixtures :each fixture/fixture)

(defn setup []
  (let [customer (fixture/register! (fn [_ e] (:customer e))
                                   {:include? some? :indexes {:name (rmp/order-key [:name]) :company :company}
                                    :searches {:text (rmp/search-text [:name :email] {:suffix " "})}})
        company (fixture/register! (fn [_ e] (:company e))
                                  {:key :company-id :include? some? :searches {:text (rmp/search-text [:name])}})
        ledger (fixture/register! (fn [_ e] (:ledger e))
                                  {:include? some? :indexes {:amount (rmp/order-key [:amount])}
                                   :ranked-indexes #{:amount}})
        definition {:from {:customer customer}
                    :related {:company {:model company :key :customer/company}
                              :ledger {:model ledger :key [:id :customer] :missing [:default {:amount 0}]}}
                    :search [:customer/name :customer/email :company/name]
                    :orders {:name [:customer/name] :company [:company/name] :amount [:ledger/amount]
                             :computed [[:call + :ledger/amount [:param :offset]]]}}]
    {:customer customer :company company :ledger ledger :definition definition}))

(defn seed! []
  (fixture/append! (mapv (fn [i] {:owner (str i) :company-id (str "c" (mod i 3))
                                  :customer {:name (str "Name " (mod i 7)) :email "example.invalid"
                                            :company (str "c" (mod i 3))}
                                  :company {:name (if (= 2 (mod i 3)) "Zulu" "Same")}
                                  :ledger {:amount (- i 20)}}) (range 61))))

(defn oracle [ctx {:keys [customer company ledger]} options]
  (let [companies (into {} (map (juxt :key :value)) (rmp/values ctx company))
        ledgers (into {} (map (juxt :key :value)) (rmp/values ctx ledger))
        rows (map (fn [{:keys [key value]}] {:key key :customer value :company (get companies (:company value))
                                             :ledger (get ledgers key {:amount 0})}) (into [] (rmp/values ctx customer)))
        text (str/lower-case (str/trim (:search options "")))
        rows (filter #(str/includes? (str/lower-case (str (get-in % [:customer :name]) " "
                                                          (get-in % [:customer :email]) " " (get-in % [:company :name]))) text) rows)
        order (case (:order options) :name #(str/lower-case (get-in % [:customer :name]))
                    :company #(str/lower-case (get-in % [:company :name]))
                    :amount #(get-in % [:ledger :amount])
                    :computed #(+ (get-in % [:ledger :amount]) (get-in options [:params :offset])))
        rows (sort (fn [a b] (let [c (compare (order a) (order b))]
                               (if (zero? c) (compare (:key a) (:key b))
                                   (if (= :desc (:direction options)) (- c) c)))) rows)
        total (count rows) limit (:limit options 7) pages (max 1 (quot (+ total (dec limit)) limit))
        page (min (:page options 1) pages)]
    {:rows (vec (take limit (drop (* (dec page) limit) rows))) :total total :total-pages pages
     :page page :page-size limit}))

(deftest query-matches-composition-and-preserves-exact-membership
  (let [{:keys [definition ledger] :as models} (setup) ctx fixture/*ctx*]
    (seed!)
    (doseq [order [:name :company :amount :computed] direction [:asc :desc]
            search ["" "NAME" "example" "valid sam" "absent"] page [1 2 999]
            :let [options {:order order :direction direction :search search :page page :limit 7 :params {:offset 10}}]]
      (is (= (oracle ctx models options) (dissoc (rmp/query ctx definition options) :work)) (pr-str options)))
    (testing "Equal cardinality is not sufficient to use the related index directly"
      (fixture/append! [{:owner "0" :company-id "c0" :customer {:name "Missing" :email "example.invalid" :company "c0"}
                         :company {:name "Same"} :ledger nil}
                        {:owner "outsider" :company-id "unused" :customer nil :company nil :ledger {:amount -999}}])
      (doseq [direction [:asc :desc] page [1 2 999]
              :let [options {:order :amount :direction direction :page page :limit 7}]]
        (is (= (oracle ctx models options) (dissoc (rmp/query ctx definition options) :work)))
        (is (= :joined (get-in (rmp/query ctx definition options) [:work :strategy])))))
    (is (= 3 (count (rmp/query-models definition))))
    (is (= #{ledger} (set (filter #(= % ledger) (keys (rmp/query-models definition))))))))

(deftest indexed-paths-do-not-read-off-page-customers
  (let [{:keys [definition]} (setup) ctx fixture/*ctx* seen (atom [])
        measured (assoc ctx :read-model/observer #(swap! seen conj %))]
    (seed!)
    (rmp/query ctx definition {:order :name})
    (doseq [order [:name :company :amount] page [1 999]]
      (reset! seen [])
      (let [result (rmp/query measured definition {:order order :page page :limit 7})]
        (is (<= (count (filter #(= :result-decoded (:operation %)) @seen)) 24))
        (is (not-any? #(#{:state-decoded :join-membership-entry} (:operation %)) @seen))
        (is (#{:indexed :related-groups} (get-in result [:work :strategy])))))))

(deftest malformed-definitions-and-short-searches
  (let [{:keys [definition]} (setup) ctx fixture/*ctx*]
    (doseq [bad [(assoc definition :fields {})
                 (assoc-in definition [:related :company :key] :missing/id)
                 (assoc-in definition [:related :company :key] :company/id)
                 (assoc-in definition [:orders :bad] [[:unknown :customer]])
                 (assoc-in definition [:related :ledger :missing] :optional)]]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/query-models bad))))
    (let [seen (atom []) result (rmp/query (assoc ctx :read-model/observer #(swap! seen conj %))
                                           definition {:order :name :search "😀a"})]
      (is (= [] (:rows result)))
      (is (= 0 (:total result)))
      (is (empty? @seen)))))

(deftest required-validation-and-optional-nil
  (let [{:keys [definition]} (setup) ctx fixture/*ctx*]
    (fixture/append! [{:owner "a" :company-id "missing" :customer {:name "Anna" :company "missing"}}])
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required related result"
                          (rmp/query ctx definition {:order :name})))
    (let [optional (assoc-in definition [:related :company :missing] [:default nil])]
      (is (nil? (get-in (rmp/query ctx optional {:order :name}) [:rows 0 :company]))))
    (let [checked (assoc-in definition [:related :company :validate]
                            (fn [id value] (when-not value (throw (ex-info "Domain company error" {:id id})))))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Domain company error"
                            (rmp/query ctx checked {:order :name}))))))

(deftest stored-nil-is-not-a-missing-default
  (let [root (fixture/register! (fn [_ e] (:customer e)) {:indexes {:name (rmp/order-key [:name])}})
        nullable (fixture/register! (fn [_ _] nil) {})
        definition {:from {:customer root}
                    :related {:nullable {:model nullable :key [:id :customer] :missing [:default :absent]}}
                    :orders {:name [:customer/name]}}]
    (fixture/append! [{:owner "a" :customer {:name "Anna"}}])
    (is (nil? (get-in (rmp/query fixture/*ctx* definition {:order :name}) [:rows 0 :nullable])))))

(deftest search-defaults-cannot-silently-lose-matches
  (let [{:keys [definition]} (setup)
        definition (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}])]
    (fixture/append! [{:owner "a" :company-id "absent" :customer {:name "Anna" :company "absent"}}])
    (is (= "Fallback" (get-in (rmp/query fixture/*ctx* definition {:order :name}) [:rows 0 :company :name])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nil missing default"
                          (rmp/query fixture/*ctx* definition {:order :name :search "Fallback"}))))
  (let [{:keys [definition]} (setup)]
    (seed!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with root"
                          (rmp/query fixture/*ctx* (assoc definition :search [:company/name])
                                     {:order :name :search "Same"})))))

(deftest query-freshness-and-tenant-isolation
  (let [{:keys [definition] :as models} (setup) ctx fixture/*ctx*
        other (assoc ctx :tenant-id (random-uuid))]
    (seed!)
    (fixture/append! other [{:owner "0" :company-id "c0"
                            :customer {:name "Other" :email "other.invalid" :company "c0"}
                            :company {:name "Other company"} :ledger {:amount 777}}])
    (is (= 1 (:total (rmp/query other definition {:order :amount}))))
    (is (= 61 (:total (rmp/query ctx definition {:order :amount}))))
    (fixture/append! [{:owner "0" :company-id "c0"
                       :customer {:name "Renamed" :email "example.invalid" :company "c0"}
                       :company {:name "Renamed company"} :ledger {:amount 999}}])
    (doseq [options [{:order :company :search "Renamed company"}
                    {:order :amount :direction :desc}
                    {:order :computed :params {:offset 100} :direction :desc}]]
      (is (= (oracle ctx models (assoc options :limit 7))
             (dissoc (rmp/query ctx definition (assoc options :limit 7)) :work))))
    (is (= "Other company" (get-in (rmp/query other definition {:order :name}) [:rows 0 :company :name])))))
