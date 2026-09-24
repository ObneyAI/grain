(ns ai.obney.grain.read-model-processor-v3.interface
  "Unified durable projections. Every reducer uses (state,event)->state and every
   successful event is committed with its watermark in Datahike. Map entries are
   individually stored; project returns a retained, read-only map view. Within a
   reducer, assoc/update/dissoc and reductions produce immutable intermediate maps.
   Other serializable state shapes use the same store and checkpoint lifecycle.

   Optional :indexes derive types from :schema; no :kind or separate cache exists.
   Supply :projection-store from open-store alongside :event-store and :tenant-id.
   v2 storage is not migrated: start fresh and replay the event log."
  (:require [ai.obney.grain.read-model-processor-v3.core :as core]
            [ai.obney.grain.read-model-processor-v3.index-definition :as index-definition]))

(def read-model-registry* (atom {}))

(defn- registration
  [rm-name reducer-fn opts]
  (when-not (ifn? reducer-fn)
    (index-definition/invalid! rm-name "Supply a callable reducer" {:option :reducer}))
  (let [compiled (index-definition/compile-definition rm-name opts)]
    (assoc opts :reducer-fn reducer-fn :indexed compiled :state-valid? (:state-valid? compiled)
           :descriptor (assoc (select-keys opts [:schema :indexes :events :version :query])
                              :partitioned? (boolean (:partition-fn opts))))))

(defn- register-entry!
  [rm-name reducer-fn opts definition]
  (when-not (qualified-keyword? rm-name)
    (index-definition/invalid! rm-name "Use a qualified read-model keyword" {:option :name}))
  (let [entry (merge (registration rm-name reducer-fn opts) definition)]
    (swap! read-model-registry*
           (fn [registry]
             (when-let [existing (get registry rm-name)]
               (when (and (= (:version existing 1) (:version entry 1))
                          (or (not= (:descriptor existing) (:descriptor entry))
                              (and (:definition/value existing)
                                   (:definition/value entry)
                                   (not= (:definition/value existing)
                                         (:definition/value entry)))))
                 (core/fail! rm-name :definition-version-conflict
                             "Definition changed; bump :version and rebuild" {})))
             (assoc registry rm-name entry)))))

(defn register-read-model!
  [rm-name reducer-fn opts]
  (register-entry! rm-name reducer-fn opts nil))

(defn ^:no-doc register-declared!
  [rm-name reducer-fn opts definition]
  (register-entry! rm-name reducer-fn opts definition))

(defn global-read-model-registry
  []
  @read-model-registry*)

(defmacro defreadmodel
  "Define a pure projection reducer, initially receiving {}. Optional :schema
   describes its state; :indexes {:by-name {:fields [:surname]}} requires a map-of
   schema. Indexed and nonindexed models both support project and point reads.
   Intermediate backed maps cannot escape the reducer's thread or invocation."
  {:arglists '([ns-kw name opts? docstring? [state event] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring bindings body] (if (string? (first args))
                                    [(first args) (second args) (drop 2 args)]
                                    [nil (first args) (rest args)])
        rm-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))
        definition (when docstring
                     {:definition/description docstring
                      :definition/source {:ns (str *ns*) :file *file* :line (:line (meta &form))}
                      :definition/value {:description docstring
                                         :options (select-keys opts [:events :version :schema :indexes])}})]
    `(do
       (defn ~var-name ~@(when docstring
                           [docstring]) ~bindings ~@body)
       (register-declared! ~rm-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))

(defn- definition!
  [name]
  (or (get @read-model-registry* name)
      (core/fail! name :unknown-read-model "Register the read model before querying it" {})))

(defn open-store
  "Open a Closeable, single-owner Datahike store. Required :storage-dir; optional
   :backend (:lmdb default, or :file), :gc-interval-ms (300000), :pin-ttl-ms
   (3600000), and LMDB :map-size (16 GiB). LMDB requires Java 22+ and native LMDB.
   Closing rejects new work; retained committed maps remain readable."
  [options]
  (core/open-store options))

(defn close-store!
  [store]
  (.close ^java.io.Closeable store))

(defn collect!
  "Request storage collection. Normally maintenance runs automatically. Retained
   committed results remain protected. Full collection can be expensive."
  [store]
  (core/collect! store))

(defn store-status
  [store]
  (core/store-status store))

(defn project
  "Catch up to a finite event head and return committed state. Map results are
   read-only storage-backed values; use into {} to materialize an editable copy.
   Optional scope: :tags, :queries, :partition-key. All scopes are tenant-isolated."
  ([context name]
   (project context name nil))
  ([context name scope]
   (core/project context name (definition! name) scope)))

(defn p
  "Low-level projection with :f, :query, :name and :version. Uses the same durable
   engine as project; optional :schema, :indexes and partition options apply."
  [context {:keys [f name scope partition-key] :as args}]
  (core/project context name (registration name f (dissoc args :f :name :scope :partition-key))
                (cond-> scope (contains? args :partition-key) (assoc :partition-key partition-key))))

(defn record
  "Point lookup in a map projection: {:item {:id k :value v}|nil :watermark id}."
  ([context name id]
   (record context name id nil))
  ([context name id scope]
   (core/record context name (definition! name) id scope)))

(defn page
  "Ordered native-index page: {:items [{:id :value} ...] :watermark :next-cursor}.
   Request: :index, required positive :limit, optional equality :prefix and :after.
   Follow :next-cursor even on short pages. Each call uses one committed snapshot."
  ([context name request]
   (page context name request nil))
  ([context name request scope]
   (core/page context name (definition! name) request scope)))

(defn reduce-records
  "Stream {:id :value} entries through rf, honoring reduced. Request {} traverses
   a map; {:index name :prefix [...]} seeks a declared index. Retains one snapshot
   during the call, including if rf triggers concurrent projection updates."
  ([context name request rf initial]
   (reduce-records context name request rf initial nil))
  ([context name request rf initial scope]
   (core/reduce-records context name (definition! name) request rf initial scope)))
