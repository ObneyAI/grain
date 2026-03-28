# Distributed Coordination

For multi-instance deployments, the `grain-control-plane` package provides distributed coordination using Grain's own event-sourcing primitives. No external coordination service (ZooKeeper, etcd, Consul) is required — the control plane is itself event-sourced, storing its state in a dedicated tenant on the shared event store.

## How It Works

```
  ┌──────────┐     ┌──────────┐     ┌──────────┐
  │  Node A  │     │  Node B  │     │  Node C  │
  │          │     │          │     │          │
  │ Poller   │     │ Poller   │     │ Poller   │
  │ T1,T2    │     │ T3,T4    │     │ T5,T6    │
  └────┬─────┘     └────┬─────┘     └────┬─────┘
       │                │                │
       │   heartbeat    │   heartbeat    │   heartbeat
       ▼                ▼                ▼
  ┌──────────────────────────────────────────────┐
  │              Event Store (Postgres)           │
  │                                              │
  │  Control plane tenant:                       │
  │    heartbeats, departures, lease events      │
  │                                              │
  │  Domain tenants:                             │
  │    T1, T2, T3, T4, T5, T6, ...              │
  └──────────────────────────────────────────────┘
```

1. **Heartbeats** — Each node periodically appends heartbeat events. Nodes that stop heartbeating are considered stale after a configurable threshold.
2. **Coordinator election** — The active node with the lexicographically smallest UUID v7 becomes coordinator. Deterministic, no voting protocol.
3. **Tenant assignment** — The coordinator projects active nodes and domain tenants, computes a round-robin assignment, and emits lease-acquired/lease-released events.
4. **Reconciliation** — Each node projects the lease-ownership read model and starts/stops its tenant poller to match its assigned tenants.
5. **Graceful shutdown** — On stop, the node drains in-flight work, then emits a departure event. The coordinator reassigns immediately.

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

In a multi-instance deployment behind a load balancer, HTTP requests and SSE connections should be served by the node that holds the tenant's lease (warm L2 cache, in-process pub/sub for SSE push).

Grain provides a Pedestal interceptor that implements retry-until-correct-node routing with sticky cookies:

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

When a request hits the wrong node, the interceptor returns `503` with `Retry-After`. The load balancer (ALB, HAProxy) retries on another backend. When the correct node serves the request, it sets a sticky cookie — subsequent requests from that client are locked to that node. Self-healing on lease migration: the old node stops setting the cookie, the client bounces, and finds the new owner.

For custom routing logic without the interceptor:

```clojure
(control-plane/route-for-tenant lease-ownership active-nodes this-node-id tenant-id)
;; => {:route/decision :local,  :route/reason :owner}
;; => {:route/decision :remote, :route/owner node-id, :route/address "10.0.1.2:8080"}
;; => {:route/decision :local,  :route/reason :no-owner}   ; graceful degradation
```
