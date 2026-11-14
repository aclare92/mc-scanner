package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class InventoryTest {

    private fun needle(id: String) = ItemType(Identifier.of(id))

    private fun slot(id: String, countKey: String, count: Int) = CompoundTag(
        mutableMapOf(
            "id" to StringTag(id),
            countKey to IntTag(count),
        )
    )

    @Test
    fun preReworkCountKeyIsRead() {
        val slots = ListTag(listOf(slot("minecraft:stone", "Count", 64)))
        val result = scanInventory(slots, listOf(needle("minecraft:stone")), false, 0)
        assertEquals(64L, result[0].getLong(needle("minecraft:stone")))
    }

    @Test
    fun postReworkCountKeyIsRead() {
        val slots = ListTag(listOf(slot("minecraft:stone", "count", 64)))
        val result = scanInventory(slots, listOf(needle("minecraft:stone")), false, DataVersion.COMPONENTS_REWORK)
        assertEquals(64L, result[0].getLong(needle("minecraft:stone")))
    }

    @Test
    fun preReworkNestedItemsAreScanned() {
        val items = ListTag(listOf(slot("minecraft:diamond", "Count", 3)))
        val shulker = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "Count" to IntTag(1),
                "tag" to CompoundTag(
                    mutableMapOf(
                        "BlockEntityTag" to CompoundTag(mutableMapOf("Items" to items))
                    )
                ),
            )
        )
        val result = scanInventory(ListTag(listOf(shulker)), listOf(needle("minecraft:diamond")), false, 0)
        assertEquals(3L, result[0].getLong(needle("minecraft:diamond")))
    }

    @Test
    fun postReworkContainerComponentIsScanned() {
        val container = ListTag(
            listOf(
                CompoundTag(
                    mutableMapOf(
                        "slot" to IntTag(0),
                        "item" to slot("minecraft:diamond", "count", 2),
                    )
                )
            )
        )
        val shulker = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "count" to IntTag(1),
                "components" to CompoundTag(mutableMapOf("minecraft:container" to container)),
            )
        )
        val result = scanInventory(ListTag(listOf(shulker)), listOf(needle("minecraft:diamond")), false, DataVersion.COMPONENTS_REWORK)
        assertEquals(2L, result[0].getLong(needle("minecraft:diamond")))
    }

    @Test
    fun statsModeCountsContainerAndContents() {
        val container = ListTag(
            listOf(
                CompoundTag(
                    mutableMapOf(
                        "slot" to IntTag(0),
                        "item" to slot("minecraft:diamond", "count", 2),
                    )
                )
            )
        )
        val shulker = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "count" to IntTag(1),
                "components" to CompoundTag(mutableMapOf("minecraft:container" to container)),
            )
        )
        val result = scanInventory(
            ListTag(listOf(shulker)), emptyList(), true, DataVersion.COMPONENTS_REWORK,
        )[0]
        assertEquals(1L, result.getLong(needle("minecraft:shulker_box")))
        assertEquals(2L, result.getLong(needle("minecraft:diamond")))
    }

    @Test
    fun statsModeCountsChargedCrossbowAndProjectiles() {
        val crossbow = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:crossbow"),
                "count" to IntTag(1),
                "components" to CompoundTag(
                    mutableMapOf(
                        "minecraft:charged_projectiles" to ListTag(
                            listOf(slot("minecraft:arrow", "count", 3))
                        )
                    )
                ),
            )
        )
        val result = scanInventory(
            ListTag(listOf(crossbow)), emptyList(), true, DataVersion.COMPONENTS_REWORK,
        )[0]
        assertEquals(1L, result.getLong(needle("minecraft:crossbow")))
        assertEquals(3L, result.getLong(needle("minecraft:arrow")))
    }

    @Test
    fun postReworkBundleContentsAreScanned() {
        val bundle = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:bundle"),
                "count" to IntTag(1),
                "components" to CompoundTag(
                    mutableMapOf(
                        "minecraft:bundle_contents" to ListTag(listOf(slot("minecraft:diamond", "count", 4)))
                    )
                ),
            )
        )
        val result = scanInventory(ListTag(listOf(bundle)), listOf(needle("minecraft:diamond")), false, DataVersion.COMPONENTS_REWORK)
        assertEquals(4L, result[0].getLong(needle("minecraft:diamond")))
    }
}
