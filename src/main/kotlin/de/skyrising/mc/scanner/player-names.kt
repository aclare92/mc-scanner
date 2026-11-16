package de.skyrising.mc.scanner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

private val PLAYER_NAME_FILES = listOf(
    "usercache.json",
    "whitelist.json",
    "ops.json",
    "banned-players.json",
)

private fun parseUuid(value: String): UUID? {
    try {
        return UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        val hex = value.replace("-", "")
        if (hex.length != 32) return null
        return try {
            UUID.fromString(
                "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                    "${hex.substring(16, 20)}-${hex.substring(20)}"
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

private fun readNameEntries(path: Path): List<Pair<UUID, String>> {
    if (!Files.isRegularFile(path)) return emptyList()
    return try {
        Json.parseToJsonElement(Files.readString(path).removePrefix("\uFEFF")).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val uuid = obj["uuid"]?.jsonPrimitive?.contentOrNull?.let(::parseUuid) ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            uuid to name
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Best-effort UUID -> name lookup from server metadata. The world directory, its
 * parent, and its grandparent are searched. usercache.json is preferred, then
 * whitelist/ops/banned-players. Missing or malformed files are ignored.
 */
fun resolvePlayerNames(worldDir: Path, uuids: Set<UUID>): Map<UUID, String> {
    if (uuids.isEmpty()) return emptyMap()
    val absolute = worldDir.toAbsolutePath().normalize()
    val directories = listOfNotNull(absolute, absolute.parent, absolute.parent?.parent).distinct()
    val names = LinkedHashMap<UUID, String>()
    for (fileName in PLAYER_NAME_FILES) {
        for (directory in directories) {
            for ((uuid, name) in readNameEntries(directory.resolve(fileName))) {
                if (uuid in uuids) names.putIfAbsent(uuid, name)
            }
        }
    }
    return names
}
