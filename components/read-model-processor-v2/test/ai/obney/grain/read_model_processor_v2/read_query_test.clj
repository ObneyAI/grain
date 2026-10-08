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
                                   :filtered-indexes #{:amount}})
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
    (is (= "Fallback" (get-in (rmp/query fixture/*ctx* (dissoc definition :search) {:order :name})
                              [:rows 0 :company :name])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-nil missing default"
                          (rmp/query-models definition)))
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

(deftest related-validation-is-independent-of-page-strategy
  (let [{:keys [definition]} (setup)
        checked (assoc-in definition [:related :ledger :validate]
                          (fn [_ _] (throw (ex-info "Invalid ledger" {}))))]
    (seed!)
    (doseq [order [:name :amount] search ["" "example"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid ledger"
                            (rmp/query fixture/*ctx* checked {:order order :search search}))))))

(deftest unsupported-field-orders-fail-before-reading
  (let [root (fixture/register! (fn [_ e] (:customer e)) {:indexes {:name :name}})
        definition {:from {:customer root} :orders {:name [:customer/name]}}
        seen (atom [])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ordering"
                          (rmp/query-models definition)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (rmp/query (assoc fixture/*ctx* :read-model/observer #(swap! seen conj %))
                            definition {:order :name})))
    (is (empty? @seen))))

(deftest search-definition-is-checked-before-reading
  (let [{:keys [definition]} (setup)]
    (doseq [bad [(assoc definition :search [:company/name])
                 (assoc definition :search [:customer/name])
                 (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}])]]
      (is (thrown? clojure.lang.ExceptionInfo (rmp/query-models bad))))))

(defn definition-error [definition]
  (try (rmp/query-models definition) nil
       (catch clojure.lang.ExceptionInfo e {:message (ex-message e) :data (ex-data e)})))

(deftest index-errors-give-usable-extractor-corrections
  (let [step (fn [_ e] (:customer e))
        root (fixture/register! step {:indexes {:name :name}})
        definition {:from {:customer root} :orders {:name [:customer/name]}}
        {:keys [message data]} (definition-error definition)
        extractor (:suggested-extractor data)]
    (is (= [:orders :name] (:path data)))
    (is (= root (:model data)))
    (is (= [:customer/name] (:fields data)))
    (is (= '(rmp/order-key [:name]) extractor))
    (is (str/includes? message (str root)))
    (is (str/includes? message (pr-str extractor)))
    (rmp/register-read-model! root step {:events #{:keyed/change} :key :owner
                                        :indexes {:name (apply rmp/order-key (rest extractor))}})
    (is (= {root 1} (rmp/query-models definition)))
    (let [searchable (assoc definition :search [:customer/name])
          {:keys [message data]} (definition-error searchable)
          extractor (:suggested-extractor data)]
      (is (= [:search] (:path data)))
      (is (= root (:model data)))
      (is (= '(rmp/search-text [:name]) extractor))
      (is (str/includes? message (pr-str extractor)))
      (rmp/register-read-model! root step {:events #{:keyed/change} :key :owner
                                          :indexes {:name (rmp/order-key [:name])}
                                          :searches {:text (apply rmp/search-text (rest extractor))}})
      (is (= {root 1} (rmp/query-models searchable))))))

(deftest search-errors-identify-the-offending-shape-and-relationship
  (let [{:keys [definition customer company]} (setup)
        bad-start (definition-error (assoc definition :search [:company/name]))
        bad-segments (definition-error (assoc definition :search [:customer/name :company/name :customer/email]))
        bad-default (definition-error (assoc-in definition [:related :company :missing] [:default {:name "Fallback"}]))]
    (is (= [:search] (get-in bad-start [:data :path])))
    (is (= :customer (get-in bad-start [:data :root])))
    (is (str/includes? (:message bad-start) ":customer"))
    (is (= [:customer :company :customer] (get-in bad-segments [:data :aliases])))
    (is (re-find #"together and first" (get-in bad-segments [:data :hint])))
    (is (= [:related :company :missing] (get-in bad-default [:data :path])))
    (is (= company (get-in bad-default [:data :model])))
    (is (= [:company/name] (get-in bad-default [:data :fields])))
    (is (re-find #"if the domain permits" (get-in bad-default [:data :hint])))
    (let [stored (get (rmp/global-read-model-registry) customer)
          opts (-> stored (dissoc :reducer-fn) (update :indexes dissoc :company))]
      (rmp/register-read-model! customer (:reducer-fn stored) opts)
      (let [{:keys [message data]} (definition-error definition)]
        (is (= [:related :company :key] (:path data)))
        (is (= company (:model data)))
        (is (= customer (:index-model data)))
        (is (= :company (:suggested-extractor data)))
        (is (re-find #"raw keyword extractor" message))
        (rmp/register-read-model! customer (:reducer-fn stored)
                                  (assoc-in opts [:indexes :company] (:suggested-extractor data)))
        (is (= 3 (count (rmp/query-models definition))))))))

(deftest one-text-index-serves-root-and-related-search
  (let [customer (fixture/register! (fn [_ e] (:customer e))
                                    {:indexes {:name (rmp/order-key [:name]) :company :company}
                                     :searches {:text (rmp/search-text [:name :email])}})
        company (fixture/register! (fn [_ e] (:company e))
                                   {:key :company-id :searches {:text (rmp/search-text [:name])}})
        root-only {:from {:customer customer} :search [:customer/name :customer/email]
                   :orders {:name [:customer/name]}}
        related (assoc root-only :related {:company {:model company :key :customer/company}}
                                 :search [:customer/name :customer/email :company/name])]
    (fixture/append! [{:owner "a" :company-id "c" :company {:name "Acme"}
                       :customer {:name "Anna" :email "mail.test" :company "c"}}])
    (doseq [[definition text] [[root-only "anna"] [related "anna"]
                               [related "acme"] [related "test acm"]]]
      (is (= ["a"] (mapv :key (:rows (rmp/query fixture/*ctx* definition {:order :name :search text}))))))))

(deftest changed-membership-does-not-turn-field-ordering-into-a-scan
  (let [{:keys [definition]} (setup)
        checked (-> definition
                    (update-in [:related :ledger] dissoc :missing)
                    (assoc :search [:customer/name :customer/email]))
        seen (atom []) ctx (assoc fixture/*ctx* :read-model/observer #(swap! seen conj %))]
    (seed!)
    (is (= :indexed (get-in (rmp/query ctx checked {:order :amount}) [:work :strategy])))
    (fixture/append! [{:owner "outsider" :company-id "unused" :ledger {:amount -999}}])
    ;; Catch up first so the observations below concern query execution only.
    (rmp/read-session ctx (keys (rmp/query-models checked)) (constantly nil))
    (doseq [search ["" "example"]]
      (reset! seen [])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported field ordering"
                            (rmp/query ctx checked {:order :amount :search search})))
      (is (not-any? #(= :result-decoded (:operation %)) @seen)))))

(deftest oversized-index-keys-fail-without-unindexed-replay
  (let [reductions (atom 0)
        root (fixture/register! (fn [_ e] (swap! reductions inc) (:customer e))
                                {:indexes {:name (rmp/order-key [:name])}})
        definition {:from {:customer root} :orders {:name [:customer/name]}}]
    (fixture/append! [{:owner "a" :customer {:name (apply str (repeat 600 "x"))}}])
    (is (= :grain.read-model/key-too-large
           (try (rmp/query fixture/*ctx* definition {:order :name})
                (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
    (is (= 1 @reductions))))

(deftest search-boundaries-match-literal-composition-with-either-index-spelling
  (doseq [suffix ["" " "]]
    (let [customer (fixture/register! (fn [_ e] (:customer e))
                                      {:indexes {:name (rmp/order-key [:name]) :company :company}
                                       :searches {:text (rmp/search-text [:name :email] {:suffix suffix})}})
          company (fixture/register! (fn [_ e] (:company e))
                                     {:key :company-id :searches {:text (rmp/search-text [:name])}})
          root-only {:from {:customer customer} :search [:customer/name :customer/email]
                     :orders {:name [:customer/name]}}
          related (assoc root-only :related {:company {:model company :key :customer/company}}
                                   :search [:customer/name :customer/email :company/name])
          examples [["Anna" "mail.test" "Acme"] ["BOB" "  tail " " Beta"]
                    ["😀𐐀" "end😀" "𐐀Co"] ["Empty" "" "Company"]]
          texts (mapv (fn [[name email company]] [(str name " " email) (str name " " email " " company)]) examples)
          queries ["anna" "test acm" "tail   be" "tail  be" "😀 𐐀" "end😀 𐐀" "empty  com" "bob" "missing"]]
      (fixture/append! (mapv (fn [i [name email company]]
                              {:owner (str i) :company-id (str "c" i) :company {:name company}
                               :customer {:name name :email email :company (str "c" i)}})
                            (range) examples))
      (doseq [[definition column] [[root-only 0] [related 1]] query queries]
        (let [expected (into #{} (keep-indexed (fn [i fields]
                                                (when (str/includes? (str/lower-case (nth fields column))
                                                                     (str/lower-case query)) (str i)))) texts)]
          (is (= expected (set (map :key (:rows (rmp/query fixture/*ctx* definition
                                                          {:order :name :search query})))))
              (pr-str {:suffix suffix :related? (= column 1) :query query})))))))
