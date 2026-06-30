(ns ai.obney.grain.event-model-validator.core
  "Pure, shippable structural validator + boot-guard for the service-area-first
   event model. Reconciles an EDN event model against the LIVE grain registries
   (commands/queries/read-models/processors/periodic-triggers + schemas) read
   directly from the building-block components. No `install!`, no event-store, no
   dev-only surface, never throws (except the explicit boot-guard `verify-or-throw!`).

   Extracted out of the dev-only code-agent-tools so the boot-guard can run in
   production; code-agent-tools now re-exports from here."
  (:require [ai.obney.grain.command-processor-v2.interface :as cp]
            [ai.obney.grain.event-model.interface :as event-model]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.query-processor.interface :as qp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.schema-util.interface :as schema-util]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]))

;; ---- sanitization (inert EDN over nREPL / in verdicts) --------------------

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

(defn sanitize-map [m] (into {} (map (fn [[k v]] [k (sanitize v)])) m))

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

;; ---- live catalog (read directly from the building-block registries) ------

(defn schema-registry [] @schema-util/registry*)

(defn schema-summary
  [schema-reg schema-name]
  (cond-> {:name schema-name :present? (contains? schema-reg schema-name)}
    (contains? schema-reg schema-name) (assoc :schema (sanitize (get schema-reg schema-name)))))

(defn schema-summaries
  [schema-reg schema-names]
  (into {} (map (fn [n] [n (schema-summary schema-reg n)])) schema-names))

(defn registry-entry
  [kind schema-reg [entry-name {:keys [handler-fn reducer-fn] :as opts}]]
  (let [f (or handler-fn reducer-fn)
        public-opts (dissoc opts :handler-fn :reducer-fn)]
    (cond-> {:kind kind
             :name entry-name
             :schema (schema-summary schema-reg entry-name)
             :opts (sanitize public-opts)
             :instructions {:guide entry-name}}
      f (assoc :source (source-info f))
      (contains? opts :authorized?) (assoc :authorized?/present? true)
      (not (contains? opts :authorized?)) (assoc :authorized?/present? false)
      (:topics opts) (assoc :events/consumes (:topics opts)
                            :events/schemas (schema-summaries schema-reg (:topics opts)))
      (:events opts) (assoc :events/consumes (:events opts)
                            :events/schemas (schema-summaries schema-reg (:events opts)))
      (:schedule opts) (assoc :schedule (:schedule opts))
      (:grain.event-model/produces opts) (assoc :produces (:grain.event-model/produces opts))
      (:grain.event-model/reads opts) (assoc :reads (:grain.event-model/reads opts)))))

(defn schema-entry [[schema-name schema]] {:kind :schema :name schema-name :schema (sanitize schema)})

(defn schemas [] (into {} (map (fn [[k v]] [k (sanitize v)])) (schema-registry)))
(defn schema-names [] (set (keys (schema-registry))))
(defn missing-schema-names [names] (set/difference (set names) (schema-names)))

(defn catalog
  "A sanitized EDN view of the live grain registries: commands/queries/read-models/
   processors/periodic-triggers/schemas + missing-schema diagnostics."
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

(defn catalog-block
  "Find a live block named `id` across the catalog kind-maps (the first kind that has it)."
  [cat id]
  (some (fn [k] (get-in cat [k id])) [:commands :queries :read-models :processors :periodic-triggers]))

;; ===========================================================================
;; Structural validation
;; ===========================================================================

(def ^:private connection-grammar
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
  {:command        {:reads #{:read-model} :produces #{:event}}
   :query          {:reads #{:read-model}}
   :read-model     {:consumes #{:event}}
   :todo-processor {:subscribes #{:event} :produces #{:command}}
   :periodic-task  {:produces #{:event :command}}
   :screen         {:queries #{:query} :commands #{:command}}})

(def ^:private severity-rank {:error 0 :warning 1 :info 2})

(defn- finding [type severity m] (merge {:type type :severity severity} m))

;; ---- spec accessors -------------------------------------------------------

(defn- spec-kind-map [model kind]
  (reduce (fn [acc [_ area-map]] (merge acc (get area-map (kind->area-field kind)))) {} model))

(defn- spec-names [model kind] (set (keys (spec-kind-map model kind))))
(defn- spec-has? [model nm kind] (contains? (spec-names model kind) nm))
(defn- spec-area-names [model] (set (map name (keys model))))
(defn- in-spec-area? [areas nm] (contains? areas (namespace nm)))

(defn spec-block
  "Find a block named `id` in `model` across kinds → the block map with :kind, or nil."
  [model id]
  (some (fn [kind] (when-let [b (get (spec-kind-map model kind) id)] (assoc b :kind kind))) all-kinds))

;; ---- live snapshot --------------------------------------------------------

(defn- live-model [cat]
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

(defn- endpoint-kind [ep] (when (vector? ep) (first ep)))
(defn- endpoint-ref [ep] (when (vector? ep) (second ep)))

(defn- classify-schema-error [e]
  (let [d (ex-data e)]
    (if (and (= :malli.core/invalid-schema (:type d)) (qualified-keyword? (:schema d)))
      :schema/unresolved-ref :schema/malformed)))

(defn- schema-form [s] (try (m/form (m/schema s)) (catch Exception _ ::unparseable)))

;; ---- checks ---------------------------------------------------------------

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
               {:area area :kind kind :block block-key :edge edge-field :ref ref :expected-kinds expected-kinds
                :message (str block-key " " edge-field " references " ref
                              ", which exists but is not " (str/join "/" (map name expected-kinds)) ".")})
      (finding :ref/dangling :error
               {:area area :kind kind :block block-key :edge edge-field :ref ref :expected-kinds expected-kinds
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
    (mapcat (fn [i step] (step-findings model live area flow-key tos i step)) (range) steps)))

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
  (let [areas (spec-area-names model) ms (:missing-schemas live) in? (partial in-spec-area? areas)]
    (concat
     (for [n (:commands ms) :when (in? n)] (finding :schema/unregistered :warning {:kind :command :block n :message (str "Live command " n " has no registered schema.")}))
     (for [n (:queries ms) :when (in? n)] (finding :schema/unregistered :warning {:kind :query :block n :message (str "Live query " n " has no registered schema.")}))
     (for [n (:read-model-events ms) :when (in? n)] (finding :schema/unregistered :warning {:kind :event :block n :message (str "Consumed event " n " (read-model) has no registered schema.")}))
     (for [n (:processor-topics ms) :when (in? n)] (finding :schema/unregistered :warning {:kind :event :block n :message (str "Subscribed event " n " (processor) has no registered schema.")})))))

(defn- check-schema-match [model opts]
  (let [reg (schema-registry)
        sev (if (= :lenient (:schema-match opts)) :warning :error)]
    (for [[area area-map] model
          [field kind] [[:commands :command] [:events :event] [:queries :query]]
          [block-key block] (get area-map field)
          :let [spec-schema (:schema block)]
          :when (and spec-schema (contains? reg block-key))
          :let [sf (schema-form spec-schema) lf (schema-form (get reg block-key))]
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
               :missing-in-spec (set/difference live-p spec-p) :missing-in-live (set/difference spec-p live-p)
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
               :missing-in-spec (set/difference live-r spec-r) :missing-in-live (set/difference spec-r live-r)
               :message (str block-key " :reads differs from the runtime-declared read-model dependencies.")}))))

(defn- check-auth [model live]
  (let [areas (spec-area-names model)]
    (for [[nm authed?] (:auth live) :when (and (not authed?) (in-spec-area? areas nm))]
      (finding :auth/missing :warning
               {:block nm :message (str "Live command/query " nm " has no :authorized? predicate.")}))))

;; ---- strict-only completeness checks (run only when {:strict true}) --------

(defn- check-produces-required [model live]
  (let [areas (spec-area-names model)]
    (for [kind [:command :todo-processor :periodic-task]
          nm (get live kind)
          :when (and (in-spec-area? areas nm) (not (contains? (:declared-produces live) [kind nm])))]
      (finding :produces/undeclared :error
               {:kind kind :block nm
                :message (str (name kind) " " nm " does not declare :grain.event-model/produces (required in strict mode).")}))))

(defn- check-reads-required [model live]
  (let [areas (spec-area-names model)]
    (for [kind [:command :query]
          nm (get live kind)
          :when (and (in-spec-area? areas nm) (not (contains? (:declared-reads live) [kind nm])))]
      (finding :reads/undeclared :error
               {:kind kind :block nm
                :message (str (name kind) " " nm " does not declare :grain.event-model/reads (required in strict mode).")}))))

(defn- check-gwt-required [model]
  (for [[area area-map] model
        [block-key block] (:commands area-map)
        :when (empty? (:given-when-thens block))]
    (finding :gwt/missing :error
             {:area area :kind :command :block block-key
              :message (str "Command " block-key " has no Given/When/Then examples (required in strict mode).")})))

;; ---- verdict + entrypoints ------------------------------------------------

(defn- design-notices [model present?]
  (let [has-design? (some (fn [[_ am]] (or (seq (:screens am)) (seq (:flows am)))) model)]
    (concat
     (when-not present? [(finding :runtime/absent :info {:message "No grain registries are loaded; ran spec-internal checks only."})])
     (when has-design? [(finding :runtime/design-only :info {:message "Screens and flows are design-time only; not verified against the runtime."})]))))

(defn- total-verdict [findings model present? opts]
  (let [error-sevs (:error-severities opts #{:error})
        fatal-types (:fatal-types opts #{})
        fatal? (fn [f] (or (contains? error-sevs (:severity f)) (contains? fatal-types (:type f))))
        fs (sort-by (juxt (comp severity-rank :severity) (comp str :type) (comp str :area) (comp str :block)) findings)]
    (sanitize
     {:valid? (not (some fatal? fs))
      :summary {:findings (count fs)
                :errors (count (filter (comp #{:error} :severity) fs))
                :warnings (count (filter (comp #{:warning} :severity) fs))
                :info (count (filter (comp #{:info} :severity) fs))
                :fatal (count (filter fatal? fs))
                :by-type (frequencies (map :type fs))
                :areas (when (map? model) (vec (keys model)))
                :strict (boolean (:strict opts))
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
             ;; :structural-only forces spec-internal checks even when grain
             ;; registries ARE loaded — used to distil/validate a model for a
             ;; FOREIGN (non-grain) system without the live-comparison checks
             ;; flagging every block as undeclared.
             present? (boolean (and live (not (:structural-only opts)) (registries-present? live)))
             strict? (boolean (:strict opts))
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
                 (when (and present? strict?) (check-produces-required model live))
                 (when (and present? strict?) (check-reads-required model live))
                 (when strict? (check-gwt-required model))
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
                            [k (set (filter #(and (in-spec-area? areas %) (not (spec-has? model % k))) (get live k)))]))
      :runtime/registries-present? (boolean (and live (registries-present? live)))})))

;; ===========================================================================
;; Boot-guard
;; ===========================================================================

(def strict-defaults
  "Strictest tier: full coverage + declared produces/reads + GWT, and spec↔runtime
   mismatches are fatal. :auth/missing is intentionally NOT fatal (deny-by-default
   is not a model defect); add it to :fatal-types to change that."
  {:strict true
   :fatal-types #{:block/uncovered :wiring/mismatch :produces/mismatch :reads/mismatch}})

(defn verify-event-model!
  "Validate the registered event model (or `(:model opts)`) against the live runtime
   in strict mode. Returns the verdict; does NOT throw. `opts` is merged over
   `strict-defaults`."
  ([] (verify-event-model! {}))
  ([opts]
   (let [model (or (:model opts) (event-model/registered-model))]
     (validate-event-model model (merge strict-defaults (dissoc opts :model))))))

(defn verify-or-throw!
  "Boot-guard: `verify-event-model!` and throw ex-info (with the verdict) when the
   model is invalid/incomplete — so a system refuses to start. Returns the verdict
   on success."
  ([] (verify-or-throw! {}))
  ([opts]
   (let [verdict (verify-event-model! opts)]
     (when-not (:valid? verdict)
       (throw (ex-info "Event model verification failed; refusing to start."
                       {:type :event-model/invalid :verdict verdict})))
     verdict)))
