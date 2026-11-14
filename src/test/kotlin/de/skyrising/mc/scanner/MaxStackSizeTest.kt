package de.skyrising.mc.scanner

import kotlin.test.Test
import kotlin.test.assertEquals

class MaxStackSizeTest {

    @Test
    fun defaultsTo64() {
        assertEquals(64, maxStackSize("minecraft:stone"))
        assertEquals(64, maxStackSize(Identifier.of("stone")))
    }

    @Test
    fun sixteenStackItems() {
        assertEquals(16, maxStackSize("minecraft:ender_pearl"))
        assertEquals(16, maxStackSize("minecraft:snowball"))
        assertEquals(16, maxStackSize("minecraft:egg"))
        assertEquals(16, maxStackSize("minecraft:oak_sign"))
        assertEquals(16, maxStackSize("minecraft:white_banner"))
        assertEquals(16, maxStackSize("minecraft:honey_bottle"))
        assertEquals(16, maxStackSize("minecraft:written_book"))
    }

    @Test
    fun oneStackItems() {
        assertEquals(1, maxStackSize("minecraft:diamond_sword"))
        assertEquals(1, maxStackSize("minecraft:shulker_box"))
        assertEquals(1, maxStackSize("minecraft:bundle"))
        assertEquals(1, maxStackSize("minecraft:oak_boat"))
        assertEquals(1, maxStackSize("minecraft:white_bed"))
        assertEquals(1, maxStackSize("minecraft:water_bucket"))
    }

    @Test
    fun surprisingBoundaries() {
        // banner is 16, its pattern item is 1
        assertEquals(16, maxStackSize("minecraft:white_banner"))
        assertEquals(1, maxStackSize("minecraft:creeper_banner_pattern"))
        // written book is 16, book & quill is 1
        assertEquals(16, maxStackSize("minecraft:written_book"))
        assertEquals(1, maxStackSize("minecraft:writable_book"))
        // ender eye is 64, ender pearl is 16
        assertEquals(64, maxStackSize("minecraft:ender_eye"))
        assertEquals(16, maxStackSize("minecraft:ender_pearl"))
    }

    @Test
    fun unknownIdsDefaultTo64() {
        assertEquals(64, maxStackSize("minecraft:does_not_exist"))
        assertEquals(64, maxStackSize("somemod:strange_item"))
    }
}
