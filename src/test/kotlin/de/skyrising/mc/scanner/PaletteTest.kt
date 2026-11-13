package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class PaletteTest {

    private val preCompact = DataVersion.BLOCK_PALETTE_COMPACT - 1
    private val compact = DataVersion.BLOCK_PALETTE_COMPACT

    @Test
    fun pre26_3CompoundEntry() {
        val entry = CompoundTag(
            mutableMapOf(
                "Name" to StringTag("minecraft:oak_log"),
                "Properties" to CompoundTag(mutableMapOf("axis" to StringTag("y"))),
            )
        )
        assertEquals(
            BlockState(Identifier.of("minecraft:oak_log"), mapOf("axis" to "y")),
            BlockState.from(entry, preCompact)
        )
    }

    @Test
    fun post26_3CompoundEntry() {
        val entry = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:oak_log"),
                "properties" to CompoundTag(mutableMapOf("axis" to StringTag("y"))),
            )
        )
        assertEquals(
            BlockState(Identifier.of("minecraft:oak_log"), mapOf("axis" to "y")),
            BlockState.from(entry, compact)
        )
    }

    @Test
    fun post26_3DefaultStateCompoundEntry() {
        val entry = CompoundTag(mutableMapOf("id" to StringTag("minecraft:stone")))
        assertEquals(BlockState(Identifier.of("minecraft:stone")), BlockState.from(entry, compact))
    }

    @Test
    fun post26_3StringEntry() {
        assertEquals(
            BlockState(Identifier.of("minecraft:stone")),
            BlockState.from(StringTag("minecraft:stone"), compact)
        )
    }

    @Test
    fun post26_3EmptyKeyCompoundEntry() {
        val entry = CompoundTag(mutableMapOf("" to StringTag("minecraft:stone")))
        assertEquals(BlockState(Identifier.of("minecraft:stone")), BlockState.from(entry, compact))
    }

    @Test
    fun getPaletteReadsStringList() {
        val section = CompoundTag(
            mutableMapOf(
                "block_states" to CompoundTag(
                    mutableMapOf(
                        "palette" to ListTag(listOf(StringTag("minecraft:stone"), StringTag("minecraft:dirt")))
                    )
                )
            )
        )
        assertEquals(
            listOf<Tag>(StringTag("minecraft:stone"), StringTag("minecraft:dirt")),
            getPalette(section, compact)
        )
    }

    @Test
    fun getPaletteReadsPre1_18CompoundList() {
        val section = CompoundTag(
            mutableMapOf(
                "Palette" to ListTag(
                    listOf(CompoundTag(mutableMapOf("Name" to StringTag("minecraft:stone"))))
                )
            )
        )
        assertEquals(
            listOf<Tag>(CompoundTag(mutableMapOf("Name" to StringTag("minecraft:stone")))),
            getPalette(section, DataVersion.FLATTENING)
        )
    }
}
