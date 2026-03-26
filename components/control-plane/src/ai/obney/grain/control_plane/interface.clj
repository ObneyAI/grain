(ns ai.obney.grain.control-plane.interface
  (:require [ai.obney.grain.control-plane.events :as events]
            [ai.obney.grain.control-plane.assignment :as assignment]
            [ai.obney.grain.control-plane.core :as core]
            [ai.obney.grain.control-plane.routing :as routing]
            [ai.obney.grain.control-plane.read-models]
            [ai.obney.grain.control-plane.schemas]))

(def control-plane-tenant-id events/control-plane-tenant-id)

(def coordinator assignment/coordinator)
(def assign assignment/assign)

(def ->heartbeat events/->heartbeat)
(def ->node-departed events/->node-departed)
(def ->lease-acquired events/->lease-acquired)
(def ->lease-released events/->lease-released)

(defn start [config]
  (core/start config))

(defn stop [control-plane]
  (core/stop control-plane))

(defn running-processors [control-plane]
  (core/running-processors control-plane))

(defn project-active-nodes [ctx staleness-threshold-ms]
  (core/project-active-nodes ctx staleness-threshold-ms))

(defn project-lease-ownership [ctx]
  (core/project-lease-ownership ctx))

(defn route-for-tenant
  "Pure routing decision. See routing/route-for-tenant."
  [lease-ownership active-nodes this-node-id tenant-id]
  (routing/route-for-tenant lease-ownership active-nodes this-node-id tenant-id))

(defn tenant-routing-interceptor
  "Pedestal interceptor for tenant-aware routing. See routing/tenant-routing-interceptor."
  [opts]
  (routing/tenant-routing-interceptor opts))
