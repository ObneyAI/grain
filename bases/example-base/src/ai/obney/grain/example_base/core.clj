(ns ai.obney.grain.example-base.core
  (:require [ai.obney.grain.command-request-handler.interface :as crh]
            [ai.obney.grain.query-request-handler.interface :as qrh]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.event-store-v2.interface :as es]
            [ai.obney.grain.event-store-postgres-v2.interface]
            [ai.obney.grain.webserver.interface :as ws]
            [ai.obney.grain.pubsub.interface :as ps]
            [ai.obney.grain.todo-processor.interface :as tp]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.event-notifier-postgres.interface :as event-notifier]
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
                  :conn {:type :in-memory ;; change to :postgres to try Postgres
                         ;; uncomment below to try Postgres
                         #_#_#_#_#_#_#_#_#_#_:server-name "localhost"
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

   ;; Control plane — commented out by default (single-instance mode).
   ;; Uncomment to enable multi-instance coordination.
   #_#_
   ::control-plane {:event-store (ig/ref ::event-store)
                    :cache nil ;; provide a kv-store for L2 cache
                    :processor-names [:example/calculate-average-counter-value]
                    :heartbeat-interval-ms 5000
                    :staleness-threshold-ms 15000
                    :strategy :round-robin}

   ;; Event notifier — commented out by default.
   ;; Requires Postgres event store for LISTEN/NOTIFY.
   #_#_
   ::event-notifier {:connection-config {:server-name "localhost"
                                         :port-number "5432"
                                         :username "postgres"
                                         :password "password"
                                         :database-name "obneyai"}
                     :event-pubsub (ig/ref ::event-pubsub)
                     :event-store (ig/ref ::event-store)}

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
                             :pretty? false})

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

(defmethod ig/init-key ::control-plane [_ config]
  (control-plane/start config))

(defmethod ig/halt-key! ::control-plane [_ cp]
  (control-plane/stop cp))

(defmethod ig/init-key ::event-notifier [_ {:keys [connection-config event-pubsub event-store]}]
  (event-notifier/start-listener
   {:connection-config connection-config
    :on-notification (fn [tenant-id-str]
                       (u/log ::event-notification :tenant-id tenant-id-str))}))

(defmethod ig/halt-key! ::event-notifier [_ listener]
  (event-notifier/stop-listener listener))

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