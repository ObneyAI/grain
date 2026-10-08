(ns ai.obney.grain.read-model-processor-v2.core.read-query
  "Read-query definitions and execution over independent keyed results.
   No application callback receives a storage handle or prepared context."
  (:require [clojure.string :as str])
  (:import [java.util HashMap HashSet PriorityQueue Comparator Locale]))

(defn fail [message data]
  (throw (ex-info message (assoc data :type :grain.read-query/invalid))))

(defn- unsupported! [message data hint]
  (throw (ex-info (str message ". " hint)
                  (assoc data :type :grain.read-query/unsupported :hint hint))))

(defn normalize [value]
  (cond (nil? value) ""
        (string? value) (.toLowerCase ^String value Locale/ROOT)
        (keyword? value) (name value)
        :else value))

(defn order-key [fields]
  (when-not (and (vector? fields) (seq fields) (every? keyword? fields))
    (fail "Order fields must be a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result]
               (let [values (mapv #(normalize (get result %)) fields)]
                 (if (= 1 (count values)) (first values) values)))
    {::order fields}))

(defn search-text [fields]
  (when-not (and (vector? fields) (seq fields) (every? keyword? fields))
    (fail "Search text requires a nonempty keyword vector" {:fields fields}))
  (with-meta (fn [result] (str/join " " (map #(get result %) fields)))
    {::text {:fields fields}}))

(defn field-ref [expression]
  (when (qualified-keyword? expression)
    [(keyword (namespace expression)) (keyword (name expression))]))

(defn- parse-definition [definition registry]
  (when-not (and (map? definition) (every? #{:from :related :search :orders} (keys definition))
                 (map? (:from definition)) (= 1 (count (:from definition))))
    (fail "Read query requires one root and only from/related/search/orders" {:definition definition}))
  (let [[root model] (first (:from definition))
        related (:related definition {})
        aliases (set (cons root (keys related)))
        orders (:orders definition)
        search (:search definition [])]
    (when-not (and (map? related) (every? #(and (simple-keyword? %) (not= :key %)) aliases)
                   (not (contains? related root)) (map? orders) (seq orders)
                   (every? simple-keyword? (keys orders)) (vector? search))
      (fail "Invalid read-query bindings or orders" {:definition definition}))
    (letfn [(references [expr]
              (if-let [[alias _] (field-ref expr)]
                (do (when-not (aliases alias) (fail "Unknown result alias" {:expression expr})) #{alias})
                (if (vector? expr)
                  (case (first expr)
                    (:id :value) (do (when-not (and (= 2 (count expr)) (aliases (second expr)))
                                        (fail "Unknown result binding" {:expression expr})) #{(second expr)})
                    :param (do (when-not (and (= 2 (count expr)) (keyword? (second expr)))
                                 (fail "Invalid parameter reference" {:expression expr})) #{})
                    :call (do (when-not (and (<= 2 (count expr)) (fn? (second expr)))
                                (fail "Calculation requires a function" {:expression expr}))
                              (into #{} (mapcat references) (drop 2 expr)))
                    (fail "Unknown read-query expression" {:expression expr}))
                  (fail "Expected a field reference or expression" {:expression expr}))))]
      (doseq [[alias relation] related]
        (when-not (and (map? relation) (contains? relation :key)
                       (every? #{:model :key :missing :validate} (keys relation))
                       (or (nil? (:validate relation)) (fn? (:validate relation)))
                       (let [missing (:missing relation :error)]
                         (or (= :error missing)
                             (and (vector? missing) (= 2 (count missing)) (= :default (first missing))))))
          (fail "Invalid related-result declaration" {:alias alias :relation relation}))
        (when-not (or (field-ref (:key relation))
                      (and (vector? (:key relation)) (= :id (first (:key relation)))))
          (fail "Relationship keys must reference an instance ID or field" {:alias alias}))
        (references (:key relation)))
      (doseq [field search]
        (when-not (field-ref field) (fail "Search requires result-field references" {:field field}))
        (references field))
      (doseq [[order expressions] orders]
        (when-not (and (vector? expressions) (seq expressions))
          (fail "Ordering requires a nonempty expression vector" {:order order}))
        (doseq [expr expressions] (references expr)))
      (let [bindings (assoc related root {:model model})
            models (into {} (map (fn [[alias {:keys [model]}]]
                                   (let [d (get registry model)]
                                    (when-not (or (:key d) (:route d)) (fail "Expected registered keyed model" {:alias alias :model model}))
                                     [model d]))) bindings)
            sorted (loop [done [root] remaining related]
                     (if (empty? remaining) done
                         (let [ready (filterv #(every? (set done) (references (:key (val %)))) remaining)]
                           (when (empty? ready) (fail "Cyclic related-result definitions" {:aliases (keys remaining)}))
                           (recur (into done (map key) ready) (apply dissoc remaining (map key ready))))))]
        {:definition definition :root root :model model :bindings bindings
         :aliases sorted :models models :orders orders :search search}))))

(defn subscriptions [plan]
  (into {} (map (fn [[model d]] [model (:version d 1)])) (:models plan)))

(defn resolve-value [api ctx {:keys [bindings]} alias id ^HashMap shared]
  (let [{:keys [model missing validate] :or {missing :error}} (bindings alias)
        address [alias id]]
    (if (.containsKey shared address) (.get shared address)
        (let [absent (Object.)
              found (if (nil? id) absent ((:value api) ctx model id absent))
              value (if (identical? absent found)
                      (if (= :error missing)
                        (do (when validate (validate id nil))
                            (throw (ex-info "Required related result is missing"
                                            {:type :grain.read-query/missing :alias alias :model model :key id})))
                        (second missing)) found)]
          (when validate (validate id value))
          (.put shared address value)
          value))))

(defn row-reader [api ctx plan params ^HashMap shared id & [seed]]
  (let [local (HashMap.) root (:root plan)]
    (letfn [(binding-key [alias]
              (if (= root alias) id (evaluate (get-in plan [:bindings alias :key]))))
            (result [alias]
              (if (.containsKey local alias) (.get local alias)
                  (let [relation (get-in plan [:bindings alias])
                        value (if (contains? seed alias)
                                (let [value (get seed alias)]
                                  (when-let [validate (:validate relation)]
                                    (validate (binding-key alias) value))
                                  value)
                                ;; Share only non-identity relationships (catalog etc.).
                                (let [cache (if (or (= alias root)
                                                    (and (vector? (:key relation)) (= :id (first (:key relation)))))
                                              (HashMap.) shared)]
                                  (resolve-value api ctx plan alias (binding-key alias) cache)))]
                    (.put local alias value) value)))
            (evaluate [expr]
              (if-let [[alias field] (field-ref expr)] (get (result alias) field)
                      (case (first expr)
                        :id (binding-key (second expr))
                        :value (result (second expr))
                        :param (if (contains? params (second expr)) (get params (second expr))
                                   (fail "Missing calculation parameter" {:parameter (second expr)}))
                        :call (apply (second expr) (map evaluate (drop 2 expr))))))]
      {:evaluate evaluate
       :row (fn [] (into {:key id} (map (fn [alias] [alias (result alias)])) (:aliases plan)))})))

(defn- order-index [plan alias fields]
  (some (fn [[index extract]] (when (= fields (::order (meta extract))) index))
        (get-in plan [:models (get-in plan [:bindings alias :model]) :indexes])))

(defn- equality-index [plan alias field]
  ;; A keyword extractor means exact field equality, with no normalization.
  (some (fn [[index extract]] (when (= field extract) index))
        (get-in plan [:models (get-in plan [:bindings alias :model]) :indexes])))

(defn- same-key? [plan alias]
  (or (= alias (:root plan)) (= [:id (:root plan)] (get-in plan [:bindings alias :key]))))

(defn- group-source [plan alias]
  (when-let [[parent field] (field-ref (get-in plan [:bindings alias :key]))]
    (when (same-key? plan parent)
      (when-let [index (equality-index plan parent field)]
        {:parent parent :model (get-in plan [:bindings parent :model]) :index index}))))

(defn- observe [ctx event]
  (when-let [f (:read-model/observer ctx)] (f (assoc event :bytes 0))))

(defn- groups [api ctx plan alias shared]
  (when-let [source (group-source plan alias)]
    (let [entries ((:index-groups api) ctx (:model source) (:index source))]
      (doseq [{:keys [value]} entries] (resolve-value api ctx plan alias value shared))
      (observe ctx {:operation :related-groups :count (count entries)})
      (assoc source :entries entries))))

(defn- union-start [groups offset]
  (loop [bounds (mapv #(assoc % :lo 0 :hi (:total %)) groups)]
    (let [{:keys [lo hi slice]} (apply max-key #(- (:hi %) (:lo %)) bounds)]
      (when-not (< lo hi) (fail "Group rank search lost its target" {:offset offset}))
      (let [pivot (first (slice (+ lo (quot (- hi lo) 2)) 1))
            ranks (mapv #((:rank %) pivot) bounds)
            before (reduce + (map :rank ranks))]
        (cond (= before offset) pivot
              (< before offset) (recur (mapv (fn [g r] (assoc g :lo (+ (:rank r) (if (:present? r) 1 0)))) bounds ranks))
              :else (recur (mapv (fn [g r] (assoc g :hi (:rank r))) bounds ranks)))))))

(defn- union-page [groups offset limit]
  (if (= 1 (count groups)) ((:slice (first groups)) offset limit)
      (let [pivot (union-start groups offset)
            heap (PriorityQueue. 11 (reify Comparator (compare [_ a b] (compare (str (:id a)) (str (:id b))))))
            offer! (fn [group offset]
                     (when (< offset (:total group))
                       (.add heap {:id (first ((:slice group) offset 1)) :group group :offset offset})))]
        (doseq [g groups] (offer! g (:rank ((:rank g) pivot))))
        (loop [out []]
          (if (or (= limit (count out)) (.isEmpty heap)) out
              (let [{:keys [id group offset]} (.poll heap)]
                (when (< (inc (count out)) limit) (offer! group (inc offset)))
                (recur (conj out id))))))))

(defn page-info [total {:keys [page limit]}]
  (let [pages (max 1 (quot (+ total (dec limit)) limit))]
    {:total total :total-pages pages :page (min page pages) :page-size limit}))

(defn- grouped-page [api ctx plan alias field shared options]
  (when-let [{:keys [entries model index]} (groups api ctx plan alias shared)]
    (when ((:same-members? api) ctx (:model plan) model)
      (let [buckets (->> entries
                         (group-by #(normalize (get (resolve-value api ctx plan alias (:value %) shared) field)))
                         (sort-by key (if (= :desc (:direction options)) #(compare %2 %1) compare)))
            info (page-info (reduce + 0 (map :total entries)) options)
            make-group (fn [{:keys [value total]}]
                         (let [selection {:index index :equals value}]
                           {:total total
                            :slice (fn [offset limit] ((:index-keys api) ctx model (assoc selection :offset offset :limit limit)))
                            :rank (fn [id] ((:index-rank api) ctx model selection id))}))
            ids (loop [buckets (seq buckets) offset (* (dec (:page info)) (:limit options))
                       remaining (:limit options) out []]
                  (if (or (nil? buckets) (zero? remaining)) out
                      (let [members (val (first buckets)) total (reduce + (map :total members))]
                        (if (>= offset total) (recur (next buckets) (- offset total) remaining out)
                            (let [n (min remaining (- total offset))]
                              (recur (next buckets) 0 (- remaining n)
                                     (into out (union-page (mapv make-group members) offset n))))))))]
        (assoc info :keys ids :strategy :related-groups :related-groups (count entries))))))

(defn- text-segment [plan fields]
  (let [alias (first (field-ref (first fields)))
        names (mapv (comp second field-ref) fields)
        model (get-in plan [:bindings alias :model])
        segment (some (fn [[index extract]]
                        (when (= {:fields names} (::text (meta extract)))
                          {:alias alias :model model :index index}))
                      (get-in plan [:models model :searches]))]
    (when-not segment
      (let [extractor (list 'rmp/search-text names)]
        (unsupported! (str "Search fields " (pr-str fields) " on model " model
                           " need a matching search-text extractor")
                      {:path [:search] :alias alias :model model :fields fields
                       :suggested-extractor extractor}
                      (str "Declare an entry under :searches using " (pr-str extractor)
                           ". The fields and their order must match. Bump :version when changing stored extraction."))))
    segment))

(defn- equality-guidance [plan alias]
  (let [expression (get-in plan [:bindings alias :key])
        [parent field] (field-ref expression)
        model (get-in plan [:bindings parent :model])]
    (if (and parent (same-key? plan parent))
      {:index-model model :suggested-extractor field
       :hint (str "Declare an exact equality index on " model ", for example "
                  (pr-str {:indexes {:relation field}})
                  ". Use the raw keyword extractor to preserve IDs. Bump :version when adding the index.")}
      {:hint (str "Relationship " alias " uses " (pr-str expression)
                  ". Indexed related search and field ordering require a field lookup from the root "
                  (:root plan) " or a binding with the same instance ID. Change that relationship or exclude its fields.")})))

(defn- search-segments [plan]
  (let [parts (mapv vec (partition-by (comp first field-ref) (:search plan)))
        aliases (mapv (comp first field-ref first) parts)]
    (when-not (<= 1 (count parts) 2)
      (unsupported! (str "Search has unsupported alias segments " (pr-str aliases))
                    {:path [:search] :model (:model plan) :fields (:search plan)
                     :aliases aliases :segments (count parts)}
                    (str "Keep fields for root " (:root plan)
                         " together and first, optionally followed by fields from one related binding.")))
    (when-not (= (:root plan) (first aliases))
      (unsupported! (str "Indexed search must start with root text from " (:root plan)
                         ", but starts with " (first aliases))
                    {:path [:search] :model (:model plan) :fields (:search plan)
                     :root (:root plan) :aliases aliases}
                    "Place the root's search fields first; related-only search is not supported."))
    (let [segments (mapv #(text-segment plan %) parts)
          related (second segments)
          alias (:alias related)
          missing (get-in plan [:bindings alias :missing])]
      (when (and related (vector? missing) (some? (second missing)))
        (unsupported! (str "Search relationship " alias " on model " (:model related)
                           " has a non-nil missing default " (pr-str missing))
                      {:path [:related alias :missing] :alias alias :model (:model related)
                       :fields (second parts) :missing missing}
                      "Default text is absent from the stored search index. Remove these related fields from :search, or use :error or [:default nil] if the domain permits."))
      (when (and related (nil? (group-source plan alias)))
        (let [{:keys [hint] :as guidance} (equality-guidance plan alias)]
          (unsupported! (str "Search relationship " alias " on model " (:model related)
                             " requires indexed equality membership")
                        (merge guidance {:path [:related alias :key] :alias alias :model (:model related)
                                         :fields (second parts) :expression (get-in plan [:bindings alias :key])})
                        hint)))
      segments)))

(defn- computed-order? [expressions]
  (boolean (some #(and (vector? %) (= :call (first %))) expressions)))

(defn- unsupported-order [plan order expressions reason]
  (let [refs (mapv field-ref expressions)
        alias (when (and (every? some? refs) (apply = (map first refs))) (ffirst refs))
        model (get-in plan [:bindings alias :model])
        fields (mapv second refs)
        guidance (cond
                   (= reason :different-membership)
                   {:hint "The index source must contain exactly the root's included IDs. Restore membership agreement, or use an explicit :call calculation, which can evaluate every candidate."}
                   (and alias (same-key? plan alias))
                   (let [extractor (list 'rmp/order-key fields)]
                     {:index-model model :suggested-extractor extractor
                      :hint (str "Declare an entry under :indexes on " model " using " (pr-str extractor)
                                 ". A raw keyword or arbitrary function does not supply order-field metadata. Bump :version when changing stored extraction.")})
                   (and alias (= 1 (count fields))) (equality-guidance plan alias)
                   :else {:hint "Use fields from one supported indexed binding, or an explicit :call calculation. A calculation can evaluate every candidate."})]
    (unsupported! (str "Unsupported field ordering " order " for " (pr-str expressions)
                       (when model (str " on model " model)))
                  (merge guidance {:path [:orders order] :order order :alias alias :model model
                                   :fields expressions :expressions expressions :reason reason})
                  (:hint guidance))))

(defn compile-definition [definition registry]
  (let [plan (parse-definition definition registry)
        segments (when (seq (:search plan)) (search-segments plan))]
    (doseq [[order expressions] (:orders plan) :when (not (computed-order? expressions))]
      (let [refs (mapv field-ref expressions)
            alias (ffirst refs)
            fields (mapv second refs)]
        (when-not (and (every? some? refs) (every? #(= alias (first %)) refs)
                       (or (and (same-key? plan alias) (order-index plan alias fields))
                           (and (= 1 (count fields)) (group-source plan alias))))
          (unsupported-order plan order expressions :missing-index-support))))
    (assoc plan :search-segments segments)))

(defn- matching-keys [api ctx plan text shared]
  (let [[root related] (:search-segments plan)]
    (when-not root
      (throw (ex-info "This read query does not declare search fields" {:type :grain.read-query/unsupported})))
    (let [source (when related (groups api ctx plan (:alias related) shared))
          totals (into {} (map (juxt :value :total)) (:entries source))
          members (memoize (fn [group]
                             (if-let [total (get totals group)]
                               ((:index-keys api) ctx (:model source)
                                                  {:index (:index source) :equals group :limit total}) [])))
          search (fn [segment text mode]
                   ((:search-keys api) ctx (:model segment)
                                       {:index (:index segment) :query text :mode mode}))
          join (fn [keys] (into #{} (mapcat members) keys))
          found (HashSet. ^java.util.Collection (search root text :contains))]
      (when related
        (.addAll found ^java.util.Collection (join (search related text :contains)))
        (let [^ints points (.toArray (.codePoints ^String text))]
          (doseq [split (range 1 (alength points)) :when (= 32 (aget points (dec split)))
                  :let [left (String. points 0 (int (dec split)))
                        right (String. points (int split) (int (- (alength points) split)))
                        related-hits (join (search related right :prefix))]
                  :when (seq related-hits)
                  id (search root left :suffix) :when (contains? related-hits id)]
            (.add found id))))
      found)))

(defn- scan-keys [api ctx plan]
  (into [] (map :key) ((:values api) ctx (:model plan))))

(defn- computed-page [api ctx plan expressions keys shared options]
  ;; ALLIUM-OPEN: computed ordering still requires evaluating all candidates.
  ;; Keep the cost explicit; declaring a function does not make it indexable.
  (let [keys (or keys (scan-keys api ctx plan))
        value (fn [id _]
                (let [evaluate (:evaluate (row-reader api ctx plan (:params options) shared id))]
                  (mapv #(normalize (evaluate %)) expressions)))]
    (assoc ((:key-page api) ctx (:model plan) keys
                            (assoc (select-keys options [:page :limit :direction]) :index :identity :sort-value value))
           :strategy :computed :candidates-examined (count keys))))

(defn- ordered-page [api ctx plan keys shared options]
  (let [expressions (get (:orders plan) (:order options))
        refs (mapv field-ref expressions)
        aliases (set (map first refs))
        alias (when (and (every? some? refs) (= 1 (count aliases))) (first aliases))
        fields (mapv second refs)
        model (get-in plan [:bindings alias :model])
        index (when alias (order-index plan alias fields))
        ranked? (contains? (get-in plan [:models model :filtered-indexes]) index)
        selection (assoc (select-keys options [:page :limit :direction]) :index index)
        root? (= alias (:root plan))
        same? (and index (same-key? plan alias)
                   (or root? ((:same-members? api) ctx (:model plan) model)))]
    (cond
      (and index same?)
      (if keys
        (assoc ((if ranked? (:filtered-key-page api) (:key-page api)) ctx model keys selection)
               :strategy (if ranked? :ranked-matches :indexed-matches))
        (let [page ((:page api) ctx model selection)]
          (assoc (dissoc page :entries) :keys (mapv :key (:entries page)) :strategy :indexed
                 :seeds (into {} (map (fn [{:keys [key value]}] [key {alias value}])) (:entries page)))))

      (and (nil? keys) alias (= 1 (count fields)) (group-source plan alias))
      (or (grouped-page api ctx plan alias (first fields) shared options)
          (unsupported-order plan (:order options) expressions :different-membership))

      (and keys alias (= 1 (count fields)) (group-source plan alias))
      (let [{:keys [model index]} (group-source plan alias)]
        (if ((:same-members? api) ctx (:model plan) model)
          (assoc ((:key-page api) ctx model keys
                                  (assoc (select-keys options [:page :limit :direction]) :index index
                                         :sort-value (fn [_ related-id]
                                                       (normalize (get (resolve-value api ctx plan alias related-id shared)
                                                                       (first fields))))))
                 :strategy :related-matches)
          (unsupported-order plan (:order options) expressions :different-membership)))

      (and index ranked? (same-key? plan alias)
           (vector? (get-in plan [:bindings alias :missing]))
           (= :default (first (get-in plan [:bindings alias :missing]))))
      (let [extract (get-in plan [:models model :indexes index])
            default (second (get-in plan [:bindings alias :missing]))]
        (assoc ((:joined-page api) ctx (:model plan) model
                                   (cond-> (assoc selection :missing-value (extract default)) keys (assoc :keys keys)))
               :strategy :joined))

      (computed-order? expressions) (computed-page api ctx plan expressions keys shared options)

      :else (unsupported-order plan (:order options) expressions :different-membership))))

(defn execute [api ctx plan request]
  (when-not (and (map? request) (every? #{:params :search :order :direction :page :limit} (keys request)))
    (fail "Unknown read-query request option" {:request request}))
  (let [options (merge {:params {} :search "" :direction :asc :page 1 :limit 25} request)
        _ (when-not (and (map? (:params options)) (string? (:search options))
                         (contains? (:orders plan) (:order options)) (#{:asc :desc} (:direction options))
                         (pos-int? (:page options)) (pos-int? (:limit options)))
            (fail "Invalid read-query page, ordering or parameters" {:request request}))
        text (normalize (str/trim (:search options)))
        options (assoc options :search text)
        run (fn [prepared]
              (let [shared (HashMap.)
                    keys (when (seq text) (matching-keys api prepared plan text shared))
                    selected (ordered-page api prepared plan keys shared options)
                    rows (mapv (fn [id] ((:row (row-reader api prepared plan (:params options) shared id
                                                           (get-in selected [:seeds id]))))) (:keys selected))]
                (observe prepared {:operation :read-query-plan :strategy (:strategy selected)
                                   :candidates-examined (:candidates-examined selected 0)})
                (assoc (select-keys selected [:total :total-pages :page :page-size])
                       :rows rows :work (dissoc selected :keys :seeds :total :total-pages :page :page-size))))]
    (if (and (seq text) (< (.codePointCount ^String text 0 (count text)) 3))
      {:rows [] :total 0 :total-pages 1 :page 1 :page-size (:limit options)
       :search-error "Enter at least 3 characters to search." :work {:strategy :unsupported-short-search}}
      ((:read-session api) ctx (keys (:models plan)) run))))
