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
                       (for [[node-id pairs] desired-assignment
                             pair pairs]
                         [pair node-id]))
        to-release (into []
                     (for [[pair current-owner] current-leases
                           :when (not= current-owner (get desired-flat pair))]
                       {:node-id current-owner :tenant-id (first pair) :processor-name (second pair)}))
        to-acquire (into []
                     (for [[pair desired-owner] desired-flat
                           :when (not= desired-owner (get current-leases pair))]
                       {:node-id desired-owner :tenant-id (first pair) :processor-name (second pair)}))]
    {:release to-release
     :acquire to-acquire}))

(defn emit-lease-changes!
  "Emit lease-released and lease-acquired events.
   The coordinator is deterministically elected (only one coordinator at a time),
   so CAS is not needed here — the assignment function is idempotent and
   only the coordinator emits lease events."
  [ctx releases acquisitions]
  (let [events (concat
                (map #(events/->lease-released (:node-id %) (:tenant-id %) (:processor-name %))
                     releases)
                (map #(events/->lease-acquired (:node-id %) (:tenant-id %) (:processor-name %))
                     acquisitions))]
    (when (seq events)
      (es/append (:event-store ctx)
        {:tenant-id events/control-plane-tenant-id
         :events (vec events)}))))

(defn run-assignment!
  "Run one assignment cycle: project state, compute assignment, emit diffs.
   Only the coordinator should call this."
  [ctx node-id processor-names staleness-threshold-ms strategy]
  (let [active-nodes (project-active-nodes ctx staleness-threshold-ms)
        current-leases (project-lease-ownership ctx)
        tenant-ids (es/tenant-ids (:event-store ctx))
        ;; Remove the control plane tenant from assignment
        domain-tenants (disj tenant-ids events/control-plane-tenant-id)
        ;; Build all (tenant, processor) pairs
        pairs (set (for [tid domain-tenants
                         pname processor-names]
                     [tid pname]))
        desired (assignment/assign active-nodes pairs current-leases strategy)
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

(defn- reconcile-processors!
  "Diff lease assignments for this node against running processors.
   Start new ones (poll-based), stop released ones."
  [ctx node-id running-processors-atom]
  (let [leases (project-lease-ownership ctx)
        my-leases (into #{}
                    (comp (filter (fn [[_ owner]] (= owner node-id)))
                          (map key))
                    leases)
        currently-running (set (keys @running-processors-atom))
        to-start (clojure.set/difference my-leases currently-running)
        to-stop (clojure.set/difference currently-running my-leases)
        registry @tp/processor-registry*]
    ;; Stop released processors
    (doseq [k to-stop]
      (when-let [proc (get @running-processors-atom k)]
        (u/log ::reactor-stopping-processor :key k)
        (tp/stop-polling proc)
        (swap! running-processors-atom dissoc k)))
    ;; Start newly assigned processors (poll-based)
    (doseq [[tenant-id proc-name :as k] to-start]
      (when-let [proc-config (get registry proc-name)]
        (u/log ::reactor-starting-processor :key k)
        (let [lease-check-fn (fn [tid pname]
                               (= node-id (get (project-lease-ownership ctx)
                                               [tid pname])))
              proc (tp/start-polling
                     {:event-store (:event-store ctx)
                      :tenant-id tenant-id
                      :topics (:topics proc-config)
                      :handler-fn (:handler-fn proc-config)
                      :processor-name proc-name
                      :lease-check-fn lease-check-fn})]
          (swap! running-processors-atom assoc k proc))))))

(defn- coordinator-handler
  "Called periodically to run the assignment cycle if this node is coordinator,
   then reconcile local processors with lease assignments."
  [{:keys [ctx node-id processor-names staleness-threshold-ms strategy
           running-processors-atom]}]
  (fn [_time]
    (try
      (rmp/l1-clear!)
      (let [active-nodes (project-active-nodes ctx staleness-threshold-ms)
            coordinator (assignment/coordinator active-nodes)]
        (when (= node-id coordinator)
          (u/log ::running-assignment :node-id node-id)
          (run-assignment! ctx node-id processor-names staleness-threshold-ms strategy)))
      ;; Reconcile processors regardless of coordinator status
      (reconcile-processors! ctx node-id running-processors-atom)
      (catch Throwable t
        (u/log ::coordinator-error :exception t)))))

(defn start
  "Start the control plane for this node. Returns a map that can be passed to `stop`.

   config keys:
     :event-store          - the event store instance
     :cache                - the kv-store for read model L2 cache
     :node-id              - UUID v7 identifying this node (generated if not provided)
     :node-metadata        - optional metadata map for this node
     :processor-names      - vector of processor name keywords to assign
     :heartbeat-interval-ms - heartbeat period (default 5000)
     :staleness-threshold-ms - time before a node is considered dead (default 15000)
     :strategy             - assignment strategy (default :round-robin)"
  [{:keys [event-store cache node-id node-metadata processor-names
           heartbeat-interval-ms staleness-threshold-ms strategy]
    :or {heartbeat-interval-ms 5000
         staleness-threshold-ms 15000
         strategy :round-robin
         node-metadata {}
         processor-names []}}]
  (let [node-id (or node-id (uuid/v7))
        ctx {:event-store event-store
             :cache cache
             :tenant-id events/control-plane-tenant-id}
        running-processors-atom (atom {})
        interval (Duration/ofMillis heartbeat-interval-ms)
        heartbeat-schedule (chime/chime-at
                            (chime/periodic-seq (Instant/now) interval)
                            (heartbeat-handler {:ctx ctx :node-id node-id :metadata node-metadata}))
        coordinator-schedule (chime/chime-at
                              (chime/periodic-seq (Instant/now) interval)
                              (coordinator-handler {:ctx ctx
                                                    :node-id node-id
                                                    :processor-names processor-names
                                                    :staleness-threshold-ms staleness-threshold-ms
                                                    :strategy strategy
                                                    :running-processors-atom running-processors-atom}))]
    (u/log ::control-plane-started :node-id node-id)
    ;; Emit initial heartbeat immediately
    (emit-heartbeat! ctx node-id node-metadata)
    {:node-id node-id
     :ctx ctx
     :heartbeat-schedule heartbeat-schedule
     :coordinator-schedule coordinator-schedule
     :running-processors running-processors-atom}))

(defn running-processors
  "Returns a map of currently running processor instances managed by the reactor."
  [cp-instance]
  @(:running-processors cp-instance))

(defn stop
  "Stop the control plane. Emits a departure event for graceful shutdown."
  [{:keys [node-id ctx heartbeat-schedule coordinator-schedule running-processors]}]
  (u/log ::control-plane-stopping :node-id node-id)
  (.close heartbeat-schedule)
  (.close coordinator-schedule)
  ;; Stop all running processors
  (doseq [[k proc] @running-processors]
    (u/log ::stopping-reactor-processor :key k)
    (tp/stop-polling proc))
  (reset! running-processors {})
  (emit-node-departed! ctx node-id)
  (u/log ::control-plane-stopped :node-id node-id))
