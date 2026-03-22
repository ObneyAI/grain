(ns ai.obney.grain.control-plane-test-base.core
  "Minimal test app for live-testing the control plane with two instances.
   Each instance connects to shared Postgres, runs the control plane,
   and processes events. The test processor records which node handled
   each event for observability."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.event-store-postgres-v3.interface]
            [ai.obney.grain.pubsub.interface :as pubsub]
            [ai.obney.grain.control-plane.interface :as control-plane]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.kv-store-lmdb.interface :as lmdb]
            [ai.obney.grain.schema-util.interface :refer [defschemas]]
            [com.brunobonacci.mulog :as u]
            [nrepl.server :as nrepl]
            [clj-uuid :as uuid]))

;; ------------------- ;;
;; Schemas             ;;
;; ------------------- ;;

(defschemas test-schemas
  {:test/counter-incremented [:map]
   :test/counter-processed [:map
                            [:processed-by/node-id :uuid]
                            [:processed-by/event-id :uuid]]})

;; ------------------- ;;
;; Processor           ;;
;; ------------------- ;;

(defonce node-id-atom (atom nil))

(defn counter-processor-handler
  "Processes :test/counter-incremented events by appending a
   :test/counter-processed event tagged with this node's ID."
  [{:keys [event event-store tenant-id]}]
  (let [node-id @node-id-atom]
    (u/log ::processing-event :node-id node-id :event-id (:event/id event) :tenant-id tenant-id)
    {:result/events
     [(es/->event {:type :test/counter-processed
                   :body {:processed-by/node-id node-id
                          :processed-by/event-id (:event/id event)}})]}))

;; Register the processor so the control plane can discover it
(tp/register-processor! :test/counter-processor
  {:topics [:test/counter-incremented]
   :handler-fn #'counter-processor-handler})

;; ------------------- ;;
;; System              ;;
;; ------------------- ;;

(defn pg-config []
  {:server-name   (or (System/getenv "PG_HOST") "localhost")
   :port-number   (or (System/getenv "PG_PORT") "5432")
   :username      (or (System/getenv "PG_USER") "postgres")
   :password      (or (System/getenv "PG_PASSWORD") "password")
   :database-name (or (System/getenv "PG_DATABASE") "obneyai")})

(defn start
  "Start the test app. Returns a system map."
  []
  (u/set-global-context! {:app-name "control-plane-test"})
  (let [console-stop (u/start-publisher! {:type :console-json :pretty? true})
        nrepl-port (Integer/parseInt (or (System/getenv "NREPL_PORT") "7888"))
        cache-dir (str "/tmp/grain-cp-test-" (uuid/v4))

        ;; Core infrastructure
        event-pubsub (pubsub/start {:type :core-async :topic-fn :event/type})
        event-store (es/start {:conn (assoc (pg-config) :type :postgres)
                               :event-pubsub event-pubsub})
        cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir cache-dir :db-name "cp-test"}))

        ;; Control plane
        cp (control-plane/start {:event-store event-store
                                 :cache cache
                                 :event-pubsub event-pubsub
                                 :processor-names [:test/counter-processor]
                                 :heartbeat-interval-ms 2000
                                 :staleness-threshold-ms 6000})

        ;; nREPL for live interaction
        nrepl-server (nrepl/start-server :bind "0.0.0.0" :port nrepl-port)]

    ;; Store node-id for the processor handler
    (reset! node-id-atom (:node-id cp))

    (u/log ::started :node-id (:node-id cp) :nrepl-port nrepl-port)
    (println (str "Node " (:node-id cp) " started. nREPL on port " nrepl-port))

    {:event-store event-store
     :event-pubsub event-pubsub
     :cache cache
     :cache-dir cache-dir
     :control-plane cp
     :nrepl-server nrepl-server
     :console-stop console-stop
     :ctx {:event-store event-store
           :cache cache
           :tenant-id ai.obney.grain.control-plane.events/control-plane-tenant-id}}))

(defn stop
  "Stop the test app."
  [{:keys [control-plane nrepl-server event-pubsub event-store cache console-stop]}]
  (control-plane/stop control-plane)
  (nrepl/stop-server nrepl-server)
  (pubsub/stop event-pubsub)
  (kv/stop cache)
  (es/stop event-store)
  (console-stop)
  (println "Stopped."))

;; ------------------- ;;
;; Helper functions     ;;
;; (for use from REPL) ;;
;; ------------------- ;;

(defn create-tenant!
  "Create a tenant by appending an initial event."
  [system tenant-id]
  (es/append (:event-store system)
    {:tenant-id tenant-id
     :events [(es/->event {:type :test/counter-incremented :body {}})]}))

(defn increment!
  "Append a counter-incremented event to a tenant."
  [system tenant-id]
  (es/append (:event-store system)
    {:tenant-id tenant-id
     :events [(es/->event {:type :test/counter-incremented :body {}})]}))

(defn active-nodes
  "Show active (non-stale) nodes."
  [system]
  (rmp/l1-clear!)
  (control-plane/project-active-nodes (:ctx system) 6000))

(defn leases
  "Show current lease ownership."
  [system]
  (rmp/l1-clear!)
  (rmp/project (:ctx system) :grain.control/lease-ownership))

(defn processed-events
  "Show processed events for a tenant."
  [system tenant-id]
  (into []
    (comp (remove #(= :grain/tx (:event/type %)))
          (filter #(= :test/counter-processed (:event/type %))))
    (es/read (:event-store system) {:tenant-id tenant-id})))

(defn all-events
  "Show all non-tx events for a tenant."
  [system tenant-id]
  (into []
    (remove #(= :grain/tx (:event/type %)))
    (es/read (:event-store system) {:tenant-id tenant-id})))

(defn running-processors
  "Show running processors on this node."
  [system]
  (control-plane/running-processors (:control-plane system)))

(defn diagnose-tenant
  "Full diagnostic for a tenant: which events were incremented, which were
   processed, which are missing, which are duplicated."
  [system tenant-id]
  (let [all (into []
              (remove #(= :grain/tx (:event/type %)))
              (es/read (:event-store system) {:tenant-id tenant-id}))
        increments (->> all
                        (filter #(= :test/counter-incremented (:event/type %)))
                        (mapv :event/id))
        processed (->> all
                       (filter #(= :test/counter-processed (:event/type %)))
                       (mapv :processed-by/event-id))
        checkpoints (->> all
                         (filter #(= :grain/todo-processor-checkpoint (:event/type %)))
                         (mapv :triggered-by))
        increment-set (set increments)
        processed-set (set processed)
        checkpoint-set (set checkpoints)
        missing (clojure.set/difference increment-set processed-set)
        unexpected (clojure.set/difference processed-set increment-set)
        uncheckpointed (clojure.set/difference processed-set checkpoint-set)
        duplicate-processed (let [freqs (frequencies processed)]
                              (into {} (filter #(> (val %) 1)) freqs))]
    {:tenant-id tenant-id
     :increments (count increments)
     :processed (count processed)
     :checkpoints (count checkpoints)
     :missing-count (count missing)
     :missing-event-ids (vec missing)
     :unexpected-count (count unexpected)
     :duplicate-processed duplicate-processed
     :uncheckpointed-count (count uncheckpointed)}))

;; ------------------- ;;
;; Main                ;;
;; ------------------- ;;

(defonce app (atom nil))

(defn -main [& _]
  (when-let [delay (System/getenv "START_DELAY_MS")]
    (let [ms (Integer/parseInt delay)]
      (println (str "Delaying start by " ms "ms..."))
      (Thread/sleep ms)))
  (reset! app (start))
  (.addShutdownHook (Runtime/getRuntime)
    (Thread. #(when @app (stop @app))))
  ;; Block forever so the container stays alive
  @(promise))
