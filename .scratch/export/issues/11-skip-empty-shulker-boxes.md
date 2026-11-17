# 11 — Skip empty shulker boxes everywhere

**Type:** task
**Status:** resolved

## Problem

Empty shulker boxes were carried into the export as ordinary pass-through items.
That is lossless, but it floods the schematic with meaningless boxes:

- **~14,300** empty box items stored in chests/barrels, which expand to **~24,500**
  boxes because some slots hold counts up to 64;
- empty placed boxes (ticket `10`) were also being materialized.

The owner's decision: **do not migrate empty shulker boxes at all.**

## Rule

A shulker box is **empty** when it has neither:

- a non-empty `minecraft:container` (modern) / `tag.BlockEntityTag.Items` (legacy), nor
- a `minecraft:container_loot` component / unresolved loot table.

Empty boxes are excluded from the report, stats, packing, manifest and export.
Loot-bearing boxes are **not** empty: their contents are generated on first open.

## Implementation

- `isEmptyShulkerBox(slot, version)` in `items.kt` is the single predicate.
- `scanInventory` skips empty boxes, so `counts.csv` / `stats.csv` no longer
  count them.
- `dumpItemStacks` and `dumpItemStacksRaw` skip them, so `nbt.csv`, `totals.csv`
  and the export item list do too.
- `packIntoBarrels` filters them defensively.
- `exportTotals`, `boxRows` and placed-block synthesis use the same predicate.
- `export.scan.kts` passes `source.version` to `dumpItemStacksRaw`.

## Acceptance criteria

- An empty stored box (including `count: 64`) is absent from report counts and the
  export; a filled box in the same list is kept.
- An empty placed box is absent; a filled or loot-bearing placed box is kept.
- Report `counts.csv` and export `totals.csv` still reconcile exactly.
- `boxes.csv` contains no empty boxes.

## Comments

- **Implemented.** `items.kt` (`isEmptyShulkerBox`, `dumpItemStacks`,
  `dumpItemStacksRaw`, `blockEntitySources`), `inventories.kt` (`scanInventory`),
  `packing.kt`, `manifest.kt`, `export.scan.kts`.
- Tests: `emptyStoredShulkerBoxIsSkippedButFilledOneIsKept`,
  `emptyUnnamedPlacedShulkerBoxIsNotAnItem`,
  `emptyNamedPlacedShulkerBoxIsAlsoSkipped`, `skipsEmptyShulkerBoxes`,
  `boxRowsListFilledBoxesAndSkipEmptyOnes`. `./gradlew test` passes.
- Re-ran the real 1.21.11 export: empty boxes dropped from 24,743 to **0**;
  report and export totals still match across all 1,778 types.
