---
name: grain-genome
description: Regenerate Grain’s interactive component map or update its layout and macro catalog.
---

# Grain genome

From the Grain repository root, run `bb scripts/genome/genome.bb`. It reads
component source and writes `scripts/genome/grain-genome.html`.

Edit `scripts/genome/genome-taste.edn` for placement, descriptions and macro
signatures. Give each current component a unique grid cell and cluster. Keep
writes left, the event log central and reads right. Check missing and stale entries against source. Cache-only directories under
`components/` can appear in the report. Match macro signatures to their
definitions, then rebuild.

For layout changes, run `node scripts/genome/shoot.js` with Playwright and
Chromium available. Inspect the board, selection, protocol and catalog screenshots
in the working directory.
