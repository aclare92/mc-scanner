package de.skyrising.mc.scanner

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExportItemsTest {

    private val ver = DataVersion.COMPONENTS_REWORK

    private fun item(id: String, count: Int = 1, components: CompoundTag? = null) =
        CompoundTag(mutableMapOf(
            "id" to StringTag(id),
            "count" to IntTag(count),
        ).apply { if (components != null) this["components"] = components })

    private fun container(vararg stacks: CompoundTag) = CompoundTag(mutableMapOf(
        "minecraft:container" to ListTag(stacks.mapIndexed { slot, s ->
            CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to s))
        }),
    ))

    @Test
    fun removesExcludedIdsAtTopLevelAndInsideContainers() {
        val box = item("minecraft:shulker_box", 1, CompoundTag(mutableMapOf(
            "minecraft:container" to ListTag(listOf(
                CompoundTag(mutableMapOf("slot" to IntTag(0), "item" to item("minecraft:barrier"))),
                CompoundTag(mutableMapOf("slot" to IntTag(1), "item" to item("minecraft:stone"))),
            )),
        )))

        val filtered = filterExcludedItems(listOf(item("minecraft:command_block"), box))

        assertEquals(1, filtered.size)
        val kept = filtered.single()
        assertEquals("minecraft:shulker_box", kept.getString("id"))
        val contents = kept.getCompound("components").getList<CompoundTag>("minecraft:container")
        assertEquals(listOf("minecraft:stone"), contents.map { it.getCompound("item").getString("id") })
    }

    @Test
    fun keepsPreservedContainerEvenWhenItsOnlyContentIsExcluded() {
        val named = item("minecraft:gray_shulker_box", 1, CompoundTag(mutableMapOf(
            "minecraft:custom_name" to StringTag("Kit"),
            "minecraft:container" to ListTag(listOf(
                CompoundTag(mutableMapOf("slot" to IntTag(0), "item" to item("minecraft:structure_void"))),
            )),
        )))

        val filtered = filterExcludedItem(named)

        assertNotNull(filtered)
        assertEquals("Kit", filtered.getCompound("components").getString("minecraft:custom_name"))
        assertEquals(0, filtered.getCompound("components").getList<CompoundTag>("minecraft:container").size)
    }

    @Test
    fun filtersBundleContents() {
        val bundle = item("minecraft:bundle", 1, CompoundTag(mutableMapOf(
            "minecraft:bundle_contents" to ListTag(listOf(
                item("minecraft:stone"),
                item("minecraft:debug_stick"),
            )),
        )))

        val filtered = filterExcludedItem(bundle)

        assertNotNull(filtered)
        val contents = filtered.getCompound("components").getList<CompoundTag>("minecraft:bundle_contents")
        assertEquals(listOf("minecraft:stone"), contents.map { it.getString("id") })
    }

    @Test
    fun canonicalInventorySlotsMapArmourAndOffhand() {
        assertEquals(36, canonicalInventorySlot(100))
        assertEquals(39, canonicalInventorySlot(103))
        assertEquals(40, canonicalInventorySlot(-106))
        assertEquals(40, canonicalInventorySlot(40))
        assertNull(canonicalInventorySlot(200))
    }

    @Test
    fun dumpPlayerItemsKeepsEmptyBoxesAndRecordsSlots() {
        val uuid = UUID.randomUUID()
        val source = ItemSource(
            PlayerInventory(uuid, false),
            ListTag(listOf(
                CompoundTag(mutableMapOf(
                    "Slot" to IntTag(5),
                    "id" to StringTag("minecraft:shulker_box"),
                    "count" to IntTag(1),
                )),
                CompoundTag(mutableMapOf(
                    "Slot" to IntTag(100),
                    "id" to StringTag("minecraft:diamond_helmet"),
                    "count" to IntTag(1),
                )),
            )),
            ver,
        )

        val items = dumpPlayerItems(source)

        assertEquals(2, items.size)
        assertEquals(5, items[0].slot)
        assertEquals(36, items[1].slot)
        assertEquals("minecraft:shulker_box", items[0].item.getString("id"))
        assertNull(items[0].item["Slot"])
    }

    @Test
    fun rawExportKeepsEmptyBoxesUntilUnpacking() {
        val empty = item("minecraft:shulker_box")
        val namedEmpty = item("minecraft:gray_shulker_box", 1, CompoundTag(mutableMapOf(
            "minecraft:custom_name" to StringTag("Kit"),
        )))
        val raw = dumpItemStacksRaw(ListTag(listOf(empty, namedEmpty)))

        assertEquals(2, raw.size)
        val totals = countItems(raw, ver)
        assertEquals(1L, totals["minecraft:shulker_box"])
        assertEquals(1L, totals["minecraft:gray_shulker_box"])
        assertEquals(listOf(namedEmpty), unpackPlainBoxes(raw))
    }

    @Test
    fun dumpPlayerItemsReadsEnderChestSource() {
        val uuid = UUID.randomUUID()
        val source = ItemSource(
            PlayerInventory(uuid, true),
            ListTag(listOf(item("minecraft:stone", 3))),
            ver,
        )

        val items = dumpPlayerItems(source)

        assertEquals(PlayerSource.ENDER_CHEST, items.single().source)
        assertEquals(0, items.single().slot)
    }
}
