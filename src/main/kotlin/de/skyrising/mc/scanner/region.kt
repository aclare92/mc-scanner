package de.skyrising.mc.scanner

import de.skyrising.mc.scanner.region.RegionReader
import de.skyrising.mc.scanner.region.RegionVisitor
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.objects.Object2IntMap
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.math.ceil
import kotlin.math.log2

data class RegionFile(private val path: Path) : Scannable {
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
        val fileName = path.fileName.toString()
        val parts = fileName.split('.')
        if (parts.size != 4 || parts[0] != "r" || parts[3] != "mca") {
            throw IllegalArgumentException("Not a valid region file name: $fileName")
        }
        x = parts[1].toInt()
        z = parts[2].toInt()
    }

    fun reader() = RegionReader(Files.newByteChannel(path, StandardOpenOption.READ))

    fun forEachChunk(visitor: (x: Int, z: Int, version: Int, data: CompoundTag) -> Unit) {
        reader().use {
            it.accept(RegionVisitor.visitAllChunks(visitor))
        }
    }

    fun scanChunks(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val blockIdNeedles: Set<BlockIdMask> = needles.filterIsInstanceTo(mutableSetOf())
        val blockStateNeedles: Set<BlockState> = needles.filterIsInstanceTo(mutableSetOf())
        val itemNeedles: Set<ItemType> = needles.filterIsInstanceTo(mutableSetOf())
        forEachChunk { x, z, version, data ->
            scanChunk(results, blockIdNeedles, blockStateNeedles, itemNeedles, statsMode, ChunkPos(dimension, (this.x shl 5) or x, (this.z shl 5) or z), version, data)
        }
        return results
    }

    override fun scan(needles: Collection<Needle>, statsMode: Boolean): List<SearchResult> {
        return scanChunks(needles, statsMode)
    }

    override fun itemSources(): List<ItemSource> {
        val sources = mutableListOf<ItemSource>()
        forEachChunk { _, _, version, data ->
            sources += chunkItemSources(data, version, dimension)
        }
        return sources
    }

    override fun toString(): String {
        return "RegionFile($dimension, x=$x, z=$z)"
    }
}

fun getPalette(section: CompoundTag, version: Int): List<Tag>? {
    val listTag: Tag? = when {
        version <= DataVersion.BLOCK_STATES_CONTAINERIZED -> section["Palette"]
        else -> {
            if (!section.has("block_states", Tag.COMPOUND)) return null
            section.getCompound("block_states")["palette"]
        }
    }
    return (listTag as? ListTag<*>)?.map { it as Tag }
}

fun getBlockStates(section: CompoundTag, version: Int): LongArray? {
    if (version <= DataVersion.BLOCK_STATES_CONTAINERIZED) {
        return if (section.has("BlockStates", Tag.LONG_ARRAY)) section.getLongArray("BlockStates") else null
    }
    if (!section.has("block_states", Tag.COMPOUND)) return null
    val container = section.getCompound("block_states")
    return if (container.has("data", Tag.LONG_ARRAY)) container.getLongArray("data") else null
}

/** The block id at an absolute chunk position, e.g. `minecraft:red_shulker_box`, or null if unavailable. */
fun blockIdAt(data: CompoundTag, version: Int, x: Int, y: Int, z: Int): String? {
    val sectionsKey = if (version < DataVersion.REMOVE_LEVEL_TAG) "Sections" else "sections"
    if (!data.has(sectionsKey, Tag.LIST)) return null
    val sectionY = y shr 4
    val section = data.getList<CompoundTag>(sectionsKey).firstOrNull { it.getInt("Y") == sectionY } ?: return null
    val palette = getPalette(section, version) ?: return null
    if (palette.isEmpty()) return null
    val states = getBlockStates(section, version)
    val index = (y and 15) * 256 + (z and 15) * 16 + (x and 15)
    val paletteIndex = if (states == null) 0 else unpackBlockState(states, index, palette.size, version)
    if (paletteIndex !in palette.indices) return null
    return try {
        BlockState.from(palette[paletteIndex], version).id.toString()
    } catch (e: Exception) {
        null
    }
}

private fun unpackBlockState(states: LongArray, index: Int, paletteSize: Int, version: Int): Int {
    val bits = maxOf(4, ceil(log2(paletteSize.toDouble())).toInt())
    val mask = (1L shl bits) - 1
    if (version < DataVersion.NON_PACKED_BLOCK_STATES) {
        val perLong = 64 / bits
        val longIndex = index / perLong
        if (longIndex >= states.size) return -1
        val offset = (index % perLong) * bits
        return ((states[longIndex] ushr offset) and mask).toInt()
    }
    val start = index.toLong() * bits
    val word = (start ushr 6).toInt()
    val offset = (start and 63L).toInt()
    if (word >= states.size) return -1
    var value = states[word] ushr offset
    if (offset + bits > 64) {
        if (word + 1 >= states.size) return -1
        value = value or (states[word + 1] shl (64 - offset))
    }
    return (value and mask).toInt()
}

fun scanChunk(results: MutableList<SearchResult>, blockIdNeedles: Set<BlockIdMask>, blockStateNeedles: Set<BlockState>, itemNeedles: Set<ItemType>, statsMode: Boolean, chunkPos: ChunkPos, version: Int, data: CompoundTag) {
    val dimension = chunkPos.dimension
    val flattened = version >= DataVersion.FLATTENING
    if (!flattened && blockIdNeedles.isNotEmpty()) {
        scanUnflattenedChunk(blockIdNeedles, data, results, chunkPos)
    }
    if (flattened && blockStateNeedles.isNotEmpty()) {
        scanFlattenedChunk(data, version, blockStateNeedles, results, chunkPos)
    }
    if (itemNeedles.isNotEmpty() || statsMode) {
        scanChunkItems(version, data, itemNeedles, statsMode, dimension, results)
    }
}

private fun scanUnflattenedChunk(blockIdNeedles: Set<BlockIdMask>, data: CompoundTag, results: MutableList<SearchResult>, chunkPos: ChunkPos) {
    val sections = data.getList<CompoundTag>("Sections")
    val matches = Object2IntOpenHashMap<BlockIdMask>()
    for (section in sections) {
        val y = section.getInt("Y")
        val blocks = section.getByteArray("Blocks")
        //val add = if (section.has("Add", Tag.BYTE_ARRAY)) section.getByteArray("Add") else null
        for (blockNeedle in blockIdNeedles) {
            val count = blocks.count { it == blockNeedle.id.toByte() }
            if (count != 0) {
                matches[blockNeedle] = matches.getInt(blockNeedle) + count
            }
        }
        if (matches.size == blockIdNeedles.size) break
    }
    for (match in matches) {
        results.add(SearchResult(match.key.blockState ?: match.key, chunkPos, match.value.toLong()))
    }
}

private fun scanFlattenedChunk(data: CompoundTag, version: Int, blockStateNeedles: Set<BlockState>, results: MutableList<SearchResult>, chunkPos: ChunkPos) {
    val sections = data.getList<CompoundTag>(if (version < DataVersion.REMOVE_LEVEL_TAG) "Sections" else "sections")
    val matches = Object2IntOpenHashMap<BlockState>()
    for (section in sections) {
        val palette = getPalette(section, version) ?: continue
        val blockStates = getBlockStates(section, version) ?: continue
        val matchingPaletteEntries = Int2ObjectOpenHashMap<BlockState>()
        palette.forEachIndexed { index, paletteEntry ->
        val state = BlockState.from(paletteEntry, version)
            for (blockNeedle in blockStateNeedles) {
                if (state.matches(blockNeedle)) matchingPaletteEntries[index] = blockNeedle
            }
        }
        if (matchingPaletteEntries.isEmpty()) continue
        val counts = scanBlockStates(matchingPaletteEntries, blockStates, palette.size, version < DataVersion.NON_PACKED_BLOCK_STATES)
        for (e in counts.object2IntEntrySet()) {
            matches[e.key] = matches.getInt(e.key) + e.intValue
        }
    }
    for (match in matches) {
        results.add(SearchResult(match.key, chunkPos, match.value.toLong()))
    }
}

private fun scanChunkItems(version: Int, data: CompoundTag, itemNeedles: Set<ItemType>, statsMode: Boolean, dimension: String, results: MutableList<SearchResult>) {
    for (source in chunkItemSources(data, version, dimension)) {
        val contents = scanInventory(source.items, itemNeedles, statsMode, source.version)
        addResults(results, source.location, contents, statsMode)
    }
}

fun scanBlockStates(ids: Int2ObjectMap<BlockState>, blockStates: LongArray, paletteSize: Int, packed: Boolean): Object2IntMap<BlockState> {
    val counts = Object2IntOpenHashMap<BlockState>()
    val bits = maxOf(4, ceil(log2(paletteSize.toDouble())).toInt())
    val mask = (1 shl bits) - 1
    var longIndex = 0
    var subIndex = 0
    repeat(16 * 16 * 16) {
        if (subIndex + bits > 64 && !packed) {
            longIndex++
            subIndex = 0
        }
        val id = if (subIndex + bits > 64) {
            val loBitsCount = 64 - subIndex
            val loBits = (blockStates[longIndex] ushr subIndex).toInt()
            val hiBits = blockStates[longIndex + 1].toInt()
            longIndex++
            (loBits or (hiBits shl loBitsCount)) and mask
        } else {
            (blockStates[longIndex] ushr subIndex).toInt() and mask
        }
        if (id in ids) {
            val state = ids[id]
            counts[state] = counts.getInt(state) + 1
        }
        if (subIndex + bits == 64) longIndex++
        subIndex = (subIndex + bits) and 0x3f
    }
    return counts
}