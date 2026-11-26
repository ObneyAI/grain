# V3 event stores

Start a store with `es/start` and release it with `es/stop`. The core package
includes the in-memory backend, which loses its data when stopped.

```clojure
(require '[ai.obney.grain.event-store-v3.interface :as es])

(def store (es/start {:conn {:type :in-memory}}))
(es/stop store)
```

## Postgres

Add the [Postgres package](packages.md#grain-event-store-postgres-v3) and load
its interface to register the backend. Point it at an existing database.

```clojure
(require 'ai.obney.grain.event-store-postgres-v3.interface)

(def store
  (es/start {:conn {:type :postgres
                    :server-name "localhost"
                    :port-number "5432"
                    :database-name "grain"
                    :username (System/getenv "PG_USER")
                    :password (System/getenv "PG_PASSWORD")}}))
```

## SQLite

Add the [SQLite package](packages.md#grain-event-store-sqlite-v3) and require
`ai.obney.grain.event-store-sqlite-v3.interface` to register the backend.
SQLite runs in WAL mode and queues writes from a single process. Reads use the
connection pool. Set these options in the event store’s `:conn` map.

```clojure
{:type :sqlite
 :database-file "grain.sqlite"
 :maximum-pool-size 4
 :write-queue-capacity 1024
 :write-shutdown-timeout-ms 5000}
```

## Write contention

The in-memory, Postgres and SQLite stores retry appends and compaction after
recoverable lock contention. Set these options in the event store’s `:conn` map.

| Option | Default | Meaning |
| --- | ---: | --- |
| `:write-lock-timeout-ms` | 5000 | Lock wait per attempt, in milliseconds. Must be positive. |
| `:write-max-retries` | 3 | Additional attempts after the first. Set to 0 for a single attempt. |
| `:write-retry-backoff-ms` | 100 | Initial backoff in milliseconds. Doubles on each retry. |
| `:write-retry-max-backoff-ms` | 400 | Cap on the base backoff. Jitter adds up to one more base interval. |

After exhaustion the store returns a `:cognitect.anomalies/busy` anomaly with
`:event-store/attempts`. A CAS predicate may be evaluated again during a storage
retry, so keep it pure. A false predicate returns a conflict immediately.
Interruptions, connection failures and ambiguous commit outcomes bypass retries.

SQLite also accepts the older `:busy-timeout-ms`, `:busy-max-retries`,
`:busy-retry-backoff-ms` and `:busy-retry-max-backoff-ms` names. The corresponding
`:write-*` option wins when both are set. Legacy `:busy-timeout-ms 0` requests
an immediate lock attempt. A full SQLite write queue returns busy before a lock attempt.
