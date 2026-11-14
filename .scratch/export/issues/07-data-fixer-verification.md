# 07 — Data-fixer verification (owner-action)

**Type:** research
**Status:** resolved
**Blocked by:** 01

## Goal

Confirm the spec's Q9 approach: that schematic item NBT stamped at the source data version (4671) is upgraded to 26.3 (5023) by the game's data-fixer when the schematic is loaded — in **both** the Litematica importer and the vanilla structure-block loader.

## Steps (owner)

1. From `01`, produce a minimal test schematic containing a barrel → shulker box → items, plus a decorated-pot item whose `pot_decorations` is in the **4671 list form**, stamped `MinecraftDataVersion: 4671` / `DataVersion: 4671`.
2. In a 26.3 client: load via **Litematica** and via a **structure block** / `/place template`.
3. Confirm the items (especially the pot) load and behave correctly, i.e. the 4671 → 5023 fixer ran on the item NBT.

## Decision this gates

- **Fixer applies** → keep the verbatim + source-`DataVersion` approach; no item translation code needed.
- **Fixer does not apply** to schematic items → open a new ticket to write at 5023 with an explicit, minimal component mapping (the known drift is `pot_decorations`; verify whether any other existing component changed format between 4671 and 5023).

## Comments

- Owner will run the in-game paste test. Ready-made artifacts from the real source world:
  - `output-data/antcraft_vanilla/export.litematic` (~415 KB) — Litematica, stamped `MinecraftDataVersion: 4671`.
  - `output-data/antcraft_vanilla/export.nbt` (~419 KB) — vanilla structure, stamped `DataVersion: 4671`.
  - `output-data/antcraft_vanilla/manifest.csv` — maps barrel/slot → item, for spot-checking exact stacks.
- To regenerate (or make a smaller 1-2 barrel sample), run:
  `java -jar build/libs/mc-scanner-0.7.0.jar --export input-data/antcraft_vanilla output-data/antcraft_vanilla`
- What to confirm: items appear in the barrels' shulker boxes with NBT intact (enchanted books/armour/weapons, custom names, a decorated pot whose sherds came from the pre-26.3 list form), and the pot's `pot_decorations` upgraded rather than erroring.
- If only the **structure block** path works and Litematica does not (or vice versa), note which one on this ticket: the fallback is to stamp at 5023 with an explicit component mapping.
- **Owner confirmed** that pasting the real 1.21.11 export into a 26.3 client worked correctly via **both** Litematica (`.litematic`) and a structure block (`.nbt`). The 4671 → 5023 data-fixer upgrades the item NBT on load, so the verbatim + source-`DataVersion` approach stands and no item-translation code is needed.
