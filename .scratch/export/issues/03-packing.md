# 03 — Packing (items → shulker boxes → barrels)

**Type:** task
**Status:** resolved
**Blocked by:** 02, 04

## Goal

Turn the retained item stacks (`04`) into a barrel fill: barrels placed on a flat grid, each holding up to 27 shulker-box items, each shulker box holding packed item stacks. This is the input to the schematic writer (`01`) and the manifest (`06`).

## Input / Output

- **In:** a list of item stacks (full `CompoundTag`: `id`, `count`, `components`) from `04`.
- **Out:** a `BarrelFill` — barrel positions (x, z; y = 0) and, per barrel, up to 27 shulker-box item `CompoundTag`s — plus a packing summary (stacks, items, barrels).

## Rules

1. **Sort** globally by item id, then by NBT variant (the existing `itemStackNbt` SNBT string is a fine sort key).
2. **Aggregate**: sum identical `(id, NBT variant)` stacks into one row with a total count. This row set is the packing input; individual source slots are never packed directly.
3. **Never nest shulkers**: an item whose id is a shulker box (its contents already in `minecraft:container`) becomes a shulker-box item placed directly into a barrel — never inside another shulker box. An empty shulker box is filtered out before packing. Bundles/pots/crossbows pack normally (contents are components, preserved verbatim).
4. **Build the shulker-box item**: `{ id: "minecraft:shulker_box", count: 1, components: { "minecraft:container": [ { slot: <Int>, item: { id, count, components } }, ... ] } }` with contiguous `slot` values 0..n−1 (empty slots omitted). Copy the item's `count`/`components` verbatim into `item`.
5. **Fill barrels** with up to 27 shulker boxes each (each shulker box is `count:1`, max-stack-1), spilling into the next barrel.
6. Layout (from spec Q8): flat grid, y = 0, rows along X, adjacent barrels with no gaps. `columns = ceil(sqrt(barrelCount))` (X is the long side); ragged final row allowed, no padding.
7. **Fill rules** (tickets `13`, `14`): split each group's count into stacks of at most `maxStackSize`; stackable items get one box per variant (closed at the group boundary); max-stack-1 items share a pool across ids. Then classify every box by contents: identity NBT → player grid; bulk-ready (27 saturated slots of one stackable variant) → full grid, recoloured grey; everything else → main grid.

## Acceptance criteria

- 100 `minecraft:stone` → 2 stacks (64 + 36) in one shulker box.
- A filled shulker box in the input is placed directly into a barrel (not nested); its contents are untouched.
- `minecraft:diamond_sword` never exceeds count 1 per slot.
- 27 shulker boxes fill one barrel; the 28th spills into a second barrel.
- Round-trip: summing the packed counts equals the input counts (lossless).

## Comments

- **Implemented.** `packIntoBarrels(items, version)` in `packing.kt` returns `PackingResult(fullBarrels, mainBarrels, playerBoxBarrels, totalShulkerBoxes, totalItems)`. `aggregateItems` sums identical `(id, NBT)` stacks, sorts by id then NBT, splits by `maxStackSize`, fills 27-slot shulker boxes (per-variant for stackables, one shared pool for unstackables), classifies each box (player identity → player grid; bulk-ready → grey full grid; else main), and lays each section on a flat adjacent near-square grid. Shulker boxes pass straight through to barrels (never nested). `PackingTest` covers 64+36 split, tool max-stack, shulker passthrough, barrel spill at 27, NBT-variant separation, unstackable pooling, full-grid promotion/recolour, near-square layout, and losslessness. `./gradlew test` passes.
- Generated export boxes are `minecraft:shulker_box`; colour-coding remains out of scope.
