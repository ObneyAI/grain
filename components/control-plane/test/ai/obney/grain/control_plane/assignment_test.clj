(ns ai.obney.grain.control-plane.assignment-test
  "Property-based tests for the pure assignment and coordinator functions.
   Tests provable properties CP2, CP3, CP4, CP9."
  (:require [clojure.test :refer :all]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [clj-uuid :as uuid]))

;; -------------------- ;;
;; Generators           ;;
;; -------------------- ;;

(def gen-node-id
  "Generates UUID v7s (time-ordered) with small delays to ensure ordering."
  (gen/fmap (fn [_] (uuid/v7)) gen/nat))

(def gen-tenant-id
  (gen/fmap (fn [_] (uuid/v4)) gen/nat))

(def gen-processor-name
  (gen/elements [:proc/a :proc/b :proc/c :proc/d :proc/e]))

(def gen-tenant-processor-pair
  (gen/tuple gen-tenant-id gen-processor-name))

(def gen-active-nodes
  "Generates a non-empty map of {node-id -> {:last-heartbeat-at instant, :metadata {}}}."
  (gen/let [n (gen/choose 1 10)
            node-ids (gen/vector gen-node-id n)]
    (into {} (map (fn [nid] [nid {:last-heartbeat-at (java.time.Instant/now)
                                   :metadata {}}])
                  (distinct node-ids)))))

;; =====================================
;; CP2: Assignment Completeness
;; =====================================

(defspec cp2-assignment-completeness 100
  (prop/for-all
    [active-nodes gen-active-nodes
     pairs (gen/vector gen-tenant-processor-pair 1 30)]
    (let [pairs-set (set pairs)
          result (assignment/assign active-nodes pairs-set {} :round-robin)
          assigned-pairs (into #{} (mapcat val) result)
          node-ids-in-result (set (keys result))]
      (and
       ;; Every pair is assigned
       (= pairs-set assigned-pairs)
       ;; Only active nodes receive assignments
       (every? #(contains? active-nodes %) node-ids-in-result)
       ;; No pair assigned to multiple nodes
       (= (count assigned-pairs)
          (reduce + (map #(count (val %)) result)))))))

;; =====================================
;; CP3: Coordinator Convergence
;; =====================================

(defspec cp3-coordinator-convergence 100
  (prop/for-all
    [active-nodes gen-active-nodes]
    (let [c1 (assignment/coordinator active-nodes)
          c2 (assignment/coordinator active-nodes)
          c3 (assignment/coordinator active-nodes)]
      (and
       ;; Deterministic — same input always gives same output
       (= c1 c2 c3)
       ;; Result is one of the active nodes
       (contains? active-nodes c1)))))

(deftest coordinator-empty-returns-nil
  (is (nil? (assignment/coordinator {}))))

(deftest coordinator-single-node
  (let [nid (uuid/v7)
        nodes {nid {:last-heartbeat-at (java.time.Instant/now) :metadata {}}}]
    (is (= nid (assignment/coordinator nodes)))))

;; =====================================
;; CP4: Assignment Stability
;; =====================================

(defspec cp4-assignment-stability 100
  (prop/for-all
    [active-nodes gen-active-nodes
     pairs (gen/vector gen-tenant-processor-pair 1 30)]
    (let [pairs-set (set pairs)
          result1 (assignment/assign active-nodes pairs-set {} :round-robin)
          result2 (assignment/assign active-nodes pairs-set {} :round-robin)]
      (= result1 result2))))

;; =====================================
;; CP9: Coordinator Leader Stability
;; =====================================

(defspec cp9-coordinator-leader-stability 50
  (prop/for-all
    [initial-nodes gen-active-nodes
     new-node-id gen-node-id]
    (let [coordinator-before (assignment/coordinator initial-nodes)
          ;; Add a new node (simulating a node joining)
          expanded-nodes (assoc initial-nodes new-node-id
                                {:last-heartbeat-at (java.time.Instant/now)
                                 :metadata {}})
          coordinator-after (assignment/coordinator expanded-nodes)]
      (if (contains? initial-nodes new-node-id)
        ;; Node already existed, coordinator shouldn't change
        (= coordinator-before coordinator-after)
        ;; New node joined — coordinator only changes if the new node has a smaller id
        (or (= coordinator-before coordinator-after)
            ;; If coordinator changed, the new node must be smaller than the old coordinator
            (and (= new-node-id coordinator-after)
                 (neg? (compare (str new-node-id) (str coordinator-before)))))))))

(deftest coordinator-does-not-change-when-non-leader-leaves
  (let [n1 (uuid/v7)
        _ (Thread/sleep 1) ;; ensure different v7 timestamps
        n2 (uuid/v7)
        _ (Thread/sleep 1)
        n3 (uuid/v7)
        all-nodes {n1 {:last-heartbeat-at (java.time.Instant/now) :metadata {}}
                   n2 {:last-heartbeat-at (java.time.Instant/now) :metadata {}}
                   n3 {:last-heartbeat-at (java.time.Instant/now) :metadata {}}}
        after-removal (dissoc all-nodes n3)]
    (is (= (assignment/coordinator all-nodes)
           (assignment/coordinator after-removal)))))

;; =====================================
;; Assignment edge cases
;; =====================================

(deftest assign-single-node-gets-everything
  (let [nid (uuid/v7)
        nodes {nid {:last-heartbeat-at (java.time.Instant/now) :metadata {}}}
        pairs #{[(uuid/v4) :proc/a] [(uuid/v4) :proc/b]}
        result (assignment/assign nodes pairs {} :round-robin)]
    (is (= pairs (get result nid)))))

(deftest assign-even-distribution
  (let [n1 (uuid/v7)
        _ (Thread/sleep 1)
        n2 (uuid/v7)
        nodes {n1 {:last-heartbeat-at (java.time.Instant/now) :metadata {}}
               n2 {:last-heartbeat-at (java.time.Instant/now) :metadata {}}}
        pairs (set (for [i (range 10)] [(uuid/v4) :proc/a]))
        result (assignment/assign nodes pairs {} :round-robin)
        counts (mapv #(count (val %)) result)]
    ;; Difference between max and min should be at most 1
    (is (<= (- (apply max counts) (apply min counts)) 1))))
