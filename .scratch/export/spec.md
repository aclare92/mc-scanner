# Export — Spec

**Status**: accepted

## Goal

Materialise the scanned items of an old world back into a form that can be placed into the new world, losslessly and verifiably. The driving use case is world-migration item accounting: the owner is rebuilding storage in a new world (26.3) and needs every item from the old world (1.21.11) to survive the move intact.

## Decisions

| # | Decision |
|---|---|
| 1 | Emit a **Litematica `.litematic`** and a **vanilla structure block `.nbt`** (both; the latter is nearly free). |
| 2 | **Full item NBT** fidelity — enchantments, custom names, damage, written books, and all components preserved verbatim. |
| 3 | **Everything** the report covers: all item sources across all dimensions (players, block entities, entities, item entities). |
| 4 | **Barrels of shulker boxes**: items → shulker boxes → barrels (three-level packing). Barrels are the placed blocks; shulker boxes are items inside them. |
| 5 | Target world is **26.3** (data version 5023). Items are written **verbatim at the source data version** (4671 for the owner's world) and upgraded by the game's data-fixer on load. |
| 6 | **Global sort + greedy fill**, no source provenance, respecting max stack size and the no-shulker-in-shulker rule. |
| 7 | New built-in **`--export`** mode with an `export.scan.kts` script (mirrors `--stats`/`--geode`). |
| 8 | **Flat grid** of barrels: y = 0, rows along X, 1-block air gap between adjacent barrels. |
| 9 | **No legacy translation layer** (source is post-1.20.5; no numeric-ID/`Damage`/`tag` → components rework). |
| 10 | **`manifest.csv` + `totals.csv`**, the totals diffed against the report's `counts.csv` as the losslessness proof. |
| 11 | Vanilla `.nbt` emitted **alongside** the `.litematic`. |
| 12 | **Plain** shulker boxes (no color coding). |

## Non-goals (v1)

- Legacy (pre-1.20.5) item translation. Export v1 requires a post-1.20.5 source; the scanner's read-only old-world support is unchanged. Pre-1.20.5 input should be **rejected with a clear error**, not silently mangled.
- Shulker-box color coding; per-player/per-inventory provenance; placement into the new world's region files directly.

## Invocation

```
mc-scanner --export <world-dir> <out-dir>
```

Outputs in `out-dir`:

- `export.litematic` — Litematica schematic (gzip-compressed NBT).
- `export.nbt` — vanilla structure block file (gzip-compressed NBT).
- `manifest.csv` — `barrel_x,barrel_z,shulker_slot,item_id,count,nbt_variant` (one row per occupied slot).
- `totals.csv` — `Type,Total` (same schema as the report's `counts.csv`, recursed through container components) for diffing.

On exit, print a summary: total item stacks, total item count, total barrels, and a losslessness check against `counts.csv` when present.

## Architecture

Scan behaviour lives in runtime-evaluated `.scan.kts` scripts (see `AGENTS.md`); compiled Kotlin provides primitives. Export adds:

1. **Structured item retention** — a collection path that keeps each slot's full `CompoundTag` (not the count/SNBT-string collapse used by the report). The existing `dumpItemStacks` flattens to `ItemStackEntry(id, count, nbtString)` and is insufficient; export needs the raw tags so they can be written back verbatim.
2. **Schematic writer** (compiled Kotlin, unit-testable) — builds the litematica and vanilla-structure compounds from a set of placed barrels and serialises gzip-compressed binary NBT. See ticket `01`.
3. **Max-stack-size table** — static resource (like `flattening/`) or generated table: item id → max stack size. Values are exactly 1/16/64. See ticket `02`.
4. **Packing** — global sort + greedy fill. See ticket `03`.
5. **`export.scan.kts`** + `--export` flag wiring in `main`. See ticket `05`.

## Data formats (pinned down)

### Litematica `.litematic`

- Root: `MinecraftDataVersion` (int, = source data version), `Version` (int, `7` for 1.20.5+), `SubVersion` (int, `1`), `Metadata` (compound: `Name`, `Author`, `Description`, `RegionCount`, `TotalVolume`, `TotalBlocks`, `TimeCreated`, `TimeModified`, `EnclosingSize`), `Regions` (compound of named regions).
- One region (name `"region"`): `Position{x,y,z}`, `Size{x,y,z}` (positive), `BlockStatePalette` (list of compounds, **`minecraft:air` at index 0**), `BlockStates` (long array), `TileEntities` (list).
- `BlockStates` bit-packing: `bits = max(2, ceil(log2(paletteSize)))`; one continuous stream over the whole region; LSB-first within each long; flat index `i = y*(sx*sz) + z*sx + x`; `ceil(volume*bits/64)` longs.
- Palette entries: `{Name: "minecraft:barrel", Properties: {facing: "north", open: "false"}}` (and `{Name: "minecraft:air"}`).

### Vanilla structure `.nbt`

- Root: `DataVersion` (int), `size` (list of 3 ints), `palette` (list of `{Name, Properties}`), `blocks` (list of `{state:int, pos:[x,y,z], nbt:compound}`), `entities` (list).
- Block-entity `nbt` has no `x/y/z` (position comes from `pos`).

### Barrel block entity

```
{ id: "minecraft:barrel", x: <int>, y: <int>, z: <int>,
  Items: [ { Slot: 0b, id: "minecraft:shulker_box", count: 1, components: { ... } }, ... ] }
```

- `Items` slots 0–26; empty slots omitted. `Slot` is **Byte**; item stack is inlined.
- A shulker-box item's contents live in its `minecraft:container` component:
  `[ { slot: <Int>, item: { id: <String>, count: <Int>, components: {...} } }, ... ]` — `slot` is **Int**, nested `item`, empty slots omitted.

### DataVersion handling

Items are copied **verbatim** from the source world, and the schematic's `MinecraftDataVersion`/`DataVersion` is set to the **source world's data version** (4671). On load, the 26.3 game's data-fixer upgrades 4671 → 5023 — the same mechanism used to open an old world. The only drift between 4671 and 5023 that touches existing item components is `minecraft:pot_decorations` (list → object); the fixer handles it. **Open verification** (ticket `07`): confirm both the Litematica importer and the vanilla structure loader apply the data-fixer to item NBT. Fallback if they do not: write at 5023 with a minimal, explicit component mapping.

## Packing rules

1. Sort all distinct item stacks by item id, then NBT variant.
2. Greedily pack each stack into shulker-box slots, never exceeding the item's max stack size (1/16/64 table, ticket `02`).
3. Shulker boxes (max stack 1) are **never nested**: an old-world filled shulker box becomes a shulker-box item placed directly into a barrel, its contents already in `minecraft:container`. Bundles, decorated pots, and crossbows are ordinary items (their nested contents live in components) and pack normally.
4. Fill barrels with up to 27 shulker boxes each; spill into the next barrel.
5. Do not write `minecraft:max_stack_size`; the game derives it from the item id.

## Manifest & verification

- `manifest.csv` maps each occupied slot to its item, so any single item can be located.
- `totals.csv` sums every item **recursing through containers**, so it is directly comparable to the report's `counts.csv`. The report counts container items as well as their contents.
- The report and export agree when their totals match. Note: the report currently under-counts sherds inside decorated-pot items on pre-26.3 worlds (see ticket `08`); the diff will surface that as a report-side gap, not an export loss.

## Tickets

- `01-schematic-writer` — schematic NBT writer (litematica + vanilla), round-trip tested.
- `02-max-stack-size-table` — item → max stack size (1/16/64) resource/generator.
- `03-packing` — global sort + greedy fill into shulker boxes → barrels.
- `04-structured-item-retention` — retain full item `CompoundTag`s during the export scan.
- `05-export-mode-wiring` — `--export` flag + `export.scan.kts`, orchestrating the above.
- `06-manifest-and-diff` — `manifest.csv` + `totals.csv` + losslessness diff.
- `07-data-fixer-verification` — confirm the data-fixer upgrades schematic item NBT on load (owner-action).
- `08-pot-decorations-report-gap` — fix stats-mode recursion for pre-26.3 `pot_decorations` list form.
- `09-report-container-item-omission` — **resolved**: the report now counts a container item in addition to its contents.

## References

- `GLOSSARY.md` — `export`, `item source`, `report`, `data version`, `flattening`.
- `docs/adr/0001-full-world-format-range.md` — full-range support; export inherits the "never remove legacy paths" principle (it rejects rather than mangles pre-1.20.5 input).
- Format research (in-session): litematica `.litematic` schema, vanilla structure schema, `minecraft:container`/barrel block-entity shapes, and the 1/16/64 max-stack-size lists (source: maruohon/litematica, minecraft.wiki, misode/mcmeta).
