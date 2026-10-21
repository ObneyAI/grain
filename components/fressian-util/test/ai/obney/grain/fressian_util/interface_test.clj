(ns ai.obney.grain.fressian-util.interface-test
  (:require [ai.obney.grain.fressian-util.interface :as fu]
            [clojure.data.fressian :as fressian]
            [clojure.test :refer [deftest is testing]])
  (:import [java.time Instant OffsetDateTime ZoneOffset]
           [java.util Arrays]
           [java.lang.reflect InvocationHandler Proxy]
           [org.fressian Writer]
           [org.fressian.handlers ILookup WriteHandler]))

(deftest offset-date-time-round-trips
  (testing "UTC"
    (let [odt (OffsetDateTime/now ZoneOffset/UTC)
          decoded (fu/decode (fu/encode odt))]
      (is (instance? OffsetDateTime decoded))
      (is (= odt decoded))))

  (testing "non-UTC offset preserved"
    (let [odt (OffsetDateTime/now (ZoneOffset/of "-05:00"))
          decoded (fu/decode (fu/encode odt))]
      (is (instance? OffsetDateTime decoded))
      (is (= odt decoded))
      (is (= (.getOffset odt) (.getOffset ^OffsetDateTime decoded))))))

(deftest instant-round-trips
  (let [inst (Instant/now)
        decoded (fu/decode (fu/encode inst))]
    (is (instance? Instant decoded))
    (is (= inst decoded))))

(deftest nested-structure-round-trips
  (let [odt (OffsetDateTime/now ZoneOffset/UTC)
        inst (Instant/now)
        data {:event/timestamp odt
              :body {:started-at inst
                     :items #{1 2 3}
                     :history [{:at inst :v 1} {:at inst :v 2}]}}
        decoded (fu/decode (fu/encode data))]
    (is (= data decoded))
    (is (instance? OffsetDateTime (:event/timestamp decoded)))
    (is (instance? Instant (get-in decoded [:body :started-at])))
    (is (set? (get-in decoded [:body :items])) "sets stay Clojure sets after deep-clojurize")
    (is (vector? (get-in decoded [:body :history])) "vectors stay Clojure vectors after deep-clojurize")))

(deftest stock-clojure-types-still-work
  (testing "keyword, symbol, set, ratio, vector, map survive round-trip alongside the new handlers"
    (let [data {:kw :my/keyword
                :sym 'my.ns/sym
                :set #{:a :b :c}
                :ratio 3/7
                :vec [1 2 3]
                :nested {:inner [{:x 1}]}}
          decoded (fu/decode (fu/encode data))]
      (is (= data decoded))
      (is (set? (:set decoded))))))

(deftest old-payloads-without-java-time-decode-unchanged
  (testing "a payload that contains no java.time values decodes identically under the new decoder"
    (let [data {:data {:count 5 :items #{:a :b}} :watermark (random-uuid)}
          decoded (fu/decode (fu/encode data))]
      (is (= data decoded)))))

(deftest normalization-reuses-unchanged-clojure-collections
  (doseq [value [{} #{} []
                 {:name "Ada" :count 3 :optional nil}
                 (into {} (map (fn [i] [i {:items [i]}]) (range 40)))
                 #{[:a :b] {:id 1}}
                 [{:projects #{[1 2]}} nil]
                 {[:project 1] {:labels #{"A"}}}]]
    (is (identical? value (#'fu/deep-clojurize value)))))

(deftest normalization-replaces-only-changed-branches
  (let [unchanged {:name "Ada" :tags #{"customer"}}
        java-list (java.util.ArrayList. ["project"])
        nested {:items [unchanged java-list] :untouched unchanged}
        result (#'fu/deep-clojurize nested)]
    (is (= {:items [unchanged ["project"]] :untouched unchanged} result))
    (is (not (identical? nested result)))
    (is (not (identical? (:items nested) (:items result))))
    (is (identical? unchanged (:untouched result)))
    (is (identical? unchanged (get-in result [:items 0])))
    (is (vector? (get-in result [:items 1])))
    (is (identical? java-list (get-in nested [:items 1])) "Input remains untouched")
    (is (identical? result (#'fu/deep-clojurize result)))))

(deftest normalization-converts-keys-set-members-and-nested-java-collections
  (let [java-key (java.util.ArrayList. ["project" 1])
        java-map (doto (java.util.HashMap.) (.put java-key (java.util.HashSet. [java-key])))
        native-map {java-key {:members #{java-key}}}
        native-set #{java-key}
        normalized-map (#'fu/deep-clojurize native-map)
        normalized-set (#'fu/deep-clojurize native-set)
        normalized-java (#'fu/deep-clojurize java-map)]
    (is (vector? (first (keys normalized-map))))
    (is (= {:members #{["project" 1]}} (get normalized-map ["project" 1])))
    (is (vector? (first (:members (get normalized-map ["project" 1])))))
    (is (vector? (first normalized-set)))
    (is (contains? normalized-set ["project" 1]))
    (is (map? normalized-java))
    (is (set? (get normalized-java ["project" 1])))
    (is (vector? (first (get normalized-java ["project" 1]))))
    (is (identical? java-key (first (keys native-map))))
    (is (identical? java-key (first native-set))))
  (testing "conversion survives an actual codec round-trip, including hashed lookups"
    (let [value {[:project 1] #{[:customer 2] [:customer 3]}}
          result (fu/decode (fu/encode value))]
      (is (= value result))
      (is (every? vector? (keys result)))
      (is (every? vector? (get result [:project 1])))
      (is (contains? (get result [:project 1]) [:customer 2])))))


;; Keep the pre-optimization handler set as a byte-level compatibility oracle.
;; Scope identities also use encoded bytes, so round-trip equality alone is not
;; sufficient to protect existing projection caches.
(def ^:private legacy-write-handlers
  (-> (merge fressian/clojure-write-handlers
             (var-get #'fu/java-time-write-handlers))
      fressian/associative-lookup
      fressian/inheritance-lookup))

(defn- legacy-encode ^bytes [value]
  (let [^java.nio.ByteBuffer buffer (fressian/write value :handlers legacy-write-handlers)
        result (byte-array (.remaining buffer))]
    (.get buffer result)
    result))

(defrecord CodecRecord [id payload])

(deftest native-map-sized-list-boundaries-preserve-wire-bytes
  (doseq [size [0 1 2 3 4 7 8 9 16 32 127 128 129 256]
          value [(clojure.lang.PersistentArrayMap.
                  (object-array (mapcat (fn [i] [i {:items [i]}]) (range size))))
                 (into clojure.lang.PersistentHashMap/EMPTY
                       (map (fn [i] [i {:items [i]}]) (range size)))
                 (into (sorted-map) (map (fn [i] [i {:items [i]}]) (range size)))]]
    (is (Arrays/equals (legacy-encode value) (fu/encode value))
        (str (class value) " size " size))
    (is (= value (fu/decode (fu/encode value))))))

(deftest native-map-order-and-rich-nesting-preserve-wire-bytes
  (let [instant (Instant/parse "2026-10-14T01:02:03.123456789Z")
        offset (OffsetDateTime/parse "2026-10-14T01:02:03.987654321-05:30")
        nil-key-map (assoc (into {} (map (fn [i] [i {:value i}]) (range 300))) nil {:nil-key true})
        collision-keys (reduce (fn [prefixes _]
                                 (for [prefix prefixes suffix ["Aa" "BB"]]
                                   (str prefix suffix)))
                               [""] (range 6))
        collisions (into clojure.lang.PersistentHashMap/EMPTY
                         (map-indexed (fn [i s] [s i]) collision-keys))
        reverse-map (into (sorted-map-by #(compare %2 %1)) (map (fn [i] [i {:n i}]) (range 30)))
        nested {[:lookup 1] #{[:member 2] [:member 3]}
                :time {:instant instant :offset offset}
                :numbers {:decimal 1.25M :ratio 3/7 :bigint 99999999999999999999N}
                :names {:keyword :some/value :symbol 'some/value :uuid (random-uuid)}
                :record (->CodecRecord 1 {:inside {:timestamp instant}})
                :branches [nil-key-map collisions reverse-map]}]
    (is (apply = (map hash collision-keys)) "Exercise a real hash collision node")
    (doseq [value [nil-key-map collisions reverse-map nested (with-meta nested {:private true})]]
      (is (Arrays/equals (legacy-encode value) (fu/encode value)))
      (is (= value (fu/decode (fu/encode value)))))
    (let [decoded (fu/decode (fu/encode nested))]
      (is (vector? (first (filter vector? (keys decoded)))))
      (is (set? (get decoded [:lookup 1])))
      (is (every? vector? (get decoded [:lookup 1])))
      (is (instance? CodecRecord (:record decoded)))
      (is (= (.getOffset offset) (.getOffset ^OffsetDateTime (get-in decoded [:time :offset])))))))

(deftest custom-map-subclasses-and-records-retain-handler-dispatch
  (let [native-subclass (proxy [clojure.lang.PersistentArrayMap]
                              [(object-array [:a {:x 1} :b 2])]
                          (kvreduce [_ _] (throw (AssertionError. "Native fast path must not handle subclasses"))))
        java-subclass (doto (proxy [java.util.HashMap] []) (.put :a {:nested 1}))
        record-subclass (proxy [clojure.lang.PersistentArrayMap clojure.lang.IRecord]
                              [(object-array [:id 7 :payload {:x 1}])])]
    (is (nil? (.valAt ^ILookup fu/write-handlers (class native-subclass))))
    (is (nil? (.valAt ^ILookup fu/write-handlers (class java-subclass))))
    (is (identical? (.valAt ^ILookup legacy-write-handlers (class record-subclass))
                    (.valAt ^ILookup fu/write-handlers (class record-subclass))))
    (doseq [value [native-subclass java-subclass record-subclass (->CodecRecord 3 {:inside 4})]]
      (is (Arrays/equals (legacy-encode value) (fu/encode value))))
    (is (= native-subclass (fu/decode (fu/encode native-subclass))))
    (is (= java-subclass (fu/decode (fu/encode java-subclass))))))

(deftest published-map-handler-supports-generic-writer
  (let [calls (atom [])
        writer (Proxy/newProxyInstance
                (.getClassLoader Writer) (into-array Class [Writer])
                (reify InvocationHandler
                  (invoke [_ proxy method args]
                    (swap! calls conj [(.getName method) (vec args)])
                    proxy)))
        ^WriteHandler handler (get (.valAt ^ILookup fu/write-handlers clojure.lang.PersistentArrayMap) "map")]
    (.write handler writer (array-map :a 1 :b 2))
    (is (= [["writeTag" ["map" 1]]
            ["writeList" [(java.util.ArrayList. [:a 1 :b 2])]]]
           @calls))))

(deftest native-map-errors-and-detached-output-remain-compatible
  (let [value {:unreadable-map (proxy [clojure.lang.PersistentArrayMap]
                                    [(object-array [:a 1])]
                                  (entrySet [] (throw (UnsupportedOperationException. "Unreadable entries"))))}
        error-class (fn [encoder]
                      (try (encoder value) nil
                           (catch Exception error (class error))))]
    (is (some? (error-class legacy-encode)))
    (is (= (error-class legacy-encode) (error-class fu/encode))))
  (let [value {:items (mapv (fn [i] {:id i :payload [i i]}) (range 100))}
        encoded (fu/encode value)
        snapshot (aclone encoded)]
    (doseq [size [0 1 1000]] (fu/encode (into {} (map (fn [i] [i {:id i}]) (range size)))))
    (is (Arrays/equals encoded snapshot))
    (is (= value (fu/decode encoded)))))


(deftest nested-byte-arrays-preserve-wire-bytes
  (let [value {:bytes (byte-array [0 1 -1 127 -128]) :nested [{:bytes (byte-array [3 4])}]}
        decoded (fu/decode (fu/encode value))]
    (is (Arrays/equals (legacy-encode value) (fu/encode value)))
    (is (Arrays/equals ^bytes (:bytes value) ^bytes (:bytes decoded)))
    (is (Arrays/equals ^bytes (get-in value [:nested 0 :bytes])
                       ^bytes (get-in decoded [:nested 0 :bytes])))))
