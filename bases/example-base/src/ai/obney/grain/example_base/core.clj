(ns ai.obney.grain.example-base.core
  "Example grain app base, wired on the current macro stack.

   Handlers are defined in components/example-service with the macro
   system (`defcommand`, `defquery`, `defreadmodel`, `defprocessor`,
   `defperiodic`) and register themselves in global registries when their
   namespaces load — so this base only requires the example-service
   interface namespaces and wires the runtime: an in-memory event-store-v3,
   an LMDB read-model cache, the HTTP request handlers, a standalone tenant
   poller for processors (no control plane), and the periodic triggers.

   The app is single-tenant: every event-store append/read, projection,
   poll, and periodic tick is scoped to `service-schemas/example-tenant-id`."
  (:require [ai.obney.grain.command-request-handler-v2.interface :as crh]
            [ai.obney.grain.query-request-handler.interface :as qrh]
            [ai.obney.grain.periodic-task.interface :as pt]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface]
            [ai.obney.grain.webserver.interface :as ws]
            [ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface :as cloudwatch-emf]
            [clojure.set :as set]
            [com.brunobonacci.mulog :as u]
            [integrant.core :as ig]
            [nrepl.server :as nrepl]

            ;; Requiring the example-service interface namespaces loads their
            ;; core namespaces, whose def* macros register the handlers.
            [ai.obney.grain.example-service.interface
             [commands]
             [queries]
             [todo-processors]
             [periodic-tasks]
             [schemas :as service-schemas]]))

;; --------------------- ;;
;; Service Configuration ;;
;; --------------------- ;;

(def system
  {::logger {}

   ::event-store {:conn {:type :in-memory
                         ;; To try Postgres instead, add a dep on
                         ;; event-store-postgres-v3, require its interface
                         ;; namespace, and use:
                         #_#_#_#_#_#_#_#_#_#_:type :postgres
                         :server-name "localhost"
                         :port-number "5432"
                         :username "postgres"
                         :password "password"
                         :database-name "obneyai"}}

   ::cache {}

   ::context {:event-store (ig/ref ::event-store)
              :cache (ig/ref ::cache)
              :tenant-id service-schemas/example-tenant-id}

   ;; Standalone tenant poller — runs every registered defprocessor for the
   ;; single example tenant. No control plane / pubsub.
   ::processors {:event-store (ig/ref ::event-store)
                 :cache (ig/ref ::cache)
                 :tenant-id service-schemas/example-tenant-id}

   ;; Runs every registered defperiodic trigger on its schedule.
   ::periodic-triggers {:event-store (ig/ref ::event-store)
                        :tenant-id service-schemas/example-tenant-id}

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

(defmethod ig/init-key ::cache [_ _]
  (kv/start
   {:type :lmdb
    :storage-dir (str "/tmp/grain-example-" (random-uuid))
    :db-name "example"}))

(defmethod ig/halt-key! ::cache [_ cache]
  (kv/stop cache))

(defmethod ig/init-key ::context [_ context]
  context)

(defmethod ig/init-key ::processors [_ {:keys [event-store cache tenant-id]}]
  (tp/start-tenant-poller
   {:event-store event-store
    :tenant-ids #{tenant-id}
    ;; Merged into each handler's context (the poller injects
    ;; :event-store/:tenant-id/:event itself); :cache is needed so the
    ;; processor's command can project the read model.
    :context {:cache cache}
    :poll-interval-ms 250}))

(defmethod ig/halt-key! ::processors [_ poller]
  (tp/stop-tenant-poller poller))

(defmethod ig/init-key ::periodic-triggers [_ {:keys [event-store tenant-id]}]
  (pt/start-periodic-triggers!
   {:append-fn (partial es/append event-store)
    :tenant-ids-fn (constantly #{tenant-id})}))

(defmethod ig/halt-key! ::periodic-triggers [_ triggers]
  (pt/stop-periodic-triggers! triggers))

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
  [app]
  (ig/halt! app))

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

  (stop app)

  "")
