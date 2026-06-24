# Changelog

[Grain](../readme.md) is an opinionated toolkit of composable building blocks for
AI-native, event-sourced (CQRS) information systems in Clojure, organized as a Polylith
workspace.

Grain does not adhere to semvar and currently carries no release tags, so this changelog is organized
**chronologically by month**, newest first. It provides complete coverage of all **302
commits** from **2025-06-13** (workspace created) through **2026-06-24**, authored
primarily by `cjbarre`, with contributions from Brandon Ringe (`bpringe`) and Cameron
Barre.

Within each month, changes are grouped into **Added / Changed / Fixed / Removed / Docs /
Internal**, and every entry cites the short commit hashes it folds in. Backward-
incompatible changes are marked **Breaking:**. A complete, one-line-per-commit index of
all 302 commits appears in the [appendix](#appendix-complete-commit-log) at the end, so
every commit is accounted for whether or not it warranted narrative detail. (There were
no commits in October 2025, so that month does not appear below.)

Hashes are short Git SHAs; resolve any of them with `git show <hash>` or at
`https://github.com/ObneyAI/grain/commit/<hash>`.

---

## June 2026 — A Datastar UI authoring layer and cross-node live updates

### Added
- Introduced the Datastar UI IR DSL: a new intermediate-representation authoring layer (`ui.clj`) that lowers checked UI effects and expressions into Datastar action strings, establishing the foundation for the rest of the month's UI work (`b22ae07`). Built atop it: indexed collection bindings for per-item iteration (`d3a7066`); stable signal identifiers and DOM property bindings (`cdc3e30`); element-level effects that target individual elements (`4e55415`); checked signal-patch handlers for validated signal patching (`acf3289`); a checked morph-ignore attribute to exclude elements from Datastar morphing (`5822779`); recursive/nested action payloads with deeply nested signal references (`af59c4d`); and registry-backed symbolic route references so UI actions target routes by name rather than hardcoded URLs (`082fea4`).
- Added a grain-core-v2 event-tailer component that polls the shared event store by tenant/type and republishes new events onto each node's local pubsub, wiring Datastar SSE streams to register tailer interest with per-stream tenant filtering and event-id dedupe for cross-node live updates (`21e08d0`).
- Exposed per-command metadata through the Datastar component core/interface, making it available to callers (`54e3e68`).
- Added clj-kondo hooks and config for the grain-datastar UI DSL so the linter understands the macro forms and avoids false positives (`eec5416`).

### Changed
- **Breaking:** Removed the imperative bang-suffixed names from the Datastar UI DSL, renaming effect builders and related functions to plain names across implementation, tests, and docs (`35f5f5c`).
- Refined the Datastar UI DSL internals: reworked event handling and signal scoping so signals resolve within their proper scope (`43c4f41`); refined the signal-reference API (`a6ddac2`); and migrated checked event-effect (`fd82fd0`) and signal-patch (`1f17f3f`) handlers to the expression-based lowering model.
- Changed the default value of `:datastar/fps` to 0, disabling frame-rate throttling by default (`207d4b1`).
- Switched the event-tailer's local event-store and pubsub deps to `event-tailer-local` aliases so downstream apps can depend on multiple Grain git packages at the same SHA without tools.deps local-version conflicts (`a05c542`).

### Fixed
- Fixed O(N²) checkpoint handling in todo-processor-v2: added `:reverse?`/`:limit` to the event-store v3 read API across all three backends and rewrote three quadratic checkpoint sites into O(1) single-row index seeks, resolving timeouts as the SQLite event store grows (`dd2e21f`).
- Corrected several Datastar UI DSL behaviors: chained and nested effects now emit correctly terminated and ordered JS via a trailing-semicolon-aware `statement` helper (`a8f362d`); bound values sync before a blur event fires, preventing stale input values (`3db1781`); and nil entries are dropped from emitted UI payloads to avoid null keys (`24720f6`).
- Hardened todo-processor-v2 worker-pool shutdown (extended awaitTermination to 30s, fall back to shutdownNow, throw with active/queued counts on failure) to address flaky CI tests (`8e35241`).
- Trimmed two stray dependency lines from the event-tailer `deps.edn` left over from the prior aliasing change (`0b4d02d`).

### Docs
- Added distilled Allium v3 behavioural specs (`.allium` files) for all 37 components plus a grouped catalog at `docs/allium-specs.md`, distilled from source and tests and audited to pass allium check/analyse (`d7aa140`).
- Substantially expanded `docs/datastar-ui.md` (+621 lines) with fuller coverage of the UI IR DSL usage and patterns (`aac4687`), and documented event-tailer-backed SSE delivery across nodes and its relation to tenant-owner command/query routing (`4855b2f`).
- Minor readme prose edits (`d719562`).

### Internal
- Bumped documented package/git SHAs across `docs/packages.md`, `docs/code-agent-tools.md`, and `readme.md` (`0ab49d7`, `222c99e`, `b8b20bb`, `042cf65`, `b3970af`).

## May 2026 — Embedded SQLite v3 store, code-agent tooling, and example/readme polish

### Added
- Introduced an `event-store-sqlite-v3` component: a SQLite-backed implementation of the v3 event-store protocol for single-process/embedded deployments (WAL mode, `BEGIN IMMEDIATE` per append, tenant-scoped events table plus a normalized `event_tags` join table), with full protocol-parity, durability/concurrency, and control-plane conformance tests. Wired it into the workspace as a deployable `grain-event-store-sqlite-v3` project (alias `es-sq-v3`) mirroring the postgres-v3 component graph, relocating the control-plane conformance suite into the project test dir and documenting it (`4fab864`, `87efa8f`).
- Added the new `code-agent-tools` component and `grain-code-agent-tools` project (core, interface, tests, docs) providing tooling for code agents, wired into deps.edn and workspace.edn (`b68f736`).
- Bundled clj-kondo export config and a `grain_v2.clj` hook with `grain-core-v2` so consumers get linting and macro analysis for the v2 core macros out of the box (`18bcee6`).

### Changed
- Datastar now coerces scalar string query/command inputs (not just JSON) through their Malli schemas via a new string+json transformer, fixing decoding of form-style scalar inputs (`6120a4b`).
- Substantially rewrote the example-base and example-service code — commands, queries, read-models, todo-processors, periodic-tasks, schemas, and the demo — to align with current Grain APIs and patterns, plus a follow-up refactor of example-service and a `.gitignore` update (PR #11) (`97dd94d`, `b1fbf98`).

### Fixed
- Reworked todo-processor-v2 batch checkpoint logic (with related event-store-v3 schema tweaks) to prevent overlapping batch checkpoints from conflicting, with regression coverage in the interface test (`0fd269c`).
- Corrected grain-core's clj-kondo export to use a set rather than a vector for `:exclude-when-defined-by` so unused-binding exclusions are honored (PR #2 by bpringe) (`09109e1`, `d5cd286`).

### Docs
- Refined the readme's "What is Grain?" framing (shallow constraints / LLM-as-compiler narrative) and added links to two Scicloj conference talks (`f61aca5`).
- Reordered the readme to lead with the motivation before the how-to mechanics (PR #1 by bpringe) (`fec5001`, `2e80b9b`).
- Replaced the readme's ASCII architecture diagram with a Mermaid diagram for clearer rendering (PR #3 by bpringe) (`a4d9dbb`, `a357527`).
- Expanded the core-concepts doc with alias namespaces, an LMDB link, and require-form examples for aliases and referred symbols (PR #8) (`6bef79c`, `caa3559`, `8f580cd`).
- Corrected the deps coordinate key in the readme from `:sha` to `:git/sha` (PR #6) (`c5cb224`, `98f27e9`).
- Generalized wording in the readme and distributed-coordination doc, dropping the specific ZooKeeper/etcd/Consul list in favor of "no external coordination service required" (`c4136c3`).
- Updated namespace docstrings across example-service commands, queries, read-models and todo-processors (`edd2737`, `ca8a808`).

### Internal
- Cleaned up the Polylith poly check by adjusting workspace.edn and the control-plane/dspy-extensions deps.edn (`bc3af7b`).
- Updated the GitHub Actions tests workflow commands and restricted which branches/pushes trigger CI (`b3ce6b2`, `01287cd`).
- Isolated the todo-processor registry within the dynamic tenant replay test so runs don't leak shared registry state (`5fef92a`).
- Refined the workspace clj-kondo config to tune static-analysis linting behavior (`4a7093c`).
- Bumped documented package git SHA pins across docs/packages.md, code-agent-tools.md and the readme to current HEAD (`9fa2a98`, `5cf2d5e`, `9ed2ab0`, `6fd1ba2`).
- Added entries to `.gitignore` and merged remote main bringing in the readme why-before-how reordering (`e9dd3b2`, `4afd64f`).

## April 2026 — Tenant-poller correctness and CI hardening

### Added
- Added a shared `fressian-util` component that consolidates duplicated Fressian encode/decode helpers and registers custom handlers for `OffsetDateTime` and `Instant`, so `java.time` values can be stored in events and read-model reducers without manual epoch conversion (`ddb91e5`).
- Introduced a pluggable datasource abstraction for the Postgres v3 event store via a new datasource interface namespace, letting callers supply their own datasource, with accompanying datasource and integration tests (`08d1806`).

### Changed
- **Breaking:** Added a per-tick batched probe of `grain.tenants.last_event_id` so caught-up tenant/processor pairs skip their event-store read; this renames `EventStore/tenant-ids` to `tenants` (now returning per-tenant watermark maps) and moves the tenant watermark upsert after the CAS check to eliminate phantom watermarks (`464bbb7`).
- Dropped the redundant per-tick L1 cache clear from the control-plane coordinator, since its read models already set `:l1-ttl-ms 0` and revalidate against the event store, avoiding wiping unrelated read models that share the JVM cache (`ce037ec`).

### Fixed
- Stopped `:after` checkpoint effects from being re-fired for already-checkpointed events during control-plane reassignment or catch-up by adding an `already-checkpointed?` pre-check, with new idempotency unit tests and scenario assertions tracking duplicate effect executions (`aff2fd5`).
- Fixed tenant-poller watermark handling: newly-assigned tenants now seed their watermark lazily on first poll via `get-last-processed-id` (avoiding `:after nil` historical replay), and nil checkpoint lookups are memoized with a `::uninit` sentinel so uncheckpointed pairs stop re-querying Postgres every poll cycle (`560caac`, `4ed7c58`).
- Improved the Postgres v3 event store's Fressian deserialization to properly reconstruct Clojure data types from stored events (`3c050c7`).
- De-flaked the control-plane tests by replacing fixed-duration `Thread/sleep` + assert pairs with a polling `wait-for` helper that retries until the condition holds or times out (`b46978d`).
- Corrected a test helper's destructuring of the lease-ownership read model (keyed by tenant-id UUID rather than a `[tenant processor]` tuple), which had thrown on UUID `nth` and made scale-test report zero owners (`2412200`).

### Internal
- Added a GitHub Actions test workflow with parallel jobs covering `poly :all` against a Postgres service container plus live/routing/scale/churn/throughput scenario scripts and a shared setup-clojure composite action (`efc97d8`), later splitting the combined poly+PG job into separate poly-unit-tests, pg-v2-integration, and pg-v3-integration jobs each with its own Postgres service and database to fix v3's `CREATE POLICY` failing on a pre-existing v2-shaped `grain.events` table (`4a831d8`), forcing `:all` so shallow checkouts run all brick tests instead of silently passing zero (`8c18efb`), and removing the branch filter so Tests run on all branches (`3251893`).
- Tuned the heavy scenario jobs for shared CI: moved scale/churn/throughput jobs to the 8-core runner SKU (`7346161`), staggered the scale-test scenario-3 restart and raised `setup-node!` readiness to 180s (`9e32dd2`), merged the namespace-require retry and app-readiness poll into one eval per iteration with a 120s deadline (`307e9a1`), made `setup-node!` wait for `@app/app` to expose a populated `:control-plane` with a `:node-id` (`cce4824`), then dropped the three heavy scenario jobs from CI entirely since no large runners are provisioned, leaving the scripts for local use (`d7e1be0`).
- Added and then reverted temporary timestamped `println` probes around `cp/start`, `wait-for`, and `cp/stop` in the control-plane core-test to locate a CI hang (`6f03c7e`, `5b1ca35`).
- Bumped documented package SHA pins in `docs/packages.md` and `readme.md` to current HEAD (`9e86f13`, `f5a87e4`, `66c6ab8`, `64e0608`, `bec88e3`).

## March 2026 — Distributed control plane, event-store v3, and the v2 processor stack

### Added
- Landed the **event-store v3** subsystem: a tenant-scoped `EventStore` protocol with CAS/tag/type/as-of/after filtering and streaming reducible reads, an in-memory implementation, and a Postgres v3 backend in a new `grain-event-store-postgres-v3` project (`688152c`).
- Introduced the v2 processor stack on top of event-store v3 — `command-processor-v2`, `read-model-processor-v2`, and `todo-processor-v2` — plus a `command-request-handler-v2` component and a `grain-core-v2` project aggregating the v2/v3 stack (`db3afa5`, `1c728c8`).
- Built the distributed **control plane** across three phases: Phase 1 introduced the assignment/events/read-models/schemas subsystem with a Postgres `event-notifier-postgres` using LISTEN/NOTIFY and event-store-v3 notifier hooks (`e8f3c92`); Phase 2 switched todo processors from push-based pubsub to pull-based consumption (`a524e61`); graceful shutdown drain ordering with CloudWatch EMF metrics (`700a3f5`) and tenant-aware routing for ALB/HAProxy sticky load balancing with 503/Retry-After fallback (`9142691`) followed.
- Added a `defperiodic` macro and `periodic-task` component for declaring scheduled tasks (`fcf627b`), later extended with cron-expression scheduling and optional timezones (`be91194`).
- Added batch processing to todo-processor-v2 dispatch for higher throughput (`7e812c1`), and let command and todo handlers return a `:command-result/cas` map that is forwarded to event-store append, propagating `::anom/conflict` back to callers (`ca1309c`).
- Expanded the Datastar adapter substantially: unified GET/POST SSE stream reuse keyed on `[user-id,query-name,nonce]`, 3-arity routes with a defaults map, auto-generated auth-redirect interceptors from `:authorized?` predicates, and a new `:datastar/gate` for context-dependent guards (`151070d`); also added POST-request support for queries with a public `parse-datastar-signals` interceptor (`adad666`).
- Added an in-process LRU L1 cache over the durable LMDB L2 tier for read-model-processor-v2, plus a segmented/partitioned LMDB access model to avoid fressian deserialization on hot reads (`36f2522`).

### Changed
- Packaged the control plane as its own Polylith project `grain-control-plane` (depending on event-store-v3, read-model-processor-v2, todo-processor-v2, periodic-task), moving the Postgres integration test into `grain-event-store-postgres-v3` and de-flaking the DR2 test (`155920e`); also threaded an app `:context` map through `control-plane/start` to tenant poller handlers so processors can reach app-specific dependencies (`3f0d07e`).
- **Breaking:** Reduced the control-plane tenant leasing footprint to per-node rather than per-node-plus-processor, reworking assignment, events, read-models, and schemas (`b43bbc6`).
- Optimized control-plane tenant watermark polling and reworked todo-processor-v2 dispatch onto a cached thread pool for better concurrency under load (`afa3328`, `1e0d73c`).
- Streamlined the Datastar per-tab SSE nonce logic: a unique `__ds_nonce` per page load is folded into the SSE session key to fix cross-tab stream collisions, then simplified (`e5b71d1`, `99589c9`); also let the page `:head` option be a thunk for dynamic head content (`038a341`).
- Removed redundant Started/Finished metric logs from the command and todo processors (`u/trace` already emits the metrics), and stripped `u/trace` from in-memory event-store read paths so the tenant poller no longer floods logs on empty polls (`f797247`, `9839b54`).
- Resolved a tools.deps path collision when consuming both grain-core-v2 and grain-control-plane by adding then reverting a shared-deps include in favor of a `control-plane/` namespace prefix (`7bc918c`, `9ab26ec`, `0f8b404`).

### Fixed
- Raised the LMDB default `maxReaders` to 1024 with retry-on-`ReadersFullException`, and made the v2 todo-processor poller skip re-submitting in-flight `(tenant,processor)` tasks so slow effect handlers no longer fire duplicate LLM calls each poll cycle (`b62a1c5`).
- Fixed cross-partition move detection in partitioned read models — replaced postwalk with deep-clojurize in fressian-decode so compound keys survive round-trip, added a lazy `{eid->pk}` entity index for O(1) partition lookups, and kept partition state synced to L2 (`0028071`); also fixed an NPE in the single-partition relevance filter by guarding the speculative reducer probe (`74dae8b`).
- Incorporated tenant-id into the read-model-processor-v2 cache key so cached projections cannot leak across tenants (`9497a34`), and threaded tenant-id through event-store-v3 pubsub publish/subscribe so subscribers receive correctly tenant-scoped notifications (`5941d7b`).
- Fixed fressian decoding to coerce non-vector `java.util.List` values back into Clojure vectors, preserving vector type through LMDB round-trips (`4b9ae07`).
- **Breaking:** Renamed the Datastar stream suffix to `/__stream` to avoid colliding with parameterized routes, normalized the root path, resolved path params in shim stream URLs, surfaced real event-store anomalies in command-processor-v2, and added warnings for missing query schema / `:authorized?` predicate (`6fed1f5`).
- Fixed Datastar poll-and-render to diff on rendered `:datastar/hiccup` rather than `:query/result`, so dynamic hiccup over a static query result re-renders after initial load (`6126ac1`).
- Stopped stringifying POST JSON signal values so native types (e.g. integers) survive, aligning POST signal parsing with GET, and updated the affected core_test assertions (`4d20714`, `b6d9e5e`).
- Added `Cache-Control: no-store` to the Datastar shim page so per-page-load nonces are never served from cache (`25219e7`).
- Aligned Datastar command/query authorization checks with the command/query request handlers by inverting them to the positive `authorized?` branch (`5f0f5f5`).
- Corrected deps.edn aliases in the grain-core-v2 project (`0900cb2`).

### Docs
- Condensed the sprawling readme into focused docs (core-concepts, datastar, distributed-coordination, packages), shrinking readme.md from ~497 lines to a concise overview (`5c00770`).
- Expanded Datastar docstrings on core and interface functions (~120 lines) and finalized readme datastar usage documentation alongside grain-datastar project deps (`0bbddc6`, `218dfae`).

### Internal
- Added test coverage for todo-processor-v2 and the control plane plus live-test scenarios (`43a55a3`).
- Tagged many non-tenant-aware interface namespaces with deprecation markers as part of the v2/v3 transition (covered under `1c728c8`).
- Routine documented-SHA bumps and package-doc refreshes (`148763a`, `543484f`, `bcebce4`, `e2ba950`, `46c143c`, `6fb0705`, `3a20d65`, `937dd47`, `d921a3f`, `f82bb33`, `a90aa2b`, `4f5f0bb`, `ae0172e`, `1273aee`, `a66ae41`, `3171535`, `a0c5994`, `ecf3aff`, `a649134`, `dc52b32`, `fb3c7f2`, `cf61a39`, `98b03d6`, `9d36f9b`, `bdcb56e`).

## February 2026 — Datastar reactive UI and read-model registry

### Added
- Introduced the `datastar` component: a Pedestal-style interceptor and route stack for server-rendered Datastar SSE UIs, providing `stream-view`, `shim-page`, `action-handler`, `render-html`, and `patch-elements`/`patch-signals` helpers, with extensive tests (`8fbe33b`).
- Added tag-based event filtering for Datastar streams: `stream-view`s can declare `:event-tags` resolved from the query context into a subset predicate used as a channel transducer, so SSE re-renders fire only for events whose `:event/tags` match the subscriber's scope (`080b16e`).
- Added a global read-model registry with a `defreadmodel` macro and a registry-driven `project` fn supporting scoped projections (`:tags`/`:queries` hashed into the cache key), wired Datastar to the registry, plus a clj-kondo hook so `defreadmodel` lints as `defn` (`20e99de`).
- Added batch-query support to event-store v2: `read` now accepts either a single query map or a vector of query maps, where vector queries OR together — merging and deduplicating by `:event/id` and ordering by id per the dcb.events spec — implemented for both the in-memory and Postgres stores with new integration and read-batch test suites (`9109383`, `6c8e7be`).
- Added global command and query registries with `defcommand` and `defquery` macros plus matching clj-kondo lint hooks, expanded command/query processor and request-handler tests, and a substantially rewritten readme (`829303f`).

### Changed
- Datastar command decoding now uses a strip-extra-keys malli transformer to drop unknown signal keys before validation, and the shim-page interceptor moves `@get data-init` onto the body, supports `:html-attrs`, and surfaces `:error/explain` on command anomalies (`7e37b6c`).
- Switched read-model cache snapshot serialization from EDN strings to binary Fressian encode/decode for speed, with an LMDB kv-store tweak and expanded interface tests (`ed55e97`).

### Fixed
- Read-model fressian-decode now postwalks results to coerce `java.util.Set` instances back into Clojure sets, and `add-watermark` no longer assocs a nil `:after` when there is no watermark (`7861952`).

### Internal
- Added a clj-kondo hiccup import config (lint-as `defhtml`, analyze-call hook for `defelem`) to suppress false linter warnings on hiccup macros (`b970fd1`).
- Refactored the read-model-processor interface fn to delegate straight to `core/p` rather than reimplementing logic inline (`49b8b75`).
- Bumped and corrected the pinned git SHAs in the readme deps coordinates (`d9b021e`, `f045992`, `85510d5`).

## January 2026 — Authorization gates and composable command handlers

### Added
- **Breaking:** Added an `:authorized?` predicate key to command and query metadata; the request handlers now evaluate it against the context before dispatch, returning a `::anom/forbidden` "Unauthorized" response when the predicate is absent or false, gating the entire CQRS request path (`32a3b15`).
- Added a `:command-processor/skip-event-storage` context flag that returns the command result without appending its events to the event store, enabling reliable pass-through when composing command handlers (`840f584`).

### Internal
- Refreshed the documented package SHA pins in the readme (`3caba35`, `01a3e04`).

## December 2025 — Self-registering commands & queries, first-class read models

### Added
- Introduced global command and query registries (`register!`/`global-registry`) backed by registry atoms, letting handlers self-register so `process-command`/`process-query` can fall back to the global registry, with accompanying clj-kondo hooks and interface tests (`fd31573`). Finalized the `defcommand`/`defquery` macros (with optional opts map and docstring) and consolidated their clj-kondo hooks—alongside `defschemas`—into grain-core's `grain.clj`, retiring the redundant dspy-extensions hook config so static analysis understands the macros (`5715dee`).
- Added a `kv-store` protocol abstraction with an LMDB-backed `kv-store-lmdb` implementation, plus a `read-model-processor` that elevates read models to first-class status with transparent incremental snapshotting and standardized logging/metrics (note: LMDB requires `java.nio --add-opens` JVM flags) (`ca47fda`).

### Docs
- Substantially rewrote the readme to document the toolkit, including the new `defcommand`/`defquery` and read-model-processor capabilities (`452b5c2`).
- Added detailed docstrings to the command-processor and query-processor interface namespaces, documenting the new registration and macro APIs (`7eb0038`).

### Internal
- Bumped five documented package/dependency SHAs referenced in the readme (`f8ed78a`).

## November 2025 — Test coverage and resilient request handling

### Added
- Established interface test coverage for the core command/query execution path: `command-processor` and a parallel suite for the request handlers (`aa6c635`).
- `query-request-handler` now merges `:grain/additional-context` from the HTTP context into the query config before processing, matching the command path, with tests covering both handlers (`2d065bd`).

### Changed
- `query-processor` now validates queries against the general `::query-schema/query` in addition to the per-query schema, and wraps handler invocation in try/catch to return an `::anom/fault` anomaly instead of throwing (`3093b10`).
- Raised the core.async pubsub channel buffer to 1024 and bumped the `todo-processor` in-chan buffer to reduce backpressure and drop risk under load, the latter alongside new processor tests (`f36aa01`, `a7fdafb`).

### Fixed
- `command-processor` now wraps command handler execution in try/catch, converting thrown exceptions into an `::anom/fault` anomaly propagated back to `command-request-handler` instead of escaping (`5b9bdf8`).
- Corrected swapped HTTP status codes in `query-request-handler` so `::anom/forbidden` returns 403 and `::anom/conflict` returns 409, with accompanying tests (`fb883bb`).

### Internal
- Routine maintenance bumps to documented package SHA references in the readme (`c7c8c79`, `cbaf084`).

## September 2025 — HTTP context plumbing for command handlers

### Added
- Command handlers can now receive per-request context: HTTP interceptors may `assoc` `:grain/additional-context` onto the Pedestal context, which the command-request-handler merges into the grain context before invoking the handler (the `config` arg was renamed to `grain-context` accordingly) (`fcc065b`). An earlier approach added an optional `:http-response-fn` key to the command registry for transforming the HTTP response from the command-result, but this was subsequently reverted (`67b1fe5`).

### Changed
- **Breaking:** Reverted the `:http-response-fn` registry hook in favor of having the command-request-handler `assoc` `:grain/command` and `:grain/command-result` onto the Pedestal context alongside the response. This keeps HTTP concerns out of the command registry and lets downstream interceptors shape the HTTP output in user space without implicating the command-processor (`b1b7807`).

### Docs
- Documented the public functions of the behavior-tree-v2 interface namespace with docstrings (`fc73023`).

### Internal
- Routine syncs of the four documented package SHAs in readme.md (`411984a`, `102f760`, `3664e6c`, `78ce148`).

## August 2025 — Transit wire format, decoupling the Python dependency, and the first public README

### Added
- Added a top-level `requirements.txt` declaring the Python dependencies for the `grain-dspy-extensions` package (`3eb61b5`).

### Changed
- **Breaking:** Switched the command and query request handlers from plain JSON to transit+json encoding, preserving richer Clojure data types over the wire (`a2122f0`).
- **Breaking:** Extracted the behavior-tree DSPy extensions into a standalone `grain-dspy-extensions` project so `grain-core` no longer carries a hard Python dependency; also removed the legacy `behavior-tree` and `event-store`/`event-store-postgres` components, pruned placeholder interface tests, and added a LICENSE (`c289b3c`).
- Migrated the cljc conversion: anomalies, event-store-v2, pubsub, schema-util, and time interfaces moved to cross-platform `.cljc`, with the event-store-v2 in-memory backend rewritten (`b340595`).
- Reworked `clj-dspy` Python generation to emit fully typed Pydantic BaseModels for DSPy signatures, integrated more tightly with malli's default registry and additional schema forms (maps as TypedDict, `:maybe`/`:or`/`:enum`, refs), and pruned the now-unneeded public API surface (`11e9341`, `9cc091a`, `bff1efa`, `19f3f89`).
- Defaulted the example-base event-store connection to `:in-memory` and dropped the Postgres-specific log-truncation transform, simplifying the out-of-box demo (`8b29142`).

### Fixed
- Corrected the command request handler so `::forbidden` returns HTTP 403 and `::conflict` returns 409, which were previously transposed (`680ddba`).
- Fixed `clj-dspy` malli-to-Python schema conversion for nested/collection schemas and for map schemas carrying an options/properties map, and removed a stray debug print (`6c7d884`, `cb3d3be`, `1263323`).
- Removed a malformed comment block in the behavior-tree-v2 engine that caused a read/syntax error (`9555df8`).

### Removed
- Deleted three scratch exploration files (`blah.clj`, `bt_dspy_clean.clj`, `bt_dspy_clean_2.clj`), roughly 1,095 lines of throwaway code (`19f3f89`).

### Docs
- Added the first substantial draft of the top-level README introducing the Grain toolkit, followed by extensive expansions, rewrites, and a fixed broken link (`38ea779`, `ecc1a18`, `c1eca20`, `bee8ac4`, `8a21606`, `24e6cde`, `4c89df5`, `fbe7a4c`, `80cf557`, `2320231`).

### Internal
- Bumped documented package SHA pins in the README across several updates (`2a39b5f`, `f94fd70`, `47fd3e1`).
- Added clj-kondo/linter suppression annotations across numerous component interface namespaces with no behavior change (`dcf2f63`, `5759ba4`).
- Removed the `.portal/vs-code.edn` editor scratch file and merged main (`8c518a3`, `fbdf1e1`).

## July 2025 — clj-dspy and behavior trees, plus a streaming Postgres event store v2

### Added
- Introduced **clj-dspy**, a Polylith component providing a Clojure DSL over the Python DSPy library for declarative LLM programs, with `defsignature`/`defmodel` macros; grew from an initial prototype into the extracted component (`5672078`, `e1df826`).
- Landed the original **behavior-tree** component (blackboard, builder, nodes, schemas, interface) along with early DSPy behavior-tree examples (`abe0214`).
- Added a new **behavior-tree-v2** component with engine, nodes, long-term-memory, protocol, and interface namespaces, rebuilding the tree subsystem around event-sourced memory, plus a development example demonstrating a dspy-style workflow on it (`ff89edc`, `a630e49`).
- Added an event-sourced LLM activity log for dspy nodes in the original behavior-tree component, recording LLM interactions as events (`40982b7`).
- Introduced the new **event-store-postgres-v2** component with streaming reduce-based reads and `insert-events`, and added a matching deployable `grain-event-store-postgres-v2` project (`0e7189f`, `ad81bea`).
- Wired event-store-v2, behavior-tree, and clj-dspy into the grain-core distribution (`13f080c`, `0d7a9ec`).
- Added a Babashka scaffolding script (`scripts/create_component.bb`) that generates the standard Polylith layout for new grain components (`d3a834e`).

### Changed
- **Breaking:** Redefined the event-store-v2 read protocol to return a reducible stream rather than a lazy seq, and reworked the in-memory read to return a reify implementing `IReduceInit`/`IReduce` to match the Postgres-v2 implementation, enabling memory-efficient streaming reads (`0e7189f`, `5fe3778`).
- Began porting Grain components to ClojureScript: renamed interface/core files in event-store-v2, pubsub, anomalies, schema-util, and time to `.cljc` so they compile under both ClojureScript and JVM Clojure (`7f6bd91`).
- Iterated on the behavior-tree-v2 engine and public interface, expanding the interface namespace and wiring long-term-memory into execution (`ff0dedd`).
- Restructured the original behavior-tree component (extracting a protocols namespace, trimming builder/nodes) and added snapshot support to the blackboard for capturing and restoring tree state (`256fdde`).
- Extended the behavior-tree blackboard and execute interface so callers can seed initial blackboard state when running a tree, alongside broader blackboard/interface work and expanded interface tests (`a0df740`, `4372b19`).
- Made behavior-tree condition nodes merge their opts into the evaluation context, with a reusable has-value/DSPy-module example (`33cda4c`).
- Changed clj-dspy so `defsignature` returns a live Python object for direct DSPy interop and stashed the original definition map in var metadata for both `defsignature` and `defmodel`; namespaced generated Python classes to avoid collisions (`86aed1d`, `81edaa0`).
- Migrated the example app (base, read-models, demo script) onto the new Postgres event-store-v2 and its updated read pattern (`23fa61e`).
- Relaxed input validation in the dspy node of the bt-dspy-clean-2 example (`461a82a`).

### Fixed
- Aligned event-store tag-query semantics with the DCB.events spec: in-memory now matches when query tags are a subset of an event's tags, and Postgres-v2 matches via containment rather than exact match, including an operator correction (`f8c3ec4`, `0126f97`, `d3f5364`).
- Fixed the Postgres-v2 CAS insert path to persist the processed `events*` binding, stopped attaching empty tx-metadata when none is supplied (and required the core namespace from the interface for correct loading) (`898b644`, `acbe6aa`).
- Fixed behavior-tree-v2 long-term-memory to act only on non-empty event sets and to advance the latest-event-id cursor only when new events are present, and added the missing `nodes` require in the engine so node implementations load before the tree is built (`324671c`, `ef0003d`, `dd6dd92`).
- Fixed the command-processor component's deps.edn and a test require so it builds and resolves correctly (`b452fe8`).
- Fixed the example-service average calculation to skip counters lacking a `:counter/value`, preventing a skewed denominator (`0fd7477`).
- Corrected a typo in the grain-core deps.edn dependency declaration (`606bd51`).

### Removed
- Removed redundant initialization logic from behavior-tree-v2 long-term-memory (`9ebb831`).
- Removed two superseded DSPy customer-service behavior-tree example files (`3a4f02b`).

### Internal
- Merged main into the BTs branch, reconciling the event-store-v2 and event-store-postgres-v2 tag-query fixes (`ba05cc5`).
- Extended clj-kondo config with lint hints for the new clj-dspy `defsignature`/`defmodel` macros (`4265a31`).
- Cleaned up whitespace and paren alignment in the Postgres-v2 reify/IReduce block and simplified a REPL comment (`adb940e`).
- Added a dependency entry to the grain-core project's deps.edn (`835fe34`).
- Removed the large CLAUDE.md as part of the cljc portability work (`7f6bd91`).

## June 2025 — Genesis: the CQRS/event-sourcing toolkit and event-store v2

### Added
- Bootstrapped the Polylith workspace skeleton (`351cbcd`) and landed the entire Grain CQRS/event-sourcing toolkit as ~20 components — event-store with Postgres backend, command/query processors and request-handlers, schemas, pubsub, periodic-task, anomalies, time, webserver, and todo-processor — plus the grain-core project (`dbe7d27`).
- Began the event-store v2 overhaul: scaffolded the new event-store-v2 component (deps, interface, test stub, root registration) (`687d220`), grew its interface as the v2 API took shape (`d5efd50`), and then fleshed it out with a protocol, malli schemas, and an in-memory implementation, migrating the example app and command/todo processors onto it (`97f27cd`).
- Added an in-memory implementation of the original event-store protocol for Postgres-free testing and development (`0aab755`).
- Introduced the event-model component, a formal malli schema for commands, events, views, todo-processors, screens, flows and their valid connections (`c19069a`), and extended it with periodic-task entities (`00c4ccc`).
- Added a full example-service component and example-base reference app covering commands, queries, read-models, todo-processors, periodic-tasks, and schemas (`cf9675f`).
- Added a `now-from-ms` helper to the time utilities for building UTC timestamps from epoch milliseconds (`429e985`).
- Added a new grain-mulog-aws-cloudwatch-emf-publisher project for emitting mulog logs as CloudWatch EMF metrics (`18e7322`).

### Changed
- Extracted the Postgres event-store into a standalone event-store-postgres component with an optional grain-event-store-postgres project, reworking the core event-store to depend on an interface protocol rather than inlined Postgres code (`da41ef0`).
- **Breaking:** Consolidated the standalone command-schema and event-schema components into command-processor and event-store as `interface.schemas` namespaces, deleting the separate components (`aa1e816`).
- **Breaking:** Removed the required `[:version [:enum 1]]` key from the event-model schema, dropping the version constraint on event models (`1cc0fb4`).
- Added schema coercion when reading events from the Postgres event-store, decoding each event through the general and per-event-name schemas via a malli json-transformer (`78893ef`), and consequently dropped now-redundant manual UUID casts in example-service read-models (`59f7ef3`).
- Taught the command- and query-request-handlers to additionally decode against the specific per-command-name and per-query-name schemas after the general schema, enabling request-specific coercion (`1325d74`, `8808912`).
- Moved average computation in example-service from the todo-processor into the command handler, aligning the example with event-modeling conventions (`0d40419`).

### Fixed
- Corrected the default `start-event-store` method to destructure `:type` from the nested `:conn` map so the unsupported-type error fires correctly (`f732402`).

### Docs
- Substantially expanded CLAUDE.md with Polylith structure, CQRS/event-sourcing patterns, and component conventions (`34a803e`), added further agent guidance (`5d5c2ff`), and clarified that events read from the event-store never carry an `:event/body` key (`cd609a9`).

### Internal
- Cleaned up unused requires and compiler warnings across command-processor, command-request-handler, pubsub, query-processor, and todo-processor (`5c0ea78`).
- Added clj-kondo config imports for potemkin and tweaked the Portal VS Code edn config (`6cdb7c6`).
- Registered the new project alias in workspace.edn (`430263c`) and rebalanced dependency declarations between the event-store-postgres component and its project (`6c028cc`, `038638b`).
- Extended .gitignore and removed an accidentally committed Calva REPL output file (`2299252`).

