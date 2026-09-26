package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.terrain.TerrainCache
import org.bukkit.World

class WorldMap(
    val world: World,
    val cache: TerrainCache,
    val ceiling: Int?,
    val rgb: Boolean,
    val brightness: Double,
)
