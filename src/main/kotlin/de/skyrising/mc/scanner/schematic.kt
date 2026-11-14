package de.skyrising.mc.scanner

import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * A barrel placed in an export schematic. [items] are shulker-box item stacks
 * (each `{id, count, components}`) without a slot; the writer assigns `Slot`
 * in list order when it builds the block entity.
 */
data class PlacedBarrel(val x: Int, val y: Int, val z: Int, val items: List<CompoundTag>)

/**
 * A schematic ready to be written: the placed barrels plus provenance and the
 * source world's data version (so the target game can data-fix the item NBT).
 */
data class Schematic(
    val barrels: List<PlacedBarrel>,
    val dataVersion: Int,
    val name: String = "mc-scanner export",
    val author: String = "mc-scanner",
    val description: String = "",
)

private const val LITEMATICA_VERSION = 7
private const val LITEMATICA_SUBVERSION = 1
private const val MAX_BARREL_SLOTS = 27
private const val PALETTE_SIZE = 2
private const val BARREL_PALETTE_INDEX = 1

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
    if (barrels.isEmpty()) return Bounds(0, 0, 0, 1, 1, 1)
    val minX = barrels.minOf { it.x }; val maxX = barrels.maxOf { it.x }
    val minY = barrels.minOf { it.y }; val maxY = barrels.maxOf { it.y }
    val minZ = barrels.minOf { it.z }; val maxZ = barrels.maxOf { it.z }
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
))

private fun barrelItems(items: List<CompoundTag>): ListTag<CompoundTag> {
    require(items.size <= MAX_BARREL_SLOTS) {
        "A barrel holds at most $MAX_BARREL_SLOTS shulker boxes, got ${items.size}"
    }
    return ListTag(items.mapIndexed { slot, item ->
        val map = LinkedHashMap(item.toMutableMap())
        map["Slot"] = ByteTag(slot.toByte())
        CompoundTag(map)
    })
}

/** The Litematica `.litematic` root compound. */
fun Schematic.toLitematicaNbt(now: Long = System.currentTimeMillis()): CompoundTag {
    val b = bounds()
    val indices = IntArray(b.volume)
    for (barrel in barrels) indices[b.index(barrel.x, barrel.y, barrel.z)] = BARREL_PALETTE_INDEX

    val region = compound(
        "Position" to vec3(0, 0, 0),
        "Size" to vec3(b.sizeX, b.sizeY, b.sizeZ),
        "BlockStatePalette" to palette(),
        "BlockStates" to LongArrayTag(packValues(indices, bitsFor(PALETTE_SIZE))),
        "TileEntities" to ListTag(barrels.map { barrel ->
            compound(
                "id" to StringTag("minecraft:barrel"),
                "x" to IntTag(barrel.x - b.minX),
                "y" to IntTag(barrel.y - b.minY),
                "z" to IntTag(barrel.z - b.minZ),
                "Items" to barrelItems(barrel.items),
            )
        }),
    )

    val metadata = compound(
        "Name" to StringTag(name),
        "Author" to StringTag(author),
        "Description" to StringTag(description),
        "RegionCount" to IntTag(1),
        "TotalVolume" to IntTag(b.volume),
        "TotalBlocks" to IntTag(barrels.size),
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
                "Items" to barrelItems(barrel.items),
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
