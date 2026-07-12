#!/usr/bin/env bb
;; genome.bb — deterministically build the grain component genome.
;;   repo facts (this script) + frozen taste (genome-taste.edn) → genome-template.html → grain-genome.html
;; Usage:  bb genome.bb            (repo defaults to ../.. of a grain checkout, or $GRAIN_REPO)
;;         GRAIN_REPO=/path/to/grain bb genome.bb
(ns genome
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json]))

(def here (-> *file* io/file .getCanonicalFile .getParentFile))
;; repo = two levels up from scripts/genome/, or $GRAIN_REPO
(def repo (io/file (or (System/getenv "GRAIN_REPO") (.. here getParentFile getParentFile))))
(def components-dir (io/file repo "components"))
(def taste (edn/read-string (slurp (io/file here "genome-taste.edn"))))
(def template (slurp (io/file here "genome-template.html")))

;; ---------- deterministic facts from the repo ----------
(defn subdirs [d] (->> (.listFiles d) (filter #(.isDirectory %)) (map #(.getName %)) sort vec))
(def comps (subdirs components-dir))
(def compset (set comps))

(defn src-files [c]
  (let [d (io/file components-dir c "src")]
    (when (.isDirectory d)
      (->> (file-seq d) (filter #(and (.isFile %) (re-find #"\.clj[cs]?$" (.getName %))))))))
(defn src-text [c] (apply str (map slurp (or (src-files c) []))))
(defn loc [c] (reduce + 0 (map #(count (str/split-lines (slurp %))) (or (src-files c) []))))

(def req-re  #"\[ai\.obney\.grain\.([a-z0-9-]+)\.")           ; a require-vector edge
(def mac-re  #"\(defmacro\s+([a-zA-Z0-9!*<>?=/.+-]+)")
(def prot-re #"\(defprotocol\s+([A-Za-z0-9!*<>?=/.+-]+)")

(defn facts [c]
  (let [t (src-text c)]
    {:loc        (loc c)
     :deps       (->> (re-seq req-re t) (map second) (filter #(and (compset %) (not= % c))) distinct sort vec)
     :macros     (->> (re-seq mac-re t) (map second) distinct sort vec)
     :protocols  (->> (re-seq prot-re t) (map second) distinct sort vec)
     :deprecated (boolean (re-find #"(?i)DEPRECATED" t))}))

(def F (into {} (map (juxt identity facts)) comps))

;; who implements a protocol they don't own (a swappable backend)
(def proto-owner (into {} (for [c comps p (:protocols (F c))] [p c])))
(defn implements [c]
  (let [t (src-text c)]
    (->> proto-owner
         (filter (fn [[p owner]]
                   (and (not= owner c)
                        (re-find (re-pattern (str "(?s)(reify|extend-type|extend-protocol|defrecord|deftype)[\\s\\S]{0,400}\\b" p "\\b")) t))))
         (map first) sort vec)))
(def IMPL (into {} (for [c comps] [c (implements c)])))

(def current (->> comps (remove #(:deprecated (F %))) vec))
(def current-set (set current))
(def deprecated (->> comps (filter #(:deprecated (F %))) vec))
(def macro-def (into {} (for [c current m (:macros (F c))] [m c])))

;; ---------- merge facts over frozen taste ----------
(def taste-nodes (:nodes taste))

(defn node-out [id]
  (let [tn (taste-nodes id) f (F id)]
    (merge {:id id :loc (:loc f) :deps (:deps f) :macros (:macros f)
            :proto (first (:protocols f)) :impl (first (IMPL id))}
           (select-keys tn [:col :row :cluster :role :desc]))))

(def nodes-out (->> current (map node-out) (sort-by (juxt :row :col)) vec))

(def macro-groups-out
  (vec (for [g (:groups (:macro-catalog taste))]
         {:g (:g g)
          :items (vec (for [it (:macros g)] {:m (:m it) :comp (macro-def (:m it)) :sig (:sig it) :p (:p it)}))})))

(def protocols-out
  (vec (for [c (sort current) p (:protocols (F c))]
         {:name p :comp c
          :impls (vec (sort (for [x current :when (some #{p} (IMPL x))] x)))
          :p (get (:protocol-notes taste) p "")})))

(def clusters-out
  (reduce (fn [m {:keys [key name var desc]}] (assoc m key {:name name :v var :desc desc}))
          (array-map) (:clusters taste)))

(def edge-count (reduce + (for [c current] (count (filter current-set (:deps (F c)))))))

(defn tokenize [s]
  (-> s (str/replace "{{components}}" (str (count current)))
        (str/replace "{{deprecated}}" (str (count deprecated)))))

(def genome
  {:clusters clusters-out
   :cols (:cols taste) :rows (:rows taste) :zones (:zones taste)
   :copy (-> (:copy taste) (update :title tokenize) (update :lede tokenize))
   :nodes nodes-out
   :macroGroups macro-groups-out
   :protocols protocols-out})

;; ---------- emit (inline data + vendored Scittle + the CLJS app) ----------
(def scittle-js (slurp (io/file here "vendor" "scittle.js")))
(def app-cljs   (slurp (io/file here "genome-app.cljs")))
(def out-file (io/file here "grain-genome.html"))
(spit out-file
      (-> template
          (str/replace "__GENOME_DATA__" (json/generate-string genome))
          (str/replace "__SCITTLE_JS__"  scittle-js)
          (str/replace "__GENOME_CLJS__" app-cljs)))

;; ---------- coverage report (drives the taste-making skill) ----------
(def missing-taste (vec (remove taste-nodes current)))
(def stale-taste   (vec (remove current-set (keys taste-nodes))))
(def cat-macros    (set (for [g (:groups (:macro-catalog taste)) it (:macros g)] (:m it))))
(def all-macros    (distinct (mapcat #(:macros (F %)) current)))
(def uncatalogued  (vec (remove cat-macros all-macros)))

(binding [*out* *err*]
  (println (format "genome: %d current / %d total components · %d edges · %d macros · %d protocols"
                   (count current) (count comps) edge-count
                   (count all-macros) (count protocols-out)))
  (when (seq missing-taste) (println "  ⚠ no taste (placement/desc) for:" (str/join ", " missing-taste) "→ run the grain-genome skill"))
  (when (seq stale-taste)   (println "  ⚠ taste for gone/deprecated component:" (str/join ", " stale-taste)))
  (when (seq uncatalogued)  (println "  ⚠ macros missing from catalog:" (str/join ", " uncatalogued))))
(println "wrote" (.getName out-file))
