(ns ai.obney.grain.code-agent-tools.event-model-validation-test
  "Structural validation + guides tests for the service-area-first event model.

   Requiring example-base loads the example-service interface namespaces, whose
   def* macros populate the global registries at load time — so the validator
   runs against the LIVE :example area with no `install!`."
  (:require [ai.obney.grain.code-agent-tools.interface :as tools]
            [ai.obney.grain.event-model-validator.interface :as emv]
            [ai.obney.grain.example-base.core]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def sample
  "A correct event model for the live :example area. Deliberately exercises both
   :area/name collisions and includes a design-only screen + intent edges."
  {:example
   {:description "Counter service area."
    :commands
    {:example/create-counter
     {:description "Create a counter." :schema [:map [:name :string]]
      :reads #{:example/counters} :produces #{:example/counter-created}
      :given-when-thens [{:given "no counter named A" :when "create A" :then "A created"}]}
     :example/increment-counter
     {:description "Increment." :schema [:map [:counter-id :uuid]]
      :reads #{:example/counters} :produces #{:example/counter-incremented}}
     :example/decrement-counter
     {:description "Decrement." :schema [:map [:counter-id :uuid]]
      :reads #{:example/counters} :produces #{:example/counter-decremented}}
     :example/calculate-average-counter-value
     {:description "Average." :schema [:map]
      :reads #{:example/counters} :produces #{:example/average-calculated}}}
    :events
    {:example/counter-created     {:description "created" :schema [:map [:counter-id :uuid] [:name :string]]}
     :example/counter-incremented {:description "inc" :schema [:map [:counter-id :uuid]]}
     :example/counter-decremented {:description "dec" :schema [:map [:counter-id :uuid]]}
     :example/average-calculated  {:description "avg" :schema [:map [:value :double]]}}
    :read-models
    {:example/counters {:description "counters"
                        :consumes #{:example/counter-created
                                    :example/counter-incremented
                                    :example/counter-decremented}
                        :version 1}}
    :queries
    {:example/counters {:description "all" :schema [:map] :reads #{:example/counters}}
     :example/counter  {:description "one" :schema [:map [:counter-id :uuid]] :reads #{:example/counters}}}
    :todo-processors
    {:example/calculate-average-counter-value
     {:description "recompute avg" :subscribes #{:example/counter-incremented :example/counter-decremented}
      :produces #{:example/calculate-average-counter-value}}}
    :periodic-tasks
    {:example/example-periodic-task {:description "heartbeat" :schedule {:every 30 :duration :seconds}}}
    :screens
    {:example/dashboard {:description "dash" :queries #{:example/counters}
                         :commands #{:example/create-counter}}}
    :flows
    {:example/counter-lifecycle
     {:description "increment lifecycle"
      :steps [{:from [:screen :example/dashboard]          :to [:command :example/increment-counter]}
              {:from [:command :example/increment-counter] :to [:event :example/counter-incremented]}
              {:from [:event :example/counter-incremented] :to [:read-model :example/counters]}
              {:from [:read-model :example/counters]        :to [:query :example/counters]}
              {:from [:query :example/counters]             :to [:screen :example/dashboard]}]}}}})

(defn- types [v] (set (map :type (:findings v))))
(defn- errors [v] (filter #(= :error (:severity %)) (:findings v)))

(deftest valid-spec-matches-live-runtime
  (let [v (tools/validate-event-model sample)]
    (is (true? (:valid? v)))
    (is (empty? (errors v)) (str "unexpected errors: " (vec (errors v))))
    (is (true? (get-in v [:summary :runtime/registries-present?])))))

(deftest negatives-each-surface-the-right-finding
  (testing "spec block with no live handler -> :block/undeclared"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :commands :example/delete-counter]
                                     {:description "x" :schema [:map]})))
                   :block/undeclared)))
  (testing "live block missing from spec -> :block/uncovered"
    (is (contains? (types (tools/validate-event-model
                           (update-in sample [:example :commands] dissoc :example/decrement-counter)))
                   :block/uncovered)))
  (testing "unparseable schema -> :schema/malformed"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :commands :example/create-counter :schema]
                                     [:map [:name :totally-bogus-type]])))
                   :schema/malformed)))
  (testing "schema differs from live registry -> :schema/mismatch"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :commands :example/create-counter :schema]
                                     [:map [:name :int]])))
                   :schema/mismatch)))
  (testing "illegal CQRS flow connection -> :flow/illegal-connection"
    (is (contains? (types (tools/validate-event-model
                           (update-in sample [:example :flows :example/counter-lifecycle :steps]
                                      conj {:from [:event :example/counter-incremented]
                                            :to [:command :example/increment-counter]})))
                   :flow/illegal-connection)))
  (testing "a todo-processor's only legal input is a query (not event, not read-model)"
    (let [illegal? (fn [from]
                     (contains? (types (tools/validate-event-model
                                        (update-in sample [:example :flows :example/counter-lifecycle :steps]
                                                   conj {:from from
                                                         :to [:todo-processor :example/calculate-average-counter-value]})))
                                :flow/illegal-connection))]
      (is (illegal? [:event :example/counter-incremented]))   ; event -> todo-processor illegal
      (is (illegal? [:read-model :example/counters]))          ; read-model -> todo-processor illegal
      (is (not (illegal? [:query :example/counters])))))       ; query -> todo-processor legal
  (testing "read-models feed only commands and queries (not screens)"
    (let [illegal? (fn [step]
                     (contains? (types (tools/validate-event-model
                                        (update-in sample [:example :flows :example/counter-lifecycle :steps] conj step)))
                                :flow/illegal-connection))]
      (is (illegal? {:from [:read-model :example/counters] :to [:screen :example/dashboard]}))        ; read-model -> screen illegal
      (is (not (illegal? {:from [:read-model :example/counters] :to [:command :example/increment-counter]}))))) ; read-model -> command legal
  (testing "flow endpoint names a non-existent block -> :flow/dangling-reference"
    (is (contains? (types (tools/validate-event-model
                           (update-in sample [:example :flows :example/counter-lifecycle :steps]
                                      conj {:from [:event :example/nope] :to nil})))
                   :flow/dangling-reference)))
  (testing "read-model :consumes diverges from live -> :wiring/mismatch"
    (is (contains? (types (tools/validate-event-model
                           (update-in sample [:example :read-models :example/counters :consumes]
                                      conj :example/average-calculated)))
                   :wiring/mismatch)))
  (testing "block key namespace != area -> :block/misnamespaced"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :commands :other/oops]
                                     {:description "x" :schema [:map]})))
                   :block/misnamespaced)))
  (testing "intent edge of the wrong kind -> :ref/wrong-kind"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :screens :example/dashboard :queries]
                                     #{:example/create-counter})))
                   :ref/wrong-kind))))

(deftest production-edges-confirmed-against-def-site-annotations
  (testing "matching spec :produces/:reads confirm cleanly (example-service is annotated)"
    (let [t (types (tools/validate-event-model sample))]
      (is (not (contains? t :produces/mismatch)))
      (is (not (contains? t :reads/mismatch)))))
  (testing "spec :produces disagreeing with the runtime-declared edge -> :produces/mismatch"
    (is (contains? (types (tools/validate-event-model
                           (assoc-in sample [:example :commands :example/create-counter :produces]
                                     #{:example/counter-decremented})))
                   :produces/mismatch)))
  (testing "spec :reads disagreeing with the runtime-declared edge -> :reads/mismatch"
    (is (contains? (types (tools/validate-event-model
                           (update-in sample [:example :queries :example/counters] dissoc :reads)))
                   :reads/mismatch))))

(deftest malformed-model-short-circuits
  (let [v (tools/validate-event-model {:example {:commands {:not-qualified {:description "x" :schema [:map]}}}})]
    (is (false? (:valid? v)))
    (is (contains? (types v) :model/malformed))))

(deftest degrades-without-registries
  ;; A foreign area with no live counterpart: spec-internal checks still run,
  ;; live-comparison checks are skipped for blocks outside loaded areas.
  (let [v (tools/validate-event-model
           {:zzz {:commands {:zzz/do-thing {:description "x" :schema [:map]}}
                  :flows {:zzz/f {:description "bad"
                                  :steps [{:from [:event :zzz/nope] :to [:command :zzz/do-thing]}]}}}})]
    ;; event :zzz/nope does not exist and event->command is illegal: both caught spec-internally.
    (is (contains? (types v) :flow/dangling-reference))
    (is (contains? (types v) :flow/illegal-connection))))

(deftest file-and-resource-wrappers
  (testing "validate-event-model-file"
    (let [f (java.io.File/createTempFile "event-model" ".edn")]
      (try
        (spit f (pr-str sample))
        (is (true? (:valid? (tools/validate-event-model-file (.getAbsolutePath f)))))
        (finally (.delete f)))))
  (testing "committed example resource validates"
    (when-let [r (io/resource "example.event-model.edn")]
      (is (true? (:valid? (tools/validate-event-model (edn/read-string (slurp r)))))))))

(deftest flows-guide-is-generated-from-the-connection-grammar
  ;; Guards against the guide drifting from the enforcer: every adjacency in the
  ;; validator's connection-grammar must appear on the matching line of the
  ;; rendered :flows guide (matched at line start, padding-agnostic).
  (let [lines (str/split-lines (:body (tools/guide :flows)))
        line-for (fn [from] (some #(when (str/starts-with? (str/trim %) (str (name from) " ")) %) lines))]
    (doseq [[from tos] emv/connection-grammar]
      (let [line (line-for from)]
        (is (some? line) (str "flows guide has no adjacency line for " from))
        (doseq [to tos]
          (is (and line (str/includes? line (name to)))
              (str "flows guide is missing the edge " from " -> " to)))))
    (testing "the now-illegal event -> todo-processor edge is not presented as legal"
      (is (not (str/includes? (or (line-for :event) "") "todo-processor"))
          "flows guide still lists the stale event -> todo-processor edge"))))

(deftest guides-are-served-from-the-runtime
  (let [idx (:guides (tools/guides))]
    (is (some #(= :getting-started (:id %)) idx))
    (is (some #(= :example/create-counter (:id %)) idx))
    (is (some #(= :catalog (:id %)) idx)))
  (testing "concept guide"
    (is (= :getting-started (:id (tools/guide :getting-started))))
    (is (string? (:body (tools/guide :getting-started)))))
  (testing "live block usage card, enriched by the spec"
    (let [c (tools/guide :example/create-counter {:spec sample})]
      (is (= :block (:applies-to c)))
      (is (= :command (:kind c)))
      (is (string? (:doc c)))
      (is (= "Create a counter." (:spec/description c)))
      (is (contains? (:spec/produces c) :example/counter-created))))
  (testing "tool guide"
    (is (= :tool (:applies-to (tools/guide :catalog)))))
  (testing "unknown id"
    (is (= :nope (:guide/unknown (tools/guide :nope))))))
