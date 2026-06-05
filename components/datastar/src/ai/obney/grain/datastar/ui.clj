(ns ai.obney.grain.datastar.ui
  "IR-first Datastar UI helpers for Grain applications.

   `hiccup` is the main app-facing boundary: it accepts ordinary hiccup plus
   this namespace's Datastar-aware attributes/effects, compiles that source to a
   Datastar-shaped IR, and lowers the IR back to ordinary Datastar hiccup. Final
   HTML serialization still belongs to the existing Datastar adapter and
   hiccup2.

   Server effects generated here use explicit payloads. Datastar signals remain
   client-local UI state unless a `dispatch` or `refresh` explicitly references
   them in its payload. Raw Datastar attributes and raw actions pass through
   unchanged and are intentionally unchecked."
  (:refer-clojure :exclude [num reset!])
  (:require [ai.obney.grain.datastar.core :as core]
            [ai.obney.grain.query-processor.interface :as qp]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [malli.core :as m]))

(def ^:dynamic *signal-scope*
  "Current explicit signal scope. Bound by `with-signal-scope`."
  nil)

(def default-command-post
  "Default Datastar action target used by `dispatch`."
  "$__grainAction")

(defrecord Signal [binding semantic-name resolved-name scope init type])
(defrecord Expr [op args])
(defrecord Effect [op args])
(defrecord Href [query params])

(defn- signal? [x] (instance? Signal x))
(defn- expr? [x] (instance? Expr x))
(defn- effect? [x] (instance? Effect x))
(defn- href? [x] (instance? Href x))

(def ^:dynamic *lower-opts*
  {})

(defn- query-registry
  []
  (or (:query-registry *lower-opts*)
      (qp/global-query-registry)))

(defn- signal-key
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    :else (str k)))

(defn- js-literal
  [value]
  (string/replace
   (json/write-str (cond
                     (keyword? value) (subs (str value) 1)
                     (uuid? value) (str value)
                     :else value))
   "\\/"
   "/"))

(defn- data-signals
  [signals]
  (string/replace
   (json/write-str
    (into {}
          (map (fn [[k v]]
                 [(signal-key k) (cond
                                   (keyword? v) (subs (str v) 1)
                                   (uuid? v) (str v)
                                   :else v)]))
          signals))
   "\\/"
   "/"))

(defn signal-ref
  "Returns a Datastar signal reference string for `signal-or-name`.

   Identifier-safe names lower to `$name`; names with dashes, slashes, or other
   non-identifier characters lower to bracket notation."
  [signal-or-name]
  (let [n (cond
            (signal? signal-or-name) (or (:resolved-name signal-or-name)
                                         (:semantic-name signal-or-name))
            (keyword? signal-or-name) (signal-key signal-or-name)
            :else (str signal-or-name))]
    (if (re-matches #"[A-Za-z_$][A-Za-z0-9_$]*" n)
      (str "$" n)
      (str "$[" (js-literal n) "]"))))

(defn- stable-suffix
  [scope binding semantic-name]
  (when (seq scope)
    (-> (hash [scope binding semantic-name])
        (bit-and 0xfffff)
        (Long/toString 36))))

(defn- resolve-signal-name
  ([binding opts]
   (resolve-signal-name *signal-scope* binding opts))
  ([scope binding {:keys [name]}]
   (let [semantic (or name (clojure.core/name binding))
        prefix (:prefix scope)
        manual-name (:name scope)
        base (cond
               manual-name (str manual-name "-" semantic)
               prefix (str prefix "-" semantic)
               :else semantic)
        suffix (stable-suffix scope binding semantic)]
     (if suffix
       (str base "__" suffix)
       base))))

(defn create-signal
  "Creates a lexical signal handle.

   Usually called by `with-signals`; public primarily for tests and low-level
   interop. `binding` is the source binding symbol, and opts may include
   `:name`, `:init`, and `:type`."
  [binding {:keys [name init type] :as opts}]
  (->Signal binding
            (or name (clojure.core/name binding))
            (when (seq *signal-scope*)
              (resolve-signal-name binding opts))
            *signal-scope*
            init
            type))

(defmacro with-signal-scope
  "Binds an explicit signal scope for nested `with-signals` forms.

   `{:prefix \"plan\" :key application-id}` produces deterministic,
   collision-resistant names such as `plan-duration-weeks__abc`. `{:name \"x\"}`
   is reserved for raw interop with a manually named scope."
  [scope & body]
  `(binding [*signal-scope* ~scope]
     ~@body))

(defmacro with-signals
  "Declares lexical Datastar signals and attaches their declarations to the
   returned subtree root.

   Example:
   `(with-signals [open? (signal {:init false})] [:button {:on/click {:effect (set-signal! open? true)}}])`"
  [bindings & body]
  (let [pairs (partition 2 bindings)
        lets (mapcat (fn [[sym form]]
                       (when-not (symbol? sym)
                         (throw (ex-info "Signal binding must be a symbol" {:binding sym})))
                       [sym (if (and (seq? form)
                                     (= "signal" (name (first form))))
                              `(create-signal '~sym ~(second form))
                              `(create-signal '~sym ~form))])
                     pairs)
        syms (mapv first pairs)]
    `(let [~@lets]
       (attach-signals ~syms (do ~@body)))))

(defn signal
  "Signal declaration marker for `with-signals`.

   This function is also usable directly as a low-level declaration map, but
   normal app code should call it only inside `with-signals`."
  [opts]
  opts)

(defn attach-signals
  "Attaches signal declarations to the root hiccup node.

   Public for tests; application code should prefer `with-signals`."
  [signals node]
  (if (and (vector? node) (keyword? (first node)))
    (let [[tag maybe-attrs & children] node
          attrs? (map? maybe-attrs)
          attrs (if attrs? maybe-attrs {})
          children (if attrs? children (cons maybe-attrs children))]
      (into [tag (update attrs ::signals (fnil into []) signals)] children))
    (throw (ex-info "with-signals body must return a hiccup element"
                    {:node node}))))

(defn lit
  "Wraps a literal expression value."
  [value]
  (->Expr :lit {:value value}))

(defn sig
  "References a Datastar signal in an expression."
  [signal]
  (->Expr :sig {:signal signal}))

(defn trimmed
  "Expression for `.trim()` on a signal or expression."
  [x]
  (->Expr :trimmed {:expr x}))

(defn num
  "Expression for JavaScript `Number(x)`."
  [x]
  (->Expr :num {:expr x}))

(defn num-cents
  "Expression for converting dollar input to rounded integer cents."
  [dollars]
  (->Expr :num-cents {:expr dollars}))

(defn present?
  "Expression that is true when a value is neither empty string nor null."
  [x]
  (->Expr :present? {:expr x}))

(defn changed?
  "Expression that is true when `x` differs from `old`."
  [x old]
  (->Expr :changed? {:expr x :old old}))

(defn evt
  "References a field on Datastar's event object, e.g. `(evt :key)`."
  [field]
  (->Expr :evt {:field field}))

(defn js
  "Opaque raw JavaScript expression with embedded Weft expressions resolved.

   Example: `(js \"Math.max(0, \" (sig amount) \")\")`."
  [& parts]
  (->Expr :js {:parts parts}))

(declare lower-expr)

(defn- expr-value
  [x]
  (cond
    (expr? x) (lower-expr x)
    (signal? x) (signal-ref x)
    :else (js-literal x)))

(defn lower-expr
  "Lowers a checked expression to a Datastar JavaScript expression string."
  [expr]
  (cond
    (expr? expr)
    (case (:op expr)
      :lit (js-literal (get-in expr [:args :value]))
      :sig (signal-ref (get-in expr [:args :signal]))
      :trimmed (str (expr-value (get-in expr [:args :expr])) ".trim()")
      :num (str "Number(" (expr-value (get-in expr [:args :expr])) ")")
      :num-cents (str "String(Math.round(parseFloat("
                      (expr-value (get-in expr [:args :expr]))
                      " || '0') * 100))")
      :present? (let [v (expr-value (get-in expr [:args :expr]))]
                  (str "(" v " !== '' && " v " !== null)"))
      :changed? (str "(" (expr-value (get-in expr [:args :expr]))
                     " !== " (js-literal (get-in expr [:args :old])) ")")
      :evt (str "evt." (name (get-in expr [:args :field])))
      :js (apply str (map (fn [part]
                            (cond
                              (expr? part) (lower-expr part)
                              (signal? part) (signal-ref part)
                              :else (str part)))
                          (get-in expr [:args :parts]))))

    (signal? expr) (signal-ref expr)
    :else (js-literal expr)))

(defn dispatch
  "Creates a checked command dispatch effect.

   The effect lowers to an explicit Datastar payload post. Ambient Datastar
   signals are not posted unless referenced in `args`.

   Options:
   - `:post` reserved Datastar signal ref, default `$__grainAction`.
     Literal route strings are rejected in checked UI."
  ([command args] (dispatch command args {}))
  ([command args opts]
   (->Effect :dispatch {:command command :args args :opts opts})))

(defn refresh
  "Creates an explicit query/stream refresh effect.

   `params` are the only request values sent to the server; ambient page signals
   are not included. `query` is a registered query keyword with `:datastar/path`.
   By default, Datastar UI includes the reusable-stream protocol nonce `dsNonce`
   as explicit metadata. Pass `{:include-nonce? false}` to omit it for
   one-shot/manual interop.

   Options:
   - `:method` Datastar action method, default `:post`.
   - `:path-params` values for path templates such as `/items/:item-id`.
   - `:query-params` values appended to the generated stream path."
  ([query params] (refresh query params {}))
  ([query params opts]
   (->Effect :refresh {:query query :params params :opts opts})))

(defn href
  "Creates a checked page href for a registered Datastar query.

   `params` may include `:path-params` and `:query-params`."
  ([query] (href query {}))
  ([query params]
   (->Href query params)))

(defn set-signal!
  "Sets a signal to an expression.

   Named `set-signal!` because `set!` is a Clojure special form. Docs may still
   describe this as the checked signal-set effect."
  [signal expr]
  (->Effect :set! {:signal signal :expr expr}))

(defn reset!
  "Resets a signal to its declared initial value."
  [signal]
  (->Effect :reset! {:signal signal}))

(defn clear-errors!
  "Clears the conventional Datastar error signals."
  []
  (->Effect :clear-errors! {}))

(defn blur!
  "Calls `el.blur()`."
  []
  (->Effect :blur! {}))

(defn action
  "Embeds an opaque raw Datastar action string."
  [raw]
  (->Effect :action {:raw raw}))

(defn do!
  "Runs effects in sequence."
  [& effects]
  (->Effect :do! {:effects effects}))

(defn when!
  "Runs `effect` when `pred` is truthy."
  [pred effect]
  (->Effect :when! {:pred pred :effect effect}))

(defn if!
  "Runs `then-effect` or `else-effect` based on `pred`."
  ([pred then-effect] (if! pred then-effect nil))
  ([pred then-effect else-effect]
   (->Effect :if! {:pred pred :then then-effect :else else-effect})))

(defn on-keys
  "Creates a keydown effect map, e.g. `{:Enter (blur!) :Escape (reset! title)}`."
  [key->effect]
  (->Effect :on-keys {:key->effect key->effect}))

(defn- command-name-string
  [command]
  (if (keyword? command)
    (subs (str command) 1)
    (str command)))

(defn- map-schema-entries
  [schema]
  (when (and (vector? schema) (= :map (first schema)))
    (keep (fn [entry]
            (when (vector? entry)
              (let [[k maybe-props maybe-schema] entry
                    props? (map? maybe-props)]
                {:key k
                 :optional? (boolean (and props? (:optional maybe-props)))
                 :schema (if props? maybe-schema maybe-props)})))
          (rest schema))))

(defn- validate-dispatch!
  [command args]
  (when (keyword? command)
    (let [schema (try (m/form (m/deref (m/schema command)))
                      (catch Exception _ nil))
          entries (map-schema-entries schema)]
      (when (seq entries)
        (let [schema-keys (set (map :key entries))
              required (set (keep #(when-not (:optional? %) (:key %)) entries))
              arg-keys (set (keys args))
              missing (seq (remove arg-keys required))
              unknown (seq (remove schema-keys arg-keys))]
          (when missing
            (throw (ex-info "Dispatch is missing required command keys"
                            {:command command :missing (vec missing) :schema schema})))
          (when unknown
            (throw (ex-info "Dispatch has keys not present in command schema"
                            {:command command :unknown (vec unknown) :schema schema}))))))))

(defn- object-literal
  [m]
  (str "{"
       (string/join ", "
                    (map (fn [[k v]]
                           (str (js-literal (signal-key k)) ": " (expr-value v)))
                         m))
       "}"))

(declare lower-effect)

(defn- post-target
  [target]
  (cond
    (nil? target) default-command-post
    (string/starts-with? (str target) "$") (str target)
    :else (throw (ex-info "Dispatch :post must be a reserved signal ref, not a literal route"
                          {:post target}))))

(defn- lower-dispatch
  [{:keys [command args opts]}]
  (validate-dispatch! command args)
  (let [target (post-target (:post opts))
        payload (assoc args :command/name (command-name-string command))]
    (str "@post(" target ", {payload: " (object-literal payload) "})")))

(defn- lower-refresh
  [{:keys [query params opts]}]
  (when-not (keyword? query)
    (throw (ex-info "Refresh target must be a registered query keyword, not a route string"
                    {:target query})))
  (let [method (name (or (:method opts) :post))
        path (core/stream-path query
                               (select-keys opts [:path-params :query-params])
                               (query-registry))
        include-nonce? (not= false (:include-nonce? opts))
        payload (cond-> params
                  include-nonce? (assoc :dsNonce (->Expr :sig {:signal "dsNonce"})))]
    (str "@" method "(" (js-literal path) ", {payload: " (object-literal payload) "})")))

(defn lower-effect
  "Lowers a checked effect to a Datastar action string."
  [effect]
  (cond
    (nil? effect) ""
    (string? effect) effect
    (effect? effect)
    (case (:op effect)
      :dispatch (lower-dispatch (:args effect))
      :refresh (lower-refresh (:args effect))
      :set! (str (signal-ref (get-in effect [:args :signal]))
                 " = "
                 (expr-value (get-in effect [:args :expr]))
                 ";")
      :reset! (let [s (get-in effect [:args :signal])]
                (str (signal-ref s) " = " (js-literal (:init s)) ";"))
      :clear-errors! "$fieldErrors = {}; $error = '';"
      :blur! "el.blur();"
      :action (get-in effect [:args :raw])
      :do! (string/join " " (map lower-effect (get-in effect [:args :effects])))
      :when! (str "if (" (lower-expr (get-in effect [:args :pred])) ") { "
                  (lower-effect (get-in effect [:args :effect]))
                  " }")
      :if! (str "if (" (lower-expr (get-in effect [:args :pred])) ") { "
                (lower-effect (get-in effect [:args :then]))
                " }"
                (when-let [else-effect (get-in effect [:args :else])]
                  (str " else { " (lower-effect else-effect) " }")))
      :on-keys (string/join
                " "
                (map (fn [[k e]]
                       (str "if (evt.key === " (js-literal (name k)) ") { "
                            "evt.preventDefault(); "
                            (lower-effect e)
                            " }"))
                     (get-in effect [:args :key->effect]))))
    :else (js-literal effect)))

(defn- attr-kind
  [k]
  (cond
    (= k ::signals) :signals
    (and (keyword? k) (= "bind" (namespace k))) :bind
    (and (keyword? k) (= "on" (namespace k))) :on
    :else :raw))

(defn- modifier-name
  [k]
  (let [n (cond
            (keyword? k) (name k)
            (symbol? k) (name k)
            (string? k) k
            :else nil)]
    (when-not (and n (not (string/blank? n)))
      (throw (ex-info "Event modifier names must be non-empty keywords, symbols, or strings"
                      {:modifier k})))
    n))

(defn- modifier-value
  [v]
  (cond
    (or (nil? v) (false? v)) nil
    (true? v) true
    (string? v) (do
                  (when (string/blank? v)
                    (throw (ex-info "Event modifier values must not be blank"
                                    {:value v})))
                  v)
    (number? v) (str v)
    (keyword? v) (name v)
    (symbol? v) (name v)
    :else (throw (ex-info "Event modifier values must be booleans, strings, numbers, keywords, symbols, or nil"
                          {:value v}))))

(defn- normalize-modifiers
  [modifiers]
  (when-not (map? modifiers)
    (throw (ex-info "Event :modifiers must be a map"
                    {:modifiers modifiers})))
  (let [normalized (map (fn [[k v]]
                          [(modifier-name k) (modifier-value v)])
                        modifiers)
        modifier-names (map first normalized)
        duplicate-names (->> modifier-names
                             frequencies
                             (keep (fn [[n c]] (when (< 1 c) n))))]
    (when (seq duplicate-names)
      (throw (ex-info "Event :modifiers contains duplicate lowered names"
                      {:modifiers modifiers
                       :duplicate-names (vec duplicate-names)})))
    (into (sorted-map) normalized)))

(defn- normalize-event
  [event-name v]
  (when-not (and (map? v) (not (effect? v)))
    (throw (ex-info "Checked :on/... events must use a map with :effect"
                    {:event event-name
                     :value v})))
  (let [allowed-keys #{:effect :modifiers}
        unknown-keys (seq (remove allowed-keys (keys v)))]
    (when unknown-keys
      (throw (ex-info "Checked event maps may only contain :effect and :modifiers"
                      {:event event-name
                       :unknown-keys (vec unknown-keys)})))
    (when-not (contains? v :effect)
      (throw (ex-info "Checked event maps must include :effect"
                      {:event event-name})))
    {:effect (:effect v)
     :modifiers (normalize-modifiers (or (:modifiers v) {}))}))

(defn- split-attrs
  [attrs]
  (reduce-kv (fn [acc k v]
               (case (attr-kind k)
                 :signals (assoc acc :signals v)
                 :bind (assoc-in acc [:bindings (keyword (name k))] v)
                 :on (assoc-in acc [:events (keyword (name k))]
                               (normalize-event (keyword (name k)) v))
                 :raw (assoc-in acc [:attrs k] v)))
             {:attrs {} :bindings {} :events {} :signals []}
             attrs))

(defn- auto-signal-scope
  [path]
  {:auto/path path})

(defn- resolve-signal
  [signal-env x]
  (if (signal? x)
    (or (.get signal-env x) x)
    x))

(declare resolve-expr-signals resolve-effect-signals resolve-dsl-signals)

(defn- resolve-expr-signals
  [signal-env expr]
  (cond
    (signal? expr) (resolve-signal signal-env expr)
    (expr? expr)
    (case (:op expr)
      :lit expr
      :sig (update-in expr [:args :signal] #(resolve-signal signal-env %))
      :trimmed (update-in expr [:args :expr] #(resolve-expr-signals signal-env %))
      :num (update-in expr [:args :expr] #(resolve-expr-signals signal-env %))
      :num-cents (update-in expr [:args :expr] #(resolve-expr-signals signal-env %))
      :present? (update-in expr [:args :expr] #(resolve-expr-signals signal-env %))
      :changed? (update-in expr [:args :expr] #(resolve-expr-signals signal-env %))
      :evt expr
      :js (update-in expr [:args :parts]
                     (fn [parts]
                       (mapv #(resolve-dsl-signals signal-env %) parts))))
    :else expr))

(defn- resolve-payload-signals
  [signal-env m]
  (into {}
        (map (fn [[k v]]
               [k (resolve-dsl-signals signal-env v)]))
        m))

(defn- resolve-effect-signals
  [signal-env effect]
  (cond
    (effect? effect)
    (case (:op effect)
      :dispatch (update-in effect [:args :args]
                           #(resolve-payload-signals signal-env %))
      :refresh (update-in effect [:args :params]
                          #(resolve-payload-signals signal-env %))
      :set! (-> effect
                (update-in [:args :signal] #(resolve-signal signal-env %))
                (update-in [:args :expr] #(resolve-expr-signals signal-env %)))
      :reset! (update-in effect [:args :signal] #(resolve-signal signal-env %))
      :clear-errors! effect
      :blur! effect
      :action effect
      :do! (update-in effect [:args :effects]
                      (fn [effects]
                        (mapv #(resolve-effect-signals signal-env %) effects)))
      :when! (-> effect
                 (update-in [:args :pred] #(resolve-expr-signals signal-env %))
                 (update-in [:args :effect] #(resolve-effect-signals signal-env %)))
      :if! (-> effect
               (update-in [:args :pred] #(resolve-expr-signals signal-env %))
               (update-in [:args :then] #(resolve-effect-signals signal-env %))
               (update-in [:args :else] #(resolve-effect-signals signal-env %)))
      :on-keys (update-in effect [:args :key->effect]
                          (fn [key->effect]
                            (into {}
                                  (map (fn [[k v]]
                                         [k (resolve-effect-signals signal-env v)]))
                                  key->effect))))
    :else effect))

(defn- resolve-dsl-signals
  [signal-env x]
  (cond
    (signal? x) (resolve-signal signal-env x)
    (expr? x) (resolve-expr-signals signal-env x)
    (effect? x) (resolve-effect-signals signal-env x)
    :else x))

(defn- resolve-binding-signals
  [signal-env kind value]
  (if (= kind :attr)
    (resolve-payload-signals signal-env value)
    (resolve-dsl-signals signal-env value)))

(defn- resolve-local-signals!
  [signal-env path signals]
  (mapv (fn [^Signal signal]
          (let [scope (or (:scope signal) (auto-signal-scope path))
                resolved (if (:resolved-name signal)
                           signal
                           (assoc signal
                                  :scope scope
                                  :resolved-name (resolve-signal-name scope
                                                                      (:binding signal)
                                                                      {:name (:semantic-name signal)})))]
            (.put signal-env signal resolved)
            resolved))
        signals))

(defn- signal-ir
  [^Signal signal]
  {:op :signal
   :binding (:binding signal)
   :semantic-name (:semantic-name signal)
   :resolved-name (:resolved-name signal)
   :scope (:scope signal)
   :init (:init signal)
   :type (:type signal)})

(declare ir*)

(defn- ir*
  [node path signal-env]
  (cond
    (vector? node)
    (let [[tag maybe-attrs & children] node
          attrs? (map? maybe-attrs)
          attrs (if attrs? maybe-attrs {})
          children (if attrs? children (cons maybe-attrs children))
          raw-signals (get attrs ::signals [])
          resolved-signals (resolve-local-signals! signal-env path raw-signals)
          parts (split-attrs attrs)]
      {:op :element
       :tag tag
       :attrs (:attrs parts)
       :signals (mapv signal-ir resolved-signals)
       :bindings (into {}
                       (map (fn [[k v]]
                              [k (resolve-binding-signals signal-env k v)]))
                       (:bindings parts))
       :events (into {}
                     (map (fn [[k v]]
                            [k (update v :effect
                                       #(resolve-effect-signals signal-env %))]))
                     (:events parts))
       :children (mapv (fn [[idx child]]
                         (ir* child (conj path idx) signal-env))
                       (map-indexed vector children))})

    (seq? node) {:op :fragment
                 :children (mapv (fn [[idx child]]
                                    (ir* child (conj path idx) signal-env))
                                  (map-indexed vector node))}
    :else {:op :literal :value node}))

(defn ir
  "Compiles Datastar UI source hiccup into Datastar-shaped IR.

   The IR is stable enough for tests and tooling, but application code should
   normally consume `hiccup`."
  [node]
  (ir* node [] (java.util.IdentityHashMap.)))

(defn- lower-binding-attr
  [kind value]
  (case kind
    :value [:data-bind (if (signal? value) (:resolved-name value) (str value))]
    :text [:data-text (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :show [:data-show (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :class [:data-class (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :attr (throw (ex-info "Internal error: :bind/attr handled separately" {}))
    [(keyword "data-bind" (name kind)) value]))

(defn- lower-event-modifier
  [[modifier-name modifier-value]]
  (when modifier-value
    (if (true? modifier-value)
      (str "__" modifier-name)
      (str "__" modifier-name "." modifier-value))))

(defn- lower-event-attr
  [event-name {:keys [effect modifiers]}]
  [(keyword (str "data-on:" (name event-name)
                 (apply str (keep lower-event-modifier modifiers))))
   (lower-effect effect)])

(defn- lower-attr-value
  [v]
  (if (href? v)
    (core/page-path (:query v) (:params v) (query-registry))
    v))

(defn- lower-attrs
  [attrs]
  (into {}
        (map (fn [[k v]]
               [k (lower-attr-value v)]))
        attrs))

(defn- merge-data-signals
  [attrs signals]
  (if (seq signals)
    (let [signal-map (into {} (map (fn [{:keys [resolved-name init]}]
                                     [resolved-name init])
                                   signals))
          encoded (data-signals signal-map)]
      (if (:data-signals attrs)
        ;; Raw data-signals may use Datastar's JSON-ish single-quote syntax, so
        ;; don't parse and rewrite it. Put generated declarations in ifmissing
        ;; instead, preserving the raw attr exactly.
        (assoc attrs "data-signals__ifmissing" encoded)
        (assoc attrs :data-signals encoded)))
    attrs))

(declare lower-ir)

(defn lower-ir
  "Lowers Datastar UI IR to plain Datastar hiccup.

   `opts` currently accepts `{:target :datastar}` and is reserved for future
   checked interpretations."
  ([ir-node] (lower-ir ir-node {:target :datastar}))
  ([ir-node opts]
   (binding [*lower-opts* opts]
     (case (:op ir-node)
     :literal (lower-attr-value (:value ir-node))
     :fragment (doall (map #(lower-ir % opts) (:children ir-node)))
     :element
     (let [attrs0 (lower-attrs (:attrs ir-node))
           attrs1 (merge-data-signals attrs0 (:signals ir-node))
           attrs2 (reduce-kv (fn [m k v]
                               (if (= k :attr)
                                 (reduce-kv (fn [m2 attr-k attr-v]
                                              (assoc m2 (keyword (str "data-attr:" (name attr-k)))
                                                     (expr-value attr-v)))
                                            m
                                            v)
                                 (let [[attr-k attr-v] (lower-binding-attr k v)]
                                   (assoc m attr-k attr-v))))
                             attrs1
                             (:bindings ir-node))
           attrs3 (reduce-kv (fn [m k v]
                               (let [[attr-k attr-v] (lower-event-attr k v)]
                                 (assoc m attr-k attr-v)))
                             attrs2
                             (:events ir-node))
           children (map #(lower-ir % opts) (:children ir-node))]
       (into [(:tag ir-node) attrs3] children))))))

(defn hiccup
  "Compiles Datastar UI source to ordinary Datastar hiccup.

   This returns hiccup, not an HTML string. The existing Datastar adapter remains
   responsible for `hiccup2` HTML rendering and SSE patch construction."
  ([source] (hiccup source {}))
  ([source opts]
   (lower-ir (ir source) opts)))

(defn static
  "Interprets IR as static gallery hiccup.

   Checked events and signal declarations are dropped. Options:
   - `:strip-href?` removes `:href`.
   - `:strip-raw-events?` removes raw `data-on*` attributes."
  ([ir-node] (static ir-node {}))
  ([ir-node opts]
   (binding [*lower-opts* opts]
     (case (:op ir-node)
       :literal (lower-attr-value (:value ir-node))
       :fragment (doall (map #(static % opts) (:children ir-node)))
       :element
       (let [attrs (cond-> (lower-attrs (:attrs ir-node))
                     (:strip-href? opts) (dissoc :href)
                     (:strip-raw-events? opts)
                     (as-> a
                           (into {}
                                 (remove (fn [[k _]]
                                           (let [s (if (keyword? k)
                                                     (str (when-let [ns (namespace k)] (str ns ":"))
                                                          (name k))
                                                     (str k))]
                                             (string/starts-with? s "data-on")))
                                         a))))]
         (into [(:tag ir-node) attrs]
               (map #(static % opts))
               (:children ir-node)))))))

(defmacro defcomponent
  "Defines a component function whose body is compiled with `hiccup`."
  [name args & body]
  `(defn ~name ~args
     (hiccup (do ~@body))))
