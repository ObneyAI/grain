---
name: grain-genome
description: >-
  Build or update Grain's interactive component genome. Use when regenerating scripts/genome/grain-genome.html, resolving genome coverage warnings after component or macro changes, editing the taste overlay, or visually verifying component placement. Keep repository facts deterministic and human placement/description decisions in genome-taste.edn.
---

# Grain Genome

`bb scripts/genome/genome.bb` combines deterministic repository facts with the
editorial overlay in `scripts/genome/genome-taste.edn`, then injects them into the
HTML/Scittle shell. Never hand-edit generated `grain-genome.html`.

## Loop

1. Run `bb scripts/genome/genome.bb` from the repository root.
2. Resolve every warning in `genome-taste.edn`:
   - Add missing current components with a unique `(col,row)`, cluster, short role,
     and description.
   - Remove entries for deleted or deprecated components.
   - Add missing macros to the proper catalog group using the real arglist and a
     one-line purpose.
3. Rebuild until the coverage report is clean.
4. Run `node scripts/genome/shoot.js`, inspect every screenshot, and adjust the
   overlay until traces and layers read clearly.

## Taste rules

Rows represent dependency layers: apps, entry/coordination, processors, event-log
spine, storage, foundation. Columns run write-left, log-center, read-right. Place
nodes near dependencies, avoid shared cells, and exploit shared columns for hubs.
Prefer service-area clusters when registered; otherwise classify components by
their architectural role.

Facts such as dependencies, LOC, protocols, macros, and deprecation belong only
in `genome.bb` extraction. Placement, clusters, roles, descriptions, and macro
presentation belong only in `genome-taste.edn`.
