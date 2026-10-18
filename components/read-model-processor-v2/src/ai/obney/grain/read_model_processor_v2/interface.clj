(ns ai.obney.grain.read-model-processor-v2.interface
  "Read models: pure reducers over a tenant's events, materialized in an LMDB
   cache.

   An ordinary model (no :key or :route) folds all its events into one state;
   read it with project. A keyed model (:key or :route) keeps one state and
   result per instance, with indexes and text searches; read it with value,
   state, values, page or query, and combine reads with read-session.

   Every read takes a context with :event-store, :cache and :tenant-id and
   first catches the model up to the event store head (an ordinary model with
   :l1-ttl-ms may skip that check). Keyed reads accept an event scope through
   scoped-context.

   The guide, with examples and operational notes, is
   components/read-model-processor-v2/README.md. The behavioural contract is
   read-model-processor-v2.allium in the same directory."
  (:require [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.core.keyed :as-alias keyed]
            [malli.core :as m]))

(defn p
  "Project an unregistered ordinary reducer: fold the events matching :query
   through :f, caching state in L1 and the LMDB :cache. project calls this for
   registered ordinary models.

   context: :event-store, :cache, :tenant-id (all required).

   args:
     :f              (state, event) -> state, starting from {} (required)
     :query          event store query map or vector of maps, without
                     :tenant-id; the context's tenant is added (required)
     :name, :version cache identity; change :version to rebuild (required)
     :scope          extra cache identity, hashed into the key
     :partition-fn   (entity-state -> partition key), with :entity-id-fn
     :entity-id-fn   (event -> key of the entity in the state map)
     :partition-key  read only this partition
     :l1-ttl-ms      serve L1 without checking the event store for this
                     many ms (default 0: always check)
     :l1-max-entries L1 size limit (default 10000)"
  [{:keys [_event-store _cache _tenant-id] :as context}
   {:keys [_f _query _name _version _scope _partition-fn _entity-id-fn _partition-key] :as args}]
  (core/p context args))

(def read-model-registry*
  "Global registry atom: qualified model name -> the declaration's options
   plus :reducer-fn and, for defreadmodel with a docstring, :definition/*
   metadata."
  (atom {}))

(defn- validate-registration! [rm-name opts]
  (core/keyed-validate! rm-name opts)
  (when-let [schema (:schema opts)]
    (try
      (m/schema schema)
      (catch Exception cause
        (throw (ex-info "Read model state schema is malformed"
                        {:read-model/name rm-name :schema schema}
                        cause))))))

(defn register-read-model!
  "Validate opts and register reducer-fn under rm-name (a qualified keyword),
   replacing any existing registration. Takes the same options as
   defreadmodel."
  [rm-name reducer-fn opts]
  (validate-registration! rm-name opts)
  (swap! read-model-registry* assoc rm-name (merge {:reducer-fn reducer-fn} opts)))

(defn- still-interned? [^clojure.lang.Var v]
  (when-let [declaring-ns (find-ns (ns-name (.ns v)))]
    (identical? v (.findInternedVar ^clojure.lang.Namespace declaring-ns (.sym v)))))

(defn- conflicting-declaration? [existing reducer-fn definition]
  (let [existing-fn (:reducer-fn existing)]
    (and definition (:definition/value existing)
         (if (and (var? existing-fn) (var? reducer-fn)
                  (.sym ^clojure.lang.Var existing-fn) (.sym ^clojure.lang.Var reducer-fn))
           (and (not= (symbol existing-fn) (symbol reducer-fn))
                (still-interned? existing-fn))
           (not= (:definition/value definition) (:definition/value existing))))))

(defn ^:no-doc register-declared! [rm-name reducer-fn opts definition]
  (validate-registration! rm-name opts)
  (swap! read-model-registry*
         (fn [registry]
           (when-let [existing (get registry rm-name)]
             (when (conflicting-declaration? existing reducer-fn definition)
               (throw (ex-info "Conflicting read model definition"
                               {:read-model/name rm-name :existing existing
                                :candidate definition}))))
           (assoc registry rm-name
                  (merge {:reducer-fn reducer-fn} opts definition)))))

(defn global-read-model-registry
  "Return the current registry map of model name -> declaration."
  []
  @read-model-registry*)

(defmacro defreadmodel
  "Define a reducer var named <ns-kw>-<name> and register it as the read model
   :<ns-kw>/<name>.

   Without :key or :route the model is ordinary: one state, starting from {},
   read with project. Options: :events, :version (default 1), :schema (Malli
   syntax checked at registration; states are not validated), :partition-fn,
   :entity-id-fn, :l1-ttl-ms.

   With exactly one of :key or :route the model is keyed: one state and result
   per instance, read with value, state, values, page or query.
     :key              event -> instance ID
     :route            event -> [[ID input] ...]; the reducer gets the input
     :initial          starting state of each instance (a value, default {})
     :result           state -> result (default identity)
     :include?         result -> truthy; excluded results are not readable
     :indexes          {name (result -> sort value)}
     :searches         {name (result -> string or nil)}
     :filtered-indexes set of index names with membership data (experimental)
     :state-schema     Malli schema checked after every reducer call
     :result-schema    Malli schema checked before a result is stored

       (defreadmodel :crm customers
         {:events #{:crm/customer-registered} :version 1
          :key :customer-id :indexes {:name (order-key [:last-name :first-name])}}
         \"Optional docstring.\"
         [customer event]
         (merge customer (select-keys event [:customer-id :first-name :last-name])))

   Unknown options, and options of the other kind of model, throw
   :grain.read-model/invalid. Change :version whenever what the model stores
   changes.

   With a docstring, the declaration belongs to the var it defines.
   Re-evaluating or reloading the form always succeeds; the same name declared
   from another namespace throws \"Conflicting read model definition\" while the
   original var is still interned."
  {:arglists '([ns-kw name opts? docstring? [state event] & body])}
  [ns-kw fn-name & args]
  (let [[opts args] (if (map? (first args))
                      [(first args) (rest args)]
                      [{} args])
        [docstring args body] (if (string? (first args))
                                [(first args) (second args) (drop 2 args)]
                                [nil (first args) (rest args)])
        rm-name (keyword (name ns-kw) (name fn-name))
        var-name (symbol (str (name ns-kw) "-" (name fn-name)))
        definition (when docstring
                     {:definition/description docstring
                      :definition/source {:ns (str *ns*) :file *file*
                                          :line (:line (meta &form))}
                      :definition/value
                      {:description docstring
                       :options (select-keys opts #{:events :version :schema
                                                    :l1-ttl-ms :key :route :initial :result :include?
                                                    :indexes :searches :filtered-indexes :state-schema :result-schema})}})]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-declared! ~rm-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))

(defn project
  "Return the current state of a registered ordinary model. Throws for keyed
   models.

     (project context :example/counters)
     (project context :example/counters scope)

   scope is one of:
     {:tags #{[tag id] ...}}  the model's events carrying these tags
     {:queries [...]}         this event query instead of the model's types
     {:partition-key pk}      one partition of a partitioned model

   Each scope is cached separately. A partitioned model read without
   :partition-key returns all partitions merged."
  ([context rm-name] (project context rm-name nil))
  ([context rm-name scope]
   (let [entry (get @read-model-registry* rm-name)
         _ (when (core/keyed? entry)
             (throw (ex-info "Use value, state, values or page for a keyed fold" {:model rm-name})))
         query (cond
                 (:queries scope) (:queries scope)
                 (:tags scope)    {:types (:events entry) :tags (:tags scope)}
                 :else            {:types (:events entry)})]
     (p context (cond-> {:f       (:reducer-fn entry)
                         :query   query
                         :name    rm-name
                         :version (:version entry 1)
                         :scope   scope}
                  (:partition-fn entry)  (assoc :partition-fn (:partition-fn entry))
                  (:entity-id-fn entry) (assoc :entity-id-fn (:entity-id-fn entry))
                  (:partition-key scope) (assoc :partition-key (:partition-key scope))
                  (:l1-ttl-ms entry)    (assoc :l1-ttl-ms (:l1-ttl-ms entry)))))))

(defn scoped-context
  "Return context with an event scope: keyed reads through it fold only the
   selected events.

   scope is {:tags #{[tag UUID] ...}} (events carrying all the tags) or
   {:queries [{:tags ... :types ...} ...]} (a union of queries; :types is
   intersected with each model's :events). Empty selectors select nothing;
   nil removes the scope. Each scope has its own state, results, indexes,
   counts and progress. Index selections filter after folding. Throws
   :grain.read-model/invalid-scope for a malformed scope.

     (values (scoped-context context {:tags #{[:user user-id]}})
             :enrollment/applications {:index :user :equals user-id})"
  [context scope]
  (core/keyed-scoped-context context scope))

(defn- keyed-definition [context name]
  (let [definition (or (core/keyed-session-definition context name)
                       (get @read-model-registry* name))]
    (when-not (core/keyed? definition)
      (throw (ex-info "Expected a registered keyed fold" {:model name})))
    definition))

(defn value
  "Return the result of instance key in keyed model name, or not-found
   (default nil) when the instance is absent or excluded by :include?. Pass a
   not-found to tell those apart from a stored nil result."
  ([context name key] (value context name key nil))
  ([context name key not-found]
   (core/keyed-point context name (keyed-definition context name) key :value not-found)))

(defn state
  "Return the reducer state of instance key in keyed model name, or not-found
   (default nil). Excluded instances still have state."
  ([context name key] (state context name key nil))
  ([context name key not-found]
   (core/keyed-point context name (keyed-definition context name) key :state not-found)))

(defn values
  "Return a reducible of {:key id :value result} for included instances, in
   index order. Selection: :index (default :identity), :direction (:asc or
   :desc), and at most one of :equals or a vector :prefix (complete leading
   components of vector index values). Reducing it catches up and reads one
   snapshot; inside a read-session, reduce it before the session returns."
  ([context name] (values context name {}))
  ([context name selection]
   (core/keyed-values context name (keyed-definition context name) selection)))

(defn page
  "Return one page of included instances in index order.

   Options: :index (default :identity), :direction (:asc or :desc), at most one
   of :equals, :prefix (a vector) or :prefixes (a vector of vectors, paged as
   their union in index order; [] selects nothing), :page (default 1, clamped
   to the last page) and :limit (default 25).

   Returns :entries [{:key id :value result} ...], :total, :total-pages, :page,
   :page-size, :progress, and the counters :rows-examined, :results-decoded and
   :rank-entries-examined. Counting and skipping pages use stored ranks and do
   not read the skipped entries."
  [context name options]
  (core/keyed-page context name (keyed-definition context name) options))

(defn index-groups
  "Return [{:value v :total n} ...] for the distinct values of index, in
   ascending order. With values, return only those groups, in the order given,
   skipping duplicates and empty groups. Reads stored counts (for :identity, the
   identity index) and never loads results."
  ([context name index]
   (core/keyed-index-groups context name (keyed-definition context name) index))
  ([context name index values]
   (when (nil? values) (throw (ex-info "Expected group values, not nil" {:model name})))
   (core/keyed-index-groups context name (keyed-definition context name) index values)))

(defn search-keys
  "Return the IDs of included instances whose :searches text matches :query, a
   case-insensitive literal string. Options: :index (a search name), :query,
   :mode (:contains, the default, needs at least 3 characters; :prefix and
   :suffix need 1). Shorter queries throw :grain.read-model/short-search. Does
   not load results."
  [context name options]
  (core/keyed-search-keys context name (keyed-definition context name) options))

(defn key-page
  "Sort candidate IDs (keys, or every included instance in the three-argument
   form) by a stored index value and return one page of them.

   Options: :index (default :identity), :direction (default :asc), :page
   (default 1), :limit (default 25), and optional callbacks
   :sort-value (fn [id index-value] -> sort value) and
   :include?   (fn [id index-value] -> truthy).
   With :indexes [names ...] the callbacks receive {name stored-value} instead.

   Duplicate, absent and excluded candidates are ignored. Returns :keys,
   :total, :total-pages, :page, :page-size and :sort-keys-read. Reads the index
   data of every candidate, but no results. Ties are ascending by string ID."
  ([context name options]
   (core/keyed-key-page context name (keyed-definition context name) ::keyed/all options))
  ([context name keys options]
   (core/keyed-key-page context name (keyed-definition context name) keys options)))

(defn filtered-key-page
  "Experimental. Return one page of candidate IDs (keys) in the stored order
   of an index listed in the model's :filtered-indexes, with the same results
   as key-page without callbacks. Options: :index (default :identity),
   :direction, :page, :limit. Skips whole stored spans instead of sorting
   candidates; resolving the candidates still costs one lookup each."
  [context name keys options]
  (core/keyed-filtered-key-page context name (keyed-definition context name) keys options))

(defn joined-page
  "Experimental. Page the included IDs of membership-model in the order of an
   index of ordering-model listed in its :filtered-indexes. IDs without an
   ordering result sort as :missing-value (required). Options: :index,
   :direction, :page, :limit, :missing-value, and :keys to restrict the
   candidates. Reads both models in one snapshot. Without :keys it visits every
   membership ID; no results are loaded and nothing is stored."
  [context membership-model ordering-model options]
  (core/keyed-joined-page context membership-model (keyed-definition context membership-model)
                          ordering-model (keyed-definition context ordering-model) options))

(defn index-keys
  "Return the IDs whose index value equals :equals, ascending by string ID.
   Options: :index, :equals (required), zero-based :offset (default 0), :limit
   (default 25). :direction is accepted but does not change the order. An
   offset past the end returns []. Does not load results."
  [context name options]
  (core/keyed-index-keys context name (keyed-definition context name) options))

(defn index-values
  "Return a reducible of {:key id :value {index stored-value}} for the index
   names in the vector indexes, for every included instance or, in the
   four-argument form, for ids (duplicates and absent IDs are skipped). Does not
   load results. Inside a read-session, reduce it before the session returns."
  ([context name indexes]
   (core/keyed-index-values context name (keyed-definition context name) indexes nil))
  ([context name indexes ids]
   (when (nil? ids) (throw (ex-info "Expected instance IDs, not nil" {:model name})))
   (core/keyed-index-values context name (keyed-definition context name) indexes ids)))

(defn index-rank
  "Return {:rank n :present? bool}: the zero-based position of id within the
   :equals group of :index (ascending by string ID), or where it would be
   inserted when absent. Selection: :index, :equals (required); :direction is
   accepted but ignored."
  [context name selection id]
  (core/keyed-index-rank context name (keyed-definition context name) selection id))

(defn progress
  "Catch up keyed model name and return its progress, {:watermark event-id
   :checked-through event-id}: the last event folded and the latest head it
   was checked against."
  [context name]
  (core/keyed-progress context name (keyed-definition context name)))

(defn read-session
  "Catch up the named keyed models, then call f with a session context. Reads
   through the session context see one cache snapshot and must finish, with
   reducibles consumed, before f returns. Only the named models can be read.

   The two-argument form takes [[context model-names] ...], for example several
   scopes or tenants; all contexts must share one cache and event store, and f
   receives a vector of session contexts in the same order. Sessions cannot be
   nested."
  ([context model-names f]
   (read-session [[context model-names]] (fn [[prepared]] (f prepared))))
  ([requests f]
   (core/keyed-read-session requests @read-model-registry* f)))

(defn order-key
  "Return an index extractor over result fields that query can match to an
   order. Strings are lowercased (Locale/ROOT), nil becomes \"\", keywords
   become their name. One field gives a scalar, several give a vector."
  [fields] (core/query-order-key fields))

(defn search-text
  "Return a search extractor that joins result fields with spaces, recording
   the fields so query can match it to a :search declaration."
  [fields] (core/query-search-text fields))

(defn query-models
  "Validate a query definition, including its index and search requirements,
   and return {model version} for every model it reads: the value for a query
   endpoint's :grain/read-models."
  [definition]
  (core/query-subscriptions (core/query-compile-definition definition @read-model-registry*)))

(defn query
  "Run a query definition {:from {alias model} :related {...} :search [...]
   :orders {...}} and return one page.

   request: :order (required), :search, :direction, :page, :limit, :params.
   Returns :rows [{:key root-id alias result ...} ...], :total, :total-pages,
   :page, :page-size and :work (the strategy used and what it read). A
   non-empty search shorter than 3 characters returns no rows and a
   :search-error. Definitions the planner cannot serve from indexes throw
   :grain.read-query/unsupported; [:call ...] orders evaluate every candidate.
   See the component README for the definition syntax."
  [context definition request]
  (let [registry @read-model-registry*
        plan (core/query-compile-definition definition registry)
        api {:value #'value :values #'values :page #'page
             :index-groups #'index-groups :index-keys #'index-keys :index-rank #'index-rank
             :search-keys #'search-keys :key-page #'key-page
             :filtered-key-page #'filtered-key-page :joined-page #'joined-page
             :read-session (fn [ctx names f]
                             (core/keyed-read-session [[ctx names]] registry (fn [[prepared]] (f prepared))))
             :same-members? (fn [ctx a b]
                              (or (= a b) (core/keyed-same-members? ctx a (get registry a) b (get registry b))))}]
    (core/query-execute api context plan request)))

(defn l1-clear!
  "Empty the in-process L1 cache used by ordinary models."
  []
  (core/l1-clear!))

(defn l1-stats
  "Return {:entries n} for the in-process L1 cache used by ordinary models."
  []
  (core/l1-stats))
