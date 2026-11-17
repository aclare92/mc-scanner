# 09 — Report omits container items that have contents

**Type:** task
**Status:** resolved

## Problem

`scanInventory` (in `inventories.kt`) only counts an item itself when
`contained.isEmpty()`:

```kotlin
if (matchingTypes != null || (byId.isEmpty() && statsMode && contained.isEmpty())) {
    var bestMatch = if (statsMode) ItemType(id, dmg ?: -1) else null
    ...
}
```

So in `--stats` mode (no `-i` needles), a **filled shulker box, a bundle with
contents, or a crossbow with `charged_projectiles` is not counted as an item** —
only its contents are counted. An *empty* container (or any item with no nested
list) is counted normally.

This was surfaced by the export reconciliation: the export counts everything,
the report does not, so those container items appear "only in export".

Example from `test-data/worlds/26.3/`: the report omits `minecraft:shulker_box`
(1), `minecraft:cyan_bundle` (1), and `minecraft:crossbow` (3) even though they
exist in the world with contents.

## Why it matters

The stated goal is "a complete, aggregated report of every item in the old
world". A filled shulker box *is* an item; dropping it undercounts the world.
It is not obvious whether the current behaviour is deliberate ("count storage
contents, treat packaging as packaging") or a bug.

## Decision needed (owner)

- **Fix it**: count the container item as well as its contents (remove the
  `contained.isEmpty()` guard, or count it unconditionally in stats mode). This
  changes `counts.csv`/`stats.csv`/`nbt.csv` numbers for filled containers.
- **Keep it**: document the rule explicitly in `GLOSSARY.md` and keep the export
  diff's `reportEquivalentTotals` mirror.

If fixed, `reportEquivalentTotals` in `manifest.kt` should be removed and the
export diff compared against `exportTotals` directly.

## Acceptance criteria

- A test asserting a filled shulker/bundle/crossbow is counted (or explicitly
  documenting the chosen semantics).
- `counts.csv` reflects the decision; `GLOSSARY.md` `report` entry notes the
  container rule.

## Comments

- **Owner decision: count everything.** Removed the `contained.isEmpty()` guard in `scanInventory`: stats mode now counts a container item in addition to recursing into its contents. `reportEquivalentTotals` was deleted; the export diff now compares `counts.csv` against `exportTotals` directly.
- Tests: `statsModeCountsContainerAndContents`, `statsModeCountsChargedCrossbowAndProjectiles`, plus `totalsCountContainerAndContents`. `./gradlew test` passes (52 tests).
- **Later exception:** empty shulker boxes are excluded from the report and export (ticket `11`). Container items with contents are still counted in addition to their contents.
- Re-validated end-to-end on `test-data/worlds/26.3/`: `counts.csv` now lists `minecraft:shulker_box` (1), `minecraft:cyan_bundle` (1) and `minecraft:crossbow` (3), and the export's losslessness check passes against the complete totals.
- `GLOSSARY.md` `report` entry documents the rule. Note this changes `counts.csv`/`stats.csv`/`nbt.csv` numbers for worlds containing filled containers.
