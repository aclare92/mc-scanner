package scripts

import de.skyrising.mc.scanner.*
import java.util.Collections

val items = Collections.synchronizedList(mutableListOf<CompoundTag>())
val playerItems = Collections.synchronizedList(mutableListOf<PlayerItem>())
val versions = Collections.synchronizedSet(mutableSetOf<Int>())
val failures = Collections.synchronizedList(mutableListOf<String>())

scan {
    try {
        for (source in itemSources()) {
            versions.add(source.version)
            if (source.location is PlayerInventory) {
                playerItems.addAll(dumpPlayerItems(source))
            } else {
                items.addAll(dumpItemStacksRaw(source.items))
            }
        }
    } catch (e: Exception) {
        failures.add("$this: ${e.message}")
    }
    emptyList<SearchResult>()
}

after {
    if (failures.isNotEmpty()) {
        System.err.println("Export aborted: ${failures.size} file(s) failed to scan; refusing to write an incomplete schematic.")
        for (failure in failures) System.err.println("  $failure")
        throw IllegalStateException("export scan failures")
    }

    val minVersion = versions.minOrNull() ?: DataVersion.COMPONENTS_REWORK
    val maxVersion = versions.maxOrNull() ?: DataVersion.COMPONENTS_REWORK
    if (minVersion < DataVersion.COMPONENTS_REWORK) {
        throw IllegalStateException(
            "Export requires a 1.20.5+ source world (data version >= ${DataVersion.COMPONENTS_REWORK}), found $minVersion"
        )
    }
    if (versions.size > 1) {
        System.err.println("Export: mixed data versions $versions; stamping schematic with $maxVersion")
    }
    val dataVersion = maxVersion

    val scannedTotals = countItems(items + playerItems.map { it.item }, dataVersion)

    val filteredPlayer = playerItems.mapNotNull { playerItem ->
        filterExcludedItem(playerItem.item)?.let { playerItem.copy(item = it) }
    }
    val filteredNonPlayer = filterExcludedItems(items)
    val unpackedNonPlayer = unpackPlainBoxes(filteredNonPlayer)
    val exportedTotals = countItems(filteredPlayer.map { it.item } + unpackedNonPlayer, dataVersion)

    val playerUuids = filteredPlayer.map { it.uuid }.toSet()
    val names = resolvePlayerNames(inputPath, playerUuids)
    val unnamed = playerUuids.count { it !in names }
    if (unnamed > 0) {
        println("Export: $unnamed player(s) without a resolved name; using UUID labels.")
    }
    val playerPacking = packPlayerSections(filteredPlayer, names)
    val nonPlayerPacking = packNonPlayer(unpackedNonPlayer, dataVersion, startZ = playerPacking.nextZ)
    val layout = ExportLayout(playerPacking, nonPlayerPacking)

    val schematic = Schematic(
        layout.barrels, dataVersion,
        name = "mc-scanner export", author = "mc-scanner",
        description = "Items exported from world for migration",
        signs = layout.signs,
    )
    schematic.writeLitematica(outPath.resolve("export.litematic"))
    schematic.writeVanillaStructure(outPath.resolve("export.nbt"))
    writeManifest(buildManifestRows(layout, dataVersion), outPath.resolve("manifest.csv"))
    writeTotals(buildTotals(scannedTotals, exportedTotals), outPath.resolve("totals.csv"))

    println("Export: ${layout.nonPlayer.totalItems} non-player item(s) in ${layout.nonPlayer.totalBoxes} shulker box(es) across ${layout.nonPlayer.barrels.size} barrel(s).")
    println("Export: player grid: ${layout.player.sections.size} section(s), ${layout.player.barrels.size} barrel(s), ${layout.player.signs.size} sign(s).")

    fun printGrid(label: String, barrels: List<PlacedBarrel>) {
        if (barrels.isEmpty()) return
        val minX = barrels.minOf { it.x }; val maxX = barrels.maxOf { it.x }
        val minZ = barrels.minOf { it.z }; val maxZ = barrels.maxOf { it.z }
        val boxes = barrels.sumOf { it.items.size }
        println("Export: $label: $boxes box(es) in ${barrels.size} barrel(s), x=$minX..$maxX, z=$minZ..$maxZ")
    }
    printGrid("identity", layout.nonPlayer.identityBarrels)
    printGrid("bulk", layout.nonPlayer.bulkBarrels)
    printGrid("remainder", layout.nonPlayer.remainderBarrels)
    printGrid("unstackable", layout.nonPlayer.unstackableBarrels)
}
