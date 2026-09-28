(ns ai.obney.grain.read-model-processor-v2.keyed
  "Independent pure keyed folds. Application callbacks receive detached values."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v2.fold-key :as key]
            [ai.obney.grain.read-model-processor-v2.ranked-set :as ranked]
            [ai.obney.grain.read-model-processor-v2.text-search :as search]
            [ai.obney.grain.read-model-processor-v2.sort-data :as sort-data]
            [ai.obney.grain.read-model-processor-v2.instance-ids :as ids]
            [ai.obney.grain.read-model-processor-v2.span-membership :as membership]
            [malli.core :as m])
  (:import [org.roaringbitmap RoaringBitmap]
           [java.util Arrays ArrayList HashSet]
           [ai.obney.grain.read_model_processor_v2.ranked_set VirtualMember]))

(defn keyed? [definition] (contains? definition :key))

(defn validate! [name opts]
  (when (and (contains? opts :ranked-indexes) (not (keyed? opts)))
    (throw (ex-info "Rank membership requires a keyed fold" {:model name})))
  (when (and (contains? opts :searches) (not (keyed? opts)))
    (throw (ex-info "Text search requires a keyed fold" {:model name})))
  (when (keyed? opts)
    (when-not (and (qualified-keyword? name) (pos-int? (:version opts 1))
                   (set? (:events opts)) (seq (:events opts))
                   (every? qualified-keyword? (:events opts))
                   (ifn? (:key opts)) (ifn? (:result opts identity))
                   (ifn? (:include? opts (constantly true)))
                   (map? (:indexes opts {}))
                   (map? (:searches opts {}))
                   (set? (:ranked-indexes opts #{}))
                   (every? #(or (= :identity %) (contains? (:indexes opts) %)) (:ranked-indexes opts))
                   (every? (fn [[k f]] (and (keyword? k) (ifn? f))) (:searches opts))
                   (every? (fn [[k f]] (and (keyword? k) (not= :identity k) (ifn? f)))
                           (:indexes opts))
                   (not-any? #(contains? opts %)
                             [:partition-fn :entity-id-fn :l1-ttl-ms :l1-max-entries
                              :record-scope :entity-key :entity-source :depends-on :derived?
                              :tenant-key :tenant-id :scope :query :queries]))
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
(defn- index-config [b index direction]
  (ranked/config (key/join b (byte-array [5]) (key/encode index)
                           (byte-array [(if (= direction :desc) 1 0)]))))
(defn- index-prefix [b index direction]
  (ranked/prefix (index-config b index direction) 0))
(defn- membership-config [b d index direction]
  (assoc (index-config b index direction) :membership? (contains? (:ranked-indexes d) index)))
(defn- sort-bytes [value direction]
  (cond-> (key/encode value) (= direction :desc) key/descending))
(defn- index-member [direction value id]
  (key/join (sort-bytes value direction) (key/encode (str id)) (key/encode id)))

(defn- update-group! [tx b index value delta]
  ;; Levels 0..5 are the RankedSet. Level 6 stores one count per sort value,
  ;; shared by both directions; no duplicated Student membership collection.
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
      (let [tx (if (seq (:ranked-indexes d)) (membership/transaction tx) tx)
            before-progress (read-meta tx (progress-key b))
            events (filterv #(or (nil? (:watermark before-progress))
                                (pos? (compare (:event/id %) (:watermark before-progress)))) events)
            states (reduce
                    (fn [states event]
                      (let [id ((:key d) event)]
                        (when (nil? id)
                          (throw (ex-info "Keyed-fold event has no owner"
                                          {:model name :event-id (:event/id event)
                                           :event-type (:event/type event)})))
                        (let [k (address b 1 id)
                              state (if (contains? states id) (get states id)
                                      (if-let [raw ((:get tx) k)]
                                        (decode ctx :state-decoded raw) (:initial d {})))
                              next ((:reducer-fn d) state event)]
                          (validate-state! next)
                          (observe! ctx :event-applied 0)
                          (assoc states id next)))) {} events)
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
  (reduce (fn [_ event] (reduced (:event/id event))) nil
          (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :reverse? true :limit 1})))

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

(defn- identity-of [ctx] [(:event-source ctx) (:tenant-id ctx)])

(defn session-definition [ctx name]
  (when-let [s (::session ctx)]
    (when-not (and (= (:identity s) (identity-of ctx))
                   (identical? (:cache s) (:cache ctx)))
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
   All requests must share a cache. Callers explicitly supply any trusted identity
   context; definitions never select tenants. No transaction escapes to callbacks."
  [requests registry f]
  (when-not (seq requests) (throw (ex-info "Read session requires at least one request" {})))
  (let [cache (:cache (ffirst requests))]
    (when-not cache (throw (ex-info "Read session requires :cache" {})))
    (let [prefixes
          (reduce (fn [prefixes [ctx names]]
                    (when (::session ctx)
                      (throw (ex-info "Cannot open a nested keyed-fold read session" {})))
                    (when-not (identical? cache (:cache ctx))
                      (throw (ex-info "Read-session contexts must share one store" {})))
                    (reduce (fn [prefixes name]
                              (let [id [(identity-of ctx) name]
                                    d (get registry name)]
                                (when-not (keyed? d)
                                  (throw (ex-info "Expected a registered keyed fold" {:model name})))
                                (if (contains? prefixes id) prefixes
                                  (assoc prefixes id (base ctx name d)))))
                            prefixes names)) {} requests)
          prepared
          (locking cache
            (let [heads (reduce (fn [heads [ctx _]]
                                 (let [id (identity-of ctx)]
                                   (if (contains? heads id) heads (assoc heads id (head ctx))))) {} requests)]
              (mapv (fn [[ctx names]]
                      (doseq [name (distinct names)]
                        (catch-up! ctx name (get registry name)
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
                            {:cache cache :identity (identity-of ctx)
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

(defn progress [ctx name d]
  (with-reader ctx name d (fn [tx b _d _read] (read-meta tx (progress-key b)))))

(defn search-keys [ctx name d options]
  (with-reader ctx name d
    (fn [tx b _ _read]
      (search/search-keys tx b (:searches d) options #(observe! ctx %1 %2)))))

(defn key-page [ctx name d keys options]
  (let [{:keys [index direction page limit sort-value]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :direction :page :limit :sort-value} (clojure.core/keys options))
                   (or (= index :identity) (contains? (:indexes d) index))
                   (#{:asc :desc} direction) (pos-int? page) (pos-int? limit)
                   (or (nil? sort-value) (ifn? sort-value)))
      (throw (ex-info "Invalid matching-key page" {:model name :options options})))
    (with-reader ctx name d
      (fn [_tx _b _d read]
        (let [field (sort-data/field (:indexes d) index)
              seen (java.util.HashSet.) entries (java.util.ArrayList.)]
          (doseq [id keys :when (.add seen id)]
            (when-let [raw (read 11 id)]
              (observe! ctx :sort-key-read (alength ^bytes raw))
              (.add entries (ai.obney.grain.read_model_processor_v2.sort_data.Entry.
                              id raw (when sort-value (key/encode (sort-value id (sort-data/value raw field))))))))
          (.sort entries (sort-data/comparator field (= direction :desc) (some? sort-value)))
          (let [total (.size entries) pages (max 1 (quot (+ total (dec limit)) limit))
                page (min page pages) offset (* (dec page) limit)]
            {:keys (mapv #(.-id ^ai.obney.grain.read_model_processor_v2.sort_data.Entry (.get entries %))
                         (range offset (min total (+ offset limit))))
             :total total :total-pages pages :page page :page-size limit
             :sort-keys-read total}))))))

(defn ranked-key-page [ctx name d keys options]
  (let [{:keys [index direction page limit]
         :or {index :identity direction :asc page 1 limit 25}} options]
    (when-not (and (map? options) (every? #{:index :direction :page :limit} (clojure.core/keys options))
                   (contains? (:ranked-indexes d) index)
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
                   (contains? options :missing-value) (contains? (:ranked-indexes d) index)
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
                 (every? #{:index :direction :equals} (keys options)))
    (throw (ex-info "Result selection accepts :index, :direction and :equals" {:options options})))
  (let [index (:index options :identity) direction (:direction options :asc)]
    (when-not (and (#{:asc :desc} direction)
                   (or (= :identity index) (contains? (:indexes d) index)))
      (throw (ex-info "Unknown result index or direction" {:model name :options options})))
    nil))

(defn- selection [b name d options]
  (validate-selection! name d options)
  (let [direction (:direction options :asc)]
    (cond-> (index-prefix b (:index options :identity) direction)
      (contains? options :equals) (key/join (sort-bytes (:equals options) direction)))))

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

(defn page [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :page :limit} (keys options))
                 (pos-int? (:page options 1)) (pos-int? (:limit options 25)))
    (throw (ex-info "Invalid keyed-fold page" {:options options})))
  (with-reader ctx name d
    (fn [tx b d read]
      (let [selection-options (select-keys options [:index :direction :equals])
            prefix (selection b name d selection-options)
            cfg (index-config b (:index options :identity) (:direction options :asc))
            rank-examined (volatile! 0)
            navigation-tx (assoc tx :reduce
                                 (fn [bounds rf initial]
                                   ((:reduce tx) bounds
                                    (fn [acc entry]
                                      (observe! ctx :rank-entry 0)
                                      (vswap! rank-examined inc)
                                      (rf acc entry)) initial)))
            equals? (contains? options :equals)
            member-prefix (when equals? (sort-bytes (:equals options) (:direction options :asc)))
            first-rank (if equals? (ranked/rank navigation-tx cfg member-prefix false) 0)
            total (if equals?
                    (- (if-let [end (key/prefix-end member-prefix)]
                         (ranked/rank navigation-tx cfg end false)
                         (ranked/size navigation-tx cfg)) first-rank)
                    (or (read-meta tx (count-key b)) 0))
            limit (:limit options 25)
            pages (max 1 (quot (+ total (dec limit)) limit))
            page (min (:page options 1) pages)
            offset (* (dec page) limit)
            start (when (pos? total) (ranked/get-nth navigation-tx cfg (+ first-rank offset)))
            entries (if-not start []
                      ((:reduce tx) {:prefix prefix :from (ranked/pack cfg 0 start)}
                       (fn [entries {:keys [v]}]
                         (observe! ctx :index-entry 0)
                         (let [id (codec/decode v)
                               entry (decode ctx :result-decoded (read 2 id))
                               entries (conj entries (select-keys entry [:key :value]))]
                           (if (= limit (count entries)) (reduced entries) entries))) []))]
        {:entries entries :total total :total-pages pages :page page :page-size limit
         :rows-examined (count entries) :rank-entries-examined @rank-examined
         :results-decoded (count entries)
         :progress (read-meta tx (progress-key b))}))))

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

(defn index-groups [ctx name d index]
  (validate-selection! name d {:index index})
  (with-reader ctx name d
    (fn [tx b _ _read]
      ((:reduce tx) {:prefix (ranked/prefix (index-config b index :asc) 6)}
       (fn [groups {:keys [v]}]
         (observe! ctx :index-group 0)
         (conj groups (codec/decode v))) []))))

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
        (let [tx (navigation-reader ctx tx)
              cfg (index-config b (:index options :identity) (:direction options :asc))
              [first end] (group-bounds tx cfg (:direction options :asc) (:equals options))
              offset (:offset options 0) limit (:limit options 25)]
          (if (>= offset (- end first)) []
            (let [start (ranked/get-nth tx cfg (+ first offset))]
              ((:reduce tx) {:prefix (key/join (ranked/prefix cfg 0)
                                             (sort-bytes (:equals options) (:direction options :asc)))
                             :from (ranked/pack cfg 0 start)}
               (fn [ids {:keys [v]}]
                 (observe! ctx :index-entry 0)
                 (let [ids (conj ids (codec/decode v))]
                   (if (= limit (count ids)) (reduced ids) ids))) []))))))))

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
