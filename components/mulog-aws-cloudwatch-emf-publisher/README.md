# CloudWatch EMF publisher

The publisher writes μ/log metrics as Embedded Metric Format JSON to stdout.
Configure your log collector to forward that output to CloudWatch Logs.

```clojure
(require '[ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface :as emf]
         '[com.brunobonacci.mulog :as u])

(def dimension-values
  {:app-name #{"academy"} :env #{"production"} :service #{"academy"}
   :command #{":academy/enroll"} :processor #{":academy/project"}
   :periodic #{":academy/remind"} :backend #{"postgres"}
   :operation #{"append" "compact" "pool"}
   :outcome #{"succeeded" "rejected" "retrying" "failed" "sampled" "saturated"}
   :anomaly-category #{"forbidden"} :failure-class #{"exception"}})

(def application-metrics
  {"EnrollmentCompleted"
   {:metric/type :counter
    :metric/unit :count
    :metric/resolution :standard
    :metric/dimensions (select-keys dimension-values [:app-name :env :service :outcome])}})

(def publisher-config
  {:namespace "Academy/Grain"
   :metric-registry (emf/extend-grain-metric-registry dimension-values application-metrics)})
```

Start the publisher before logging metrics.

```clojure
(u/set-global-context! {:app-name "academy" :env "production"})
(def stop-metrics (emf/start-cloudwatch-emf-publisher! publisher-config))

(u/log :metric/metric
       :metric/name "EnrollmentCompleted" :metric/value 1
       :metric/dimensions {:service "academy" :outcome "succeeded"})

;; After stopping application producers:
(stop-metrics)
```

The wrapper includes events already buffered by the current μ/log logger.
Existing μ/log `:custom` configurations can continue using the
`cloudwatch-emf-publisher` factory.

## What to monitor

| Area | Metrics |
| --- | --- |
| Commands and queries | Compare `CommandDuration` and `QueryDuration` with success, rejection and handler-exception counts. `CommandEventAppendFailed` counts failed appends, including CAS conflicts. `QueryAuthorizationDenied` counts adapter denials. |
| Todo processors | `TodoBatchSize` and `TodoPollDuration` describe polling work. Check `TodoCheckpointFailed`, `TodoPollError` and `TodoDrainTimedOut` when progress stalls. |
| Periodic tasks | `PeriodicTriggered` counts firings, `PeriodicTenantCount` counts target tenants and `PeriodicLastSuccessAge` measures seconds since the previous successful firing. |
| Event store | Compare append and read durations with event counts. `EventStoreWriteRetry` and `EventStoreWriteContentionExhausted` distinguish recoverable lock contention from failed writes. Event read timing includes reduction. |
| Postgres and SQLite | Postgres reports pool activity, saturation, transaction and notification failures. SQLite reports write queue depth, saturation and wait time. |
| Read models | Read latency, catch-up, waits, committed work, cold builds and cache hits. See [read-model metrics](../read-model-processor-v2/README.md#production-metrics). |

## Dimensions and custom metrics

Without a registry entry, a metric uses the global `:app-name` and `:env` when
present. Read-model metrics have built-in units and resolution, including bytes
for encoded writes. Use `grain-metric-registry` to publish framework metrics
with their declared types (including gauges), units and resolution. Use
`extend-grain-metric-registry` to add your own.

Map each dimension to a bounded set of strings. A configured definition rejects
measurements with undeclared explicit dimensions or values outside their sets.
Include the services, commands, queries, processors and scheduled tasks your
deployment emits. For store retry counters, allow `"append"` and `"compact"`
under `:operation`. Include global dimensions in custom definitions to publish
them. Application metric names must be distinct from Grain's.

For read models, allowlist `:read-model-operation`, `:read-model-kind`,
`:read-model-scope`, `:read-model-outcome`, `:read-model-wait-kind` and
`:read-model-target` as needed. Optional `:read-model-name` values are strings
such as `":crm/customers"`. An unknown model name in the log context is omitted
so the aggregate still publishes. See the [example application's registry setup](../../bases/example-base/src/ai/obney/grain/example_base/core.clj).

## Buffering and shutdown

| Setting | Default | Meaning |
| --- | --- | --- |
| `:namespace` | `"ObneyAI/InfoSystem"` | CloudWatch metric namespace. |
| `:flush-interval` | 200 ms | Time between scheduled flushes. |
| `:buffer-capacity` | 10,000 | Maximum buffered events. |
| `:overflow-policy` | `:drop-newest` | Drop incoming events when full. Use `:drop-oldest` to evict older entries. |
| `:max-delivery-attempts` | 3 | Attempts for a measurement whose sink write throws. |
| `:final-drain-timeout` | 5,000 ms | Deadline for the final flush. |
| `:output-sink` | stdout | Function of one EMF JSON string. Throw to signal a delivery failure. |

Within a batch, counters sum, gauges keep the latest sample, and timers retain
up to 100 samples per array. Documents are split at the 1 MiB encoded limit.
A normal sink return records local acceptance. Check the log pipeline for
CloudWatch delivery.

The stop function returns the same drain result on repeated calls. With a
factory-created instance, call `(emf/final-drain instance)` after stopping
producers. Inspect `:published-count`, `:invalid-count`, `:failed-count`,
`:dropped-count` and `:timed-out?`. On timeout, `:retained-count` is `nil`
because a write may still finish. Cleanup drops remaining work after that
write returns. For current buffer use and cumulative counts, call
`(emf/publisher-health instance)`.
