# 07 — Data-fixer verification (owner-action)

**Type:** research
**Status:** ready-for-human
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

_None yet._
