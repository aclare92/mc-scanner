package de.skyrising.mc.scanner

import java.util.UUID

/** Operator/command-only items removed from the export. */
val EXCLUDED_ITEM_IDS: Set<String> = setOf(
    "minecraft:command_block",
    "minecraft:chain_command_block",
    "minecraft:repeating_command_block",
    "minecraft:command_block_minecart",
    "minecraft:structure_block",
    "minecraft:jigsaw",
    "minecraft:debug_stick",
    "minecraft:knowledge_book",
    "minecraft:test_block",
    "minecraft:test_instance_block",
    "minecraft:barrier",
    "minecraft:light",
    "minecraft:structure_void",
)

enum class PlayerSource(val label: String) {
    INVENTORY("inventory"),
    ENDER_CHEST("ender_chest"),
}

/** A top-level item stack sourced from a player's inventory or ender chest. */
data class PlayerItem(
    val uuid: UUID,
    val source: PlayerSource,
    val slot: Int,
    val item: CompoundTag,
)

fun isShulkerBoxId(id: String): Boolean = id.substringAfter(':').endsWith("shulker_box")

private fun boxContents(box: Map<String, Tag>): List<CompoundTag>? {
    val components = box["components"] as? CompoundTag ?: return null
    val container = components["minecraft:container"] as? ListTag<*> ?: return null
    return container.mapNotNull { entry -> (entry as? CompoundTag)?.get("item") as? CompoundTag }
}

/** The canonical player-inventory slot for a raw player-data Slot value. */
fun canonicalInventorySlot(raw: Int): Int? = when (raw) {
    in 0..35 -> raw
    in 100..103 -> 36 + (raw - 100)
    -106 -> 40
    40 -> 40
    else -> null
}

private fun stripSlot(item: CompoundTag): CompoundTag =
    CompoundTag(item.value.filterKeys { it != "Slot" && it != "slot" }.toMutableMap())

/**
 * Extracts player items from a `PlayerInventory` source. Unlike [dumpItemStacksRaw]
 * this keeps empty boxes and records the source section and canonical slot.
 */
fun dumpPlayerItems(source: ItemSource): List<PlayerItem> {
    val location = source.location as? PlayerInventory ?: return emptyList()
    val playerSource = if (location.enderChest) PlayerSource.ENDER_CHEST else PlayerSource.INVENTORY
    val out = mutableListOf<PlayerItem>()
    source.items.forEachIndexed { index, item ->
        if (!item.has("id", Tag.STRING)) return@forEachIndexed
        val rawSlot = when {
            item.has("Slot", Tag.INTEGER) -> item.getInt("Slot")
            item.has("slot", Tag.INTEGER) -> item.getInt("slot")
            else -> index
        }
        val slot = if (playerSource == PlayerSource.INVENTORY) {
            canonicalInventorySlot(rawSlot) ?: index.coerceIn(0, 40)
        } else {
            rawSlot.coerceIn(0, 26)
        }
        out += PlayerItem(location.player, playerSource, slot, stripSlot(item))
    }
    return out
}

/**
 * Removes [EXCLUDED_ITEM_IDS] from a stack and recursively from every nested
 * item list. The containing item is preserved; only excluded stacks disappear.
 */
fun filterExcludedItem(item: CompoundTag): CompoundTag? {
    if (!item.has("id", Tag.STRING)) return item
    if (item.getString("id") in EXCLUDED_ITEM_IDS) return null

    val components = item["components"] as? CompoundTag ?: return item
    val newComponents = CompoundTag(components.value.toMutableMap())

    (newComponents["minecraft:container"] as? ListTag<*>)?.let { container ->
        val entries = container.mapNotNull { rawEntry ->
            val entry = rawEntry as? CompoundTag ?: return@mapNotNull rawEntry
            val nested = entry["item"] as? CompoundTag ?: return@mapNotNull entry
            val filtered = filterExcludedItem(nested) ?: return@mapNotNull null
            CompoundTag(entry.value.toMutableMap().apply { put("item", filtered) })
        }
        newComponents["minecraft:container"] = ListTag(entries)
    }

    for (key in listOf("minecraft:bundle_contents", "minecraft:charged_projectiles")) {
        (newComponents[key] as? ListTag<*>)?.let { list ->
            newComponents[key] = ListTag(list.mapNotNull { raw ->
                (raw as? CompoundTag)?.let(::filterExcludedItem)
            })
        }
    }

    (newComponents["minecraft:use_remainder"] as? CompoundTag)?.let { remainder ->
        val filtered = filterExcludedItem(remainder)
        if (filtered == null) newComponents.remove("minecraft:use_remainder")
        else newComponents["minecraft:use_remainder"] = filtered
    }

    (newComponents["minecraft:pot_decorations"] as? CompoundTag)?.let { decorations ->
        val newDecorations = CompoundTag(decorations.value.toMutableMap())
        for (face in listOf("left", "back", "right", "front")) {
            val faceItem = newDecorations[face] as? CompoundTag ?: continue
            val filtered = filterExcludedItem(faceItem)
            if (filtered == null) newDecorations.remove(face) else newDecorations[face] = filtered
        }
        newComponents["minecraft:pot_decorations"] = newDecorations
    }

    (newComponents["minecraft:pot_decorations"] as? ListTag<*>)?.let { decorations ->
        newComponents["minecraft:pot_decorations"] = ListTag(decorations.map { raw ->
            if (raw is StringTag && raw.value in EXCLUDED_ITEM_IDS) StringTag("minecraft:brick") else raw
        })
    }

    return CompoundTag(item.value.toMutableMap().apply { put("components", newComponents) })
}

fun filterExcludedItems(items: List<CompoundTag>): List<CompoundTag> =
    items.mapNotNull(::filterExcludedItem)

/**
 * Recursively unpacks plain shulker boxes outside player data. Identity boxes
 * are kept whole. Empty plain boxes produce nothing.
 */
fun unpackPlainBoxes(items: List<CompoundTag>): List<CompoundTag> {
    val out = mutableListOf<CompoundTag>()
    fun walk(item: CompoundTag) {
        if (!item.has("id", Tag.STRING)) return
        val id = item.getString("id")
        if (isShulkerBoxId(id)) {
            if (hasBoxIdentityComponents(item)) {
                out += item
                return
            }
            val contents = boxContents(item)
            if (contents != null) for (child in contents) walk(child)
            return
        }
        out += item
    }
    for (item in items) walk(item)
    return out
}
