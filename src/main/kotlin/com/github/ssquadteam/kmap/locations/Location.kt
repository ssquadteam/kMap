package com.github.ssquadteam.kmap.locations

import com.github.ssquadteam.kmap.config.PinLabel

class PinAction(val type: String, val value: String, val extra: Map<String, Any?>)

class MapLocation(
    val index: Int,
    val name: String,
    val world: String?,
    val x: Double,
    val y: Double?,
    val z: Double,
    val icon: String?,
    val description: String?,
    val permission: String?,
    val searchable: Boolean,
    val nameColor: Int?,
    val descColor: Int?,
    val tag: String?,
    val tagColor: Int?,
    val pin: Boolean,
    val pinSize: Int,
    val pinIcon: String?,
    val pinLabel: PinLabel,
    val beam: Boolean,
    val actions: List<PinAction>,
)
