# mc-scanner

Kotlin/JVM CLI that scans Minecraft world or region files for blocks and items, and collects inventory statistics. Ships as a single fat jar.

## Build and test

- Requires JDK **21** (the `kotlin` toolchain targets 21). Gradle 9.2.1 needs Java 17+ to run. This checkout's machine had no JDK on `PATH`; I used a downloaded Temurin 21 to build.
- `./gradlew build` — compile, run tests, and assemble.
- `./gradlew test` — tests only.
- Single test: `./gradlew test --tests "de.skyrising.mc.scanner.zlib.TestDeflate.smartHuffmanSimple"`
- Runnable fat jar: `./gradlew shadowJar` → `build/libs/mc-scanner-<version>.jar`. Version comes from `gradle.properties` (currently `0.6.0`). `shadowJar` also triggers `distTar`/`distZip`.
- Run from Gradle: `./gradlew run --args="-i minecraft:sponge -b minecraft:sponge <world-dir> out.zip"`

## Architecture

- `scanner.kt` is the CLI entrypoint (`main`): parses options, picks a scan script, builds the input file list, then scans files in parallel.
- Scan behaviour is not in compiled Kotlin classes. It lives in Kotlin scripts at `src/main/resources/scripts/*.scan.kts`, evaluated at runtime via `kotlin-scripting-jvm-host`. Mode dispatch: `--stats` → `stats.scan.kts`, `--geode` → `geode.scan.kts`, a first non-option arg ending in `.scan.kts` → that file, otherwise `search.scan.kts`.
- A script configures the `Scan` object through `collect`, `scan`, `onResults`, and `after`. Only types listed in `ScannerScriptConfiguration.defaultImports` are in scope; add new ones there.
- Core packages: `nbt.kt` (NBT tags), `region/` (`.mca` reader), `region.kt` (chunk scanning), `needles.kt` (query targets `BlockState`, `BlockIdMask`, `ItemType`, plus flattening maps), `zlib/` (bundled inflate), `inventories.kt` (player and inventory scanning).
- Input is either one `.mca` file or a world directory. `getHaystack` reads `playerdata/*.dat`, `region/`, `DIM-1/region/`, and `DIM1/region/`.
- Older worlds are deliberately supported. `constants.kt` holds `DataVersion` thresholds; parsing branches for pre-1.13 numeric IDs, packed block states, the 1.18 `block_states` container, and the `Level` wrapper removal. Keep every branch working.
- `buildSrc/.../gen/random-ticks.kt` generates `build/generated/kotlin/.../random-ticks.kt` on every `compileKotlin` (random-tick geometry constants used by `geode.scan.kts`). Edit the generator, never the generated file.
- Custom inflate is the default (`DECOMPRESSOR = INTERNAL`); `--decompressor java` switches to `java.util.zip`. `zlib/` is the only code with tests.

## Gotchas

- Per-file scan exceptions are caught and printed, but the process still exits 0. Do not infer success from exit status.
- Adding a built-in mode requires a new resource script **and** flag wiring in `main`.
- `src/main/resources/flattening/{block_states,items}.json` are static legacy-ID → modern-ID maps, not build output.
- No CI, lint, formatter, or typecheck task exists; `./gradlew build` is the whole gate.
- Local issue tracker: specs and tickets live under `.scratch/` (see `docs/agents/issue-tracker.md`).

## Agent skills

### Issue tracker

Issues live as markdown files under `.scratch/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
