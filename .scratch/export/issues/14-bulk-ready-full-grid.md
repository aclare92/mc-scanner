# 14 — Bulk-ready full grid, including promoted source boxes

**Type:** feature
**Status:** resolved
**Blocked by:** 12, 13

## Problem

With ticket `13`, only *generated* boxes were eligible for the full-box grid. On
the owner's 1.21.11 world that left **6,811 boxes in the main grid that were
already full of one item** — the player's own old boxes (grey boxes of cobbled
deepslate 584, bone meal 560, terracotta 279, granite 192, …). The storage
system cannot tell those from any other main-grid box, so the bulk bypass only
worked for a minority of the bulk stock. The full grid was a lottery based on
provenance rather than on contents.

## Rule

1. **Classify every box by its contents**, generated or pass-through:
   - identity NBT (any component other than `minecraft:container`) → player-box
     grid; this overrides everything else, even when the box is full;
   - **bulk-ready** → full-box grid;
   - everything else → main grid.
2. **Bulk-ready** means all 27 slots present, one item id, one NBT variant, and
   every stack at its max stack size. A partial final stack (e.g. 26×64 + 40) is
   **not** bulk-ready; contents must be transferable wholesale.
3. **Only stackable items qualify.** A box of 27 identical unstackable items is
   saturated but not bulk; it stays in the main grid.
4. **Promote, do not repack.** An eligible source box moves to the full grid with
   its contents and slot arrangement untouched. Repacking would destroy the
   player's box colours and reshuffle contents for no change in what the bulk
   system sees.
5. **The full grid is uniform grey.** Generated full boxes are emitted as
   `minecraft:gray_shulker_box`, and promoted source boxes are recoloured from
   their original colour to grey. Colour is not identity (ticket `12`), so this
   is lossless; the main grid keeps original colours, and generated packaging
   there stays plain `minecraft:shulker_box`.
6. Ordering within the grid is not significant; no extra CSV column is added.

## Acceptance criteria

- The full grid contains no box that fails rule 2, and no unstackable box.
- Every full-grid box has id `minecraft:gray_shulker_box`.
- A saturated, uniform, unnamed source box is promoted and recoloured; its
  contents are byte-identical to the original.
- A named saturated source box stays in the player grid with its name and colour.
- Mixed-id, mixed-NBT, spare-slot and partial-stack source boxes stay in main.
- Totals are unchanged (lossless): the same items, differently distributed.
- `./gradlew test` passes.

## Comments

- **Implemented.** `packing.kt`: `isSaturatedBox` checks 27 slots, one id, one
  variant, every stack at max, and `maxStackSize > 1`; `boxContents` reads a
  source box's container; `withBoxId` recolours promoted boxes;
  `makeBox` emits generated full boxes as grey. `PackingResult` keeps the three
  grid lists; `export.scan.kts` prints all three ranges.
- Tests: `saturatedBoxesGoToTheFullGrid`,
  `sixteenStackableSaturatedBoxGoesToTheFullGrid`, `unstackablesNeverFillTheFullGrid`,
  `saturatedSourceBoxIsPromotedAndRecoloured`, `partialSourceBoxStaysInMainWithItsColour`,
  `mixedIdSourceBoxIsNotFull`, `namedSaturatedSourceBoxStaysInThePlayerGrid`.
- Verified on the real 1.21.11 world: full 8,541 boxes / 317 barrels (all grey,
  all bulk-ready), main 2,756 / 103, player 112 / 5. The 443 uniform unstackable
  boxes in main are correct (shears, shovels, potions, …).
