;; Run from the workspace root against a disposable database:
;; clojure -J-Xmx4g -M:dev scripts/benchmarks/retention-scale.clj sqlite 1000000 results.edn
;; PG_HOST/PG_PORT/PG_DATABASE/PG_USER/PG_PASSWORD select PostgreSQL instead.
;; PostgreSQL must be empty; this script never truncates an existing store.
;; The event count excludes transaction markers, policy facts, and live writes.
(require '[ai.obney.grain.event-store-v3.interface :as es]
         '[ai.obney.grain.event-store-v3.interface.event-definition :as definitions]
         '[ai.obney.grain.event-store-sqlite-v3.interface]
         '[ai.obney.grain.event-store-postgres-v3.core :as pg]
         '[ai.obney.grain.event-store-sqlite-v3.core :as sqlite]
         '[ai.obney.grain.event-retention.interface :as retention]
         '[ai.obney.grain.event-store-v3.interface.compaction :as compaction]
         '[ai.obney.grain.time.interface :as time]
         '[clojure.java.io :as io]
         '[clojure.pprint :as pprint]
         '[clojure.walk :as walk]
         '[next.jdbc :as jdbc]
         '[next.jdbc.result-set :as rs]
         '[clj-uuid :as uuid])
(import '[java.io File]
        '[java.time OffsetDateTime ZoneOffset]
        '[java.security MessageDigest]
        '[java.util HashSet HexFormat UUID])

(doseq [type [:retention-scale/expired :retention-scale/keyed
              :retention-scale/permanent :retention-scale/live]]
  (definitions/register-event-definition!
   type "Retention scale-test event"
   (cond-> {:schema [:map [:scale/n :int] [:scale/payload :string]]}
     (= type :retention-scale/expired) (assoc :history {:retain-at-least "P1D"})
     (= type :retention-scale/keyed) (assoc :history {:retain-at-least "P1D"
                                                    :keep-latest-per {:tags #{:scale/key}}}))
   {:file *file*}))

(defn check! [condition message data]
  (when-not condition (throw (ex-info message data))))
(defn now-ns [] (System/nanoTime))
(defn elapsed-ms [start] (/ (- (now-ns) start) 1e6))
(defn progress! [data] (prn (assoc data :at (str (OffsetDateTime/now ZoneOffset/UTC)))) (flush))
(defn canonical [value]
  (walk/postwalk (fn [v] (cond (map? v) (into (sorted-map) v)
                             (set? v) (vec (sort-by pr-str v))
                             :else v)) value))
(defn digest! [^MessageDigest digest event]
  (.update digest (.getBytes (str (pr-str (canonical event)) "\n") "UTF-8")))
(defn digest-result [^MessageDigest digest] (.formatHex (HexFormat/of) (.digest digest)))
(defn sha [] (MessageDigest/getInstance "SHA-256"))
(defn percentile [values p]
  (let [sorted (vec (sort values))]
    (when (seq sorted) (nth sorted (max 0 (dec (long (Math/ceil (* p (count sorted))))))))))
(defn latency-summary [values]
  {:samples (count values) :p50-ms (percentile values 0.50)
   :p95-ms (percentile values 0.95) :p99-ms (percentile values 0.99)
   :max-ms (when (seq values) (apply max values))})
(def payload (apply str (repeat 64 "x")))
(defn event [type n tags]
  (es/->event {:type type :tags tags :body {:scale/n n :scale/payload payload}}))
(defn append! [store tenant events]
  (let [returned (es/append store {:tenant-id tenant :events events})]
    (check! (and (vector? returned) (= (count events) (count returned)))
            "Append failed" {:result returned})
    returned))
(defn kind [n]
  (let [group (quot n 10) category (mod group 10)]
    (cond (< category 5) :retention-scale/expired
          (= category 5) (if (< (mod n 10) 5) :retention-scale/expired :retention-scale/permanent)
          (#{6 7} category) :retention-scale/keyed
          (= category 8) :retention-scale/expired
          :else :retention-scale/permanent)))
(defn key-number [n key-count] (mod (quot n 100) key-count))
(defn seed! [store tenant n key-count recorded-at]
  (let [start (now-ns) old (.minusDays recorded-at 3)]
    (dotimes [group (quot n 10)]
      (let [events (mapv (fn [number]
                           (let [type (kind number)]
                             (event type number
                                    (if (= type :retention-scale/keyed)
                                      #{[:scale/key (UUID. 1 (key-number number key-count))]}
                                      #{}))))
                         (range (* group 10) (+ (* group 10) 10)))]
        (with-redefs [time/now (constantly (if (= 8 (mod group 10)) recorded-at old))]
          (append! store tenant events)))
      (when (zero? (mod (inc group) 10000))
        (progress! {:phase :seed :domain-events (* 10 (inc group)) :elapsed-ms (elapsed-ms start)})))
    {:domain-events n :transactions (quot n 10) :elapsed-ms (elapsed-ms start)}))

(defn manifest! [store tenant n key-count]
  (let [latest (reduce (fn [m number] (assoc m (key-number number key-count) number))
                       {} (filter #(= :retention-scale/keyed (kind %)) (range n)))
        deleted (HashSet.) deleted-txs (HashSet.) survivors (sha)
        result (reduce
                (fn [{:keys [batch-survives?] :as acc} e]
                  (if (= :grain/tx (:event/type e))
                    (do (if batch-survives? (digest! survivors e) (.add deleted-txs (:event/id e)))
                        (-> acc (assoc :batch-survives? false) (update :markers inc)))
                    (let [number (:scale/n e)
                          keep? (or (= :retention-scale/permanent (:event/type e))
                                    (= 8 (mod (quot number 10) 10))
                                    (and (= :retention-scale/keyed (:event/type e))
                                         (= number (get latest (key-number number key-count)))))]
                      (check! (and (int? number) (<= 0 number) (< number n)) "Invalid seeded event" {:event e})
                      (if keep? (digest! survivors e) (.add deleted (:event/id e)))
                      (-> acc (update :domain-events inc)
                          (update :survivors + (if keep? 1 0))
                          (update :batch-survives? #(or % keep?))))))
                {:domain-events 0 :markers 0 :survivors 0 :batch-survives? false}
                (es/read store {:tenant-id tenant}))]
    (check! (= n (:domain-events result)) "Seed count mismatch" result)
    (check! (= (quot n 10) (:markers result)) "Seed marker count mismatch" result)
    (assoc result :deleted deleted :deleted-txs deleted-txs :survivor-sha256 (digest-result survivors)
           :expected-deleted-events (.size deleted) :expected-deleted-transactions (.size deleted-txs))))

(defn audit-receipt! [manifest receipts receipt]
  (doseq [[field ^HashSet pending] [[:retention/deleted-event-ids (:deleted manifest)]
                                   [:retention/deleted-transaction-ids (:deleted-txs manifest)]]
          id (get receipt field)]
    (check! (.remove pending id) "Unexpected or duplicate receipted deletion" {:field field :id id}))
  (swap! receipts assoc (:event/id receipt) (canonical receipt)))

(defn compact-type! [admin tenant type batch-size manifest receipts]
  (let [start (now-ns)]
    (loop [latencies [] events 0 transactions 0]
      (let [began (now-ns) receipt (retention/compact! admin type tenant batch-size)
            ms (elapsed-ms began)]
        (if-not receipt
          (let [elapsed (elapsed-ms start)]
            {:type type :batches (count latencies) :deleted-events events
             :deleted-transactions transactions :elapsed-ms elapsed
             :events-per-second (/ (* events 1000.0) elapsed)
             :batch-latency (latency-summary latencies)})
          (let [event-count (count (:retention/deleted-event-ids receipt))
                tx-count (count (:retention/deleted-transaction-ids receipt))]
            (check! (<= 1 event-count batch-size) "Batch exceeded its event bound" {:count event-count})
            (check! (<= tx-count event-count) "Too many markers deleted" {:count tx-count})
            (audit-receipt! manifest receipts receipt)
            (when (zero? (mod (inc (count latencies)) 25))
              (progress! {:phase :compact :type type :batches (inc (count latencies))
                          :deleted-events (+ events event-count) :last-batch-ms ms
                          :elapsed-ms (elapsed-ms start)}))
            (recur (conj latencies ms) (+ events event-count) (+ transactions tx-count))))))))

(defn start-writer! [store tenant]
  (let [running? (atom true) latencies (atom [])
        task (future
               (loop [n 0]
                 (when @running?
                   (let [start (now-ns)]
                     (append! store tenant [(event :retention-scale/live n #{})])
                     (swap! latencies conj (elapsed-ms start)))
                   (Thread/sleep 20)
                   (recur (inc n)))))]
    {:running? running? :latencies latencies :task task}))
(defn stop-writer! [{:keys [running? task latencies]}]
  (reset! running? false)
  (check! (not= ::timeout (deref task 30000 ::timeout)) "Writer did not finish" {})
  (latency-summary @latencies))

(defn verify! [store tenant manifest receipts live-count]
  (let [digest (sha) seen-receipts (HashSet.) live-ids (HashSet.)
        result (reduce
                (fn [acc e]
                  (case (:event/type e)
                    :retention-scale/live
                    (do (.add live-ids (:event/id e)) (update acc :live-events inc))
                    :grain.retention/compacted
                    (do (check! (= (get receipts (:event/id e)) (canonical e))
                                "Stored receipt differs from committed result" {:id (:event/id e)})
                        (.add seen-receipts (:event/id e)) (update acc :receipts inc))
                    :grain/tx
                    (cond
                      (some #(.contains seen-receipts %) (:event-ids e))
                      (do (check! (and (= 1 (count (:event-ids e)))
                                       (.remove seen-receipts (first (:event-ids e))))
                                  "Invalid receipt marker" {:id (:event/id e)})
                          (update acc :receipt-markers inc))
                      (some #(.contains live-ids %) (:event-ids e))
                      (do (check! (and (= 1 (count (:event-ids e)))
                                       (.remove live-ids (first (:event-ids e))))
                                  "Invalid live marker" {:id (:event/id e)})
                          (update acc :live-markers inc))
                      :else (do (digest! digest e) (update acc :original-markers inc)))
                    (do (digest! digest e) (update acc :original-events inc))))
                {:original-events 0 :original-markers 0 :receipts 0 :receipt-markers 0
                 :live-events 0 :live-markers 0}
                (es/read store {:tenant-id tenant}))
        actual-sha (digest-result digest)]
    (check! (= (:survivor-sha256 manifest) actual-sha) "Survivor contents/order differ" result)
    (check! (= (:survivors manifest) (:original-events result)) "Survivor count differs" result)
    (check! (= (- (:markers manifest) (:expected-deleted-transactions manifest))
               (:original-markers result)) "Surviving marker count differs" result)
    (check! (= live-count (:live-events result) (:live-markers result)) "Live writes lost" result)
    (check! (= (count receipts) (:receipts result) (:receipt-markers result)) "Receipt count differs" result)
    (check! (and (.isEmpty seen-receipts) (.isEmpty live-ids)) "Unpaired markers" result)
    (check! (and (.isEmpty ^HashSet (:deleted manifest)) (.isEmpty ^HashSet (:deleted-txs manifest)))
            "Expected deletions missing from receipts" {})
    (let [last-event (first (into [] (es/read store {:tenant-id tenant :reverse? true :limit 1})))]
      (check! (= (:event/id last-event) (es/head store tenant)) "Watermark differs from last event" {}))
    (assoc result :survivor-sha256 actual-sha :passed? true)))

(defn pool [backend store]
  (get-in store [:state (if (= backend :postgres) ::pg/connection-pool ::sqlite/connection-pool)]))
(defn storage-check! [backend store]
  (if (= backend :sqlite)
    (let [db (pool backend store)
          orphans (:n (jdbc/execute-one! db ["SELECT COUNT(*) AS n FROM event_tags t LEFT JOIN events e ON e.tenant_id=t.tenant_id AND e.id=t.event_id WHERE e.id IS NULL"] {:builder-fn rs/as-unqualified-lower-maps}))]
      (check! (zero? orphans) "Orphaned SQLite tags" {:orphans orphans})
      {:orphaned-tags orphans})
    {:database-bytes (:bytes (jdbc/execute-one! (pool backend store)
                           ["SELECT pg_database_size(current_database()) AS bytes"]
                           {:builder-fn rs/as-unqualified-lower-maps}))}))

;; Comparison harnesses can run the identical seeded store through two builds.
;; These bindings are internal to benchmarks; the CLI still requires an empty DB.
(def ^:dynamic *prepared-fixture* nil)
(def ^:dynamic *store-config* nil)

(defn run-scale! [backend n batch-size result-path]
  (check! (and (pos-int? n) (zero? (mod n 100))) "Count must be a positive multiple of 100" {:count n})
  (check! (pos-int? batch-size) "Batch size must be positive" {:batch-size batch-size})
  (let [tmp (when (= backend :sqlite) (File/createTempFile "grain-retention-scale-" ".sqlite"))
        config (or *store-config* {:conn (case backend
                        :sqlite {:type :sqlite :database-file (.getAbsolutePath tmp)}
                        :postgres {:type :postgres :server-name (or (System/getenv "PG_HOST") "127.0.0.1")
                                   :port-number (or (System/getenv "PG_PORT") "5432")
                                   :database-name (or (System/getenv "PG_DATABASE") "grain_retention_scale")
                                   :username (or (System/getenv "PG_USER") "postgres")
                                   :password (or (System/getenv "PG_PASSWORD") "password")})})
        store* (atom (es/start config))
        started-at (str (OffsetDateTime/now ZoneOffset/UTC))
        tenant (or (:tenant *prepared-fixture*) (uuid/v4))
        recorded-at (if-let [at (:recorded-at *prepared-fixture*)]
                      (OffsetDateTime/parse at) (OffsetDateTime/now ZoneOffset/UTC))
        key-count (min 1000 (quot n 100))]
    (try
      (check! (if *prepared-fixture*
                (= #{tenant} (set (keys (es/tenants @store*))))
                (empty? (es/tenants @store*)))
              "Scale test requires an empty database or its exact prepared fixture" {})
      (progress! {:phase :start :backend backend :domain-events n :batch-size batch-size})
      (let [load-result (or (:load *prepared-fixture*)
                            (seed! @store* tenant n key-count recorded-at))
            admin (retention/administration @store* {:clock (constantly recorded-at)})
            activation-start (now-ns)
            _ (doseq [type [:retention-scale/expired :retention-scale/keyed]] (retention/activate! admin type))
            activation-ms (elapsed-ms activation-start)
            _ (progress! {:phase :activated :elapsed-ms activation-ms})
            manifest (manifest! @store* tenant n key-count)
            system-before (into [] (es/read @store* {:tenant-id compaction/system-tenant-id}))
            _ (progress! {:phase :manifest :domain-events (:domain-events manifest)
                          :transaction-markers (:markers manifest)
                          :expected-deleted-events (:expected-deleted-events manifest)
                          :expected-deleted-transactions (:expected-deleted-transactions manifest)})
            estimates (mapv (fn [type]
                              (let [start (now-ns) estimate (retention/estimate admin type tenant batch-size)]
                                {:type type :elapsed-ms (elapsed-ms start)
                                 :events (:eligible-count estimate)
                                 :transactions (:eligible-transaction-count estimate)}))
                            [:retention-scale/expired :retention-scale/keyed])
            receipts (atom {})
            writer (start-writer! @store* tenant)
            phases (try
                     (mapv #(compact-type! admin tenant % batch-size manifest receipts)
                           [:retention-scale/expired :retention-scale/keyed])
                     (finally
                       (reset! (:running? writer) false)
                       (deref (:task writer) 30000 ::timeout)))
            writer-result (stop-writer! writer)
            _ (progress! {:phase :verifying :live-writes (:samples writer-result)})
            verified (verify! @store* tenant manifest @receipts (:samples writer-result))
            _ (check! (= system-before (into [] (es/read @store* {:tenant-id compaction/system-tenant-id})))
                      "Policy lifecycle changed" {})
            storage (storage-check! backend @store*)
            _ (es/stop @store*)
            _ (reset! store* nil)
            _ (reset! store* (es/start config))
            after-restart (verify! @store* tenant manifest @receipts (:samples writer-result))
            restarted-admin (retention/administration @store* {:clock (constantly recorded-at)})
            head (es/head @store* tenant)
            _ (doseq [type [:retention-scale/expired :retention-scale/keyed]]
                (check! (nil? (retention/compact! restarted-admin type tenant batch-size)) "Retry was not empty" {:type type}))
            _ (check! (= head (es/head @store* tenant)) "Empty retry changed watermark" {})
            result {:backend backend :started-at started-at :completed-at (str (OffsetDateTime/now ZoneOffset/UTC))
                    :java (System/getProperty "java.version") :os (System/getProperty "os.name")
                    :arch (System/getProperty "os.arch") :max-heap-bytes (.maxMemory (Runtime/getRuntime))
                    :fixture {:domain-events n :preseeded? (boolean *prepared-fixture*) :events-per-append 10 :key-count key-count :payload-characters 64
                              :batch-size batch-size :writer-pause-ms 20}
                    :load load-result :activation-ms activation-ms :initial-estimates estimates
                    :compaction phases :concurrent-appends writer-result :verification verified
                    :restart-verification after-restart :storage storage :passed? true}]
        (io/make-parents result-path)
        (spit result-path (with-out-str (pprint/pprint result)))
        (progress! {:phase :complete :backend backend :result result-path :passed? true})
        result)
      (finally
        (when @store* (es/stop @store*))
        (when tmp
          (doseq [suffix ["" "-wal" "-shm" "-journal"]]
            (.delete (File. (str (.getAbsolutePath tmp) suffix)))))))))

(when-not (= ["--load-only"] (vec *command-line-args*))
 (let [[backend count-str result-path batch-str] *command-line-args*]
  (try
    (run-scale! (keyword (or backend "sqlite")) (Long/parseLong (or count-str "1000000"))
          (Long/parseLong (or batch-str "1000")) (or result-path "artifacts/retention-scale/result.edn"))
    (shutdown-agents)
    (catch Throwable t
      (.printStackTrace t)
      (shutdown-agents)
      (System/exit 1)))))
