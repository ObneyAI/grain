# Grain CloudWatch EMF publisher

Applications can publish domain metrics through the same publisher without
adding those metrics to Grain. Extend the framework registry when configuring
the publisher:

```clojure
(def application-metrics
  {"EnrollmentCompleted"
   {:metric/type :counter
    :metric/unit :count
    :metric/resolution :standard
    :metric/dimensions {:service #{"academy"}
                        :outcome #{"succeeded" "failed"}}}})

(cloudwatch-emf/start-cloudwatch-emf-publisher!
 {:metric-registry
  (cloudwatch-emf/extend-grain-metric-registry
   {:app-name #{"academy"}
    :env #{"production"}}
   application-metrics)})
```

Emit a matching μ/log event with dimensions nested under
`:metric/dimensions`:

```clojure
(u/log :metric/metric
       :metric/name "EnrollmentCompleted"
       :metric/value 1
       :metric/resolution :low
       :metric/dimensions {:service "academy"
                           :outcome "succeeded"})
```

Consumer metric names must not collide with Grain metric names. Every custom
dimension must have an explicit bounded vocabulary; identifiers, raw URLs,
messages, payload data, credentials, and personal data must not be dimensions.

For an already-built Grain registry, plain `merge` is equivalent only when the
metric-name sets are known to be disjoint. Prefer
`extend-grain-metric-registry`, which detects collisions.
