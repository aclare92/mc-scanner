package de.skyrising.mc.scanner

import java.io.PrintStream
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

enum class ManifestKind(val label: String) {
    ITEM("item"),
    BOX("box"),
}

/** One manifest row: an item or a source box, located by barrel and slot. */
data class ManifestRow(
    val kind: ManifestKind,
    val grid: ExportGrid,
    val player: String?,
    val source: PlayerSource?,
    val barrelX: Int,
    val barrelZ: Int,
    val barrelSlot: Int,
    val boxSlot: Int?,
    val sourceSlot: Int?,
    val itemId: String,
    val count: Long,
    val nbt: String,
)

/** One row of the scan-vs-export totals file. */
data class TotalsRow(val type: String, val scanned: Long, val exported: Long, val dropped: Long)

/** A box's identity NBT (name, lore, …) without its contents. */
private fun boxNbt(box: CompoundTag): String {
    val filtered = box.value
        .filterKeys { it != "id" && it != "count" && it != "Count" && it != "Slot" && it != "slot" }
        .toMutableMap()
    val identity = boxIdentityComponents(box)
    if (identity == null) filtered.remove("components") else filtered["components"] = identity
    val sw = StringWriter()
    CompoundTag(filtered).writeSnbt(sw)
    return sw.toString()
}

private fun containerSlots(box: CompoundTag): List<Pair<Int, CompoundTag>> {
    val components = box["components"] as? CompoundTag ?: return emptyList()
    val container = components["minecraft:container"] as? ListTag<*> ?: return emptyList()
    return container.mapIndexedNotNull { index, raw ->
        val entry = raw as? CompoundTag ?: return@mapIndexedNotNull null
        val item = entry["item"] as? CompoundTag ?: return@mapIndexedNotNull null
        if (!item.has("id", Tag.STRING)) return@mapIndexedNotNull null
        val slot = if (entry.has("slot", Tag.INTEGER)) entry.getInt("slot") else index
        slot to item
    }
}

private fun itemRow(
    kind: ManifestKind,
    grid: ExportGrid,
    player: String?,
    source: PlayerSource?,
    barrel: PlacedBarrel,
    barrelSlot: Int,
    boxSlot: Int?,
    sourceSlot: Int?,
    item: CompoundTag,
    version: Int,
): ManifestRow = ManifestRow(
    kind = kind,
    grid = grid,
    player = player,
    source = source,
    barrelX = barrel.x,
    barrelZ = barrel.z,
    barrelSlot = barrelSlot,
    boxSlot = boxSlot,
    sourceSlot = sourceSlot,
    itemId = item.getString("id"),
    count = itemCount(item, version),
    nbt = itemStackNbt(item),
)

private fun contentRows(
    rows: MutableList<ManifestRow>,
    grid: ExportGrid,
    player: String?,
    barrel: PlacedBarrel,
    barrelSlot: Int,
    box: CompoundTag,
    version: Int,
) {
    for ((slot, item) in containerSlots(box)) {
        rows += itemRow(
            kind = ManifestKind.ITEM, grid = grid, player = player, source = null,
            barrel = barrel, barrelSlot = barrelSlot, boxSlot = slot, sourceSlot = null,
            item = item, version = version,
        )
    }
}

private fun sourceBoxRows(
    rows: MutableList<ManifestRow>,
    grid: ExportGrid,
    player: String?,
    source: PlayerSource?,
    sourceSlot: Int?,
    barrel: PlacedBarrel,
    barrelSlot: Int,
    box: CompoundTag,
    version: Int,
) {
    rows += ManifestRow(
        kind = ManifestKind.BOX, grid = grid, player = player, source = source,
        barrelX = barrel.x, barrelZ = barrel.z, barrelSlot = barrelSlot,
        boxSlot = null, sourceSlot = sourceSlot,
        itemId = box.getString("id"), count = itemCount(box, version), nbt = boxNbt(box),
    )
    contentRows(rows, grid, player, barrel, barrelSlot, box, version)
}

/** Builds the single manifest for all five grids. */
fun buildManifestRows(layout: ExportLayout, version: Int): List<ManifestRow> {
    val rows = mutableListOf<ManifestRow>()

    for (section in layout.player.sections) {
        for (barrel in section.barrels) {
            barrel.items.forEachIndexed { index, item ->
                val meta = barrel.metaAt(index)
                val barrelSlot = barrel.slotAt(index)
                val player = meta.player ?: section.displayName
                if (isShulkerBoxId(item.getString("id"))) {
                    sourceBoxRows(
                        rows, ExportGrid.PLAYER, player, meta.source, meta.sourceSlot,
                        barrel, barrelSlot, item, version,
                    )
                } else {
                    rows += itemRow(
                        kind = ManifestKind.ITEM, grid = ExportGrid.PLAYER, player = player,
                        source = meta.source, barrel = barrel, barrelSlot = barrelSlot,
                        boxSlot = null, sourceSlot = meta.sourceSlot, item = item, version = version,
                    )
                }
            }
        }
    }

    for (barrel in layout.nonPlayer.identityBarrels) {
        barrel.items.forEachIndexed { index, box ->
            sourceBoxRows(
                rows, ExportGrid.IDENTITY, null, null, null,
                barrel, barrel.slotAt(index), box, version,
            )
        }
    }

    for (barrel in layout.nonPlayer.bulkBarrels) {
        barrel.items.forEachIndexed { index, box ->
            contentRows(rows, ExportGrid.BULK, null, barrel, barrel.slotAt(index), box, version)
        }
    }

    for (barrel in layout.nonPlayer.remainderBarrels) {
        barrel.items.forEachIndexed { index, box ->
            contentRows(rows, ExportGrid.REMAINDER, null, barrel, barrel.slotAt(index), box, version)
        }
    }

    for (barrel in layout.nonPlayer.unstackableBarrels) {
        barrel.items.forEachIndexed { index, box ->
            contentRows(rows, ExportGrid.UNSTACKABLE, null, barrel, barrel.slotAt(index), box, version)
        }
    }

    return rows
}

/**
 * Totals for source items, recursing through nested containers. Empty boxes are
 * counted here; the export's Scanned total is deliberately raw.
 */
fun countItems(items: List<CompoundTag>, version: Int): Map<String, Long> {
    val totals = LinkedHashMap<String, Long>()
    fun walk(tag: CompoundTag) {
        if (!tag.has("id", Tag.STRING)) return
        val key = itemKey(tag, version)
        totals[key] = (totals[key] ?: 0L) + itemCount(tag, version)
        for (nested in nestedItemLists(tag, version, countLoot = false)) for (slot in nested) walk(slot)
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

fun buildTotals(scanned: Map<String, Long>, exported: Map<String, Long>): List<TotalsRow> {
    val types = (scanned.keys + exported.keys).sorted()
    return types.map { type ->
        val s = scanned[type] ?: 0L
        val e = exported[type] ?: 0L
        TotalsRow(type, s, e, s - e)
    }
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
        out.println("kind,grid,player,source,barrel_x,barrel_z,barrel_slot,box_slot,source_slot,item_id,count,nbt_variant")
        for (row in rows) {
            out.print(row.kind.label); out.print(',')
            out.print(row.grid.label); out.print(',')
            out.print(csv(row.player ?: "")); out.print(',')
            out.print(row.source?.label ?: ""); out.print(',')
            out.print(row.barrelX); out.print(',')
            out.print(row.barrelZ); out.print(',')
            out.print(row.barrelSlot); out.print(',')
            out.print(row.boxSlot?.toString() ?: ""); out.print(',')
            out.print(row.sourceSlot?.toString() ?: ""); out.print(',')
            out.print(row.itemId); out.print(',')
            out.print(row.count); out.print(',')
            out.println(csv(row.nbt))
        }
    }
}

fun writeTotals(rows: List<TotalsRow>, path: Path) {
    PrintStream(Files.newOutputStream(path), false, "UTF-8").use { out ->
        out.println("Type,Scanned,Exported,Dropped")
        for (row in rows) {
            out.print(row.type); out.print(',')
            out.print(row.scanned); out.print(',')
            out.print(row.exported); out.print(',')
            out.println(row.dropped)
        }
    }
}
