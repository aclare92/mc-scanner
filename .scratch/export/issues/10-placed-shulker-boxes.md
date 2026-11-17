# 10 — Placed shulker boxes are exported as contents only

**Type:** bug
**Status:** resolved

## Problem

`blockEntitySources` treats every block entity as a source of item stacks and
reads only its `Items` list. For a **placed shulker box**, that means:

- the box item itself is never emitted;
- its `CustomName` (the label on the box) is lost;
- its colour is lost (the block entity id is always
  `minecraft:shulker_box`, even for a red/blue/… box);
- an **empty** placed box produces no item source at all.

Item-form boxes inside chests and ender chests were already retained correctly
by `dumpItemStacksRaw` and pass through packing into a barrel. The gap is only
placed boxes. On the owner's 1.21.11 world this affected 853 placed shulker
blocks (605 with items, 248 empty).

`manifest.csv` separately lists only what is *inside* each box, so even a
correctly exported box (e.g. a named ender-chest box) does not appear by name in
the manifest. That made the boxes look missing.

## Fix

- In `blockEntitySources`, when the block entity is a shulker box (modern
  components format), return **one** synthetic item source: the box item with
  `minecraft:container` built from `Items` and `minecraft:custom_name` copied
  from `CustomName`.
- Read the block's real id from the chunk palette (`blockIdAt`) so a placed red
  box comes back as `minecraft:red_shulker_box`, not plain.
- Skip **all empty** placed boxes: they behave like building blocks here, and emitting
  one item per block floods the export with empties. A placed box that holds items or has an
  unresolved `LootTable` is still emitted; loot is carried as `minecraft:container_loot` so
  opening the box in the target world generates its contents. See ticket `11` for applying
  the same rule to stored empty boxes.
- The report sees the same source, so it now counts the box in addition to its
  contents (`scanInventory` already recurses the component).
- Add `boxes.csv` (one row per barrel slot, box identity without contents) so
  boxes are locatable; `manifest.csv` remains the contents list.
- Pre-1.20.5 worlds keep the old contents-only path; `--export` rejects them.

## Acceptance criteria

- A placed shulker with `CustomName` and `Items` yields one box item with the
  name and a `minecraft:container` preserving slots/counts.
- The box colour comes from the chunk palette.
- An empty placed box is skipped; filled and loot-bearing boxes are exported.
- The report counts box + contents, and `exportTotals` matches.
- `boxes.csv` lists the box itself; `manifest.csv` still lists contents.

## Comments

- **Implemented.** `items.kt` (`placedShulkerBoxItem`, `blockEntitySources`,
  `chunkItemSources`), `region.kt` (`blockIdAt`), `manifest.kt` (`boxRows`,
  `writeBoxes`), `export.scan.kts`.
- Tests: `placedShulkerBoxBecomesOneContainerItem`,
  `emptyUnnamedPlacedShulkerBoxIsNotAnItem`, `emptyNamedPlacedShulkerBoxIsAlsoSkipped`,
  `chunkItemSourcesReadsShulkerColourFromChunk`,
  `boxRowsListTheBoxItselfIncludingEmptyAndNamedOnes`. `./gradlew test` passes.
- Re-ran the real 1.21.11 export: 853 placed boxes were candidates; 624 are
  materialized (605 filled + 19 loot-bearing) and 229 empty boxes are skipped
  (ticket `11`). Named placed boxes (e.g. `Ancient City` → blue box) appear in the
  litematic and `boxes.csv`. Generated-box nesting dropped because placed-box
  contents stay inside their box.
