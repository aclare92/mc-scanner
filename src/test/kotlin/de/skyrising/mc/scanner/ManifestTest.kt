package de.skyrising.mc.scanner

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun manifestListsPlacedItems() {
        val result = packIntoBarrels(listOf(item("minecraft:stone", 100)), ver)
        val rows = manifestRows(result.barrels, ver)
        assertEquals(2, rows.size)
        assertEquals(listOf(64L, 36L), rows.map { it.count })
        assertTrue(rows.all { it.barrelX == 0 && it.barrelZ == 0 && it.shulkerSlot == 0 })
        assertTrue(rows.all { it.itemId == "minecraft:stone" })
    }

    @Test
    fun totalsRecurseIntoContainers() {
        val filled = item("minecraft:shulker_box", 1, container(item("minecraft:stone", 64)))
        val totals = exportTotals(listOf(filled), ver)
        assertEquals(1L, totals["minecraft:shulker_box"])
        assertEquals(64L, totals["minecraft:stone"])
    }

    @Test
    fun reportEquivalentOmitsContainerWithContents() {
        val filled = item("minecraft:shulker_box", 1, container(item("minecraft:stone", 64)))
        val totals = reportEquivalentTotals(listOf(filled), ver)
        assertEquals(64L, totals["minecraft:stone"])
        assertTrue(!totals.containsKey("minecraft:shulker_box"))
    }

    @Test
    fun totalsKeyDamageLikeReport() {
        val damaged = item("minecraft:stone_sword", 1, CompoundTag(
            mutableMapOf("minecraft:damage" to IntTag(39)),
        ))
        assertEquals(mapOf("minecraft:stone_sword.39" to 1L), exportTotals(listOf(damaged), ver))
    }

    @Test
    fun totalsDoNotCountGeneratedPackaging() {
        val totals = exportTotals(listOf(item("minecraft:stone", 100)), ver)
        assertEquals(mapOf("minecraft:stone" to 100L), totals)
    }

    @Test
    fun diffDetectsMissingAndMismatched() {
        val report = mapOf("minecraft:stone" to 100L, "minecraft:dirt" to 5L)
        val export = mapOf("minecraft:stone" to 90L, "minecraft:sword" to 3L)
        val diff = diffTotals(report, export)
        assertEquals(setOf("minecraft:dirt"), diff.onlyInReport)
        assertEquals(setOf("minecraft:sword"), diff.onlyInExport)
        assertEquals(listOf("minecraft:stone" to (100L to 90L)), diff.countMismatches)
        assertTrue(!diff.isEmpty)
    }

    @Test
    fun identicalTotalsHaveEmptyDiff() {
        val totals = mapOf("minecraft:stone" to 100L)
        assertTrue(diffTotals(totals, totals).isEmpty)
    }

    @Test
    fun writesCsvAndReadsCounts() {
        val dir = createTempDirectory("manifest-test")
        try {
            writeManifest(manifestRows(packIntoBarrels(listOf(item("minecraft:stone", 70)), ver).barrels, ver),
                dir.resolve("manifest.csv"))
            val manifest = Files.readAllLines(dir.resolve("manifest.csv"))
            assertEquals("barrel_x,barrel_z,shulker_slot,item_id,count,nbt_variant", manifest[0])
            assertEquals(3, manifest.size)

            writeTotals(mapOf("minecraft:stone" to 70L), dir.resolve("totals.csv"))
            val counts = dir.resolve("counts.csv")
            Files.writeString(counts, "Type,Total,Number of Inventories\nminecraft:stone,70,1\n")
            assertEquals(mapOf("minecraft:stone" to 70L), readCountsCsv(counts))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
