package de.skyrising.mc.scanner

import de.skyrising.mc.scanner.region.ChunkVisitor
import de.skyrising.mc.scanner.region.RegionReader
import de.skyrising.mc.scanner.region.RegionVisitor
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A location that holds a list of item stacks, discovered by the version-aware
 * resolvers below. The scanner walks every source uniformly: resolve sources,
 * then scan each source's items.
 */
data class ItemSource(
    val location: Location,
    val items: ListTag<CompoundTag>,
    val version: Int,
)

/** Where items live inside a block entity, at the given data version. */
fun blockEntitySources(be: CompoundTag, version: Int, dimension: String): List<ItemSource> {
    val pos = BlockPos(dimension, be.getInt("x"), be.getInt("y"), be.getInt("z"))
    val location = Container(be.getString("id"), pos)
    val sources = mutableListOf<ItemSource>()

    // Unresolved loot table (contents generated on first open, not from NBT)
    if (be.has("LootTable", Tag.STRING)) LOOT_CONTAINERS.incrementAndGet()

    // Generic container list (chest, barrel, hopper, furnace, dispenser, dropper, chiseled bookshelf, ...)
    if (be.has("Items", Tag.LIST)) {
        sources += ItemSource(location, be.getList("Items"), version)
    }
    // Single-item block entities
    if (be.has("RecordItem", Tag.COMPOUND)) { // jukebox
        sources += ItemSource(location, ListTag(listOf(be.getCompound("RecordItem"))), version)
    }
    if (be.has("Book", Tag.COMPOUND)) { // lectern
        sources += ItemSource(location, ListTag(listOf(be.getCompound("Book"))), version)
    }
    if (be.has("item", Tag.COMPOUND)) { // decorated pot slot 0
        sources += ItemSource(location, ListTag(listOf(be.getCompound("item"))), version)
    }
    // Decorated pot faces: 26.3+ stores them as an object of item stacks, earlier as a list of item ids
    if (be.has("sherds", Tag.COMPOUND)) {
        val sherds = be.getCompound("sherds")
        val items = listOf("left", "back", "right", "front").mapNotNull { face ->
            if (sherds.has(face, Tag.COMPOUND)) sherds.getCompound(face) else null
        }
        if (items.isNotEmpty()) sources += ItemSource(location, ListTag(items), version)
    } else if (be.has("sherds", Tag.LIST)) {
        val countKey = if (version >= DataVersion.COMPONENTS_REWORK) "count" else "Count"
        val items = be.getList<StringTag>("sherds").map {
            CompoundTag(mutableMapOf("id" to StringTag(it.value), countKey to IntTag(1)))
        }
        if (items.isNotEmpty()) sources += ItemSource(location, ListTag(items), version)
    }
    return sources
}

/** Where items live inside an entity, at the given data version. */
fun entitySources(entity: CompoundTag, version: Int, dimension: String): List<ItemSource> {
    val items = mutableListOf<CompoundTag>()
    if (entity.has("HandItems", Tag.LIST)) items.addAll(entity.getList("HandItems"))
    if (entity.has("ArmorItems", Tag.LIST)) items.addAll(entity.getList("ArmorItems"))
    if (entity.has("BodyArmorItems", Tag.LIST)) items.addAll(entity.getList("BodyArmorItems"))
    if (entity.has("Inventory", Tag.LIST)) items.addAll(entity.getList("Inventory"))
    if (entity.has("Items", Tag.LIST)) items.addAll(entity.getList("Items")) // chest/hopper minecart, llama, horse, ...
    if (entity.has("Item", Tag.COMPOUND)) items.add(entity.getCompound("Item")) // dropped items, item frames
    val listTag = ListTag(items.filter(CompoundTag::isNotEmpty))
    if (listTag.isEmpty() || !entity.has("Pos", Tag.LIST)) return emptyList()
    val posTag = entity.getList<DoubleTag>("Pos")
    val pos = Vec3d(dimension, posTag[0].value, posTag[1].value, posTag[2].value)
    return listOf(ItemSource(Entity(entity.getString("id"), pos), listTag, version))
}

/** Where items live inside a player record, at the given data version. */
fun playerSources(player: CompoundTag, version: Int, uuid: UUID): List<ItemSource> {
    val sources = mutableListOf<ItemSource>()
    if (player.has("Inventory", Tag.LIST)) {
        sources += ItemSource(PlayerInventory(uuid, false), player.getList("Inventory"), version)
    }
    if (player.has("EnderItems", Tag.LIST)) {
        sources += ItemSource(PlayerInventory(uuid, true), player.getList("EnderItems"), version)
    }
    return sources
}

/**
 * A region file from an entities directory (entities/r.x.z.mca). Each chunk entry
 * is { DataVersion, Position, Entities } rather than terrain data.
 */
data class EntityRegionFile(private val path: Path) : Scannable {
    val x: Int
    val z: Int
    val dimension = when (val dim = path.getName(path.nameCount - 3).toString()) {
        "." -> "overworld"
        "DIM-1" -> "the_nether"
        "DIM1" -> "the_end"
        else -> dim
    }
    override val size = Files.size(path)

    init {
        val parts = path.fileName.toString().split('.')
        if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") {
            throw IllegalArgumentException("Not a valid region file name: ${path.fileName}")
        }
        x = parts[1].toInt()
        z = parts[2].toInt()
    }

    fun reader() = RegionReader(Files.newByteChannel(path, StandardOpenOption.READ), stripLevel = false)

    override fun itemSources(): List<ItemSource> {
        val sources = mutableListOf<ItemSource>()
        reader().use { r ->
            r.accept(object : RegionVisitor() {
                override fun visitChunk(x: Int, z: Int): ChunkVisitor {
                    return object : ChunkVisitor() {
                        override fun visit(version: Int, data: CompoundTag) {
                            if (!data.has("Entities", Tag.LIST)) return
                            for (entity in data.getList<CompoundTag>("Entities")) {
                                sources += entitySources(entity, version, dimension)
                            }
                        }

                        override fun onInvalidData(ex: Exception) {
                            ex.printStackTrace()
                        }
                    }
                }
            })
        }
        return sources
    }

    override fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult> {
        val itemNeedles = needles.filterIsInstance<ItemType>()
        if (itemNeedles.isEmpty() && !statsMode) return emptyList()
        val results = mutableListOf<SearchResult>()
        for (source in itemSources()) {
            val contents = scanInventory(source.items, itemNeedles, statsMode, source.version)
            addResults(results, source.location, contents, statsMode)
        }
        return results
    }

    override fun toString() = "EntityRegionFile($dimension, x=$x, z=$z)"
}

/** Thread-safe count of containers whose contents come from an unresolved loot table. */
val LOOT_CONTAINERS = AtomicInteger()

/** The nested item-stack lists held inside an item stack (shulker boxes, bundles, crossbows, pots). */
fun nestedItemLists(slot: CompoundTag, version: Int): List<ListTag<CompoundTag>> {
    val lists = mutableListOf<ListTag<CompoundTag>>()
    if (version >= DataVersion.COMPONENTS_REWORK) {
        if (!slot.has("components", Tag.COMPOUND)) return emptyList()
        val components = slot.getCompound("components")
        if (components.has("minecraft:container_loot", Tag.COMPOUND)) LOOT_CONTAINERS.incrementAndGet()
        if (components.has("minecraft:container", Tag.LIST)) {
            val items = components.getList<CompoundTag>("minecraft:container").map { it.getCompound("item") }.toMutableList()
            lists.add(ListTag(items))
        }
        if (components.has("minecraft:bundle_contents", Tag.LIST)) {
            lists.add(components.getList<CompoundTag>("minecraft:bundle_contents"))
        }
        if (components.has("minecraft:charged_projectiles", Tag.LIST)) {
            lists.add(components.getList<CompoundTag>("minecraft:charged_projectiles"))
        }
        if (components.has("minecraft:pot_decorations", Tag.COMPOUND)) {
            val decorations = components.getCompound("minecraft:pot_decorations")
            val items = listOf("left", "back", "right", "front").mapNotNull { face ->
                if (decorations.has(face, Tag.COMPOUND)) decorations.getCompound(face) else null
            }
            if (items.isNotEmpty()) lists.add(ListTag(items))
        }
    } else {
        if (slot.has("tag", Tag.COMPOUND)) {
            val tag = slot.getCompound("tag")
            if (tag.has("BlockEntityTag", Tag.COMPOUND)) {
                val bet = tag.getCompound("BlockEntityTag")
                if (bet.has("Items", Tag.LIST)) lists.add(bet.getList<CompoundTag>("Items"))
            }
        }
    }
    return lists
}

/** Item sources held inside a chunk compound (block entities and entities). */
fun chunkItemSources(data: CompoundTag, version: Int, dimension: String): List<ItemSource> {
    val sources = mutableListOf<ItemSource>()
    val blockEntitiesTag = if (version >= DataVersion.REMOVE_LEVEL_TAG) "block_entities" else "TileEntities"
    if (data.has(blockEntitiesTag, Tag.LIST)) {
        for (be in data.getList<CompoundTag>(blockEntitiesTag)) sources += blockEntitySources(be, version, dimension)
    }
    val entitiesTag = if (version >= DataVersion.REMOVE_LEVEL_TAG) "entities" else "Entities"
    if (data.has(entitiesTag, Tag.LIST)) {
        for (e in data.getList<CompoundTag>(entitiesTag)) sources += entitySources(e, version, dimension)
    }
    return sources
}

/** The number of items in a stack, defaulting to 1 when the count tag is omitted. */
fun itemCount(slot: CompoundTag, version: Int): Long {
    val countKey = if (version >= DataVersion.COMPONENTS_REWORK) "count" else "Count"
    return if (slot.has(countKey, Tag.INTEGER)) slot.getInt(countKey).toLong() else 1L
}

/** A single item stack, with its distinguishing NBT, for the granular breakdown. */
data class ItemStackEntry(val id: String, val count: Long, val nbt: String)

/** The item's NBT minus id/count/slot, so distinct variants collapse to distinct rows. */
fun itemStackNbt(slot: CompoundTag): String {
    val filtered = slot.value.filterKeys { it != "id" && it != "count" && it != "Count" && it != "Slot" && it != "slot" }
    val sw = StringWriter()
    CompoundTag(filtered.toMutableMap()).writeSnbt(sw)
    return sw.toString()
}

/** Recursively walks item stacks (including nested containers) and emits granular entries. */
fun dumpItemStacks(items: ListTag<CompoundTag>, version: Int): List<ItemStackEntry> {
    val out = mutableListOf<ItemStackEntry>()
    fun walk(list: ListTag<CompoundTag>) {
        for (slot in list) {
            if (!slot.has("id", Tag.STRING)) continue
            out += ItemStackEntry(slot.getString("id"), itemCount(slot, version), itemStackNbt(slot))
            for (nested in nestedItemLists(slot, version)) walk(nested)
        }
    }
    walk(items)
    return out
}

/**
 * Retains each top-level item stack verbatim, for lossless [export]. Unlike
 * [dumpItemStacks] this does not recurse into nested containers: a filled shulker
 * box stays one item whose contents remain in its `minecraft:container` component.
 * The returned tags are the original slot compounds, not copies.
 */
fun dumpItemStacksRaw(items: ListTag<CompoundTag>): List<CompoundTag> =
    items.filter { it.has("id", Tag.STRING) }
