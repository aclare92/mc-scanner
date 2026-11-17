# 13 — Box contents: one stackable variant per box, unstackables pooled

**Type:** feature
**Status:** resolved
**Blocked by:** 03, 11

## Problem

Packing filled the 27-slot box buffer continuously and only flushed it when it
was full, so a box almost always straddled two adjacent item groups — often
half one item, half another. That defeats the owner's storage-system import: a
box full of exactly one item can bypass box unloading and go straight into bulk
storage, but a mixed box cannot.

## Rule

1. **Stackable items (max stack > 1) never share a box.** A generated box is
   closed when its `(id, NBT variant)` group ends, so every such box holds
   exactly one variant. Identity is the item's components verbatim, ignoring only
   `count`/`slot`.
2. **Max-stack-1 items share one pool.** Any unstackable items may occupy the
   same box regardless of id or NBT variant. They are never bulk-ready, so
   per-variant boxes would only multiply mostly-empty boxes; a mixed box also
   matches how a normal dump-style system unloads.
3. **Source boxes are exempt.** A box that came out of the old world is
   pass-through; its contents are whatever the player left in it (ticket `14`
   decides where it is placed).
4. Box contents are built from the aggregated `(id, NBT variant) → count` rows,
   never from individual source slots.

## Acceptance criteria

- No generated box contains two stackable ids or two NBT variants of the same
  stackable id.
- 27 different unstackable items share one box.
- A group's leftover slots are never topped up by the next variant.
- Totals are unchanged: the same items, differently distributed.
- `./gradlew test` passes.

## Comments

- **Implemented.** `packing.kt`: `aggregateItems` produces the variant rows;
  stackable groups flush a box at the group boundary; max-stack-1 groups append
  to a shared `looseSlots` buffer that flushes every 27 items.
- Tests: `neverMixesItemTypesInOneBox`, `preservesNbtVariantsSeparately`,
  `unstackablesShareBoxesAcrossIds`, `unstackablesNeverFillTheFullGrid`.
- Trade-off: stackable box count grows to roughly
  `Σ ceil(slots(group) / 27)`, so long-tail variants each consume a mostly-empty
  box. Unstackable pooling offsets part of that growth.
