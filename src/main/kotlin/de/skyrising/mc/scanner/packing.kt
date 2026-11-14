package de.skyrising.mc.scanner

/** The result of packing items into a flat grid of barrels. */
data class PackingResult(
    val barrels: List<PlacedBarrel>,
    /** Total shulker-box items placed (export boxes + pass-through shulkers). */
    val totalShulkerBoxes: Int,
    /** Total top-level items packed (input counts summed, not recursed). */
    val totalItems: Long,
)

private const val SHULKER_SLOTS = 27
private const val BARREL_SLOTS = 27

private data class Group(
    val id: String,
    val nbt: String,
    val template: Map<String, Tag>,
    var count: Long,
)

/** Groups identical (id, NBT) stacks so they can be packed together, totals summed. */
private fun groupItems(items: List<CompoundTag>, version: Int): List<Group> {
    val groups = LinkedHashMap<Pair<String, String>, Group>()
    for (item in items) {
        if (!item.has("id", Tag.STRING)) continue
        val id = item.getString("id")
        val nbt = itemStackNbt(item)
        val count = itemCount(item, version)
        if (count <= 0) continue
        val group = groups.getOrPut(id to nbt) {
            val template = item.filterKeys { it != "count" && it != "Count" && it != "Slot" && it != "slot" }
            Group(id, nbt, template, 0)
        }
        group.count += count
    }
    return groups.values.sortedWith(compareBy({ it.id }, { it.nbt }))
}

private fun buildStack(template: Map<String, Tag>, count: Int): CompoundTag =
    CompoundTag(template.toMutableMap().apply { put("count", IntTag(count)) })

private fun isShulkerBox(id: String): Boolean = id.substringAfter(':').endsWith("shulker_box")

/**
 * Packs [items] (top-level item stacks with full NBT) into shulker boxes, then
 * barrels on a flat grid. Existing shulker-box items are never nested inside an
 * export box; they pass through directly to a barrel. Items are sorted by id and
 * NBT variant, and greedily filled respecting max stack size.
 */
fun packIntoBarrels(
    items: List<CompoundTag>,
    version: Int = DataVersion.COMPONENTS_REWORK,
    rowLength: Int = 16,
): PackingResult {
    require(rowLength > 0) { "rowLength must be positive" }
    val groups = groupItems(items, version)

    val exportBoxes = mutableListOf<CompoundTag>()
    val directShulkers = mutableListOf<CompoundTag>()
    var totalItems = 0L

    val boxSlots = mutableListOf<CompoundTag>()
    fun flushBox() {
        if (boxSlots.isEmpty()) return
        val entries = boxSlots.mapIndexed { slot, stack ->
            CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to stack))
        }
        exportBoxes += CompoundTag(mutableMapOf(
            "id" to StringTag("minecraft:shulker_box"),
            "count" to IntTag(1),
            "components" to CompoundTag(mutableMapOf("minecraft:container" to ListTag(entries))),
        ))
        boxSlots.clear()
    }

    for (group in groups) {
        totalItems += group.count
        if (isShulkerBox(group.id)) {
            var remaining = group.count
            while (remaining > 0) {
                directShulkers += buildStack(group.template, 1)
                remaining--
            }
            continue
        }
        val max = maxStackSize(group.id)
        var remaining = group.count
        while (remaining > 0) {
            val chunk = minOf(remaining, max.toLong()).toInt()
            boxSlots += buildStack(group.template, chunk)
            remaining -= chunk
            if (boxSlots.size == SHULKER_SLOTS) flushBox()
        }
    }
    flushBox()

    val barrelItems = exportBoxes + directShulkers
    val barrels = barrelItems.chunked(BARREL_SLOTS).mapIndexed { i, chunk ->
        PlacedBarrel((i % rowLength) * 2, 0, (i / rowLength) * 2, chunk)
    }
    return PackingResult(barrels, barrelItems.size, totalItems)
}
