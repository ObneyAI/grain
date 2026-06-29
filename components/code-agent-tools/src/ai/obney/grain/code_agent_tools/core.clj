(ns ai.obney.grain.code-agent-tools.core
  (:refer-clojure :exclude [read])
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-model.interface]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.time.interface :as time]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
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
             :opts (sanitize public-opts)
             ;; How to learn this block over the REPL: (guide entry-name) returns
             ;; a runtime-assembled usage card.
             :instructions {:guide entry-name}}
      f (assoc :source (source-info f))
      (contains? opts :authorized?) (assoc :authorized?/present? true)
      (not (contains? opts :authorized?)) (assoc :authorized?/present? false)
      (:topics opts) (assoc :events/consumes (:topics opts)
                            :events/schemas (schema-summaries schema-reg (:topics opts)))
      (:events opts) (assoc :events/consumes (:events opts)
                            :events/schemas (schema-summaries schema-reg (:events opts)))
      (:schedule opts) (assoc :schedule (:schedule opts))
      ;; Optional event-model production/read declarations, opt-in at the def
      ;; site. These promote the spec's production edges (command->event,
      ;; processor->command, periodic->event) and read edges (command/query->
      ;; read-model) into the catalog so the validator can CONFIRM them, not just
      ;; assert them. Absent when the service has not annotated.
      (:grain.event-model/produces opts) (assoc :produces (:grain.event-model/produces opts))
      (:grain.event-model/reads opts) (assoc :reads (:grain.event-model/reads opts)))))

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

;; ===========================================================================
;; Service-area-first event-model: structural validation against the live runtime
;; ===========================================================================
;;
;; Reconciles an EDN event-model spec (areas -> blocks keyed :<area>/<name>)
;; against the live grain registries exposed by `catalog`. Structural only: no
;; command invocation, no Given/When/Then execution. Builds on `catalog`, needs
;; no `install!`, degrades when no registries are loaded, and never throws.

(def ^:private connection-grammar
  "Legal CQRS data-flow adjacency between block kinds (A -> #{Bs} means A leads
   to B). Reconciled to grain's actual def* contracts: query inserted on the read
   path; periodic-task emits events; commands/queries read read-models."
  {:command        #{:event}
   :event          #{:read-model :todo-processor}
   :read-model     #{:query :command :screen}
   :query          #{:screen}
   :screen         #{:command}
   :todo-processor #{:command}
   :periodic-task  #{:command :event}})

(def ^:private kind->area-field
  {:command :commands :event :events :read-model :read-models :query :queries
   :todo-processor :todo-processors :periodic-task :periodic-tasks :screen :screens})

(def ^:private all-kinds
  [:command :event :read-model :query :todo-processor :periodic-task :screen])

(def ^:private intent-edges
  "Per-kind dependency fields and the kind(s) each target must be."
  {:command        {:reads #{:read-model} :produces #{:event}}
   :query          {:reads #{:read-model}}
   :read-model     {:consumes #{:event}}
   :todo-processor {:subscribes #{:event} :produces #{:command}}
   :periodic-task  {:produces #{:event :command}}
   :screen         {:queries #{:query} :commands #{:command}}})

(def ^:private severity-rank {:error 0 :warning 1 :info 2})

(defn- finding [type severity m]
  (merge {:type type :severity severity} m))

;; ---- spec accessors -------------------------------------------------------

(defn- spec-kind-map
  "Merged {block-name block} for `kind` across every area in `model`."
  [model kind]
  (reduce (fn [acc [_ area-map]] (merge acc (get area-map (kind->area-field kind)))) {} model))

(defn- spec-names [model kind] (set (keys (spec-kind-map model kind))))
(defn- spec-has? [model nm kind] (contains? (spec-names model kind) nm))

;; The areas a spec declares, as namespace strings. Live-iterating checks
;; (coverage, missing-schemas, auth) are scoped to these so validating one area's
;; spec does not report on blocks that belong to areas it never claims to cover.
(defn- spec-area-names [model] (set (map name (keys model))))
(defn- in-spec-area? [areas nm] (contains? areas (namespace nm)))

;; ---- live snapshot (derived from catalog) ---------------------------------

(defn- live-model
  "A normalized snapshot of the live runtime, derived from `catalog`."
  [cat]
  (let [commands (set (keys (:commands cat)))
        queries (set (keys (:queries cat)))
        read-models (set (keys (:read-models cat)))
        processors (set (keys (:processors cat)))
        periodics (set (keys (:periodic-triggers cat)))
        schema-keys (set (keys (:schemas cat)))
        handler-keys (set/union commands queries read-models processors periodics)
        rm-consumes (into {} (map (fn [[k v]] [k (set (:events/consumes v))])) (:read-models cat))
        tp-subscribes (into {} (map (fn [[k v]] [k (set (:events/consumes v))])) (:processors cat))
        consumed (reduce set/union #{} (concat (vals rm-consumes) (vals tp-subscribes)))
        ;; app event schemas: registered schemas that are neither handlers nor
        ;; framework/meta schemas (long dotted ns, or the :event-model meta keys).
        event-schema-keys (->> (set/difference schema-keys handler-keys)
                               (filter qualified-keyword?)
                               (filter (fn [k] (let [ns (namespace k)]
                                                 (and ns (not (str/includes? ns ".")) (not= "event-model" ns)))))
                               set)]
    {:command commands :query queries :read-model read-models
     :todo-processor processors :periodic-task periodics
     :event (set/union consumed event-schema-keys)
     :consumed consumed
     :rm-consumes rm-consumes :tp-subscribes tp-subscribes
     :periodic-schedule (into {} (map (fn [[k v]] [k (:schedule v)])) (:periodic-triggers cat))
     ;; opt-in production/read declarations promoted from def-site opts, keyed by
     ;; [kind name] because a name may denote blocks of several kinds.
     :declared-produces (into {} (concat
                                  (for [[k v] (:commands cat) :when (:produces v)] [[:command k] (set (:produces v))])
                                  (for [[k v] (:processors cat) :when (:produces v)] [[:todo-processor k] (set (:produces v))])
                                  (for [[k v] (:periodic-triggers cat) :when (:produces v)] [[:periodic-task k] (set (:produces v))])))
     :declared-reads (into {} (concat
                               (for [[k v] (:commands cat) :when (:reads v)] [[:command k] (set (:reads v))])
                               (for [[k v] (:queries cat) :when (:reads v)] [[:query k] (set (:reads v))])))
     :auth (into {} (map (fn [[k v]] [k (boolean (:authorized?/present? v))]))
                 (merge (:commands cat) (:queries cat)))
     :missing-schemas (:missing-schemas cat)}))

(defn- registries-present? [live]
  (boolean (some seq (map #(get live %) [:command :query :read-model :todo-processor :periodic-task]))))

(defn- live-has? [live nm kind] (boolean (and live (contains? (get live kind) nm))))
(defn- block-exists? [model live nm kind] (or (spec-has? model nm kind) (live-has? live nm kind)))

;; ---- flow endpoint helpers ------------------------------------------------

(defn- endpoint-kind [ep] (when (vector? ep) (first ep)))
(defn- endpoint-ref [ep] (when (vector? ep) (second ep)))

;; ---- schema helpers -------------------------------------------------------

(defn- classify-schema-error [e]
  (let [d (ex-data e)]
    (if (and (= :malli.core/invalid-schema (:type d)) (qualified-keyword? (:schema d)))
      :schema/unresolved-ref
      :schema/malformed)))

(defn- schema-form [s] (try (m/form (m/schema s)) (catch Exception _ ::unparseable)))

;; ---- checks (C2..C14; C1 is handled inline by validate-event-model) --------

(defn- check-namespacing [model]
  (for [[area area-map] model
        :let [area-name (name area)]
        [field blocks] (select-keys area-map [:commands :events :read-models :queries
                                              :todo-processors :periodic-tasks :screens :flows])
        [block-key _] blocks
        :when (not= area-name (namespace block-key))]
    (finding :block/misnamespaced :error
             {:area area :field field :block block-key
              :expected-namespace area-name :actual-namespace (namespace block-key)
              :message (str block-key " must be namespaced " area-name "/* to belong to area " area ".")})))

(defn- check-schema-wellformed [model]
  (for [[area area-map] model
        [field kind] [[:commands :command] [:events :event] [:read-models :read-model] [:queries :query]]
        [block-key block] (get area-map field)
        :let [schema (:schema block)
              err (when (some? schema) (try (m/schema schema) nil (catch Exception e e)))]
        :when (some? err)
        :let [ftype (classify-schema-error err)]]
    (finding ftype (if (= ftype :schema/malformed) :error :warning)
             {:area area :kind kind :block block-key
              :error/message (ex-message err)
              :message (str "Schema for " block-key " did not parse (" (name ftype) ").")})))

(defn- check-block-refs [model live]
  (for [[area area-map] model
        [field kind] [[:commands :command] [:events :event] [:read-models :read-model]
                      [:queries :query] [:todo-processors :todo-processor]
                      [:periodic-tasks :periodic-task] [:screens :screen]]
        [block-key block] (get area-map field)
        [edge-field expected-kinds] (get intent-edges kind)
        ref (get block edge-field)
        :let [ok? (some #(block-exists? model live ref %) expected-kinds)
              elsewhere? (some #(block-exists? model live ref %) all-kinds)]
        :when (not ok?)]
    (if elsewhere?
      (finding :ref/wrong-kind :error
               {:area area :kind kind :block block-key :edge edge-field :ref ref
                :expected-kinds expected-kinds
                :message (str block-key " " edge-field " references " ref
                              ", which exists but is not " (str/join "/" (map name expected-kinds)) ".")})
      (finding :ref/dangling :error
               {:area area :kind kind :block block-key :edge edge-field :ref ref
                :expected-kinds expected-kinds
                :message (str block-key " " edge-field " references unknown block " ref ".")}))))

(defn- step-findings [model live area flow-key tos i step]
  (let [fk (endpoint-kind (:from step)) fr (endpoint-ref (:from step))
        tk (endpoint-kind (:to step))   tr (endpoint-ref (:to step))]
    (cond-> []
      (and fr (not (block-exists? model live fr fk)))
      (conj (finding :flow/dangling-reference :error
                     {:area area :flow flow-key :endpoint :from :kind fk :ref fr
                      :message (str "Flow " flow-key " :from references unknown " fk " " fr ".")}))

      (and tr (not (block-exists? model live tr tk)))
      (conj (finding :flow/dangling-reference :error
                     {:area area :flow flow-key :endpoint :to :kind tk :ref tr
                      :message (str "Flow " flow-key " :to references unknown " tk " " tr ".")}))

      (and fk tk (not (contains? (get connection-grammar fk #{}) tk)))
      (conj (finding :flow/illegal-connection :error
                     {:area area :flow flow-key :from-kind fk :to-kind tk :from fr :to tr
                      :allowed (get connection-grammar fk #{}) :confirmed-by-runtime? false
                      :message (str "Flow " flow-key ": " fk " -> " tk " is not a legal CQRS connection.")}))

      (and (pos? i) fr (not (contains? tos fr)))
      (conj (finding :flow/discontinuous :warning
                     {:area area :flow flow-key :step i :ref fr
                      :message (str "Flow " flow-key " step " i " :from " fr " has no producing prior step.")})))))

(defn- flow-findings [model live area flow-key flow]
  (let [steps (:steps flow)
        tos (into #{} (keep #(endpoint-ref (:to %)) steps))]
    (mapcat (fn [i step] (step-findings model live area flow-key tos i step))
            (range) steps)))

(defn- check-flows [model live]
  (for [[area area-map] model
        [flow-key flow] (:flows area-map)
        f (flow-findings model live area flow-key flow)]
    f))

(defn- check-existence [model live]
  (for [[field kind] [[:commands :command] [:events :event] [:read-models :read-model]
                      [:queries :query] [:todo-processors :todo-processor] [:periodic-tasks :periodic-task]]
        nm (spec-names model kind)
        :when (not (live-has? live nm kind))]
    (finding :block/undeclared :error
             {:kind kind :block nm
              :message (str (name kind) " " nm " is declared in the spec but absent from the live runtime.")})))

(defn- check-coverage [model live]
  (let [areas (spec-area-names model)]
    (concat
     (for [kind [:command :read-model :query :todo-processor :periodic-task]
           nm (get live kind)
           :when (and (in-spec-area? areas nm) (not (spec-has? model nm kind)))]
       (finding :block/uncovered :warning
                {:kind kind :block nm
                 :message (str (name kind) " " nm " is present in the live runtime but missing from the spec.")}))
     (for [nm (:consumed live)
           :when (and (in-spec-area? areas nm) (not (spec-has? model nm :event)))]
       (finding :block/uncovered :warning
                {:kind :event :block nm
                 :message (str "event " nm " is consumed live but missing from the spec.")})))))

(defn- check-missing-schemas [model live]
  (let [areas (spec-area-names model)
        ms (:missing-schemas live)
        in? (partial in-spec-area? areas)]
    (concat
     (for [n (:commands ms) :when (in? n)] (finding :schema/unregistered :warning
                                                    {:kind :command :block n :message (str "Live command " n " has no registered schema.")}))
     (for [n (:queries ms) :when (in? n)] (finding :schema/unregistered :warning
                                                   {:kind :query :block n :message (str "Live query " n " has no registered schema.")}))
     (for [n (:read-model-events ms) :when (in? n)] (finding :schema/unregistered :warning
                                                             {:kind :event :block n :message (str "Consumed event " n " (read-model) has no registered schema.")}))
     (for [n (:processor-topics ms) :when (in? n)] (finding :schema/unregistered :warning
                                                            {:kind :event :block n :message (str "Subscribed event " n " (processor) has no registered schema.")})))))

(defn- check-schema-match [model opts]
  (let [reg (schema-registry)
        sev (if (= :lenient (:schema-match opts)) :warning :error)]
    (for [[area area-map] model
          [field kind] [[:commands :command] [:events :event] [:queries :query]]
          [block-key block] (get area-map field)
          :let [spec-schema (:schema block)]
          :when (and spec-schema (contains? reg block-key))
          :let [sf (schema-form spec-schema)
                lf (schema-form (get reg block-key))]
          :when (and (not= sf ::unparseable) (not= lf ::unparseable) (not= sf lf))]
      (finding :schema/mismatch sev
               {:area area :kind kind :block block-key :spec/schema sf :live/schema lf
                :message (str "Spec schema for " block-key " differs from the live registered schema.")}))))

(defn- check-wiring [model live]
  (concat
   (for [[area area-map] model
         [block-key block] (:read-models area-map)
         :when (live-has? live block-key :read-model)
         :let [spec-c (set (:consumes block)) live-c (get (:rm-consumes live) block-key #{})]
         :when (not= spec-c live-c)]
     (finding :wiring/mismatch :warning
              {:area area :kind :read-model :block block-key
               :missing-in-spec (set/difference live-c spec-c) :missing-in-live (set/difference spec-c live-c)
               :message (str "Read-model " block-key " :consumes differs from the live subscribed events.")}))
   (for [[area area-map] model
         [block-key block] (:todo-processors area-map)
         :when (live-has? live block-key :todo-processor)
         :let [spec-s (set (:subscribes block)) live-s (get (:tp-subscribes live) block-key #{})]
         :when (not= spec-s live-s)]
     (finding :wiring/mismatch :warning
              {:area area :kind :todo-processor :block block-key
               :missing-in-spec (set/difference live-s spec-s) :missing-in-live (set/difference spec-s live-s)
               :message (str "Todo-processor " block-key " :subscribes differs from the live topics.")}))
   (for [[area area-map] model
         [block-key block] (:periodic-tasks area-map)
         :when (live-has? live block-key :periodic-task)
         :let [spec-sched (:schedule block) live-sched (get (:periodic-schedule live) block-key)]
         :when (not= spec-sched live-sched)]
     (finding :wiring/mismatch :warning
              {:area area :kind :periodic-task :block block-key
               :spec/schedule spec-sched :live/schedule live-sched
               :message (str "Periodic-task " block-key " :schedule differs from the live schedule.")}))))

;; Confirm production/read edges against opt-in def-site declarations. Only fires
;; when the LIVE block declares the edge (otherwise the edge stays asserted-only,
;; checked for endpoint existence + grammar by check-block-refs). A match is a
;; confirmation; a difference is reported both ways like check-wiring.
(defn- check-production [model live]
  (concat
   (for [[area area-map] model
         [field kind] [[:commands :command] [:todo-processors :todo-processor] [:periodic-tasks :periodic-task]]
         [block-key block] (get area-map field)
         :let [live-p (get (:declared-produces live) [kind block-key])]
         :when live-p
         :let [spec-p (set (:produces block))]
         :when (not= spec-p live-p)]
     (finding :produces/mismatch :warning
              {:area area :kind kind :block block-key
               :missing-in-spec (set/difference live-p spec-p)
               :missing-in-live (set/difference spec-p live-p)
               :message (str block-key " :produces differs from the runtime-declared production edges.")}))
   (for [[area area-map] model
         [field kind] [[:commands :command] [:queries :query]]
         [block-key block] (get area-map field)
         :let [live-r (get (:declared-reads live) [kind block-key])]
         :when live-r
         :let [spec-r (set (:reads block))]
         :when (not= spec-r live-r)]
     (finding :reads/mismatch :warning
              {:area area :kind kind :block block-key
               :missing-in-spec (set/difference live-r spec-r)
               :missing-in-live (set/difference spec-r live-r)
               :message (str block-key " :reads differs from the runtime-declared read-model dependencies.")}))))

(defn- check-auth [model live]
  (let [areas (spec-area-names model)]
    (for [[nm authed?] (:auth live) :when (and (not authed?) (in-spec-area? areas nm))]
      (finding :auth/missing :warning
               {:block nm :message (str "Live command/query " nm " has no :authorized? predicate.")}))))

(defn- design-notices [model present?]
  (let [has-design? (some (fn [[_ am]] (or (seq (:screens am)) (seq (:flows am)))) model)]
    (concat
     (when-not present?
       [(finding :runtime/absent :info {:message "No grain registries are loaded; ran spec-internal checks only."})])
     (when has-design?
       [(finding :runtime/design-only :info {:message "Screens and flows are design-time only; not verified against the runtime."})]))))

(defn- total-verdict [findings model present? opts]
  (let [error-sevs (:error-severities opts #{:error})
        fs (sort-by (juxt (comp severity-rank :severity) (comp str :type) (comp str :area) (comp str :block)) findings)]
    (sanitize
     {:valid? (not (some #(contains? error-sevs (:severity %)) fs))
      :summary {:findings (count fs)
                :errors (count (filter (comp #{:error} :severity) fs))
                :warnings (count (filter (comp #{:warning} :severity) fs))
                :info (count (filter (comp #{:info} :severity) fs))
                :by-type (frequencies (map :type fs))
                :areas (when (map? model) (vec (keys model)))
                :runtime/registries-present? present?}
      :findings (vec fs)})))

(defn validate-event-model
  ([model] (validate-event-model model {}))
  ([model opts]
   (try
     (if-let [ex (m/explain :event-model model)]
       (total-verdict [(finding :model/malformed :error
                                {:message "Spec does not conform to the :event-model schema."
                                 :explain/humanized (me/humanize ex)})]
                      model false opts)
       (let [cat (try (catalog) (catch Exception _ nil))
             live (when cat (live-model cat))
             present? (boolean (and live (registries-present? live)))
             fs (concat
                 (check-namespacing model)
                 (check-schema-wellformed model)
                 (check-block-refs model live)
                 (check-flows model live)
                 (when present? (check-existence model live))
                 (when present? (check-coverage model live))
                 (when present? (check-missing-schemas model live))
                 (when present? (check-schema-match model opts))
                 (when present? (check-wiring model live))
                 (when present? (check-production model live))
                 (when present? (check-auth model live))
                 (design-notices model present?))]
         (total-verdict (remove nil? fs) model present? opts)))
     (catch Exception e
       (total-verdict [(finding :validator/error :error
                                {:error/message (.getMessage e) :error/class (.getName (class e))})]
                      model false opts)))))

(defn validate-event-model-file
  ([path] (validate-event-model-file path {}))
  ([path opts] (validate-event-model (edn/read-string (slurp (io/file path))) opts)))

(defn validate-event-model-var
  ([sym] (validate-event-model-var sym {}))
  ([sym opts] (validate-event-model @(requiring-resolve sym) opts)))

(defn event-model-coverage [model]
  (let [cat (try (catalog) (catch Exception _ nil))
        live (when cat (live-model cat))
        areas (spec-area-names model)]
    (sanitize
     {:undeclared (into {} (for [k [:command :event :read-model :query :todo-processor :periodic-task]]
                             [k (set (remove #(live-has? live % k) (spec-names model k)))]))
      :uncovered (into {} (for [k [:command :read-model :query :todo-processor :periodic-task]]
                            [k (set (filter #(and (in-spec-area? areas %) (not (spec-has? model % k)))
                                            (get live k)))]))
      :runtime/registries-present? (boolean (and live (registries-present? live)))})))

;; ===========================================================================
;; REPL-served authoritative instructions ("guides")
;; ===========================================================================
;;
;; Served from the live JVM so they always match the loaded code. Concept guides
;; are authored and registered (see the guides ns); block and tool guides are
;; assembled live from var docstrings, the live schema registry, and — for blocks
;; — an optionally supplied event-model spec.

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
      (merge {:id (keyword (name id)) :applies-to :tool} (source-info v)))))

(defn- catalog-block [cat id]
  (some (fn [k] (get-in cat [k id]))
        [:commands :queries :read-models :processors :periodic-triggers]))

(defn- block-in-catalog? [id]
  (boolean (when-let [cat (try (catalog) (catch Exception _ nil))] (catalog-block cat id))))

(defn- spec-block [model id]
  (some (fn [kind] (when-let [b (get (spec-kind-map model kind) id)] (assoc b :kind kind)))
        all-kinds))

(defn- block-usage-card [id opts]
  (let [cat (try (catalog) (catch Exception _ nil))
        entry (when cat (catalog-block cat id))
        sb (when-let [m (:spec opts)] (spec-block m id))]
    (cond-> {:id id :applies-to :block}
      entry (merge (select-keys entry [:kind :schema :source :authorized?/present?
                                       :events/consumes :schedule]))
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
    (sanitize {:guides (vec (concat concept blocks tools))})))

(defn guide
  ([id] (guide id {}))
  ([id opts]
   (try
     (cond
       (contains? @guide-registry* id) (sanitize (get @guide-registry* id))
       (and (qualified-keyword? id) (block-in-catalog? id)) (sanitize (block-usage-card id opts))
       (tool-guide id) (sanitize (tool-guide id))
       :else {:guide/unknown id :did-you-mean (vec (sort-by str (keys @guide-registry*)))})
     (catch Exception e
       {:guide/error (.getMessage e) :id id}))))
