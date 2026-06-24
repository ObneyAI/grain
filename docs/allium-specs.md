# Allium Specifications

Distilled behavioural specifications for every grain component on `main`. Each spec
captures *what* a component does and *why* it matters — not *how* it is implemented —
and lives beside its component as `components/<name>/<name>.allium` (Allium language v3).

**37 components** — 2 domain, 26 library, 9 infrastructure. All pass `allium check` and `allium analyse` with no errors and no findings.

Each was distilled and then adversarially audited and repaired against its own source
and tests. Two specs (`pubsub`, `command-request-handler`) carry an expected
`externalEntity.missingSourceHint` reminder — a caller-supplied/cross-component type
with no governing spec to import — which is correct, not a defect.

Regenerate validation status with: `find components -name '*.allium' -exec allium check {} \;`

## Domain (2)

*Application-level behaviour: entities, state machines, rules and invariants.*

### [`control-plane`](../components/control-plane/control-plane.allium)

Cluster coordination for a multi-node, multi-tenant, event-sourced system: nodes prove liveness by heartbeat; the oldest live node is the leaderless coordinator; it leases each domain tenant to exactly one live node (round-robin, rebalanced on join/departure/death); tenant-aware routing serves a request at the lease owner or redirects. Captures CP1-CP9 conformance properties as invariants, contracts and surfaces.

<sub>entities 2 · rules 7 · contracts 2 · invariants 1 · surfaces 4</sub>

### [`example-service`](../components/example-service/example-service.allium)

A minimal reference CQRS/event-sourced domain service that manages named Counters (created, incremented, decremented under a unique-name rule) and records a fresh CounterAverage over all valued counters whenever any counter changes. The spec captures the observable domain behaviour: counter lifecycle, name uniqueness, first-change zero-initialisation, and automatic average recomputation.

<sub>entities 2 · rules 4 · contracts 0 · invariants 1 · surfaces 1</sub>

## Library / Framework (26)

*Reusable mechanisms specified as boundary `contract`s with `@invariant` guarantees plus `surface`s.*

### [`behavior-tree-v2`](../components/behavior-tree-v2/behavior-tree-v2.allium)

A reusable behaviour-tree execution engine (marked deprecated in code, noted in the spec). It captures the composition semantics of "ticking" a tree: each tick collapses the tree to exactly one of success/failure/running per node-kind rules, plus per-run short-term scratch memory and an incrementally folded long-term read model over event-store domain events (per-event exactly-once holds per query; a single shared cursor is re-applied across queries).

<sub>entities 3 · rules 5 · contracts 4 · invariants 8 · surfaces 3</sub>

### [`behavior-tree-v2-dspy-extensions`](../components/behavior-tree-v2-dspy-extensions/behavior-tree-v2-dspy-extensions.allium)

Captures the single reusable behaviour-tree leaf node (`dspy`) that bridges a tree tick to an LLM agent call: it gathers a node's declared inputs from working memory, runs one of two operations (predict, or chain-of-thought which also yields a reasoning trace), writes outputs back to memory only on success, and records a best-effort observability fact. The real guarantees modelled are the binary-and-contained tick outcome (Success/Failure, never Running; errors caught), best-effort input gathering (the operation runs even when some declared input is absent — there is deliberately no complete-inputs precondition), success-only memory effect, and conditional fact recording.

<sub>entities 2 · rules 3 · contracts 2 · invariants 2 · surfaces 1</sub>

### [`code-agent-tools`](../components/code-agent-tools/code-agent-tools.allium)

A developer-tooling boundary: a code agent installs a live Grain runtime once, then drives it over a REPL via read-only tools (catalog, schemas, validate, events, projections, diagnostics) and write tools (invoke command/query). The spec captures how the toolkit gates (dev-only install, requires installation), scopes (tenant deny-by-default), sanitizes (no raw runtime objects leak) and forwards to the demanded host runtime contracts — not the command/query/event-store/control-plane behaviour itself.

<sub>entities 1 · rules 7 · contracts 3 · invariants 2 · surfaces 3</sub>

### [`command-processor`](../components/command-processor/command-processor.allium)

Library/framework spec for grain's write-side CQRS command processor (the deprecated v1). It captures dispatch (registered name -> handler), command schema validation, handler-outcome classification (succeeded/unknown/invalid/rejected/fault), atomic tenant-scoped event submission, and the skip-storage composition mechanism that lets a parent command aggregate a child's events and store them exactly once.

<sub>entities 2 · rules 4 · contracts 2 · invariants 1 · surfaces 1</sub>

### [`command-processor-v2`](../components/command-processor-v2/command-processor-v2.allium)

Specifies the command dispatch boundary: it looks up the handler registered for an inbound command's name, validates the command against its schema before any effect, runs the handler within a tenant scope, and durably appends the events the handler emits to that tenant's stream. Captures the guarantees the tests prove: registry deny-by-default with per-call-over-global precedence, validate-before-effect, handler outcome discipline (refusal pass-through, nil/throw to fault), tenant-scoped exactly-once event append, CAS conflict distinct from fault, and skip-storage command composition.

<sub>entities 3 · rules 4 · contracts 4 · invariants 10 · surfaces 1</sub>

### [`command-request-handler`](../components/command-request-handler/command-request-handler.allium)

The component is the command-submission boundary: it stamps each command with a fresh identity and admission timestamp, validates its shape, enforces deny-by-default authorization, dispatches admitted/authorized commands to the command processor, and maps every outcome onto a fixed, transport-agnostic classification (ok/incorrect/not_found/forbidden/conflict/fault) while isolating handler faults. Modelled as a library spec: a CommandRequest lifecycle entity with witnessing rules, a demanded CommandProcessing contract, and a CommandSubmission surface carrying the boundary's guarantees.

<sub>entities 4 · rules 4 · contracts 1 · invariants 2 · surfaces 1</sub>

### [`command-request-handler-v2`](../components/command-request-handler-v2/command-request-handler-v2.allium)

Specifies the tenant-scoped command-submission boundary: it stamps each arriving command with a fresh identity and receipt timestamp, validates shape as a rejection gate, enforces deny-by-default authorization against the full assembled context, dispatches authorized commands to the command processor, and maps results onto a fixed transport-agnostic outcome classification while isolating handler faults. Modelled as a library/framework spec with a single CommandRequest lifecycle entity, two demanded/fulfilled contracts, external placeholders for the consuming system's command, registry, tenant and request context, and one boundary surface.

<sub>entities 5 · rules 4 · contracts 2 · invariants 2 · surfaces 1</sub>

### [`datastar`](../components/datastar/datastar.allium)

The datastar component is the server-driven reactive-UI boundary for grain's CQRS pipeline: it opens a live view (SSE) of a query, re-renders it as relevant domain events fire (or on a timer, or once), turns user actions into deny-by-default commands, and exposes a checked server-side UI layer. The existing spec is a faithful library-style distillation; I verified it against the full implementation and both test suites and it already validates cleanly with no error- or warning-severity diagnostics.

<sub>entities 3 · rules 7 · contracts 3 · invariants 5 · surfaces 3</sub>

### [`event-model`](../components/event-model/event-model.allium)

The spec captures event-model as the design-time vocabulary and well-formedness grammar for describing a grain CQRS application: six classified building-block kinds (command, event, view, todo-processor, screen, periodic-task), names unique per kind, the canonical connection grammar wiring blocks into flows, payload-schema obligations on data-carrying kinds, and Given/When/Then acceptance examples on commands. It models behavioural guarantees as contracts, variants and invariants rather than runtime mechanics.

<sub>entities 4 · rules 0 · contracts 3 · invariants 5 · surfaces 2</sub>

### [`event-notifier-postgres`](../components/event-notifier-postgres/event-notifier-postgres.allium)

A cross-instance event-notification bridge: when one node appends events for a tenant, every node observes a per-tenant signal and catches that tenant up by reading the new events from the durable store and republishing them onto its local bus with the tenant attached. The spec is a library/framework spec — boundary modelled as contracts (EventSource, LocalBus) plus external-entity placeholders (Tenant, Event), a per-tenant watermark entity, and surfaces — capturing notify-on-append, per-tenant gap-free/duplicate-free at-least-once delivery, ordering, tenant isolation, internal-event filtering, and failure containment.

<sub>entities 1 · rules 2 · contracts 2 · invariants 1 · surfaces 2</sub>

### [`event-store-postgres-v2`](../components/event-store-postgres-v2/event-store-postgres-v2.allium)

A durable, append-only relational event store: the persistence boundary of the event-sourced system. The spec is a library/framework spec modelling the construct/append/read boundaries as module-level contracts with @invariant guarantees, an Event placeholder entity, and writer/reader surfaces. It captures the real proven guarantees: time-ordered total ordering, atomic and globally-serialised batch append, a self-describing transaction marker per append, optimistic compare-and-swap (stored vs conflict), conjunctive read filtering, deduplicated batch reads, streaming/early-terminable reads, faithful round-trip, and idempotent initialisation.

<sub>entities 1 · rules 3 · contracts 3 · invariants 2 · surfaces 2</sub>

### [`event-store-postgres-v3`](../components/event-store-postgres-v3/event-store-postgres-v3.allium)

A library spec for grain's durable, Postgres-backed, tenant-scoped append-only event store. It models the store boundary as module-level contracts (EventCodec round-trip, AppendWriter atomic/CAS/serialized appends, StreamReader ordered-filtered-streaming reads) with @invariant guarantees, an external Tenant placeholder carrying a durable watermark, value types for positions/tags/read-filters/CAS-guards, an Event sum type (DomainEvent | TransactionMarker), append rules, two state invariants, and append/read surfaces — all abstracted away from the relational schema, advisory locks, RLS, connection pooling and serialization format.

<sub>entities 2 · rules 4 · contracts 3 · invariants 2 · surfaces 2</sub>

### [`event-store-sqlite-v3`](../components/event-store-sqlite-v3/event-store-sqlite-v3.allium)

A library/framework spec for the durable single-file SQLite backend behind grain's event-store-v3 boundary: a per-tenant, append-only, time-ordered event log with atomic batch commits, a synthesized transaction marker per append, compare-and-swap (fenced) conditional appends, filtered/reversed/limited and batch reads, a durable per-tenant watermark, and faithful body/tag/timestamp round-trip. The behavioural guarantees (time-ordering, atomicity, writer serialization, durability, indexed newest-event lookup, tenant isolation, codec round-trip, pubsub tenant-scoping) are modelled as module-level contracts with @invariant guarantees, plus entities, expression-bearing invariants and code-facing surfaces.

<sub>entities 3 · rules 5 · contracts 3 · invariants 7 · surfaces 3</sub>

### [`event-store-v2`](../components/event-store-v2/event-store-v2.allium)

The event-store-v2 facade: the validating, publishing boundary above a pluggable storage backend for the event-sourced system. The spec captures the real guarantees the code and tests prove — construction-time time-ordered identity stamping, validate-before-store, atomic/serialised batch append with a synthesised transaction marker, optional compare-and-swap guarding against a selected subset (conflict distinct from rejection), publish-after-store fan-out, and ordered/conjunctive/deduplicated/streaming reads — modelled as module-level contracts with @invariants, an Event entity placeholder, and writer/reader surfaces.

<sub>entities 1 · rules 4 · contracts 6 · invariants 2 · surfaces 2</sub>

### [`event-store-v3`](../components/event-store-v3/event-store-v3.allium)

Captures the append-only, tenant-scoped event store at the heart of grain's event-sourced CQRS model: event identity/time-ordering, atomic batch append with a transaction marker and per-tenant high-watermark, filtered/reversed/limited streaming reads, batch union+dedup, conditional CAS append, deny-by-default schema validation, tenant isolation, serialization round-trip, and tenant-tagged publication. Modelled as a library spec with EventCodec/EventStream contracts plus Tenant/Event entities, CAS rules, cross-entity invariants and append/read surfaces.

<sub>entities 2 · rules 3 · contracts 2 · invariants 4 · surfaces 2</sub>

### [`event-tailer`](../components/event-tailer/event-tailer.allium)

The event-tailer is a reusable "tail from now" delivery mechanism: a consumer registers reference-counted interest in a tenant and a set of event types, and from that moment every newly appended event of those types is republished onto the local bus with its tenant attached, via a single background poll that catches each actively-subscribed tenant up incrementally and in append order. The spec models this as a library: two module-level contracts (EventSource, LocalBus) with typed signatures and prose @invariants, two external-entity placeholders (Tenant, Event), two internal entities for interest counting and the poll cursor, five rules, three expression-bearing invariants, and two surfaces.

<sub>entities 4 · rules 5 · contracts 2 · invariants 3 · surfaces 2</sub>

### [`kv-store`](../components/kv-store/kv-store.allium)

The spec captures kv-store as a library abstraction boundary: a single module-level KeyValueStore contract carrying the five protocol operations (start, stop, read, write, batch_write) and the behavioural guarantees a conformant store must honour, fulfilled by one surface. Keys and values are modelled as opaque (Any), with prose @invariants — not field types — expressing how they relate, as the language reference prescribes for opaque payloads.

<sub>entities 0 · rules 0 · contracts 1 · invariants 8 · surfaces 1</sub>

### [`kv-store-lmdb`](../components/kv-store-lmdb/kv-store-lmdb.allium)

An embedded, durable, single-node key/value store: callers write opaque byte values under opaque byte keys, read them back unchanged, and write several entries together as one atomic batch. The spec models the boundary as a Store contract carrying the real guarantees the tests prove (write-then-read consistency, absent-key-yields-nothing, batch atomicity, durability, concurrent-read liveness), with put/get behaviour also stated as rules over a StoredEntry binding.

<sub>entities 2 · rules 2 · contracts 1 · invariants 1 · surfaces 1</sub>

### [`periodic-task`](../components/periodic-task/periodic-task.allium)

A reusable scheduling mechanism: a registered periodic trigger fires on a recurring interval or cron schedule and, on each fire, fans out across exactly the domain tenants, running a pure handler per tenant whose events are appended to that tenant's stream under an optional compare-and-swap predicate. Captures schedule well-formedness/monotonicity/spacing, control-plane tenant isolation, at-most-once-per-fire dedup via CAS, and per-fire (not per-tenant) failure isolation. Modelled as a LIBRARY/FRAMEWORK spec: module-level contracts with typed signatures and prose invariants, external-entity placeholders, and surfaces.

<sub>entities 3 · rules 6 · contracts 3 · invariants 0 · surfaces 2</sub>

### [`pubsub`](../components/pubsub/pubsub.allium)

A library spec for an instance-local, topic-routed publish/subscribe bus: a publisher hands an opaque message to the bus, which derives the message's topic and fans it out to exactly the subscribers registered for that topic on the same node. Delivery is best-effort and lossy under a fixed per-subscriber buffer bound (drop-oldest, never blocks the publisher), with no durability, acknowledgement or replay. Guarantees are captured as the LocalBus contract (RoutedByTopic, FanOutToAllSubscribers, BoundedBestEffort, NodeLocal) plus boundary surfaces.

<sub>entities 2 · rules 2 · contracts 1 · invariants 4 · surfaces 2</sub>

### [`query-processor`](../components/query-processor/query-processor.allium)

Captures the read side of CQRS: a query names a question, the processor resolves a registered handler (per-call registry shadowing a process-wide fallback), validates the query against its base shape and query-specific schema, runs the handler, and classifies the outcome. Models the QueryHandler boundary as a contract (ReadsNotWrites / NonNullResult / AnomalyPreserved), with three dispatch rules (unknown name, invalid schema, execute) and a QuerySubmission surface guaranteeing no side effects, single-outcome classification, and one-registry name resolution.

<sub>entities 2 · rules 3 · contracts 1 · invariants 4 · surfaces 1</sub>

### [`query-request-handler`](../components/query-request-handler/query-request-handler.allium)

Distills the query-submission request boundary: a caller submits a named query, the boundary stamps it with a fresh id and admission timestamp, validates the envelope shape, enforces deny-by-default authorization (no predicate or any non-true result denies), dispatches to the query processor, and relays a classified outcome from a fixed transport-agnostic vocabulary (ok/incorrect/not_found/forbidden/conflict/fault). The boundary isolates handler faults and a query is a pure read that appends no events and mutates no state.

<sub>entities 4 · rules 4 · contracts 1 · invariants 2 · surfaces 1</sub>

### [`read-model-processor`](../components/read-model-processor/read-model-processor.allium)

Captures the deprecated v1 read-model processor: the read side of CQRS where a projection folds a tenant's events through a pure registered reducer, transparently keeping an incremental snapshot keyed by (name, version, scope) and resuming from a watermark. The spec models the boundary as demanded contracts (EventLog, SnapshotCache, Codec, Reducer) plus entities for ReadModel and Projection, with rules governing cache-miss full folds, cache-hit incremental folds, and the snapshot-update threshold.

<sub>entities 2 · rules 4 · contracts 4 · invariants 2 · surfaces 1</sub>

### [`read-model-processor-v2`](../components/read-model-processor-v2/read-model-processor-v2.allium)

Captures the read side of grain's CQRS model: a projection engine that folds a tenant's event stream through a pure reducer into queryable state, memoised in a two-tier cache and advanced incrementally from a stored watermark. The spec records behavioural guarantees only — fold correctness and determinism, gapless reprocessing-free catch-up, cache tier-agreement, per-tenant/per-scope isolation, size-transparent and partitioned storage with cross-partition move detection, and serialization round-trip — via module-level contracts, external-entity placeholders, expression-bearing invariants and code-facing surfaces.

<sub>entities 4 · rules 3 · contracts 6 · invariants 9 · surfaces 2</sub>

### [`todo-processor`](../components/todo-processor/todo-processor.allium)

A LIBRARY/FRAMEWORK spec for the first-generation (now deprecated) reactive event-handler runtime: a processor subscribes to event topics on an in-process pub/sub bus and invokes one consumer-supplied handler per delivered event, atomically appending the handler's emitted events to the single store it is bound to. It captures the real test-proven guarantees — per-event delivery, atomic append, fault isolation, bounded backpressure with no drops — and the explicit non-guarantee of transient at-most-once delivery with no checkpoints or replay (the gap v2 closes).

<sub>entities 2 · rules 4 · contracts 3 · invariants 0 · surfaces 1</sub>

### [`todo-processor-v2`](../components/todo-processor-v2/todo-processor-v2.allium)

A reusable event-processing mechanism: registered processors react to a tenant's stream events, each consumed exactly once per processor+tenant via a durable, monotonic, gap-free high-watermark checkpoint. Captures the four handler-outcome delivery shapes (pure/batched, at-least-once effect, at-most-once effect, handler-CAS), idempotent replay/catch-up, lease fencing, tenant isolation, and O(1) checkpoint reads — all as module-level contracts and invariants over external Event/Tenant placeholders.

<sub>entities 2 · rules 8 · contracts 5 · invariants 1 · surfaces 2</sub>

## Infrastructure (9)

*Thin plumbing — brief, honest specs that record what little behaviour is specifiable.*

### [`anomalies`](../components/anomalies/anomalies.allium)

Captures grain's codebase-wide convention of signalling recoverable failures as in-band data values (anomalies) rather than thrown exceptions: a single discrimination predicate over the shared cognitect.anomalies category vocabulary (fault, conflict, not-found, incorrect, forbidden, ...), plus the success-or-anomaly branch every fallible call site performs. Deliberately brief, as the whole component is one predicate (anomaly?) with no domain, lifecycle or persistent state.

<sub>entities 1 · rules 1 · contracts 1 · invariants 4 · surfaces 1</sub>

### [`clj-dspy`](../components/clj-dspy/clj-dspy.allium)

clj-dspy is a thin, DEPRECATED authoring helper whose only macro (defsignature) translates a declarative Malli field specification into a named, documented DSPy/Pydantic signature in an embedded Python runtime. The spec captures the single genuine guarantee — meaning-preserving, one-for-one translation of fields with their shapes, descriptions and defaults, namespaced by defining module — and nothing more.

<sub>entities 1 · rules 0 · contracts 1 · invariants 0 · surfaces 1</sub>

### [`core-async-thread-pool`](../components/core-async-thread-pool/core-async-thread-pool.allium)

A short, honest infrastructure spec for a reusable worker-pool: a fixed number of workers drain a single shared queue, running a caller-supplied handler per job with ordinary-exception failures isolated and reported. It captures the only real guarantees (bounded concurrency, per-job error isolation limited to ordinary exceptions, graceful drain-then-stop, no cross-worker ordering) as contracts with @invariants, plus a Job placeholder, a single HandleJob rule, and one PoolControl surface.

<sub>entities 1 · rules 1 · contracts 2 · invariants 4 · surfaces 1</sub>

### [`fressian-util`](../components/fressian-util/fressian-util.allium)

fressian-util is a binary codec that encodes grain domain values to bytes and decodes them back losslessly. The spec captures its one genuine behavioural guarantee — a faithful round-trip preserving collection kind, nesting, and grain's two timestamp types — as a single Codec contract with three invariants, exposed through one Serialization surface.

<sub>entities 1 · rules 0 · contracts 1 · invariants 3 · surfaces 1</sub>

### [`mulog-aws-cloudwatch-emf-publisher`](../components/mulog-aws-cloudwatch-emf-publisher/mulog-aws-cloudwatch-emf-publisher.allium)

A μ/log publisher that observes a structured telemetry stream and re-emits the metric-bearing events as CloudWatch (EMF) measurements. It owns no domain state or workflow; the spec captures only the genuine behavioural guarantees: which events qualify for publication, counter-vs-timer unit selection, storage-resolution mapping, and consider-once batch drain semantics.

<sub>entities 2 · rules 1 · contracts 1 · invariants 3 · surfaces 1</sub>

### [`query-schema`](../components/query-schema/query-schema.allium)

query-schema defines the uniform envelope every read request is wrapped in: which query is asked (name), a unique request identity, and an arrival timestamp. The spec captures the single genuine guarantee — a query must be well-formed against this shape before any handler runs — plus the fact that identity and timestamp are stamped at the boundary, overwriting whatever the caller supplied.

<sub>entities 1 · rules 0 · contracts 1 · invariants 0 · surfaces 1</sub>

### [`schema-util`](../components/schema-util/schema-util.allium)

schema-util is a process-wide shared schema registry over the Malli validation library: components contribute named schemas into one global namespace and resolve/validate against any contributed name. The spec captures the only genuine behaviour — registration semantics (unique names, last-writer-wins, idempotent, globally resolvable) and validation-as-pure-predicate — as a SchemaRegistry contract with a single contribution surface.

<sub>entities 0 · rules 0 · contracts 1 · invariants 0 · surfaces 1</sub>

### [`time`](../components/time/time.allium)

The time component is the system clock: a single source of the current instant plus two helpers that construct an instant from an external representation (an ISO-8601 string or epoch milliseconds). The spec models it as one Clock contract carrying the only genuine guarantees — a current-instant reading (volatile, zone-offset-bearing, no monotonicity promise) and parse fidelity (a parsed source denotes the same moment, with the offset treated as a rendering detail) — plus a placeholder Component entity, a Caller actor, and a single TimeSource surface that fulfils Clock.

<sub>entities 1 · rules 0 · contracts 1 · invariants 2 · surfaces 1</sub>

### [`webserver`](../components/webserver/webserver.allium)

A thin HTTP-server lifecycle wrapper: a host starts a single server over a caller-supplied route set on a configured port (port 0 binds an OS-chosen ephemeral port readable back from the instance) and later stops it. All request semantics — routing, authorization, tenant scoping, command/query dispatch — belong to the route-providing components, not here.

<sub>entities 0 · rules 0 · contracts 1 · invariants 4 · surfaces 1</sub>

