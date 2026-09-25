package com.github.ssquadteam.kmap.world

import com.github.ssquadteam.kmap.config.RenderMode
import com.github.ssquadteam.kmap.terrain.TerrainCache
import org.bukkit.World

class WorldMap(
    val world: World,
    val mode: RenderMode,
    val cache: TerrainCache,
    val ceiling: Int?,
    val rgb: Boolean,
    val brightness: Double,
) {
    @Volatile
    var bakes: List<BakedMap> = emptyList()

    val bake: BakedMap? get() = bakes.firstOrNull()
}
