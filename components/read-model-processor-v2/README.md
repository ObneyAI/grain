# Queries over independent keyed read models

`query` composes existing keyed results without defining another projection.
The application supplies meaning; Grain selects and loads the page.

```clojure
;; In the customer model declaration:
:indexes {:name (rmp/order-key [:last-name :first-name])}
:searches {:text (rmp/search-text [:first-name :last-name :email])}

;; In the ledger model declaration:
:indexes {:balance (rmp/order-key [:balance-cents])}
:filtered-indexes #{:balance}

(def customers
  {:from {:customer :crm/customer-directory}
   :related {:ledger {:model :billing/customer-ledger
                      :key [:id :customer]
                      :missing [:default {:balance-cents 0}]}}
   :search [:customer/first-name :customer/last-name :customer/email]
   :orders {:name [:customer/last-name :customer/first-name]
            :balance [:ledger/balance-cents]}})

(rmp/query context customers
           {:order :name :direction :asc :page 1 :limit 25 :search "smith"})
;; => {:rows [{:key customer-id :customer {...} :ledger {...}} ...]
;;     :total 42 :page 1 :total-pages 2 :page-size 25
;;     :work {:strategy :indexed-matches ...}}

;; Reuse the endpoint's existing subscriptions:
:grain/read-models (rmp/query-models customers)
```

| Key | Meaning |
|---|---|
| `:from` | Exactly one alias and root keyed model. Root inclusion defines rows. |
| `:related` | To-one model lookups using another binding's key or result field. |
| `:search` | Fields joined with spaces, searched as a case-insensitive literal substring. |
| `:orders` | Allowed order names and their ordered field references or pure calculations. |

A field reference such as `:customer/company-id` reads that result field.
`[:id :customer]` means its instance identity; `[:value :ledger]` supplies its whole
compact result. `[:param :exchange-rate]` reads an explicit request parameter.
`[:call f [:value :ledger] [:param :exchange-rate]]` invokes an
ordinary pure function. Arguments are resolved eagerly. No callback receives a
storage handle or prepared context. There is no `:fields` section or tenant key;
the event store, cache and tenant come from the trusted context.

Relationships are required by default. `:missing [:default value]` handles an
absent result, including optional nil; a stored nil stays nil. An optional pure
`:validate` function receives the lookup key and resolved value and may throw a
domain error. Its return value is ignored. Aliases cannot use the reserved `:key`.
Reusing a result fetched during page selection still runs its relationship validator.

`order-key` and `search-text` remain pure functions. Their metadata lets Grain
match declared fields to indexes without interpreting arbitrary functions.
Text ordering lowercases using `Locale/ROOT`, nil becomes empty text, and keyword
ordering uses `name`. Other values retain their ordering. Root-ID ties always
use ascending string order, even for descending pages. Changing an extractor
requires the usual model version change. Opaque extractors still work through
the existing low-level APIs.

Any change that alters what a keyed model stores needs a `:version` bump.
Grain detects part of this contract: if the index names, `:filtered-indexes` or
search names differ from the ones a cache was built with, reads and writes fail
with `:grain.read-model/invalid-cache` and nothing is written. Changes under the
same names (an index extractor, a search's fields, `:include?`, `:result`,
`:initial` or the handled events) cannot be detected and silently keep the old
derived data until the version changes.

## Choosing the application API

| Need | API |
|---|---|
| One ordinary model's accumulated state | `defreadmodel` without routing, then `project` |
| Individually stored results | `defreadmodel` with `:key` or `:route`, then `value`, `state`, `values`, or `page` |
| Search and page across related keyed models | A four-key declaration, then `query` |

Candidate-ID pages, index metadata functions, and `read-session` are advanced
composition tools. A declarative query coordinates those operations for the application.
`filtered-key-page` selects matching IDs in stored order using indexes listed in
`:filtered-indexes`. Ordinary indexes already support rank-based page navigation.
The optional filtered capability adds membership storage and update work.

Ordinary and keyed declarations have different option sets. Unknown options,
including keyed-only options on ordinary models, fail during registration.
Keyed `:initial` supplies a state value; its default is `{}`. Keyed state and
result schemas validate processing. Ordinary `:schema` validates schema syntax only;
keyed models use `:state-schema` and reject `:schema`.

## Materializing a requested event scope

Keyed reads accept an explicit event scope through `scoped-context`. Scope limits
event replay before folding; index filters select results afterward. Application
context keys such as a student ID do not implicitly scope Grain's event reads.

```clojure
(let [scoped (rmp/scoped-context context {:tags #{[:User user-id]}})]
  (into [] (rmp/values scoped :enrollment/applications
                      {:index :user :equals user-id})))

;; Reuse existing tag/type query scopes, including related record tags:
(rmp/scoped-context context
                    {:queries [{:tags #{[:User user-id]}}
                               {:tags #{[:Application application-id]}}]})
```

Use the returned context with `value`, `state`, `values`, `page`, `query`, and
`read-session`. Each event scope has independent state, results, indexes, exact
counts, and durable progress. Reading one scope does not catch up another scope
or the unscoped model. Cold reads build only the requested scope; later reads
resume its progress. Overlapping scopes are independent materializations, so
their histories cannot overwrite one another. Equal maps and tag sets reuse the
same durable scope identity, including after restart.

Lists and counts include every matching instance within the requested scope,
even when none was cached before the request. Events outside that scope do not
run its reducer. Without an event scope, reads request the whole model for that
tenant. A point key or an index equality/prefix filter alone still requests
unscoped replay: Grain cannot infer event tags from an arbitrary routing or index
function.

Scope accepts `{:tags #{[tag UUID] ...}}` or `{:queries query-or-vector}`. Query
maps accept only `:tags` and `:types`; query types are intersected with each
model's declared events, and union reads deduplicate matching events. Empty tag
sets, empty type sets, and an empty query vector select nothing. Tenant, replay
cursors, limits, and direction remain controlled by Grain. An active read
session's event scope cannot change. Pass nil to `scoped-context` to remove a
scope before opening a session. This context helper applies to keyed APIs;
ordinary `project` continues to take its scope argument directly.

Within one query, an event must bear all supplied tags. Use separate query maps
to union the User stream with related Application, Enrollment, and financial
record streams, as in the existing `project` query-scope API.

Scoped namespaces use separate registry and data prefixes alongside existing
unscoped KF9 data, so introducing scopes does not invalidate an existing cache.

Declaration errors identify the model and offending options, order, or search
fields. Messages include a correction. Structured `ex-data` supplies `:hint`,
query `:path`, and, when an index is missing, `:suggested-extractor`.
For example, an unsupported `:name` order over `:customer/name` suggests
`(rmp/order-key [:name])` on the customer model. Option errors list
`:supported-options` and distinguish unknown options from inapplicable ones.

## Execution and current limits

Grain uses the existing indexed pages, filtered ranks, related group merges and
joined pages. Grouped ordering reports its group count as `:related-groups` in
`:work` and emits the same operation name to the read observer.
Direct use of a related ordering requires exact equal membership;
per-model bitmaps over shared tenant identity ordinals prove this without
reading every result. Membership changes commit with state, indexes and progress.
The derived data uses the KF9 key prefixes. Storage is addressed by tenant, model
and version within the configured cache, which belongs to its event store. There
is no application-supplied history identifier or backfill command.

**Caches must be wiped when this layout ships.** The index layout changed
underneath the same KF9 prefixes and there is no format guard. A cache written by
an earlier build is not detected; it may be misread or fail with
`:grain.read-model/invalid-cache`. Delete the cache directory on rollout and let
normal replay rebuild it.

KF9 uses durable short namespace addresses. Every index key, identity suffix and
sort metadata value uses one compact order-preserving encoding:
- Index names become one-byte ordinals, numbered by sorted name.
- Strings take one byte per ASCII or Latin-1 character and two per other BMP
  character, and are never longer than the former encoding.
- Integers use a variable-length encoding.
- Each member stores its identity once, as text plus a one-byte type tag.

Ordering matches the former encoding for Unicode strings and mixed identity
types, so queries see the same order. No ASCII-only schema is required. The
511-byte key limit holds about 459 ASCII or 229 CJK characters of an indexed
value.

Descending pages are served from the ascending index, and each index grows its
rank levels as it grows. Cold builds sort and append the final results instead of
maintaining indexes per event.

Namespace reservation is atomic and distinct from fold publication. A failed
first fold can leave a complete empty reservation, but cannot publish its states,
results, indexes, counts or progress. Retrying reuses that reservation. Invalid
durable namespace mappings fail explicitly rather than being reassigned. A read
session retains its captured namespace resolution and does not rerun the user's
callback to change an encoding.

For rollout, build and validate the new derived cache from the existing event
history with enough disk and LMDB map capacity for replay. Keep event history and
durable processor checkpoints unchanged. An old cache retained for rollback may
be behind events written during KF9 operation: rebuild the old format from the
current history or verify its normal catch-up before serving reads. Merely
swapping in an older cache directory does not establish freshness.

Indexed search supports a root text segment, optionally followed by one related
text segment. The latter must be reached through an exact keyword equality index
on the root or a same-key related model. Grain handles the joining space; one
extractor serves both root-only and combined search, including boundary matches.
Other search shapes, including non-nil defaults for related search text, fail
during definition validation, including `query-models`. Nonempty searches shorter than three Unicode code
points return a validation message without reading the population.

Plain field orders require matching index support. Unsupported declarations fail
before reads begin. If current membership prevents indexed ordering, the query
reports an error instead of scanning root results. Use an explicit `:call` for
an order that requires calculation across candidates.

An explicit computed order still evaluates its candidates and is reported as
`:computed` in `:work`; it is not made indexable by declaring dependencies.
Eager argument resolution can read values the business function ultimately does
not need, increasing allocation for computed sorts.
A typed oversized-storage-key failure propagates to the caller. It does not
trigger an unindexed replay or a population scan.

Every selected row contains all declared bindings. Results are detached from the
read session. Snapshot/freshness semantics are those of the existing keyed
read-session API, not a stronger cross-fold event-log transaction.

Contract: [keyed folds and composed read queries](read-model-processor-v2.allium).

Implementation lives in `core.clj`; tests and the ranked-membership benchmark
live in `interface_test.clj`. Run the benchmark from `projects/grain-core-v2`:

```sh
clojure -J--add-opens=java.base/java.nio=ALL-UNNAMED \
  -J--add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  -Sdeps '{:paths ["resources" "../../components/read-model-processor-v2/test"] :deps {bench/sqlite {:local/root "../../components/event-store-sqlite-v3"}}}' \
  -M -m ai.obney.grain.read-model-processor-v2.interface-test 10000 /tmp/ranked-membership.edn
```
