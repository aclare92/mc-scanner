# 06 — Manifest and losslessness diff

**Type:** task
**Status:** ready-for-agent
**Blocked by:** 03

## Goal

Emit the granular manifest, the diffable totals, and a losslessness check so the owner can trust the migration lost nothing.

## Requirements

1. **`manifest.csv`** — header `barrel_x,barrel_z,shulker_slot,item_id,count,nbt_variant`; one row per occupied slot (recursing into `minecraft:container` to enumerate the actual items inside each shulker box). `nbt_variant` is the existing `itemStackNbt` SNBT string.
2. **`totals.csv`** — schema `Type,Total` (same as the report's `counts.csv`), summing every item **recursing through `minecraft:container`** so it is directly comparable to `counts.csv`.
3. **Summary line** on completion: total item stacks, total item count, total barrels.
4. **Diff**: when a report `counts.csv` is available in the output dir, print types present in one file but not the other, and any count mismatches. This is the losslessness proof.

## Acceptance criteria

- For a small known input, `totals.csv` matches `counts.csv` for the same input (with container recursion applied).
- `manifest.csv` lets a single item be located by barrel/shulker/slot.

## Notes

- The report's pre-26.3 `pot_decorations` under-count (`08`) will surface here as "export has more sherds than the report" — a report-side gap, not an export loss.

## Comments

_None yet._
