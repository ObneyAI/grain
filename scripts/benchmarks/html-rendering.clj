;; Standalone immutable fixtures: alternate both implementations in one JVM.
;; CLJ_CACHE=/tmp/detail-warm-clj-cache clojure -M:dev scripts/benchmarks/html-rendering.clj /tmp/detail-warm-html-benchmark.edn
(require '[ai.obney.grain.datastar.core :as datastar]
         '[hiccup2.core :as hiccup])
(defn original-html [value] (str (hiccup/html value)))
(def ^com.sun.management.ThreadMXBean bean
  (java.lang.management.ManagementFactory/getThreadMXBean))
(def tid (.getId (Thread/currentThread)))
(defn median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn sha [^String value]
  (.formatHex (java.util.HexFormat/of)
              (.digest (java.security.MessageDigest/getInstance "SHA-256")
                       (.getBytes value java.nio.charset.StandardCharsets/UTF_8))))
(defn rows [n]
  [:table {:class "table"}
   [:tbody (doall (map (fn [i]
                  [:tr {:data-index i}
                   [:td {:class "text-nowrap"} (str "Payment " i " & <tag>")]
                   [:td "\u00e9 \u20ac \ud83d\ude00"]
                   [:td (str "$" (* i 10) ".00")]
                   [:td [:button {:data-on:click "alert('value')"} "Details"]]])
                (range n)))]])
(def fixtures
  [{:name :rows-233 :tree (rows 233)}
   {:name :rows-233-depth-20 :tree (nth (iterate #(vector :div {:class "wrapper"} %) (rows 233)) 20)}
   {:name :rows-1000 :tree (rows 1000)}
   {:name :depth-100 :tree (nth (iterate #(vector :section %) [:p "Deep & child"]) 100)}])
(defn sample [f value]
  (let [bytes (.getThreadAllocatedBytes bean tid) started (System/nanoTime)
        output (f value) elapsed (- (System/nanoTime) started)
        allocated (- (.getThreadAllocatedBytes bean tid) bytes)]
    {:ms (/ elapsed 1e6) :allocated-bytes allocated :html-chars (count output)
     :sha256 (sha output)}))
(def results
  (mapv (fn [{:keys [name tree]}]
          (assert (= (original-html tree) (datastar/render-html tree)))
          (dotimes [_ 20] (original-html tree) (datastar/render-html tree))
          (let [samples (mapv (fn [i]
                                (into {} (map (fn [[label f]] [label (sample f tree)]))
                                      (if (even? i)
                                        [[:before original-html] [:after datastar/render-html]]
                                        [[:after datastar/render-html] [:before original-html]])))
                              (range 20))
                all-values (mapcat vals samples)]
            (assert (= 1 (count (set (map (juxt :html-chars :sha256) all-values)))))
            {:name name :samples samples
             :before {:median-ms (median (map #(get-in % [:before :ms]) samples))
                      :median-allocated-bytes (median (map #(get-in % [:before :allocated-bytes]) samples))}
             :after {:median-ms (median (map #(get-in % [:after :ms]) samples))
                     :median-allocated-bytes (median (map #(get-in % [:after :allocated-bytes]) samples))}}))
        fixtures))
(spit (or (first *command-line-args*) "/tmp/detail-warm-html-benchmark.edn")
      (pr-str {:method :same-jvm-alternating :warmups 20 :repetitions 20 :results results}))
(prn (mapv #(dissoc % :samples) results))
(shutdown-agents)
