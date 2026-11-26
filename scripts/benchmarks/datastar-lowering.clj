(require '[ai.obney.grain.datastar.ui :as ui]
         '[ai.obney.grain.datastar.core :as ds])
(import '[java.lang.management ManagementFactory]
        '[java.security MessageDigest]
        '[java.util HexFormat])

;; Run in a fresh process before/after the patch. Timing and allocation runs
;; are uninstrumented; public entry counts are collected separately.
(def opts {:query-registry {:bench/detail {:datastar/path "/bench/detail"}}})
(defn fixture [rows checked?]
  [:div {:class "space-y-4"}
   (doall (map (fn [i]
           [:article {:class "rounded-lg border p-3"}
            [:h3 {:class "font-semibold"} (str "Schedule " i)]
            (doall (map (fn [j]
                    [:div {:class "flex justify-between"}
                     [:span {:class "text-neutral"} (str "Field " j)]
                     [:span {:class "font-medium"} (str "$" (+ i j) ".00")]])
                  (range 6)))
            (if checked?
              (ui/with-signals [open {:name "row-open" :init false}]
                [:div {:bind/show open}
                 [:a {:href (ui/href :bench/detail {:query-params {:id i}})} "Details"]
                 [:button {:on/click {:effect (ui/refresh :bench/detail {:id i})}}
                  "Refresh"]])
              [:a {:href (str "/bench/detail?id=" i)} "Details"])])
         (range rows)))])
(def ^com.sun.management.ThreadMXBean mx (ManagementFactory/getThreadMXBean))
(def thread-id (.getId (Thread/currentThread)))
(defn allocated [] (.getThreadAllocatedBytes mx thread-id))
(defn median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn sample [f n]
  (let [start-bytes (allocated) started (System/nanoTime)]
    (dotimes [_ n] (f))
    {:us (/ (- (System/nanoTime) started) n 1000.0)
     :allocated-bytes (/ (- (allocated) start-bytes) n)}))
(defn sha [s]
  (.formatHex (HexFormat/of)
              (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String s "UTF-8"))))
(defn extra-fixture [rows kind]
  (case kind
    :literal-heavy (apply list (map #(str "Literal " %) (range rows)))
    :deep (nth (iterate #(vector :div {:class "nested"} %)
                        [:a {:href (ui/href :bench/detail {:query-params {:id 7}})} "Details"])
               rows)))
(defn case-result [rows checked? kind timing?]
  (let [source (if kind (extra-fixture rows kind) (fixture rows checked?)) ir (ui/ir source)
        output (ui/lower-ir ir opts) html (ds/render-html output)
        calls (atom 0) original ui/lower-ir
        _ (with-redefs [ui/lower-ir (fn [& args] (swap! calls inc) (apply original args))]
            (assert (= output (ui/lower-ir ir opts))))
        _ (when timing? (dotimes [_ 40] (ui/lower-ir ir opts) (ui/hiccup source opts)))
        lowering (when timing? (repeatedly 7 #(sample (fn [] (ui/lower-ir ir opts)) 30)))
        compile (when timing? (repeatedly 7 #(sample (fn [] (ui/hiccup source opts)) 30)))]
    (cond-> {:rows rows :checked? checked? :kind kind :html-chars (count html) :sha256 (sha html)
     :ir-sha256 (sha (pr-str ir)) :hiccup-sha256 (sha (pr-str output))
     :ir-nodes (count (filter #(and (map? %) (#{:element :fragment :literal} (:op %)))
                              (tree-seq coll? seq ir)))
     :public-lower-calls @calls}
     timing? (assoc
     :lower {:median-us (median (map :us lowering))
             :median-allocated-bytes (median (map :allocated-bytes lowering))
             :samples (vec lowering)}
     :hiccup {:median-us (median (map :us compile))
              :median-allocated-bytes (median (map :allocated-bytes compile))
              :samples (vec compile)}))))
(let [[label out suite] *command-line-args*
      main [[233 false nil] [233 true nil] [1000 true nil]]
      extra [[5000 false :literal-heavy] [120 true :deep]]
      cases (case suite "extra" extra "semantics" (concat main extra) main)
      result {:label label :timing-instrumented? false :samples 7 :iterations 30
              :cases (mapv (fn [[rows checked? kind]] (case-result rows checked? kind (not= suite "semantics"))) cases)}]
  (spit out (pr-str result))
  (prn (update result :cases #(mapv (fn [x] (-> x (update :lower dissoc :samples)
                                               (update :hiccup dissoc :samples))) %))))
