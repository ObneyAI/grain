---
name: grain-view
description: Compose a Grain Datastar page or fragment with navigation, command submissions and query refreshes.
---

# Grain views

Keep reads and authorization in the [query](../grain-query-handler/SKILL.md).
Give the view the data it renders. Return Hiccup with a stable root ID that
matches the patch target. At the query boundary, return
`{:datastar/hiccup (ui/hiccup (view data))}` using `ai.obney.grain.datastar.ui`.

- Navigate with `(ui/href query-name {:query-params params})`.
- Submit with `:on/submit {:effect (ui/dispatch command-name payload) :modifiers {:prevent true}}`.
- Use `(ui/refresh query-name params)` when a filter or selection changes.
  For event-driven updates, declare the query’s model subscriptions.

Pass command and refresh inputs explicitly. Use a [UI component](../grain-ui-component/SKILL.md)
when multiple views share an interaction.
