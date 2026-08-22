(ns ai.obney.grain.event-store-v3.interface.event-definition
  "Opt-in, registration-only event definitions. Definitions describe event
   identity, payload schema, documentation and history availability; they never
   construct events or activate retention."
  (:require [ai.obney.grain.schema-util.interface :as schema]
            [malli.core :as m])
  (:import [java.time Duration]
           [java.time.format DateTimeParseException]))

(defonce ^:private registry* (atom {}))

(def ^:private fixed-duration-pattern
  ;; The deliberately narrow ISO-8601 PnDTnHnMnS subset. At least one component
  ;; is required; fractional seconds are accepted because java.time.Duration
  ;; normalizes them without losing precision.
  #"^P(?=\d+D|T)(?:\d+D)?(?:T(?=\d+(?:H|M|(?:\.\d+)?S))(?:\d+H)?(?:\d+M)?(?:\d+(?:\.\d+)?S)?)?$")

(defn normalize-duration
  "Validate and normalize a positive fixed ISO-8601 duration string to data.
   Calendar units, weeks, signs, zero and non-standard forms are rejected."
  [value]
  (when-not (and (string? value) (re-matches fixed-duration-pattern value))
    (throw (ex-info "Retention duration must be a positive fixed ISO-8601 duration"
                    {:duration value})))
  (try
    (let [duration (Duration/parse value)]
      (when (or (.isZero duration) (.isNegative duration))
        (throw (ex-info "Retention duration must be positive" {:duration value})))
      {:seconds (.getSeconds duration)
       :nanos (.getNano duration)})
    (catch DateTimeParseException cause
      (throw (ex-info "Invalid retention duration" {:duration value} cause)))))

(defn normalize-history
  "Validate and canonicalize a bounded history policy. Returns nil for complete
   history. The result is inert EDN suitable for durable value comparison."
  [history]
  (when history
    (when-not (map? history)
      (throw (ex-info "Event history policy must be a map" {:history history})))
    (let [allowed #{:retain-at-least :keep-latest-per}
          unknown (seq (remove allowed (keys history)))
          duration (:retain-at-least history)
          keep-latest (:keep-latest-per history)]
      (when unknown
        (throw (ex-info "Unknown event history policy keys"
                        {:history history :unknown-keys (set unknown)})))
      (when-not (contains? history :retain-at-least)
        (throw (ex-info "Bounded history requires :retain-at-least"
                        {:history history})))
      (when (and keep-latest
                 (or (not (map? keep-latest))
                     (not= #{:tags} (set (keys keep-latest)))
                     (not (set? (:tags keep-latest)))
                     (empty? (:tags keep-latest))
                     (not-every? keyword? (:tags keep-latest))))
        (throw (ex-info ":keep-latest-per requires a non-empty set of tag names"
                        {:history history})))
      (cond-> {:retain-at-least (normalize-duration duration)}
        keep-latest (assoc :keep-latest-per {:tags (:tags keep-latest)})))))

(defn- validate-schema! [event-type event-schema]
  (when-not event-schema
    (throw (ex-info "Event definition requires :schema" {:event/type event-type})))
  (try
    (m/schema event-schema)
    (catch Exception cause
      (throw (ex-info "Event definition schema is malformed"
                      {:event/type event-type :schema event-schema}
                      cause)))))

(defn register-event-definition!
  "Register one event definition. Identical semantic registration is idempotent;
   a different definition for the same type is rejected."
  [event-type description {:keys [schema history] :as options} source]
  (when-not (qualified-keyword? event-type)
    (throw (ex-info "Event type must be a qualified keyword" {:event/type event-type})))
  (when-not (and (string? description) (not-empty description))
    (throw (ex-info "Event definition requires a non-empty description"
                    {:event/type event-type})))
  (when-not (map? options)
    (throw (ex-info "Event definition options must be a map"
                    {:event/type event-type :options options})))
  (let [unknown (seq (remove #{:schema :history} (keys options)))]
    (when unknown
      (throw (ex-info "Unknown event definition options"
                      {:event/type event-type :unknown-keys (set unknown)}))))
  (validate-schema! event-type schema)
  (let [definition {:event/type event-type
                    :description description
                    :schema schema
                    :history history
                    :history/normalized (normalize-history history)
                    :definition/source source}
        semantic (dissoc definition :definition/source)]
    (swap! registry*
           (fn [registry]
             (if-let [existing (get registry event-type)]
               (if (= semantic (dissoc existing :definition/source))
                 registry
                 (throw (ex-info "Conflicting event definition registration"
                                 {:event/type event-type
                                  :existing existing
                                  :candidate definition})))
               (assoc registry event-type definition))))
    (schema/register! {event-type schema})
    event-type))

(defmacro defevent
  "Register an event definition without changing event construction."
  [event-type description options]
  (let [source {:ns (str *ns*)
                :file *file*
                :line (:line (meta &form))}]
    `(register-event-definition! ~event-type ~description ~options ~source)))

(defn event-definition [event-type]
  (get @registry* event-type))

(defn event-definitions
  "Return an immutable snapshot of all registered definitions."
  []
  @registry*)

(defn reset-event-definitions!
  "Test-only registry reset. Public so isolated component tests need no private
   var access; production code should never call it."
  []
  (reset! registry* {}))
