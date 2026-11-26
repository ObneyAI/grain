;; Seed a disposable empty PostgreSQL database for identical before/after clones.
;; Args: fixture.edn [domain-event-count]; connection comes from PG_*.
(def seed-args (vec *command-line-args*))
(binding [*command-line-args* ["--load-only"]]
  (load-file "scripts/benchmarks/retention-scale.clj"))
(try
  (let [[path count-str] seed-args
        n (Long/parseLong (or count-str "1000000"))
        _ (check! (and path (pos-int? n) (zero? (mod n 100)))
                  "Supply a fixture path and a positive event count divisible by 100" {})
        config {:conn {:type :postgres :server-name (or (System/getenv "PG_HOST") "127.0.0.1")
                       :port-number (or (System/getenv "PG_PORT") "5432")
                       :database-name (or (System/getenv "PG_DATABASE") "grain_retention_seed")
                       :username (or (System/getenv "PG_USER") "postgres")
                       :password (or (System/getenv "PG_PASSWORD") "password")}}
        store (es/start config)
        tenant (uuid/v4)
        at (OffsetDateTime/now ZoneOffset/UTC)]
    (try
      (check! (empty? (es/tenants store)) "Seed database must be empty" {})
      (let [loaded (seed! store tenant n (min 1000 (quot n 100)) at)]
        (io/make-parents path)
        (spit path (pr-str {:tenant tenant :recorded-at (str at) :load loaded})))
      (finally (es/stop store))))
  (shutdown-agents)
  (catch Throwable t (.printStackTrace t) (shutdown-agents) (System/exit 1)))
