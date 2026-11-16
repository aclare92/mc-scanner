# Export — Spec

**Status**: accepted (revised: five-grid, player source-mirror design)

## Goal

Materialise the scanned items of an old world into a form that can be placed into a new world for migration. The export must:

- carry player-owned items over in their original inventory layout;
- compact all non-player items into as few shulker boxes and barrels as possible;
- preserve full item NBT verbatim;
- remove operator/command-only items;
- produce a human-usable manifest and an honest scanned-vs-exported totals file.

The driving use case is a small server moving from 1.21.11 to 26.3. Players want their own inventories and ender chests back; the remaining world items can be reorganised freely.

## Non-goals

- Translating pre-1.20.5 item formats. Export requires a 1.20.5+ source.
- Preserving non-player source-box layouts. Plain non-player boxes are unpacked.
- Per-player provenance for non-player items.
- Signs labelling grids other than the player sections.
- Item-data sanitation beyond the excluded-ID list (overstacked counts, negative damage, command-bearing `block_entity_data`/`entity_data`).
- Writing directly into target region files.
- Preserving source chests/barrels/other containers as blocks. Their contents are exported as items.

## Invocation

```
mc-scanner --export <world-dir> [output]
```

`--export` is a flag with no argument. The first positional argument is the world or region input; the optional second is an output directory or `.zip`.

Outputs:

- `export.litematic` — Litematica schematic (gzip-compressed NBT).
- `export.nbt` — vanilla structure-block file (gzip-compressed NBT).
- `manifest.csv` — one item-location CSV, including source-box rows.
- `totals.csv` — `Type,Scanned,Exported,Dropped`.

Removed from the previous design: `boxes.csv`, `player-boxes.csv`, and the automatic `counts.csv` losslessness diff.

## Item sources

The export scans everything the report scans:

- player `Inventory` and `EnderItems` from `players/data/*.dat` (26.1+) or `playerdata/*.dat` (older);
- region block entities (chests, barrels, hoppers, furnaces, dispensers, droppers, chiseled bookshelves, jukeboxes, lecterns, decorated pots, placed shulker boxes, etc.);
- entities and entity inventories (item frames, dropped items, minecarts, mob inventories, etc.);
- all dimensions supported by `getHaystack`.

Player data path preference:

1. If `players/data` exists, scan it and do **not** scan `playerdata`.
2. Otherwise scan `playerdata`.

Player items are identified by the existing `PlayerInventory(uuid, enderChest)` location.

## Classification order

Every top-level source item is classified in this order. The first matching rule wins.

1. **Player item** — source location is `PlayerInventory`.
2. **Identity box** — non-player shulker box with identity NBT.
3. **Bulk-ready generated box** — non-player stackable items, grouped by `(item ID, NBT variant)`, saturated boxes.
4. **Remainder** — non-player stackable items left after the bulk grid.
5. **Unstackable** — non-player, non-box items whose max stack size is 1.

The excluded-item rule is not part of this order: it is applied before everything, to every source, including player items and the contents of preserved containers.

## Player grid

The player grid is the **first** grid and is a source mirror, not a repackaging grid.

### Contents

- Raw item stacks from `Inventory` and `EnderItems`, carried over as-is.
- Shulker boxes carried by players, carried over as-is, including plain, empty, coloured, named, and loot-bearing boxes.
- No wrapping into generated shulker boxes, no unpacking, no re-dyeing.
- Empty plain boxes inside player data are kept.
- Excluded items are removed wherever they occur.
- Contents of player-carried boxes are filtered for excluded items but the boxes are not unpacked.

### Sections and slot mapping

Each player gets one contiguous section. A section has up to three barrels:

| Section | Source slots | Barrel slots |
|---|---:|---:|
| Inventory A | 0–26 | 0–26 |
| Inventory B | 27–40 | 0–13 |
| Ender chest | 0–26 | 0–26 |

Canonical player-inventory slots:

- `0–35` main inventory and hotbar;
- `36–39` armour;
- `40` offhand;
- ender chest `0–26` separately.

An empty section is skipped entirely. Empty slots inside a non-empty section are preserved. A player with no exported items gets no section and no sign.

### Layout

- Player grid runs along X at `z = 0`.
- Player sections are contiguous along X.
- One empty barrel separates player sections.
- Named players come first, sorted case-insensitively by resolved name. UUID-only players follow, sorted by UUID. UUID breaks name ties.
- The next grid starts two empty rows after the player barrels, at `z = 3`.
- The sign occupies the first empty row (`z = 1`); the second row (`z = 2`) remains air.

### Signs

One sign per player section, attached to the first barrel of that section.

- Block: `minecraft:oak_wall_sign[facing=south, waterlogged=false]`
- Position: one block south of the first barrel (`x = first barrel x`, `z = 1`).
- Block entity: `minecraft:sign`
- Text: black, unwaxed, plain-string messages.
- `front_text.messages`: four strings, up to four lines, name or UUID wrapped/split.
- `back_text.messages`: four empty strings.
- `has_glowing_text: 0b`, `is_waxed: 0b`.
- Omit `allow_op_features` so the schematic also loads cleanly in 1.21.11.
- Player names are wrapped at 12–13 characters per line. A 36-character UUID is split into three lines of 12–13 characters.

No signs for the identity, bulk, remainder, or unstackable grids in this version.

## Player name resolution

Names are best-effort; the UUID is always a valid fallback.

Search these directories in order:

1. the world directory;
2. the world's parent (dedicated server root);
3. the world's grandparent (singleplayer `.minecraft` root for `saves/<world>`).

Read, in preference order:

1. `usercache.json` — `[{name, uuid, expiresOn}]`;
2. `whitelist.json` — `[{uuid, name}]`;
3. `ops.json` — `[{uuid, name, level, bypassesPlayerLimit}]`;
4. `banned-players.json` — `[{uuid, name, ...}]`.

`banned-ips.json` has no UUID/name and is ignored. Stats/advancement files and `level.dat` are not name sources. Malformed or missing files are ignored; name lookup never fails the export.

Carpet bots normally appear in `usercache.json`; they are included like any other player. Offline UUIDs and bots may be absent from the cache, so the fallback matters.

## Non-player grids

### Identity-box grid

- Second grid.
- Holds non-player shulker boxes with identity NBT: any component other than `minecraft:container`.
- Identity includes custom name, lore, `custom_data`, `container_loot`, etc.
- Colour alone is not identity; a coloured box with only container contents is plain.
- Identity boxes pass through whole, even when empty.
- Their contents are filtered for excluded items but not unpacked.

### Plain boxes

A plain box has no component other than `minecraft:container`, or no components at all.

Outside player data:

- empty plain box → dropped;
- non-empty plain box → unpacked recursively into its contents.

Inside player data, plain boxes are kept as source items in the player grid.

### Bulk grid

- Third grid.
- Built from non-player stackable items grouped by `(item ID, NBT variant)`.
- Each generated box contains one variant only, all 27 slots saturated at the item's max stack size.
- Generated bulk boxes are `minecraft:gray_shulker_box`.
- Source boxes are never promoted; plain source boxes are unpacked first.
- A 27-slot box with a partial final stack is not bulk-ready; its partial stack goes to the remainder grid.
- Unstackable items never enter the bulk grid.

### Remainder grid

- Fourth grid.
- Holds non-player stackable items left over after the bulk grid.
- Mixes item IDs and NBT variants freely.
- Packs stacks into as few boxes as possible.
- Generated packaging uses `minecraft:shulker_box`.

### Unstackable grid

- Fifth grid.
- Holds non-player, non-box items whose max stack size is 1.
- Pooled regardless of item ID or NBT, 27 per box.
- Generated packaging uses `minecraft:shulker_box`.
- Shulker boxes never go here.

## Excluded items

Remove these operator/command-only item IDs wherever found:

```
minecraft:command_block
minecraft:chain_command_block
minecraft:repeating_command_block
minecraft:command_block_minecart
minecraft:structure_block
minecraft:jigsaw
minecraft:debug_stick
minecraft:knowledge_book
minecraft:test_block
minecraft:test_instance_block
minecraft:barrier
minecraft:light
minecraft:structure_void
```

Filtering rules:

- Highest precedence: applied even inside player inventories and preserved identity/player boxes.
- Recurses through all nested item lists: `minecraft:container`, `minecraft:bundle_contents`, `minecraft:charged_projectiles`, and `minecraft:pot_decorations`; a single nested `minecraft:use_remainder` item is filtered too.
- The containing item remains; only the excluded contents are removed. A removed pot-decoration face becomes `minecraft:brick`, the default face.
- An excluded stack is removed along with anything nested inside it.
- Excluded items remain in `Scanned` and appear in `Dropped`.

Do not exclude creative-only but legitimate/ambiguous content such as bedrock, spawn eggs, elytra, totems, enchanted golden apples, Mending books, or dragon eggs. Do not exclude `petrified_oak_slab` from old worlds.

## Packing rules

- Max stack sizes come from `max_stack_size.json` (1/16/64; unknown IDs default to 64).
- Do not write `minecraft:max_stack_size`; the game derives it from the item ID.
- Barrels hold 27 items/boxes.
- Non-player grids are flat, adjacent, near-square: `columns = ceil(sqrt(barrelCount))`, X as the long side, ragged final row allowed, no padding.
- Grids are separated by two empty rows. The player sign occupies the first gap row after the player grid.
- Player grid is a line, not a square.
- Generated boxes are not nested.

## Manifest

Single CSV with header:

```
kind,grid,player,source,barrel_x,barrel_z,barrel_slot,box_slot,source_slot,item_id,count,nbt_variant
```

Row rules:

- `kind` = `item` or `box`.
- `grid` = `player`, `identity`, `bulk`, `remainder`, or `unstackable`.
- `player` = resolved player name or UUID for player-grid rows; empty elsewhere.
- `source` = `inventory` or `ender_chest` for player top-level rows; empty elsewhere.
- `barrel_x`, `barrel_z` = barrel position.
- `barrel_slot` = slot in the barrel.
- `box_slot` = slot inside a shulker box for item rows; empty for raw player items and box rows.
- `source_slot` = canonical source slot for player top-level items/boxes; empty elsewhere.
- `item_id`, `count` = item identity and count.
- `nbt_variant` = item NBT minus `id`/`count`/`Slot`; for box rows, identity NBT only, excluding `minecraft:container` contents.

Source boxes get `kind=box` rows:

- non-player identity boxes;
- player-carried boxes.

Generated packaging boxes get no box row. Their contents get item rows with `barrel_slot` and `box_slot`. Empty source boxes still get a box row. Contents of source boxes get item rows with the same `player`/`grid`, and `box_slot` set.

## Totals

Single CSV with header:

```
Type,Scanned,Exported,Dropped
```

- `Scanned` is the raw pre-export total: every item stack found, recursing through nested containers, including empty boxes and excluded items.
- `Exported` is the source-item total after excluded items are removed and plain non-player boxes are unpacked. Generated packaging is not counted.
- `Dropped = Scanned - Exported`.
- Keys match the report's item identity: item ID plus `.damage` for non-zero damage.
- The report's `counts.csv` is not used and its empty-box exclusion does not affect this file.

Examples of deliberate drops:

- excluded operator items (all sources);
- plain empty boxes outside player data;
- plain non-empty boxes outside player data: the box item is dropped, its contents remain exported.

Player empty boxes and non-player identity boxes are never dropped.

## Schematic formats

### Litematica `.litematic`

- Root: `MinecraftDataVersion` = source data version, `Version = 7`, `SubVersion = 1`, `Metadata`, `Regions`.
- One region named `region`.
- Palette: air at index 0, barrel, oak wall sign.
- `BlockStates`: LSB-first packed longs, flat index `y*(sx*sz) + z*sx + x`.
- `TileEntities`: barrels and signs, each with region-local `x/y/z`.
- Barrel block entity: `{id:"minecraft:barrel", x, y, z, Items:[...]}`.
- Sign block entity: `minecraft:sign` payload described above, plus `x/y/z`.
- `TotalBlocks` counts barrels and signs; `TotalVolume` covers the whole bounding box.

### Vanilla structure `.nbt`

- Root: `DataVersion` = source data version, `size`, `palette`, `blocks`, `entities`.
- Palette: air, barrel, oak wall sign.
- Each block: `{state, pos:[x,y,z], nbt:{...}}`.
- Barrel NBT has no `x/y/z`.
- Sign NBT has no `x/y/z`; position comes from `pos`.
- `entities` is empty.

### Barrel contents

- `Items` slots 0–26; empty slots omitted.
- `Slot` is a byte.
- Item stacks are inlined as `{id, count, components}`.
- Non-player grids place shulker-box items in barrels.
- Player grid places raw source items and source boxes directly in barrels.
- Shulker-box contents remain in `minecraft:container`; they are not nested inside another shulker box.

### DataVersion handling

Items are copied verbatim at the source data version. The schematic stamps that version so the target game's data fixer upgrades it on load. The export rejects sources below `DataVersion.COMPONENTS_REWORK` (3819, 1.20.5). Mixed source versions are allowed with a warning; the schematic uses the maximum version.

## Failure behaviour

- Any per-file scan failure aborts the export before writing anything.
- Scan failures are printed with the file name.
- A source below 1.20.5 aborts with a clear error.
- Name lookup failures are non-fatal.
- The export prints a summary of item counts, box counts, barrel counts, grid ranges, player sections, and signs.

## Implementation plan

### 1. Shared data model and helpers

- Add the excluded-item ID set and a recursive nested-item filter that preserves parents.
- Add a player-name resolver that reads the four JSON files from the three candidate directories.
- Add raw-item carriers for player sources that retain player UUID, `inventory`/`ender_chest`, canonical source slot, and the item tag.

### 2. Player grid packing

- New per-player section packer.
- Canonical slot mapping: inventory 0–26, 27–40, ender chest 0–26.
- Skip empty sections; preserve empty slots.
- Place sections along X with one empty barrel between players.
- Sort by resolved name.
- Emit signs at `z = 1` attached to each section's first barrel.

### 3. Non-player packing rewrite

- Separate identity boxes.
- Recursively unpack plain boxes; drop empty plain boxes.
- Aggregate stackables by `(item ID, NBT)`.
- Generate bulk-ready boxes; send partial stacks to the remainder grid.
- Pool unstackables.
- Place identity, bulk, remainder, unstackable grids in order with two-row gaps.

### 4. Schematic writer

- Extend `Schematic` with placed signs.
- Add the oak wall sign palette entry.
- Include signs in bounds, block states, `TotalBlocks`, and tile entities.
- Write the sign block entity in Litematica (with `x/y/z`) and vanilla structure (without `x/y/z`) forms.

### 5. Manifest and totals

- Replace the old manifest/box-row API with the unified `kind` schema.
- Add grid, player, source, and source-slot fields.
- Add `box_slot` by reading container entry slots.
- Replace `totals.csv` with `Scanned/Exported/Dropped`.
- Remove `diffTotals` and `readCountsCsv` usage from export.

### 6. Export script wiring

- Split collected raw items into player and non-player lists.
- Record scanned totals before filtering.
- Run excluded-item filtering, plain-box unpacking, and grid packing.
- Write the four files only.
- Print the new summary and any name-lookup misses.

### 7. Tests and verification

- Update `PackingTest` for five grids, unpacking, identity/plain rules.
- Update `ManifestTest` for the new schema, box rows, player rows, source slots.
- Update `SchematicTest` for signs and raw player barrels.
- Add tests for excluded-item recursion, excluded totals, player sections, signs, and name resolution.
- Add an end-to-end fixture if a suitable world exists; otherwise unit-test the script helpers.
- Run `./gradlew test` and `./gradlew build`.

## References

- `GLOSSARY.md` — export, player grid, player section, player sign, identity box, plain box, excluded item, manifest, totals.
- Minecraft Wiki: [Sign](https://minecraft.wiki/w/Sign), [Structure block file format](https://minecraft.wiki/w/Structure_block_file_format), [Player.dat format](https://minecraft.wiki/w/Player.dat_format), [Server](https://minecraft.wiki/w/Server), [.minecraft](https://minecraft.wiki/w/.minecraft).
- Litematica source: `LitematicaSchematic.java` (v7 tile-entity positions relative to the region min corner).
- Existing implementation: `src/main/kotlin/de/skyrising/mc/scanner/{packing,schematic,manifest,items,inventories}.kt`.
