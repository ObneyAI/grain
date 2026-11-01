;; Frozen pre-optimization selectors for retention-profile.clj. Load only after
;; the PostgreSQL backend is required. Commit, locking and receipt logic are
;; shared with the current backend; these are the two functions being compared.
(in-ns 'ai.obney.grain.event-store-postgres-v3.core)

(defn- keyed-eligible-metadata
  [conn tenant-id event-type cutoff-time limit tag-names]
  (set-tenant! conn tenant-id)
  (let [tag-names (mapv key-fn (sort-by key-fn tag-names))
        exact-counts (mapv (fn [_]
                             "(SELECT COUNT(*) FROM unnest(e.tags) tag
                               WHERE split_part(tag, ':', 1) = ?) = 1")
                           tag-names)
        sql (str "WITH keyed AS ("
                 "SELECT e.id, e.time, "
                 "ROW_NUMBER() OVER (PARTITION BY "
                 "ARRAY(SELECT tag FROM unnest(e.tags) tag "
                 "WHERE split_part(tag, ':', 1) = ANY(?::text[]) ORDER BY tag) "
                 "ORDER BY e.id DESC) AS retention_rank "
                 "FROM grain.events e "
                 "WHERE e.tenant_id = ? AND e.type = ? AND "
                 (string/join " AND " exact-counts)
                 ") SELECT id, time FROM keyed "
                 "WHERE time < ? AND retention_rank > 1 "
                 "ORDER BY id LIMIT ?")
        params (into [sql
                      (into-array String tag-names)
                      tenant-id
                      (str event-type)]
                     (concat tag-names [cutoff-time limit]))]
    (mapv metadata->event
          (jdbc/execute! conn params
                         {:builder-fn rs/as-unqualified-lower-maps}))))

(defn- removable-transaction-ids [conn tenant-id eligible]
  (compaction/removable-transaction-ids
   (set (map :event/id eligible))
   (fn [event-id]
     (some-> (jdbc/execute-one!
              conn
              ["SELECT id, time, type, tags, data FROM grain.events
                WHERE tenant_id = ? AND type = ? AND id > ?
                ORDER BY id LIMIT 1"
               tenant-id (str :grain/tx) event-id]
              {:builder-fn rs/as-unqualified-lower-maps})
             transform-row))
   (fn [member-ids]
     ;; Chunk large append batches to stay within SQL parameter limits, and
     ;; stop at the first surviving member. No member payload is decoded.
     (some (fn [ids]
             (jdbc/execute-one!
              conn
              (into [(str "SELECT id FROM grain.events WHERE tenant_id = ? AND id IN ("
                          (placeholders (count ids)) ") LIMIT 1")
                     tenant-id]
                    ids)
              {:builder-fn rs/as-unqualified-lower-maps}))
           (partition-all 500 member-ids)))))
