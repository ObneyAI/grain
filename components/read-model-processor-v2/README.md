# read-model-processor-v2

A read model is a pure reducer over a tenant's events. This component folds
events through registered reducers, stores the results in an LMDB cache and
serves reads from it. Every read first catches the model up to the event
store's current head, so reads never return stale data. The one exception is
an ordinary model that opts into `:l1-ttl-ms`.

There are two kinds of read model:

- An ordinary model folds all of its events into one state value. Read it with
  `project`. See [Ordinary models](#ordinary-models).
- A keyed model folds each event into the state of one instance (a customer, an
  account, an order). Instances are stored and indexed separately, so you can
  read one instance, page through them in index order, search them, and join
  several keyed models in one query. Read it with `value`, `state`, `values`,
  `page` and `query`.

Use a keyed model for anything a user lists, sorts, searches or opens by ID.
An ordinary model returns its whole state on every read, which suits small
aggregates and coordination state.

All functions live in `ai.obney.grain.read-model-processor-v2.interface`,
required as `rmp` below. The behavioural contract is in
[read-model-processor-v2.allium](read-model-processor-v2.allium).

## Context

Every read takes a context map with three keys:

```clojure
(require '[ai.obney.grain.read-model-processor-v2.interface :as rmp :refer [defreadmodel]]
         '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.kv-store.interface :as kv]
         '[ai.obney.grain.kv-store-lmdb.interface :as lmdb])

(def context
  {:event-store (es/start {:conn {:type :in-memory}})
   :cache (kv/start (lmdb/->KV-Store-LMDB {:storage-dir "/var/lib/app/read-models"
                                           :db-name "read-models"
                                           :map-size (* 8 1024 1024 1024)}))
   :tenant-id tenant-id})
```

The tenant always comes from the context; model declarations never name a
tenant. A cache belongs to one event store. The LMDB default map size is 10 MB,
which keyed models outgrow quickly; size `:map-size` for the data you expect.
LMDB needs the JVM flags `--add-opens=java.base/java.nio=ALL-UNNAMED` and
`--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`.

Read models fold ordinary Grain events. The event store only accepts event
types that have a registered schema; `es/append` returns an anomaly for any
other type. The keys of an event's `:body` appear directly on the event map a
reducer receives, next to `:event/type`, `:event/id`, `:event/timestamp` and
`:event/tags`:

```clojure
(require '[ai.obney.grain.schema-util.interface :refer [defschemas]])

(defschemas crm-events
  {:crm/customer-registered [:map [:customer-id :uuid] [:first-name :string]
                             [:last-name :string] [:email :string]]})

(es/append (:event-store context)
           {:tenant-id tenant-id
            :events [(es/->event {:type :crm/customer-registered
                                  :tags #{[:customer customer-id]}
                                  :body {:customer-id customer-id :first-name "Ada"
                                         :last-name "Lovelace" :email "ada@example.com"}})]})
```

See [Events](../../docs/core-concepts.md#events) for event definitions.

## Keyed models

```clojure
(defreadmodel :crm customers
  {:events #{:crm/customer-registered :crm/customer-renamed :crm/customer-closed}
   :version 1
   :key :customer-id
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
;;     :total 2 :total-pages 1 :page 1 :page-size 25 ...}
```

`:key` maps each event to the ID of the instance it belongs to; a `nil` ID is
an error. The reducer receives that instance's current state (starting from
`:initial`, default `{}`) and the event. `defreadmodel` registers the model as
`:crm/customers` and also defines the reducer as the var `crm-customers`. The
docstring is optional.
`register-read-model!` registers a model without the macro, and
`global-read-model-registry` returns every registered declaration.

Registering a name again replaces its declaration. For a `defreadmodel` with
a docstring, the replacement must come from the same var: re-evaluating the
form at the REPL, or reloading its namespace, always succeeds, whatever
changed. A declaration of the same name from another namespace throws
`Conflicting read model definition` while the original var still exists; once
its namespace is removed or the var unmapped, as when a file is renamed, the
new declaration takes the name.

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

Supply exactly one of `:key` or `:route`. Unknown options, and options that only
apply to ordinary models (`:schema`, `:partition-fn`, `:entity-id-fn`,
`:l1-ttl-ms`), fail at registration with `:grain.read-model/invalid`. The
`ex-data` lists `:unknown-options`, `:inapplicable-options` and
`:supported-options`.

Use `:route` when one event affects several instances, or none:

```clojure
(defreadmodel :bank balances
  {:events #{:bank/transfer-made}
   :route (fn [{:keys [from to amount-cents]}]
            [[from (- amount-cents)] [to amount-cents]])
   :initial 0
   :result (fn [cents] {:cents cents})
   :indexes {:cents :cents}}
  [cents delta]
  (+ cents delta))
```

With `:route` the reducer receives the routed input, not the event. Inputs are
applied in the order returned. An empty sequence skips the event; `nil`, a
malformed pair or a `nil` ID is an error. All inputs from one event commit
together.

Routing, reducers, results, inclusion and index and search extractors must be
pure. They receive only their arguments: no context, other models, clock or
I/O. Grain may call them more than once for the same events (see
[Catch-up](#catch-up-and-consistency)).

Instance IDs and index values may be `nil` (index values only), booleans,
strings, integers in the signed 64-bit range, UUIDs, keywords, and vectors of
these. An encoded index entry must fit in 511 bytes, which holds an indexed
string of roughly 450 ASCII or 220 CJK characters. A larger entry fails the
batch with `:grain.read-model/key-too-large`; values are never truncated.

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
;; => {:entries [{:key ... :value {...}} {:key ... :value {...}}]
;;     :total 3 :total-pages 2 :page 1 :page-size 2
;;     :rows-examined 2 :results-decoded 2 :rank-entries-examined 3
;;     :progress {:watermark ... :checked-through ...}}

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
(rmp/search-keys context :crm/customers {:index :text :query "smith"})
;; => [#uuid "..."]
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

(rmp/query context customer-list {:order :name :search "smith" :page 1 :limit 25})
;; => {:rows [{:key customer-id :customer {...} :ledger {:balance-cents 500}}]
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

In the `customer-list` example, `:name` is served from the root's `:name`
index. `:balance` orders by a related model. `:billing/ledger` only has
customers who were charged, so its IDs differ from the root's; the order works
because the relationship has `:missing [:default {:balance-cents 0}]` and
`:balance` is in the ledger's `:filtered-indexes`, which lets Grain join the two
(`:work` reports `:joined`). Uncharged customers sort as balance 0. If every
customer had a ledger, the IDs would match and `:work` would report `:indexed`.

Unsupported definitions fail with `:grain.read-query/unsupported`. The
`ex-data` includes `:path`, a `:hint`, and, where an index is missing,
`:suggested-extractor`, for example `(rmp/order-key [:name])`. Malformed
definitions and requests fail with `:grain.read-query/invalid`.

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

A read captures the tenant's event store head, folds every event after the
model's progress up to that head, and then serves. A read never returns data
older than the head it captured, and a failed catch-up throws instead of
serving older data. Newer data written by a concurrent reader may be visible.

Catch-up folds events in batches. Each batch commits every affected state,
result, index entry, count, search posting and the model's progress in one
LMDB write transaction. A failed batch commits nothing; earlier batches stay
committed, and the next read resumes from them. Grain prepares a batch in a
read transaction and then writes it. The batch is prepared again inside the
write transaction if another writer advanced the model in between or if the
first preparation threw, and again for each half when a batch is split after
the LMDB map fills. That is why reducers and extractors can run more than once
for the same event.

Concurrent reads of the same model in one process share a single catch-up.
Readers in other processes are serialized by LMDB's single writer and skip
events that were already applied, so no event is applied twice and progress
never moves backwards.

When the event store guarantees that empty intervals stay empty (the built-in
stores do), Grain remembers recent intervals in which a model had no matching
events and skips the event query while the head stays within them.

### Batch size

Batch size adapts to the work. A model with no progress starts at 1,024 events
per batch; others start at 128. The size doubles up to 8,192 while a batch
writes at most 32 MiB, and halves when a batch writes more than four times that.
It drops to the minimum when the LMDB map is more than half full or nearly out
of free space, and a batch that fails with a full map is split in half and
retried.

To change the policy, put a map under `:read-model/catch-up` in the context
with any of these keys:

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

When a model with no progress has 1,024 or more matching events, Grain builds
it in a staging area instead of in place. It folds all events into staging
without maintaining indexes or searches, then builds them from the sorted
results, and finally swaps staging into place in one transaction. Readers wait
for the build and then read the finished model. Only one process builds a
given model; it holds a lease that it renews while it makes progress, and
another process takes over if the lease lapses. If folding fails, the next
read resumes the staged build. If building the indexes fails, the staging data
is removed and later reads build the model in place.

| Context key | Default | Meaning |
|---|---|---|
| `:read-model/deferred-build-threshold` | 1024 | Events buffered before a cold build moves to staging. 0 disables staged builds. |
| `:read-model/deferred-build-entries` | 1,000,000 | Index entries sorted in memory at once while building indexes. |
| `:read-model/deferred-build-lease-ms` | 30,000 | How long a build lease lasts without a heartbeat. |
| `:read-model/deferred-build-max-stall-ms` | 60,000 | How long a build may go without progress before it stops renewing its lease. Doubles with each takeover, up to `:read-model/deferred-build-max-stall-cap-ms` (10 minutes). |

## Changing a model

Storage is addressed by tenant, model name, version and event scope. Change
`:version` whenever you change what a model stores: events, routing, reducer,
`:initial`, `:result`, `:include?`, schemas, index or search extractors, or
`:filtered-indexes`. The new version builds from the event history; the old
version's data is left unused.

Grain detects part of this. If the set of index names, `:filtered-indexes` or
search names differs from the one the stored data was built with, reads fail
with `:grain.read-model/invalid-cache` and nothing is written. Changes under the
same names, such as a new extractor function or a different `:include?`, cannot
be detected and keep serving the old data until the version changes.

## Storage and operations

The cache holds only derived data. Delete it whenever no process has it open;
reads rebuild it from the event store. There is no stored format version. When
a release changes the storage layout, delete the cache directory as part of
the rollout.

Use one cache per event store. Cache entries are keyed by tenant, model,
version and scope, not by event store, so two event stores with the same
tenant IDs reading through one cache directory would see each other's state.

Index pages, counts and ranks are maintained in the fold transaction, so read
cost does not depend on the number of skipped pages. Descending pages are read
from the ascending index. Writing an instance touches only the entries of
indexes whose value changed.

Pass `:read-model/observer` in the context to receive
`{:operation keyword :bytes n}` for each storage read, write and fold step,
for measurement in tests and benchmarks. It disables staged cold builds and
batch preparation outside the write transaction, so it is not meant for
production.

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
cache's internal metadata is inconsistent; delete the cache. Other failures,
such as an invalid keyed declaration, a schema validation failure
(`ex-data` has `:model`, `:kind` and `:explanation`) or an exception from a
reducer, are thrown as they occur and roll back the current batch.

## Ordinary models

An ordinary model folds all of its events into one state, starting from `{}`:

```clojure
(defreadmodel :example counters
  {:events #{:example/counter-created :example/counter-incremented}
   :version 1}
  [state event]
  (case (:event/type event)
    :example/counter-created
    (assoc state (:counter-id event) {:name (:name event) :value 0})
    :example/counter-incremented
    (update-in state [(:counter-id event) :value] inc)))

(rmp/project context :example/counters)
;; => {#uuid "..." {:name "clicks" :value 1}}
```

Options are `:events`, `:version`, `:schema` (a Malli schema checked for valid
syntax at registration; states are not validated against it),
`:partition-fn`, `:entity-id-fn` and `:l1-ttl-ms`. `project` throws for keyed
models, and the keyed read functions throw for ordinary ones.

`project` takes an optional scope:

```clojure
(rmp/project context :example/counters {:tags #{[:counter counter-id]}})
(rmp/project context :example/counters {:queries [{:types #{:example/counter-created}
                                                   :tags #{[:counter counter-id]}}]})
(rmp/project context :inventory/items {:partition-key location-id})
```

`:tags` reads the model's event types restricted to those tags. `:queries`
replaces the event query entirely; the tenant is added to each query map. Each
scope is cached separately. `p` is the same projection without the registry:
pass `:f`, `:query`, `:name` and `:version` directly.

### Caching

State is kept in two tiers. L1 is an in-process map of decoded states, kept
separately for each event store and cache and evicted least-recently-used
beyond 10,000 projections. L2 is the LMDB cache: one entry for
states up to 10,000 keys, 64 hash segments above that. For unpartitioned
models, L2 is rewritten when a read folds at least 10 new events; below that,
L1 holds the newer state and a restarted process folds the remainder again.
The first write-back after a process starts rewrites only the segments that
changed; later write-backs rewrite the whole state. Partitioned models write
every change.

By default every `project` call reads the events after the cached watermark, so
it is as current as a keyed read. `:l1-ttl-ms` lets L1 serve a state without
checking the event store for that many milliseconds after it was last checked.
It applies to unpartitioned models and single-partition reads; reading all
partitions of a partitioned model does not use L1. It is the only read path
that can return stale data; keyed models have no equivalent. `l1-clear!`
empties L1 and `l1-stats` returns `{:entries n}`.

Unpartitioned reads emit mulog traces named `ReadModelL1Hit`,
`ReadModelL1Revalidated`, `ReadModelL1Stale`, `ReadModelL1Miss`,
`ReadModelL2Hit` and `ReadModelL2Miss`, with `:read-model/cache-tier`.
Single-partition reads emit the four L1 traces, which also carry
`:read-model/name`. Full partition projections emit `ReadModelL2Hit` or
`ReadModelL2Miss`, according to whether a partition manifest was present. All
ordinary-model paths retain model name/version context.

### Partitioned models

For large ordinary models that split naturally, such as appointments by
location and day, supply both `:partition-fn` and `:entity-id-fn`:

```clojure
(defreadmodel :scheduling appointments
  {:events #{:scheduling/appointment-booked :scheduling/appointment-moved}
   :version 1
   :partition-fn (fn [appointment] [(:location-id appointment) (subs (:start-at appointment) 0 10)])
   :entity-id-fn :appointment-id}
  [state event]
  ...)

(rmp/project context :scheduling/appointments {:partition-key [location-id "2025-06-15"]})
```

`:entity-id-fn` maps an event to the key of the entity it changes in the state
map. `:partition-fn` maps that entity's state to its partition. Each partition
is stored separately with a manifest of partition keys. When an event moves an
entity to another partition, it is removed from the old one and added to the
new one. Without `:partition-key`, `project` returns all partitions merged. A
single-partition read folds only the events relevant to that partition; to
decide whether an unseen entity belongs to it, Grain calls the reducer on an
empty state and treats an exception as "not relevant". The first
single-partition read on an empty cache projects every partition once.

## Tests and benchmarks

`interface.testing` exposes cache-key and L1 helpers for ordinary-model
integration tests. CI runs this component's tests with
`clojure -M:poly test :all-bricks :dev project:development` from the
repository root. The ranked-membership benchmark is the `-main` of
`interface_test.clj`; run it from `projects/grain-core-v2`:

```sh
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Sdeps '{:paths ["resources" "../../components/read-model-processor-v2/test"] :deps {bench/sqlite {:local/root "../../components/event-store-sqlite-v3"}}}' \
  -M -m ai.obney.grain.read-model-processor-v2.interface-test 10000 /tmp/ranked-membership.edn
```

## Production metrics

Read-model operations emit μ/log metrics automatically, independently of
`:read-model/observer`. Enabling metrics does not disable speculative preparation,
optimized index builders, or staged cold builds. The observer remains a diagnostic
hook with its existing execution-path effects.

| Metric | Meaning |
| --- | --- |
| `ReadModelReadDuration` | One completed public logical read, including failures. Reducible reads are timed during consumption, including consumer work; creating an unconsumed reducible emits no duration. Each subsequent reduction is another attempt. |
| `ReadModelReadFailed` | Read definition, validation, materialization, or storage failure. Caller callback errors and cancellation do not increment this counter. |
| `ReadModelCatchUpDuration`, `ReadModelCatchUpFailed` | One locally owned keyed catch-up, including internal restarts. Waiting readers do not duplicate catch-up attempts. |
| `ReadModelCommittedEvents` | Source events accepted by successfully committed physical keyed batches. Routing to multiple owners counts the source event once. Speculative reducer calls and rolled-back writes do not count. |
| `ReadModelCommittedBatches` | Successful physical keyed batch transactions, including each successful leaf after map-full splitting. Checkpoint-only transactions do not count. |
| `ReadModelEncodedBytesWritten` | Encoded key/value bytes passed to put operations by committed keyed batches, including repeated writes. This is neither disk growth nor total cold-build/index finalization I/O. |
| `ReadModelWaitDuration` | Time waiting for another local catch-up owner or a staged-build owner. |
| `ReadModelBatchBackoff` | Map-full failures that actually trigger a batch split. |
| `ReadModelColdBuildDuration`, `ReadModelColdBuildFailed` | One owned staged cold-build attempt, including replay, index finalization, and publication. Resumed ownership starts a new attempt. Small builds performed in place are covered by catch-up metrics. Supersession/restart and cancellation are not failures. |

Durations use milliseconds at high resolution. Counters use standard resolution
and Count, except encoded bytes, which uses Bytes. Read outcomes are `success`,
`failure`, `consumer-error`, or `cancelled`; a staged build that loses ownership or
must restart uses `superseded`. Errors retain their original throwable identity.
A query or read-session owns one read duration; its synchronous internal reads
and reads made in the session callback do not duplicate that denominator. An
independent read on another thread owns its own attempt. An internal read failure
propagated through a callback remains a processor failure.

Bounded optional dimension names are `:read-model-operation`, `:read-model-kind`
(`ordinary`, `partitioned`, `keyed`), `:read-model-scope` (`tenant`, `scoped`,
`partition`), `:read-model-outcome`, `:read-model-wait-kind` (`catch-up`,
`cold-build`), and `:read-model-target` (`live`, `staging`). Scope labels describe
only the mode. Tenant IDs, keys, selectors, versions, and search text are never
metric dimensions. `:read-model-name` is an optional allowlist of stringified
qualified keywords. Unknown top-level model names are omitted while preserving
the aggregate metric. Explicit `:metric/dimensions` remain strict.

The CloudWatch EMF publisher recognizes the semantics of these eleven metrics
with unchanged existing factory configuration, including custom registries that
do not yet contain them. For dimensions, use `grain-metric-registry` with bounded
values (the example application demonstrates all vocabularies). Without a
configured definition, legacy app/env dimensions continue to work; configured
definitions remain authoritative. Existing ordinary cache-tier timers now include
event reading/folding, and full partition projections emit L2 hit/miss timings.
