(ns ai.obney.grain.read-model-processor-v2.session-concurrency-test
  "Keyed read sessions take no process-wide lock: a cold build blocks only reads
   of its own model namespace, catch-up is single-flight per namespace identity,
   deferred builds are owned in-process, and a warm read is served from the one
   snapshot that proved it fresh."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.obney.grain.read-model-processor-v2.interface-test :as fixture]
            [ai.obney.grain.read-model-processor-v2.interface :as rmp]
            [ai.obney.grain.read-model-processor-v2.core :as core]
            [ai.obney.grain.kv-store.interface :as kv]
            [ai.obney.grain.event-store-v3.interface :as es]))

(def model :session-concurrency/items)
(def other :session-concurrency/other)
(def tenant (java.util.UUID. 73 1))
(def neighbour (java.util.UUID. 73 2))

(defn- cv [s]
  (or (get (ns-interns 'ai.obney.grain.read-model-processor-v2.core) s)
      (throw (ex-info "No such processor var" {:var s}))))

(defn- bodies [seed n]
  (let [r (java.util.Random. seed)]
    (vec (for [_ (range n)]
           {:inputs [[(.nextInt r 50) {:score (.nextInt r 9) :name (str "n" (.nextInt r 1000))}]]}))))

(defn- truth [history]
  (into (sorted-map)
        (reduce (fn [m {:keys [inputs]}] (reduce (fn [m [o in]] (update m o merge in)) m inputs)) {} history)))

(defn- register! []
  (doseq [name [model other]]
    (rmp/register-read-model! name merge
      {:events #{:keyed/change} :version 1 :route :inputs
       :indexes {:score :score} :searches {:name :name}})))

(defn- values [ctx name] (into (sorted-map) (map (juxt :key :value)) (rmp/values ctx name)))

(defn- ctx-for [t] (assoc fixture/*keyed-ctx* :tenant-id t :read-model/batch-size 8
                          :read-model/deferred-build-threshold 16))

(defn- append! [ctx history] (fixture/keyed-append! ctx history))

(defn- counting [calls var-sym]
  (let [f @(cv var-sym)]
    {(cv var-sym) (fn [& args] (swap! calls update var-sym (fnil inc 0)) (apply f args))}))

(defn- in-flight [] @@(cv 'keyed-catch-ups*))
(defn- owners [] @@(cv 'deferred-owners*))

;; (a) Same-model contention ---------------------------------------------------

(deftest concurrent-readers-of-one-cold-model-share-one-build
  ;; Mutations killed: no single-flight (every thread claims; :wait results),
  ;; the JVM lock put back (the warm reads of `other` wait for the build).
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) history (bodies 3 300)
            build @(cv 'deferred-build!) claim @(cv 'deferred-claim!) wipe @(cv 'keyed-wipe!)
            guard @(cv 'deferred-guard!)
            claims (atom []) builds (atom 0) superseded (atom 0) staging-wipes (atom 0)
            staging (atom nil) entered (promise) release (promise) warm-during (atom [])]
        (register!)
        (append! ctx history)
        (is (= (truth history) (values ctx other)) "other is warm before the build starts")
        (with-redefs-fn {(cv 'deferred-claim!)
                         (fn [& args] (let [c (apply claim args)]
                                        (when (map? c) (reset! staging (:prefix c)))
                                        (swap! claims conj (if (map? c) :claimed c)) c))
                         (cv 'deferred-build!)
                         (fn [& args] (swap! builds inc) (deliver entered true)
                           ;; Held until the warm reads are done (bounded for a failing run).
                           (deref release 20000 nil) (apply build args))
                         (cv 'deferred-guard!)
                         (fn [& args] (try (apply guard args)
                                           (catch clojure.lang.ExceptionInfo e (swap! superseded inc) (throw e))))
                         (cv 'keyed-wipe!)
                         (fn [tx prefix] (when (and @staging (java.util.Arrays/equals ^bytes prefix ^bytes @staging))
                                           (swap! staging-wipes inc))
                           (wipe tx prefix))}
          (fn []
            (let [readers (vec (repeatedly 4 #(future (values ctx model))))
                  _ (is (deref entered 10000 nil) "The build started")
                  ;; Warm reads of another model of the same tenant run to
                  ;; completion while the build is still in deferred-build!.
                  warm (future (dotimes [_ 20]
                                 (let [t0 (System/nanoTime) v (values ctx other)]
                                   (swap! warm-during conj [(- (System/nanoTime) t0) (= (truth history) v)]))))]
              (try
                (is (not= ::timeout (deref warm 5000 ::timeout)) "Warm reads never wait for the cold build")
                (is (= 20 (count @warm-during)))
                (is (every? second @warm-during))
                (Thread/sleep 100)
                (is (not-any? realized? readers) "Readers of the building model wait for its build")
                (finally (deliver release true)))
              (is (= (repeat 4 (truth history)) (map #(deref % 20000 ::timeout) readers)))
              (deref warm 20000 nil))))
        (is (= [:claimed] @claims) "One claim: other threads wait for the in-flight catch-up")
        (is (= 1 @builds))
        (is (zero? @superseded) "No supersede ping-pong")
        (is (zero? @staging-wipes) "The live staging namespace is never wiped (the claim wipes before staging is known)")
        (is (empty? (in-flight)))
        (is (empty? (owners)))))))

(deftest in-process-owner-is-live-until-released
  ;; Mutations killed: deferred-live? ignoring the registry (the second claim
  ;; takes over and supersedes, and clean-stale wipes the live staging), and
  ;; dropping the disown in `finally` (the read after a failure waits forever).
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) identity [tenant model 1]]
        (register!)
        (append! ctx (bodies 3 300))
        (let [b (get (core/namespace-resolve! (:cache ctx) [identity]) identity)
              claim @(cv 'deferred-claim!)
              owned (claim ctx identity b)
              marker #(kv/transact (:cache ctx) :read (fn [tx] (@(cv 'deferred-marker) tx identity)))]
          (is (map? owned))
          (is (= {[(:env (:cache ctx)) identity] (:build owned)} (owners)))
          (testing "another thread of this process sees a live owner"
            (is (= :wait @(future (claim ctx identity b))))
            (is (true? (@(cv 'deferred-live?) ctx identity (marker) 30000))))
          (testing "warm-read housekeeping never wipes a live in-process build"
            (kv/transact (:cache ctx) :write #((:put %) (core/key-join (:prefix owned) (byte-array [2 9])) (byte-array [1])))
            (@(cv 'deferred-clean-stale!) ctx identity)
            (is (= (:build owned) (:build (marker))))
            (is (some? (kv/transact (:cache ctx) :read
                         #((:get %) (core/key-join (:prefix owned) (byte-array [2 9])))))))
          (testing "after release the same-process marker is stale and is taken over"
            (@(cv 'deferred-disown!) ctx identity (:build owned))
            (is (false? (@(cv 'deferred-live?) ctx identity (marker) 30000)))
            (let [taken @(future (claim ctx identity b))]
              (is (:resumed? taken))
              (@(cv 'deferred-disown!) ctx identity (:build taken))))
          (testing "a failing build attempt releases ownership; the next read completes"
            (let [build @(cv 'deferred-build!)
                  failed (with-redefs-fn {(cv 'deferred-build!) (fn [& _] (throw (ex-info "boom" {})))}
                           #(try (values ctx model) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))]
              (is (= "boom" failed))
              (is (empty? (owners)))
              (is (= (truth (bodies 3 300)) (deref (future (values ctx model)) 20000 ::timeout)))
              (is (some? build)))))))))

;; (b) Noisy neighbour ---------------------------------------------------------

(deftest a-cold-build-never-blocks-another-tenants-warm-reads
  ;; Mutation killed: the JVM-wide (locking cache ...) put back.
  (fixture/keyed-fixture
    (fn []
      (let [a (ctx-for tenant) b (ctx-for neighbour)
            build @(cv 'deferred-build!) entered (promise) release (promise)]
        (register!)
        (append! b (bodies 5 40))
        (is (= (truth (bodies 5 40)) (values b model)) "Tenant B is warm")
        (append! a (bodies 3 300))
        (with-redefs-fn {(cv 'deferred-build!)
                         (fn [c & more]
                           (when (= tenant (:tenant-id c))
                             (deliver entered true)
                             ;; Held until B's reads are done (bounded for a failing run).
                             (deref release 20000 nil))
                           (apply build c more))}
          (fn []
            (let [builder (future (values a model)) reads (promise)]
              (try
                (is (deref entered 10000 nil))
                (let [_ (deliver reads (future (vec (repeatedly 50 #(values b model)))))
                      done (deref @reads 5000 ::timeout)]
                  (is (not= ::timeout done) "B's warm reads complete while A's build is held")
                  (when (vector? done) (is (every? #(= (truth (bodies 5 40)) %) done))))
                (finally (deliver release true)))
              (is (= (truth (bodies 3 300)) (deref builder 20000 ::timeout)))
              (some-> (deref reads 0 nil) (deref 20000 nil)))))))))

;; (c) Publish during reads ----------------------------------------------------

(deftest reads-see-the-old-or-the-published-namespace-never-a-wiped-one
  ;; Readers whose head precedes the build's events keep reading while the
  ;; build publishes. Each session resolves, checks and reads in one snapshot,
  ;; so it sees the complete cold namespace (checked through its head) or the
  ;; complete published one. Mutation killed: resolving/checking in a separate
  ;; transaction from the read (the read then finds the wiped namespace: no
  ;; progress), as the locked implementation did after releasing its lock.
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) history (bodies 3 300)
            head-fn es/head fresh? @(cv 'keyed-fresh?) publish @(cv 'deferred-publish!)
            readers (atom #{}) go (promise) published (promise) observations (atom [])]
        (register!)
        ;; One event of another type, so the cold namespace is checked through
        ;; a real head H0 with no folded event.
        (es/append (:event-store ctx) {:tenant-id tenant :events [(es/->event {:type :keyed/other :body {}})]})
        (with-redefs-fn {#'es/stable-empty-intervals? (constantly false)}
          (fn []
            (let [h0 (head-fn (:event-store ctx) tenant)]
              (is (= {} (values ctx model)))
              (is (= h0 (:checked-through (rmp/progress ctx model))))
              (append! ctx history)
              (with-redefs-fn
                {#'es/head (fn [store t] (if (contains? @readers (Thread/currentThread)) h0 (head-fn store t)))
                 (cv 'keyed-fresh?) (fn [& args] (let [r (apply fresh? args)]
                                                   (when (contains? @readers (Thread/currentThread)) (Thread/sleep 5))
                                                   r))
                 (cv 'deferred-publish!) (fn [& args] @go (let [r (apply publish args)] (deliver published true) r))}
                (fn []
                  (let [builder (future (values ctx model))
                        read-loop (fn []
                                    (swap! readers conj (Thread/currentThread))
                                    (loop [after 0]
                                      (when (< after 40)
                                        (swap! observations conj
                                               (rmp/read-session ctx [model]
                                                 (fn [s] [(rmp/progress s model) (values s model)])))
                                        (recur (if (realized? published) (inc after) after)))))
                        threads (vec (repeatedly 4 #(future (read-loop))))]
                    (Thread/sleep 300)
                    (deliver go true)
                    (is (= (truth history) (deref builder 20000 ::timeout)))
                    (doseq [t threads] (deref t 20000 nil)))))
              (let [full (truth history)
                    old? (fn [[p v]] (and (= {} v) (= h0 (:checked-through p)) (nil? (:watermark p))))
                    new? (fn [[p v]] (and (= full v) (some? (:watermark p))))
                    groups (group-by #(cond (old? %) :old (new? %) :new :else :inconsistent) @observations)]
                (is (pos? (count (:old groups))))
                (is (pos? (count (:new groups))))
                (is (empty? (:inconsistent groups)) (pr-str (take 2 (:inconsistent groups))))))))))))

;; (d) Single-flight errors ----------------------------------------------------
;; A failure is thrown only in the thread whose own attempt failed (as under the
;; former store-wide lock); waiters retry. Mutation killed: forwarding the
;; winner's Throwable to every waiter (the first version of this change).

(defn- attempt [ctx]
  (future (let [me (Thread/currentThread)]
            (try [:ok (values ctx model)]
                 (catch Throwable t [:error (.getSimpleName (class t)) (:type (ex-data t))
                                     (identical? me (:thread (ex-data t)))])))))

(deftest a-transient-catch-up-failure-fails-only-the-failing-request
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) catch-up @(cv 'keyed-catch-up!)
            first? (atom true) entered (promise) release (promise)]
        (register!)
        (append! ctx (bodies 3 40))
        (let [[winner & waiters]
              (with-redefs-fn {(cv 'keyed-catch-up!)
                               (fn [& args]
                                 (if (compare-and-set! first? true false)
                                   (do (deliver entered true) (deref release 20000 nil)
                                       (throw (ex-info "transient" {:type ::injected :thread (Thread/currentThread)})))
                                   (apply catch-up args)))}
                (fn []
                  (let [w (attempt ctx) _ (deref entered 10000 nil)
                        ws (vec (repeatedly 3 #(attempt ctx)))]
                    (Thread/sleep 300)
                    (is (= 1 (count (in-flight))))
                    (deliver release true)
                    (mapv #(deref % 20000 ::timeout) (cons w ws)))))]
          (is (= [:error "ExceptionInfo" ::injected true] winner) "The failing attempt's own thread gets its error")
          (is (= (repeat 3 [:ok (truth (bodies 3 40))]) waiters) "Waiters retry and succeed")
          (is (empty? (in-flight))))))))

(deftest a-persistent-catch-up-failure-reaches-every-caller-from-its-own-attempt
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) calls (atom 0)]
        (register!)
        (append! ctx (bodies 3 40))
        (let [outcomes
              (with-redefs-fn {(cv 'keyed-catch-up!)
                               (fn [& _] (swap! calls inc) (Thread/sleep 50)
                                 (throw (ex-info "persistent" {:type ::injected :thread (Thread/currentThread)})))}
                #(mapv deref (vec (repeatedly 4 (fn [] (attempt ctx))))))]
          (is (= (repeat 4 [:error "ExceptionInfo" ::injected true]) outcomes)
              "Every caller fails with the exception of its own attempt")
          (is (<= 4 @calls) "Every caller made its own attempt")
          (is (empty? (in-flight)) "No entry is left behind")
          (is (= (truth (bodies 3 40)) (deref (future (values ctx model)) 20000 ::timeout))))))))

;; Mutation killed: no own attempt after repeated foreign failures (the waiter
;; then waits on other threads' failing attempts forever).
(deftest a-waiter-behind-repeatedly-failing-attempts-makes-its-own
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) k [(:env (:cache ctx)) [tenant model 1]]
            failed :ai.obney.grain.read-model-processor-v2.core/failed
            running (atom true)]
        (register!)
        (append! ctx (bodies 3 40))
        ;; Other threads' attempts that keep failing: each in-flight entry is
        ;; replaced by the next before it reports its failure.
        (let [feeder (future
                       (loop [p (promise)]
                         (swap! @(cv 'keyed-catch-ups*) assoc k p)
                         (when @running
                           (Thread/sleep 20)
                           (let [next (promise)]
                             (swap! @(cv 'keyed-catch-ups*) assoc k next)
                             (deliver p failed)
                             (recur next))))
                       (swap! @(cv 'keyed-catch-ups*) dissoc k))]
          (let [waiter (future (values ctx model))]
            (try
              (is (= (truth (bodies 3 40)) (deref waiter 5000 ::timeout))
                  "The waiter makes its own attempt")
              ;; Never leave the fixture (which closes the store) with a
              ;; thread still using it, also when the assertion failed.
              (finally (reset! running false) (deref feeder 5000 nil) (deref waiter 20000 nil))))
          (is (empty? (in-flight))))))))

;; Mutation killed: a failed attempt not reported as a failure (waiters then
;; never reach their own attempt and keep competing for the single flight).
(deftest a-real-failure-counts-toward-the-own-attempt
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) k [(:env (:cache ctx)) [tenant model 1]]
            catch-up @(cv 'keyed-catch-up!) entered (promise) release (promise)
            first? (atom true) registered-during-own (atom ::unset)]
        (register!)
        (append! ctx (bodies 3 40))
        (with-redefs-fn {(cv 'keyed-catch-up-foreign-failures) 1
                         (cv 'keyed-catch-up!)
                         (fn [& args]
                           (if (compare-and-set! first? true false)
                             (do (deliver entered true) (deref release 20000 nil)
                                 (throw (ex-info "fails" {:type ::injected})))
                             (do (reset! registered-during-own (contains? @@(cv 'keyed-catch-ups*) k))
                                 (apply catch-up args))))}
          (fn []
            (let [w (attempt ctx) _ (deref entered 10000 nil)
                  o (attempt ctx)]
              (Thread/sleep 300)
              (deliver release true)
              (is (= ::injected (nth (deref w 20000 nil) 2)))
              (is (= [:ok (truth (bodies 3 40))] (deref o 20000 ::timeout)))
              (is (false? @registered-during-own)
                  "After one failed foreign attempt the waiter's own attempt runs outside the single flight"))))))))

;; D2. Mutation killed: delivering the outcome before removing the entry (a
;; waiter then counts one failure several times and leaves the single flight
;; after a single foreign failure). The window is widened by delaying deliver.
(deftest one-failed-attempt-is-counted-once
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) k [(:env (:cache ctx)) [tenant model 1]]
            catch-up @(cv 'keyed-catch-up!) orig-deliver deliver
            entered (promise) first? (atom true) calls (atom [])]
        (register!)
        (append! ctx (bodies 3 40))
        (with-redefs-fn {(cv 'keyed-catch-up!)
                         (fn [& args]
                           ;; Inside the single flight this attempt's own promise is registered
                           ;; and not yet delivered.
                           (swap! calls conj (if (some-> (get @@(cv 'keyed-catch-ups*) k) realized? not)
                                               :single-flight :own-attempt))
                           (if (compare-and-set! first? true false)
                             (do (deliver entered true) (Thread/sleep 300)
                                 (throw (ex-info "fails once" {:type ::injected})))
                             (apply catch-up args)))
                         #'clojure.core/deliver
                         (fn [p v] (let [r (orig-deliver p v)]
                                     (when (= v :ai.obney.grain.read-model-processor-v2.core/failed) (Thread/sleep 300))
                                     r))}
          (fn []
            (let [w (attempt ctx) _ (deref entered 10000 nil)
                  o (attempt ctx)]
              (is (= ::injected (nth (deref w 20000 nil) 2)))
              (is (= [:ok (truth (bodies 3 40))] (deref o 20000 ::timeout)))
              (is (= [:single-flight :single-flight] @calls)
                  "After one foreign failure the waiter's attempt still goes through the single flight"))))))))

(deftest an-interrupted-catch-up-never-interrupts-its-waiters
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) catch-up @(cv 'keyed-catch-up!)
            first? (atom true) entered (promise)]
        (register!)
        (append! ctx (bodies 3 40))
        (let [waiters
              (with-redefs-fn {(cv 'keyed-catch-up!)
                               (fn [& args]
                                 (when (compare-and-set! first? true false)
                                   (deliver entered true) (Thread/sleep 20000))
                                 (apply catch-up args))}
                (fn []
                  (let [w (future (values ctx model)) _ (deref entered 10000 nil)
                        ws (vec (repeatedly 3 #(attempt ctx)))]
                    (Thread/sleep 300)
                    (future-cancel w)
                    (mapv #(deref % 20000 ::timeout) ws))))]
          (is (= (repeat 3 [:ok (truth (bodies 3 40))]) waiters))
          (is (empty? (in-flight))))))))

(deftest a-hung-catch-up-blocks-its-waiters-only-until-the-namespace-is-fresh
  ;; C3: waiters of a hung in-process catch-up continue once the namespace is
  ;; fresh for their head by other means (here another process's catch-up).
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) catch-up @(cv 'keyed-catch-up!)
            first? (atom true) entered (promise) release (promise)
            identity [tenant model 1] shared [tenant :grain.read-query/shared-identities 1]]
        (register!)
        (append! ctx (bodies 3 40))
        (with-redefs-fn {(cv 'keyed-catch-up-wait-ms) 100
                         (cv 'keyed-catch-up!)
                         (fn [& args]
                           (if (compare-and-set! first? true false)
                             (do (deliver entered true) (deref release 20000 nil) (apply catch-up args))
                             (apply catch-up args)))}
          (fn []
            (let [hung (future (values ctx model))
                  _ (deref entered 10000 nil)
                  waiter (future (values ctx model))]
              (try
                (Thread/sleep 300)
                (is (not (realized? waiter)) "The waiter waits while the namespace is behind")
                (let [prefixes (core/namespace-resolve! (:cache ctx) [identity shared])]
                  (catch-up (assoc ctx :ai.obney.grain.read-model-processor-v2.core.keyed/shared-identity-prefix
                                   (get prefixes shared))
                            model (get (rmp/global-read-model-registry) model) (get prefixes identity)
                            (@(cv 'keyed-head) ctx) identity))
                (is (= (truth (bodies 3 40)) (deref waiter 5000 ::timeout))
                    "Fresh by another catch-up: the waiter continues while the owner still hangs")
                (is (not (realized? hung)))
                (finally (deliver release true)))
              (is (= (truth (bodies 3 40)) (deref hung 20000 ::timeout)))
              (deref waiter 20000 nil))))))))

;; (e) Fast-path guards ----------------------------------------------------------

(deftest the-fast-path-serves-only-registered-namespaces-at-the-head
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant) calls (atom {})
            redefs (merge (counting calls 'namespace-resolve!) (counting calls 'keyed-catch-up!))
            read! (fn [f] (reset! calls {}) [(with-redefs-fn redefs f) @calls])]
        (register!)
        (testing "unresolved identities take the slow path, which registers them"
          (let [[v c] (read! #(values ctx model))]
            (is (= {} v))
            (is (= 1 (get c 'namespace-resolve!)))))
        (testing "a warm read at the head never resolves with allocation or catches up"
          (append! ctx (bodies 3 40))
          (is (= (truth (bodies 3 40)) (values ctx model)))
          (let [[v c] (read! #(values ctx model))]
            (is (= (truth (bodies 3 40)) v))
            (is (= {} c) "Served by the fast path")))
        (testing "behind the head: the slow path catches up before serving"
          (append! ctx (bodies 4 5))
          (let [[v c] (read! #(values ctx model))]
            (is (= (truth (concat (bodies 3 40) (bodies 4 5))) v))
            (is (= 1 (get c 'keyed-catch-up!)))))
        (testing "an unregistered shared-identities namespace is not served by the fast path"
          (let [t (java.util.UUID. 73 9) c2 (ctx-for t)]
            (core/namespace-resolve! (:cache ctx) [[t model 1]])
            ;; The model namespace is registered and fresh (no events): only
            ;; the shared identity is missing.
            (let [[v c] (read! #(values c2 model))]
              (is (= {} v))
              (is (= 1 (get c 'namespace-resolve!)))
              (is (some? (kv/transact (:cache ctx) :read
                           #(@(cv 'namespace-resolve-in) % [[t :grain.read-query/shared-identities 1]])))))))
        (testing "scoped (KRS9) namespaces use the fast path when warm"
          (let [scoped (rmp/scoped-context ctx {:tags #{[:shard (java.util.UUID. 5 5)]}})]
            (is (= {} (values scoped model)))
            (let [[v c] (read! #(values scoped model))]
              (is (= {} v))
              (is (= {} c)))))))))

(deftest a-changed-index-layout-still-fails-on-a-warm-fast-path-read
  ;; Mutation killed: the fast path skipping keyed-check-index-layout!.
  (fixture/keyed-fixture
    (fn []
      (let [ctx (ctx-for tenant)]
        (register!)
        (append! ctx (bodies 3 40))
        (is (seq (values ctx model)))
        (rmp/register-read-model! model merge
          {:events #{:keyed/change} :version 1 :route :inputs
           :indexes {:score2 :score} :searches {:name :name}})
        (let [e (try (values ctx model) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (= :grain.read-model/invalid-cache (:type (ex-data e)))))))))
