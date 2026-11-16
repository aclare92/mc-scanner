package de.skyrising.mc.scanner

import java.util.UUID
import kotlin.math.ceil
import kotlin.math.sqrt

/** The five export grids, in placement order. */
enum class ExportGrid(val label: String) {
    PLAYER("player"),
    IDENTITY("identity"),
    BULK("bulk"),
    REMAINDER("remainder"),
    UNSTACKABLE("unstackable"),
}

private const val SHULKER_SLOTS = 27
private const val BARREL_SLOTS = 27

/** Empty rows between grids so the sections read as separate areas. */
private const val SECTION_GAP_ROWS = 2

private const val INVENTORY_SLOT_COUNT = 41
private const val ENDER_CHEST_SLOT_COUNT = 27
private const val PLAYER_BARREL_Z = 0
private const val PLAYER_SIGN_Z = 1
private val INVENTORY_SECTION_A = 0..26
private val INVENTORY_SECTION_B = 27..40
private val ENDER_CHEST_SECTION = 0..26

/** Generated packaging that is not bulk-ready. */
private const val PLAIN_BOX_ID = "minecraft:shulker_box"

/** Bulk-ready boxes are always grey so the bulk section is visually uniform. */
private const val BULK_GRID_BOX_ID = "minecraft:gray_shulker_box"

private data class Group(
    val id: String,
    val nbt: String,
    val template: Map<String, Tag>,
    var count: Long,
)

/** Non-player packing, after excluded items and plain-box unpacking. */
data class NonPlayerPacking(
    val identityBarrels: List<PlacedBarrel>,
    val bulkBarrels: List<PlacedBarrel>,
    val remainderBarrels: List<PlacedBarrel>,
    val unstackableBarrels: List<PlacedBarrel>,
    val totalBoxes: Int,
    val totalItems: Long,
) {
    val barrels: List<PlacedBarrel>
        get() = identityBarrels + bulkBarrels + remainderBarrels + unstackableBarrels
}

/** One player's source-mirror section in the player grid. */
data class PlayerSection(
    val uuid: UUID,
    val displayName: String,
    val barrels: List<PlacedBarrel>,
    val sign: PlacedSign?,
)

/** Player-grid packing result. */
data class PlayerPacking(
    val sections: List<PlayerSection>,
) {
    val barrels: List<PlacedBarrel> get() = sections.flatMap { it.barrels }
    val signs: List<PlacedSign> get() = sections.mapNotNull { it.sign }

    /** The first row available to the next grid. */
    val nextZ: Int
        get() = if (sections.isEmpty()) 0 else PLAYER_BARREL_Z + 1 + SECTION_GAP_ROWS
}

/** Everything needed to write the schematic and manifest. */
data class ExportLayout(
    val player: PlayerPacking,
    val nonPlayer: NonPlayerPacking,
) {
    val barrels: List<PlacedBarrel> get() = player.barrels + nonPlayer.barrels
    val signs: List<PlacedSign> get() = player.signs
}

private fun buildStack(template: Map<String, Tag>, count: Int): CompoundTag =
    CompoundTag(template.toMutableMap().apply { put("count", IntTag(count)) })

/** Builds a generated box holding [slots] contiguously from slot 0. */
private fun makeBox(slots: List<CompoundTag>, id: String): CompoundTag {
    val entries = slots.mapIndexed { slot, stack ->
        CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to stack))
    }
    return CompoundTag(mutableMapOf(
        "id" to StringTag(id),
        "count" to IntTag(1),
        "components" to CompoundTag(mutableMapOf("minecraft:container" to ListTag(entries))),
    ))
}

/** Lays [items] out as adjacent barrels in a near-square grid starting at [startZ]. */
private fun packGrid(items: List<CompoundTag>, startZ: Int): List<PlacedBarrel> {
    val chunks = items.chunked(BARREL_SLOTS)
    if (chunks.isEmpty()) return emptyList()
    // Near-square rectangle; X is the long side. Adjacent barrels, no air gaps.
    val columns = ceil(sqrt(chunks.size.toDouble())).toInt()
    return chunks.mapIndexed { i, chunk ->
        PlacedBarrel(i % columns, 0, startZ + i / columns, chunk)
    }
}

private fun isBulkStack(stack: CompoundTag, variant: String, max: Int, version: Int): Boolean =
    stack.has("id", Tag.STRING) &&
        itemStackNbt(stack) == variant &&
        itemCount(stack, version) == max.toLong()

/**
 * Packs non-player items into the identity, bulk, remainder, and unstackable
 * grids. The input must already have excluded items removed and plain boxes
 * unpacked.
 */
fun packNonPlayer(
    items: List<CompoundTag>,
    version: Int = DataVersion.COMPONENTS_REWORK,
    startZ: Int = 0,
): NonPlayerPacking {
    val identityGroups = LinkedHashMap<Pair<String, String>, Group>()
    val groups = LinkedHashMap<Pair<String, String>, Group>()
    var totalItems = 0L

    for (item in items) {
        if (!item.has("id", Tag.STRING)) continue
        val id = item.getString("id")
        val count = itemCount(item, version)
        if (count <= 0) continue
        totalItems += count
        val template = item.filterKeys { it != "count" && it != "Count" && it != "Slot" && it != "slot" }
        val nbt = itemStackNbt(item)
        val target = if (isShulkerBoxId(id)) identityGroups else groups
        val group = target.getOrPut(id to nbt) { Group(id, nbt, template, 0) }
        group.count += count
    }

    val identityBoxes = mutableListOf<CompoundTag>()
    for (group in identityGroups.values.sortedWith(compareBy({ it.id }, { it.nbt }))) {
        repeat(group.count.toInt()) { identityBoxes += buildStack(group.template, 1) }
    }

    val bulkBoxes = mutableListOf<CompoundTag>()
    val remainderStacks = mutableListOf<CompoundTag>()
    val unstackableStacks = mutableListOf<CompoundTag>()

    for (group in groups.values.sortedWith(compareBy({ it.id }, { it.nbt }))) {
        val max = maxStackSize(group.id)
        var remaining = group.count
        if (max <= 1) {
            while (remaining > 0) {
                unstackableStacks += buildStack(group.template, 1)
                remaining--
            }
            continue
        }

        val stacks = mutableListOf<CompoundTag>()
        while (remaining > 0) {
            val chunk = minOf(remaining, max.toLong()).toInt()
            stacks += buildStack(group.template, chunk)
            remaining -= chunk
        }
        for (chunk in stacks.chunked(SHULKER_SLOTS)) {
            if (chunk.size == SHULKER_SLOTS && chunk.all { isBulkStack(it, group.nbt, max, version) }) {
                bulkBoxes += makeBox(chunk, BULK_GRID_BOX_ID)
            } else {
                remainderStacks += chunk
            }
        }
    }

    val remainderBoxes = remainderStacks.chunked(SHULKER_SLOTS).map { makeBox(it, PLAIN_BOX_ID) }
    val unstackableBoxes = unstackableStacks.chunked(SHULKER_SLOTS).map { makeBox(it, PLAIN_BOX_ID) }

    var nextZ = startZ
    fun place(boxes: List<CompoundTag>): List<PlacedBarrel> {
        val placed = packGrid(boxes, startZ = nextZ)
        if (placed.isNotEmpty()) nextZ = placed.maxOf { it.z } + 1 + SECTION_GAP_ROWS
        return placed
    }

    val identityBarrels = place(identityBoxes)
    val bulkBarrels = place(bulkBoxes)
    val remainderBarrels = place(remainderBoxes)
    val unstackableBarrels = place(unstackableBoxes)
    val totalBoxes = identityBarrels.sumOf { it.items.size } +
        bulkBarrels.sumOf { it.items.size } +
        remainderBarrels.sumOf { it.items.size } +
        unstackableBarrels.sumOf { it.items.size }

    return NonPlayerPacking(
        identityBarrels = identityBarrels,
        bulkBarrels = bulkBarrels,
        remainderBarrels = remainderBarrels,
        unstackableBarrels = unstackableBarrels,
        totalBoxes = totalBoxes,
        totalItems = totalItems,
    )
}

private fun signLines(label: String, isUuid: Boolean): List<String> {
    val width = if (isUuid) 12 else 13
    return label.chunked(width).take(4).ifEmpty { listOf("") }
}

/**
 * Packs player items as a source mirror: one contiguous section per player, with
 * up to three sectioned barrels and one empty barrel between players. Items are
 * placed in their canonical source slots; no packaging or unpacking happens here.
 */
fun packPlayerSections(
    items: List<PlayerItem>,
    names: Map<UUID, String>,
): PlayerPacking {
    if (items.isEmpty()) return PlayerPacking(emptyList())

    val byPlayer = items.groupBy { it.uuid }
    val ordered = byPlayer.entries.sortedWith(
        compareBy<Map.Entry<UUID, List<PlayerItem>>> {
            names[it.key]?.lowercase() ?: "\uFFFF${it.key}"
        }.thenBy { it.key.toString() }
    )

    val sections = mutableListOf<PlayerSection>()
    var nextX = 0

    for ((uuid, playerItems) in ordered) {
        val displayName = names[uuid] ?: uuid.toString()
        val inventory = arrayOfNulls<PlayerItem>(INVENTORY_SLOT_COUNT)
        val ender = arrayOfNulls<PlayerItem>(ENDER_CHEST_SLOT_COUNT)
        for (item in playerItems) {
            if (item.source == PlayerSource.INVENTORY) {
                if (item.slot in inventory.indices) inventory[item.slot] = item
            } else {
                if (item.slot in ender.indices) ender[item.slot] = item
            }
        }

        val barrels = mutableListOf<PlacedBarrel>()
        fun addSection(source: PlayerSource, sourceSlots: IntRange, barrelSlotOffset: Int) {
            val present = sourceSlots.mapNotNull { slot ->
                val item = if (source == PlayerSource.INVENTORY) inventory[slot] else ender[slot]
                if (item == null) null else slot to item
            }
            if (present.isEmpty()) return
            barrels += PlacedBarrel(
                x = nextX++, y = 0, z = PLAYER_BARREL_Z,
                items = present.map { it.second.item },
                slots = present.map { it.first - barrelSlotOffset },
                meta = present.map {
                    PlacedItemMeta(
                        player = displayName,
                        source = source,
                        sourceSlot = it.first,
                    )
                },
            )
        }

        addSection(PlayerSource.INVENTORY, INVENTORY_SECTION_A, 0)
        addSection(PlayerSource.INVENTORY, INVENTORY_SECTION_B, 27)
        addSection(PlayerSource.ENDER_CHEST, ENDER_CHEST_SECTION, 0)
        if (barrels.isEmpty()) continue

        val firstX = barrels.first().x
        val sign = PlacedSign(
            x = firstX, y = 0, z = PLAYER_SIGN_Z,
            lines = signLines(displayName, isUuid = names[uuid] == null),
        )
        sections += PlayerSection(uuid, displayName, barrels, sign)
        nextX++ // one empty barrel between player sections
    }

    return PlayerPacking(sections)
}
