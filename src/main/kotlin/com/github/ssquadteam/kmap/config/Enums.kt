package com.github.ssquadteam.kmap.config

enum class RenderMode(val baked: Boolean, val streamed: Boolean, val hd: Boolean, val discovery: Boolean, val caves: Boolean) {
    WORLD_VIEW(true, false, false, false, false),
    WORLD_VIEW_DISCOVERY(true, false, false, true, false),
    WORLD_VIEW_HD(true, false, true, false, false),
    WORLD_VIEW_HD_DISCOVERY(true, false, true, true, false),
    EXPLORER(false, true, false, true, false),
    DEEP_EXPLORER(false, true, false, true, true),
    ;

    companion object {
        private val legacy = mapOf(
            "ADAPTIVE_DISPLAY_STATIC" to WORLD_VIEW,
            "ADAPTIVE_DISPLAY_DISCOVERY" to WORLD_VIEW_DISCOVERY,
            "PRELOAD_STATIC" to WORLD_VIEW,
            "PRELOAD_DISCOVERY" to WORLD_VIEW_DISCOVERY,
            "WORLDVIEW" to WORLD_VIEW,
            "STREAMED" to EXPLORER,
        )

        fun parse(raw: String?): RenderMode? {
            if (raw.isNullOrBlank()) return null
            val key = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.name == key } ?: legacy[key]
        }
    }
}

enum class BakeSource { HD_SURFACE, SURFACE, PLAN, ROOMS_SURFACE, OFF }

enum class MapModule(val minimap: Boolean, val big: Boolean, val screen: Boolean) {
    MINIMAP_ONLY(true, false, false),
    MINIMAP_AND_BIG(true, true, false),
    MINIMAP_AND_SCREEN(true, false, true),
    BIG_MAP_ONLY(false, true, false),
    SCREEN_MAP_ONLY(false, false, true),
    ;

    companion object {
        fun parse(raw: String?): MapModule? = entries.firstOrNull { it.name.equals(raw?.trim(), true) }
    }
}

enum class MinimapShape { SQUARE, CIRCLE }

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

enum class HostingMode { SELF_HOST, EXTERNAL, NONE }

enum class MergeTarget { NONE, AUTO, NEXO, ITEMSADDER, ORAXEN }

enum class PinLabel { HOVER, ALWAYS, NEVER }

enum class Gesture { SWAP_HANDS, RIGHT_CLICK, LEFT_CLICK, ADVANCEMENTS, COMMAND, DROP }

enum class HandFilter { ANY, EMPTY, MAIN_EMPTY }
