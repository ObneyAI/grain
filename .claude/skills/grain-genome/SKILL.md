---
name: grain-genome
description: Build or update the grain component genome — the interactive circuit-board dependency map. Use when regenerating the genome, when `bb genome.bb` reports coverage warnings (a component lacks placement/description, taste references a gone component, or a macro is missing from the catalog), or when adding/renaming/removing a component and its genome entry. The build is deterministic (repo facts via genome.bb) merged with a frozen taste overlay (genome-taste.edn); this skill performs the *taste-making* — the judgement the facts can't supply — and keeps the overlay in sync.
---

# grain-genome

The genome is produced by **`bb genome.bb`**, which merges two layers:

| Layer | Source | Nature |
|---|---|---|
| **Facts** | the repo, extracted by `genome.bb` | deterministic: component set, deprecation, dependency edges, LOC, which macros/protocols exist, protocol implementers |
| **Taste** | `genome-taste.edn` | judgement: cluster, grid placement `(col,row)`, role tag, description, CQRS framing, macro signatures & purposes |
| **Shell** | `genome-template.html` (CSS/DOM) + `genome-app.cljs` (ClojureScript interactions) | the frozen visual/interaction code; reads the injected `window.GENOME` |

The whole stack is Clojure: facts (bb) + taste (EDN) + the interactive layer (**ClojureScript, interpreted in-browser by Scittle** — no build step). `bb genome.bb` inlines the data, the vendored `scittle.js`, and the CLJS into one self-contained `grain-genome.html`, and prints a coverage report. The facts recompute on every run, so **the only thing this skill maintains is the taste overlay** (and, rarely, the CLJS shell).

## Files — all under `scripts/genome/` (`genome.bb` resolves siblings by its own path)
- `scripts/genome/genome.bb` — deterministic build (do not put judgement here)
- `scripts/genome/genome-taste.edn` — the frozen editorial overlay (what you edit for taste)
- `scripts/genome/genome-template.html` — HTML/CSS shell + 3 Scittle `<script>` tags. Injection markers: `__GENOME_DATA__` (JSON), `__SCITTLE_JS__` (interpreter), `__GENOME_CLJS__` (app).
- `scripts/genome/genome-app.cljs` — the interactive layer in ClojureScript; reads `window.GENOME`. Edit for behaviour/interaction changes.
- `scripts/genome/vendor/scittle.js` — vendored SCI interpreter (~897 KB, CSP-safe: no `eval`). Bump from cdn.jsdelivr.net/npm/scittle.
- `scripts/genome/shoot.js` — Playwright screenshotter for visual verification
- `scripts/genome/grain-genome.html` — generated output (never hand-edit; regenerable, safe to gitignore)

`genome.bb` finds the grain repo as two levels up from itself; override with `GRAIN_REPO`.

## Build & verify loop
1. `bb scripts/genome/genome.bb` (from the grain repo root; or `GRAIN_REPO=/path bb genome.bb`).
2. Read the stderr coverage report. If it is clean, you're done — publish `grain-genome.html`.
3. Otherwise resolve each warning by editing **`genome-taste.edn`** only, then rebuild. Repeat until clean.
4. Visually verify with Playwright before shipping (see below) — placement is taste, so it must be *looked at*.

## Resolving each warning (the taste-making)

### ⚠ `no taste for: <component>`  (a new/renamed current-gen component)
Add a `:nodes` entry `{:col _ :row _ :cluster _ :role _ :desc _}`:
- **Cluster** — prefer the component's **event-model service area** if it registers one (`grep -rl defeventmodel components/<c>` / inspect the registered area). Otherwise classify by shape:
  - `*-request-handler*` → `write` (command) or `read` (query) → cluster by side; role `entry`
  - `command-processor*` / `todo-processor*` → `write`; `read-model-processor*` / `query-*` → `read`
  - `event-store*` / `event-tailer` / `event-notifier*` / `control-plane` → `log`
  - depends only on primitives, is depended-upon widely (kv/pubsub/schema/time/anomalies/serde/pool/periodic) → `found`
  - services / UI / dev tooling (`datastar`, `example-service`, `*-validator`, `event-model`, `webserver`, `code-agent-tools`) → `apps`
  - terminal UI / observability (`tui-*`, `mulog-*`) → `ops`
- **Placement `(col,row)`** — rows are dependency layers (0 apps · 1 entry/coord · 2 processors · 3 event-log spine · 4 backends/storage · 5 foundation rail); columns run write-left / log-center / read-right (`:cols` has 8 slots). Pick a **free cell** near the component's dependencies/dependents to keep traces short. Two nodes must not share a `(col,row)`.
- **Role** — a 1–2 word tag; use the `def*` macro name if it defines one, else a terse function tag (`entry`, `postgres`, `bus`).
- **Desc** — prefer the **first line of the component core ns docstring** (deterministic-ish); otherwise one plain sentence in the user's vocabulary.

### ⚠ `taste for gone/deprecated component: <component>`
Remove its `:nodes` entry (it was deleted or picked up a `DEPRECATED` marker). Backfill any hole its neighbours leave.

### ⚠ `macros missing from catalog: <macro>`
Add it under the right `:macro-catalog` group with a real signature and purpose:
- **Signature** — from the `defmacro`'s `:arglists` metadata or its arg vector: `grep -A3 '(defmacro <macro>' components/<c>/src/**/interface.clj`.
- **Purpose** — one line describing what an author uses it for. The defining component is filled in automatically.

## Visual verification (required after any placement change)
The layout is aesthetic, so look at it. From the genome dir:
```
node shoot.js   # renders grain-genome.html → shot-*.png (board / selected / protocols / catalog)
```
Read the screenshots. Adjust `(col,row)` in `genome-taste.edn` to minimise trace crossings and keep columns/rows aligned (the circuit-board read). Rebuild, re-shoot. Nodes sharing a column give straight vertical traces — exploit that for hubs like `event-store-v3` and `schema-util`.

## Determinism contract
Never encode facts in `genome-taste.edn` (no LOC, no edges, no "defines X macro") and never encode taste in `genome.bb`. A component with a `DEPRECATED` marker drops out of the current generation automatically. If you find yourself wanting the build to "decide" a cluster or position, that decision belongs here, written into the frozen overlay — so the next `bb genome.bb` stays reproducible.
