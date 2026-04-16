#!/usr/bin/env clojure -M:dev

;; End-to-end routing test through HAProxy.
;; Proves ALB-style sticky load balancing with tenant-aware routing.
;;
;; Usage: clojure -M:dev scripts/routing-test.clj

(require '[nrepl.core :as nrepl])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.string :as str])
(require '[clj-http.client :as http])
(require '[clojure.data.json :as json])

(def compose-file "docker-compose.routing-test.yml")
(def all-ports [7890 7891 7892])
(def primary-port (first all-ports))
(def haproxy-url "http://localhost:8080")
(def n-nodes 3)
(def n-tenants 6)

;; -------------------------------- ;;
;; State                            ;;
;; -------------------------------- ;;

(def results (atom {:pass 0 :fail 0 :error 0}))
(def tenant-ids (atom []))

;; -------------------------------- ;;
;; Shell / Docker helpers           ;;
;; -------------------------------- ;;

(defn docker [& args]
  (let [result (apply sh "docker" args)]
    (when (not= 0 (:exit result))
      (binding [*out* *err*]
        (println "docker" (str/join " " args) "failed:" (:err result))))
    result))

(defn compose [& args]
  (apply docker "compose" "-f" compose-file args))

(defn wait-for-port [port timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if (> (- (System/currentTimeMillis) start) timeout-ms)
        false
        (if (try
              (with-open [_ (java.net.Socket. "localhost" port)]
                true)
              (catch Exception _ false))
          true
          (do (Thread/sleep 500) (recur)))))))

(defn wait-for-http [url timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if (> (- (System/currentTimeMillis) start) timeout-ms)
        false
        (if (try
              (let [resp (http/get url {:throw-exceptions false :socket-timeout 2000 :connection-timeout 2000})]
                (= 200 (:status resp)))
              (catch Exception _ false))
          true
          (do (Thread/sleep 1000) (recur)))))))

;; -------------------------------- ;;
;; nREPL helpers                    ;;
;; -------------------------------- ;;

(defn eval-on [port code]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 15000)
          results (nrepl/message client {:op :eval :code code})]
      (doseq [r results]
        (when (:err r) (binding [*out* *err*] (print (:err r)))))
      (some :value results))))

(defn eval-read [port code]
  (let [v (eval-on port code)]
    (when v (read-string v))))

(defn setup-node! [port]
  ;; nREPL is opened inside (start) before (reset! app (start)) completes, so
  ;; simply requiring the namespace is not proof that the app is ready.
  ;; Each iteration: require namespaces AND check that @app is populated
  ;; with a :control-plane having a :node-id. Self-contained to tolerate
  ;; transient failures during app startup.
  (let [deadline (+ (System/currentTimeMillis) 180000)]
    (loop []
      (let [ready? (try
                     (eval-read port
                       "(do (require '[ai.obney.grain.control-plane-test-base.core :as app])
                            (require '[clj-uuid :as uuid])
                            (let [s @app/app]
                              (boolean (and s (:control-plane s) (:node-id (:control-plane s))))))")
                     (catch Exception _ false))]
        (cond
          ready? :ok
          (> (System/currentTimeMillis) deadline)
          (binding [*out* *err*]
            (println (str "Node on port " port " did not become ready within 180s")))
          :else (do (Thread/sleep 1000) (recur)))))))

(defn node-status [port]
  (eval-read port
    "(let [s @app/app]
       {:node-id (str (:node-id (:control-plane s)))
        :active-nodes (count (app/active-nodes s))
        :leases (count (app/leases s))
        :running (count (app/running-processors s))})"))

(defn create-tenants! [port n]
  (eval-read port
    (format
      "(let [s @app/app
             tids (repeatedly %d uuid/v4)]
         (doseq [t tids] (app/create-tenant! s t))
         (mapv str tids))" n)))

(defn lease-details [port]
  (eval-read port
    "(let [s @app/app]
       (->> (app/leases s)
            (map (fn [[tid owner]]
                   {:tenant (str tid) :owner (str owner)}))
            vec))"))

;; -------------------------------- ;;
;; Reporting                        ;;
;; -------------------------------- ;;

(defn pass [msg]
  (swap! results update :pass inc)
  (println (str "  ✓ " msg)))

(defn fail [msg]
  (swap! results update :fail inc)
  (println (str "  ✗ FAIL: " msg)))

(defn info [msg] (println (str "  → " msg)))
(defn header [msg] (println (str "\n== " msg " ==")))

(defn check [desc pred]
  (if pred (pass desc) (fail desc))
  pred)

;; -------------------------------- ;;
;; HTTP helpers                     ;;
;; -------------------------------- ;;

(defn haproxy-get
  "GET through HAProxy with optional tenant-id header.
   Returns {:status, :body (parsed JSON), :headers, :cookies}."
  ([path] (haproxy-get path nil nil))
  ([path tenant-id] (haproxy-get path tenant-id nil))
  ([path tenant-id cookie-store]
   (let [opts (cond-> {:throw-exceptions false
                       :socket-timeout 5000
                       :connection-timeout 5000
                       :as :string}
                tenant-id (assoc :headers {"X-Tenant-Id" (str tenant-id)})
                cookie-store (assoc :cookie-store cookie-store))
         resp (http/get (str haproxy-url path) opts)]
     {:status (:status resp)
      :body (when (and (:body resp) (str/starts-with? (get-in resp [:headers "Content-Type"] "") "application/json"))
              (json/read-str (:body resp) :key-fn keyword))
      :headers (:headers resp)
      :cookies (:cookies resp)})))

(defn make-cookie-store []
  (org.apache.http.impl.client.BasicCookieStore.))

;; -------------------------------- ;;
;; Docker lifecycle                 ;;
;; -------------------------------- ;;

(defn start-cluster! []
  (info (str "Starting " n-nodes "-node cluster with HAProxy..."))
  (compose "down" "-v")
  (compose "build")
  (compose "up" "-d" "postgres")
  (Thread/sleep 5000)
  (compose "up" "-d" "node-a")
  (wait-for-port primary-port 60000)
  (Thread/sleep 3000)
  (compose "up" "-d")
  (info "Waiting for all nodes...")
  (let [ok (every? true? (map #(wait-for-port % 90000) all-ports))]
    (when-not ok (fail "Not all nodes started"))
    (Thread/sleep 12000)
    (doseq [p all-ports] (setup-node! p))
    ;; Wait for HTTP + HAProxy
    (info "Waiting for HAProxy health...")
    (let [haproxy-ok (wait-for-http (str haproxy-url "/health") 30000)]
      (when-not haproxy-ok (fail "HAProxy not responding"))
      (and ok haproxy-ok))))

(defn stop-cluster! []
  (info "Stopping cluster...")
  (compose "down" "-v"))

;; -------------------------------- ;;
;; Scenarios                        ;;
;; -------------------------------- ;;

(defn scenario-routing-convergence []
  (header "Scenario: Routing convergence through HAProxy")
  (info (str "Creating " n-tenants " tenants..."))
  (let [tids (create-tenants! primary-port n-tenants)]
    (reset! tenant-ids tids)
    (info "Waiting for assignment (8s)...")
    (Thread/sleep 8000)

    ;; Get lease ownership to know expected routing
    (let [leases (lease-details primary-port)
          owner-by-tenant (into {} (map (fn [{:keys [tenant owner]}] [tenant owner]) leases))]
      (info (str "Leases: " (count leases)))

      ;; For each tenant, make a request through HAProxy
      ;; HAProxy retries on 503, so we should get 200 from the correct node
      (doseq [tid tids]
        (let [resp (haproxy-get "/api/whoami" tid)]
          (check (str "Tenant " (subs tid 0 8) ": got 200 (status=" (:status resp) ")")
                 (= 200 (:status resp)))
          (when (= 200 (:status resp))
            (let [resp-node (get-in resp [:body :node-id])
                  expected-owner (get owner-by-tenant tid)]
              (check (str "Tenant " (subs tid 0 8) ": served by lease owner")
                     (= expected-owner resp-node)))))))))

(defn scenario-sticky-sessions []
  (header "Scenario: Sticky session persistence")
  (let [tid (first @tenant-ids)
        cookie-store (make-cookie-store)]
    ;; First request establishes stickiness
    (let [resp1 (haproxy-get "/api/whoami" tid cookie-store)]
      (check "First request succeeds" (= 200 (:status resp1)))
      (let [first-node (get-in resp1 [:body :node-id])]
        (info (str "First request served by " first-node))
        ;; 10 more requests — all should go to the same node
        (let [subsequent-nodes
              (doall
                (for [_ (range 10)]
                  (let [resp (haproxy-get "/api/whoami" tid cookie-store)]
                    (get-in resp [:body :node-id]))))]
          (check "All 10 subsequent requests go to same node"
                 (every? #(= first-node %) subsequent-nodes)))))))

(defn scenario-failover-rerouting []
  (header "Scenario: Failover rerouting through HAProxy")
  ;; Find a tenant owned by node-c
  (let [leases (lease-details primary-port)
        node-c-id (:node-id (node-status 7892))
        victim-tenant (->> leases
                           (filter #(= node-c-id (:owner %)))
                           first
                           :tenant)]
    (if victim-tenant
      (do
        (info (str "Tenant " (subs victim-tenant 0 8) " owned by node-c"))
        ;; Verify it's served by node-c through HAProxy
        (let [resp-before (haproxy-get "/api/whoami" victim-tenant)]
          (check "Before failover: served by node-c"
                 (= node-c-id (get-in resp-before [:body :node-id]))))
        ;; Kill node-c
        (info "Killing node-c...")
        (docker "kill" "grain-node-c-1")
        (info "Waiting for reassignment (12s)...")
        (Thread/sleep 12000)
        ;; Request through HAProxy — should find new owner
        (let [resp-after (haproxy-get "/api/whoami" victim-tenant)]
          (check "After failover: request succeeds" (= 200 (:status resp-after)))
          (when (= 200 (:status resp-after))
            (let [new-node (get-in resp-after [:body :node-id])]
              (info (str "Now served by " new-node))
              (check "Served by a different node" (not= node-c-id new-node)))))
        ;; Restart node-c for subsequent tests
        (info "Restarting node-c...")
        (compose "restart" "node-c")
        (wait-for-port 7892 60000)
        (Thread/sleep 8000)
        (setup-node! 7892))
      (fail "No tenants owned by node-c"))))

(defn scenario-graceful-degradation []
  (header "Scenario: Graceful degradation for new tenant")
  ;; Create a brand new tenant — no lease exists yet
  (let [new-tid (eval-read primary-port "(str (uuid/v4))")]
    (info (str "New tenant (no lease): " (subs new-tid 0 8)))
    ;; Request through HAProxy — should succeed (graceful degradation)
    ;; The tenant has no lease, so whatever node gets it will serve locally
    (let [resp (haproxy-get "/api/whoami" new-tid)]
      (check "New tenant request succeeds (graceful degradation)"
             (= 200 (:status resp)))
      (when (= 200 (:status resp))
        (info (str "Served by " (get-in resp [:body :node-id]) " (any node is fine)"))))))

(defn scenario-health-no-tenant []
  (header "Scenario: Health endpoint (no tenant-id)")
  (let [resp (haproxy-get "/health")]
    (check "Health endpoint returns 200" (= 200 (:status resp)))
    (check "Health body is ok" (= "ok" (get-in resp [:body :status])))))

;; -------------------------------- ;;
;; Main runner                      ;;

(defn run-all []
  (println "\n╔══════════════════════════════════════════╗")
  (println "║  Grain Routing Live Test (HAProxy)       ║")
  (println (str "║  " n-nodes " nodes, " n-tenants " tenants, 1 HAProxy             ║"))
  (println "╚══════════════════════════════════════════╝")

  (when-not (start-cluster!)
    (println "\nFailed to start cluster. Aborting.")
    (stop-cluster!)
    (System/exit 1))

  (try
    (scenario-health-no-tenant)
    (scenario-routing-convergence)
    (scenario-sticky-sessions)
    (scenario-failover-rerouting)
    (scenario-graceful-degradation)

    (catch Throwable t
      (swap! results update :error inc)
      (println (str "\n  !! Unexpected error: " (.getMessage t)))
      (.printStackTrace t)))

  (let [{:keys [pass fail error]} @results
        total (+ pass fail error)]
    (println "\n╔══════════════════════════════════════════╗")
    (println (format "║  Results: %d passed, %d failed, %d errors  " pass fail error))
    (println (format "║  Total:   %d checks                        " total))
    (println "╚══════════════════════════════════════════╝"))

  (stop-cluster!)
  (let [{:keys [fail error]} @results]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))

;; Entry point
(run-all)
