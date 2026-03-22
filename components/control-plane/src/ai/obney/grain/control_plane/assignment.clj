(ns ai.obney.grain.control-plane.assignment
  "Pure functions for coordinator election and work assignment.
   No IO, no event store — just data in, data out.")

(defn coordinator
  "Returns the node-id of the coordinator: the active node with the
   lexicographically smallest UUID (string comparison).
   Returns nil if active-nodes is empty."
  [active-nodes]
  (when (seq active-nodes)
    (first (sort-by str (keys active-nodes)))))

(defn- round-robin-assign
  "Distributes pairs evenly across nodes in a deterministic order."
  [node-ids pairs]
  (let [sorted-nodes (vec (sort-by str node-ids))
        sorted-pairs (vec (sort-by str pairs))
        n (count sorted-nodes)]
    (reduce
     (fn [acc [i pair]]
       (let [node (nth sorted-nodes (mod i n))]
         (update acc node (fnil conj #{}) pair)))
     {}
     (map-indexed vector sorted-pairs))))

(defn assign
  "Pure function. Given active nodes, tenant-ids, current leases,
   and a strategy, returns {node-id -> #{tenant-id ...}}.
   Every tenant is assigned to exactly one node."
  [active-nodes tenant-ids current-leases strategy]
  (if (or (empty? active-nodes) (empty? tenant-ids))
    {}
    (let [node-ids (keys active-nodes)]
      (case strategy
        :round-robin (round-robin-assign node-ids tenant-ids)
        (if (fn? strategy)
          (strategy active-nodes tenant-ids current-leases)
          (round-robin-assign node-ids tenant-ids))))))
