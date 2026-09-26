package com.github.ssquadteam.kmap.config

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
