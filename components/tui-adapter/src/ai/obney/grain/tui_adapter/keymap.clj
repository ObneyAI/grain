(ns ai.obney.grain.tui-adapter.keymap
  "Layered keymap resolution and tagged-tuple dispatch.

   Per §10.3, on each input event the adapter walks the keymap stack
   in priority order:
     1. Active overlay keymap
     2. Focused region keymap
     3. Screen keymap
     4. Session keymap
     5. Global (substrate-provided) defaults
   First match wins. Sequence chords (e.g. `[\"g\" \"g\"]`) hold for a
   timeout (default 1 s) before reset.

   Keymap values are tagged tuples (§10.2):
     [:command name]            issue Grain command, no inputs
     [:command name opts]       issue Grain command with opts
     [:session  action]         invoke session action
     [:session  action opts]    invoke session action with opts

   Tags other than `:command` and `:session` are errors.")

;; ─────────────────────────────────────────────────────────────────────
;; Layer composition
;; ─────────────────────────────────────────────────────────────────────

(defn build-stack
  "Build a keymap stack from the named layers in priority order. Nil
   layers are filtered out. Returns a vector of keymap maps."
  [{:keys [overlay region screen session global]}]
  (vec (remove nil? [overlay region screen session global])))

;; ─────────────────────────────────────────────────────────────────────
;; Action validation
;; ─────────────────────────────────────────────────────────────────────

(def ^:private valid-tags #{:command :session})

(defn valid-action?
  "Tagged-tuple actions per §10.2: [:command name opts?] or
   [:session action opts?]. Returns true when the action is well-formed."
  [action]
  (and (vector? action)
       (>= (count action) 2)
       (<= (count action) 3)
       (contains? valid-tags (first action))
       (keyword? (second action))
       (or (= 2 (count action))
           (map? (nth action 2)))))

(defn assert-action!
  "Throws ex-info when `action` is not a valid tagged tuple."
  [action]
  (when-not (valid-action? action)
    (throw (ex-info "Invalid keymap action — must be tagged tuple [:command|:session name opts?]"
                    {:action action})))
  action)

;; ─────────────────────────────────────────────────────────────────────
;; Key resolution against a single layer
;; ─────────────────────────────────────────────────────────────────────

(defn- resolve-in-layer
  "Look up `key-or-seq` in `layer`. `key-or-seq` is either a single key
   string or a vector chord. Returns the matched action, or nil."
  [layer key-or-seq]
  (when (map? layer)
    (let [k (if (and (vector? key-or-seq) (= 1 (count key-or-seq)))
              (first key-or-seq)
              key-or-seq)]
      (or (get layer key-or-seq)
          (when-not (= k key-or-seq) (get layer k))))))

;; ─────────────────────────────────────────────────────────────────────
;; Sequence-aware resolver
;; ─────────────────────────────────────────────────────────────────────

(defn- has-prefix?
  "True when `layer` contains any binding whose key vector starts with
   `prefix` (a vector of keys). Used to decide whether to keep buffering
   on chord input."
  [layer prefix]
  (boolean
    (and (map? layer)
         (some (fn [k]
                 (and (vector? k)
                      (>= (count k) (count prefix))
                      (= prefix (subvec k 0 (count prefix)))))
               (keys layer)))))

(defn resolve-key
  "Given a `stack` (vector of keymap layers, top first), a `sequence-buffer`
   (vector of keys awaiting completion) and an incoming `key`, return:

     {:state :match     :action [...]      :buffer []}        single key matched
     {:state :match     :action [...]      :buffer []}        chord completed
     {:state :pending   :buffer [...]}                         chord building
     {:state :no-match  :buffer []}                            unrecognized

   First match wins across layers. Chord ambiguity is resolved by the
   :pending state — caller holds the buffer until either a completing
   key arrives or a timeout elapses (default 1s)."
  [stack sequence-buffer key]
  (let [candidate (conj (or sequence-buffer []) key)
        ;; Search layers for an exact match against `candidate` (chord)
        ;; or against `key` (single, only when buffer empty).
        match     (some (fn [layer]
                          (or (resolve-in-layer layer candidate)
                              (when (empty? sequence-buffer)
                                (resolve-in-layer layer key))))
                        stack)
        ;; Even without a match, check whether any layer has a binding
        ;; whose prefix matches `candidate` — i.e. we should keep buffering.
        prefix?   (some (fn [layer] (has-prefix? layer candidate)) stack)]
    (cond
      match
      {:state :match :action match :buffer []}

      prefix?
      {:state :pending :buffer candidate}

      :else
      {:state :no-match :buffer []})))

;; ─────────────────────────────────────────────────────────────────────
;; Input derivation for :command actions (§10.2)
;; ─────────────────────────────────────────────────────────────────────

(defn build-inputs
  "Build the `:inputs` map for a [:command name opts] action, given
   `session-state` (a map of `:focus`, `:selection`, `:input-area`).

   `opts` may contain:
     :inputs                 literal map merged into result.
     :inputs-from-selection  binds the current selection's value at this
                             key into the inputs map under the same key.
     :inputs-from-focus      binds the focused region's identity to this key.
     :inputs-from-prompt     binds the input-area's submitted value to this key."
  [opts session-state]
  (let [{:keys [inputs inputs-from-selection inputs-from-focus inputs-from-prompt]} opts
        base inputs
        with-sel (if inputs-from-selection
                   (assoc base inputs-from-selection (:selection session-state))
                   base)
        with-foc (if inputs-from-focus
                   (assoc with-sel inputs-from-focus (:focus session-state))
                   with-sel)
        with-pr  (if inputs-from-prompt
                   (assoc with-foc inputs-from-prompt (:input-area session-state))
                   with-foc)]
    with-pr))

;; ─────────────────────────────────────────────────────────────────────
;; Dispatch
;; ─────────────────────────────────────────────────────────────────────

(defmulti dispatch!
  "Dispatch a tagged-tuple action. Methods are dispatched on the tag.
   `ctx` is the session context map; `action` is a validated tagged tuple.

   `:command`-tagged actions invoke the configured `:command-dispatcher`
   in `ctx` (a function `(name inputs ctx) -> result`); the test harness
   substitutes a mock that records calls.

   `:session`-tagged actions invoke the configured `:session-dispatcher`
   (a function `(action opts ctx) -> result`)."
  (fn [_ctx [tag _name _opts]] tag))

(defmethod dispatch! :command [{:keys [command-dispatcher session-state] :as ctx}
                               [_ name opts]]
  (let [opts*  (or opts {})
        inputs (build-inputs opts* session-state)
        result (command-dispatcher name inputs ctx)]
    ;; Context-sensitive bindings: when the command reports it had nothing
    ;; to do (result carries truthy :keymap/fallthrough?), dispatch the
    ;; binding's :fallback action instead. Lets one key mean "cancel the
    ;; running thing, else quit" without the adapter knowing the domain.
    (if (and (:fallback opts*) (:keymap/fallthrough? result))
      (dispatch! ctx (assert-action! (:fallback opts*)))
      result)))

(defmethod dispatch! :session [{:keys [session-dispatcher] :as ctx}
                               [_ action opts]]
  (session-dispatcher action (or opts {}) ctx))

(defmethod dispatch! :default [_ctx action]
  (throw (ex-info "Unknown action tag" {:action action})))
