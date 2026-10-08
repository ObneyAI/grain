(ns ai.obney.grain.read-model-processor-v2.core.keyed
  "Independent pure keyed folds. Application callbacks receive detached values."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v2.core.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.core.ranked-set :as ranked]
            [ai.obney.grain.read-model-processor-v2.core.text-search :as search]
            [ai.obney.grain.read-model-processor-v2.core.sort-data :as sort-data]
            [ai.obney.grain.read-model-processor-v2.core.instance-ids :as ids]
            [ai.obney.grain.read-model-processor-v2.core.span-membership :as membership]
            [malli.core :as m])
  (:import [org.roaringbitmap RoaringBitmap]
           [java.util Arrays ArrayList HashSet]
           [ai.obney.grain.read_model_processor_v2.core.ranked_set VirtualMember]))

(defn keyed? [definition]
  (or (contains? definition :key) (contains? definition :route)))

(defn validate! [name opts]
  (when-not (map? opts)
    (throw (ex-info "Read model options must be a map" {:model name})))
  (let [keyed-options #{:events :version :key :route :initial :result :include? :indexes
                        :searches :filtered-indexes :schema :state-schema :result-schema}
        ordinary-options #{:events :version :schema :partition-fn :entity-id-fn :l1-ttl-ms}
        keyed? (boolean (keyed? opts))
        supported (if keyed? keyed-options ordinary-options)
        invalid (into #{} (remove supported) (keys opts))]
    (when (seq invalid)
      (let [unknown (into #{} (remove (into keyed-options ordinary-options)) invalid)
            inapplicable (into #{} (remove unknown) invalid)
            supported (vec (sort supported))
            hint (str (when (seq inapplicable)
                        (str (pr-str (vec (sort inapplicable)))
                             (if keyed?
                               " apply only to ordinary models read with project; remove them from this keyed model. "
                               " require a keyed model; remove them or supply exactly one of :key or :route. ")))
                      "Supported options: " (pr-str supported) ".")]
        (throw (ex-info (str "Read model " name " has unknown or inapplicable options "
                             (pr-str (vec (sort-by pr-str invalid))) ". " hint)
                        {:type :grain.read-model/invalid :model name :options invalid :keyed? keyed?
                         :unknown-options unknown :inapplicable-options inapplicable
                         :supported-options supported :hint hint})))))
  (when (keyed? opts)
    (when-not (and (qualified-keyword? name) (pos-int? (:version opts 1))
                   (set? (:events opts)) (seq (:events opts))
                   (every? qualified-keyword? (:events opts))
                   (not= (contains? opts :key) (contains? opts :route))
                   (ifn? (if (contains? opts :route) (:route opts) (:key opts)))
                   (ifn? (:result opts identity))
                   (ifn? (:include? opts (constantly true)))
                   (map? (:indexes opts {}))
                   (map? (:searches opts {}))
                   (set? (:filtered-indexes opts #{}))
                   (every? #(or (= :identity %) (contains? (:indexes opts) %)) (:filtered-indexes opts))
                   (every? (fn [[k f]] (and (keyword? k) (ifn? f))) (:searches opts))
                   (every? (fn [[k f]] (and (keyword? k) (not= :identity k) (ifn? f)))
                           (:indexes opts)))
      (throw (ex-info "Invalid keyed-fold declaration" {:model name})))
    (doseq [schema (keep opts [:schema :state-schema :result-schema])] (m/schema schema))))

(defn- observe! [context operation size]
  (when-let [f (:read-model/observer context)] (f {:operation operation :bytes size})))

(defn- decode [ctx operation bytes]
  (when bytes
    (observe! ctx operation (alength ^bytes bytes))
    (codec/decode bytes)))

(defn- base [ctx name d] (key/prefix ctx name (:version d 1)))
(defn- address [b kind id] (key/checked (key/join b (byte-array [kind]) (key/encode id))))
(defn- progress-key [b] (key/join b (byte-array [0])))
(defn- count-key [b] (key/join b (byte-array [4])))
(defn- included-key [b] (key/join b (byte-array [12])))
(defn- index-config [b index direction]
  (ranked/config (key/join b (byte-array [5]) (key/encode index)
                           (byte-array [(if (= direction :desc) 1 0)]))))
(defn- index-prefix [b index direction]
  (ranked/prefix (index-config b index direction) 0))
(defn- membership-config [b d index direction]
  (assoc (index-config b index direction) :membership? (contains? (:filtered-indexes d) index)))
(defn- sort-bytes [value direction]
  (cond-> (key/encode value) (= direction :desc) key/descending))
(defn- index-member [direction value id]
  (key/join (sort-bytes value direction) (key/encode (str id)) (key/encode id)))

(defn- update-group! [tx b index value delta]
  ;; Levels 0..5 are the RankedSet. Level 6 stores one count per sort value,
  ;; shared by both directions; no duplicated root membership collection.
  (let [k (key/checked (key/join (ranked/prefix (index-config b index :asc) 6) (key/encode value)))
        before (some-> ((:get tx) k) codec/decode :total)
        total (+ (or before 0) delta)]
    (when (neg? total) (throw (ex-info "Negative index group count" {:index index :value value})))
    (if (zero? total) ((:delete tx) k)
      ((:put tx) k (codec/encode {:value value :total total})))))

(defn- remove-index! [tx b d index direction position id]
  (when (ranked/remove! tx (membership-config b d index direction) (index-member direction position id))
    (when (= direction :asc) (update-group! tx b index position -1))))

(defn- add-index! [ctx tx b d index direction position id]
  (let [cfg (membership-config b d index direction)
        member (index-member direction position id)
        payload (codec/encode id)]
    (ranked/ensure-init! tx cfg)
    (when (ranked/add! tx cfg member payload
                       (when (:membership? cfg) (ids/ensure-ordinal! tx b id)))
      (when (= direction :asc) (update-group! tx b index position 1)))
    (observe! ctx :index-written (alength ^bytes payload))))

(defn- read-meta [tx k] (some-> ((:get tx) k) codec/decode))
(defn- write! [tx k v] ((:put tx) k (codec/encode v)))
(defn- write-observed! [ctx tx operation k value]
  (let [bytes (codec/encode value)]
    ((:put tx) k bytes)
    (observe! ctx operation (alength ^bytes bytes))))
(defn- prepare-validation [schema name kind]
  (if schema
    (let [schema (m/schema schema)
          valid? (m/validator schema)]
      (fn [value]
        (when-not (valid? value)
          (throw (ex-info "Keyed-fold schema validation failed"
                          {:model name :kind kind :explanation (m/explain schema value)})))))
    (constantly nil)))

(defn- apply-batch! [ctx name d b validate-state! validate-result! events]
  (kv/transact (:cache ctx) :write
    (fn [tx]
      (let [tx (membership/transaction tx)
            identity-base (force (::shared-identity-prefix ctx))
            before-progress (read-meta tx (progress-key b))
            events (filterv #(or (nil? (:watermark before-progress))
                                (pos? (compare (:event/id %) (:watermark before-progress)))) events)
            apply-input (fn [states id input]
                          (when (nil? id)
                            (throw (ex-info "Keyed-fold event has no owner"
                                            {:model name :event-id (:event/id input)
                                             :event-type (:event/type input)})))
                          (let [k (address b 1 id)
                                state (if (contains? states id) (get states id)
                                        (if-let [raw ((:get tx) k)]
                                          (decode ctx :state-decoded raw) (:initial d {})))
                                next ((:reducer-fn d) state input)]
                            (validate-state! next)
                            (observe! ctx :event-applied 0)
                            (assoc states id next)))
            states (reduce
                    (fn [states event]
                      (if-let [route (:route d)]
                        (let [inputs (route event)]
                          (when-not (sequential? inputs)
                            (throw (ex-info "Route must return an ordered sequence of [owner input] pairs"
                                            {:model name :event-id (:event/id event)})))
                          (reduce (fn [states entry]
                                    (when-not (and (vector? entry) (= 2 (count entry)))
                                      (throw (ex-info "Invalid routed [owner input] pair"
                                                      {:model name :event-id (:event/id event)})))
                                    (apply-input states (nth entry 0) (nth entry 1)))
                                  states inputs))
                        (apply-input states ((:key d) event) event))) {} events)
            count-before (or (read-meta tx (count-key b)) 0)
            added (volatile! 0)]
        (doseq [[id state] states]
          (let [value ((:result d identity) state)
                _ (validate-result! value)
                result-k (address b 2 id)
                old (decode ctx :result-decoded ((:get tx) result-k))
                included? ((:include? d (constantly true)) value)
                indexes (when included?
                          (assoc (into {} (map (fn [[index f]] [index (f value)])) (:indexes d))
                                 :identity (str id)))]
            (observe! ctx :result-calculated 0)
            ;; State must advance even when its completion value is equal.
            (write-observed! ctx tx :state-written (address b 1 id) state)
            (vswap! added + (- (if included? 1 0) (if old 1 0)))
            (when (not= (boolean included?) (boolean old))
              (let [ordinal (ids/ensure-ordinal! tx identity-base id)
                    bits (membership/bitmap tx (included-key b))]
                (if included? (.add ^RoaringBitmap bits (int ordinal))
                    (.remove ^RoaringBitmap bits (int ordinal)))
                (membership/put! tx (included-key b) bits)))
            (if-not included?
              (when old
                (search/update! tx b (:searches d) id old nil)
                (doseq [[index position] (:indexes old) direction [:asc :desc]]
                  (remove-index! tx b d index direction position id))
                ((:delete tx) (address b 11 id))
                ((:delete tx) result-k))
              (when (or (nil? old) (not= (:value old) value))
                (search/update! tx b (:searches d) id old {:value value})
                (doseq [[index position] indexes
                        direction [:asc :desc]
                        :when (or (nil? old) (not= position (get-in old [:indexes index])))]
                  (when old
                    (remove-index! tx b d index direction (get-in old [:indexes index]) id))
                  (add-index! ctx tx b d index direction position id))
                ((:put tx) (address b 11 id) (sort-data/encode id (dissoc indexes :identity)))
                (write-observed! ctx tx :result-written result-k {:key id :value value :indexes indexes})))))
        (membership/flush! tx #(observe! ctx %1 %2))
        (when (seq events)
          (when-not (zero? @added) (write! tx (count-key b) (+ count-before @added)))
          (write! tx (progress-key b) (assoc before-progress :watermark (:event/id (peek events)))))))))

(defn- head [ctx]
  (let [result (es/head (:event-store ctx) (:tenant-id ctx))]
    (when (:cognitect.anomalies/category result)
      (throw (ex-info "Cannot read the committed event-store head" result)))
    result))

(defn- catch-up! [ctx name d b through]
  (let [progress (kv/transact (:cache ctx) :read #(read-meta % (progress-key b)))
        after (last (sort (keep #(get progress %) [:checked-through :watermark])))
        batch-size (:read-model/batch-size ctx 128)]
    (when-not (pos-int? batch-size)
      (throw (ex-info "Keyed-fold batch size must be positive" {:batch-size batch-size})))
    (when (and through (or (nil? after) (pos? (compare through after))))
      (let [validate-state! (prepare-validation (or (:state-schema d) (:schema d)) name :state)
            validate-result! (prepare-validation (:result-schema d) name :result)
            remaining (reduce
                       (fn [batch event]
                         (if (pos? (compare (:event/id event) through)) (reduced batch)
                           (let [batch (conj batch event)]
                             (if (= batch-size (count batch))
                               (do (apply-batch! ctx name d b validate-state! validate-result! batch) []) batch))))
                       [] (es/read (:event-store ctx)
                                   (cond-> {:tenant-id (:tenant-id ctx) :types (:events d)}
                                     after (assoc :after after))))]
        (when (seq remaining) (apply-batch! ctx name d b validate-state! validate-result! remaining)))
      (kv/transact (:cache ctx) :write
        (fn [tx]
          (let [p (read-meta tx (progress-key b)) old (:checked-through p)]
            (when (or (nil? old) (pos? (compare through old)))
              (write! tx (progress-key b) (assoc p :checked-through through)))))))))

(defn- identity-of [ctx] (:tenant-id ctx))

(defn session-definition [ctx name]
  (when-let [s (::session ctx)]
    (when-not (and (= (:identity s) (identity-of ctx))
                   (identical? (:cache s) (:cache ctx))
                   (identical? (:event-store s) (:event-store ctx)))
      (throw (ex-info "Read-session context identity was changed" {:model name})))
    (or (get (:definitions s) name)
        (throw (ex-info "Model was not prepared in this read session" {:model name})))))

(defn- prepare-reader [tx b d]
  ;; Both point reads and traversals use the same prepared address space.
  ;; The transaction still enforces callback/thread lifetime on every access.
  (let [read (fn [kind id] ((:get tx) (address b kind id)))]
    (fn [f] (f tx b d read))))

(defn read-session
  "Prepare explicit [context model-names] requests, then pass their contexts to f.
   All requests must share a cache and event store. Callers supply trusted tenant
   context; definitions never select tenants. No transaction escapes to callbacks."
  [requests registry f]
  (when-not (seq requests) (throw (ex-info "Read session requires at least one request" {})))
  (let [cache (:cache (ffirst requests))
        event-store (:event-store (ffirst requests))]
    (when-not cache (throw (ex-info "Read session requires :cache" {})))
    (let [prefixes
          (reduce (fn [prefixes [ctx names]]
                    (when (::session ctx)
                      (throw (ex-info "Cannot open a nested keyed-fold read session" {})))
                    (when-not (identical? cache (:cache ctx))
                      (throw (ex-info "Read-session contexts must share one store" {})))
                    (when-not (identical? event-store (:event-store ctx))
                      (throw (ex-info "Read-session contexts must share one event store" {})))
                    (reduce (fn [prefixes name]
                              (let [id [(identity-of ctx) name]
                                    d (get registry name)]
                                (when-not (keyed? d)
                                  (throw (ex-info "Expected a registered keyed fold" {:model name})))
                                (if (contains? prefixes id) prefixes
                                  (assoc prefixes id (base ctx name d)))))
                            prefixes names)) {} requests)
          identity-prefixes (into {} (map (fn [[ctx _]]
                                            [(identity-of ctx)
                                             (delay (key/prefix ctx :grain.read-query/shared-identities 1))])) requests)
          prepared
          (locking cache
            (let [heads (reduce (fn [heads [ctx _]]
                                 (let [id (identity-of ctx)]
                                   (if (contains? heads id) heads (assoc heads id (head ctx))))) {} requests)]
              (mapv (fn [[ctx names]]
                      (doseq [name (distinct names)]
                        (catch-up! (assoc ctx ::shared-identity-prefix (get identity-prefixes (identity-of ctx)))
                                   name (get registry name)
                                   (get prefixes [(identity-of ctx) name])
                                   (get heads (identity-of ctx))))
                      [ctx (select-keys registry names)]) requests)))]
      ;; Catch-up is serialized; detached result composition can run concurrently.
      ;; A writer may advance before this snapshot opens, which the finite-head
      ;; contract permits. The snapshot still includes all captured-head effects.
      (kv/transact cache :read
        (fn [tx]
          (f (mapv (fn [[ctx definitions]]
                     (assoc ctx ::session
                            {:cache cache :event-store event-store :identity (identity-of ctx)
                             :definitions definitions
                             :readers (into {} (map (fn [[name d]]
                                                      [name (prepare-reader tx
                                                              (get prefixes [(identity-of ctx) name]) d)]))
                                            definitions)})) prepared)))))))

(defn- with-reader [ctx name d f]
  (if-let [s (::session ctx)]
    (do (session-definition ctx name)
        ((get (:readers s) name) f))
    (read-session [[ctx [name]]] {name d}
                  (fn [[prepared]] ((get-in prepared [::session :readers name]) f)))))

(defn point [ctx name d id kind not-found]
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (with-reader ctx name d
    (fn [_tx _b _d read]
      (if-let [raw (read (if (= kind :state) 1 2) id)]
        (let [decoded (decode ctx (if (= kind :state) :state-decoded :result-decoded) raw)]
          (if (= kind :state) decoded (:value decoded)))
        not-found))))

(defn same-members? [ctx a ad b bd]
  ;; The shared ordinal namespace is tenant scoped; these are exact sets,
  ;; not cardinality or hash-based guesses. Called within a prepared session.
  (with-reader ctx a ad
    (fn [tx ab _ _]
      (with-reader ctx b bd
        (fn [_ bb _ _]
          (let [left ((:get tx) (included-key ab))
                right ((:get tx) (included-key bb))]
            (observe! ctx :identity-membership-read (+ (if left (alength ^bytes left) 0)
                                                     (if right (alength ^bytes right) 0)))
            (= (if left (membership/decode left) (RoaringBitmap.))
               (if right (membership/decode right) (RoaringBitmap.)))))))))

(defn progress [ctx name d]
  (with-reader ctx name d (fn [tx b _d _read] (read-meta tx (progress-key b)))))

(defn search-keys [ctx name d options]
  (with-reader ctx name d
    (fn [tx b _ _read]
      (search/search-keys tx b (:searches d) options #(observe! ctx %1 %2)))))

(defn- reduce-distinct [rf initial items]
  (let [seen (volatile! (transient #{}))]
    (reduce (fn [acc item]
              (if (contains? @seen item) acc
                (do (vreset! seen (conj! @seen item))
                    (rf acc item))))
            initial items)))

(defn index-values [ctx name d indexes ids]
  (when-not (and (vector? indexes) (every? #(or (= :identity %) (contains? (:indexes d) %)) indexes))
    (throw (ex-info "Expected a vector of declared index names" {:model name :indexes indexes})))
  (let [fields (mapv #(sort-data/field (:indexes d) %) indexes)]
    (reify clojure.lang.IReduceInit
      (reduce [_ rf initial]
        (with-reader ctx name d
          (fn [tx b _ read]
            (let [step (fn [acc raw]
                         (observe! ctx :sort-key-read (alength ^bytes raw))
                         (rf acc {:key (sort-data/value raw 1)
                                  :value (sort-data/values raw indexes fields)}))]
              (if (some? ids)
                (reduce-distinct (fn [acc id] (if-let [raw (read 11 id)] (step acc raw) acc)) initial ids)
                ((:reduce tx) {:prefix (key/join b (byte-array [11]))}
                 (fn [acc {:keys [v]}] (step acc v)) initial)))))))))

(defn key-page [ctx name d keys options]
  (let [{:keys [index indexes direction page limit sort-value include?]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :indexes :direction :page :limit :sort-value :include?} (clojure.core/keys options))
                   (or (= index :identity) (contains? (:indexes d) index))
                   (or (not (contains? options :indexes))
                       (and (or sort-value include?) (vector? indexes)
                            (every? #(or (= :identity %) (contains? (:indexes d) %)) indexes)))
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit)
                   (or (nil? sort-value) (ifn? sort-value))
                   (or (nil? include?) (ifn? include?)))
      (throw (ex-info "Invalid matching-key page" {:model name :options options})))
    (with-reader ctx name d
      (fn [tx b _d read]
        (let [field (sort-data/field (:indexes d) index)
              fields (mapv #(sort-data/field (:indexes d) %) indexes)
              comparator (sort-data/comparator field (= direction :desc) (some? sort-value))
              ;; Keep the worst retained entry at the front, so later candidates
              ;; outside the requested prefix can be discarded immediately.
              entries (java.util.PriorityQueue. 11 (.reversed ^java.util.Comparator comparator))
              wanted (long (min Long/MAX_VALUE (*' page limit)))
              ;; Inline aset keeps these counters primitive; aset-long boxes
              ;; its value argument on each candidate.
              ^longs counts (long-array 2)
              collect! (fn [id raw]
                         (observe! ctx :sort-key-read (alength ^bytes raw))
                         (aset counts 0 (inc (aget counts 0)))
                         (let [values (when (or include? sort-value)
                                        (if (some? indexes)
                                          (sort-data/values raw indexes fields)
                                          (sort-data/value raw field)))]
                           (when (or (nil? include?) (include? id values))
                             (aset counts 1 (inc (aget counts 1)))
                             (let [entry (ai.obney.grain.read_model_processor_v2.core.sort_data.Entry.
                                          id raw (when sort-value (key/encode (sort-value id values))))]
                               (if (< (.size entries) wanted)
                                 (.add entries entry)
                                 (when (neg? (.compare ^java.util.Comparator comparator entry (.peek entries)))
                                   (.poll entries)
                                   (.add entries entry)))))))]
          (if (= ::all keys)
            ((:reduce tx) {:prefix (key/join b (byte-array [11]))}
             (fn [_ {:keys [v]}] (collect! (sort-data/value v 1) v)) nil)
            (let [seen (java.util.HashSet.)]
              (doseq [id keys :when (.add seen id)]
                (when-let [raw (read 11 id)] (collect! id raw)))))
          (let [^objects ordered (.toArray entries)
                _ (Arrays/sort ordered ^java.util.Comparator comparator)
                total (aget counts 1) pages (max 1 (quot (+' total (dec limit)) limit))
                page (min page pages) offset (* (dec page) limit)]
            {:keys (mapv #(.-id ^ai.obney.grain.read_model_processor_v2.core.sort_data.Entry (aget ordered %))
                         (range offset (min total (+ offset limit))))
             :total total :total-pages pages :page page :page-size limit
             :sort-keys-read (aget counts 0)}))))))

(defn filtered-key-page [ctx name d keys options]
  (let [{:keys [index direction page limit]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :direction :page :limit} (clojure.core/keys options))
                   (contains? (:filtered-indexes d) index)
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit))
      (throw (ex-info "Ranked key page requires an opted-in stored index" {:model name :options options})))
    (with-reader ctx name d
      (fn [tx b _d _read]
        (let [candidates (RoaringBitmap.) examined (volatile! 0)]
          (doseq [id keys]
            (vswap! examined inc)
            (let [ordinal (ids/ordinal tx b id)]
              (observe! ctx :candidate-ordinal-read (if (some? ordinal) 4 0))
              (when (some? ordinal) (.add candidates (int ordinal)))))
          (let [result (ranked/filtered-page tx (membership-config b d index direction)
                                            candidates page limit #(observe! ctx %1 %2))]
            (-> result
                (dissoc :payloads)
                (assoc :keys (mapv codec/decode (:payloads result))
                       :candidate-keys-read @examined :sort-keys-read 0))))))))

(defn joined-page [ctx membership-name membership-d name d options]
  (let [{:keys [index direction page limit missing-value]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options)
                   (every? #{:index :direction :page :limit :missing-value :keys} (clojure.core/keys options))
                   (contains? options :missing-value) (contains? (:filtered-indexes d) index)
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit))
      (throw (ex-info "Joined page requires a ranked index and an explicit missing value"
                      {:model name :options options})))
    (let [missing-prefix (sort-bytes missing-value direction)]
      (if-not (::session ctx)
        (read-session [[ctx [membership-name name]]] {membership-name membership-d name d}
                      (fn [[prepared]] (joined-page prepared membership-name membership-d name d options)))
        (with-reader ctx membership-name membership-d
          (fn [tx membership-base _ membership-read]
            (with-reader ctx name d
              (fn [_ b _ _]
                (let [cfg (membership-config b d index direction)
                      observe #(observe! ctx %1 %2)
                      live (ranked/members tx cfg observe)
                      candidates (RoaringBitmap.) virtual (ArrayList.)
                      examined (volatile! 0)
                      collect! (fn [id]
                                 (vswap! examined inc)
                                 (let [ordinal (ids/ordinal tx b id)]
                                   (observe :candidate-ordinal-read (if (some? ordinal) 4 0))
                                   (if (and (some? ordinal) (.contains live (int ordinal)))
                                     (.add candidates (int ordinal))
                                     (do (observe :join-default-key 0)
                                         (.add virtual (VirtualMember.
                                                         (key/join missing-prefix (key/encode (str id)) (key/encode id))
                                                         (codec/encode id)))))))]
                  (if (contains? options :keys)
                    (let [seen (HashSet.)]
                      (doseq [id (:keys options) :when (.add seen id)]
                        (let [raw (membership-read 11 id)]
                          (observe :join-membership-probe (if raw (alength ^bytes raw) 0))
                          (when raw (collect! id))))
                      ;; Only absent/excluded ordering keys need this tie sort.
                      (.sort virtual
                             (fn [^VirtualMember a ^VirtualMember b]
                               (Arrays/compareUnsigned (.-member a) (.-member b)))))
                    ;; Identity order supplies default ties directly, even for desc.
                    (let [prefix (index-prefix membership-base :identity :asc)]
                      ((:reduce tx) {:prefix prefix :from (ranked/after prefix)}
                       (fn [_ {:keys [v]}]
                         (observe :join-membership-entry (alength ^bytes v))
                         (collect! (codec/decode v)) nil) nil)))
                  (let [result (ranked/filtered-page tx cfg candidates page limit observe virtual)]
                    (-> result (dissoc :payloads)
                        (assoc :keys (mapv codec/decode (:payloads result))
                               :membership-keys-examined @examined
                               :defaulted-keys (.size virtual) :sort-keys-read 0))))))))))))

(defn- validate-selection! [name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix} (keys options))
                 (or (not (contains? options :prefix))
                     (and (vector? (:prefix options)) (not (contains? options :equals)))))
    (throw (ex-info "Result selection accepts :index, :direction and either :equals or a vector :prefix"
                    {:options options})))
  (let [index (:index options :identity) direction (:direction options :asc)]
    (when-not (and (#{:asc :desc} direction)
                   (or (= :identity index) (contains? (:indexes d) index)))
      (throw (ex-info "Unknown result index or direction" {:model name :options options})))
    nil))

(defn- selection-prefix [options]
  (let [encoded (cond
                  (contains? options :equals) (key/encode (:equals options))
                  (contains? options :prefix) (key/tuple-prefix (:prefix options)))]
    (cond-> encoded (and encoded (= :desc (:direction options))) key/descending)))

(defn- selection [b name d options]
  (validate-selection! name d options)
  (let [prefix (selection-prefix options)]
    (cond-> (index-prefix b (:index options :identity) (:direction options :asc))
      prefix (key/join prefix))))

(defn- reduce-results [ctx name d tx b read options rf initial]
  (let [prefix (selection b name d options)]
    ((:reduce tx) {:prefix prefix :from (ranked/after prefix)}
     (fn [acc {:keys [v]}]
       (observe! ctx :index-entry 0)
       (let [id (codec/decode v)
             entry (decode ctx :result-decoded (read 2 id))]
         (rf acc (select-keys entry [:key :value])))) initial)))

(defn values [ctx name d options]
  ;; No resource is opened until reduction. A session-bound reducible must be
  ;; consumed during its session; storage operations enforce that lifetime.
  (validate-selection! name d options)
  (reify clojure.lang.IReduceInit
    (reduce [_ rf initial]
      (with-reader ctx name d
        (fn [tx b d read] (reduce-results ctx name d tx b read options rf initial))))))

(defn- page-prefixes [name d options]
  (let [selection-options (select-keys options [:index :direction :equals :prefix])]
    (validate-selection! name d selection-options)
    (if (contains? options :prefixes)
      (do
        (when-not (and (vector? (:prefixes options))
                       (every? vector? (:prefixes options))
                       (not-any? #(contains? options %) [:equals :prefix]))
          (throw (ex-info "Page prefixes must be vectors and cannot be combined with :equals or :prefix"
                          {:options options})))
        (mapv #(selection-prefix (assoc selection-options :prefix %)) (distinct (:prefixes options))))
      [(selection-prefix selection-options)])))

(defn- page-ranges [tx cfg prefixes total]
  ;; Rank intervals are already in index order, including descending indexes.
  ;; Merging overlapping intervals makes duplicate/nested prefixes a true union.
  (reduce (fn [ranges [start end :as interval]]
            (if-let [[previous-start previous-end] (peek ranges)]
              (if (<= start previous-end)
                (conj (pop ranges) [previous-start (max end previous-end)])
                (conj ranges interval))
              [interval]))
          []
          (sort-by first
                   (keep (fn [prefix]
                           (let [start (if prefix (ranked/rank tx cfg prefix false) 0)
                                 end (if prefix
                                       (if-let [end (key/prefix-end prefix)]
                                         (ranked/rank tx cfg end false)
                                         (ranked/size tx cfg))
                                       total)]
                             (when (< start end) [start end]))) prefixes))))

(defn- read-page-ranges [ctx tx navigation-tx cfg read ranges offset limit]
  (loop [ranges (seq ranges) offset offset entries []]
    (if-let [[start end] (first ranges)]
      (let [size (- end start)]
        (if (>= offset size)
          (recur (next ranges) (- offset size) entries)
          (let [stop (+ (count entries) (min (- size offset) (- limit (count entries))))
                member (ranked/get-nth navigation-tx cfg (+ start offset))
                entries ((:reduce tx) {:prefix (ranked/prefix cfg 0) :from (ranked/pack cfg 0 member)}
                         (fn [entries {:keys [v]}]
                           (observe! ctx :index-entry 0)
                           (let [id (codec/decode v)
                                 entry (decode ctx :result-decoded (read 2 id))
                                 entries (conj entries (select-keys entry [:key :value]))]
                             (if (= stop (count entries)) (reduced entries) entries))) entries)]
            (if (= limit (count entries)) entries
              (recur (next ranges) 0 entries)))))
      entries)))

(defn page [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :prefix :prefixes :page :limit} (keys options))
                 (pos-int? (:page options 1)) (pos-int? (:limit options 25)))
    (throw (ex-info "Invalid keyed-fold page" {:options options})))
  (let [prefixes (page-prefixes name d options)]
    (with-reader ctx name d
      (fn [tx b d read]
        (let [cfg (index-config b (:index options :identity) (:direction options :asc))
              rank-examined (volatile! 0)
              navigation-tx (assoc tx :reduce
                                   (fn [bounds rf initial]
                                     ((:reduce tx) bounds
                                      (fn [acc entry]
                                        (observe! ctx :rank-entry 0)
                                        (vswap! rank-examined inc)
                                        (rf acc entry)) initial)))
              ranges (page-ranges navigation-tx cfg prefixes (or (read-meta tx (count-key b)) 0))
              total (reduce (fn [total [start end]] (+ total (- end start))) 0 ranges)
              limit (:limit options 25)
              pages (max 1 (quot (+ total (dec limit)) limit))
              page (min (:page options 1) pages)
              entries (read-page-ranges ctx tx navigation-tx cfg read ranges (* (dec page) limit) limit)]
          {:entries entries :total total :total-pages pages :page page :page-size limit
           :rows-examined (count entries) :rank-entries-examined @rank-examined
           :results-decoded (count entries)
           :progress (read-meta tx (progress-key b))})))))

(defn- navigation-reader [ctx tx]
  (assoc tx :reduce
         (fn [bounds rf initial]
           ((:reduce tx) bounds
            (fn [acc entry] (observe! ctx :rank-entry 0) (rf acc entry)) initial))))

(defn- group-bounds [tx cfg direction value]
  (let [prefix (sort-bytes value direction)
        first (ranked/rank tx cfg prefix false)
        end (if-let [end (key/prefix-end prefix)]
              (ranked/rank tx cfg end false) (ranked/size tx cfg))]
    [first end]))

(defn index-groups
  ([ctx name d index] (index-groups ctx name d index nil))
  ([ctx name d index values]
   (validate-selection! name d {:index index})
   (with-reader ctx name d
     (fn [tx b _ _read]
       (let [prefix (ranked/prefix (index-config b index :asc) 6)]
         (if (some? values)
           (reduce-distinct (fn [groups value]
                              (observe! ctx :index-group 0)
                              (if-let [group (read-meta tx (key/checked (key/join prefix (key/encode value))))]
                                (conj groups group) groups)) [] values)
           ((:reduce tx) {:prefix prefix}
            (fn [groups {:keys [v]}]
              (observe! ctx :index-group 0)
              (conj groups (codec/decode v))) [])))))))

(defn- validate-group! [name d selection]
  (validate-selection! name d selection)
  (when-not (contains? selection :equals)
    (throw (ex-info "Index composition requires an equality group" {:model name :selection selection}))))

(defn index-keys [ctx name d options]
  (let [selection (select-keys options [:index :direction :equals])]
    (when-not (and (map? options)
                   (every? #{:index :direction :equals :offset :limit} (keys options))
                   (nat-int? (:offset options 0)) (pos-int? (:limit options 25)))
      (throw (ex-info "Invalid index identity slice" {:options options})))
    (validate-group! name d selection)
    (with-reader ctx name d
      (fn [tx b _ _read]
        (let [cfg (index-config b (:index options :identity) (:direction options :asc))
              offset (:offset options 0) limit (:limit options 25)
              prefix (key/join (ranked/prefix cfg 0)
                               (sort-bytes (:equals options) (:direction options :asc)))
              start (if (zero? offset)
                      prefix
                      (let [navigation (navigation-reader ctx tx)
                            [first end] (group-bounds navigation cfg (:direction options :asc) (:equals options))]
                        (when (< offset (- end first))
                          (ranked/pack cfg 0 (ranked/get-nth navigation cfg (+ first offset))))))]
          (if-not start []
              ((:reduce tx) {:prefix prefix :from start}
               (fn [ids {:keys [v]}]
                 (observe! ctx :index-entry 0)
                 (let [ids (conj ids (codec/decode v))]
                   (if (= limit (count ids)) (reduced ids) ids))) [])))))))

(defn index-rank [ctx name d selection id]
  (validate-group! name d selection)
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (with-reader ctx name d
    (fn [tx b _ _read]
      (let [tx (navigation-reader ctx tx) direction (:direction selection :asc)
            cfg (index-config b (:index selection :identity) direction)
            member (index-member direction (:equals selection) id)
            first (ranked/rank tx cfg (sort-bytes (:equals selection) direction) false)]
        {:rank (- (ranked/rank tx cfg member false) first)
         :present? (ranked/contains? tx cfg member)}))))
