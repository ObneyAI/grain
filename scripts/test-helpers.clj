;; Shared helpers for control plane test scripts.
;; Load via: (load-file "scripts/test-helpers.clj")

(require '[nrepl.core :as nrepl])
(require '[clojure.java.shell :refer [sh]])
(require '[clojure.string :as str])

;; -------------------------------- ;;
;; State                            ;;
;; -------------------------------- ;;

(def results (atom {:pass 0 :fail 0 :error 0}))

;; -------------------------------- ;;
;; Shell / Docker helpers           ;;
;; -------------------------------- ;;

(defn docker [& args]
  (let [result (apply sh "docker" args)]
    (when (not= 0 (:exit result))
      (binding [*out* *err*]
        (println "docker" (str/join " " args) "failed:" (:err result))))
    result))

(defn compose [compose-file & args]
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

;; -------------------------------- ;;
;; nREPL helpers                    ;;
;; -------------------------------- ;;

(defn eval-on [port code]
  (with-open [conn (nrepl/connect :port port)]
    (let [client (nrepl/client conn 30000)
          results (nrepl/message client {:op :eval :code code})]
      (doseq [r results]
        (when (:err r) (binding [*out* *err*] (print (:err r)))))
      (some :value results))))

(defn eval-read [port code]
  (let [v (eval-on port code)]
    (when v (read-string v))))

(defn setup-node! [port]
  (loop [attempts 3]
    (let [result (try
                   (eval-on port
                     "(require '[ai.obney.grain.control-plane-test-base.core :as app])
                      (require '[clj-uuid :as uuid])
                      :ok")
                   (catch Exception e
                     (when (pos? attempts)
                       (Thread/sleep 2000))
                     nil))]
      (if (or result (zero? attempts))
        result
        (recur (dec attempts))))))

(defn node-status [port]
  (eval-read port
    "(let [s @app/app]
       {:node-id (str (:node-id (:control-plane s)))
        :active-nodes (count (app/active-nodes s))
        :leases (count (app/leases s))
        :running (count (app/running-processors s))})"))

(defn lease-details [port]
  (eval-read port
    "(let [s @app/app]
       (->> (app/leases s)
            (map (fn [[[tid pname] owner]]
                   {:tenant (str tid) :processor (str pname) :owner (str owner)}))
            vec))"))

(defn create-tenants! [port n]
  (eval-read port
    (format
      "(let [s @app/app
             tids (repeatedly %d uuid/v4)]
         (doseq [t tids] (app/create-tenant! s t))
         (mapv str tids))" n)))

(defn increment! [port tenant-id]
  (eval-on port
    (format "(app/increment! @app/app (java.util.UUID/fromString \"%s\"))" tenant-id)))

(defn bulk-increment! [port tenant-ids-str n-per-tenant]
  (eval-on port
    (format "(let [s @app/app
                   tids %s]
              (doseq [tid-str tids
                      _ (range %d)]
                (app/increment! s (java.util.UUID/fromString tid-str)))
              :done)"
            tenant-ids-str n-per-tenant)))

(defn processed-count [port tenant-id]
  (eval-read port
    (format "(count (app/processed-events @app/app (java.util.UUID/fromString \"%s\")))" tenant-id)))

(defn increment-count [port tenant-id]
  (eval-read port
    (format "(count (filter (fn [e] (= :test/counter-incremented (:event/type e)))
                            (app/all-events @app/app (java.util.UUID/fromString \"%s\"))))" tenant-id)))

(defn node-reachable? [port]
  (try (node-status port) true
       (catch Exception _ false)))

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
(defn metric [msg] (println (str "  ◆ " msg)))

(defn check [desc pred]
  (if pred (pass desc) (fail desc))
  pred)

(defn report-and-exit! []
  (let [{:keys [pass fail error]} @results
        total (+ pass fail error)]
    (println "\n╔══════════════════════════════════════════╗")
    (println (format "║  Results: %d passed, %d failed, %d errors  " pass fail error))
    (println (format "║  Total:   %d checks                        " total))
    (println "╚══════════════════════════════════════════╝"))
  (let [{:keys [fail error]} @results]
    (System/exit (if (and (zero? fail) (zero? error)) 0 1))))
