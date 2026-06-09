(ns ai.obney.grain.datastar.ui
  "IR-first Datastar UI helpers for Grain applications.

   `hiccup` is the main app-facing boundary: it accepts ordinary hiccup plus
   this namespace's Datastar-aware attributes/effects, compiles that source to a
   Datastar-shaped IR, and lowers the IR back to ordinary Datastar hiccup. Final
   HTML serialization still belongs to the existing Datastar adapter and
   hiccup2.

   Server effects generated here use explicit payloads. Datastar signals remain
   client-local UI state unless a `dispatch` or `refresh` explicitly references
   them in its payload. Checked route refs resolve through the Grain query
   registry so UI code does not hard-code Datastar paths. Raw Datastar
   attributes and raw actions pass through unchanged and are intentionally
   unchecked."
  (:refer-clojure :exclude [num indexed?])
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

(defrecord Signal [binding semantic-name resolved-name scope init])
(defrecord Indexed [collection index])
(defrecord Expr [op args])
(defrecord Effect [op args])
(defrecord Href [query params])

(defn- signal? [x] (instance? Signal x))
(defn- indexed? [x] (instance? Indexed x))
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

(declare expr-value)

(defn- indexed-ref
  [indexed]
  (str (expr-value (:collection indexed))
       "["
       (expr-value (:index indexed))
       "]"))

(defn- target-ref
  [target]
  (cond
    (signal? target) (signal-ref target)
    (indexed? target) (indexed-ref target)
    :else (throw (ex-info "Effect target must be a signal or indexed signal"
                          {:target target}))))

(defn- stable-suffix
  [scope binding semantic-name]
  (when (seq scope)
    (-> (hash [scope binding semantic-name])
        (bit-and 0xfffff)
        (Long/toString 36))))

(defn- resolve-signal-name
  ([binding opts]
   (resolve-signal-name *signal-scope* binding opts))
  ([scope binding {:keys [name stable?]}]
   (when (and stable? (not name))
     (throw (ex-info "Stable signals must declare :name"
                     {:binding binding})))
   (let [semantic (or name (clojure.core/name binding))
        prefix (:prefix scope)
        manual-name (:name scope)
        base (cond
               stable? semantic
               manual-name (str manual-name "-" semantic)
               prefix (str prefix "-" semantic)
               :else semantic)
        suffix (when-not stable?
                 (stable-suffix scope binding semantic))]
     (if suffix
       (str base "__" suffix)
       base))))

(defn create-signal
  "Creates a lexical signal handle.

   Usually called by `with-signals`; public primarily for tests and low-level
   interop. `binding` is the source binding symbol, and opts may include
   `:name`, `:init`, and `:stable?`."
  [binding opts]
  (when-not (map? opts)
    (throw (ex-info "Signal options must be a map"
                    {:binding binding :opts opts})))
  (when (and (:stable? opts) (not (:name opts)))
    (throw (ex-info "Stable signals must declare :name"
                    {:binding binding :opts opts})))
  (let [{:keys [name init stable?]} opts]
    (->Signal binding
              (or name (clojure.core/name binding))
              (when (or stable? (seq *signal-scope*))
                (resolve-signal-name binding opts))
              *signal-scope*
              init)))

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
   `(with-signals [open? {:init false}] [:button {:on/click {:effect (set-signal open? true)}}])`"
  [bindings & body]
  (when-not (vector? bindings)
    (throw (ex-info "Signal bindings must be a vector" {:bindings bindings})))
  (when-not (even? (count bindings))
    (throw (ex-info "Signal bindings must contain symbol/options pairs"
                    {:bindings bindings})))
  (let [pairs (partition 2 bindings)
        lets (mapcat (fn [[sym form]]
                       (when-not (symbol? sym)
                         (throw (ex-info "Signal binding must be a symbol" {:binding sym})))
                       [sym `(create-signal '~sym ~form)])
                     pairs)
        syms (mapv first pairs)]
    `(let [~@lets]
       (attach-signals ~syms (do ~@body)))))

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
  "Opaque raw JavaScript expression with embedded checked expressions resolved.

   Signal refs and UI expressions inside `parts` are lowered before the raw
   JavaScript fragments are joined. Use this as an expression escape hatch when
   the checked helpers cannot describe the condition.

   Example: `(js \"Math.max(0, \" amount \")\")`."
  [& parts]
  (->Expr :js {:parts parts}))

(defn indexed
  "References an indexed element inside a collection signal."
  [collection-signal idx]
  (->Indexed collection-signal idx))

(declare lower-expr)

(defn- expr-value
  [x]
  (cond
    (expr? x) (lower-expr x)
    (signal? x) (signal-ref x)
    (indexed? x) (indexed-ref x)
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
                              (indexed? part) (indexed-ref part)
                              :else (str part)))
                          (get-in expr [:args :parts]))))

    (signal? expr) (signal-ref expr)
    (indexed? expr) (indexed-ref expr)
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

   `params` may include `:path-params` and `:query-params`. The route keyword
   resolves through the query registry during lowering."
  ([query] (href query {}))
  ([query params]
   (->Href query params)))

(defn set-signal
  "Sets a signal to an expression.
   Named `set-signal` because `set` is already a core collection function."
  [signal expr]
  (->Effect :set-signal {:signal signal :expr expr}))

(defn reset-signal
  "Resets a signal to its declared initial value."
  [signal]
  (->Effect :reset-signal {:signal signal}))

(defn clear-errors
  "Clears the conventional Datastar error signals."
  []
  (->Effect :clear-errors {}))

(defn blur
  "Calls `el.blur()`."
  []
  (->Effect :blur {}))

(defn action
  "Embeds an opaque raw Datastar action string.

   This is an escape hatch for unsupported Datastar behavior. Raw actions are
   not route-checked and do not receive route-ref or payload handling."
  [raw]
  (->Effect :action {:raw raw}))

(defn effects
  "Runs effects in sequence."
  [& effects]
  (->Effect :effects {:effects effects}))

(defn when-effect
  "Runs `effect` when `pred` is truthy."
  [pred effect]
  (->Effect :when-effect {:pred pred :effect effect}))

(defn choose-effect
  "Runs `then-effect` or `else-effect` based on `pred`."
  ([pred then-effect] (choose-effect pred then-effect nil))
  ([pred then-effect else-effect]
   (->Effect :choose-effect {:pred pred :then then-effect :else else-effect})))

(defn on-keys
  "Creates a keydown effect map, e.g. `{:Enter (blur) :Escape (reset-signal title)}`."
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

(defn- validate-dispatch
  [command args]
  (when (keyword? command)
    (let [schema (try (m/form (m/deref (m/schema command)))
                      (catch Exception _ nil))
          entries (map-schema-entries schema)]
      (when (seq entries)
        (let [schema-keys (set (map :key entries))
              required (set (keep #(when-not (:optional? %) (:key %)) entries))
              arg-keys (set (keys args))
              literal-nil-required (seq (keep (fn [k]
                                                 (when (and (contains? args k)
                                                            (nil? (get args k)))
                                                   k))
                                               required))
              missing (seq (remove arg-keys required))
              unknown (seq (remove schema-keys arg-keys))]
          (when missing
            (throw (ex-info "Dispatch is missing required command keys"
                            {:command command :missing (vec missing) :schema schema})))
          (when literal-nil-required
            (throw (ex-info "Dispatch has nil values for required command keys"
                            {:command command :nil-required (vec literal-nil-required) :schema schema})))
          (when unknown
            (throw (ex-info "Dispatch has keys not present in command schema"
                            {:command command :unknown (vec unknown) :schema schema}))))))))

(declare payload-literal)

(defn- payload-key-literal
  [k]
  (cond
    (or (expr? k) (signal? k))
    (throw (ex-info "Payload map keys must be static literal values"
                    {:key k}))

    (or (keyword? k) (symbol? k)) (js-literal (signal-key k))
    (or (string? k) (uuid? k) (number? k) (boolean? k)) (js-literal k)
    :else (throw (ex-info "Payload map keys must be keywords, strings, symbols, UUIDs, numbers, or booleans"
                          {:key k}))))

(defn- payload-object-literal
  [m]
  (str "{"
       (string/join ", "
                    (keep (fn [[k v]]
                            (let [key-literal (payload-key-literal k)]
                              (cond
                                (nil? v) nil
                                (or (expr? v) (signal? v) (indexed? v))
                                (let [value (expr-value v)]
                                  (str "...((" value ") == null ? {} : {"
                                       key-literal
                                       ": " value "})"))
                                :else (str key-literal ": " (payload-literal v)))))
                          m))
       "}"))

(defn- payload-array-literal
  [xs]
  (str "["
       (string/join ", " (map payload-literal xs))
       "]"))

(defn- payload-literal
  [v]
  (cond
    (or (expr? v) (signal? v) (indexed? v)) (expr-value v)
    (map? v) (payload-object-literal v)
    (or (sequential? v) (set? v)) (payload-array-literal v)
    :else (js-literal v)))

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
  (validate-dispatch command args)
  (let [target (post-target (:post opts))
        payload (assoc args :command/name (command-name-string command))]
    (str "@post(" target ", {payload: " (payload-object-literal payload) "})")))

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
    (str "@" method "(" (js-literal path) ", {payload: " (payload-object-literal payload) "})")))

(defn- statement
  [s]
  (let [s (string/trim (str s))]
    (when-not (string/blank? s)
      (if (string/ends-with? s ";")
        s
        (str s ";")))))

(declare lower-effect*)
(declare lower-effect-expr*)

(defn- contains-blur?
  [effect]
  (and (effect? effect)
       (case (:op effect)
         :blur true
         :effects (boolean (some contains-blur? (get-in effect [:args :effects])))
         :when-effect (contains-blur? (get-in effect [:args :effect]))
         :choose-effect (or (contains-blur? (get-in effect [:args :then]))
                            (contains-blur? (get-in effect [:args :else])))
         :on-keys (boolean (some contains-blur? (vals (get-in effect [:args :key->effect]))))
         false)))

(defn- same-signal?
  [a b]
  (and (or (signal? a) (indexed? a))
       (or (signal? b) (indexed? b))
       (= (target-ref a) (target-ref b))))

(defn- bound-value-assignment?
  [ctx signal]
  (and (:sync-bound-value? ctx)
       (same-signal? signal (:bound-value-signal ctx))))

(defn- lower-set-signal
  [signal expr sync-dom?]
  (let [assignment (str (target-ref signal) " = " (expr-value expr))]
    (str (if sync-dom?
           (str "el.value = (" assignment ")")
           assignment)
         ";")))

(defn- lower-reset-signal
  [signal sync-dom?]
  (let [assignment (str (signal-ref signal) " = " (js-literal (:init signal)))]
    (str (if sync-dom?
           (str "el.value = (" assignment ")")
           assignment)
         ";")))

(defn- lower-effects
  [effects ctx]
  (let [effectv (vec effects)]
    (->> (map-indexed (fn [idx effect]
                        (let [later-effects (subvec effectv (inc idx))]
                          (lower-effect* effect
                                         (assoc ctx
                                                :sync-bound-value?
                                                (boolean (some contains-blur?
                                                               later-effects))))))
                      effectv)
         (keep statement)
         (string/join " "))))

(defn- expression
  [s]
  (let [s (string/trim (str s))]
    (when-not (string/blank? s)
      (string/replace s #";+\s*$" ""))))

(defn- lower-effects-expr
  [effects ctx]
  (let [effectv (vec effects)
        exprs (keep-indexed (fn [idx effect]
                              (let [later-effects (subvec effectv (inc idx))]
                                (expression
                                 (lower-effect-expr*
                                  effect
                                  (assoc ctx
                                         :sync-bound-value?
                                         (boolean (some contains-blur?
                                                        later-effects)))))))
                            effectv)]
    (case (count exprs)
      0 "undefined"
      1 (first exprs)
      (str "(" (string/join ", " exprs) ")"))))

(defn- lower-set-signal-expr
  [signal expr sync-dom?]
  (let [assignment (str (target-ref signal) " = " (expr-value expr))]
    (str "(" (if sync-dom?
               (str "el.value = (" assignment ")")
               assignment)
         ")")))

(defn- lower-reset-signal-expr
  [signal sync-dom?]
  (let [assignment (str (signal-ref signal) " = " (js-literal (:init signal)))]
    (str "(" (if sync-dom?
               (str "el.value = (" assignment ")")
               assignment)
         ")")))

(defn- lower-on-keys-expr
  [key->effect ctx]
  (lower-effects-expr
   (map (fn [[k e]]
          (->Effect :when-effect
                    {:pred (js "evt.key === " (js-literal (name k)))
                     :effect (effects
                              (action "evt.preventDefault()")
                              e)}))
        key->effect)
   ctx))

(defn- lower-effect-expr*
  [effect ctx]
  (cond
    (nil? effect) "undefined"
    (string? effect) (or (expression effect) "undefined")
    (effect? effect)
    (case (:op effect)
      :dispatch (lower-dispatch (:args effect))
      :refresh (lower-refresh (:args effect))
      :set-signal (lower-set-signal-expr (get-in effect [:args :signal])
                                         (get-in effect [:args :expr])
                                         (bound-value-assignment?
                                          ctx
                                          (get-in effect [:args :signal])))
      :reset-signal (lower-reset-signal-expr (get-in effect [:args :signal])
                                             (bound-value-assignment?
                                              ctx
                                              (get-in effect [:args :signal])))
      :clear-errors "(($fieldErrors = {}), ($error = ''))"
      :blur "el.blur()"
      :action (or (expression (get-in effect [:args :raw])) "undefined")
      :effects (lower-effects-expr (get-in effect [:args :effects]) ctx)
      :when-effect (str "(" (lower-expr (get-in effect [:args :pred])) ") && ("
                        (lower-effect-expr* (get-in effect [:args :effect]) ctx)
                        ")")
      :choose-effect (str "(" (lower-expr (get-in effect [:args :pred])) ") ? ("
                          (lower-effect-expr* (get-in effect [:args :then]) ctx)
                          ") : ("
                          (if-let [else-effect (get-in effect [:args :else])]
                            (lower-effect-expr* else-effect ctx)
                            "undefined")
                          ")")
      :on-keys (lower-on-keys-expr (get-in effect [:args :key->effect]) ctx))
    :else (js-literal effect)))

(defn- lower-effect*
  [effect ctx]
  (cond
    (nil? effect) ""
    (string? effect) effect
    (effect? effect)
    (case (:op effect)
      :dispatch (lower-dispatch (:args effect))
      :refresh (lower-refresh (:args effect))
      :set-signal (lower-set-signal (get-in effect [:args :signal])
                                    (get-in effect [:args :expr])
                                    (bound-value-assignment?
                                     ctx
                                     (get-in effect [:args :signal])))
      :reset-signal (lower-reset-signal (get-in effect [:args :signal])
                                        (bound-value-assignment?
                                         ctx
                                         (get-in effect [:args :signal])))
      :clear-errors "$fieldErrors = {}; $error = '';"
      :blur "el.blur();"
      :action (get-in effect [:args :raw])
      :effects (lower-effects (get-in effect [:args :effects]) ctx)
      :when-effect (str "if (" (lower-expr (get-in effect [:args :pred])) ") { "
                  (lower-effect* (get-in effect [:args :effect]) ctx)
                  " }")
      :choose-effect (str "if (" (lower-expr (get-in effect [:args :pred])) ") { "
                (lower-effect* (get-in effect [:args :then]) ctx)
                " }"
                (when-let [else-effect (get-in effect [:args :else])]
                  (str " else { " (lower-effect* else-effect ctx) " }")))
      :on-keys (string/join
                " "
                (map (fn [[k e]]
                       (str "if (evt.key === " (js-literal (name k)) ") { "
                            "evt.preventDefault(); "
                            (lower-effect* e ctx)
                            " }"))
                     (get-in effect [:args :key->effect]))))
    :else (js-literal effect)))

(defn lower-effect
  "Lowers a checked effect to a Datastar action string."
  [effect]
  (lower-effect* effect {}))

(defn- attr-kind
  [k]
  (cond
    (= k ::signals) :signals
    (= k :effect) :effect
    (and (keyword? k) (= "bind" (namespace k))) :bind
    (and (keyword? k) (= "on" (namespace k))) :on
    (and (keyword? k) (= "morph" (namespace k))) :morph
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
                 :effect (assoc acc :effect v)
                 :bind (assoc-in acc [:bindings (keyword (name k))] v)
                 :on (assoc-in acc [:events (keyword (name k))]
                               (normalize-event (keyword (name k)) v))
                 :morph (assoc-in acc [:morph (keyword (name k))] v)
                 :raw (assoc-in acc [:attrs k] v)))
             {:attrs {} :bindings {} :events {} :morph {} :signals [] :effect nil}
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

(defn- resolve-indexed-signals
  [signal-env indexed]
  (-> indexed
      (update :collection #(resolve-dsl-signals signal-env %))
      (update :index #(resolve-dsl-signals signal-env %))))

(defn- resolve-expr-signals
  [signal-env expr]
  (cond
    (signal? expr) (resolve-signal signal-env expr)
    (indexed? expr) (resolve-indexed-signals signal-env expr)
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
  [signal-env value]
  (cond
    (or (signal? value) (indexed? value) (expr? value) (effect? value))
    (resolve-dsl-signals signal-env value)

    (map? value) (into {}
                       (map (fn [[k v]]
                              [k (resolve-payload-signals signal-env v)]))
                       value)
    (vector? value) (mapv #(resolve-payload-signals signal-env %) value)
    (set? value) (into #{} (map #(resolve-payload-signals signal-env %)) value)
    (seq? value) (doall (map #(resolve-payload-signals signal-env %) value))
    :else (resolve-dsl-signals signal-env value)))

(defn- resolve-effect-signals
  [signal-env effect]
  (cond
    (effect? effect)
    (case (:op effect)
      :dispatch (update-in effect [:args :args]
                           #(resolve-payload-signals signal-env %))
      :refresh (update-in effect [:args :params]
                          #(resolve-payload-signals signal-env %))
      :set-signal (-> effect
                (update-in [:args :signal] #(resolve-dsl-signals signal-env %))
                (update-in [:args :expr] #(resolve-expr-signals signal-env %)))
      :reset-signal (update-in effect [:args :signal] #(resolve-signal signal-env %))
      :clear-errors effect
      :blur effect
      :action effect
      :effects (update-in effect [:args :effects]
                      (fn [effects]
                        (mapv #(resolve-effect-signals signal-env %) effects)))
      :when-effect (-> effect
                 (update-in [:args :pred] #(resolve-expr-signals signal-env %))
                 (update-in [:args :effect] #(resolve-effect-signals signal-env %)))
      :choose-effect (-> effect
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
    (indexed? x) (resolve-indexed-signals signal-env x)
    (expr? x) (resolve-expr-signals signal-env x)
    (effect? x) (resolve-effect-signals signal-env x)
    :else x))

(defn- resolve-binding-signals
  [signal-env kind value]
  (if (#{:attr :prop} kind)
    (resolve-payload-signals signal-env value)
    (resolve-dsl-signals signal-env value)))

(defn- resolve-local-signals
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
   :init (:init signal)})

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
          resolved-signals (resolve-local-signals signal-env path raw-signals)
          parts (split-attrs attrs)]
      {:op :element
       :tag tag
       :attrs (:attrs parts)
       :morph (:morph parts)
       :signals (mapv signal-ir resolved-signals)
       :bindings (into {}
                       (map (fn [[k v]]
                              [k (resolve-binding-signals signal-env k v)]))
                       (:bindings parts))
       :effect (resolve-effect-signals signal-env (:effect parts))
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

(defn- indexed-value-binding-attrs
  [value]
  (let [target (target-ref value)
        sync (str target " = el.value;")]
    {:data-effect (str "el.value = " target ";")
     :data-on:input sync
     :data-on:change sync}))

(defn- lower-prop-bindings
  [props]
  (when-not (map? props)
    (throw (ex-info ":bind/prop value must be a map"
                    {:value props})))
  {:data-effect
   (->> props
        (map (fn [[prop value]]
               (str "el." (name prop) " = " (expr-value value) ";")))
        (string/join " "))})

(defn- lower-binding-attrs
  [kind value]
  (case kind
    :value (cond
             (signal? value) {:data-bind (:resolved-name value)}
             (indexed? value) (indexed-value-binding-attrs value)
             :else {:data-bind (str value)})
    :text {:data-text (if (or (signal? value) (indexed? value) (expr? value))
                        (expr-value value)
                        (str value))}
    :show {:data-show (if (or (signal? value) (indexed? value) (expr? value))
                        (expr-value value)
                        (str value))}
    :class {:data-class (if (or (signal? value) (indexed? value) (expr? value))
                          (expr-value value)
                          (str value))}
    :attr (throw (ex-info "Internal error: :bind/attr handled separately" {}))
    :prop (lower-prop-bindings value)
    {(keyword "data-bind" (name kind)) value}))

(defn- merge-lowered-attr
  [attrs attr-k attr-v]
  (if-let [existing (get attrs attr-k)]
    (if (and (string? existing)
             (string? attr-v)
             (or (= attr-k :data-effect)
                 (and (keyword? attr-k)
                      (or (string/starts-with? (name attr-k) "data-on:")
                          (string/starts-with? (name attr-k) "data-on-")))))
      (assoc attrs attr-k (str existing " " attr-v))
      (assoc attrs attr-k attr-v))
    (assoc attrs attr-k attr-v)))

(defn- lower-event-modifier
  [[modifier-name modifier-value]]
  (when modifier-value
    (if (true? modifier-value)
      (str "__" modifier-name)
      (str "__" modifier-name "." modifier-value))))

(defn- event-attr-name
  [event-name modifiers]
  (str (if (= event-name :signal-patch)
         "data-on-signal-patch"
         (str "data-on:" (name event-name)))
       (apply str (keep lower-event-modifier modifiers))))

(defn- lower-event-attr
  [event-name {:keys [effect modifiers]} ctx]
  [(keyword (event-attr-name event-name modifiers))
   (lower-effect-expr* effect ctx)])

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

(defn- lower-morph-attrs
  [morph]
  (reduce-kv (fn [attrs k v]
               (case k
                 :ignore (if v
                           (assoc attrs :data-ignore-morph true)
                           attrs)
                 (throw (ex-info "Unknown :morph/... attribute"
                                 {:attribute (keyword "morph" (name k))}))))
             {}
             morph))

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

   Options:
   - `:target` lowering target, currently `:datastar`.
   - `:query-registry` explicit registry used to resolve checked route refs."
  ([ir-node] (lower-ir ir-node {:target :datastar}))
  ([ir-node opts]
   (binding [*lower-opts* opts]
     (case (:op ir-node)
     :literal (lower-attr-value (:value ir-node))
     :fragment (doall (map #(lower-ir % opts) (:children ir-node)))
     :element
     (let [attrs0 (lower-attrs (:attrs ir-node))
           attrs0b (reduce-kv merge-lowered-attr attrs0 (lower-morph-attrs (:morph ir-node)))
           attrs1 (merge-data-signals attrs0b (:signals ir-node))
           attrs2 (reduce-kv (fn [m k v]
                               (if (= k :attr)
                                 (reduce-kv (fn [m2 attr-k attr-v]
                                              (merge-lowered-attr
                                               m2
                                               (keyword (str "data-attr:" (name attr-k)))
                                               (expr-value attr-v)))
                                            m
                                            v)
                                 (reduce-kv merge-lowered-attr
                                            m
                                            (lower-binding-attrs k v))))
                             attrs1
                             (:bindings ir-node))
           attrs2b (if-let [effect (:effect ir-node)]
                     (merge-lowered-attr attrs2 :data-effect (lower-effect* effect {}))
                     attrs2)
           attrs3 (reduce-kv (fn [m k v]
                               (let [ctx {:bound-value-signal (get-in ir-node [:bindings :value])}
                                     [attr-k attr-v] (lower-event-attr k v ctx)]
                                 (merge-lowered-attr m attr-k attr-v)))
                             attrs2b
                             (:events ir-node))
           children (map #(lower-ir % opts) (:children ir-node))]
       (into [(:tag ir-node) attrs3] children))))))

(defn hiccup
  "Compiles Datastar UI source to ordinary Datastar hiccup.

   This returns hiccup, not an HTML string. The existing Datastar adapter remains
   responsible for `hiccup2` HTML rendering and SSE patch construction. Pass
   `{:query-registry registry}` when tests or alternate systems should resolve
   checked route refs without using the global registry."
  ([source] (hiccup source {}))
  ([source opts]
   (lower-ir (ir source) opts)))

(defn static
  "Interprets IR as static gallery hiccup.

   Checked events and signal declarations are dropped. Options:
   - `:strip-href?` removes `:href`.
   - `:strip-raw-events?` removes raw `data-on*` attributes.
   - `:query-registry` resolves checked `href` route refs."
  ([ir-node] (static ir-node {}))
  ([ir-node opts]
   (binding [*lower-opts* opts]
     (case (:op ir-node)
       :literal (lower-attr-value (:value ir-node))
       :fragment (doall (map #(static % opts) (:children ir-node)))
       :element
       (let [attrs (cond-> (merge (lower-attrs (:attrs ir-node))
                                  (lower-morph-attrs (:morph ir-node)))
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
