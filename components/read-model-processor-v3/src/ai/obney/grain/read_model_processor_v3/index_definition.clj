(ns ai.obney.grain.read-model-processor-v3.index-definition
  (:require [malli.core :as m]))

(defn invalid!
  [model message data]
  (throw (ex-info (str model ": " message)
                  (merge {:error :invalid-indexed-definition :read-model model} data))))

(defn- scalar-type
  [schema]
  (let [t (m/type (m/deref-all schema))]
    (when (#{:string :keyword :int :uuid} t)
      t)))

(defn- field-schema
  [model index schema path]
  (loop [s schema
         remaining path]
    (if (empty? remaining)
      (m/deref-all s)
      (let [s (m/deref-all s)
            entry (when (= :map (m/type s))
                    (find (into {} (m/entries s)) (first remaining)))
            field (second entry)]
        (when (or (nil? field) (:optional (m/properties field)))
          (invalid! model "Index path must name required schema fields"
                    {:index index
                     :path path
                     :expected :required-field
                     :actual (m/form s)}))
        (recur (first (m/children field)) (rest remaining))))))

(defn compile-definition
  "One projection model. Schemas are optional; secondary indexes require a
   map-of schema with string/UUID keys and required scalar indexed fields."
  [model opts]
  (doseq [option [:kind :storage :record-schema :record-id-type :record-ids
                  :l1-ttl-ms :l1-max-entries :cache-mode :checkpoint-threshold
                  :segment-threshold :segment-count]]
    (when (contains? opts option)
      (invalid! model "v3 has one projection model; remove obsolete option" {:option option})))
  (when-not (and (integer? (:version opts 1)) (<= 1 (:version opts 1) Long/MAX_VALUE))
    (invalid! model "Use a positive signed 64-bit version" {:option :version}))
  (when (and (contains? opts :events)
             (not (and (set? (:events opts)) (every? keyword? (:events opts)))))
    (invalid! model "Use a set of event types" {:option :events}))
  (when (and (:partition-fn opts) (not (ifn? (:partition-fn opts))))
    (invalid! model "partition-fn must be callable" {:option :partition-fn}))
  (when-not (map? (:indexes opts {}))
    (invalid! model "Supply an indexes map" {:option :indexes}))
  (let [schema (when (:schema opts)
                 (try
                   (m/deref-all (m/schema (:schema opts)))
                   (catch Exception _
                     (invalid! model "Supply a valid state schema" {:option :schema}))))
        map-of? (= :map-of (some-> schema
                                   m/type))
        [id-schema record-schema] (when map-of?
                                    (m/children schema))
        id-type (when id-schema
                  (scalar-type id-schema))]
    (when (and (seq (:indexes opts)) (not (and map-of? (#{:string :uuid} id-type))))
      (invalid! model "Indexes require a map-of schema with string or UUID keys" {:option :schema}))
    {:id-type id-type
     :id-valid? (when id-schema
                  (m/validator id-schema))
     :record-valid? (when record-schema
                      (m/validator record-schema))
     :state-valid? (when schema
                     (if map-of?
                       (let [{:keys [min max]} (m/properties schema)]
                         (fn [state]
                           (and (map? state)
                                (or (nil? min) (<= min (count state)))
                                (or (nil? max) (<= (count state) max)))))
                       (m/validator schema)))
     :indexes
     (into {} (for [[index {:keys [fields] :as definition}] (:indexes opts)]
                (do
                  (when-not (and (keyword? index) (map? definition) (= #{:fields} (set (keys definition)))
                                 (vector? fields) (<= 1 (count fields) 6))
                    (invalid! model "Index requires one to six fields (scope and ID complete the native tuple)"
                              {:index index :option :fields :actual definition}))
                  [index (mapv
                          (fn [field]
                            (let [path (if (keyword? field)
                                         [field]
                                         field)]
                              (when-not (and (vector? path) (seq path) (every? keyword? path))
                                (invalid! model "Use a field keyword or keyword path" {:index index :path path}))
                              (let [s (field-schema model index record-schema path)
                                    t (scalar-type s)]
                                (when-not t
                                  (invalid! model "Index fields require non-null string, keyword, integer or UUID schemas"
                                            {:index index :path path :actual (m/form s)}))
                                {:path path :type t :valid? (m/validator s)}))) fields)])))}))
