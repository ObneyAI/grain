(ns ai.obney.grain.example-base.core
  (:require [ai.obney.grain.command-request-handler.interface :as crh]
            [ai.obney.grain.query-request-handler.interface :as qrh]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.event-store-v2.interface :as es] 
            [ai.obney.grain.event-store-postgres-v2.interface]
            [ai.obney.grain.webserver.interface :as ws]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.todo-processor.interface :as tp]
            [ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface :as cloudwatch-emf]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [nrepl.server :as nrepl]

            [ai.obney.grain.example-service.interface
             [commands :as commands]
             [queries :as queries]
             [todo-processors :as todo-processors]
             [periodic-tasks :as periodic-tasks]
             [schemas]]))

;; --------------------- ;;
;; Service Configuration ;;
;; --------------------- ;;

;;
;; This will be deleted later, just for testing ;;
;;


(def system
  {::logger {}
   ::event-store {:logger (ig/ref ::logger)
                  :event-pubsub (ig/ref ::event-pubsub)
                  :conn {:type :postgres
                         :server-name "localhost"
                         :port-number "5433"
                         :username "postgres"
                         :password "password"
                         :database-name "obneyai"}}

   ::event-pubsub {:type :core-async
                   :topic-fn :event/type}

   ::example-periodic-task
   {:handler-fn #'periodic-tasks/example-periodic-task
    :schedule {:every 30 :duration :seconds}
    :context (ig/ref ::context)
    :task-name ::example-periodic-task}

   ::calculate-average-counter-value-todo-processor
   {:event-pubsub (ig/ref ::event-pubsub)
    :topics [:example/counter-incremented :example/counter-decremented]
    :handler-fn #'todo-processors/calculate-average-counter-value
    :context (ig/ref ::context)}

   ::context {:event-store (ig/ref ::event-store)
              :command-registry commands/commands
              :query-registry queries/queries
              :event-pubsub (ig/ref ::event-pubsub)}

   ::routes {:context (ig/ref ::context)}

   ::webserver {:http/routes (ig/ref ::routes)
                :http/port 8080
                :http/join? false}

   ::nrepl {:bind "0.0.0.0" :port 7888}})



;; -------------- ;;
;; Integrant Keys ;;
;; -------------- ;;

(defmethod ig/init-key ::logger [_ _]
  (let [console-pub-stop-fn
        (u/start-publisher! {:type :console-json
                             :pretty? false
                             :transform
                             #(map (fn [log] (case (:mulog/event-name log)
                                               :ai.obney.event-store.core.postgres/storing-events
                                               (update log
                                                       :ai.obney.event-store.core.postgres/events
                                                       (fn [events]
                                                         (map (fn [e]
                                                                (cond-> e
                                                                  (:embedding e) (assoc :embedding :truncated)
                                                                  (:access-token e) (assoc :access-token :truncated)
                                                                  (:text e) (assoc :text :truncated)))
                                                              events)))
                                               log))
                                   %)})

        cloudwatch-emf-pub-stop-fn
        (u/start-publisher!
         {:type :custom
          :fqn-function #'cloudwatch-emf/cloudwatch-emf-publisher})]
    (fn []
      (console-pub-stop-fn)
      (cloudwatch-emf-pub-stop-fn))))

(defmethod ig/halt-key! ::logger [_ stop-fn]
  (stop-fn))

(defmethod ig/init-key ::event-store [_ config]
  (es/start config))

(defmethod ig/halt-key! ::event-store [_ event-store]
  (es/stop event-store))

(defmethod ig/init-key ::event-pubsub [_ config]
  (ps/start config))

(defmethod ig/halt-key! ::event-pubsub [_ event-pubsub]
  (ps/stop event-pubsub))

(defmethod ig/init-key ::calculate-average-counter-value-todo-processor [_ config]
  (tp/start config))

(defmethod ig/halt-key! ::calculate-average-counter-value-todo-processor [_ todo-processor]
  (tp/stop todo-processor))

(defmethod ig/init-key ::example-periodic-task [_ config]
  (pt/start
   {:handler-fn (partial (:handler-fn config) (:context config))
    :schedule (:schedule config)
    :task-name (:task-name config)}))

(defmethod ig/halt-key! ::example-periodic-task [_ task]
  (pt/stop task))

(defmethod ig/init-key ::context [_ context]
  context)

(defmethod ig/init-key ::routes [_ {:keys [context]}]
  (set/union
   (crh/routes context)
   (qrh/routes context)
   #{["/healthcheck" :get [(fn [_] {:status 200 :body "OK"})] :route-name ::healthcheck]}))

(defmethod ig/init-key ::webserver [_ config]
  (ws/start config))

(defmethod ig/halt-key! ::webserver [_ webserver]
  (ws/stop webserver))

(defmethod ig/init-key ::nrepl [_ config]
  (nrepl/start-server config))

(defmethod ig/halt-key! ::nrepl [_ server]
  (nrepl/stop-server server))

;; ------------------- ;;
;; Lifecycle functions ;;
;; ------------------- ;;

(defn start
  []
  (u/set-global-context!
   {:app-name "example-app" :env "dev"})
  (ig/init system))

(defn stop
  [rag-service]
  (ig/halt! rag-service))

;; -------------- ;;
;; Runtime System ;;
;; -------------- ;;

(defonce app (atom {}))

(defn -main
  [& _]
  (reset! app (start))
  (u/log ::app-started)
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. #(do
                                (u/log ::stopping-app)
                                (stop @app)))))

(comment
  
  (def app (start))
  
  
  
  "")