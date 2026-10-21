(ns ai.obney.grain.event-model-validator.state-schema-test
  (:require [clojure.test :refer [deftest is]]
            [ai.obney.grain.event-model-validator.interface :as validator]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]))

(es/defevent :state-schema-test/observed "State schema fixture"
  {:schema [:map [:id :uuid] [:n :int]]})

(deftest topology-compares-keyed-state-and-ordinary-schema
  (let [state-schema [:map [:n :int]]
        names [:state-schema-test/keyed :state-schema-test/routed :state-schema-test/ordinary]
        definitions [{:key :id :state-schema state-schema :result-schema :string :result (comp str :n)}
                     {:route (fn [e] [[(:id e) e]]) :state-schema state-schema :result-schema :string :result (comp str :n)}
                     {:schema state-schema}]
        base {:state-schema-test
              {:events {:state-schema-test/observed
                        {:description "Observed" :schema [:map [:id :uuid] [:n :int]]}}
               :read-models (into {} (map (fn [name]
                                          [name {:description "State" :version 1
                                                 :consumes #{:state-schema-test/observed}
                                                 :schema state-schema}]) names))}}]
    (try
      (doseq [[name opts] (map vector names definitions)]
        (rmp/register-read-model! name (fn [_ event] {:n (:n event)})
                                  (merge {:events #{:state-schema-test/observed} :version 1} opts)))
      (let [matching (validator/validate-event-model base {:strict true})]
        (is (:valid? matching) (pr-str (:findings matching))))
      (doseq [name names]
        (let [mismatch (validator/validate-event-model
                        (assoc-in base [:state-schema-test :read-models name :schema] :string)
                        {:strict true})
              finding (first (filter #(and (= :schema/mismatch (:type %))
                                           (= name (:block %))) (:findings mismatch)))]
          (is (false? (:valid? mismatch)))
          (is (= state-schema (:live/schema finding)))
          (is (= :string (:spec/schema finding)))))
      (finally
        (swap! rmp/read-model-registry* #(apply dissoc % names))))))
