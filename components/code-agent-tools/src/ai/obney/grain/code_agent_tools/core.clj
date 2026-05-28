(ns ai.obney.grain.code-agent-tools.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.set :as set]
            [malli.core :as m]
            [malli.error :as me]))

(defonce runtime* (atom nil))

(defn source-info
  [x]
  (when (var? x)
    (let [m (meta x)]
      (cond-> {:var (symbol (str (ns-name (:ns m))) (str (:name m)))}
        (:ns m) (assoc :ns (ns-name (:ns m)))
        (:name m) (assoc :name (:name m))
        (:file m) (assoc :file (:file m))
        (:line m) (assoc :line (:line m))
        (:column m) (assoc :column (:column m))
        (:doc m) (assoc :doc (:doc m))
        (:arglists m) (assoc :arglists (:arglists m))))))

(declare sanitize)

(defn sanitize-map
  [m]
  (into {}
        (map (fn [[k v]] [k (sanitize v)]))
        m))

(defn sanitize-coll
  [coll]
  (into (empty coll) (map sanitize) coll))

(defn sanitize
  [x]
  (cond
    (var? x) (source-info x)
    (fn? x) {:function/class (.getName (class x))}
    (class? x) {:class (.getName ^Class x)}
    (map? x) (sanitize-map x)
    (vector? x) (mapv sanitize x)
    (set? x) (into #{} (map sanitize) x)
    (seq? x) (mapv sanitize x)
    :else x))

(defn schema-summary
  [schema-reg schema-name]
  (cond-> {:name schema-name
           :present? (contains? schema-reg schema-name)}
    (contains? schema-reg schema-name)
    (assoc :schema (sanitize (get schema-reg schema-name)))))

(defn schema-summaries
  [schema-reg schema-names]
  (into {}
        (map (fn [schema-name]
               [schema-name (schema-summary schema-reg schema-name)]))
        schema-names))

(defn registry-entry
  [kind schema-reg [entry-name {:keys [handler-fn reducer-fn] :as opts}]]
  (let [f (or handler-fn reducer-fn)
        public-opts (dissoc opts :handler-fn :reducer-fn)]
    (cond-> {:kind kind
             :name entry-name
             :schema (schema-summary schema-reg entry-name)
             :opts (sanitize public-opts)}
      f (assoc :source (source-info f))
      (contains? opts :authorized?) (assoc :authorized?/present? true)
      (not (contains? opts :authorized?)) (assoc :authorized?/present? false)
      (:topics opts) (assoc :events/consumes (:topics opts)
                            :events/schemas (schema-summaries schema-reg (:topics opts)))
      (:events opts) (assoc :events/consumes (:events opts)
                            :events/schemas (schema-summaries schema-reg (:events opts)))
      (:schedule opts) (assoc :schedule (:schedule opts)))))

(defn schema-entry
  [[schema-name schema]]
  {:kind :schema
   :name schema-name
   :schema (sanitize schema)})

(defn schema-registry
  []
  @schema-util/registry*)

(defn schemas
  []
  (into {}
        (map (fn [[k v]] [k (sanitize v)]))
        (schema-registry)))

(defn schema-names
  []
  (set (keys (schema-registry))))

(defn missing-schema-names
  [names]
  (set/difference (set names) (schema-names)))

(defn catalog
  []
  (let [commands (cp/global-command-registry)
        queries (qp/global-query-registry)
        read-models (rmp/global-read-model-registry)
        processors @tp/processor-registry*
        periodic @pt/periodic-trigger-registry*
        schema-reg (schema-registry)]
    {:commands (into {} (map (fn [e] [(key e) (registry-entry :command schema-reg e)])) commands)
     :queries (into {} (map (fn [e] [(key e) (registry-entry :query schema-reg e)])) queries)
     :read-models (into {} (map (fn [e] [(key e) (registry-entry :read-model schema-reg e)])) read-models)
     :processors (into {} (map (fn [e] [(key e) (registry-entry :todo-processor schema-reg e)])) processors)
     :periodic-triggers (into {} (map (fn [e] [(key e) (registry-entry :periodic-trigger schema-reg e)])) periodic)
     :schemas (into {} (map (fn [e] [(key e) (schema-entry e)])) schema-reg)
     :missing-schemas {:commands (missing-schema-names (keys commands))
                       :queries (missing-schema-names (keys queries))
                       :read-model-events (missing-schema-names (mapcat :events (vals read-models)))
                       :processor-topics (missing-schema-names (mapcat :topics (vals processors)))}}))

(defn install!
  [{:keys [mode] :as runtime}]
  (when (and mode (not= :dev mode))
    (throw (ex-info "code-agent-tools is dev-only; install with :mode :dev"
                    {:mode mode})))
  (let [installed (assoc runtime
                         :mode :dev
                         :installed-at (time/now))]
    (reset! runtime* installed)
    (select-keys installed [:mode :installed-at])))

(defn runtime
  []
  (when-let [rt @runtime*]
    (-> rt
        (select-keys [:mode :installed-at])
        (assoc :system/keys (set (keys (:system rt)))
               :context/keys (set (keys (:context rt)))))))

(defn require-runtime
  []
  (or @runtime*
      (throw (ex-info "code-agent-tools runtime is not installed" {}))))

(defn base-context
  []
  (or (:context (require-runtime)) {}))

(defn require-tenant-id
  [ctx args]
  (or (:tenant-id args)
      (:tenant-id ctx)
      (throw (ex-info "A tenant id is required" {:args (dissoc args :command :query)}))))

(defn event-store
  [ctx]
  (or (:event-store ctx)
      (throw (ex-info "No :event-store is available in the installed context" {}))))

(defn cache
  [ctx]
  (or (:cache ctx)
      (throw (ex-info "No :cache is available in the installed context" {}))))

(defn validation-result
  [schema value]
  (try
    (if-let [explain (m/explain schema value)]
      {:valid? false
       :schema (sanitize schema)
       :value (sanitize value)
       :explain/data (sanitize explain)
       :explain/humanized (me/humanize explain)}
      {:valid? true
       :schema (sanitize schema)})
    (catch Exception e
      {:valid? false
       :schema (sanitize schema)
       :error/message (.getMessage e)
       :error/class (.getName (class e))})))

(defn validate
  ([schema value]
   (validation-result schema value))
  ([kind schema value]
   (case kind
     :event (validation-result [:and :ai.obney.grain.event-store-v3.interface.schemas/event schema] value)
     :command (validation-result [:and :ai.obney.grain.command-processor-v2.interface.schemas/command schema] value)
     :query (validation-result [:and :ai.obney.grain.query-schema.interface/query schema] value)
     (validation-result schema value))))

(defn explain-schema
  ([schema]
   {:schema/name schema
    :schema (sanitize (m/deref schema))})
  ([schema value]
   (merge (explain-schema schema)
          (validate schema value))))

(defn invoke-command!
  [command]
  (let [ctx (base-context)
        tenant-id (require-tenant-id ctx command)
        command' (-> command
                     (dissoc :tenant-id)
                     (update :command/id #(or % (random-uuid)))
                     (update :command/timestamp #(or % (time/now))))
        call-ctx (assoc ctx :tenant-id tenant-id :command command')]
    (cp/process-command call-ctx)))

(defn invoke-query
  [query]
  (let [ctx (base-context)
        tenant-id (require-tenant-id ctx query)
        query' (-> query
                   (dissoc :tenant-id)
                   (update :query/id #(or % (random-uuid)))
                   (update :query/timestamp #(or % (time/now))))
        call-ctx (assoc ctx :tenant-id tenant-id :query query')]
    (qp/process-query call-ctx)))

(defn events
  [{:keys [types tags limit] :as args}]
  (let [ctx (base-context)
        tenant-id (require-tenant-id ctx args)
        query (cond-> {:tenant-id tenant-id}
                types (assoc :types types)
                tags (assoc :tags tags))
        xf (if limit (take limit) identity)]
    (into [] xf (es/read (event-store ctx) query))))

(defn projection
  ([read-model-name] (projection read-model-name nil))
  ([read-model-name scope]
   (let [ctx (base-context)
         tenant-id (require-tenant-id ctx (or scope {}))
         call-ctx (assoc ctx
                         :tenant-id tenant-id
                         :event-store (event-store ctx)
                         :cache (cache ctx))]
     (rmp/project call-ctx read-model-name scope))))

(defn system-value
  [system key-name]
  (or (get system (keyword key-name))
      (some (fn [[k v]]
              (when (= key-name (name k)) v))
            system)))

(defn control-plane-diagnostics
  [rt ctx args]
  (if-let [cp (system-value (:system rt) "control-plane")]
    (let [threshold (or (:staleness-threshold-ms args) 6000)
          cp-ctx (or (:ctx cp) (system-value (:system rt) "ctx") ctx)
          active (try (control-plane/project-active-nodes cp-ctx threshold)
                      (catch Exception e {:error/message (.getMessage e)}))
          leases (try (control-plane/project-lease-ownership cp-ctx)
                      (catch Exception e {:error/message (.getMessage e)}))]
      (cond-> {:control-plane/present? true
               :node/id (:node-id cp)
               :active-nodes active
               :lease-ownership leases
               :running-processors (try (control-plane/running-processors cp)
                                        (catch Exception e {:error/message (.getMessage e)}))}
        (:tenant-id args)
        (assoc :route
               (try
                 (control-plane/route-for-tenant leases active (:node-id cp) (:tenant-id args))
                 (catch Exception e {:error/message (.getMessage e)})))))
    {:control-plane/present? false}))

(defn diagnostics
  ([] (diagnostics {}))
  ([args]
   (let [rt (require-runtime)
         ctx (or (:context rt) {})]
     {:runtime (runtime)
      :registries {:commands (count (cp/global-command-registry))
                   :queries (count (qp/global-query-registry))
                   :read-models (count (rmp/global-read-model-registry))
                   :processors (count @tp/processor-registry*)
                   :periodic-triggers (count @pt/periodic-trigger-registry*)
                   :schemas (count (schema-registry))}
      :event-store {:present? (contains? ctx :event-store)
                    :tenants (when (:event-store ctx)
                               (try (es/tenants (:event-store ctx))
                                    (catch Exception e {:error/message (.getMessage e)})))}
      :cache {:present? (contains? ctx :cache)
              :l1 (try (rmp/l1-stats)
                       (catch Exception e {:error/message (.getMessage e)}))}
      :control-plane (control-plane-diagnostics rt ctx args)})))
