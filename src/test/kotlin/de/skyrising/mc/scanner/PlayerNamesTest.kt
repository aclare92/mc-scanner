package de.skyrising.mc.scanner

import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerNamesTest {

    private fun write(path: java.nio.file.Path, json: String) {
        path.parent.createDirectories()
        Files.writeString(path, json)
    }

    @Test
    fun resolvesFromServerRootUsercache() {
        val root = createTempDirectory("player-names")
        try {
            val world = root.resolve("server/world").createDirectories()
            val uuid = UUID.randomUUID()
            write(root.resolve("server/usercache.json"), """
                [{"name":"Alice","uuid":"$uuid","expiresOn":"2026-12-16 17:00:00 +0000"}]
            """.trimIndent())

            assertEquals(mapOf(uuid to "Alice"), resolvePlayerNames(world, setOf(uuid)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun prefersUsercacheOverFallbackFiles() {
        val root = createTempDirectory("player-names")
        try {
            val world = root.resolve("server/world").createDirectories()
            val uuid = UUID.randomUUID()
            write(root.resolve("server/usercache.json"), """[{"name":"Cache","uuid":"$uuid"}]""")
            write(root.resolve("server/ops.json"), """[{"name":"Op","uuid":"$uuid","level":4}]""")

            assertEquals(mapOf(uuid to "Cache"), resolvePlayerNames(world, setOf(uuid)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun fallsBackToUuidWhenMissingOrMalformed() {
        val root = createTempDirectory("player-names")
        try {
            val world = root.resolve("server/world").createDirectories()
            val uuid = UUID.randomUUID()
            write(root.resolve("server/usercache.json"), "not json")

            assertTrue(resolvePlayerNames(world, setOf(uuid)).isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun searchesSingleplayerRootFromSavesWorld() {
        val root = createTempDirectory("player-names")
        try {
            val world = root.resolve("saves/World").createDirectories()
            val uuid = UUID.randomUUID()
            write(root.resolve("usercache.json"), """[{"name":"Steve","uuid":"$uuid"}]""")

            assertEquals(mapOf(uuid to "Steve"), resolvePlayerNames(world, setOf(uuid)))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
