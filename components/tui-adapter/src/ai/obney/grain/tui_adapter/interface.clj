(ns ai.obney.grain.tui-adapter.interface
  "Public API for the Grain TUI adapter.

   Most consumers only need three things:
     - `defelement` / `register-element!` to register cell-rendering
       elements (§7.6).
     - The session registry helpers (`sessions-on-screen`, `refresh-screen!`,
       etc.) for OUT-OF-BAND operations like cross-screen toasts and
       admin-tool force-refresh. Routine refresh is automatic via the
       per-screen pubsub subscription (§4.1).
     - The Integrant component keys (in `ai.obney.grain.tui-adapter.system`).

   Lower-level functions in `ai.obney.grain.tui-adapter.cells`,
   `.layout`, `.diff`, `.ansi`, `.session`, `.stream`, and `.overlay`
   are public-ish (they're used by the test suite and by adapter
   internals), but applications should rarely need them."
  (:require [ai.obney.grain.tui-adapter.builtins]      ; ensure built-ins register
            [ai.obney.grain.tui-adapter.cells :as cells]
            [ai.obney.grain.tui-adapter.element-registry :as er]
            [ai.obney.grain.tui-adapter.session :as session]))

;; ─────────────────────────────────────────────────────────────────────
;; Element registration (§7.6)
;; ─────────────────────────────────────────────────────────────────────

(defn register-element!
  "Register a cell-rendering element. See §7.6.2 for the registration
   map shape."
  [tag registration]
  (er/register-element! tag registration))

(defmacro defelement
  "Sugar for `register-element!`. House style mirrors `defquery`/`defcommand`."
  [tag registration]
  `(er/register-element! ~tag ~registration))

(defn list-elements
  "Sorted set of every registered element keyword (built-ins + app)."
  []
  (er/list-elements))

(defn element-info
  "Registration map for `tag`, with the internal `:render` and
   `:attrs-validator` removed. Returns nil when `tag` isn't registered."
  [tag]
  (er/element-info tag))

;; ─────────────────────────────────────────────────────────────────────
;; CellGrid constructors (§7.6.1) — re-exported for convenience
;; ─────────────────────────────────────────────────────────────────────

(def blank      cells/blank)
(def text-row   cells/text-row)
(def stack      cells/stack)
(def beside     cells/beside)
(def overlay    cells/overlay)
(def with-style cells/with-style)
(def clip       cells/clip)

;; ─────────────────────────────────────────────────────────────────────
;; Session registry — OUT-OF-BAND operations only (§4.1)
;; ─────────────────────────────────────────────────────────────────────

(defn sessions-on-screen
  "Return all sessions whose `:current-screen.query-id` matches
   `query-name`. Optional `inputs` further restricts to sessions whose
   inputs equal the supplied map."
  ([registry query-name]
   (->> @(:sessions-atom registry)
        vals
        (filter (fn [s] (= query-name (get-in @s [:current-screen :query-id]))))))
  ([registry query-name inputs]
   (->> @(:sessions-atom registry)
        vals
        (filter (fn [s]
                  (let [scr (:current-screen @s)]
                    (and (= query-name (:query-id scr))
                         (= inputs     (:inputs scr)))))))))

(defn sessions-for-tenant
  [registry tenant-id]
  (->> @(:sessions-atom registry)
       vals
       (filter (fn [s] (= tenant-id (:tenant-id @s))))))

(defn sessions-for-user
  [registry user-id]
  (->> @(:sessions-atom registry)
       vals
       (filter (fn [s] (= user-id (:user-id @s))))))

(defn refresh-screen!
  "Force the session to re-render. Idempotent — multiple bursts collapse
   into a single render via the input channel's coalescing semantics."
  [session]
  (when-let [in (:input-ch @session)]
    (clojure.core.async/offer! in {:type :synthetic :reason :refresh})))

(defn broadcast-toast!
  "Add a toast overlay to `session`. `opts` per `:toast` session-action
   (`:message`, `:level :info|:warn|:error`, `:ttl-ms`)."
  [session opts]
  (session/handle-session-action session :toast opts {}))
