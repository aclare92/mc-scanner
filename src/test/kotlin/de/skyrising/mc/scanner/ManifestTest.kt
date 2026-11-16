package de.skyrising.mc.scanner

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTest {

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

    private fun sourceBox(name: String? = null, contents: List<CompoundTag> = listOf(item("minecraft:stone", 64))): CompoundTag {
        val components = mutableMapOf<String, Tag>()
        if (contents.isNotEmpty()) components["minecraft:container"] =
            container(*contents.toTypedArray()).getValue("minecraft:container")
        if (name != null) components["minecraft:custom_name"] = StringTag(name)
        return CompoundTag(mutableMapOf(
            "id" to StringTag("minecraft:gray_shulker_box"),
            "count" to IntTag(1),
        ).apply { if (components.isNotEmpty()) this["components"] = CompoundTag(components) })
    }

    private fun layoutForNonPlayer(items: List<CompoundTag>) = ExportLayout(
        PlayerPacking(emptyList()),
        packNonPlayer(items, ver),
    )

    @Test
    fun generatedPackagingGetsNoBoxRow() {
        val layout = layoutForNonPlayer(listOf(item("minecraft:stone", 27 * 64)))
        val rows = buildManifestRows(layout, ver)

        assertEquals(27, rows.size)
        assertTrue(rows.all { it.kind == ManifestKind.ITEM && it.grid == ExportGrid.BULK })
        assertEquals((0 until 27).toList(), rows.map { it.boxSlot })
        assertEquals(listOf(0), rows.map { it.barrelSlot }.distinct())
        assertEquals(listOf(0), rows.map { it.barrelX }.distinct())
    }

    @Test
    fun remainderRowsRecordBoxSlotAndBarrelSlot() {
        val layout = layoutForNonPlayer(listOf(item("minecraft:stone", 100)))
        val rows = buildManifestRows(layout, ver)

        assertEquals(2, rows.size)
        assertEquals(listOf(0, 1), rows.map { it.boxSlot })
        assertEquals(listOf(64L, 36L), rows.map { it.count })
        assertTrue(rows.all { it.grid == ExportGrid.REMAINDER })
    }

    @Test
    fun identityBoxGetsBoxRowAndContentRows() {
        val layout = layoutForNonPlayer(listOf(sourceBox(name = "Kit")))
        val rows = buildManifestRows(layout, ver)

        assertEquals(2, rows.size)
        val box = rows.single { it.kind == ManifestKind.BOX }
        val content = rows.single { it.kind == ManifestKind.ITEM }
        assertEquals(ExportGrid.IDENTITY, box.grid)
        assertNull(box.boxSlot)
        assertTrue(box.nbt.contains("Kit"))
        assertEquals(ExportGrid.IDENTITY, content.grid)
        assertEquals(0, content.boxSlot)
        assertEquals("minecraft:stone", content.itemId)
    }

    @Test
    fun playerRowsCarrySourceAndSourceSlot() {
        val uuid = UUID.randomUUID()
        val layout = ExportLayout(
            packPlayerSections(
                listOf(PlayerItem(uuid, PlayerSource.INVENTORY, 5, item("minecraft:stone", 3))),
                mapOf(uuid to "Alice"),
            ),
            packNonPlayer(emptyList(), ver, startZ = 3),
        )
        val row = buildManifestRows(layout, ver).single()

        assertEquals(ManifestKind.ITEM, row.kind)
        assertEquals(ExportGrid.PLAYER, row.grid)
        assertEquals("Alice", row.player)
        assertEquals(PlayerSource.INVENTORY, row.source)
        assertEquals(5, row.sourceSlot)
        assertNull(row.boxSlot)
        assertEquals(3L, row.count)
    }

    @Test
    fun playerCarriedBoxGetsBoxRowAndContents() {
        val uuid = UUID.randomUUID()
        val box = sourceBox(name = "Kit")
        val layout = ExportLayout(
            packPlayerSections(
                listOf(PlayerItem(uuid, PlayerSource.ENDER_CHEST, 2, box)),
                mapOf(uuid to "Alice"),
            ),
            packNonPlayer(emptyList(), ver, startZ = 3),
        )
        val rows = buildManifestRows(layout, ver)

        val boxRow = rows.single { it.kind == ManifestKind.BOX }
        val contentRow = rows.single { it.kind == ManifestKind.ITEM }
        assertEquals(ExportGrid.PLAYER, boxRow.grid)
        assertEquals("Alice", boxRow.player)
        assertEquals(PlayerSource.ENDER_CHEST, boxRow.source)
        assertEquals(2, boxRow.sourceSlot)
        assertEquals(ExportGrid.PLAYER, contentRow.grid)
        assertEquals("Alice", contentRow.player)
        assertEquals(0, contentRow.boxSlot)
    }

    @Test
    fun totalsCountEmptyBoxesAndReportDropped() {
        val empty = item("minecraft:shulker_box", 1)
        val scanned = countItems(listOf(empty), ver)
        assertEquals(mapOf("minecraft:shulker_box" to 1L), scanned)

        val totals = buildTotals(scanned, emptyMap()).single()
        assertEquals(1L, totals.scanned)
        assertEquals(0L, totals.exported)
        assertEquals(1L, totals.dropped)
    }

    @Test
    fun writesManifestAndTotalsWithNewHeaders() {
        val dir = createTempDirectory("manifest-test")
        try {
            val uuid = UUID.randomUUID()
            val layout = ExportLayout(
                packPlayerSections(
                    listOf(PlayerItem(uuid, PlayerSource.INVENTORY, 0, item("minecraft:stone", 1))),
                    mapOf(uuid to "Alice"),
                ),
                packNonPlayer(emptyList(), ver, startZ = 3),
            )
            val manifest = dir.resolve("manifest.csv")
            writeManifest(buildManifestRows(layout, ver), manifest)
            val lines = Files.readAllLines(manifest)
            assertEquals(
                "kind,grid,player,source,barrel_x,barrel_z,barrel_slot,box_slot,source_slot,item_id,count,nbt_variant",
                lines[0],
            )
            assertEquals(2, lines.size)

            val totals = dir.resolve("totals.csv")
            writeTotals(buildTotals(mapOf("minecraft:stone" to 3L), mapOf("minecraft:stone" to 2L)), totals)
            assertEquals(
                listOf("Type,Scanned,Exported,Dropped", "minecraft:stone,3,2,1"),
                Files.readAllLines(totals),
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
