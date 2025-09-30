(ns ai.obney.grain.behavior-tree-v2.interface
  (:require [ai.obney.grain.behavior-tree-v2.core.engine :as core]
            [ai.obney.grain.behavior-tree-v2.interface.protocol :as p]))

(def success p/success)
(def failure p/failure)
#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(def running p/running)

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn build
  "Given a Behavior Tree `config` and a `context`, returns 
   a built Behavior Tree that can be executed with `run`.
   
   `context` is a map that can include both user defined and special
    keys.
   
   User defined keys can be used as needed in custom condition and action nodes.
   
   Special keys:
   
   - `:event-store`   - A reified implementation of `ai.obney.grain.event-store-v2.interface.protocol/EventStore`
   - `:st-memory`     - A map representing the initial short-term memory of the Behavior Tree. Will be wrapped in
                        an atom internally.
   
   The following must be supplied together with `:event-store` to be effective, these are for utilizing
   long-term memory (e.g. domain events from the event store):
   
   - `:queries`       - A vector of Grain event-store queries as defined by the `ai.obney.grain.event-store-v2.interface.protocol/EventStore` protocol. 
   - `:read-model-fn` - A function of [initial-state event] that returns a map created by applying an event to the initial state, producing the latest state."
  [config context]
  (core/build config context))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn run 
  "Run or 'Tick' a behavior tree created with `build`.
   
   Unless there is an uncaught exception, the result will always be one of:
   
   - `:success` - The tree has finished executing.
   - `:running` - Part of the tree has not finished executing and the tree should be run again.
   - `:failure` - There was an anticipated or unanticipated failure within the tree."
  [bt]
  (core/run bt))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn st-memory-has-value?
  "Validate a specific location given a malli schema.
  
   args:
   - `:path` - Optional get-in path to a specific location in short-term memory.
   - `:schema` - Required malli schema to validate either the entire short-term memory map or the given path."
  [{{:keys [_path _schema]} :opts
    :keys [_st-memory]
    :as args}]
  (core/st-memory-has-value? args))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(defn lt-memory-has-value?
  "Validate a specific location given a malli schema.
     
   args:
   - `:path` - Optional get-in path to a specific location in short-term memory.
   - `:schema` - Required malli schema to validate either the entire short-term memory map or the given path."
  [{{:keys [_path _schema]} :opts
    :keys [_lt-memory] :as args}]
  (core/lt-memory-has-value? args))