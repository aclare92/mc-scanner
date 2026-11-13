package de.skyrising.mc.scanner

object DataVersion {
    const val FLATTENING = 1451 // 17w47a
    const val NON_PACKED_BLOCK_STATES = 2529 // 20w17a
    const val BLOCK_STATES_CONTAINERIZED = 2832 // 1.18_experimental-snapshot-7 + 1
    const val REMOVE_LEVEL_TAG = 2842 // 21w42a + 2
    const val COMPONENTS_REWORK = 3819 // 1.20.5-pre (item components replace NBT tag)
    const val BLOCK_PALETTE_COMPACT = 5009 // 26.3 snapshot 7: palette Name->id, Properties->properties, string/compact entries
}