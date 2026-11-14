package scripts

import de.skyrising.mc.scanner.*
import java.nio.file.Files
import java.util.Collections

val items = Collections.synchronizedList(mutableListOf<CompoundTag>())
val versions = Collections.synchronizedSet(mutableSetOf<Int>())
val failures = Collections.synchronizedList(mutableListOf<String>())

scan {
    try {
        for (source in itemSources()) {
            versions.add(source.version)
            items.addAll(dumpItemStacksRaw(source.items))
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

    val packed = packIntoBarrels(items, dataVersion)
    val schematic = Schematic(
        packed.barrels, dataVersion,
        name = "mc-scanner export", author = "mc-scanner",
        description = "Items exported from world for migration",
    )
    schematic.writeLitematica(outPath.resolve("export.litematic"))
    schematic.writeVanillaStructure(outPath.resolve("export.nbt"))
    writeManifest(manifestRows(packed.barrels, dataVersion), outPath.resolve("manifest.csv"))

    val totals = exportTotals(items, dataVersion)
    writeTotals(totals, outPath.resolve("totals.csv"))

    println("Export: ${packed.totalItems} items in ${packed.totalShulkerBoxes} shulker box(es) across ${packed.barrels.size} barrel(s).")

    val countsFile = outPath.resolve("counts.csv")
    if (Files.exists(countsFile)) {
        // Compare against a report-equivalent total: the report does not count an item
        // that holds contents (it counts the contents instead), so a "complete" total
        // would always differ for filled containers.
        val diff = diffTotals(readCountsCsv(countsFile), reportEquivalentTotals(items, dataVersion))
        if (diff.isEmpty) {
            println("Export: losslessness check passed (totals reconcile with counts.csv).")
        } else {
            System.err.println("Export: losslessness check found discrepancies")
            if (diff.onlyInReport.isNotEmpty()) System.err.println("  missing from export: ${diff.onlyInReport}")
            if (diff.onlyInExport.isNotEmpty()) System.err.println("  unexpected in export: ${diff.onlyInExport}")
            for ((id, counts) in diff.countMismatches) System.err.println("  $id: report=${counts.first} export=${counts.second}")
        }
    }
}
