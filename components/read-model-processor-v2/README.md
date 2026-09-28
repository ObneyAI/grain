# Queries over independent keyed read models

`query` composes existing keyed results without defining another projection.
The application supplies meaning; Grain selects and loads the page.

```clojure
;; In ordinary keyed model declarations:
:indexes {:name (rmp/order-key [:last-name :first-name])}
:searches {:text (rmp/search-text [:first-name :last-name :email])}

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

`order-key` and `search-text` remain pure functions. Their metadata lets Grain
match declared fields to indexes without interpreting arbitrary functions.
Text ordering lowercases using `Locale/ROOT`, nil becomes empty text, and keyword
ordering uses `name`. Other values retain their ordering. Root-ID ties always
use ascending string order, even for descending pages. Changing an extractor
requires the usual model version change. Opaque extractors still work through
the existing low-level APIs.

## Execution and current limits

Grain uses the existing indexed pages, filtered ranks, related group merges and
joined pages. Grouped ordering reports its group count as `:related-groups` in
`:work` and emits the same operation name to the read observer.
Direct use of a related ordering requires exact equal membership;
per-model bitmaps over shared tenant identity ordinals prove this without
reading every result. Membership changes commit with state, indexes and progress.
The derived key format is now KF5: older derived data is ignored and normal replay
builds the current format. Storage is addressed by tenant, model and version
within the configured cache, which belongs to its event store. There is no
application-supplied history identifier or backfill command.

Indexed search supports a root text segment, optionally followed by one related
text segment. The latter must be reached through an exact keyword equality index
on the root or a same-key related model. For combined search, the root extractor
uses `{:suffix " "}`; the related extractor has no suffix. This preserves matches
across the boundary. Other search shapes, including non-nil defaults for related
search text, fail explicitly. Nonempty searches shorter than three Unicode code
points return a validation message without reading the population.

An arbitrary computed order still evaluates its candidates and is reported as
`:computed` in `:work`; it is not made indexable by declaring dependencies.
Eager argument resolution can read values the business function ultimately does
not need, increasing allocation for computed sorts.
A typed oversized-storage-key failure uses a separate unindexed replay and
reports `:oversized-key-scan`; unrelated errors are propagated.

Every selected row contains all declared bindings. Results are detached from the
read session. Snapshot/freshness semantics are those of the existing keyed
read-session API, not a stronger cross-fold event-log transaction.

Contract: [keyed folds and composed read queries](read-model-processor-v2.allium).
