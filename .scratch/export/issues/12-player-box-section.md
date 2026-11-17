# 12 — Separate player boxes into their own barrel grid

**Type:** feature
**Status:** resolved
**Blocked by:** 03, 10, 11

## Problem

The export mixes generated packaging boxes with pass-through boxes from the old
world. On the owner's world only **112** of **10,309** boxes carry identity NBT
(custom name, lore, or loot), but they are scattered through the same barrels as
thousands of generated packaging boxes. Finding them means scouring
`boxes.csv` and opening every barrel.

## Rule

A pass-through shulker box with **identity NBT** — any component other than
`minecraft:container` (custom name, lore, `container_loot`, custom data, …) — is
most likely a player box. Put those boxes in a separate **player-box grid**:

- Main grid: generated packaging boxes plus pass-through boxes with no identity
  NBT, at `z = 0`.
- Player-box grid: identity-NBT boxes, starting `mainRows + 2` (two empty rows)
  so the sections read as separate areas. If the main grid is empty it starts at
  `z = 0`; if there are no player boxes no extra grid is emitted.
- Each grid is laid out independently with the existing near-square rule.
- `player-boxes.csv` (same schema as `boxes.csv`) lists only the player-box
  rows and is written even when empty.
- The completion summary prints both grids' coordinate ranges.

Unnamed coloured source boxes stay in the main grid: colour is part of the item
id, not identity NBT, and the owner chose the narrow identity-NBT rule.

## Acceptance criteria

- A named source box lands in `playerBoxBarrels`; plain and generated boxes stay
  in `mainBarrels`.
- The player grid starts two empty rows after the main grid and is offset by the
  actual main row count.
- A player-only export places its grid at `z = 0`; a main-only export has no
  player grid.
- `boxes.csv` still lists every box; `player-boxes.csv` contains exactly the
  identity-NBT subset.
- `./gradlew test` passes.

## Comments

- **Implemented.** `items.kt` (`boxIdentityComponents`,
  `hasBoxIdentityComponents`), `packing.kt` (`PackingResult.mainBarrels` /
  `playerBoxBarrels`, per-grid layout, two-row gap), `manifest.kt` (shares the
  identity predicate), `export.scan.kts` (`player-boxes.csv`, grid ranges).
- Tests: `namedSourceBoxesGoToSeparatePlayerGrid`,
  `playerGridStartsAtOriginWhenThereAreNoMainBarrels`,
  `plainBoxesDoNotCreateAPlayerGrid`, `playerBoxRowsAreTheIdentityNbtSubset`.
