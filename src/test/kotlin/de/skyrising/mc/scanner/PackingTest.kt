package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackingTest {

    private fun item(id: String, count: Int, components: CompoundTag? = null) =
        CompoundTag(mutableMapOf(
            "id" to StringTag(id),
            "count" to IntTag(count),
        ).apply { if (components != null) this["components"] = components })

    private fun containerContents(vararg stacks: CompoundTag) = CompoundTag(mutableMapOf(
        "minecraft:container" to ListTag(stacks.mapIndexed { slot, s ->
            CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to s))
        }),
    ))

    private fun shulkerContents(box: CompoundTag): List<CompoundTag> =
        box.getCompound("components").getList<CompoundTag>("minecraft:container")
            .map { it.getCompound("item") }

    @Test
    fun packsHundredStoneIntoTwoSlots() {
        val result = packIntoBarrels(listOf(item("minecraft:stone", 100)))

        assertEquals(1, result.barrels.size)
        val barrel = result.barrels[0]
        assertEquals(1, barrel.items.size)
        val stacks = shulkerContents(barrel.items[0])
        assertEquals(2, stacks.size)
        assertEquals(listOf(64, 36), stacks.map { it.getInt("count") })
        assertTrue(stacks.all { it.getString("id") == "minecraft:stone" })
        assertEquals(100, result.totalItems)
    }

    @Test
    fun respectsMaxStackSizeForTools() {
        val result = packIntoBarrels(listOf(item("minecraft:diamond_sword", 3)))
        val stacks = shulkerContents(result.barrels[0].items[0])
        assertEquals(3, stacks.size)
        assertTrue(stacks.all { it.getInt("count") == 1 && it.getString("id") == "minecraft:diamond_sword" })
    }

    @Test
    fun filledShulkerPassesThroughWithoutNesting() {
        val inner = item("minecraft:stone", 64)
        val filled = item("minecraft:shulker_box", 1, containerContents(inner))

        val result = packIntoBarrels(listOf(filled))

        assertEquals(1, result.barrels.size)
        assertEquals(1, result.barrels[0].items.size)
        val placed = result.barrels[0].items[0]
        assertEquals("minecraft:shulker_box", placed.getString("id"))
        // Its contents are preserved verbatim; it was not wrapped in another box.
        assertEquals(64, shulkerContents(placed)[0].getInt("count"))
        assertEquals(1, result.totalShulkerBoxes)
    }

    @Test
    fun spillsBarrelsAtTwentySeven() {
        val shulkers = (1..28).map { item("minecraft:shulker_box", 1) }
        val result = packIntoBarrels(shulkers)

        assertEquals(2, result.barrels.size)
        assertEquals(27, result.barrels[0].items.size)
        assertEquals(1, result.barrels[1].items.size)
        assertEquals(0, result.barrels[0].x)
        assertEquals(0, result.barrels[0].z)
        assertEquals(2, result.barrels[1].x)
        assertEquals(0, result.barrels[1].z)
    }

    @Test
    fun preservesNbtVariantsSeparately() {
        val plain = item("minecraft:stone", 10)
        val named = item("minecraft:stone", 5, CompoundTag(mutableMapOf(
            "minecraft:custom_name" to StringTag("Rock"),
        )))
        val result = packIntoBarrels(listOf(plain, named))
        val stacks = shulkerContents(result.barrels[0].items[0])
        assertEquals(2, stacks.size)
        val plainStack = stacks.single { !it.containsKey("components") }
        val namedStack = stacks.single { it.containsKey("components") }
        assertEquals(10, plainStack.getInt("count"))
        assertEquals(5, namedStack.getInt("count"))
        assertEquals("Rock", namedStack.getCompound("components").getString("minecraft:custom_name"))
    }

    @Test
    fun packingIsLossless() {
        val items = listOf(
            item("minecraft:stone", 100),
            item("minecraft:diamond_sword", 3),
            item("minecraft:ender_pearl", 20),
        )
        val result = packIntoBarrels(items)
        val total = result.barrels.flatMap { barrel -> barrel.items.flatMap(::shulkerContents) }
            .sumOf { it.getInt("count") }
        assertEquals(123, total)
        assertEquals(123, result.totalItems)
    }
}
