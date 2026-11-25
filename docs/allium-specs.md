# Allium specifications

Each link opens the behavioural contract for a Grain component. Check and
analyse the specifications together so cross-component imports resolve:

```sh
allium check components
allium analyse components
```

## Domain (2)

| Component | Coverage |
| --- | --- |
| [control-plane](../components/control-plane/control-plane.allium) | Coordinator election, node liveness, committed lease changes and tenant routing. |
| [example-service](../components/example-service/example-service.allium) | Named counters, uniqueness and average recomputation in the example service. |

## Library / Framework (28)

| Component | Coverage |
| --- | --- |
| [behavior-tree-v2](../components/behavior-tree-v2/behavior-tree-v2.allium) | Behavior tree execution and node outcomes. |
| [behavior-tree-v2-dspy-extensions](../components/behavior-tree-v2-dspy-extensions/behavior-tree-v2-dspy-extensions.allium) | DSPy nodes for behavior trees. |
| [code-agent-tools](../components/code-agent-tools/code-agent-tools.allium) | Development catalog, tenant-scoped reads, ordinary projections and command/query invocation over nREPL. |
| [command-processor](../components/command-processor/command-processor.allium) | Legacy command dispatch and event append. |
| [command-processor-v2](../components/command-processor-v2/command-processor-v2.allium) | Tenant-scoped command dispatch, validation, CAS, append failures and metrics. |
| [command-request-handler](../components/command-request-handler/command-request-handler.allium) | Legacy command request validation and dispatch. |
| [command-request-handler-v2](../components/command-request-handler-v2/command-request-handler-v2.allium) | Command envelopes, authorization and adapter outcomes. |
| [datastar](../components/datastar/datastar.allium) | Live query streams, refresh keys, subscription filters, disconnect cleanup and UI bindings. |
| [event-model](../components/event-model/event-model.allium) | Service-area topology, schemas, typed edges and Allium references. |
| [event-model-validator](../components/event-model-validator/event-model-validator.allium) | Registry reconciliation, strict coverage, per-instance state schemas and bounded-history checks. |
| [event-notifier-postgres](../components/event-notifier-postgres/event-notifier-postgres.allium) | Cross-node notification and catch-up over a shared Postgres store. |
| [event-retention](../components/event-retention/event-retention.allium) | Policy activation, cutoff and latest-key selection, transaction-marker cleanup and permanent receipts. |
| [event-store-postgres-v2](../components/event-store-postgres-v2/event-store-postgres-v2.allium) | Legacy Postgres store, ordered reads and conditional appends. |
| [event-store-postgres-v3](../components/event-store-postgres-v3/event-store-postgres-v3.allium) | Tenant isolation, ordered reads, bounded lock retries and atomic compaction in Postgres. |
| [event-store-sqlite-v3](../components/event-store-sqlite-v3/event-store-sqlite-v3.allium) | SQLite write queue, bounded lock retries, indexed reads and atomic compaction. |
| [event-store-v2](../components/event-store-v2/event-store-v2.allium) | Legacy event-store protocol and in-memory implementation. |
| [event-store-v3](../components/event-store-v3/event-store-v3.allium) | Tenant-scoped append, CAS, head lookup, stable empty intervals and write contention. |
| [event-tailer](../components/event-tailer/event-tailer.allium) | Tenant and event-type subscriptions, incremental reads and local delivery. |
| [kv-store](../components/kv-store/kv-store.allium) | Byte-key operations, consistent read snapshots and callback-scoped transactions. |
| [kv-store-lmdb](../components/kv-store-lmdb/kv-store-lmdb.allium) | Durable key/value storage, atomic writes, snapshot reads and ordered range scans. |
| [periodic-task](../components/periodic-task/periodic-task.allium) | Per-tenant scheduled triggers, CAS deduplication and firing metrics. |
| [pubsub](../components/pubsub/pubsub.allium) | Local topic delivery, bounded buffers and explicit unsubscription. |
| [query-processor](../components/query-processor/query-processor.allium) | Query lookup, input validation, result classification and metrics. |
| [query-request-handler](../components/query-request-handler/query-request-handler.allium) | Query envelopes, authorization and adapter outcomes. |
| [read-model-processor](../components/read-model-processor/read-model-processor.allium) | Legacy projection snapshots and incremental replay. |
| [read-model-processor-v2](../components/read-model-processor-v2/read-model-processor-v2.allium) | Ordinary and keyed projections, replay boundaries, indexed reads, search and joins. |
| [todo-processor](../components/todo-processor/todo-processor.allium) | Legacy event-driven processing over local pub/sub. |
| [todo-processor-v2](../components/todo-processor-v2/todo-processor-v2.allium) | Per-tenant checkpoints, initialization, polling, lease fencing and effect delivery. |

## Infrastructure (9)

| Component | Coverage |
| --- | --- |
| [anomalies](../components/anomalies/anomalies.allium) | Detection of in-band Cognitect anomaly values. |
| [clj-dspy](../components/clj-dspy/clj-dspy.allium) | Legacy Clojure declarations for DSPy signatures. |
| [core-async-thread-pool](../components/core-async-thread-pool/core-async-thread-pool.allium) | Bounded worker concurrency, job failures and shutdown. |
| [fressian-util](../components/fressian-util/fressian-util.allium) | Binary round trips for Clojure collections and timestamps. |
| [mulog-aws-cloudwatch-emf-publisher](../components/mulog-aws-cloudwatch-emf-publisher/mulog-aws-cloudwatch-emf-publisher.allium) | EMF measurement validation, aggregation, delivery retries, health and bounded shutdown. |
| [query-schema](../components/query-schema/query-schema.allium) | Query envelope fields and admission timestamps. |
| [schema-util](../components/schema-util/schema-util.allium) | Named schema registration, lookup and validation. |
| [time](../components/time/time.allium) | Current time and instant conversion. |
| [webserver](../components/webserver/webserver.allium) | HTTP server start and stop over caller-supplied routes. |
