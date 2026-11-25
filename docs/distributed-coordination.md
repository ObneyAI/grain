# Distributed Coordination

The control plane coordinates tenant polling across nodes sharing a v3 event
store. It records node liveness and leases in a dedicated system tenant.

## Tenant assignment

Nodes append heartbeats. The active node with the lexicographically smallest
node UUID coordinates assignment. By default it distributes tenants round-robin
and records lease acquisitions and releases. Each node reconciles its poller
with the committed leases.

A missed heartbeat becomes stale after the configured threshold. On graceful
shutdown a node stops heartbeating, drains its poller and records its departure.
The coordinator uses the updated membership on its next assignment cycle.

A heartbeat or lease change takes effect only after its append commits. If the
store exhausts its write retries, the next scheduled coordination cycle uses
the last committed state. A tenant poller whose checkpoint write fails resumes
from its last durable checkpoint. See [v3 write contention](event-stores.md#write-contention).

## Starting the Control Plane

```clojure
(require '[ai.obney.grain.control-plane.interface :as control-plane])

(def cp (control-plane/start
          {:event-store event-store
           :cache cache                          ; LMDB KV store for read model L2
           :node-metadata {:address "node-a:8080"} ; included in heartbeats
           :heartbeat-interval-ms 2000
           :staleness-threshold-ms 6000
           :strategy :round-robin}))             ; or a custom (fn [active-nodes tenant-ids leases] ...)

;; On shutdown:
(control-plane/stop cp)
```

Register processors with `defprocessor` before starting the control plane. The control plane discovers them from the global registry and starts polling for assigned tenants automatically.

## Tenant-Aware Routing

Route command and query requests to the lease owner for cache locality. An SSE
stream can stay on another node when an [event tailer](datastar.md#multi-node-live-updates)
republishes committed events there.

Place the tenant-routing interceptor after authentication.

```clojure
(def routing-interceptor
  (control-plane/tenant-routing-interceptor
    {:extract-tenant-id (fn [ctx]
                          (get-in ctx [:grain/additional-context :auth-claims :tenant-id]))
     :this-node-id      (:node-id cp)
     :ctx               (:ctx cp)
     :staleness-threshold-ms 6000}))

;; Add to your Pedestal routes after the auth interceptor:
#{["/api/counters" :get [auth-interceptor routing-interceptor counters-handler]]}
```

When another live node owns the lease, the interceptor returns `503` with
`Retry-After` and, when available, the owner's address in `X-Grain-Owner`.
Configure the client or load balancer to retry on another backend. An owner
sets the sticky cookie. If no live owner is known, the node serves locally
without setting it.

The interceptor counts owner hits under `RoutingLocal`, wrong-node responses
under `RoutingRemote` and requests served without a live owner under
`RoutingDegradation`.

For custom routing logic without the interceptor:

```clojure
(control-plane/route-for-tenant lease-ownership active-nodes this-node-id tenant-id)
;; => {:route/decision :local,  :route/reason :owner}
;; => {:route/decision :remote, :route/owner node-id, :route/address "10.0.1.2:8080"}
;; => {:route/decision :local,  :route/reason :no-owner}   ; graceful degradation
```
