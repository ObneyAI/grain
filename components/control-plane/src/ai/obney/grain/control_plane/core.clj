(ns ai.obney.grain.control-plane.core
  "Control plane orchestration: lease management, heartbeat, coordinator loop."
  (:require [ai.obney.grain.event-store-v3.interface :as es]
            [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.control-plane.read-models]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.todo-processor-v2.interface :as tp]
            [chime.core :as chime]
            [com.brunobonacci.mulog :as u]
            [clojure.set]
            [cognitect.anomalies :as anom]
            [clj-uuid :as uuid])
  (:import [java.time Instant Duration]))

(defn project-active-nodes
  "Project active-nodes read model, filtering out stale nodes."
  [ctx staleness-threshold-ms]
  (let [nodes (rmp/project ctx :grain.control/active-nodes)
        now (System/currentTimeMillis)]
    (into {}
          (filter (fn [[_ v]]
                    (< (- now (:last-heartbeat-at v)) staleness-threshold-ms)))
          nodes)))

(defn project-lease-ownership
  "Project current lease ownership."
  [ctx]
  (rmp/project ctx :grain.control/lease-ownership))

(defn emit-heartbeat!
  "Append a heartbeat event for this node."
  [ctx node-id metadata]
  (es/append (:event-store ctx)
    {:tenant-id events/control-plane-tenant-id
     :events [(events/->heartbeat node-id metadata)]}))

(defn emit-node-departed!
  "Append a departure event for this node."
  [ctx node-id]
  (es/append (:event-store ctx)
    {:tenant-id events/control-plane-tenant-id
     :events [(events/->node-departed node-id)]}))

(defn compute-lease-diff
  "Given desired assignment and current leases, returns {:acquire [...] :release [...]}."
  [desired-assignment current-leases]
  (let [desired-flat (into {}
                       (for [[node-id tenants] desired-assignment
                             tid tenants]
                         [tid node-id]))
        to-release (into []
                     (for [[tid current-owner] current-leases
                           :when (not= current-owner (get desired-flat tid))]
                       {:node-id current-owner :tenant-id tid}))
        to-acquire (into []
                     (for [[tid desired-owner] desired-flat
                           :when (not= desired-owner (get current-leases tid))]
                       {:node-id desired-owner :tenant-id tid}))]
    {:release to-release
     :acquire to-acquire}))

(defn emit-lease-changes!
  "Emit lease-released and lease-acquired events."
  [ctx releases acquisitions]
  (let [events (concat
                (map #(events/->lease-released (:node-id %) (:tenant-id %))
                     releases)
                (map #(events/->lease-acquired (:node-id %) (:tenant-id %))
                     acquisitions))]
    (when (seq events)
      (es/append (:event-store ctx)
        {:tenant-id events/control-plane-tenant-id
         :events (vec events)}))))

(defn run-assignment!
  "Run one assignment cycle: project state, compute assignment, emit diffs.
   Only the coordinator should call this."
  [ctx node-id staleness-threshold-ms strategy]
  (let [active-nodes (project-active-nodes ctx staleness-threshold-ms)
        current-leases (project-lease-ownership ctx)
        tenant-ids (es/tenant-ids (:event-store ctx))
        domain-tenants (disj tenant-ids events/control-plane-tenant-id)
        desired (assignment/assign active-nodes domain-tenants current-leases strategy)
        {:keys [release acquire]} (compute-lease-diff desired current-leases)]
    (when (or (seq release) (seq acquire))
      (emit-lease-changes! ctx release acquire))))

(defn- heartbeat-handler
  "Called periodically to emit a heartbeat for this node."
  [{:keys [ctx node-id metadata]}]
  (fn [_time]
    (try
      (u/log ::emitting-heartbeat :node-id node-id)
      (emit-heartbeat! ctx node-id metadata)
      (catch Throwable t
        (u/log ::heartbeat-error :exception t)))))

(defn- reconcile-tenants!
  "Update the tenant poller's owned-tenant set based on lease assignments.
   Starts the poller on first call, updates tenant set on subsequent calls."
  [ctx node-id poller-atom]
  (let [leases (project-lease-ownership ctx)
        my-tenants (into #{}
                     (comp (filter (fn [[_ owner]] (= owner node-id)))
                           (map key))
                     leases)]
    ;; Start poller if not running, or update tenant set
    (if-let [poller @poller-atom]
      ;; Poller exists — update its tenant set
      (let [current-tenants @(:tenant-ids-atom poller)]
        (when (not= current-tenants my-tenants)
          (u/log ::reactor-updating-tenants
                 :added (count (clojure.set/difference my-tenants current-tenants))
                 :removed (count (clojure.set/difference current-tenants my-tenants)))
          (reset! (:tenant-ids-atom poller) my-tenants)))
      ;; No poller yet — start one
      (when (seq my-tenants)
        (u/log ::reactor-starting-poller :tenant-count (count my-tenants))
        (let [tenant-ids-atom (atom my-tenants)
              poller (tp/start-tenant-poller
                       {:event-store (:event-store ctx)
                        :tenant-ids tenant-ids-atom
                        :poll-interval-ms 250
                        :batch-size 100
                        :thread-pool-size 32})]
          (reset! poller-atom (assoc poller :tenant-ids-atom tenant-ids-atom)))))))

(defn- coordinator-handler
  "Called periodically to run the assignment cycle if this node is coordinator,
   then reconcile local processors with lease assignments."
  [{:keys [ctx node-id staleness-threshold-ms strategy
           poller-atom]}]
  (fn [_time]
    (try
      (rmp/l1-clear!)
      (let [active-nodes (project-active-nodes ctx staleness-threshold-ms)
            coordinator (assignment/coordinator active-nodes)]
        (when (= node-id coordinator)
          (u/log ::running-assignment :node-id node-id)
          (run-assignment! ctx node-id staleness-threshold-ms strategy)))
      ;; Reconcile tenants regardless of coordinator status
      (reconcile-tenants! ctx node-id poller-atom)
      (catch Throwable t
        (u/log ::coordinator-error :exception t)))))

(defn start
  "Start the control plane for this node. Returns a map that can be passed to `stop`.

   config keys:
     :event-store          - the event store instance
     :cache                - the kv-store for read model L2 cache
     :node-id              - UUID v7 identifying this node (generated if not provided)
     :node-metadata        - optional metadata map for this node
     :heartbeat-interval-ms - heartbeat period (default 5000)
     :staleness-threshold-ms - time before a node is considered dead (default 15000)
     :strategy             - assignment strategy (default :round-robin)"
  [{:keys [event-store cache node-id node-metadata
           heartbeat-interval-ms staleness-threshold-ms strategy]
    :or {heartbeat-interval-ms 5000
         staleness-threshold-ms 15000
         strategy :round-robin
         node-metadata {}}}]
  (let [node-id (or node-id (uuid/v7))
        ctx {:event-store event-store
             :cache cache
             :tenant-id events/control-plane-tenant-id}
        poller-atom (atom nil)
        interval (Duration/ofMillis heartbeat-interval-ms)
        heartbeat-schedule (chime/chime-at
                            (chime/periodic-seq (Instant/now) interval)
                            (heartbeat-handler {:ctx ctx :node-id node-id :metadata node-metadata}))
        coordinator-schedule (chime/chime-at
                              (chime/periodic-seq (Instant/now) interval)
                              (coordinator-handler {:ctx ctx
                                                    :node-id node-id
                                                    :staleness-threshold-ms staleness-threshold-ms
                                                    :strategy strategy
                                                    :poller-atom poller-atom}))]
    (u/log ::control-plane-started :node-id node-id)
    ;; Emit initial heartbeat immediately
    (emit-heartbeat! ctx node-id node-metadata)
    {:node-id node-id
     :ctx ctx
     :heartbeat-schedule heartbeat-schedule
     :coordinator-schedule coordinator-schedule
     :poller-atom poller-atom}))

(defn running-processors
  "Returns the set of tenant-ids being processed by this node's poller."
  [cp-instance]
  (when-let [poller @(:poller-atom cp-instance)]
    @(:tenant-ids-atom poller)))

(defn stop
  "Stop the control plane. Emits a departure event for graceful shutdown."
  [{:keys [node-id ctx heartbeat-schedule coordinator-schedule poller-atom]}]
  (u/log ::control-plane-stopping :node-id node-id)
  (.close heartbeat-schedule)
  (.close coordinator-schedule)
  ;; Stop the tenant poller
  (when-let [poller @poller-atom]
    (tp/stop-tenant-poller poller)
    (reset! poller-atom nil))
  (emit-node-departed! ctx node-id)
  (u/log ::control-plane-stopped :node-id node-id))
