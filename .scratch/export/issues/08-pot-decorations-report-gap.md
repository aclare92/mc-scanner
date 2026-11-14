# 08 — Pre-26.3 `pot_decorations` report gap

**Type:** task
**Status:** resolved

## Goal

Fix the report (`stats`) under-counting of sherds inside decorated-pot items on pre-26.3 worlds, so `counts.csv` is actually "everything" on the owner's 1.21.11 world.

## Problem

`nestedItemLists` in `src/main/kotlin/de/skyrising/mc/scanner/items.kt` reads the `minecraft:pot_decorations` component **only** as a `COMPOUND` (the 26.3+ object form with `back`/`left`/`right`/`front`). Pre-26.3 (including data version 4671) stores it as a **list** of 4 item ids, which the current branch misses, so those sherds are not counted.

## Requirements

1. Add a branch in `nestedItemLists` for the pre-26.3 list form of `minecraft:pot_decorations`, gated on the data version.
2. Add a `DataVersion` constant for the `pot_decorations` list→object change. **Determine the exact threshold** — it landed in the 26.3 snapshots (between `BLOCK_PALETTE_COMPACT` 5009 and 5023); confirm the precise data version and use it.
3. Mirror the existing pre/post-26.3 pattern already used for block-entity `sherds` in `blockEntitySources` (which handles both forms).

## Acceptance criteria

- On a pre-26.3 data version, `stats` mode counts the sherds inside a decorated-pot item.
- On 26.3+, behaviour is unchanged.

## Notes

- This is a *report* gap only. Export is unaffected: it copies item NBT verbatim.

## Comments

- **Implemented.** `nestedItemLists` now handles the pre-26.3 list form: it reads the `ListTag` of item-id strings, skips `minecraft:air` and default `minecraft:brick` faces, and emits them as `{id, count:1}` stacks, so the sherds are counted. The 26.3 object form is unchanged. No `DataVersion` constant was needed because the two forms are distinguishable by tag type (`LIST` vs `COMPOUND`); the count key still uses `COMPONENTS_REWORK`. Test: `pre263PotDecorationsListIsScanned`. `./gradlew test` passes.
