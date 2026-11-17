# 01 — Schematic NBT writer (Litematica + vanilla structure)

**Type:** task
**Status:** resolved

## Goal

A compiled-Kotlin primitive that, given a set of placed barrels (each carrying up to 27 shulker-box items), produces gzip-compressed binary NBT for both a Litematica `.litematic` and a vanilla structure block `.nbt`, and writes them to files. This is the foundational, testable capability for `export`; packing and retention land in later tickets and will feed this writer.

## Input

A data structure describing the placed blocks, e.g.:

```
BarrelFill(
    positions: List<BarrelPos(x, z)>,        // y is always 0; flat grid, see layout
    itemsByBarrel: (BarrelPos) -> List<CompoundTag>   // up to 27 shulker-box item stacks (verbatim, already built)
)
```

Each item in `itemsByBarrel` is the full shulker-box item `CompoundTag` (with `id`, `count`, and `components.minecraft:container` as produced by the packing ticket), ready to be inlined into the barrel's `Items` list.

## Output

- `export.litematic` — see `spec.md` "Litematica `.litematic`".
- `export.nbt` — see `spec.md` "Vanilla structure `.nbt`".

Both are **gzip-compressed big-endian binary NBT** with an unnamed root compound (`Tag.write` already emits the binary form; add a gzip wrapper — the same kind of stream `DECOMPRESSOR` inflates).

## Requirements

1. **Litematica**:
   - Root: `MinecraftDataVersion` (source data version, default 4671), `Version=7`, `SubVersion=1`, `Metadata` (fill `Name`, `Author`, `Description`, `RegionCount=1`, `TotalVolume`, `TotalBlocks`, `TimeCreated`/`TimeModified` unix millis, `EnclosingSize`), `Regions` with one region `"region"`.
   - Region: `Position{0,0,0}`, `Size{sx,1,sz}` from the barrel grid, `BlockStatePalette` with `minecraft:air` at index 0 then `minecraft:barrel` (`Properties:{facing:"north", open:"false"}`), `BlockStates` long array, `TileEntities` (one barrel block entity per barrel).
   - Bit-packing: `bits = max(2, ceil(log2(paletteSize)))`; LSB-first within each 64-bit long; flat index `i = y*(sx*sz) + z*sx + x`; `ceil(sx*sy*sz*bits/64)` longs. Value 0 (air) everywhere a barrel isn't placed; palette index of `minecraft:barrel` where one is.
   - Each `TileEntities` element: `{id:"minecraft:barrel", x,y,z (region-local), Items:[ {Slot:0b, id:"minecraft:shulker_box", count:1, components:{...}}, ... ]}`. `Slot` is **Byte**; empty slots omitted.
2. **Vanilla structure**:
   - Root: `DataVersion` (same as `MinecraftDataVersion`), `size:[sx,1,sz]`, `palette:[{Name:"minecraft:air"}, {Name:"minecraft:barrel", Properties:{...}}]`, `blocks:[{state, pos:[x,y,z], nbt:{...}}]` for each barrel (block-entity `nbt` has `id`+`Items`, **no** x/y/z).
3. **Layout** (from spec Q8): flat grid, y=0, barrels in rows along X, directly adjacent with no gaps. First barrel at `(0,0,0)`.
4. Use the existing `Tag`/`CompoundTag`/`ListTag` types and `Tag.write(out)`; only the gzip wrapper is new.

## Acceptance criteria

- A unit test builds one barrel with one shulker-box item (e.g. 64 `minecraft:stone` + 16 `minecraft:ender_pearl` + 1 `minecraft:diamond_sword`) and:
  - Writes both files, then reads them back with the existing NBT reader and asserts the root keys, palette (`air` first), and the barrel block entity's `Items` structure.
  - Independently decodes the litematica `BlockStates` long array (the test's own little unpacker) and asserts air at empty positions and barrel at the placed positions.
  - Asserts the gzip wrapper round-trips through `DECOMPRESSOR`.
- A second barrel at a non-origin position is placed correctly in both formats.

## Notes

- Do not write `minecraft:max_stack_size`; the game derives it from the item id.
- Keep the writer data-version-agnostic: it takes the data version as a parameter; the caller (export scan) supplies the source world's value.
- The `pot_decorations` list-form report gap (`08`) is unrelated to this ticket; the writer copies item NBT verbatim and must not interpret it.

## Comments

- **Implemented.** `src/main/kotlin/de/skyrising/mc/scanner/schematic.kt` (`PlacedBarrel`, `Schematic`, `toLitematicaNbt`, `toVanillaStructureNbt`, `writeLitematica`, `writeVanillaStructure`) with `src/test/kotlin/de/skyrising/mc/scanner/SchematicTest.kt`. Covers single-barrel round-trip, a second adjacent barrel, and the vanilla structure shape. `./gradlew build` passes (28 tests).
- **Latent bug found and fixed in `nbt.kt`.** `Tag.Companion`'s `init` registered tag readers by dereferencing each tag's companion object (e.g. `IntTag.Companion`). When `Tag.read(...)` was the very first `Tag` access, that companion could still be `null` mid-initialization, leaving `getReader(INT)` null and every read NPE-ing. `getReader` now resolves readers at call time via a `when`, and `init` only records the class→id map. This was pre-existing but only surfaced once a test read NBT on a cold class-init path.
- Note: region `Position` is `{0,0,0}` (schematic origin); tile-entity coordinates are region-local. Do not write `max_stack_size`.
