# mc-scanner

A command-line tool that scans Minecraft Java Edition worlds — region files, player data, and entity data — and reports the blocks and items they contain, for world-migration accounting.

## Language

### World data

**data version**:
The integer stored in every chunk, player, and entity NBT record identifying the exact Minecraft build that wrote it. Every format branch in `constants.kt` keys on this, never the marketing version string.
_Avoid_: version, game version

**flattening**:
The 1.13 change that replaced numeric block/item IDs with namespaced IDs (`minecraft:stone`). Also the legacy numeric-ID → modern-ID maps shipped in `src/main/resources/flattening/`. Only pre-1.13 worlds need these; modern worlds are already namespaced.
_Avoid_: unflattening (that is the reverse lookup)

**block state**:
A block type plus its properties, e.g. `minecraft:oak_log[axis=y]`. In chunk NBT, stored as entries in a section's palette.

### Scanning

**needle**:
A query target the scanner searches for — a block state (`-b`), a block ID mask, or an item type (`-i`).
_Avoid_: search term, filter

**item source**:
Any location that can hold item stacks: a player inventory, a block entity, an entity, a nested item component, or an entry in the `entities/` region directory.
_Avoid_: container (it is also a `Location` subtype in the code, so the word is overloaded)

**item entity**:
A dropped item on the ground. Since 1.17 these are stored in the `entities/` region directory, not in the chunk's `entities` tag.

### Output

**report**:
A count of items by type — item → total count, with no per-location detail and no NBT. The current deliverable.
_Avoid_: dump, summary

**export**:
Materialising scanned items back into a schematic: pack them into shulker boxes inside barrels, then write a Litematica `.litematic` and a vanilla structure `.nbt`. Specified in `.scratch/export/spec.md`; not yet implemented.

**schematic**:
A file that places blocks and block entities into a world — either a Litematica `.litematic` (bit-packed block states) or a vanilla structure block `.nbt` (explicit block list). Export writes both.
