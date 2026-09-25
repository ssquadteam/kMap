package com.github.ssquadteam.kmap.waypoints

import java.util.UUID

class Waypoint(
    val id: UUID,
    var name: String,
    var world: String,
    var x: Int,
    var y: Int,
    var z: Int,
    var color: Int,
    var icon: String?,
    var visible: Boolean = true,
    var tracked: Boolean = false,
) {
    fun letter(): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    companion object {
        val COLORS = intArrayOf(0xF0C83C, 0xD84838, 0x96C446, 0x78C8DC, 0xDC386E, 0xE6823A, 0xAAC8E6, 0x28282C)
    }
}
