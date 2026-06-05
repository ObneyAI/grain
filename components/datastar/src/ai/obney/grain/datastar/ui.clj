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
  (:require [clojure.data.json :as json]
            [clojure.string :as string]
            [malli.core :as m]))

(def ^:dynamic *signal-scope*
  "Current automatic signal scope. Bound by `with-signal-scope`."
  nil)

(def default-command-post
  "Default Datastar action target used by `dispatch`."
  "$__grainAction")

(defrecord Signal [binding semantic-name resolved-name scope init type])
(defrecord Expr [op args])
(defrecord Effect [op args])

(defn- signal? [x] (instance? Signal x))
(defn- expr? [x] (instance? Expr x))
(defn- effect? [x] (instance? Effect x))

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
            (signal? signal-or-name) (:resolved-name signal-or-name)
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
  [binding {:keys [name]}]
  (let [scope *signal-scope*
        semantic (or name (clojure.core/name binding))
        prefix (:prefix scope)
        manual-name (:name scope)
        base (cond
               manual-name (str manual-name "-" semantic)
               prefix (str prefix "-" semantic)
               :else semantic)
        suffix (stable-suffix scope binding semantic)]
    (if suffix
      (str base "__" suffix)
      base)))

(defn create-signal
  "Creates a lexical signal handle.

   Usually called by `with-signals`; public primarily for tests and low-level
   interop. `binding` is the source binding symbol, and opts may include
   `:name`, `:init`, and `:type`."
  [binding {:keys [name init type] :as opts}]
  (->Signal binding
            (or name (clojure.core/name binding))
            (resolve-signal-name binding opts)
            *signal-scope*
            init
            type))

(defmacro with-signal-scope
  "Binds automatic signal scope for nested `with-signals` forms.

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
   `(with-signals [open? (signal {:init false})] [:button {:on/click (set! open? true)}])`"
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
   - `:post` Datastar post target, default `$__grainAction`."
  ([command args] (dispatch command args {}))
  ([command args opts]
   (->Effect :dispatch {:command command :args args :opts opts})))

(defn refresh
  "Creates an explicit query/stream refresh effect.

   `params` are the only request values sent to the server; ambient page signals
   are not included. By default, Datastar UI includes the reusable-stream
   protocol nonce `dsNonce` as explicit metadata. Pass
   `{:include-nonce? false}` to omit it for one-shot/manual interop."
  ([path params] (refresh path params {}))
  ([path params opts]
   (->Effect :refresh {:path path :params params :opts opts})))

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
    :else (js-literal target)))

(defn- lower-dispatch
  [{:keys [command args opts]}]
  (validate-dispatch! command args)
  (let [target (post-target (:post opts))
        payload (assoc args :command/name (command-name-string command))]
    (str "@post(" target ", {payload: " (object-literal payload) "})")))

(defn- lower-refresh
  [{:keys [path params opts]}]
  (let [method (name (or (:method opts) :post))
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

(defn- split-attrs
  [attrs]
  (reduce-kv (fn [acc k v]
               (case (attr-kind k)
                 :signals (assoc acc :signals v)
                 :bind (assoc-in acc [:bindings (keyword (name k))] v)
                 :on (assoc-in acc [:events (keyword (name k))] v)
                 :raw (assoc-in acc [:attrs k] v)))
             {:attrs {} :bindings {} :events {} :signals []}
             attrs))

(defn ir
  "Compiles Datastar UI source hiccup into Datastar-shaped IR.

   The IR is stable enough for tests and tooling, but application code should
   normally consume `hiccup`."
  [node]
  (cond
    (vector? node)
    (let [[tag maybe-attrs & children] node
          attrs? (map? maybe-attrs)
          attrs (if attrs? maybe-attrs {})
          children (if attrs? children (cons maybe-attrs children))
          parts (split-attrs attrs)]
      {:op :element
       :tag tag
       :attrs (:attrs parts)
       :signals (mapv (fn [^Signal s]
                        {:op :signal
                         :binding (:binding s)
                         :semantic-name (:semantic-name s)
                         :resolved-name (:resolved-name s)
                         :scope (:scope s)
                         :init (:init s)
                         :type (:type s)})
                      (:signals parts))
       :bindings (:bindings parts)
       :events (:events parts)
       :children (mapv ir children)})

    (seq? node) {:op :fragment :children (mapv ir node)}
    :else {:op :literal :value node}))

(defn- lower-binding-attr
  [kind value]
  (case kind
    :value [:data-bind (if (signal? value) (:resolved-name value) (str value))]
    :text [:data-text (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :show [:data-show (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :class [:data-class (if (or (signal? value) (expr? value)) (expr-value value) (str value))]
    :attr (throw (ex-info "Internal error: :bind/attr handled separately" {}))
    [(keyword "data-bind" (name kind)) value]))

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
  ([ir-node _opts]
   (case (:op ir-node)
     :literal (:value ir-node)
     :fragment (doall (map lower-ir (:children ir-node)))
     :element
     (let [attrs0 (:attrs ir-node)
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
                               (assoc m (keyword (str "data-on:" (name k)))
                                      (lower-effect v)))
                             attrs2
                             (:events ir-node))
           children (map lower-ir (:children ir-node))]
       (into [(:tag ir-node) attrs3] children)))))

(defn hiccup
  "Compiles Datastar UI source to ordinary Datastar hiccup.

   This returns hiccup, not an HTML string. The existing Datastar adapter remains
   responsible for `hiccup2` HTML rendering and SSE patch construction."
  [source]
  (lower-ir (ir source)))

(defn static
  "Interprets IR as static gallery hiccup.

   Checked events and signal declarations are dropped. Options:
   - `:strip-href?` removes `:href`.
   - `:strip-raw-events?` removes raw `data-on*` attributes."
  ([ir-node] (static ir-node {}))
  ([ir-node opts]
   (case (:op ir-node)
     :literal (:value ir-node)
     :fragment (doall (map #(static % opts) (:children ir-node)))
     :element
     (let [attrs (cond-> (:attrs ir-node)
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
             (:children ir-node))))))

(defmacro defcomponent
  "Defines a component function whose body is compiled with `hiccup`."
  [name args & body]
  `(defn ~name ~args
     (hiccup (do ~@body))))
