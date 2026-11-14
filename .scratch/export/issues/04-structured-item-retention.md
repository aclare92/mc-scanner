# 04 — Structured item retention

**Type:** task
**Status:** resolved

## Goal

A collection path that retains each item slot's full `CompoundTag` during the export scan, so items can be written back verbatim. The existing `dumpItemStacks` collapses to `ItemStackEntry(id, count, nbtString)` and is insufficient for export.

## Requirements

1. Add a function (extend `dumpItemStacks`, or a new `dumpItemStacksRaw`) that walks an item list the same way `dumpItemStacks` does (including `nestedItemLists`), but retains each slot's **full `CompoundTag`** — `id`, `count`/`Count`, and `components`/`tag` — rather than a flattened count + SNBT string.
2. Container items must stay whole: a filled shulker box is retained as **one** item whose `minecraft:container` component keeps its contents — do **not** split its contents into separate top-level items. Bundles, decorated pots, and crossbows likewise keep their nested contents inside their components.
3. Preserve `Slot`/`slot` and count exactly as read; do not interpret or rewrite NBT here.
4. Be data-version-agnostic: whatever the source world's item format is (post-1.20.5 `components` is the only supported input), copy it verbatim. Reject pre-1.20.5 input with a clear error (the writer cannot translate it).

## Acceptance criteria

- A unit test on a synthetic item list (including a filled shulker box) asserts the retained tags are byte-for-byte the input (id/count/components), and that a shulker box's contents remain inside its `minecraft:container` rather than being split out.
- A plain item, a bundle with `minecraft:bundle_contents`, and a crossbow with `minecraft:charged_projectiles` all round-trip verbatim.

## Comments

- **Implemented.** `dumpItemStacksRaw(items)` in `items.kt` returns the top-level slot compounds verbatim (the original tags, not copies) and deliberately does **not** recurse into nested containers, so a filled shulker box stays one item with its `minecraft:container` intact. Tests in `ItemRetentionTest.kt` cover shulker/bundle/crossbow components and id-less slot skipping. `./gradlew test` passes.
