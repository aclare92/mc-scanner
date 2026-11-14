# 05 — Export mode wiring (`--export` + `export.scan.kts`)

**Type:** task
**Status:** ready-for-agent
**Blocked by:** 01, 06

## Goal

Add the `--export` built-in mode and its script, orchestrating `01` (writer), `03` (packing), `04` (retention), and `06` (manifest).

## Requirements

1. Add an `--export` flag in `main` (`scanner.kt`), mirroring `--stats`. Dispatch to a new built-in script `export` (resource `src/main/resources/scripts/export.scan.kts`). See the AGENTS.md gotcha: a built-in mode needs a new resource script **and** flag wiring in `main`.
2. `export.scan.kts`:
   - `collect` all item sources across all dimensions (same haystack as `stats`).
   - `scan` retaining structured item stacks (`04`).
   - Pack (`03`) → write `export.litematic` + `export.nbt` (`01`).
   - Emit `manifest.csv`/`totals.csv` and the diff (`06`) in `after`.
3. Invocation: `mc-scanner --export <world-dir> <out-dir>`.
4. **DataVersion**: stamp both schematics with the source world's data version (detect from the scanned chunks/players; default 4671 when homogeneous, warn on mixed).
5. **Reject pre-1.20.5 input** (data version < `COMPONENTS_REWORK`) with a clear error rather than mangling it.
6. **Fail loudly on per-file scan errors** for export (unlike the report, a missed file here is silent data loss): report a non-zero exit or an unmistakable summary warning.

## Acceptance criteria

- `mc-scanner --export` on the 26.3 test world (`test-data/worlds/26.3/`) produces `export.litematic`, `export.nbt`, `manifest.csv`, and `totals.csv`, and prints a summary with total item count and barrels.
- Pre-1.20.5 input is rejected with a clear message.

## Comments

_None yet._
