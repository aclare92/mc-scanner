package de.skyrising.mc.scanner

import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/** Optional provenance for a manifest row. */
data class PlacedItemMeta(
    val player: String? = null,
    val source: PlayerSource? = null,
    val sourceSlot: Int? = null,
)

/**
 * A barrel placed in an export schematic. [items] are item stacks without a
 * slot. [slots] optionally gives each item's barrel slot; null means sequential
 * slots from 0. Player-grid barrels use explicit slots to preserve source gaps.
 */
data class PlacedBarrel(
    val x: Int,
    val y: Int,
    val z: Int,
    val items: List<CompoundTag>,
    val slots: List<Int>? = null,
    val meta: List<PlacedItemMeta> = emptyList(),
) {
    init {
        require(slots == null || slots.size == items.size) { "slots and items must have the same size" }
        require(meta.isEmpty() || meta.size == items.size) { "meta and items must have the same size" }
    }

    fun slotAt(index: Int): Int = slots?.get(index) ?: index
    fun metaAt(index: Int): PlacedItemMeta = meta.getOrElse(index) { PlacedItemMeta() }
}

/** A wall sign placed in the export's first gap row. */
data class PlacedSign(val x: Int, val y: Int, val z: Int, val lines: List<String>)

/**
 * A schematic ready to be written: the placed barrels and signs plus provenance
 * and the source world's data version (so the target game can data-fix the item
 * NBT).
 */
data class Schematic(
    val barrels: List<PlacedBarrel>,
    val dataVersion: Int,
    val name: String = "mc-scanner export",
    val author: String = "mc-scanner",
    val description: String = "",
    val signs: List<PlacedSign> = emptyList(),
)

private const val LITEMATICA_VERSION = 7
private const val LITEMATICA_SUBVERSION = 1
private const val MAX_BARREL_SLOTS = 27
private const val PALETTE_SIZE = 3
private const val BARREL_PALETTE_INDEX = 1
private const val SIGN_PALETTE_INDEX = 2
private const val SIGN_LINES = 4

private fun compound(vararg entries: Pair<String, Tag>): CompoundTag =
    CompoundTag(linkedMapOf(*entries))

private fun vec3(x: Int, y: Int, z: Int) = compound(
    "x" to IntTag(x), "y" to IntTag(y), "z" to IntTag(z),
)

private data class Bounds(
    val minX: Int, val minY: Int, val minZ: Int,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
) {
    val volume get() = sizeX * sizeY * sizeZ

    /** Litematica's flat index: y slowest, then z, then x. */
    fun index(x: Int, y: Int, z: Int): Int =
        (y - minY) * (sizeX * sizeZ) + (z - minZ) * sizeX + (x - minX)
}

private fun Schematic.bounds(): Bounds {
    val xs = barrels.map { it.x } + signs.map { it.x }
    val ys = barrels.map { it.y } + signs.map { it.y }
    val zs = barrels.map { it.z } + signs.map { it.z }
    if (xs.isEmpty()) return Bounds(0, 0, 0, 1, 1, 1)
    val minX = xs.min(); val maxX = xs.max()
    val minY = ys.min(); val maxY = ys.max()
    val minZ = zs.min(); val maxZ = zs.max()
    return Bounds(minX, minY, minZ, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1)
}

private fun bitsFor(paletteSize: Int): Int {
    var bits = 2
    while ((1 shl bits) < paletteSize) bits++
    return bits
}

/** LSB-first packed long array, the layout Litematica uses for `BlockStates`. */
private fun packValues(values: IntArray, bits: Int): LongArray {
    val words = ((values.size.toLong() * bits) + 63) / 64
    val out = LongArray(words.toInt())
    for (i in values.indices) {
        val v = values[i].toLong()
        val start = i.toLong() * bits
        val word = (start ushr 6).toInt()
        val offset = (start and 63L).toInt()
        out[word] = out[word] or (v shl offset)
        if (offset + bits > 64) out[word + 1] = out[word + 1] or (v ushr (64 - offset))
    }
    return out
}

private fun palette(): ListTag<CompoundTag> = ListTag(listOf(
    compound("Name" to StringTag("minecraft:air")),
    compound(
        "Name" to StringTag("minecraft:barrel"),
        "Properties" to compound("facing" to StringTag("north"), "open" to StringTag("false")),
    ),
    compound(
        "Name" to StringTag("minecraft:oak_wall_sign"),
        "Properties" to compound("facing" to StringTag("south"), "waterlogged" to StringTag("false")),
    ),
))

private fun barrelItems(barrel: PlacedBarrel): ListTag<CompoundTag> {
    val items = barrel.items
    require(items.size <= MAX_BARREL_SLOTS) {
        "A barrel holds at most $MAX_BARREL_SLOTS items, got ${items.size}"
    }
    return ListTag(items.mapIndexed { index, item ->
        val map = LinkedHashMap(item.toMutableMap())
        map.remove("Slot")
        map.remove("slot")
        map["Slot"] = ByteTag(barrel.slotAt(index).toByte())
        CompoundTag(map)
    })
}

private fun signText(lines: List<String>) = compound(
    "messages" to ListTag((0 until SIGN_LINES).map { StringTag(lines.getOrElse(it) { "" }) }),
    "color" to StringTag("black"),
    "has_glowing_text" to ByteTag(0),
)

private fun signTileEntity(sign: PlacedSign, b: Bounds) = compound(
    "id" to StringTag("minecraft:sign"),
    "x" to IntTag(sign.x - b.minX),
    "y" to IntTag(sign.y - b.minY),
    "z" to IntTag(sign.z - b.minZ),
    "front_text" to signText(sign.lines),
    "back_text" to signText(emptyList()),
    "is_waxed" to ByteTag(0),
)

/** The Litematica `.litematic` root compound. */
fun Schematic.toLitematicaNbt(now: Long = System.currentTimeMillis()): CompoundTag {
    val b = bounds()
    val indices = IntArray(b.volume)
    for (barrel in barrels) indices[b.index(barrel.x, barrel.y, barrel.z)] = BARREL_PALETTE_INDEX
    for (sign in signs) indices[b.index(sign.x, sign.y, sign.z)] = SIGN_PALETTE_INDEX

    val tileEntities = ListTag(
        barrels.map { barrel ->
            compound(
                "id" to StringTag("minecraft:barrel"),
                "x" to IntTag(barrel.x - b.minX),
                "y" to IntTag(barrel.y - b.minY),
                "z" to IntTag(barrel.z - b.minZ),
                "Items" to barrelItems(barrel),
            )
        } + signs.map { sign -> signTileEntity(sign, b) }
    )

    val region = compound(
        "Position" to vec3(0, 0, 0),
        "Size" to vec3(b.sizeX, b.sizeY, b.sizeZ),
        "BlockStatePalette" to palette(),
        "BlockStates" to LongArrayTag(packValues(indices, bitsFor(PALETTE_SIZE))),
        "TileEntities" to tileEntities,
    )

    val metadata = compound(
        "Name" to StringTag(name),
        "Author" to StringTag(author),
        "Description" to StringTag(description),
        "RegionCount" to IntTag(1),
        "TotalVolume" to IntTag(b.volume),
        "TotalBlocks" to IntTag(barrels.size + signs.size),
        "TimeCreated" to LongTag(now),
        "TimeModified" to LongTag(now),
        "EnclosingSize" to vec3(b.sizeX, b.sizeY, b.sizeZ),
    )

    return compound(
        "MinecraftDataVersion" to IntTag(dataVersion),
        "Version" to IntTag(LITEMATICA_VERSION),
        "SubVersion" to IntTag(LITEMATICA_SUBVERSION),
        "Metadata" to metadata,
        "Regions" to compound("region" to region),
    )
}

/** The vanilla structure block `.nbt` root compound. */
fun Schematic.toVanillaStructureNbt(): CompoundTag {
    val b = bounds()
    val blocks = barrels.map { barrel ->
        compound(
            "state" to IntTag(BARREL_PALETTE_INDEX),
            "pos" to ListTag(listOf(
                IntTag(barrel.x - b.minX), IntTag(barrel.y - b.minY), IntTag(barrel.z - b.minZ),
            )),
            "nbt" to compound(
                "id" to StringTag("minecraft:barrel"),
                "Items" to barrelItems(barrel),
            ),
        )
    } + signs.map { sign ->
        compound(
            "state" to IntTag(SIGN_PALETTE_INDEX),
            "pos" to ListTag(listOf(
                IntTag(sign.x - b.minX), IntTag(sign.y - b.minY), IntTag(sign.z - b.minZ),
            )),
            "nbt" to compound(
                "id" to StringTag("minecraft:sign"),
                "front_text" to signText(sign.lines),
                "back_text" to signText(emptyList()),
                "is_waxed" to ByteTag(0),
            ),
        )
    }
    return compound(
        "DataVersion" to IntTag(dataVersion),
        "size" to ListTag(listOf(IntTag(b.sizeX), IntTag(b.sizeY), IntTag(b.sizeZ))),
        "palette" to palette(),
        "blocks" to ListTag(blocks),
        "entities" to ListTag(emptyList<Tag>()),
    )
}

private fun writeRootCompressed(root: CompoundTag, path: Path) {
    path.parent?.let { Files.createDirectories(it) }
    Files.newOutputStream(path).use { os ->
        DataOutputStream(GZIPOutputStream(os)).use { out ->
            out.writeByte(Tag.COMPOUND)
            out.writeUTF("")
            root.write(out)
        }
    }
}

fun Schematic.writeLitematica(path: Path) = writeRootCompressed(toLitematicaNbt(), path)

fun Schematic.writeVanillaStructure(path: Path) = writeRootCompressed(toVanillaStructureNbt(), path)
