(ns ai.obney.grain.read-model-processor-v3.core
  "One durable Datahike materialization for every projection. Reducer map edits
   use db-with; only the selected result's transaction data is committed."
  (:require [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [datahike.store :as datahike-store]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [ai.obney.grain.read-model-processor-v3.cursor :as cursor]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.anomalies.interface :refer [anomaly?]]
            [ai.obney.grain.fressian-util.interface :as codec]
            [ai.obney.grain.read-model-processor-v3.lifetime :as life]
            [ai.obney.grain.read-model-processor-v3.map-view :as mv])
  (:import [java.util Arrays Base64 HexFormat UUID]
           [java.security MessageDigest]
           [ai.obney.grain.read_model_processor_v3.map_view EngineView]))

(defn fail!
  [model error message data]
  (throw (ex-info message (merge {:error error :read-model model} data))))

(defn- valid-text?
  [x]
  (or (not (string? x)) (= x (String. (.getBytes ^String x "UTF-8") "UTF-8"))))

(defn- canonical
  [x]
  (when-not (valid-text? x)
    (fail! nil :invalid-record-id "Keys must contain valid Unicode" {}))
  (cond
    (map? x)
    [:map (sort-by pr-str (map (fn [[k v]] [(canonical k) (canonical v)]) x))]
    (set? x)
    [:set (sort-by pr-str (map canonical x))]
    (sequential? x)
    [:seq (mapv canonical x)]
    (integer? x)
    [:integer (str x)]
    :else
    [:value x]))

(defn- b64
  [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- pack
  [x]
  (codec/encode x))

(defn- unpack
  [x]
  (codec/decode x))

(defn- key-bytes
  [x]
  (codec/encode (canonical x)))

(defn- digest
  [x]
  (.digest (MessageDigest/getInstance "SHA-256") (key-bytes x)))

(defn- attr
  [ident type & [extra]]
  (merge {:db/ident ident
          :db/valueType type
          :db/cardinality :db.cardinality/one} extra))

(def ^:private schema
  [(attr :projection/key :db.type/bytes {:db/unique :db.unique/identity})
   (attr :projection/generation :db.type/uuid)
   (attr :projection/watermark :db.type/uuid)
   (attr :projection/map? :db.type/boolean)
   (attr :projection/value :db.type/bytes)
   (attr :projection/count :db.type/long)
   (attr :projection/metadata :db.type/bytes)
   (attr :definition/key :db.type/bytes {:db/unique :db.unique/identity})
   (attr :definition/value :db.type/bytes)
   (attr :record/key :db.type/bytes {:db/unique :db.unique/identity})
   (attr :record/scope :db.type/bytes)
   (attr :record/id :db.type/bytes)
   (attr :record/original-id :db.type/bytes)
   (attr :record/value :db.type/bytes)
   (attr :record/partition :db.type/bytes)
   (attr :record/order :db.type/tuple {:db/tupleAttrs [:record/scope :record/id] :db/index true})
   (attr :record/partition-order :db.type/tuple
         {:db/tupleAttrs [:record/scope :record/partition :record/id]
          :db/index true})])

(defrecord ProjectionStore [runtime path]
  java.io.Closeable
  (close [_]
    (life/close! runtime)))

(defn open-store
  "Open the application's single-owner projection store. LMDB is the default;
   :file is supported for portable deployments. Old returned maps keep their
   storage alive after close until they become unreachable."
  [{:keys [storage-dir backend gc-interval-ms pin-ttl-ms map-size]
    :or {backend :lmdb
         gc-interval-ms 300000
         pin-ttl-ms 3600000
         map-size 17179869184}
    :as options}]
  (when-not (and (string? storage-dir) (not (str/blank? storage-dir))
                 (#{:lmdb :file} backend)
                 (every? #{:storage-dir :backend :gc-interval-ms :pin-ttl-ms :map-size} (keys options))
                 (every? #(and (integer? %) (pos? %) (<= % Long/MAX_VALUE))
                         [gc-interval-ms pin-ttl-ms map-size]))
    (fail! nil :invalid-projection-store "Supply :storage-dir and valid projection-store options" {}))
  (when (= backend :lmdb)
    (require 'datahike-lmdb.core))
  (let [path (.getCanonicalPath (io/file storage-dir))
        owner (try
                (life/acquire-owner! path)
                (catch Exception e
                  (throw (ex-info "Projection store already owned or inaccessible"
                                  {:error :store-unavailable :path path} e))))
        opened (atom nil)]
    (try
      (let [cfg {:store (cond-> {:backend backend
                                 :path (str (io/file path "db"))
                                 :id (UUID/nameUUIDFromBytes (.getBytes path "UTF-8"))}
                          (= backend :lmdb) (assoc :map-size map-size))
                 :schema-flexibility :write
                 :keep-history? false
                 ;; The pinned Datahike version lacks the bytes type's numeric
                 ;; attribute reference. Keyword attributes support raw bytes.
                 :attribute-refs? false
                 :writer {:backend :self :writer-ownership :exclusive}
                 :max-string-length 0}
            fresh? (not (d/database-exists? cfg))
            _ (when fresh?
                (d/create-database cfg))
            conn (try
                   (d/connect cfg)
                   (catch clojure.lang.ExceptionInfo e
                     (if (and (= :config-does-not-match-stored-db (:type (ex-data e)))
                              (get-in (ex-data e) [:stored-config :attribute-refs?]))
                       (fail! nil :incompatible-projection-store
                              "Projection storage uses an older format; rebuild in a fresh storage directory"
                              {:path path})
                       (throw e))))
            _ (reset! opened conn)
            ;; Creation and schema initialization are separate engine calls. A
            ;; process may exit between them; finish initialization on reopen.
            _ (when-not (seq (d/datoms @conn :avet :db/ident :projection/key))
                (d/transact conn schema))
            _ (when (or (get-in @conn [:config :attribute-refs?])
                        (not= :db.type/bytes (get-in @conn [:schema :record/value :db/valueType]))
                        (not= :db.type/bytes (get-in @conn [:schema :projection/key :db/valueType])))
                (fail! nil :incompatible-projection-store
                       "Projection storage uses an older format; rebuild in a fresh storage directory"
                       {:path path}))
            rt (life/new-runtime conn @conn owner (fn [] nil))]
        (reset! (:pin-ttl-ms rt) pin-ttl-ms)
        (reset! (:gc-interval-ms rt) gc-interval-ms)
        (reset! (:state rt) (life/pin-state! {:db @conn :runtime rt}))
        (life/start-maintenance! rt 1000)
        (->ProjectionStore rt path))
      (catch Throwable e
        (when-let [c @opened]
          (d/release c))
        (life/release-owner! owner)
        (throw e)))))

(defn- runtime!
  [context]
  (let [store (:projection-store context)]
    (when-not store
      (fail! nil :missing-projection-store "Supply :projection-store from open-store" {}))
    (when-not (instance? ProjectionStore store)
      (fail! nil :invalid-projection-store "Expected a projection store" {}))
    (let [rt (:runtime store)]
      (when @(:closing? rt)
        (fail! nil :invalid-projection-store "Projection store is closed" {}))
      (when-let [e (or @(:failure rt) @(:lease-error rt))]
        (throw e))
      rt)))

(defn collect!
  [store]
  (life/maintain-snapshots! (:runtime store)))

(defn store-status
  [store]
  (let [rt (:runtime store)]
    (merge @(:stats rt) {:closed? @(:closing? rt)
                         :released? @(:released? rt)
                         :retained-commits (count @(:pins rt))
                         :last-error @(:last-error rt)
                         :failure @(:failure rt)
                         :lease-error @(:lease-error rt)})))

(defn- commit!
  [rt tx]
  ;; A post-commit pin error is ambiguous to the caller. Poison this handle;
  ;; reopening recovers the durable projection and watermark without replaying it.
  (let [db (:context rt)
        store-id (datahike-store/canonical-store-id (:store db) (get-in db [:config :store]))]
    ;; Other projections may commit before this transaction's pin is installed.
    ;; Extend Datahike's GC protection through pin publication; this is not a lock.
    (gc-guard/with-unreferenced-writes store-id
      (let [base @(:state rt)]
        (try
          (when-let [e (or @(:failure rt) @(:lease-error rt))]
            (throw e))
          (let [report (d/transact (:conn rt) tx)
                s (life/pin-state! {:db (:db-after report) :runtime rt})]
            ;; Pin completions may arrive out of order. Never regress the head.
            (swap! (:state rt)
                   (fn [current]
                     (if (> (:max-tx (:db s)) (:max-tx (:db current))) s current)))
            s)
          (catch Throwable e
            (reset! (:failure rt) e)
            (throw e))
          (finally
            ;; The prior pinned head protects reused nodes until the new pin exists.
            (java.lang.ref.Reference/reachabilityFence base)))))))

(defn- layout
  [model definition]
  (let [ns (str "grain.index." (.formatHex (HexFormat/of) ^bytes (digest [model (:version definition 1)])))
        fields (->> (get-in definition [:indexed :indexes])
                    vals
                    (apply concat)
                    (map (juxt :path identity))
                    (into {})
                    vals
                    (sort-by (comp pr-str :path))
                    (map-indexed #(assoc %2 :attr (keyword ns (str "f" %1))))
                    vec)
        by-path (into {} (map (juxt :path identity) fields))]
    {:fields fields
     :scope-attr (keyword ns "scope")
     :id-attr (keyword ns "id")
     :indexes (into {} (map-indexed
                        (fn [i [index fs]]
                          [index {:attr (keyword ns (str "i" i))
                                  :fields (mapv #(by-path (:path %)) fs)}])
                        (sort-by (comp str key) (get-in definition [:indexed :indexes]))))}))

(defn- layout-schema
  [{:keys [fields scope-attr id-attr indexes]}]
  (when (seq indexes)
    (concat [(attr scope-attr :db.type/bytes) (attr id-attr :db.type/string)]
            (map #(attr (:attr %) ({:string :db.type/string
                                    :keyword :db.type/keyword
                                    :int :db.type/long
                                    :uuid :db.type/uuid} (:type %))) fields)
            (for [[_ {:keys [attr fields]}] indexes]
              {:db/ident attr
               :db/valueType :db.type/tuple
               :db/cardinality :db.cardinality/one
               :db/index true
               :db/tupleAttrs (into [scope-attr] (concat (map :attr fields) [id-attr]))}))))

(defn- pull-by
  [db pattern [a v]]
  (when-let [dt (first (d/datoms db :avet a v))]
    (d/pull db pattern (:e dt))))

(defn- meta-row
  [db identity]
  (pull-by db '[*] [:projection/key identity]))

(defn- current-pull
  [rt pattern lookup]
  (let [s @(:state rt)]
    (life/with-snapshot-state s #(pull-by (:db s) pattern lookup))))

(defn- scoped-state
  [rt model definition scope]
  (let [identity (digest [(:tenant-id scope) model (:version definition 1) (:scope scope)])
        s @(:state rt)
        row (meta-row (:db s) identity)]
    (merge s {:model model
              :definition definition
              :layout (layout model definition)
              :identity identity
              :generation (:projection/generation row)
              :watermark (:projection/watermark row)
              :count (:projection/count row 0)
              :map? (:projection/map? row true)
              :value (some-> (:projection/value row)
                             unpack)
              :annotations (some-> (:projection/metadata row)
                                   unpack)})))

(defn- ensure-projection!
  [rt model definition scope]
  (let [dk (digest [model (:version definition 1)])
        expected (:descriptor definition)
        identity (digest [(:tenant-id scope) model (:version definition 1) (:scope scope)])
        ensure-definition (fn []
                            (let [previous (some-> (current-pull rt '[:definition/value]
                                                                 [:definition/key dk])
                                                   :definition/value unpack)]
                              (when (and previous (not= previous expected))
                                (fail! model :definition-version-conflict "Definition changed; bump :version and rebuild" {}))
                              (when-not previous
                                (commit! rt (vec (concat (layout-schema (layout model definition))
                                                         [{:definition/key dk :definition/value (pack expected)}]))))))]
    ;; Schema is shared by a model/version, but normal catch-up is not.
    (if (current-pull rt '[:definition/value] [:definition/key dk])
      (ensure-definition)
      (life/with-key-lock rt [:definition (vec dk)] ensure-definition))
    (when-not (current-pull rt '[*] [:projection/key identity])
      (commit! rt [{:projection/key identity
                    :projection/generation (random-uuid)
                    :projection/map? true
                    :projection/count 0}]))))

(defn- observe!
  [context operation data]
  (when-let [f (:projection-observer context)]
    (f (assoc data :operation operation))))

(defn- settings
  [context]
  (let [opts (:projection-options context {})]
    (when-not (and (map? opts)
                   (every? #{:batch-events :batch-bytes :page-bytes :page-ms :reduce-bytes :reduce-ms :catch-up-ms} (keys opts))
                   (every? #(and (integer? %) (pos? %) (<= % Long/MAX_VALUE)) (vals opts)))
      (fail! nil :invalid-resource-option "Projection budgets must be supported positive integers" {}))
    (merge {:batch-events 128} opts)))

(defn- elapsed?
  [start ms]
  (and ms (>= (- (System/nanoTime) start) (*' ms 1000000))))

(defn- check-time!
  [model start ms phase]
  (when (elapsed? start ms)
    (fail! model :resource-budget-exceeded "Time budget exhausted" {:phase phase :retryable (= phase :catch-up)})))

(defn- key-id
  [s id]
  (pack [(:identity s) (canonical id)]))

(defn- raw-record
  [s id]
  (pull-by (:db s) '[:record/value :record/original-id :record/partition] [:record/key (key-id s id)]))

(defn- visible?
  [s row]
  (and row (or (not (contains? s :partition))
               (Arrays/equals ^bytes (key-bytes (:partition s)) ^bytes (:record/partition row)))))

(defn- get-value
  [s id fallback]
  (life/with-snapshot-state s
    #(let [row (raw-record s id)]
       (if (visible? s row)
         (unpack (:record/value row))
         fallback))))

(defn- valid-scalar?
  [field v]
  (and ((:valid? field) v) (valid-text? v)
       (or (not= :int (:type field)) (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE)))))

(def ^:private max-position-bytes 65536)

(defn- position
  [s fs id value]
  (let [vs (mapv #(get-in value (:path %)) fs)
        values (conj vs id)]
    (doseq [[f v] (map vector fs vs)]
      (when-not (valid-scalar? f v)
        (fail! (:model s) :invalid-index-value "Index value violates scalar schema" {:path (:path f)})))
    (when (> (alength (cursor/encode values)) max-position-bytes)
      (fail! (:model s) :index-key-too-large "Sort position exceeds cursor byte limit" {:max-key-bytes max-position-bytes}))
    values))

(defn- record-tx
  [s id value]
  (let [definition (:definition s)
        compiled (:indexed definition)
        layout (:layout s)]
    (when (and (:id-valid? compiled) (not ((:id-valid? compiled) id)))
      (fail! (:model s) :invalid-record-id "Record ID does not satisfy schema" {:record-id id}))
    (when (and (:record-valid? compiled) (not ((:record-valid? compiled) value)))
      (fail! (:model s) :invalid-record-output "Record does not satisfy schema" {:record-id id}))
    (doseq [[_ {:keys [fields]}] (:indexes layout)]
      (position s fields id value))
    (merge {:record/key (key-id s id)
            :record/scope (:identity s)
            :record/id (key-bytes id)
            :record/original-id (pack id)
            :record/value (pack value)}
           (when-let [partition-fn (:partition-fn definition)]
             {:record/partition (key-bytes (partition-fn value))})
           (when (seq (:indexes layout))
             (into {(:scope-attr layout) (:identity s) (:id-attr layout) (str id)}
                   (map (fn [{:keys [attr path type]}]
                          [attr (let [v (get-in value path)]
                                  (if (= type :int)
                                    (long v)
                                    v))]) (:fields layout)))))))

(defn- edit
  [s f]
  (life/with-snapshot-state s
    #(do (life/ensure-reducing! s)
         (try
           (f)
           (catch Throwable e
             (reset! (:fault (:scope s)) e)
             (throw e))))))

(defn- assoc-value
  [s id value]
  (edit s #(let [tx (record-tx s id value)
                 exists? (raw-record s id)]
             (-> s
                 (update :db d/db-with [tx])
                 (update :tx conj tx)
                 (update :count (if exists?
                                  identity
                                  inc))))))

(defn- dissoc-value
  [s id]
  (edit s #(if (raw-record s id)
             (let [tx [:db/retractEntity [:record/key (key-id s id)]]]
               (-> s
                   (update :db d/db-with [tx])
                   (update :tx conj tx)
                   (update :count dec))) s)))

(defn- tuple-equal?
  [a b]
  (and (= (count a) (count b))
       (every? true?
               (map (fn [x y]
                      (if (and (bytes? x) (bytes? y))
                        (Arrays/equals ^bytes x ^bytes y)
                        (= x y)))
                    a b))))

(defn- range-datoms
  [s attr prefix after]
  (life/with-snapshot-state s (fn [] nil))
  (let [width (count (:db/tupleAttrs (d/pull (:db s) '[*] [:db/ident attr])))
        lower (or after (into prefix (repeat (- width (count prefix)) nil)))]
    (->> (d/seek-datoms (:db s) :avet attr lower)
         (take-while #(and (= attr (:a %)) (tuple-equal? prefix (subvec (:v %) 0 (count prefix)))))
         (drop-while #(and after (tuple-equal? after (:v %)))))))

(defn- all-datoms
  [s]
  (if (contains? s :partition)
    (range-datoms s :record/partition-order [(:identity s) (key-bytes (:partition s))] nil)
    (range-datoms s :record/order [(:identity s)] nil)))

(defn- decode-datom
  [s dt context]
  (life/with-snapshot-state s
    #(let [row (d/pull (:db s) '[:record/original-id :record/value] (:e dt))
           bytes (:record/value row)]
       (observe! context :decode {:bytes (alength ^bytes bytes)})
       {:id (unpack (:record/original-id row))
        :value (codec/decode bytes)
        :bytes (alength ^bytes bytes)})))

(def ^:private map-ops
  {:get get-value
   :assoc assoc-value
   :dissoc dissoc-value
   :count (fn [s]
            (life/with-snapshot-state s #(if (contains? s :partition) (count (all-datoms s)) (:count s))))
   :entries (fn [s]
              (life/with-snapshot-state s
                #(map (fn [dt]
                        (let [{:keys [id value]} (decode-datom s dt {})]
                          [id value])) (all-datoms s))))})

(defn- projection-value
  [s]
  (if (:map? s)
    (mv/view s map-ops (:annotations s))
    (let [v (:value s)]
      (if (instance? clojure.lang.IObj v)
        (with-meta v (:annotations s))
        v))))

(defn- normalize-result
  [s result]
  (let [same? (and (instance? EngineView result)
                   (let [r (.engineState ^EngineView result)]
                     (and (identical? (:scope s) (:scope r)) (Arrays/equals ^bytes (:identity s) ^bytes (:identity r)))))
        next (if same?
               (do
                 (life/check-scope! (.engineState ^EngineView result))
                 (.engineState ^EngineView result))
               (let [empty (reduce (fn [s dt]
                                     (let [tx [:db/retractEntity (:e dt)]]
                                       (-> s
                                           (update :db d/db-with [tx])
                                           (update :tx conj tx)))) s (all-datoms s))
                     empty (assoc empty :count 0 :map? (map? result) :value nil)]
                 (if (map? result)
                   (reduce-kv assoc-value empty result)
                   (assoc empty :value result))))]
    (assoc next :annotations (meta result))))

(defn- read-events
  [context query args]
  (let [q (fn [q]
            (merge q args {:tenant-id (:tenant-id context)}))
        events (es/read (:event-store context) (if (vector? query)
                                                 (mapv q query)
                                                 (q query)))]
    (when (anomaly? events)
      (fail! nil :event-read-failed "Event read failed" {:anomaly events}))
    events))

(defn- apply-event!
  [rt s event context opts started]
  (let [scope {:active (atom true)
               :thread (Thread/currentThread)
               :fault (atom nil)}
        reducer (:reducer-fn (:definition s))
        scoped (assoc (dissoc s :basis) :scope scope :tx [])
        next (try
               (let [result (reducer (projection-value scoped) event)
                     schema-valid? (:state-valid? (:definition s))]
                 ;; Map-of validation is incremental in assoc. Full root schemas
                 ;; are checked only when declared, including ordinary replacements.
                 (when (and schema-valid? (not (schema-valid? result)))
                   (fail! (:model s) :invalid-record-output "Reducer result violates the state schema" {}))
                 (let [next (normalize-result scoped result)]
                   (when-let [e @(:fault scope)]
                     (throw e))
                   next))
               (finally
                 (reset! (:active scope) false)))
        metadata {:projection/key (:identity s)
                  :projection/map? (:map? next)
                  :projection/count (:count next)
                  :projection/watermark (:event/id event)
                  :projection/value (pack (:value next))
                  :projection/metadata (pack (:annotations next))}]
    (check-time! (:model s) started (:catch-up-ms opts) :catch-up)
    (let [t (System/nanoTime)]
      (commit! rt (conj (:tx next) metadata))
      (observe! context :batch {:events 1
                                :event-bytes (alength (codec/encode event))
                                :transaction-ns (- (System/nanoTime) t)}))))

(defn- catch-up!
  [rt context model definition scope query opts]
  (let [started (System/nanoTime)
        target (reduce (fn [head event]
                         (let [id (:event/id event)]
                           (if (or (nil? head) (pos? (compare id head)))
                             id
                             head)))
                       nil (read-events context query {:reverse? true :limit 1}))]
    (loop []
      (check-time! model started (:catch-up-ms opts) :catch-up)
      (let [s (scoped-state rt model definition scope)
            wm (:watermark s)]
        (if (or (nil? target) (and wm (not (neg? (compare wm target)))))
          s
          (let [events (:events
                        (transduce
                         (comp (take-while #(not (pos? (compare (:event/id %) target)))) (take (:batch-events opts)))
                         (completing
                          (fn [{:keys [bytes events] :as batch} event]
                            (let [n (alength (codec/encode event))
                                  bound (:batch-bytes opts)]
                              (when (and bound (> n bound))
                                (fail! model :event-exceeds-budget "Event exceeds byte allowance" {:event-bytes n}))
                              (if (and bound (> (+ bytes n) bound))
                                (reduced batch)
                                {:bytes (+ bytes n) :events (conj events event)}))))
                         {:bytes 0 :events []}
                         (read-events context query (cond-> {:reverse? false :limit (:batch-events opts)} wm (assoc :after wm)))))]
            (when (empty? events)
              (fail! model :event-history-unavailable "Cannot reach captured event head" {}))
            (doseq [event events]
              (check-time! model started (:catch-up-ms opts) :catch-up)
              (let [n (alength (codec/encode event))]
                (when (and (:batch-bytes opts) (> n (:batch-bytes opts)))
                  (fail! model :event-exceeds-budget "Event exceeds transaction byte allowance" {:event-bytes n})))
              (apply-event! rt (scoped-state rt model definition scope) event context opts started))
            (recur)))))))

(defn snapshot
  "Catch up to a finite event head and return one protected internal snapshot."
  [context model definition scope]
  (when-not (uuid? (:tenant-id context))
    (fail! model :invalid-tenant "Supply trusted tenant UUID" {}))
  (when-not (:event-store context)
    (fail! model :missing-event-store "Supply :event-store" {}))
  (when-not (or (nil? scope) (and (map? scope) (every? #{:tags :queries :partition-key} (keys scope))))
    (fail! model :invalid-scope "Use tags, queries or partition-key" {}))
  (when (and (contains? scope :partition-key) (not (:partition-fn definition)))
    (fail! model :invalid-scope "partition-key requires a partition-fn" {}))
  (let [rt (runtime! context)
        opts (settings context)
        query (or (:query definition) (:queries scope)
                  (cond-> {:types (:events definition)} (:tags scope) (assoc :tags (:tags scope))))
        identity-scope {:tenant-id (:tenant-id context)
                        :scope (not-empty (dissoc scope :partition-key))}]
    (life/with-request
      rt
      #(life/with-key-lock
         rt [:projection (vec (digest [(:tenant-id context) model (:version definition 1) (:scope identity-scope)]))]
         (fn []
           (when-let [e (or @(:failure rt) @(:lease-error rt))]
             (throw e))
           (ensure-projection! rt model definition identity-scope)
           (cond-> (catch-up! rt context model definition identity-scope query opts)
             (contains? scope :partition-key) (assoc :partition (:partition-key scope))))))))

(defn project
  [context model definition scope]
  (projection-value (snapshot context model definition scope)))

(defn- request!
  [s request paging?]
  (when-not (and (map? request) (every? (if paging?
                                          #{:index :prefix :limit :after}
                                          #{:index :prefix}) (keys request)))
    (fail! (:model s) :invalid-query "Use index/prefix and, for pages, limit/after" {}))
  (let [index (get-in s [:layout :indexes (:index request)])
        fs (:fields index)
        prefix (:prefix request [])]
    (when (and (or paging? (contains? request :index)) (nil? index))
      (fail! (:model s) :unknown-index "Select a declared index" {:index (:index request)}))
    (when-not (and (vector? prefix) (<= (count prefix) (count fs)) (every? true? (map valid-scalar? fs prefix)))
      (fail! (:model s) :invalid-prefix "Prefix must match consecutive leading index fields" {}))
    (when (and paging? (not (and (integer? (:limit request)) (pos? (:limit request)) (< (:limit request) Long/MAX_VALUE))))
      (fail! (:model s) :invalid-limit "Supply a positive signed 64-bit limit" {}))
    (when (and index (contains? s :partition))
      (fail! (:model s) :invalid-query "Use an index including the partition field for indexed partition queries" {}))
    index))

(defn- cursor-scope
  [s request]
  (b64 (digest [(:identity s) (:generation s) (:index request) (:prefix request [])])))

(defn- cursor-values
  [s request fs]
  (when-some [token (:after request)]
    (try
      (when-not (and (string? token) (<= (count token) (+ 46 (* 4 (quot (+ max-position-bytes 2) 3)))))
        (throw (Exception.)))
      (let [[version hash encoded :as parts] (str/split token #"\." -1)
            bytes (.decode (Base64/getUrlDecoder) ^String encoded)
            vs (cursor/decode bytes)]
        (when-not (and (= 3 (count parts)) (= "3" version) (= hash (cursor-scope s request))
                       (<= (alength bytes) max-position-bytes)
                       (java.util.Arrays/equals ^bytes bytes ^bytes (cursor/encode vs)) (vector? vs) (= (inc (count fs)) (count vs))
                       ((get-in s [:definition :indexed :id-valid?]) (peek vs))
                       (every? true? (map valid-scalar? fs (butlast vs)))
                       (= (:prefix request []) (subvec vs 0 (count (:prefix request [])))))
          (throw (Exception.)))
        vs)
      (catch Exception _
        (fail! (:model s) :invalid-cursor "Cursor does not match query, version or generation" {})))))

(defn- query-datoms
  [s index request after]
  (if index
    (range-datoms s (:attr index) (into [(:identity s)] (:prefix request []))
                  (when after
                    (into [(:identity s)] (conj (pop after) (str (peek after))))))
    (all-datoms s)))

(defn- ensure-map!
  [s]
  (when-not (:map? s)
    (fail! (:model s) :not-map-projection "This operation requires map-shaped projection state" {})))

(defn record
  [context model definition id scope]
  (let [s (snapshot context model definition scope)
        missing (Object.)
        _ (ensure-map! s)
        _ (when-let [valid? (get-in definition [:indexed :id-valid?])]
            (when-not (valid? id)
              (fail! model :invalid-record-id "Record ID does not satisfy schema" {:record-id id})))
        v (get-value s id missing)]
    {:item (when-not (identical? missing v)
             {:id id :value v})
     :watermark (:watermark s)}))

(defn page
  [context model definition request scope]
  (let [s (snapshot context model definition scope)
        _ (ensure-map! s)
        index (request! s request true)
        after (cursor-values s request (:fields index))
        opts (settings context)
        start (System/nanoTime)
        result (reduce
                (fn [{:keys [items bytes] :as result} dt]
                  (observe! context :index-entry {})
                  (cond
                    (= (count items) (:limit request))
                    (reduced (assoc result :more? true))
                    (elapsed? start (:page-ms opts))
                    (if (seq items)
                      (reduced (assoc result :more? true))
                      (fail! model :resource-budget-exceeded "Page time budget expired before progress" {:phase :page :retryable true}))
                    :else
                    (let [row (d/pull (:db s) '[:record/value] (:e dt))
                          n (alength ^bytes (:record/value row))]
                      (if (and (:page-bytes opts) (> (+ bytes n) (:page-bytes opts)))
                        (if (seq items)
                          (reduced (assoc result :more? true))
                          (fail! model :record-exceeds-budget "First record exceeds page allowance"
                                 {:record-bytes n :budget-bytes (:page-bytes opts)}))
                        (let [item (dissoc (decode-datom s dt context) :bytes)]
                          {:items (conj items item)
                           :bytes (+ bytes n)
                           :position (position s (:fields index) (:id item) (:value item))})))))
                {:items [] :bytes 0} (query-datoms s index request after))]
    (java.lang.ref.Reference/reachabilityFence s)
    {:items (:items result)
     :watermark (:watermark s)
     :next-cursor (when (:more? result)
                    (str "3." (cursor-scope s request) "." (b64 (cursor/encode (:position result)))))}))

(defn reduce-records
  [context model definition request rf initial scope]
  (let [s (snapshot context model definition scope)
        _ (ensure-map! s)
        index (request! s request false)
        opts (settings context)
        start (System/nanoTime)
        bytes (volatile! 0)]
    (try
      (reduce (fn [a dt]
                (check-time! model start (:reduce-ms opts) :reduce)
                (let [item (decode-datom s dt context)]
                  (vswap! bytes + (:bytes item))
                  (when (and (:reduce-bytes opts) (> @bytes (:reduce-bytes opts)))
                    (fail! model :resource-budget-exceeded "Reduction byte budget exhausted" {:phase :reduce}))
                  (let [next (rf a (dissoc item :bytes))]
                    (when-not (reduced? next)
                      (check-time! model start (:reduce-ms opts) :reduce))
                    next)))
              initial (query-datoms s index request nil))
      (finally
        (java.lang.ref.Reference/reachabilityFence s)))))
