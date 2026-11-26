---
name: grain-ui-component
description: Build reusable Grain Datastar components with scoped signals and checked bindings.
---

# Grain UI components

Return Hiccup and let the enclosing view compile the whole tree. Bind local
state with `with-signals` and pass the handle to child components that share it.

```clojure
(require '[ai.obney.grain.datastar.ui :as ui])

(defn disclosure [label content]
  (ui/with-signals [expanded {:init false}]
    [:div
     [:label [:input {:type "checkbox" :bind/value expanded}] label]
     [:div {:bind/show expanded} content]]))
```

Each `with-signals` body returns one element vector. Render two instances together
with `ui/hiccup` to check independent state. Use `:stable? true` with an explicit
`:name` only for state shared across independent patches. [Signal scoping](../../docs/datastar-ui.md#signals)
