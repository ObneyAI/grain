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
                            [:processed-by/event-id :uuid]]
   :test/slow-work [:map]
   :test/slow-work-done [:map
                         [:processed-by/node-id :uuid]
                         [:processed-by/event-id :uuid]
                         [:processing-time-ms :int]]
   :test/slow-work-failed [:map [:msg :string]]
   :test/billing-trigger [:map [:period :string]]
   :test/billing-done [:map [:period :string]]
   :grain/todo-processor-effect-failure [:map
                                         [:processor/name :keyword]
                                         [:triggered-by :uuid]
                                         [:error/message :string]]})

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

(defn slow-work-handler
  "Processes :test/slow-work events with a deliberate delay.
   Uses at-least-once effect path so we can observe what happens
   when a node dies mid-processing."
  [{:keys [event event-store tenant-id]}]
  (let [node-id @node-id-atom
        start-ms (System/currentTimeMillis)]
    {:result/effect (fn []
                      ;; Simulate slow work (2 seconds)
                      (Thread/sleep 2000))
     :result/checkpoint :after
     :result/on-success [(es/->event {:type :test/slow-work-done
                                       :body {:processed-by/node-id node-id
                                              :processed-by/event-id (:event/id event)
                                              :processing-time-ms 2000}})]}))

;; Register processors so the control plane can discover them
(tp/register-processor! :test/counter-processor
  {:topics [:test/counter-incremented]
   :handler-fn #'counter-processor-handler})

(tp/register-processor! :test/slow-processor
  {:topics [:test/slow-work]
   :handler-fn #'slow-work-handler})

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
                                 ;; No processor-names needed — control plane assigns tenants,
                                 ;; reactor starts all registered processors per tenant
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

(defn submit-slow-work!
  "Append a slow-work event to a tenant."
  [system tenant-id]
  (es/append (:event-store system)
    {:tenant-id tenant-id
     :events [(es/->event {:type :test/slow-work :body {}})]}))

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

(defn diagnose-slow-work
  "Analyze slow-work processing for a tenant: how many submitted,
   how many completed, how many checkpointed, which nodes processed them."
  [system tenant-id]
  (let [all (into []
              (remove #(= :grain/tx (:event/type %)))
              (es/read (:event-store system) {:tenant-id tenant-id}))
        submitted (filter #(= :test/slow-work (:event/type %)) all)
        done (filter #(= :test/slow-work-done (:event/type %)) all)
        checkpoints (filter #(= :grain/todo-processor-checkpoint (:event/type %)) all)
        failures (filter #(= :grain/todo-processor-effect-failure (:event/type %)) all)
        done-by-node (frequencies (map :processed-by/node-id done))]
    {:submitted (count submitted)
     :completed (count done)
     :checkpointed (count checkpoints)
     :failures (count failures)
     :completed-by-node (into {} (map (fn [[k v]] [(str k) v])) done-by-node)
     :events-by-type (frequencies (map :event/type all))}))

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

(defn reactor-diagnostics
  "Detailed diagnostic state of the control plane reactor."
  [system]
  (let [cp (:control-plane system)
        poller-atom (:poller-atom cp)
        poller (when poller-atom @poller-atom)
        node-id (str (:node-id cp))
        raw-leases (leases system)
        raw-active (active-nodes system)]
    {:node-id node-id
     :poller-nil? (nil? poller)
     :poller-running? (when poller @(:running poller))
     :poller-tenant-count (when poller
                            (when-let [a (:tenant-ids-atom poller)]
                              (count @a)))
     :poller-tenants (when poller
                       (when-let [a (:tenant-ids-atom poller)]
                         (mapv str @a)))
     :lease-count (count raw-leases)
     :leases-by-owner (frequencies (map str (vals raw-leases)))
     :active-node-count (count raw-active)
     :active-node-ids (mapv str (keys raw-active))
     :departure-events (let [all (into []
                                  (filter #(= :grain.control/node-departed (:event/type %)))
                                  (es/read (:event-store system)
                                    {:tenant-id ai.obney.grain.control-plane.events/control-plane-tenant-id}))]
                          (mapv (fn [e] {:node (str (:node/id e))
                                         :id (str (:event/id e))}) all))
     :node-b-heartbeats-after-departure
     (let [all-events (into []
                        (remove #(= :grain/tx (:event/type %)))
                        (es/read (:event-store system)
                          {:tenant-id ai.obney.grain.control-plane.events/control-plane-tenant-id}))
           departures (filter #(= :grain.control/node-departed (:event/type %)) all-events)
           departure-ids (set (map :event/id departures))]
       ;; Find heartbeats from any departed node that come AFTER its departure event
       (let [departed-nodes (set (map :node/id departures))]
         (->> all-events
              (filter #(and (= :grain.control/node-heartbeat (:event/type %))
                            (contains? departed-nodes (:node/id %))))
              (filter (fn [hb]
                        (some (fn [dep]
                                (and (= (:node/id hb) (:node/id dep))
                                     (pos? (compare (str (:event/id hb))
                                                    (str (:event/id dep))))))
                              departures)))
              (mapv (fn [e] {:node (str (:node/id e))
                              :id (str (:event/id e))})))))}))
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
