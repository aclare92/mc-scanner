# Support the full world-format range

**Status**: accepted

mc-scanner reads worlds from pre-1.13 (numeric block/item IDs) through the current calendar-versioned releases (26.3 at the time of writing). Instead of parsing only the latest format, every format change is gated on the chunk's `DataVersion` integer, and old branches are never removed.

## Considered Options

- **Latest-only**: parse just the current format, drop legacy parsing. Simpler, but useless for this tool's purpose — reading *existing* worlds for migration, which span many versions — and removing a legacy branch is a large, one-way diff.
- **Full range (chosen)**: each new Minecraft format change adds a `DataVersion` threshold in `constants.kt` and a new branch. This is how 1.20.5 item components were added, and how the 26.3 block-palette rename will be added.

## Consequences

- The NBT/block/item parsing code carries branches that are only exercised by very old worlds; they must be preserved and cannot be "cleaned up" without silently breaking old-world support.
- Adding support for a new version is additive and low-risk; removing support is the expensive, surprising operation.
