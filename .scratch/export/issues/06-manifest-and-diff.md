# 06 — Manifest and losslessness diff

**Type:** task
**Status:** resolved
**Blocked by:** 03

## Goal

Emit the granular manifest, the diffable totals, and a losslessness check so the owner can trust the migration lost nothing.

## Requirements

1. **`manifest.csv`** — header `barrel_x,barrel_z,shulker_slot,item_id,count,nbt_variant`; one row per item inside each shulker box (recursing into `minecraft:container`). `nbt_variant` is the existing `itemStackNbt` SNBT string. `boxes.csv` lists the boxes themselves (name/colour, no contents) so they can be located too; see ticket `10`.
2. **`totals.csv`** — schema `Type,Total` (same as the report's `counts.csv`), summing every item **recursing through `minecraft:container`** so it is directly comparable to `counts.csv`.
3. **Summary line** on completion: total item stacks, total item count, total barrels.
4. **Diff**: when a report `counts.csv` is available in the output dir, print types present in one file but not the other, and any count mismatches. This is the losslessness proof.

## Acceptance criteria

- For a small known input, `totals.csv` matches `counts.csv` for the same input (with container recursion applied).
- `manifest.csv` lets a single item be located by barrel/shulker/slot.

## Notes

- The report's pre-26.3 `pot_decorations` under-count (`08`) will surface here as "export has more sherds than the report" — a report-side gap, not an export loss.

## Comments

- **Implemented.** `manifest.kt`: `manifestRows`, `exportTotals` (complete, recurses containers), `reportEquivalentTotals` (mirrors the report's counting), `diffTotals`, `writeManifest`, `writeTotals`, `readCountsCsv`. `totals.csv` is keyed like the report (`id` plus `.damage` for non-zero damage). `ManifestTest` covers manifest rows, recursion, damage keying, and diff.
- **Reconciliation vs report:** the diff compares `counts.csv` against `reportEquivalentTotals`, because the report **does not count an item that holds contents** (it counts the contents instead). See ticket `09`. With that, the real 26.3 world reconciles: "losslessness check passed".
