(ns ai.obney.grain.behavior-tree-v2-dscloj-extensions.core
  (:require [ai.obney.grain.behavior-tree-v2.interface :as bt2]
            [dscloj.core :as dscloj]
            [com.brunobonacci.mulog :as u]))

(defn dscloj [{{:keys [id provider module options]} :opts
             :keys [st-memory]
             :as _context}]
  (let [input-keys (mapv :name (:inputs module))]
    (try
      (let [state @st-memory
            inputs (reduce (fn [acc key]
                             (let [value (get state key)]
                               (if value
                                 (assoc acc key value)
                                 acc)))
                           {} input-keys)]
        (if inputs
          (let [result (dscloj/predict provider module inputs options)]
            (doseq [[output-key output-value] result]
              (swap! st-memory assoc output-key output-value))
            (println (str "✓ " id) "completed successfully")
            bt2/success)
          (do
            (println (str "✗ " id " - missing inputs: " (pr-str input-keys)))
            bt2/failure)))
      (catch Exception e
        (println (str "✗ " id " error:") (.getMessage e))
        bt2/failure))))

(comment

  (dscloj/quick-setup!)

  (dscloj/list-providers)

  (def qa-module
    {:inputs [{:name :question
               :spec :string
               :description "The question to answer"}]
     :outputs [{:name :answer
                :spec :string
                :description "The answer to the question"}]
     :instructions "Provide concise and accurate answers."})

  (dscloj/predict :openrouter qa-module {:questio "Hello there"})


  (do (def st-memory (atom {:question "How old is baton rouge"}))

      (dscloj
       {:opts {:id :test
               :provider :openrouter
               :module qa-module}
        :st-memory st-memory})
      
      @st-memory)
  
  ""
  )