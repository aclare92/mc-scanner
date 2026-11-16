package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class ItemSourceTest {

    private val ver = DataVersion.COMPONENTS_REWORK

    private fun needle(id: String) = ItemType(Identifier.of(id))

    private fun slot(id: String, countKey: String, count: Int) = CompoundTag(
        mutableMapOf("id" to StringTag(id), countKey to IntTag(count))
    )

    private fun pos() = ListTag(listOf(DoubleTag(1.0), DoubleTag(2.0), DoubleTag(3.0)))

    @Test
    fun blockEntitySourcesReadsGenericItems() {
        val be = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:chest"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "Items" to ListTag(listOf(slot("minecraft:stone", "count", 64))),
            )
        )
        val sources = blockEntitySources(be, ver, "overworld")
        assertEquals(1, sources.size)
        assertEquals("minecraft:stone", sources[0].items[0].getString("id"))
    }

    @Test
    fun placedShulkerBoxBecomesOneContainerItem() {
        val be = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "CustomName" to StringTag("Redstone Kit"),
                "Items" to ListTag(listOf(
                    CompoundTag(mutableMapOf(
                        "Slot" to ByteTag(0),
                        "id" to StringTag("minecraft:redstone"),
                        "count" to IntTag(64),
                    )),
                )),
            )
        )
        val sources = blockEntitySources(be, ver, "overworld", "minecraft:red_shulker_box")
        assertEquals(1, sources.size)

        val box = sources[0].items.single()
        assertEquals("minecraft:red_shulker_box", box.getString("id"))
        assertEquals(1, box.getInt("count"))
        val components = box.getCompound("components")
        assertEquals("Redstone Kit", components.getString("minecraft:custom_name"))
        val container = components.getList<CompoundTag>("minecraft:container")
        assertEquals(1, container.size)
        assertEquals(0, container[0].getInt("slot"))
        assertEquals("minecraft:redstone", container[0].getCompound("item").getString("id"))
        assertEquals(64, container[0].getCompound("item").getInt("count"))

        // The report counts the box itself in addition to its contents.
        val result = scanInventory(sources[0].items, emptyList(), true, ver)[0]
        assertEquals(1L, result.getLong(ItemType(Identifier.of("minecraft:red_shulker_box"))))
        assertEquals(64L, result.getLong(ItemType(Identifier.of("minecraft:redstone"))))
    }

    @Test
    fun emptyUnnamedPlacedShulkerBoxIsNotAnItem() {
        val be = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
            )
        )
        assertEquals(emptyList(), blockEntitySources(be, ver, "overworld"))
    }

    @Test
    fun emptyNamedPlacedShulkerBoxIsKept() {
        val be = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "CustomName" to StringTag("Empty Kit"),
            )
        )
        val box = blockEntitySources(be, ver, "overworld").single().items.single()
        assertEquals("Empty Kit", box.getCompound("components").getString("minecraft:custom_name"))
    }

    @Test
    fun emptyStoredShulkerBoxIsRetainedRawButSkippedByReport() {
        val empty = CompoundTag(mutableMapOf(
            "id" to StringTag("minecraft:gray_shulker_box"),
            "count" to IntTag(64),
        ))
        val filled = CompoundTag(mutableMapOf(
            "id" to StringTag("minecraft:gray_shulker_box"),
            "count" to IntTag(1),
            "components" to CompoundTag(mutableMapOf(
                "minecraft:container" to ListTag(listOf(
                    CompoundTag(mutableMapOf("slot" to IntTag(0), "item" to slot("minecraft:stone", "count", 3))),
                )),
            )),
        ))
        val slots = ListTag(listOf(empty, filled, slot("minecraft:dirt", "count", 5)))

        val raw = dumpItemStacksRaw(slots)
        assertEquals(
            listOf("minecraft:gray_shulker_box", "minecraft:gray_shulker_box", "minecraft:dirt"),
            raw.map { it.getString("id") },
        )

        val result = scanInventory(slots, emptyList(), true, ver)[0]
        assertEquals(1L, result.getLong(ItemType(Identifier.of("minecraft:gray_shulker_box"))))
        assertEquals(3L, result.getLong(ItemType(Identifier.of("minecraft:stone"))))
        assertEquals(5L, result.getLong(ItemType(Identifier.of("minecraft:dirt"))))
    }

    @Test
    fun placedShulkerLootTableIsPreserved() {
        val be = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "LootTable" to StringTag("minecraft:chests/end_city_treasure"),
                "LootTableSeed" to LongTag(42L),
            )
        )
        val box = blockEntitySources(be, ver, "overworld").single().items.single()
        val loot = box.getCompound("components").getCompound("minecraft:container_loot")
        assertEquals("minecraft:chests/end_city_treasure", loot.getString("loot_table"))
        assertEquals(42L, (loot["seed"] as LongTag).value)
    }

    @Test
    fun chunkItemSourcesReadsShulkerColourFromChunk() {
        // One section: palette [air, red_shulker_box], index (1, 2, 3) = red box.
        val bits = 4
        val states = LongArray(256)
        val index = 2 * 256 + 3 * 16 + 1
        states[index * bits ushr 6] = 1L shl ((index * bits) and 63)
        val section = CompoundTag(mutableMapOf(
            "Y" to IntTag(0),
            "block_states" to CompoundTag(mutableMapOf(
                "palette" to ListTag(listOf(
                    CompoundTag(mutableMapOf("Name" to StringTag("minecraft:air"))),
                    CompoundTag(mutableMapOf("Name" to StringTag("minecraft:red_shulker_box"))),
                )),
                "data" to LongArrayTag(states),
            )),
        ))
        val chunk = CompoundTag(mutableMapOf(
            "sections" to ListTag(listOf(section)),
            "block_entities" to ListTag(listOf(CompoundTag(mutableMapOf(
                "id" to StringTag("minecraft:shulker_box"),
                "x" to IntTag(1), "y" to IntTag(2), "z" to IntTag(3),
                "Items" to ListTag(listOf(slot("minecraft:redstone", "count", 3))),
            )))),
        ))

        val sources = chunkItemSources(chunk, ver, "overworld")
        assertEquals(1, sources.size)
        assertEquals("minecraft:red_shulker_box", sources[0].items.single().getString("id"))
    }

    @Test
    fun blockEntitySourcesReadsSingleItemHolders() {
        val jukebox = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:jukebox"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "RecordItem" to slot("minecraft:music_disc_cat", "count", 1),
            )
        )
        val lectern = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:lectern"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "Book" to slot("minecraft:written_book", "count", 1),
            )
        )
        assertEquals("minecraft:music_disc_cat", blockEntitySources(jukebox, ver, "overworld")[0].items[0].getString("id"))
        assertEquals("minecraft:written_book", blockEntitySources(lectern, ver, "overworld")[0].items[0].getString("id"))
    }

    @Test
    fun blockEntitySourcesReadsDecoratedPot() {
        val pot = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:decorated_pot"),
                "x" to IntTag(0), "y" to IntTag(0), "z" to IntTag(0),
                "item" to slot("minecraft:diamond", "count", 2),
                "sherds" to CompoundTag(
                    mutableMapOf("front" to slot("minecraft:heart_pottery_sherd", "count", 1))
                ),
            )
        )
        val sources = blockEntitySources(pot, ver, "overworld")
        assertEquals(2, sources.size)
    }

    @Test
    fun entitySourcesReadsMountItemsAndBodyArmor() {
        val minecart = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:chest_minecart"),
                "Pos" to pos(),
                "Items" to ListTag(listOf(slot("minecraft:diamond", "count", 4))),
            )
        )
        assertEquals("minecraft:diamond", entitySources(minecart, ver, "overworld")[0].items[0].getString("id"))

        val wolf = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:wolf"),
                "Pos" to pos(),
                "BodyArmorItems" to ListTag(listOf(slot("minecraft:wolf_armor", "count", 1))),
            )
        )
        assertEquals("minecraft:wolf_armor", entitySources(wolf, ver, "overworld")[0].items[0].getString("id"))
    }

    @Test
    fun entitySourcesReadsDroppedItem() {
        val dropped = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:item"),
                "Pos" to pos(),
                "Item" to slot("minecraft:diamond", "count", 5),
            )
        )
        assertEquals("minecraft:diamond", entitySources(dropped, ver, "overworld")[0].items[0].getString("id"))
    }

    @Test
    fun nestedChargedProjectilesAreScanned() {
        val crossbow = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:crossbow"),
                "count" to IntTag(1),
                "components" to CompoundTag(
                    mutableMapOf("minecraft:charged_projectiles" to ListTag(listOf(slot("minecraft:arrow", "count", 3))))
                ),
            )
        )
        val result = scanInventory(ListTag(listOf(crossbow)), listOf(needle("minecraft:arrow")), false, ver)
        assertEquals(3L, result[0].getLong(needle("minecraft:arrow")))
    }

    @Test
    fun nestedPotDecorationsAreScanned() {
        val pot = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:decorated_pot"),
                "count" to IntTag(1),
                "components" to CompoundTag(
                    mutableMapOf(
                        "minecraft:pot_decorations" to CompoundTag(
                            mutableMapOf("front" to slot("minecraft:heart_pottery_sherd", "count", 1))
                        )
                    )
                ),
            )
        )
        val result = scanInventory(ListTag(listOf(pot)), listOf(needle("minecraft:heart_pottery_sherd")), false, ver)
        assertEquals(1L, result[0].getLong(needle("minecraft:heart_pottery_sherd")))
    }

    @Test
    fun pre263PotDecorationsListIsScanned() {
        // Before 26.3 the component is a list of 4 item IDs; default faces are bricks.
        val pot = CompoundTag(
            mutableMapOf(
                "id" to StringTag("minecraft:decorated_pot"),
                "count" to IntTag(1),
                "components" to CompoundTag(
                    mutableMapOf(
                        "minecraft:pot_decorations" to ListTag(listOf(
                            StringTag("minecraft:angler_pottery_sherd"),
                            StringTag("minecraft:brick"),
                            StringTag("minecraft:archer_pottery_sherd"),
                            StringTag("minecraft:air"),
                        ))
                    )
                ),
            )
        )
        val result = scanInventory(
            ListTag(listOf(pot)),
            listOf(needle("minecraft:angler_pottery_sherd"), needle("minecraft:archer_pottery_sherd")),
            false, ver,
        )
        assertEquals(1L, result[0].getLong(needle("minecraft:angler_pottery_sherd")))
        assertEquals(1L, result[0].getLong(needle("minecraft:archer_pottery_sherd")))
        // Default brick faces are not counted as items.
        assertEquals(0L, result[0].getLong(needle("minecraft:brick")))
    }

    @Test
    fun missingCountDefaultsToOne() {
        val slots = ListTag(listOf(CompoundTag(mutableMapOf("id" to StringTag("minecraft:stone")))))
        assertEquals(1L, scanInventory(slots, listOf(needle("minecraft:stone")), false, ver)[0].getLong(needle("minecraft:stone")))
        assertEquals(1L, scanInventory(slots, emptyList(), true, ver)[0].getLong(needle("minecraft:stone")))
    }
}
