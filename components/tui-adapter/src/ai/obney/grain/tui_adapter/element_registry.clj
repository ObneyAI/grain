(ns ai.obney.grain.tui-adapter.element-registry
  "Single global registry for hiccup elements (built-ins and application
   `defelement` registrations alike — §7.6.6 honesty requirement).

   The registry is a `defonce` atom keyed by element keyword:

       {:tag {:render          fn
              :attrs           malli-schema
              :attrs-validator compiled-validator
              :preferred-size  fn
              :min-size        {:width n :height n}
              :focusable?      bool
              :keymap          map
              :stream-stable?  bool
              :container?      bool   ; substrate-internal
              :doc             string}}

   Validation of the registration map happens at register-time (Malli);
   validation of element attrs happens at render-time (cached compiled
   validator)."
  (:require [com.brunobonacci.mulog :as u]
            [malli.core :as m]))

;; ─────────────────────────────────────────────────────────────────────
;; The registry itself
;; ─────────────────────────────────────────────────────────────────────

(defonce ^{:doc "Global element registry. Survives REPL reload."}
  registry* (atom {}))

;; ─────────────────────────────────────────────────────────────────────
;; Schema for a registration map
;; ─────────────────────────────────────────────────────────────────────

(def ^:private size-schema
  [:map [:width :int] [:height :int]])

(def ^:private RegistrationSchema
  [:map
   [:render fn?]
   [:attrs :any]                                 ; can be a Malli vector schema or sugar map
   [:preferred-size {:optional true} fn?]
   [:min-size       {:optional true} size-schema]
   [:focusable?     {:optional true} :boolean]
   [:keymap         {:optional true} :map]
   [:stream-stable? {:optional true} :boolean]
   [:container?     {:optional true} :boolean]
   [:doc            {:optional true} :string]])

(def ^:private registration-validator
  (m/validator RegistrationSchema))

(def ^:private registration-explainer
  (m/explainer RegistrationSchema))

;; ─────────────────────────────────────────────────────────────────────
;; :attrs schema normalization
;; ─────────────────────────────────────────────────────────────────────

(defn- normalize-attrs-schema
  "Accepts:
     - a Malli vector schema (e.g. [:map ...] or [:vector :int]),
     - a bare Malli keyword schema (e.g. :any, :int, :string),
     - an existing compiled Malli schema, or
     - a sugar map of {key sub-schema, key {:optional? true :default v}}
       which is rewritten to [:map [k schema] ...].

   Returns a value suitable to pass to `m/validator`."
  [attrs]
  (cond
    (or (vector? attrs) (keyword? attrs) (m/schema? attrs))
    attrs

    (map? attrs)
    (into [:map]
          (map (fn [[k v]]
                 (cond
                   ;; metadata-style sugar: {:optional? true :default v}
                   (and (map? v) (or (contains? v :optional?) (contains? v :default)))
                   (let [props (cond-> {}
                                 (:optional? v) (assoc :optional true)
                                 (contains? v :default) (assoc :default (:default v)))]
                     [k props :any])
                   :else
                   [k v])))
          attrs)

    :else
    (throw (ex-info "Invalid :attrs schema" {:attrs attrs}))))

;; ─────────────────────────────────────────────────────────────────────
;; Defaults
;; ─────────────────────────────────────────────────────────────────────

(def ^:private default-preferred-size (constantly {:width 0 :height 0}))

(defn- with-defaults [registration]
  (merge {:preferred-size default-preferred-size
          :min-size       {:width 1 :height 1}
          :focusable?     false
          :keymap         {}
          :stream-stable? true
          :container?     false}
         registration))

;; ─────────────────────────────────────────────────────────────────────
;; Public API
;; ─────────────────────────────────────────────────────────────────────

(defn register-element!
  "Register an element under `tag` (a keyword). Validates the registration
   map at call-time; compiles the `:attrs` schema once and stores the
   validator alongside the entry. Double-registration of an existing tag
   logs a warning and replaces the entry (REPL workflow per §7.6.5)."
  [tag registration]
  (when-not (keyword? tag)
    (throw (ex-info "Element tag must be a keyword" {:tag tag})))
  (when-not (registration-validator registration)
    (throw (ex-info "Invalid element registration"
                    {:tag         tag
                     :explanation (registration-explainer registration)})))
  (let [attrs-schema (normalize-attrs-schema (:attrs registration))
        validator    (try
                       (m/validator attrs-schema)
                       (catch Exception e
                         (throw (ex-info "Invalid :attrs schema (Malli compile failed)"
                                         {:tag tag :attrs (:attrs registration)} e))))
        entry        (-> registration
                         with-defaults
                         (assoc :attrs           attrs-schema
                                :attrs-validator validator))]
    (when (contains? @registry* tag)
      (u/log ::element-redefined :tag tag))
    (swap! registry* assoc tag entry)
    tag))

(defmacro defelement
  "Sugar for `register-element!`. Mirrors Grain's `defquery`/`defcommand`
   house style.

       (defelement :sparkline
         {:doc \"Inline sparkline.\"
          :attrs {:data [:vector :number]}
          :render (fn [attrs box] ...)})"
  [tag registration]
  `(register-element! ~tag ~registration))

(defn lookup
  "Return the full registry entry for `tag` (including `:render` and
   `:attrs-validator`). Returns nil when `tag` is not registered.
   Used by the layout dispatcher; do not use this for introspection."
  [tag]
  (get @registry* tag))

(defn element-info
  "Public introspection per §7.6.5: returns the registration entry for
   `tag` with `:render` and the internal `:attrs-validator` removed.
   Returns nil when `tag` is not registered."
  [tag]
  (when-let [entry (get @registry* tag)]
    (dissoc entry :render :attrs-validator)))

(defn list-elements
  "Return a sorted set of every registered element keyword."
  []
  (into (sorted-set) (keys @registry*)))

(defn unregister!
  "Remove `tag` from the registry. Intended for tests; production code
   should not call this."
  [tag]
  (swap! registry* dissoc tag)
  tag)

(defn clear!
  "Remove every registration. Intended for tests."
  []
  (reset! registry* {})
  nil)
