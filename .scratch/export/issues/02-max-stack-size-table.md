# 02 — Max stack size table

**Type:** task
**Status:** ready-for-agent

## Goal

A lookup `maxStackSize(itemId: Identifier): Int` returning 1, 16, or 64, used by packing (`03`) to avoid over-filling a slot. The game derives `minecraft:max_stack_size` from the item id, so this table is *only* for the packing code — it is never written into NBT.

## Requirements

1. Values are exactly **1 / 16 / 64**.
2. **16-stack** (68 items): signs (13 wood types), hanging signs (13), banners (16 colors), cushions (16 colors), and the 10 standalone — `bucket`, `snowball`, `egg`, `blue_egg`, `brown_egg`, `ender_pearl`, `armor_stand`, `honey_bottle`, `written_book`, `straw_bed`.
3. **1-stack** (248 items): durability items (tools/weapons/shears/rods/bows/crossbows/tridents/…), armor (incl. `elytra`, `wolf_armor`, horse/nautilus armor), filled buckets, potions, all beds (except `straw_bed`), boats/rafts (with/without chest), minecarts, shulker boxes (17), bundles (17), harnesses (16), banner patterns (10), music discs, stews/soups/cake, `writable_book`/`enchanted_book`/`knowledge_book`, `saddle`, `goat_horn`.
4. Everything else defaults to **64**.
5. The naive "tools=1, signs=16, else=64" heuristic is **not** reliable — the table must encode the exceptions: `*_banner` is 16 but `*_banner_pattern` is 1; `written_book` is 16 but `writable_book` is 1; `ender_eye` is 64 but `ender_pearl` is 16; `honey_bottle` is 16 but empty `bucket` is 16 while water `bucket` is 1.

## Source of truth

`misode/mcmeta@26.3-diff/items` (the `max_stack_size` default component, enumerated per item since 1.21.2). See `spec.md` references. In principle the table should match the *source* world's data version; 4671 and 5023 differ only for items added after 4671, so the 26.3 table is a safe superset for the owner's 1.21.11 source.

## Shape

Follow the repo's existing pattern. Prefer a **static resource** JSON (like `src/main/resources/flattening/`) listing only the non-64 items (`{ "minecraft:ender_pearl": 16, "minecraft:diamond_sword": 1, ... }`), with a default of 64. A build-time generator from mcmeta (like `buildSrc` does for `random-ticks.kt`) is acceptable if you want the data regenerated, but do not add a runtime dependency.

## Acceptance criteria

- `maxStackSize` returns: 64 for `stone`, 16 for `ender_pearl`/`snowball`/`egg`/`oak_sign`/`white_banner`, 1 for `diamond_sword`/`shulker_box`/`bundle`/`oak_boat`/`white_bed`.
- Unit test asserts the surprising cases above and that an unknown id defaults to 64.

## Comments

_None yet._
