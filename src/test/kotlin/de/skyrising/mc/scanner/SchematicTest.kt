package de.skyrising.mc.scanner

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SchematicTest {

    private fun item(id: String, count: Int) = CompoundTag(mutableMapOf(
        "id" to StringTag(id),
        "count" to IntTag(count),
    ))

    private fun shulker(contents: List<Pair<String, Int>>): CompoundTag {
        val container = contents.mapIndexed { slot, (id, count) ->
            CompoundTag(mutableMapOf(
                "slot" to IntTag(slot),
                "item" to item(id, count),
            ))
        }
        return CompoundTag(mutableMapOf(
            "id" to StringTag("minecraft:shulker_box"),
            "count" to IntTag(1),
            "components" to CompoundTag(mutableMapOf(
                "minecraft:container" to ListTag(container),
            )),
        ))
    }

    private fun readNbt(path: Path): CompoundTag {
        val buf = DECOMPRESSOR.decodeGzip(ByteBuffer.wrap(Files.readAllBytes(path)))
        return Tag.read(ByteBufferDataInput(buf)) as CompoundTag
    }

    /** Inverse of the writer's LSB-first packing, for assertions. */
    private fun unpack(longs: LongArray, index: Int, bits: Int): Int {
        val start = index * bits
        val word = start ushr 6
        val offset = start and 63
        val mask = (1L shl bits) - 1
        var v = longs[word] ushr offset
        if (offset + bits > 64) v = v or (longs[word + 1] shl (64 - offset))
        return (v and mask).toInt()
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = createTempDirectory("schematic-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun litematicaRoundTripsSingleBarrel() = withTempDir { dir ->
        val barrel = PlacedBarrel(0, 0, 0, listOf(shulker(listOf(
            "minecraft:stone" to 64,
            "minecraft:ender_pearl" to 16,
            "minecraft:diamond_sword" to 1,
        ))))
        val file = dir.resolve("export.litematic")
        Schematic(listOf(barrel), 4671).writeLitematica(file)

        val root = readNbt(file)
        assertEquals(4671, root.getInt("MinecraftDataVersion"))
        assertEquals(7, root.getInt("Version"))
        assertEquals(1, root.getInt("SubVersion"))

        val region = root.getCompound("Regions").getCompound("region")
        assertEquals(0, region.getCompound("Position").getInt("x"))
        assertEquals(0, region.getCompound("Position").getInt("y"))
        assertEquals(0, region.getCompound("Position").getInt("z"))
        assertEquals(1, region.getCompound("Size").getInt("x"))
        assertEquals(1, region.getCompound("Size").getInt("y"))
        assertEquals(1, region.getCompound("Size").getInt("z"))

        val palette = region.getList<CompoundTag>("BlockStatePalette")
        assertEquals("minecraft:air", palette[0].getString("Name"))
        assertEquals("minecraft:barrel", palette[1].getString("Name"))
        assertEquals("north", palette[1].getCompound("Properties").getString("facing"))

        assertEquals(1, unpack(region.getLongArray("BlockStates"), 0, 2))

        val tile = region.getList<CompoundTag>("TileEntities")[0]
        assertEquals("minecraft:barrel", tile.getString("id"))
        assertEquals(0, tile.getInt("x"))
        assertEquals(0, tile.getInt("y"))
        assertEquals(0, tile.getInt("z"))

        val items = tile.getList<CompoundTag>("Items")
        assertEquals(1, items.size)
        assertEquals("minecraft:shulker_box", items[0].getString("id"))
        assertEquals(1, items[0].getInt("count"))
        assertEquals(0, items[0].getInt("Slot"))
        val container = items[0].getCompound("components").getList<CompoundTag>("minecraft:container")
        assertEquals(3, container.size)
        assertEquals("minecraft:stone", container[0].getCompound("item").getString("id"))
        assertEquals(64, container[0].getCompound("item").getInt("count"))
        assertEquals(16, container[1].getCompound("item").getInt("count"))
        assertEquals(1, container[2].getCompound("item").getInt("count"))
    }

    @Test
    fun litematicaPlacesSecondBarrelAdjacent() = withTempDir { dir ->
        val schematic = Schematic(
            listOf(
                PlacedBarrel(0, 0, 0, listOf(shulker(listOf("minecraft:stone" to 1)))),
                PlacedBarrel(1, 0, 0, listOf(shulker(listOf("minecraft:dirt" to 1)))),
            ),
            dataVersion = 4671,
        )
        val file = dir.resolve("export.litematic")
        schematic.writeLitematica(file)

        val region = readNbt(file).getCompound("Regions").getCompound("region")
        assertEquals(2, region.getCompound("Size").getInt("x"))
        val states = region.getLongArray("BlockStates")
        assertEquals(1, unpack(states, 0, 2))
        assertEquals(1, unpack(states, 1, 2))

        val tiles = region.getList<CompoundTag>("TileEntities")
        assertEquals(2, tiles.size)
        assertEquals(1, tiles[1].getInt("x"))
    }

    @Test
    fun signIsWrittenToBothFormats() = withTempDir { dir ->
        val barrel = PlacedBarrel(0, 0, 0, listOf(shulker(listOf("minecraft:stone" to 1))))
        val sign = PlacedSign(0, 0, 1, listOf("Alice", "Line 2"))
        val litematic = dir.resolve("export.litematic")
        Schematic(listOf(barrel), 4671, signs = listOf(sign)).writeLitematica(litematic)

        val region = readNbt(litematic).getCompound("Regions").getCompound("region")
        assertEquals(2, region.getCompound("Size").getInt("z"))
        val palette = region.getList<CompoundTag>("BlockStatePalette")
        assertEquals("minecraft:oak_wall_sign", palette[2].getString("Name"))
        assertEquals("south", palette[2].getCompound("Properties").getString("facing"))
        val tile = region.getList<CompoundTag>("TileEntities").single { it.getString("id") == "minecraft:sign" }
        assertEquals(0, tile.getInt("x"))
        assertEquals(0, tile.getInt("y"))
        assertEquals(1, tile.getInt("z"))
        assertEquals(listOf("Alice", "Line 2", "", ""),
            tile.getCompound("front_text").getList<StringTag>("messages").map { it.value })
        assertEquals(0, tile.getInt("is_waxed"))

        val vanilla = dir.resolve("export.nbt")
        Schematic(listOf(barrel), 4671, signs = listOf(sign)).writeVanillaStructure(vanilla)
        val signBlock = readNbt(vanilla).getList<CompoundTag>("blocks")
            .single { it.getInt("state") == 2 }
        assertEquals(listOf(0, 0, 1), signBlock.getList<IntTag>("pos").map { it.value })
        val nbt = signBlock.getCompound("nbt")
        assertEquals("minecraft:sign", nbt.getString("id"))
        assertTrue(!nbt.containsKey("x"))
        assertEquals(listOf("Alice", "Line 2", "", ""),
            nbt.getCompound("front_text").getList<StringTag>("messages").map { it.value })
    }

    @Test
    fun explicitBarrelSlotsPreserveGaps() = withTempDir { dir ->
        val barrel = PlacedBarrel(
            0, 0, 0,
            listOf(shulker(listOf("minecraft:stone" to 1)), shulker(listOf("minecraft:dirt" to 1))),
            slots = listOf(5, 10),
        )
        val file = dir.resolve("export.litematic")
        Schematic(listOf(barrel), 4671).writeLitematica(file)

        val items = readNbt(file).getCompound("Regions").getCompound("region")
            .getList<CompoundTag>("TileEntities").single().getList<CompoundTag>("Items")
        assertEquals(listOf(5, 10), items.map { it.getInt("Slot") })
    }

    @Test
    fun vanillaStructureRoundTrips() = withTempDir { dir ->
        val schematic = Schematic(
            listOf(
                PlacedBarrel(0, 0, 0, listOf(shulker(listOf("minecraft:stone" to 64)))),
                PlacedBarrel(1, 0, 0, listOf(shulker(listOf("minecraft:dirt" to 5)))),
            ),
            dataVersion = 4671,
        )
        val file = dir.resolve("export.nbt")
        schematic.writeVanillaStructure(file)

        val root = readNbt(file)
        assertEquals(4671, root.getInt("DataVersion"))
        val size = root.getList<IntTag>("size")
        assertEquals(listOf(2, 1, 1), size.map { it.value })

        val palette = root.getList<CompoundTag>("palette")
        assertEquals("minecraft:air", palette[0].getString("Name"))
        assertEquals("minecraft:barrel", palette[1].getString("Name"))

        val blocks = root.getList<CompoundTag>("blocks")
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].getInt("state"))
        assertEquals(listOf(0, 0, 0), blocks[0].getList<IntTag>("pos").map { it.value })
        assertEquals(listOf(1, 0, 0), blocks[1].getList<IntTag>("pos").map { it.value })
        assertEquals("minecraft:barrel", blocks[1].getCompound("nbt").getString("id"))
        assertEquals(1, blocks[1].getCompound("nbt").getList<CompoundTag>("Items").size)
    }
}
