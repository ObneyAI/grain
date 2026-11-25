# Read models

Define a keyed model with `:key` or `:route` to fold tenant events into
per-instance state. Look up an instance by ID, page through an index,
search by text, or join results across models. Results are cached in LMDB.

## Context

Reads need a started event store, a cache and a tenant UUID:

```clojure
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
         '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.interface :as lmdb])

(def tenant-id (random-uuid))
(def context
  {:event-store (es/start {:conn {:type :in-memory}})
   :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir "./data/read-models"
                                           :db-name "read-models"
                                           :map-size (* 64 1024 1024)}))
   :tenant-id tenant-id})
```

Use one cache directory per event store. Set `:map-size` for the expected
data and indexes. The default is 10 MiB. Start the JVM with
`--add-opens=java.base/java.nio=ALL-UNNAMED` and
`--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`.

Define each event type with `defevent` before appending instances with `->event`.

```clojure
(es/defevent :crm/customer-registered
  "A customer registered."
  {:schema [:map [:customer-id :uuid] [:first-name :string]
                 [:last-name :string] [:email :string]]})

(def customer-id (random-uuid))
(es/append (:event-store context)
           {:tenant-id tenant-id
            :events [(es/->event {:type :crm/customer-registered
                                  :body {:customer-id customer-id :first-name "Ada"
                                         :last-name "Lovelace" :email "ada@example.com"}})]})
```

## Keyed models

```clojure
(defreadmodel :crm customers
  {:events #{:crm/customer-registered :crm/customer-renamed :crm/customer-closed}
   :version 1
   :key :customer-id
   :state-schema [:map [:customer-id :uuid] [:first-name :string] [:last-name :string]
                  [:email :string] [:closed? {:optional true} :boolean]]
   :include? (fn [customer] (not (:closed? customer)))
   :indexes {:name (rmp/order-key [:last-name :first-name])}
   :searches {:text (rmp/search-text [:first-name :last-name :email])}}
  "One customer per :customer-id."
  [customer event]
  (case (:event/type event)
    :crm/customer-registered (merge customer (select-keys event [:customer-id :first-name :last-name :email]))
    :crm/customer-renamed (merge customer (select-keys event [:first-name :last-name]))
    :crm/customer-closed (assoc customer :closed? true)))

(rmp/value context :crm/customers customer-id)
;; => {:customer-id #uuid "..." :first-name "Ada" :last-name "Lovelace" :email "ada@example.com"}

(rmp/page context :crm/customers {:index :name :page 1 :limit 25})
;; => {:entries [{:key #uuid "..." :value {...}} ...]
;;     :total 1 :total-pages 1 :page 1 :page-size 25 ...}
```

Here `:key` sends each event to a customer, `:include?` hides closed customers
from results, and `:indexes` orders the list by last and first name. Read
`state` to inspect a closed customer's reducer state.

`defreadmodel` registers `:crm/customers` and defines the reducer var
`crm-customers`. Reloading the same var replaces its declaration. Two
documented declarations in different live vars cannot claim the same name.
For programmatic registration use `register-read-model!`.

### Declaration options

| Option | Default | Meaning |
|---|---|---|
| `:events` | required | Non-empty set of qualified event types the model reads. |
| `:version` | `1` | Positive integer. Part of the storage identity; see [Changing a model](#changing-a-model). |
| `:key` | | `(event -> id)`. The instance that receives the event. |
| `:route` | | `(event -> [[id input] ...])`. Zero or more instances and the input each reducer call receives. |
| `:initial` | `{}` | Starting state of every instance. A value, not a function. |
| `:result` | `identity` | `(state -> result)`. The value that `value`, `values`, `page` and `query` return, and that indexes and searches read. |
| `:include?` | include all | `(result -> truthy)`. Excluded instances keep their state but have no readable result, index entries or count. |
| `:indexes` | `{}` | Map of index name to `(result -> sort value)`. `:identity` is reserved and always present. |
| `:searches` | `{}` | Map of search name to `(result -> string or nil)`. |
| `:filtered-indexes` | `#{}` | Index names (or `:identity`) that also store membership data for `filtered-key-page`, `joined-page` and some query plans. Experimental. |
| `:state-schema` | | Malli schema checked against the state after every reducer call. |
| `:result-schema` | | Malli schema checked against every result before it is stored. |
| `:start-after` | | Exclusive UUIDv7 replay boundary for ordinary and keyed models. Applies to scoped and partitioned reads too. |

Supply exactly one of `:key` or `:route`. Registration rejects unknown options
and ordinary-only options such as `:schema` and `:l1-ttl-ms`.

Use `:route` when one event affects several instances, or none:

```clojure
(defreadmodel :bank balances
  {:events #{:bank/transfer-made}
   :route (fn [{:keys [from to amount-cents]}]
            [[from (- amount-cents)] [to amount-cents]])
   :initial 0
   :state-schema :int
   :result (fn [cents] {:cents cents})
   :result-schema [:map [:cents :int]]
   :indexes {:cents :cents}}
  [cents delta]
  (+ cents delta))
```

With `:route` the reducer receives the routed input, not the event. Inputs are
applied in the order returned. An empty sequence skips the event; `nil`, a
malformed pair or a `nil` ID is an error. All inputs from one event commit
together.

Keep routing, reducers and extractors pure. Catch-up may evaluate them more
than once before a batch commits.

Instance IDs and index values support booleans, strings, signed 64-bit integers,
UUIDs, keywords and vectors of those values. Index values can also be `nil`.
Each encoded index key, including its ID, must fit in 511 bytes.

### Reading

| Function | Returns |
|---|---|
| `(value ctx model id)` / `(value ctx model id not-found)` | The instance's result. `not-found` (default `nil`) when the instance is absent or excluded. |
| `(state ctx model id)` / `(state ctx model id not-found)` | The instance's state, including excluded instances. |
| `(values ctx model)` / `(values ctx model selection)` | A reducible of `{:key id :value result}` in index order. Use `into`, `reduce` or `transduce`. |
| `(page ctx model options)` | One page of `{:key id :value result}` entries with exact counts. |
| `(progress ctx model)` | `{:watermark event-id :checked-through event-id}`: the last event folded and the head the model was last checked against. |

A selection picks an index and an optional range of it:

| Key | Meaning |
|---|---|
| `:index` | Index name, default `:identity` (instance IDs in string order). |
| `:direction` | `:asc` (default) or `:desc`. |
| `:equals` | Entries whose index value equals this value. |
| `:prefix` | Entries whose vector index value starts with these complete components. `[true]` matches `[true 3]`, not scalar `true`; `[]` matches every vector. |
| `:prefixes` | `page` only. A vector of prefixes; the page covers their union in index order, without duplicates. `[]` selects nothing. |

`:equals`, `:prefix` and `:prefixes` are mutually exclusive. `page` also takes
`:page` (default 1) and `:limit` (default 25). Out-of-range page numbers clamp
to the last page.

```clojure
(rmp/page context :crm/customers {:index :name :direction :desc :limit 2})

(into [] (map :key) (rmp/values context :bank/balances {:index :cents :equals 300}))
```

Pages use rank data stored with each index, so counting a selection and
skipping to page N do not read the skipped entries. Only the returned results
are decoded.

Entries with equal index values are ordered by the string form of their ID,
ascending, in both directions.

### Indexes

An index extractor is any pure function of the result. `order-key` builds one
from result fields and records which fields it reads, which `query` needs to
match orders to indexes:

```clojure
:indexes {:name (rmp/order-key [:last-name :first-name])  ; ["lovelace" "ada"]
          :status (rmp/order-key [:status])               ; scalar
          :company :company-id}                           ; raw value
```

`order-key` lowercases strings with `Locale/ROOT`, turns `nil` into `""`,
uses a keyword's name, and keeps other values as they are. One field gives a
scalar sort value, several give a vector. Use a raw keyword extractor such as
`:company-id` when the index is used for exact lookups of IDs.

### Text search

`search-text` joins result fields with spaces. Searches are maintained in the
same transaction as results.

```clojure
(rmp/search-keys context :crm/customers {:index :text :query "love"})
;; => [customer-id]
```

`search-keys` returns the IDs of included instances whose text contains the
query as a literal, case-insensitive substring. `:mode` is `:contains`
(default, needs at least 3 characters), `:prefix` or `:suffix` (at least 1).
`:prefix` and `:suffix` match the start or end of the whole space-joined text,
not of each field: with the extractor above, prefix `"ada"` matches
"Ada Lovelace ada@example.com" but prefix `"lov"` does not, and suffix `".com"`
matches every customer with a .com address. Shorter queries throw
`:grain.read-model/short-search`. Search returns IDs only; load results with
`value`, order the IDs with `key-page`, or use `query`.

### Queries across keyed models

`query` pages over one root model, joined to-one with related models, with
optional search and a named order. It does not create another stored model.

```clojure
(defreadmodel :billing ledger
  {:events #{:billing/charge-posted}
   :key :customer-id
   :initial {:balance-cents 0}
   :indexes {:balance (rmp/order-key [:balance-cents])}
   :filtered-indexes #{:balance}}
  [ledger event]
  (update ledger :balance-cents + (:amount-cents event)))

(def customer-list
  {:from {:customer :crm/customers}
   :related {:ledger {:model :billing/ledger
                      :key [:id :customer]
                      :missing [:default {:balance-cents 0}]}}
   :search [:customer/first-name :customer/last-name :customer/email]
   :orders {:name [:customer/last-name :customer/first-name]
            :balance [:ledger/balance-cents]}})

(rmp/query context customer-list {:order :name :search "love" :page 1 :limit 25})
;; => {:rows [{:key customer-id :customer {...} :ledger {:balance-cents 0}}]
;;     :total 1 :total-pages 1 :page 1 :page-size 25
;;     :work {:strategy :indexed-matches :sort-keys-read 1}}

(rmp/query-models customer-list)
;; => {:billing/ledger 1, :crm/customers 1}
```

The definition has four keys:

| Key | Meaning |
|---|---|
| `:from` | Exactly one `{alias model}`. Each included root instance is one row. |
| `:related` | `{alias {:model m :key expr :missing policy :validate f}}`. Each relationship looks up one instance. |
| `:search` | Result fields searched as one space-joined string. Optional. |
| `:orders` | `{order-name [expr ...]}`. The request picks one by name. |

Expressions:

- `:customer/last-name` reads a field of the `:customer` binding's result.
- `[:id :customer]` is the binding's instance ID; `[:value :ledger]` is its
  whole result.
- `[:param :rate]` reads `(:params request)`.
- `[:call f expr ...]` calls a pure function with the evaluated arguments.

A relationship's `:key` is `[:id alias]` or a field reference to an earlier
binding. Relationships are required by default: a missing related result
throws `:grain.read-query/missing`. `:missing [:default v]` substitutes `v` for
an absent result; a stored `nil` stays `nil`. `:validate` is called as
`(f lookup-key value)` for every related result the query reads; throw from it
to report a domain error. Its return value is ignored. `:key` is reserved and
cannot be an alias.

The request accepts `:order` (required), `:search` (default `""`),
`:direction`, `:page`, `:limit` and `:params`. Rows contain `:key` (the root ID)
and each binding's result under its alias. `:work` reports the strategy Grain
chose (`:indexed`, `:indexed-matches`, `:ranked-matches`, `:related-groups`,
`:related-matches`, `:joined` or `:computed`) and what it read. A non-empty
search shorter than 3 characters returns no rows, a `:search-error` message
and the strategy `:unsupported-short-search`.

`query-models` validates the definition and returns the `{model version}` map
that a query endpoint's `:grain/read-models` subscription expects.

What the planner supports, checked when the definition is compiled (by
`query-models` or `query`):

- A plain field order needs an index on that binding built with `order-key`
  over the same fields. The binding must be the root or share the root's ID
  (`:key [:id root]`). For a related model, the order is served directly from
  its index only when both models include exactly the same IDs. Otherwise it
  needs `:missing [:default ...]` and the order index in `:filtered-indexes`,
  and is served by a join; without them the query fails when it runs.
- A single-field order on a model related through a field of the root (for
  example `:key :customer/company-id`) needs an exact equality index on the
  root for that field, such as `{:indexes {:company :company-id}}`.
- Search fields must start with the root's fields, optionally followed by
  fields of one related model reached the same way. Each group needs a
  `search-text` extractor over the same fields in the same order. A related
  search binding cannot have a non-nil `:missing` default.
- `[:call ...]` orders evaluate every candidate row and report `:computed`.
  Use them when an order has to be calculated; plain field orders never fall
  back to scanning.

In the example, an uncharged customer sorts with a balance of zero. The ledger's
filtered index and the relationship's default let the query include customers
without a ledger entry.

For an unsupported plan, inspect `:path`, `:hint` and `:suggested-extractor`
in the exception data.

### Event scopes

By default a keyed model reads every subscribed event for the tenant.
`scoped-context` restricts which events are folded:

```clojure
(let [scoped (rmp/scoped-context context {:tags #{[:user user-id]}})]
  (into [] (rmp/values scoped :enrollment/applications {:index :user :equals user-id})))

(rmp/scoped-context context
                    {:queries [{:tags #{[:user user-id]}}
                               {:tags #{[:application application-id]}}]})
```

`{:tags #{[tag uuid] ...}}` selects events carrying all of the tags.
`{:queries [...]}` takes a union of query maps, each with optional `:tags` and
`:types`; types are intersected with the model's `:events`, and an event that
matches several queries is folded once. Empty tag sets, type sets or query
vectors select nothing. `nil` removes the scope.

Each scope is a separate materialization with its own state, indexes, counts
and progress, built on first read. Reading one scope never catches up another
scope or the unscoped model. Equal scope maps share one materialization, also
across restarts. Index selections such as `:equals` filter results after
folding; they do not limit which events are read. The scoped context works
with every keyed read function, `query` and `read-session`.

### Read sessions

Each read function catches up its model and reads in its own snapshot. To read
several models, or one model several times, from one consistent snapshot, use
`read-session`:

```clojure
(rmp/read-session context [:crm/customers :billing/ledger]
  (fn [session]
    {:customer (rmp/value session :crm/customers id)
     :balance (:balance-cents (rmp/value session :billing/ledger id))}))
```

The session catches up the named models once, then calls `f` inside one LMDB
read transaction. Reads must finish, and reducibles from `values` and
`index-values` must be consumed, before `f` returns. Reading a model that was
not named throws. Sessions cannot be nested. The two-argument form,
`(read-session [[ctx-a models-a] [ctx-b models-b]] (fn [[a b]] ...))`, prepares
several contexts at once, for example different scopes or tenants. They must
share one cache and event store. `query` opens a session for its models.

The snapshot is of the cache. Each model is caught up to the head captured at
the start of the session; the session does not make independent models a
transaction over the event log.

### Lower-level reads

`query` is built from these functions. Use them when a query definition cannot
express what you need. They read index metadata and IDs, not results, unless
noted.

| Function | Purpose |
|---|---|
| `index-groups` | Distinct values of an index with their counts, ascending. With a values argument, only those groups, in the given order. |
| `index-keys` | IDs in one `:equals` group, with `:offset` and `:limit`. Always ascending by ID. |
| `index-rank` | `{:rank n :present? bool}` for an ID within an `:equals` group. |
| `index-values` | Reducible of `{:key id :value {index stored-value}}` for named indexes, for given IDs or all included instances. |
| `key-page` | Orders a set of candidate IDs (or all included IDs) by an index, optionally with `:sort-value` and `:include?` callbacks, and returns a page of `:keys`. Reads every candidate. |
| `filtered-key-page` | Pages candidate IDs in the order of an index listed in `:filtered-indexes`, without sorting them. Experimental. |
| `joined-page` | Takes membership from one model and order from another model's filtered index, with a `:missing-value` for absent results. Experimental. |

## Catch-up and consistency

A keyed read folds matching events through the tenant head it captured, then
reads the cache. A concurrent update may already be visible. If catch-up fails,
the read throws. Each batch commits state, results, indexes, search data and
progress together. The next read resumes after the last successful batch.

Concurrent local readers share catch-up for the same materialization. Separate
processes recheck progress before committing. Built-in event stores also let
Grain skip intervals already known to contain no matching events.

### Batch size

Use `:read-model/catch-up` in the context to tune keyed catch-up. Batches grow
for cheap writes and shrink under memory pressure. A map-full batch is split
and retried.

| Key | Default | Meaning |
|---|---|---|
| `:min-events` | 128 | Smallest batch, and the starting size for a model with progress. |
| `:cold-events` | 1024 | Starting size for a model with no progress. |
| `:max-events` | 8192 | Largest batch. |
| `:max-bytes` | 32 MiB | Bytes written per batch before the size stops growing. |
| `:max-input-bytes` | 32 MiB | Estimated event size at which a batch is closed early. |
| `:max-map-fill` | 0.5 | LMDB map fill above which batches drop to `:min-events`. |

`:min-events <= :cold-events <= :max-events` must hold. `:max-bytes`,
`:max-input-bytes` and `:max-map-fill` may be `nil` to disable that limit.
`:read-model/batch-size n` in the context replaces the adaptive policy with a
fixed size.

### Cold builds

Large first reads build indexes in staging and publish the completed model
atomically. Other readers wait. A new builder can take over an expired lease
and resume replay. If index finalization fails, a later read builds in place.

| Context key | Default | Meaning |
|---|---|---|
| `:read-model/deferred-build-threshold` | 1024 | Events buffered before a cold build moves to staging. 0 disables staged builds. |
| `:read-model/deferred-build-entries` | 1,000,000 | Index entries sorted in memory at once while building indexes. |
| `:read-model/deferred-build-lease-ms` | 30,000 | How long a build lease lasts without a heartbeat. |
| `:read-model/deferred-build-max-stall-ms` | 60,000 | How long a build may go without progress before it stops renewing its lease. Doubles with each takeover, up to `:read-model/deferred-build-max-stall-cap-ms` (10 minutes). |

## Changing a model

Bump `:version` when changing event types, routing, reducer logic, schemas,
inclusion, indexes or search extractors. A new version rebuilds from retained
history. Grain detects changes to stored index and search *names*. Changing
the logic under an existing name requires an explicit version bump.

To intentionally skip a historical prefix, declare a fixed `:start-after`
UUIDv7. On a cold read the model starts from its initial state and folds events
strictly after that ID. Later reads resume after the newer of the boundary and
the cached watermark. Adding, removing or changing the boundary selects a
separate cache, even with the same version.

## Storage and operations

Each store needs its own cache directory. To rebuild after a storage layout
change, stop every process using the directory before replacing it. Verify that
the event store still contains the history needed for replay, especially for
[bounded event types](../../docs/event-definitions-and-retention.md).

Use [production metrics](#production-metrics) to monitor reads. In benchmarks,
`:read-model/observer` accepts a function of `{:operation keyword :bytes n}`.
An observer disables staged builds and speculative batch preparation.

### Custom storage backends

Ordinary segmented reads use `kv/read-snapshot` so the manifest and all segments
come from one committed state. A KV store must implement that operation. Its
callback receives a getter taking `{:k bytes}`. Keyed models additionally
require `(kv/transact cache mode f)`. In `:read` mode, the callback can use
`:get` and `:reduce` over byte keys. In `:write` mode it can also `:put` and
`:delete` atomically. Range scans accept `:prefix`, inclusive `:from`, exclusive
`:to` and `:reverse?`. Use the operations only on the callback thread before
it returns. See the
[KV contracts](../kv-store/kv-store.allium).

For custom event stores, `es/head` falls back to a reverse, limit-one read.
Implement `EventStoreHead` for a metadata-only lookup. Keyed reads record
the head they checked in the projection checkpoint, including when no events matched.

### Errors

Errors are `ex-info` exceptions. These carry a `:type` in their `ex-data`:

| `:type` | Cause |
|---|---|
| `:grain.read-model/invalid` | Unknown or inapplicable declaration options. |
| `:grain.read-model/invalid-scope` | Malformed `scoped-context` scope. |
| `:grain.read-model/invalid-cache` | Index, filtered-index or search names changed without a version change. |
| `:grain.read-model/key-too-large` | An encoded index entry or ID exceeds 511 bytes. |
| `:grain.read-model/short-search` | `search-keys` query too short for its mode. |
| `:grain.read-query/invalid` | Malformed query definition or request. |
| `:grain.read-query/unsupported` | Query definition needs an index or shape the planner does not support. |
| `:grain.read-query/missing` | A required related result is absent. |

`:grain.read-model/invalid-registry`, `invalid-namespace`,
`namespace-collision`, `namespace-exhausted` and `invalid-levels` mean the
cache's internal metadata is inconsistent. Rebuild from retained history. Other failures,
such as a schema validation failure
(`ex-data` has `:model`, `:kind` and `:explanation`) or an exception from a
reducer, are thrown as they occur and roll back the current batch.

## Ordinary models

Existing declarations without `:key` or `:route` fold into a single state
starting from `{}`. Their read API is `project`. The optional scope accepts
`:tags` to filter declared event types, `:queries` to replace the event query,
or `:partition-key` to return a single partition. Each scope has its own cache.

| Option | Behaviour on an ordinary model |
| --- | --- |
| `:schema` | Registration checks Malli syntax. The processor does not validate each new state. |
| `:partition-fn`, `:entity-id-fn` | Map entity state to a partition and events to entity IDs. The first read builds all partitions. A read without a partition key merges them. |
| `:l1-ttl-ms` | How long an unpartitioned or single-partition read can serve in-process state before checking for new events. Full partition reads bypass L1. |
| `:start-after` | Exclusive UUIDv7 replay boundary, included in cache identity alongside the model version. |

## Production metrics

Grain emits μ/log metrics on ordinary, partitioned and keyed reads. Use these
to distinguish time spent serving a read from time spent catching up its model.

| Metric | What it measures |
| --- | --- |
| `ReadModelReadDuration`, `ReadModelReadFailed` | Time and failures per logical read. A query or session counts once. Reducibles are timed when consumed, including consumer work. Cancellation and caller callback errors do not increment the failure counter. |
| `ReadModelCatchUpDuration`, `ReadModelCatchUpFailed` | Time and failures for the local owner of a keyed catch-up. Waiting readers add no catch-up attempts. |
| `ReadModelWaitDuration` | Time waiting for a catch-up or staged-build owner. |
| `ReadModelCommittedEvents`, `ReadModelCommittedBatches` | Source events and physical keyed batches committed. Fan-out counts an event once. Checkpoint-only writes add no batches. |
| `ReadModelEncodedBytesWritten` | Encoded bytes put by committed keyed batches, including rewrites. Measures write volume, including overwrites. |
| `ReadModelBatchBackoff` | Map-full errors that caused a batch split. |
| `ReadModelColdBuildDuration`, `ReadModelColdBuildFailed` | Time and failures per owned staged build, including replay and index finalization. Superseded and cancelled builds are excluded from failures. |

Ordinary cache timings distinguish `ReadModelL1Hit`, `ReadModelL1Revalidated`,
`ReadModelL1Stale`, `ReadModelL1Miss`, `ReadModelL2Hit` and `ReadModelL2Miss`.
Use the [CloudWatch publisher](../mulog-aws-cloudwatch-emf-publisher/README.md)
to configure metric dimensions.

## Tests and benchmarks

`interface.testing` exposes cache-key and L1 helpers for ordinary-model
integration tests. Run the component suite from the repository root with
`clojure -M:poly test :all-bricks :dev project:development brick:read-model-processor-v2`.
For the live lifecycle and segmented-cache scenarios, run
`clojure -M:dev scripts/live-test.clj read-models`. The ranked-membership benchmark is the `-main` of
`interface_test.clj`; run it from `projects/grain-core-v2`:

```sh
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Sdeps '{:paths ["resources" "../../components/read-model-processor-v2/test"] :deps {bench/sqlite {:local/root "../../components/event-store-sqlite-v3"}}}' \
  -M -m ai.obney.grain.read-model-processor-v2.interface-test 10000 /tmp/ranked-membership.edn
```
