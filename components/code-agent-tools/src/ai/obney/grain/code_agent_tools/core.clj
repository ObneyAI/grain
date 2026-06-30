(ns ai.obney.grain.code-agent-tools.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [malli.core :as m]
            [malli.error :as me]))

;; The pure introspection + event-model validation surface now lives in the
;; shippable event-model-validator component; re-export it here so the dev/agent
;; interface is unchanged. code-agent-tools itself remains dev-only: it adds the
;; state-mutating execution tools (install!/invoke!/events/projection/diagnostics)
;; and the REPL-served guides.

(defn catalog [] (emv/catalog))
(defn schemas [] (emv/schemas))
(defn validate-event-model
  ([model] (emv/validate-event-model model))
  ([model opts] (emv/validate-event-model model opts)))
(defn validate-event-model-file
  ([path] (emv/validate-event-model-file path))
  ([path opts] (emv/validate-event-model-file path opts)))
(defn validate-event-model-var
  ([sym] (emv/validate-event-model-var sym))
  ([sym opts] (emv/validate-event-model-var sym opts)))
(defn event-model-coverage [model] (emv/event-model-coverage model))

;; ===========================================================================
;; Installed runtime + execution tools (dev-only)
;; ===========================================================================

(defonce runtime* (atom nil))

(defn install!
  [{:keys [mode] :as runtime}]
  (when (and mode (not= :dev mode))
    (throw (ex-info "code-agent-tools is dev-only; install with :mode :dev" {:mode mode})))
  (let [installed (assoc runtime :mode :dev :installed-at (time/now))]
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
  (or @runtime* (throw (ex-info "code-agent-tools runtime is not installed" {}))))

(defn base-context [] (or (:context (require-runtime)) {}))

(defn require-tenant-id
  [ctx args]
  (or (:tenant-id args)
      (:tenant-id ctx)
      (throw (ex-info "A tenant id is required" {:args (dissoc args :command :query)}))))

(defn event-store [ctx]
  (or (:event-store ctx) (throw (ex-info "No :event-store is available in the installed context" {}))))

(defn cache [ctx]
  (or (:cache ctx) (throw (ex-info "No :cache is available in the installed context" {}))))

(defn validation-result
  [schema value]
  (try
    (if-let [explain (m/explain schema value)]
      {:valid? false
       :schema (emv/sanitize schema)
       :value (emv/sanitize value)
       :explain/data (emv/sanitize explain)
       :explain/humanized (me/humanize explain)}
      {:valid? true :schema (emv/sanitize schema)})
    (catch Exception e
      {:valid? false :schema (emv/sanitize schema)
       :error/message (.getMessage e) :error/class (.getName (class e))})))

(defn validate
  ([schema value] (validation-result schema value))
  ([kind schema value]
   (case kind
     :event (validation-result [:and :ai.obney.grain.event-store-v3.interface.schemas/event schema] value)
     :command (validation-result [:and :ai.obney.grain.command-processor-v2.interface.schemas/command schema] value)
     :query (validation-result [:and :ai.obney.grain.query-schema.interface/query schema] value)
     (validation-result schema value))))

(defn explain-schema
  ([schema] {:schema/name schema :schema (emv/sanitize (m/deref schema))})
  ([schema value] (merge (explain-schema schema) (validate schema value))))

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
         call-ctx (assoc ctx :tenant-id tenant-id
                         :event-store (event-store ctx) :cache (cache ctx))]
     (rmp/project call-ctx read-model-name scope))))

(defn system-value
  [system key-name]
  (or (get system (keyword key-name))
      (some (fn [[k v]] (when (= key-name (name k)) v)) system)))

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
               (try (control-plane/route-for-tenant leases active (:node-id cp) (:tenant-id args))
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
                   :schemas (count (emv/schema-registry))}
      :event-store {:present? (contains? ctx :event-store)
                    :tenants (when (:event-store ctx)
                               (try (es/tenants (:event-store ctx))
                                    (catch Exception e {:error/message (.getMessage e)})))}
      :cache {:present? (contains? ctx :cache)
              :l1 (try (rmp/l1-stats) (catch Exception e {:error/message (.getMessage e)}))}
      :control-plane (control-plane-diagnostics rt ctx args)})))

;; ===========================================================================
;; REPL-served authoritative instructions ("guides")
;; ===========================================================================

(defonce guide-registry* (atom {}))

(defn register-guide! [{:keys [id] :as g}] (swap! guide-registry* assoc id g) id)

(defmacro defguide [id m] `(register-guide! (assoc ~m :id ~id)))

(def ^:private tool-ids
  [:install! :runtime :catalog :schemas :explain-schema :validate
   :invoke-command! :invoke-query :events :projection :diagnostics
   :validate-event-model :validate-event-model-file :validate-event-model-var
   :event-model-coverage :guides :guide])

(defn- tool-guide [id]
  (when (or (keyword? id) (symbol? id))
    (when-let [v (try (requiring-resolve
                       (symbol "ai.obney.grain.code-agent-tools.interface" (name id)))
                      (catch Exception _ nil))]
      (merge {:id (keyword (name id)) :applies-to :tool} (emv/source-info v)))))

(defn- block-in-catalog? [id]
  (boolean (when-let [cat (try (catalog) (catch Exception _ nil))] (emv/catalog-block cat id))))

(defn- block-usage-card [id opts]
  (let [cat (try (catalog) (catch Exception _ nil))
        entry (when cat (emv/catalog-block cat id))
        sb (when-let [m (:spec opts)] (emv/spec-block m id))]
    (cond-> {:id id :applies-to :block}
      entry (merge (select-keys entry [:kind :schema :source :authorized?/present?
                                       :events/consumes :schedule :produces :reads]))
      (:doc (:source entry)) (assoc :doc (:doc (:source entry)))
      sb (merge {:spec/kind (:kind sb)
                 :spec/description (:description sb)
                 :spec/given-when-thens (:given-when-thens sb)
                 :spec/reads (:reads sb)
                 :spec/produces (:produces sb)
                 :spec/consumes (:consumes sb)
                 :spec/subscribes (:subscribes sb)
                 :spec/queries (:queries sb)
                 :spec/commands (:commands sb)}))))

(defn guides []
  (let [cat (try (catalog) (catch Exception _ nil))
        concept (for [[id g] @guide-registry*]
                  {:id id :title (:title g) :summary (:summary g) :applies-to (:applies-to g :concept)})
        blocks (when cat
                 (for [k [:commands :queries :read-models :processors :periodic-triggers]
                       [bk entry] (get cat k)]
                   {:id bk :title (str (name (:kind entry)) " " bk)
                    :summary (or (:doc (:source entry)) "Live grain block.")
                    :applies-to :block}))
        tools (for [t tool-ids] {:id t :title (str "tool " (name t)) :applies-to :tool})]
    (emv/sanitize {:guides (vec (concat concept blocks tools))})))

(defn guide
  ([id] (guide id {}))
  ([id opts]
   (try
     (cond
       (contains? @guide-registry* id) (emv/sanitize (get @guide-registry* id))
       (and (qualified-keyword? id) (block-in-catalog? id)) (emv/sanitize (block-usage-card id opts))
       (tool-guide id) (emv/sanitize (tool-guide id))
       :else {:guide/unknown id :did-you-mean (vec (sort-by str (keys @guide-registry*)))})
     (catch Exception e
       {:guide/error (.getMessage e) :id id}))))
