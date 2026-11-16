# mc-scanner

A command-line tool that scans Minecraft Java Edition worlds — region files, player data, and entity data — and reports the blocks and items they contain, for world-migration accounting.

## Language

### World data

**data version**:
The integer stored in every chunk, player, and entity NBT record identifying the exact Minecraft build that wrote it. Every format branch in `constants.kt` keys on this, never the marketing version string.
_Avoid_: version, game version

**flattening**:
The 1.13 change that replaced numeric block/item IDs with namespaced IDs (`minecraft:stone`). Also the legacy numeric-ID → modern-ID maps shipped in `src/main/resources/flattening/`. Only pre-1.13 worlds need these; modern worlds are already namespaced.
_Avoid_: unflattening (that is the reverse lookup)

**block state**:
A block type plus its properties, e.g. `minecraft:oak_log[axis=y]`. In chunk NBT, stored as entries in a section's palette.

### Scanning

**needle**:
A query target the scanner searches for — a block state (`-b`), a block ID mask, or an item type (`-i`).
_Avoid_: search term, filter

**item source**:
Any location that can hold item stacks: a player inventory, a block entity, an entity, a nested item component, or an entry in the `entities/` region directory.
_Avoid_: container (it is also a `Location` subtype in the code, so the word is overloaded)

**item entity**:
A dropped item on the ground. Since 1.17 these are stored in the `entities/` region directory, not in the chunk's `entities` tag.

### Output

**report**:
A count of items by type — item → total count, with no per-location detail and no NBT. The current deliverable. Container items (shulker boxes, bundles, charged crossbows) are counted in addition to their contents; an item holding contents is not packaging. Empty shulker boxes are excluded.
_Avoid_: dump, summary

**export**:
Materialising scanned items back into a schematic. Player items are carried over as a source mirror; all other items are filtered, unpacked from [plain boxes](#plain-box), and repacked. Writes a Litematica `.litematic`, a vanilla structure `.nbt`, one [manifest](#manifest), and one [totals](#totals) file. Boxes and items are arranged in up to five [barrel grids](#barrel-grid), in order: [player grid](#player-grid), [identity-box grid](#identity-box-grid), [bulk grid](#bulk-grid), [remainder grid](#remainder-grid), [unstackable grid](#unstackable-grid). Specified in `.scratch/export/spec.md`; run with `--export`.

**barrel grid**:
The arrangement of barrels that holds an export's items. Most grids are flat and near-square; the [player grid](#player-grid) is a line. Barrels sit directly next to each other with no gaps, and grids are separated by two empty rows. Player-grid barrels hold raw source items; the other grids hold shulker boxes.
_Avoid_: barrel line, barrel array

**player item**:
An item stack sourced from a player's `Inventory` (including hotbar, armour, and offhand) or `EnderItems`. Identified by its `PlayerInventory` location, not by item type.
_Avoid_: held item, player inventory item

**player grid**:
The first barrel grid in an export: a source mirror of player data. Raw items and source boxes are carried over as-is, without repacking, unpacking, or re-dyeing. Empty plain boxes are kept. Each player gets a contiguous [player section](#player-section), with one empty barrel between sections; the line runs along X at `z = 0`. [Excluded items](#excluded-item) are still removed.
_Avoid_: player item grid

**player section**:
One player's contiguous barrels in the [player grid](#player-grid). Up to three sectioned barrels: inventory slots 0–26, inventory slots 27–40 (remaining main/hotbar, armour, offhand), and ender chest slots 0–26. Empty sections are skipped; empty slots within a section are preserved. The first barrel carries a [player sign](#player-sign).
_Avoid_: player area

**resolved player name**:
The display name found for a player UUID from server metadata, searched best-effort in the world directory, its parent, and the singleplayer root. `usercache.json` is preferred, then `whitelist.json`, `ops.json`, and `banned-players.json`; the UUID is the fallback.
_Avoid_: username, account name

**player sign**:
An unwaxed oak wall sign with black text, attached to the first barrel of a [player section](#player-section), occupying the first gap row (`z = 1`) and facing south. It labels the player by [resolved player name](#resolved-player-name), or by the UUID split across lines when no name is available.
_Avoid_: nameplate, label

**identity box**:
A shulker box carrying identity NBT: any component other than `minecraft:container` (custom name, lore, custom data, container loot, …). The box's colour is an item ID, not identity. Outside player data, identity boxes pass through to the [identity-box grid](#identity-box-grid); inside player data, they pass through to the [player grid](#player-grid). They are kept even when empty; their contents are still filtered for [excluded items](#excluded-item).
_Avoid_: named box, player box

**identity-box grid**:
The second barrel grid in an export, holding non-player [identity boxes](#identity-box) with their original item ID and NBT intact. Empty if there are none.
_Avoid_: player-box grid, named-box grid

**plain box**:
A shulker box with no component other than `minecraft:container`, or no components at all. Coloured variants are still plain. Outside player data, a non-empty plain box is unpacked and repacked and an empty plain box is dropped. Inside player data, plain boxes are kept as source items in the [player grid](#player-grid).
_Avoid_: default box, normal box

**bulk-ready box**:
A box whose contents can be moved into bulk storage without unloading: all 27 slots filled with one `(item ID, NBT)` variant, every stack at its max stack size (1728 for 64-stack items, 432 for 16-stack). A 27-slot box whose last stack is partial is not bulk-ready, and a box of unstackable items is never bulk-ready. Bulk-ready boxes are generated from non-player aggregated items; plain source boxes are unpacked, not promoted.
_Avoid_: full box, packed box, mixed box, saturated box, fully saturated box

**bulk grid**:
The third barrel grid in an export, holding only [bulk-ready boxes](#bulk-ready-box), one `(item ID, NBT)` variant per box, recoloured `minecraft:gray_shulker_box` so the section is visually uniform. Empty if nothing qualifies.
_Avoid_: full-box grid, saturated grid

**remainder grid**:
The fourth barrel grid in an export, holding the non-player stackable items left over after the bulk grid. It mixes item IDs and NBT variants freely and packs them into the fewest possible boxes. Empty if none.
_Avoid_: main grid, mixed grid

**unstackable grid**:
The fifth barrel grid in an export, holding non-player, non-box items whose max stack size is 1. They are pooled regardless of item ID or NBT, 27 per box. Shulker boxes never go here.
_Avoid_: tools grid

**excluded item**:
An operator/command-only item removed from the export: `command_block`, `chain_command_block`, `repeating_command_block`, `command_block_minecart`, `structure_block`, `jigsaw`, `debug_stick`, `knowledge_book`, `test_block`, `test_instance_block`, `barrier`, `light`, `structure_void`. Excluded wherever found, including inside [identity boxes](#identity-box), player-carried boxes, bundles, crossbows, and decorated pots; still counted in the scan totals.
_Avoid_: illegal item, creative item, admin item

**manifest**:
The export's single item-location CSV. One row per exported item, plus rows for source boxes — [identity boxes](#identity-box) and player-carried boxes — keyed by barrel position, barrel slot, box slot, and for player rows the [resolved player name](#resolved-player-name) or UUID, the source (`inventory` or `ender_chest`), and the source slot. Generated packaging boxes get no box row.
_Avoid_: boxes.csv, player-boxes.csv

**totals**:
The export's scan-vs-export CSV, with `Type,Scanned,Exported,Dropped`. `Scanned` is the raw pre-export total, including empty boxes and [excluded items](#excluded-item); the report's totals are separate. The gap is deliberate: dropped plain boxes, excluded items, and other documented exclusions.
_Avoid_: counts.csv, report totals

**schematic**:
A file that places blocks and block entities into a world — either a Litematica `.litematic` (bit-packed block states) or a vanilla structure block `.nbt` (explicit block list). Export writes both.
