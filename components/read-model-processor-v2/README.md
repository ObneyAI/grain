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
result schemas validate processing. Ordinary `:schema` validates schema syntax only.

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
The derived key format is now KF9: older derived data is ignored and normal replay
builds the current format. Storage is addressed by tenant, model and version
within the configured cache, which belongs to its event store. There is no
application-supplied history identifier or backfill command.

KF9 uses durable short namespace addresses and compact strings in non-filtered
implicit identity ordering. Explicit indexes, filtered identity indexes and
typed identity suffixes retain their existing encoding. This preserves accepted
key sizes and ordering for Unicode strings and mixed identity types; it does not
require an ASCII-only schema. Sort metadata values use the compact representation
without changing the ordering exposed by queries.

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
Existing empty or space-suffixed extractors remain supported without rebuilding.
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
