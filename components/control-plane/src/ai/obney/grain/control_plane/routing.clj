(ns ai.obney.grain.control-plane.routing
  "Tenant-aware routing for ALB sticky load balancing.

   Provides a pure routing decision function and a Pedestal interceptor
   that ensures requests are served by the node holding the tenant's lease."
  (:require [ai.obney.grain.control-plane.core :as cp]
            [com.brunobonacci.mulog :as u]))

(defn route-for-tenant
  "Pure routing decision. Given lease ownership, active nodes, this node's ID,
   and a tenant-id, returns a routing decision map.

   Possible results:
     {:route/decision :local,  :route/reason :owner}       — this node owns it
     {:route/decision :remote, :route/owner id, :route/address addr} — another active node owns it
     {:route/decision :local,  :route/reason :owner-stale}  — owner is dead, serve locally
     {:route/decision :local,  :route/reason :no-owner}     — no lease exists yet"
  [lease-ownership active-nodes this-node-id tenant-id]
  (let [owner (get lease-ownership tenant-id)]
    (cond
      (= owner this-node-id)
      {:route/decision :local
       :route/reason   :owner}

      (and owner (contains? active-nodes owner))
      {:route/decision :remote
       :route/owner    owner
       :route/address  (get-in active-nodes [owner :metadata :address])}

      (and owner (not (contains? active-nodes owner)))
      {:route/decision :local
       :route/reason   :owner-stale}

      :else
      {:route/decision :local
       :route/reason   :no-owner})))

(defn tenant-routing-interceptor
  "Pedestal interceptor factory for tenant-aware routing.

   On :enter, extracts tenant-id from the Pedestal context using extract-tenant-id.
   If this node owns the tenant's lease, the request continues through the chain.
   If another active node owns it, short-circuits with a retry signal (503).
   If no tenant-id is extractable (healthchecks, static assets), passes through.

   On :leave, sets a sticky cookie when the request was served as the true owner,
   so ALB locks the client to this node for subsequent requests. No cookie is set
   during graceful degradation (no-owner, owner-stale) so that future requests
   can find the real owner.

   Options:
     :extract-tenant-id     - (fn [pedestal-ctx] -> tenant-id or nil). Required.
     :this-node-id          - UUID of this node. Required.
     :ctx                   - context map for projecting read models. Required.
     :staleness-threshold-ms - passed to project-active-nodes (default 15000)
     :retry-status          - HTTP status for retry signal (default 503)
     :retry-after-secs      - Retry-After header value in seconds (default 1)
     :cookie-name           - sticky cookie name (default \"GRAIN_TENANT_STICKY\")
     :cookie-max-age        - cookie max-age in seconds (default 3600)"
  [{:keys [extract-tenant-id this-node-id ctx
           staleness-threshold-ms retry-status retry-after-secs
           cookie-name cookie-max-age]
    :or {staleness-threshold-ms 15000
         retry-status 503
         retry-after-secs 1
         cookie-name "GRAIN_TENANT_STICKY"
         cookie-max-age 3600}}]
  {:name ::tenant-routing
   :enter
   (fn [pedestal-context]
     (let [tenant-id (extract-tenant-id pedestal-context)]
       (if (nil? tenant-id)
         pedestal-context
         (let [lease-ownership (cp/project-lease-ownership ctx)
               active-nodes   (cp/project-active-nodes ctx staleness-threshold-ms)
               decision       (route-for-tenant lease-ownership active-nodes
                                                this-node-id tenant-id)]
           (if (= :local (:route/decision decision))
             (do
               (case (:route/reason decision)
                 :owner       (u/log :metric/metric :metric/name "RoutingLocal" :metric/value 1 :metric/resolution :high)
                 (:no-owner
                  :owner-stale) (u/log :metric/metric :metric/name "RoutingDegradation" :metric/value 1 :metric/resolution :high)
                 nil)
               (assoc pedestal-context
                      ::routing-decision decision
                      ::tenant-id tenant-id))
             (do
               (u/log :metric/metric :metric/name "RoutingRemote" :metric/value 1 :metric/resolution :high)
               (assoc pedestal-context
                      :response
                      {:status retry-status
                       :headers (cond-> {"Retry-After" (str retry-after-secs)
                                         "Content-Type" "application/json"}
                                  (:route/address decision)
                                  (assoc "X-Grain-Owner" (:route/address decision)))
                       :body "{\"error\":\"wrong-node\",\"retry\":true}"})))))))

   :leave
   (fn [pedestal-context]
     (if-let [tenant-id (::tenant-id pedestal-context)]
       (let [decision (::routing-decision pedestal-context)]
         (if (= :owner (:route/reason decision))
           (update-in pedestal-context [:response :headers]
                      assoc "Set-Cookie"
                      (str cookie-name "=" this-node-id
                           "; Path=/; Max-Age=" cookie-max-age
                           "; HttpOnly; SameSite=Lax"))
           pedestal-context))
       pedestal-context))})
