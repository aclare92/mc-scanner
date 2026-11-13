package de.skyrising.mc.scanner

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorldLayoutTest {

    @Test
    fun newLayoutDimensionsAndPlayers() {
        val root = Files.createTempDirectory("mc-scanner-new")
        try {
            val region = root.resolve("dimensions/minecraft/overworld/region")
            Files.createDirectories(region)
            Files.createFile(region.resolve("r.0.0.mca"))
            val players = root.resolve("players/data")
            Files.createDirectories(players)
            Files.createFile(players.resolve("00000000-0000-0000-0000-000000000001.dat"))

            val haystack = getHaystack(root)
            assertEquals(2, haystack.size)
            assertTrue(haystack.count { it is RegionFile } == 1)
            assertTrue(haystack.count { it is PlayerFile } == 1)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun oldLayoutDimensionsAndPlayers() {
        val root = Files.createTempDirectory("mc-scanner-old")
        try {
            Files.createDirectories(root.resolve("region"))
            Files.createFile(root.resolve("region/r.0.0.mca"))
            Files.createDirectories(root.resolve("DIM-1/region"))
            Files.createFile(root.resolve("DIM-1/region/r.1.1.mca"))
            val players = root.resolve("playerdata")
            Files.createDirectories(players)
            Files.createFile(players.resolve("00000000-0000-0000-0000-000000000001.dat"))

            val haystack = getHaystack(root)
            assertEquals(3, haystack.size)
            assertTrue(haystack.count { it is RegionFile } == 2)
            assertTrue(haystack.count { it is PlayerFile } == 1)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
