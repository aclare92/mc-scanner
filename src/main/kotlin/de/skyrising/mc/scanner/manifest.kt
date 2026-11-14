package de.skyrising.mc.scanner

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/** One item placed inside a shulker box, located by its barrel and box slot. */
data class ManifestRow(
    val barrelX: Int,
    val barrelZ: Int,
    val shulkerSlot: Int,
    val itemId: String,
    val count: Long,
    val nbt: String,
)

/** The differences between the report's totals and the export's totals. */
data class TotalsDiff(
    val onlyInReport: Set<String>,
    val onlyInExport: Set<String>,
    val countMismatches: List<Pair<String, Pair<Long, Long>>>,
) {
    val isEmpty get() = onlyInReport.isEmpty() && onlyInExport.isEmpty() && countMismatches.isEmpty()
}

/** Enumerates the items directly inside each placed shulker box. */
fun manifestRows(barrels: List<PlacedBarrel>, version: Int): List<ManifestRow> {
    val rows = mutableListOf<ManifestRow>()
    for (barrel in barrels) {
        barrel.items.forEachIndexed { shulkerSlot, box ->
            if (!box.has("components", Tag.COMPOUND)) return@forEachIndexed
            val components = box.getCompound("components")
            if (!components.has("minecraft:container", Tag.LIST)) return@forEachIndexed
            for (entry in components.getList<CompoundTag>("minecraft:container")) {
                if (!entry.has("item", Tag.COMPOUND)) continue
                val item = entry.getCompound("item")
                if (!item.has("id", Tag.STRING)) continue
                rows += ManifestRow(
                    barrel.x, barrel.z, shulkerSlot,
                    item.getString("id"), itemCount(item, version), itemStackNbt(item),
                )
            }
        }
    }
    return rows
}

/**
 * Totals for the source items, recursing through nested containers. Keyed the way
 * the report keys `ItemType` (id plus non-zero damage), computed from the input
 * stacks so generated packaging is not counted.
 */
fun exportTotals(items: List<CompoundTag>, version: Int): Map<String, Long> {
    val totals = LinkedHashMap<String, Long>()
    fun walk(tag: CompoundTag) {
        if (!tag.has("id", Tag.STRING)) return
        val key = itemKey(tag, version)
        totals[key] = (totals[key] ?: 0L) + itemCount(tag, version)
        for (nested in nestedItemLists(tag, version)) for (slot in nested) walk(slot)
    }
    for (item in items) walk(item)
    return totals
}

/**
 * Mirrors the report's counting rule: an item that holds contents is not counted
 * itself, only its contents. Used only for the reconciliation diff, because the
 * report omits container items (shulker boxes, bundles, charged crossbows) that
 * have contents.
 */
fun reportEquivalentTotals(items: List<CompoundTag>, version: Int): Map<String, Long> {
    val totals = LinkedHashMap<String, Long>()
    fun walk(tag: CompoundTag) {
        if (!tag.has("id", Tag.STRING)) return
        val nested = nestedItemLists(tag, version)
        if (nested.isEmpty()) {
            val key = itemKey(tag, version)
            totals[key] = (totals[key] ?: 0L) + itemCount(tag, version)
        }
        for (list in nested) for (slot in list) walk(slot)
    }
    for (item in items) walk(item)
    return totals
}

/** The report's item identity: id, plus `.damage` when the item has non-zero damage. */
private fun itemKey(tag: CompoundTag, version: Int): String {
    val id = Identifier.of(tag.getString("id"))
    val damage = when {
        version >= DataVersion.COMPONENTS_REWORK && tag.has("components", Tag.COMPOUND) -> {
            val components = tag.getCompound("components")
            if (components.has("minecraft:damage", Tag.INTEGER)) components.getInt("minecraft:damage") else null
        }
        tag.has("Damage", Tag.INTEGER) -> tag.getInt("Damage")
        else -> null
    }
    return ItemType(id, damage ?: -1).flatten().format()
}

fun diffTotals(report: Map<String, Long>, export: Map<String, Long>): TotalsDiff {
    val onlyInReport = report.keys - export.keys
    val onlyInExport = export.keys - report.keys
    val mismatches = (report.keys intersect export.keys)
        .mapNotNull { id ->
            val r = report.getValue(id)
            val e = export.getValue(id)
            if (r != e) id to (r to e) else null
        }
        .sortedBy { it.first }
    return TotalsDiff(onlyInReport, onlyInExport, mismatches)
}

private fun csv(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        if (c == '"') sb.append('"')
        sb.append(c)
    }
    return sb.append('"').toString()
}

fun writeManifest(rows: List<ManifestRow>, path: Path) {
    PrintStream(Files.newOutputStream(path), false, "UTF-8").use { out ->
        out.println("barrel_x,barrel_z,shulker_slot,item_id,count,nbt_variant")
        for (row in rows) {
            out.print(row.barrelX); out.print(',')
            out.print(row.barrelZ); out.print(',')
            out.print(row.shulkerSlot); out.print(',')
            out.print(row.itemId); out.print(',')
            out.print(row.count); out.print(',')
            out.println(csv(row.nbt))
        }
    }
}

fun writeTotals(totals: Map<String, Long>, path: Path) {
    PrintStream(Files.newOutputStream(path), false, "UTF-8").use { out ->
        out.println("Type,Total")
        for ((id, count) in totals.entries.sortedBy { it.key }) {
            out.print(id); out.print(','); out.println(count)
        }
    }
}

/** Parses a report `counts.csv` (Type,Total,Number of Inventories) into a totals map. */
fun readCountsCsv(path: Path): Map<String, Long> {
    val totals = LinkedHashMap<String, Long>()
    Files.newBufferedReader(path).useLines { lines ->
        lines.drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val cols = line.split(',')
            if (cols.size < 2) return@forEach
            totals[cols[0]] = cols[1].toLongOrNull() ?: return@forEach
        }
    }
    return totals
}
