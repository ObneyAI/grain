# Exact tenant transfer

`ai.obney.grain.event-store-v3.interface.exact-transfer` offers optional
`(snapshot memory-store tenant-id)` and `(restore! sqlite-store snapshot)`.
Other event stores need not implement either protocol. The version 1 snapshot
holds one tenant UUID, ordered raw events and the matching high watermark. The
memory backend captures those from one state value; all namespaces are retained.
This is a point-in-time copy: writes after capture stay on the source. A live
cutover requires separate writer fencing.

The snapshot validator accepts complete, uncompacted v3 transaction history,
including empty transactions, validates persisted envelopes, transaction marker
membership and watermark, and refuses incomplete/compacted history. Event type
registration is not required for an already persisted event: unknown types are
preserved as opaque facts, never dropped or executed. Snapshot values support
persistent maps, vectors and sets, scalar Clojure values, UUID, Instant,
OffsetDateTime and byte arrays when the existing Fressian codec round-trips
them without loss. Records, sorted collections and values with Clojure metadata are refused because copying would lose their type, comparator or metadata; unsupported values fail explicitly. Byte arrays are copied,
but callers must not mutate them concurrently during capture; Grain's memory
backend otherwise retains object aliases and cannot atomically freeze arbitrary
mutable objects. Physical database encoding is not promised byte-identical.

The caller owns a new SQLite store and its file. Restore validates and inserts
all original records, tag indexes and watermark in one immediate transaction,
only if the entire target store is empty. Existing tenants or data refuse,
including a repeat of the same snapshot. SQLite also refuses event types and tags whose keyword encoding would be ambiguous. No marker, pubsub notification,
application bootstrap or external effect is emitted. On an ambiguous response,
inspect the owned store and compare the whole history and watermark before
considering it complete; never silently overwrite or delete it. Subsequent normal
appends retain normal CAS and notification behavior and use the restored
watermark. This API does not provide PostgreSQL restore, arbitrary corrupt or
compacted history recovery, crash-safe initialization, external effect recovery
or a no-loss live migration.
