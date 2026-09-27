(ns ai.obney.grain.read-model-processor-v2.interface
  "Pure event folds with legacy projections and opt-in keyed materialization.

   Add :key (event -> instance-id) to a declaration for independent keyed folds.
   Optional :initial defaults to {}, and :result (state -> value) defaults to
   identity. Grain atomically persists affected state, results, indexes and
   progress. Read with value, state, values or page; compose in read-session.
   Keyed context requires :event-source, :tenant-id, :event-store and :cache.
   Tenant identity always comes from context; declarations have no tenant key.

   Optional :include? (result -> truthy) controls result membership. Excluded
   instances retain state, but have no readable result, index entries or count.
   Omission includes every result, including nil. Membership changes commit with
   state and progress; no application storage mutations are needed.

   :indexes maps index names to pure result -> sort-value functions. Supported
   sort values are nil, booleans, strings, signed 64-bit integers, UUIDs, keywords,
   and tuples (vectors) of these. Keys must fit the backend's 511-byte encoding
   limit. Change the model version when changing routing, initial state, reducer,
   result, inclusion, schemas or indexes. :state-schema and :result-schema are optional.

   The caching and partitioning options below describe legacy project calls.
   Keyed folds do not use L1/TTL caching or legacy scopes.

   Read models are pure reducer functions `(state, event) -> state` that project
   event streams into queryable state. This processor handles caching, incremental
   updates via watermarks, partitioning for large datasets, and an in-process L1
   cache for sub-millisecond repeated reads.

   ## Quick Start

   Define a read model with `defreadmodel` and project it with `project`:

       (defreadmodel :example counters
         {:events #{:example/counter-created :example/counter-incremented}
          :version 1}
         [state event]
         (case (:event/type event)
           :example/counter-created
           (assoc state (:counter-id event) {:id (:counter-id event) :value 0})
           :example/counter-incremented
           (update-in state [(:counter-id event) :value] inc)
           state))

       (rmp/project context :example/counters)

   ## Two-Tier Caching

   **L1 (in-process):** Deserialized Clojure maps held in a global atom. Zero
   serialization cost on read. LRU-evicted by entry count (default 10K entries).
   TTL-based revalidation: within the TTL window, returns instantly with no I/O.
   After TTL expiry, checks the event store for new events — if none, revalidates
   the entry; if new events exist, applies them incrementally.

   **L2 (LMDB):** Fressian-serialized state on disk. Survives process restarts.
   Three storage strategies selected automatically:

   - **Monolithic**: Single cache entry for small state (< 10K keys).
   - **Segmented**: 64 hash-based segments for large state (>= 10K keys).
     Only changed segments are written back.
   - **Partitioned**: Per-partition cache entries with a global manifest.
     Requires `:partition-fn` and `:entity-id-fn` in opts.

   Cache keys include the tenant-id, ensuring strict multi-tenant isolation.
   L2 write-back is deferred until >= 10 new events to reduce I/O.

   ### L1 TTL Configuration

   By default, L1 TTL is 0 (always checks the event store for freshness).
   Set `:l1-ttl-ms` in `defreadmodel` opts to allow stale reads within a window:

       (defreadmodel :example counters
         {:events #{...} :version 1 :l1-ttl-ms 1000}  ; 1s TTL
         [state event] ...)

   With TTL > 0, rapid interactions (pagination, filter changes) skip all I/O
   and return from L1 in < 0.2ms. Use TTL=0 for real-time consistency.

   ## Partitioned Read Models

   For datasets that naturally partition (e.g., appointments by location+date),
   supply `:partition-fn` and `:entity-id-fn` in the opts map:

       (defreadmodel :scheduling appointments-by-location-date
         {:events       #{:scheduling/appointment-booked ...}
          :version      1
          :partition-fn  (fn [entity] [(:location-id entity)
                                       (subs (:start-at entity) 0 10)])
          :entity-id-fn :appointment-id}
         [state event]
         ...)

   - `partition-fn` — `(entity -> partition-key)`. Operates on entity *state*,
     not events. Determines which partition an entity belongs to.
   - `entity-id-fn` — `(event -> entity-id)`. Extracts the entity identifier
     from an event. Used to route events to the correct partition.

   Cross-partition moves are detected automatically: when `partition-fn` returns
   a different key after applying an event, the entity is evicted from the old
   partition and inserted into the new one.

   Read a single partition efficiently:

       (rmp/project context :scheduling/appointments-by-location-date
         {:partition-key [location-id \"2025-06-15\"]})

   Single-partition reads are cached independently in both L1 and L2.

   ## Scoping

   The optional `scope` map on `project` supports three patterns:

   - `{:tags #{[:entity-type id]}}` — Filter events by tag set.
   - `{:queries [...]}` — Override the event query entirely (vector of query specs).
   - `{:partition-key pk}` — Read a single partition (partitioned models only).

   ## Metrics

   All cache tiers emit CloudWatch EMF metrics via mulog:

   - `ReadModelL1Hit` / `ReadModelL1Revalidated` / `ReadModelL1Stale` / `ReadModelL1Miss`
   - `ReadModelL2Hit` / `ReadModelL2Miss`

   Context fields `:read-model/name` and `:read-model/cache-tier` are attached
   to every metric event for observability."
  (:require [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.read-model-processor-v2.keyed :as keyed]
            [ai.obney.grain.read-model-processor-v2.l1-cache :as l1]
            [malli.core :as m]))

(defn p
  "Low-level projection function. Reads events from the store, reduces them
   through `f`, and manages the LMDB cache.

   Prefer `project` for registered read models. Use `p` directly when you need
   full control over the query or reducer.

   `context` keys:
     :event-store — Event store instance (required)
     :cache       — LMDB KV store instance (required)
     :tenant-id   — UUID, included in cache key for tenant isolation (required)

   `args` keys:
     :f             — Reducer fn `(state, event) -> state` (required)
     :query         — Event store query map or vector of query maps (required)
     :name          — Cache key name (required)
     :version       — Cache version, bump to invalidate (required)
     :scope         — Optional scope map (affects cache key hash)
     :partition-fn  — `(entity -> partition-key)` for partitioned projections
     :entity-id-fn  — `(event -> entity-id)` for partitioned projections
     :partition-key — Read a single partition (partitioned models only)
     :l1-ttl-ms     — L1 cache TTL in ms (default 0 = always revalidate)"
  [{:keys [_event-store _cache _tenant-id] :as context}
   {:keys [_f _query _name _version _scope _partition-fn _entity-id-fn _partition-key] :as args}]
  (core/p context args))

;; ---------------------------------------------------------------------------
;; Read Model Registry
;; ---------------------------------------------------------------------------

(def read-model-registry*
  "Global registry atom. Maps qualified keyword to
   `{:reducer-fn f :events #{...} :version n :partition-fn f? :entity-id-fn f? :l1-ttl-ms n?}`."
  (atom {}))

(defn register-read-model!
  "Register a read model reducer and its opts under `rm-name` (qualified keyword).
   Called automatically by `defreadmodel`; use directly for programmatic registration."
  [rm-name reducer-fn opts]
  (keyed/validate! rm-name opts)
  (when-let [schema (:schema opts)]
    (try
      (m/schema schema)
      (catch Exception cause
        (throw (ex-info "Read model state schema is malformed"
                        {:read-model/name rm-name :schema schema}
                        cause)))))
  (swap! read-model-registry* assoc rm-name (merge {:reducer-fn reducer-fn} opts)))

(defn ^:no-doc register-declared! [rm-name reducer-fn opts definition]
  (keyed/validate! rm-name opts)
  (when-let [schema (:schema opts)]
    (try
      (m/schema schema)
      (catch Exception cause
        (throw (ex-info "Read model state schema is malformed"
                        {:read-model/name rm-name :schema schema}
                        cause)))))
  (swap! read-model-registry*
         (fn [registry]
           (when-let [existing (get registry rm-name)]
             (when (and definition (:definition/value existing)
                        (not= (:definition/value definition) (:definition/value existing)))
               (throw (ex-info "Conflicting read model definition"
                               {:read-model/name rm-name :existing existing
                                :candidate definition}))))
           (assoc registry rm-name
                  (merge {:reducer-fn reducer-fn} opts definition)))))

(defn global-read-model-registry
  "Returns the current snapshot of all registered read models."
  []
  @read-model-registry*)

(defmacro defreadmodel
  "Define and register a read model reducer.

   Follows the same pattern as `defcommand` and `defquery`:

       (defreadmodel :ns-kw name
         {:events  #{:ns/event-a :ns/event-b}  ; event types to subscribe to
          :version 1                            ; bump to invalidate cache
          :schema [:map [:count :int]]          ; optional projected-state schema
          ;; Optional — for partitioned read models:
          :partition-fn  (fn [entity] ...)      ; entity -> partition key
          :entity-id-fn  (fn [event] ...)       ; event -> entity id
          ;; Optional — L1 cache TTL:
          :l1-ttl-ms 1000}                      ; ms before revalidating (default 0)
         \"Optional docstring.\"
         [state event]
         ... reducer body ...)

   The reducer receives accumulated state (a map, initially `{}`) and a single event.
   It must return the new state. The function is registered under the qualified keyword
   `:<ns-kw>/<name>` and can be projected via `(project context :<ns-kw>/<name>)`."
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
                                                    :l1-ttl-ms :key :initial :result :include?
                                                    :indexes :state-schema :result-schema})}})]
    `(do
       (defn ~var-name
         ~@(when docstring [docstring])
         ~args
         ~@body)
       (register-declared! ~rm-name (var ~var-name) ~opts ~definition)
       (var ~var-name))))

(defn project
  "Project a registered read model by name, returning the current state map.

   Two arities:
     (project context :example/counters)
     (project context :example/counters scope)

   `scope` is an optional map with one of:
     {:tags #{[:entity-type id]}}   — Filter events by tag set
     {:queries [...]}               — Override event query (vector of query specs)
     {:partition-key pk}            — Read a single partition (partitioned models only)

   For partitioned models, calling without `:partition-key` returns all partitions
   merged into one map. With `:partition-key`, only that partition's state is
   returned — much cheaper for large datasets."
  ([context rm-name] (project context rm-name nil))
  ([context rm-name scope]
   (let [entry (get @read-model-registry* rm-name)
         _ (when (keyed/keyed? entry)
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

;; ---------------------------------------------------------------------------
;; Keyed folds
;; ---------------------------------------------------------------------------

(defn- keyed-definition [context name]
  (let [definition (or (keyed/session-definition context name)
                       (get @read-model-registry* name))]
    (when-not (keyed/keyed? definition)
      (throw (ex-info "Expected a registered keyed fold" {:model name})))
    definition))

(defn value
  "Read a keyed fold's materialized result after catch-up. Supply not-found to
   distinguish an absent or explicitly excluded result from a stored nil.
   Missing defaults to nil. Optional :include? controls result publication while
   retaining accumulated state; omitted means include every result."
  ([context name key] (value context name key nil))
  ([context name key not-found]
   (keyed/point context name (keyed-definition context name) key :value not-found)))

(defn state
  "Read a keyed fold's detached accumulator, not its completion value."
  ([context name key] (state context name key nil))
  ([context name key not-found]
   (keyed/point context name (keyed-definition context name) key :state not-found)))

(defn values
  "Return a reducible of {:key instance-id :value result}. Selection accepts
   :index (default :identity), :direction and :equals. A standalone reduction
   catches up once and owns a snapshot; inside a read-session consume it there."
  ([context name] (values context name {}))
  ([context name selection]
   (keyed/values context name (keyed-definition context name) selection)))

(defn page
  "Read an indexed result page with exact counts and clamping. Returns :entries,
   :total, :total-pages, :page, :page-size, and progress/work counters. Options:
   :index, :direction, :equals, :page (1 by default), :limit (25 by default)."
  [context name options]
  (keyed/page context name (keyed-definition context name) options))

(defn progress
  "Read a keyed model's committed replay progress after catch-up."
  [context name]
  (keyed/progress context name (keyed-definition context name)))

(defn read-session
  "Catch up the named keyed folds once, then invoke f with a scoped context.
   All reads through it share a snapshot and must finish during f. The two-argument
   form accepts explicit [[context model-names] ...] requests sharing one store;
   f receives their prepared contexts in order. This is an application integration
   boundary, not tenant routing in model declarations."
  ([context model-names f]
   (read-session [[context model-names]] (fn [[prepared]] (f prepared))))
  ([requests f]
   (keyed/read-session requests @read-model-registry* f)))

;; ---------------------------------------------------------------------------
;; Legacy L1 cache
;; ---------------------------------------------------------------------------

(defn l1-clear!
  "Clear the L1 in-process cache. Useful for testing or after deployments."
  []
  (l1/clear!))

(defn l1-stats
  "Return L1 cache statistics: {:entries n}"
  []
  (l1/stats))
