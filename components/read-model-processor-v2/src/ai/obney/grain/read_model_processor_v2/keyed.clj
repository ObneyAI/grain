(ns ai.obney.grain.read-model-processor-v2.keyed
  "Independent pure keyed folds. Application callbacks receive detached values."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v2.fold-key :as key]
            [malli.core :as m]))

(defn keyed? [definition] (contains? definition :key))

(defn validate! [name opts]
  (when (keyed? opts)
    (when-not (and (qualified-keyword? name) (pos-int? (:version opts 1))
                   (set? (:events opts)) (seq (:events opts))
                   (every? qualified-keyword? (:events opts))
                   (ifn? (:key opts)) (ifn? (:result opts identity))
                   (ifn? (:include? opts (constantly true)))
                   (map? (:indexes opts {}))
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
(defn- index-prefix [b index direction]
  (key/join b (byte-array [3]) (key/encode [index direction])))
(defn- sort-bytes [value direction]
  (cond-> (key/encode value) (= direction :desc) key/descending))
(defn- index-address [b index direction value id]
  (key/checked (key/join (index-prefix b index direction) (sort-bytes value direction)
                         (key/encode (str id)) (key/encode id))))

(defn- read-meta [tx k] (some-> ((:get tx) k) codec/decode))
(defn- write! [tx k v] ((:put tx) k (codec/encode v)))
(defn- write-observed! [ctx tx operation k value]
  (let [bytes (codec/encode value)]
    ((:put tx) k bytes)
    (observe! ctx operation (alength ^bytes bytes))))
(defn- assert-schema! [schema value name kind]
  (when (and schema (not (m/validate schema value)))
    (throw (ex-info "Keyed-fold schema validation failed"
                    {:model name :kind kind :explanation (m/explain schema value)}))))

(defn- apply-batch! [ctx name d events]
  (let [b (base ctx name d)]
    (kv/transact (:cache ctx) :write
      (fn [tx]
        (let [before-progress (read-meta tx (progress-key b))
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
                            (assert-schema! (or (:state-schema d) (:schema d)) next name :state)
                            (observe! ctx :event-applied 0)
                            (assoc states id next)))) {} events)
              count-before (or (read-meta tx (count-key b)) 0)
              added (volatile! 0)]
          (doseq [[id state] states]
            (let [value ((:result d identity) state)
                  _ (assert-schema! (:result-schema d) value name :result)
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
                  (doseq [[index position] (:indexes old) direction [:asc :desc]]
                    ((:delete tx) (index-address b index direction position id)))
                  ((:delete tx) result-k))
                (when (or (nil? old) (not= (:value old) value))
                  (doseq [[index position] indexes
                          direction [:asc :desc]
                          :when (or (nil? old) (not= position (get-in old [:indexes index])))]
                    (when old
                      ((:delete tx) (index-address b index direction (get-in old [:indexes index]) id)))
                    (write-observed! ctx tx :index-written (index-address b index direction position id) id))
                  (write-observed! ctx tx :result-written result-k {:key id :value value :indexes indexes})))))
          (when (seq events)
            (when-not (zero? @added) (write! tx (count-key b) (+ count-before @added)))
            (write! tx (progress-key b) (assoc before-progress :watermark (:event/id (peek events))))))))))

(defn- head [ctx]
  (reduce (fn [_ event] (reduced (:event/id event))) nil
          (es/read (:event-store ctx) {:tenant-id (:tenant-id ctx) :reverse? true :limit 1})))

(defn- catch-up! [ctx name d through]
  (let [b (base ctx name d)
        progress (kv/transact (:cache ctx) :read #(read-meta % (progress-key b)))
        after (last (sort (keep #(get progress %) [:checked-through :watermark])))
        batch-size (:read-model/batch-size ctx 128)]
    (when-not (pos-int? batch-size)
      (throw (ex-info "Keyed-fold batch size must be positive" {:batch-size batch-size})))
    (when (and through (or (nil? after) (pos? (compare through after))))
      (let [remaining (reduce
                       (fn [batch event]
                         (if (pos? (compare (:event/id event) through)) (reduced batch)
                           (let [batch (conj batch event)]
                             (if (= batch-size (count batch))
                               (do (apply-batch! ctx name d batch) []) batch))))
                       [] (es/read (:event-store ctx)
                                   (cond-> {:tenant-id (:tenant-id ctx) :types (:events d)}
                                     after (assoc :after after))))]
        (when (seq remaining) (apply-batch! ctx name d remaining)))
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

(defn read-session
  "Prepare explicit [context model-names] requests, then pass their contexts to f.
   All requests must share a cache. Callers explicitly supply any trusted identity
   context; definitions never select tenants. No transaction escapes to callbacks."
  [requests registry f]
  (when-not (seq requests) (throw (ex-info "Read session requires at least one request" {})))
  (let [cache (:cache (ffirst requests))]
    (when-not cache (throw (ex-info "Read session requires :cache" {})))
    (doseq [[ctx names] requests]
      (when (::session ctx) (throw (ex-info "Cannot open a nested keyed-fold read session" {})))
      (when-not (identical? cache (:cache ctx))
        (throw (ex-info "Read-session contexts must share one store" {})))
      (doseq [name names]
        (let [d (get registry name)]
          (when-not (keyed? d) (throw (ex-info "Expected a registered keyed fold" {:model name})))
          (base ctx name d))))
    (let [prepared
          (locking cache
            (let [heads (reduce (fn [heads [ctx _]]
                            (let [id (identity-of ctx)]
                              (if (contains? heads id) heads (assoc heads id (head ctx))))) {} requests)
                  prepared (mapv (fn [[ctx names]]
                             (doseq [name (distinct names)]
                               (catch-up! ctx name (get registry name) (get heads (identity-of ctx))))
                             [ctx (select-keys registry names)]) requests)]
              prepared))]
      ;; Catch-up is serialized; detached result composition can run concurrently.
      ;; A writer may advance before this snapshot opens, which the finite-head
      ;; contract permits. The snapshot still includes all captured-head effects.
      (kv/transact cache :read
          (fn [tx]
            (f (mapv (fn [[ctx definitions]]
                       (assoc ctx ::session {:tx tx :cache cache :identity (identity-of ctx)
                                             :definitions definitions})) prepared)))))))

(defn- with-reader [ctx name d f]
  (if-let [s (::session ctx)]
    (let [d (session-definition ctx name)] (f (:tx s) d))
    (read-session [[ctx [name]]] {name d}
                  (fn [[prepared]] (f (get-in prepared [::session :tx]) d)))))

(defn point [ctx name d id kind not-found]
  (when (nil? id) (throw (ex-info "Instance key cannot be nil" {:model name})))
  (with-reader ctx name d
    (fn [tx d]
      (if-let [raw ((:get tx) (address (base ctx name d) (if (= kind :state) 1 2) id))]
        (let [decoded (decode ctx (if (= kind :state) :state-decoded :result-decoded) raw)]
          (if (= kind :state) decoded (:value decoded)))
        not-found))))

(defn progress [ctx name d]
  (with-reader ctx name d (fn [tx d] (read-meta tx (progress-key (base ctx name d))))))

(defn- selection [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals} (keys options)))
    (throw (ex-info "Result selection accepts :index, :direction and :equals" {:options options})))
  (let [index (:index options :identity) direction (:direction options :asc)]
    (when-not (and (#{:asc :desc} direction)
                   (or (= :identity index) (contains? (:indexes d) index)))
      (throw (ex-info "Unknown result index or direction" {:model name :options options})))
    (cond-> (index-prefix (base ctx name d) index direction)
      (contains? options :equals) (key/join (sort-bytes (:equals options) direction)))))

(defn- reduce-results [ctx name d tx options rf initial]
  (let [b (base ctx name d) prefix (selection ctx name d options)]
    ((:reduce tx) {:prefix prefix}
     (fn [acc {:keys [v]}]
       (observe! ctx :index-entry 0)
       (let [id (codec/decode v)
             entry (decode ctx :result-decoded ((:get tx) (address b 2 id)))]
         (rf acc (select-keys entry [:key :value])))) initial)))

(defn values [ctx name d options]
  ;; No resource is opened until reduction. A session-bound reducible must be
  ;; consumed during its session; storage operations enforce that lifetime.
  (selection ctx name d options)
  (reify clojure.lang.IReduceInit
    (reduce [_ rf initial]
      (with-reader ctx name d #(reduce-results ctx name %2 %1 options rf initial)))))

(defn page [ctx name d options]
  (when-not (and (map? options)
                 (every? #{:index :direction :equals :page :limit} (keys options))
                 (pos-int? (:page options 1)) (pos-int? (:limit options 25)))
    (throw (ex-info "Invalid keyed-fold page" {:options options})))
  (with-reader ctx name d
    (fn [tx d]
      (let [selection-options (select-keys options [:index :direction :equals])
            prefix (selection ctx name d selection-options)
            b (base ctx name d)
            examined (volatile! 0)
            total (if (contains? options :equals)
                    ((:reduce tx) {:prefix prefix}
                     (fn [n _] (observe! ctx :index-entry 0) (vswap! examined inc) (inc n)) 0)
                    (or (read-meta tx (count-key b)) 0))
            limit (:limit options 25)
            pages (max 1 (quot (+ total (dec limit)) limit))
            page (min (:page options 1) pages)
            offset (* (dec page) limit)
            entries (if (zero? total) []
                      (:entries
                       ((:reduce tx) {:prefix prefix}
                        (fn [{:keys [seen entries]} {:keys [v]}]
                          (observe! ctx :index-entry 0)
                          (vswap! examined inc)
                          (if (< seen offset) {:seen (inc seen) :entries entries}
                            (let [id (codec/decode v)
                                  entry (decode ctx :result-decoded ((:get tx) (address b 2 id)))
                                  entries (conj entries (select-keys entry [:key :value]))
                                  next {:seen (inc seen) :entries entries}]
                              (if (= limit (count entries)) (reduced next) next))))
                        {:seen 0 :entries []})))]
        {:entries entries :total total :total-pages pages :page page :page-size limit
         :rows-examined @examined :results-decoded (count entries)
         :progress (read-meta tx (progress-key b))}))))
