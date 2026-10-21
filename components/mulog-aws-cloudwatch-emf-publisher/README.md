# Grain CloudWatch EMF publisher

Existing μ/log `:custom` configurations keep working with the
`ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface/cloudwatch-emf-publisher`
factory. The registry and lifecycle wrapper below are optional. Output is EMF
JSON written to stdout, or to `:output-sink`; a normal sink return means local
acceptance, including `nil` or `false`. CloudWatch ingestion belongs to the
logging pipeline.

Applications can add domain metrics without adding them to Grain. This complete
example declares the dimensions emitted by the framework as well as its custom
metric. Replace the application operation names with your registered names;
include every service namespace, command, query, processor and periodic task in
your deployment. Unknown explicit dimensions or values reject a measurement.

```clojure
(require '[ai.obney.grain.mulog-aws-cloudwatch-emf-publisher.interface :as cloudwatch-emf]
         '[com.brunobonacci.mulog :as u])

(def dimension-values
  {:app-name #{"academy"}
   :env #{"production"}
   :service #{"academy"}
   :command #{":academy/enroll"}
   :query #{":academy/enrollment"}
   :processor #{":academy/project"}
   :periodic #{":academy/remind"}
   :backend #{"in-memory" "sqlite" "postgres"}
   :operation #{"start" "append" "read" "transaction" "notification" "pool"}
   :outcome #{"succeeded" "rejected" "anomaly" "failed" "retrying" "skipped"
              "triggered" "timed-out" "sampled" "saturated"}
   :anomaly-category #{"incorrect" "not-found" "forbidden" "conflict" "fault"
                       "busy" "unavailable" "interrupted"}
   :failure-class #{"incorrect" "not-found" "forbidden" "conflict" "fault"
                    "busy" "unavailable" "interrupted" "exception"}})

(def application-metrics
  {"EnrollmentCompleted"
   {:metric/type :counter
    :metric/unit :count
    :metric/resolution :standard
    :metric/dimensions (select-keys dimension-values
                                   [:app-name :env :service :outcome])}})

(def publisher-config
  {:metric-registry
   (cloudwatch-emf/extend-grain-metric-registry dimension-values
                                                application-metrics)})
```

Start the optional lifecycle wrapper and emit a matching event:

```clojure
(u/set-global-context! {:app-name "academy" :env "production"})
(def stop-metrics (cloudwatch-emf/start-cloudwatch-emf-publisher! publisher-config))

(u/log :metric/metric
       :metric/name "EnrollmentCompleted"
       :metric/value 1
       :metric/resolution :low
       :metric/dimensions {:service "academy" :outcome "succeeded"})

;; Stop application producers before stopping telemetry.
(stop-metrics)
```

The registry allowlists dimensions; it does not supply their values. Global
`:app-name` and `:env` values come from μ/log context and are attached only when
that metric's definition declares them. Custom definitions above explicitly
include both; omit those declarations to omit those dimensions. Framework
definitions receive the matching global declarations from `dimension-values`.
Consumer names must be disjoint from Grain's names; the extension helper rejects
collisions. Keep dimension vocabularies bounded and free of request IDs, raw
URLs, messages, payloads, credentials and personal data.

The lifecycle wrapper captures the currently bound μ/log source logger, includes
its already buffered events, and installs a source watch that hands off each
new event to the publisher agent. The watch inspects only the newly appended
suffix and never consumes another publisher's source events. It adds a short
synchronous enqueue to logging calls; output and retries run asynchronously.
Stopping removes the watch and recurring task before draining; repeated stop
calls return the same result.

`final-drain` also works on a factory-created instance after its upstream
producer has stopped. It returns `:published-count`, `:invalid-count`,
`:failed-count`, `:dropped-count`, `:retained-count` and `:timed-out?`. A blocked
sink may finish after the deadline: its measurements remain unresolved rather
than being counted both published and dropped. Queued cleanup drops anything
still undelivered after the in-flight write returns. On timeout,
`:retained-count` is `nil`: queued enqueues, a partially completed batch or an
in-flight sink can prevent an authoritative remaining count. A successful drain
reports zero. Health settles when the sink and
queued cleanup finish. A sink that never returns leaves its agent occupied;
the stop call itself remains bounded by `:final-drain-timeout` (default 5000 ms).

Counters aggregate by name, dimensions, type, unit and resolution; gauges retain
the latest source value; timers preserve samples in arrays of up to 100 values.
Malformed measurements and invalid aggregates are counted invalid independently
of valid companions. EMF documents are split before their encoded UTF-8 size
exceeds 1 MiB. Local acceptance does not establish CloudWatch extraction; check
the logging pipeline and CloudWatch EMF validation metrics in deployment.

Read-model processor metrics require no consumer startup/configuration changes.
The publisher has a narrow semantic fallback for the eleven new `ReadModel*`
read/catch-up/committed-work/wait/backoff/cold-build metrics described in the
[read-model guide](../read-model-processor-v2/README.md#production-metrics). This
preserves Bytes for `ReadModelEncodedBytesWritten`, standard-resolution work
counters, and millisecond timers even when a deployment's custom registry predates
these metrics. A configured definition takes precedence; fallback does not grant
new dimension permissions or trust arbitrary event-supplied units.

`grain-metric-registry` accepts bounded `:read-model-operation`,
`:read-model-kind`, `:read-model-scope`, `:read-model-outcome`,
`:read-model-wait-kind`, and `:read-model-target` vocabularies. These names are
separate from generic operation/outcome dimensions. Optionally declare
`:read-model-name #{":my-app/my-model"}`. An unknown top-level model name is
omitted so aggregate publication continues; explicit `:metric/dimensions` values
are validated strictly. No tenant IDs or instance keys are emitted as dimensions.
