package de.skyrising.mc.scanner

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-item maximum stack size (1, 16 or 64) for packing. Only non-64 items are
 * stored; everything else defaults to 64. Values are never written into NBT —
 * the game derives `minecraft:max_stack_size` from the item id.
 *
 * Generated from `PrismarineJS/minecraft-data` for 1.21.11 (the owner's source
 * world). Regenerate for a newer source version; items added after 1.21.11 fall
 * back to 64, which is wrong for 26.x one-stack items.
 */
private object MaxStackSizes {
    val map: Map<String, Int> = run {
        val json = Json.decodeFromString<JsonObject>(
            MaxStackSizes::class.java.getResourceAsStream("/max_stack_size.json")!!.reader().readText()
        )
        json.mapValues { it.value.jsonPrimitive.int }
    }
}

/** The item's maximum stack size (1, 16, or 64). Unknown ids default to 64. */
fun maxStackSize(id: Identifier): Int = maxStackSize(id.toString())

/** The item's maximum stack size (1, 16, or 64). Unknown ids default to 64. */
fun maxStackSize(id: String): Int = MaxStackSizes.map[id] ?: 64
