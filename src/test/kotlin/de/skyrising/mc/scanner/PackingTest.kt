package de.skyrising.mc.scanner

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackingTest {

    private val ver = DataVersion.COMPONENTS_REWORK

    private fun item(id: String, count: Int, components: CompoundTag? = null) =
        CompoundTag(mutableMapOf(
            "id" to StringTag(id),
            "count" to IntTag(count),
        ).apply { if (components != null) this["components"] = components })

    private fun container(vararg stacks: CompoundTag) = CompoundTag(mutableMapOf(
        "minecraft:container" to ListTag(stacks.mapIndexed { slot, s ->
            CompoundTag(mutableMapOf("slot" to IntTag(slot), "item" to s))
        }),
    ))

    private fun shulkerContents(box: CompoundTag): List<CompoundTag> =
        box.getCompound("components").getList<CompoundTag>("minecraft:container")
            .map { it.getCompound("item") }

    private fun sourceBox(
        id: String = "minecraft:gray_shulker_box",
        name: String? = null,
        contents: List<CompoundTag> = listOf(item("minecraft:stone", 1)),
    ): CompoundTag {
        val components = mutableMapOf<String, Tag>()
        if (contents.isNotEmpty()) components["minecraft:container"] =
            container(*contents.toTypedArray()).getValue("minecraft:container")
        if (name != null) components["minecraft:custom_name"] = StringTag(name)
        return CompoundTag(mutableMapOf(
            "id" to StringTag(id),
            "count" to IntTag(1),
        ).apply { if (components.isNotEmpty()) this["components"] = CompoundTag(components) })
    }

    private fun playerItem(uuid: UUID, source: PlayerSource, slot: Int, item: CompoundTag = item("minecraft:stone", 1)) =
        PlayerItem(uuid, source, slot, item)

    @Test
    fun unpacksPlainBoxesRecursivelyAndKeepsIdentityBoxes() {
        val innerPlain = sourceBox(contents = listOf(item("minecraft:dirt", 5)))
        val outerPlain = sourceBox(contents = listOf(innerPlain, item("minecraft:stone", 2)))
        val identity = sourceBox(name = "Kit", contents = listOf(item("minecraft:diamond", 1)))
        val emptyPlain = sourceBox(contents = emptyList())

        val unpacked = unpackPlainBoxes(listOf(outerPlain, identity, emptyPlain))

        assertEquals(listOf("minecraft:dirt", "minecraft:stone", "minecraft:gray_shulker_box"),
            unpacked.map { it.getString("id") })
        assertEquals("Kit", unpacked[2].getCompound("components").getString("minecraft:custom_name"))
    }

    @Test
    fun bulkGridSplitsOneVariantIntoGreyBulkReadyBoxes() {
        val packed = packNonPlayer(listOf(item("minecraft:stone", 27 * 64)))

        assertEquals(1, packed.bulkBarrels.size)
        assertTrue(packed.remainderBarrels.isEmpty())
        val box = packed.bulkBarrels[0].items.single()
        assertEquals("minecraft:gray_shulker_box", box.getString("id"))
        assertEquals(27, shulkerContents(box).size)
        assertTrue(shulkerContents(box).all { it.getInt("count") == 64 })
    }

    @Test
    fun bulkGridNeverMixesVariantsAndRemainderMixesFreely() {
        val plain = item("minecraft:stone", 10)
        val named = item("minecraft:stone", 5, CompoundTag(mutableMapOf(
            "minecraft:custom_name" to StringTag("Rock"),
        )))

        val packed = packNonPlayer(listOf(plain, named))

        assertTrue(packed.bulkBarrels.isEmpty())
        val box = packed.remainderBarrels.single().items.single()
        assertEquals(2, shulkerContents(box).size)
        assertEquals("minecraft:shulker_box", box.getString("id"))
    }

    @Test
    fun remainderPacksDifferentIdsIntoOneBox() {
        val packed = packNonPlayer(listOf(
            item("minecraft:stone", 100),
            item("minecraft:dirt", 100),
        ))

        assertTrue(packed.bulkBarrels.isEmpty())
        val box = packed.remainderBarrels.single().items.single()
        val stacks = shulkerContents(box)
        assertEquals(4, stacks.size)
        assertEquals(setOf("minecraft:stone", "minecraft:dirt"), stacks.map { it.getString("id") }.toSet())
        assertEquals(listOf(36L, 36L, 64L, 64L), stacks.map { itemCount(it, ver) }.sorted())
    }

    @Test
    fun unstackablesShareOneGridAcrossIds() {
        val packed = packNonPlayer(listOf(
            item("minecraft:diamond_sword", 2),
            item("minecraft:trident", 1),
        ))

        assertTrue(packed.remainderBarrels.isEmpty())
        val box = packed.unstackableBarrels.single().items.single()
        assertEquals(3, shulkerContents(box).size)
        assertTrue(shulkerContents(box).all { it.getInt("count") == 1 })
    }

    @Test
    fun identityBoxesStayWholeInTheIdentityGrid() {
        val named = sourceBox(name = "Tools")
        val packed = packNonPlayer(listOf(named))

        assertTrue(packed.bulkBarrels.isEmpty())
        assertTrue(packed.remainderBarrels.isEmpty())
        assertTrue(packed.unstackableBarrels.isEmpty())
        val box = packed.identityBarrels.single().items.single()
        assertEquals("Tools", box.getCompound("components").getString("minecraft:custom_name"))
    }

    @Test
    fun gridsStartAtTheRequestedRowAndKeepTwoRowGaps() {
        val packed = packNonPlayer(
            listOf(
                sourceBox(name = "Kit"),
                item("minecraft:stone", 27 * 64),
                item("minecraft:dirt", 100),
                item("minecraft:diamond_sword", 1),
            ),
            startZ = 3,
        )

        assertEquals(3, packed.identityBarrels.single().z)
        assertEquals(6, packed.bulkBarrels.single().z)
        assertEquals(9, packed.remainderBarrels.single().z)
        assertEquals(12, packed.unstackableBarrels.single().z)
    }

    @Test
    fun playerSectionsMapInventoryAndEnderSlots() {
        val uuid = UUID.randomUUID()
        val packed = packPlayerSections(
            listOf(
                playerItem(uuid, PlayerSource.INVENTORY, 0),
                playerItem(uuid, PlayerSource.INVENTORY, 26),
                playerItem(uuid, PlayerSource.INVENTORY, 27),
                playerItem(uuid, PlayerSource.INVENTORY, 40),
                playerItem(uuid, PlayerSource.ENDER_CHEST, 0),
                playerItem(uuid, PlayerSource.ENDER_CHEST, 26),
            ),
            mapOf(uuid to "Alice"),
        )

        val section = packed.sections.single()
        assertEquals("Alice", section.displayName)
        assertEquals(3, section.barrels.size)
        assertEquals(listOf(0, 26), section.barrels[0].slots)
        assertEquals(listOf(0, 13), section.barrels[1].slots)
        assertEquals(listOf(0, 26), section.barrels[2].slots)
        assertEquals(PlacedSign(0, 0, 1, listOf("Alice")), section.sign)
        assertEquals(3, packed.nextZ)
    }

    @Test
    fun playerSectionsPreserveEmptySlotsAndSkipEmptySections() {
        val uuid = UUID.randomUUID()
        val packed = packPlayerSections(
            listOf(playerItem(uuid, PlayerSource.INVENTORY, 5)),
            emptyMap(),
        )

        val section = packed.sections.single()
        assertEquals(1, section.barrels.size)
        assertEquals(listOf(5), section.barrels[0].slots)
        assertEquals(uuid.toString(), section.displayName)
        assertEquals(uuid.toString().chunked(12), section.sign!!.lines)
    }

    @Test
    fun playerSectionsSortByNameAndSeparatePlayersByOneBarrel() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        val packed = packPlayerSections(
            listOf(
                playerItem(bob, PlayerSource.INVENTORY, 0),
                playerItem(alice, PlayerSource.INVENTORY, 0),
            ),
            mapOf(alice to "Alice", bob to "Bob"),
        )

        assertEquals(listOf("Alice", "Bob"), packed.sections.map { it.displayName })
        assertEquals(0, packed.sections[0].barrels.single().x)
        assertEquals(2, packed.sections[1].barrels.single().x)
        assertEquals(0, packed.sections[0].sign!!.x)
        assertEquals(2, packed.sections[1].sign!!.x)
    }

    @Test
    fun namedPlayersSortBeforeUnknownUuids() {
        val named = UUID.fromString("0052a414-0b81-4d2c-85b7-a675c988340f")
        val unknown = UUID.fromString("057d7582-cab2-47d4-9992-1677df77ce93")
        val packed = packPlayerSections(
            listOf(
                playerItem(named, PlayerSource.INVENTORY, 0),
                playerItem(unknown, PlayerSource.INVENTORY, 0),
            ),
            mapOf(named to "Alice"),
        )

        assertEquals(listOf("Alice", unknown.toString()), packed.sections.map { it.displayName })
        assertEquals(0, packed.sections[0].barrels.single().x)
        assertEquals(2, packed.sections[1].barrels.single().x)
    }

    @Test
    fun playerCarriedBoxesAreNotUnpacked() {
        val uuid = UUID.randomUUID()
        val box = sourceBox(name = "Kit", contents = listOf(item("minecraft:diamond", 64)))
        val packed = packPlayerSections(
            listOf(playerItem(uuid, PlayerSource.INVENTORY, 0, box)),
            mapOf(uuid to "Alice"),
        )

        val placed = packed.sections.single().barrels.single().items.single()
        assertEquals("minecraft:gray_shulker_box", placed.getString("id"))
        assertEquals(1, shulkerContents(placed).size)
        assertNull(placed["Slot"])
    }
}
