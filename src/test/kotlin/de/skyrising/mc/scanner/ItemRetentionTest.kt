package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ItemRetentionTest {

    private fun stack(id: String, count: Int, components: CompoundTag? = null) =
        CompoundTag(mutableMapOf(
            "id" to StringTag(id),
            "count" to IntTag(count),
        ).apply { if (components != null) this["components"] = components })

    private fun container(vararg contents: Pair<Int, CompoundTag>) = CompoundTag(mutableMapOf(
        "minecraft:container" to ListTag(contents.map { (slot, item) ->
            CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to item))
        }),
    ))

    @Test
    fun keepsContainerItemsWhole() {
        val shulker = stack("minecraft:shulker_box", 1, container(
            0 to stack("minecraft:stone", 64),
            1 to stack("minecraft:ender_pearl", 16),
        ))
        val plain = stack("minecraft:dirt", 3)

        val out = dumpItemStacksRaw(ListTag(listOf(shulker, plain)))

        assertEquals(2, out.size)
        assertSame(shulker, out[0])
        assertSame(plain, out[1])
        // The shulker's contents stay inside its component rather than being split out.
        val kept = out[0].getCompound("components").getList<CompoundTag>("minecraft:container")
        assertEquals(2, kept.size)
        assertEquals("minecraft:stone", kept[0].getCompound("item").getString("id"))
    }

    @Test
    fun preservesBundleAndCrossbowComponentsVerbatim() {
        val bundle = stack("minecraft:bundle", 1, CompoundTag(mutableMapOf(
            "minecraft:bundle_contents" to ListTag(listOf(stack("minecraft:apple", 4))),
        )))
        val crossbow = stack("minecraft:crossbow", 1, CompoundTag(mutableMapOf(
            "minecraft:charged_projectiles" to ListTag(listOf(stack("minecraft:arrow", 1))),
        )))

        val out = dumpItemStacksRaw(ListTag(listOf(bundle, crossbow)))

        assertEquals(2, out.size)
        assertSame(bundle, out[0])
        assertSame(crossbow, out[1])
        assertEquals(1, out[0].getCompound("components")
            .getList<CompoundTag>("minecraft:bundle_contents").size)
        assertEquals(1, out[1].getCompound("components")
            .getList<CompoundTag>("minecraft:charged_projectiles").size)
    }

    @Test
    fun skipsSlotsWithoutId() {
        val empty = CompoundTag(mutableMapOf("count" to IntTag(1)))
        val real = stack("minecraft:stone", 1)
        assertEquals(listOf(real), dumpItemStacksRaw(ListTag(listOf(empty, real))))
    }
}
