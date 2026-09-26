package com.github.ssquadteam.kmap.session

import com.github.ssquadteam.kmap.config.Corner
import com.github.ssquadteam.kmap.config.KMapConfig
import com.github.ssquadteam.kmap.config.MapModule
import com.github.ssquadteam.kmap.config.MinimapShape
import org.bukkit.configuration.file.YamlConfiguration

class PlayerSettings(
    var module: MapModule,
    var shape: MinimapShape,
    var corner: Corner,
    var zoom: Int,
    var coords: Boolean,
    var showMobs: Boolean,
    var showPlayers: Boolean,
    var minimap: Boolean,
    var sensitivity: Double,
    var screenZoom: Int,
    var zoomTouched: Boolean,
    var cursor: Int,
    var showGuild: Boolean = true,
) {
    fun toYaml(): YamlConfiguration {
        val y = YamlConfiguration()
        y.set("module", module.name)
        y.set("shape", shape.name)
        y.set("corner", corner.name)
        y.set("zoom", zoom)
        y.set("coords", coords)
        y.set("showMobs", showMobs)
        y.set("showPlayers", showPlayers)
        y.set("minimap", minimap)
        y.set("sensitivity", sensitivity)
        y.set("screenZoom", screenZoom)
        y.set("zoomTouched", zoomTouched)
        y.set("cursor", cursor)
        y.set("showGuild", showGuild)
        return y
    }

    companion object {
        const val CURSORS = 8

        fun defaults(cfg: KMapConfig) = PlayerSettings(cfg.defaultModule, cfg.defaultShape, cfg.defaultCorner, 3, cfg.coordinatesEnabled, true, true, true, cfg.defaultSensitivity, 6, false, 0)

        fun load(y: YamlConfiguration, cfg: KMapConfig): PlayerSettings {
            val d = defaults(cfg)
            return PlayerSettings(
                MapModule.parse(y.getString("module")) ?: d.module,
                runCatching { MinimapShape.valueOf(y.getString("shape")!!) }.getOrDefault(d.shape),
                runCatching { Corner.valueOf(y.getString("corner")!!) }.getOrDefault(d.corner),
                y.getInt("zoom", d.zoom).coerceIn(0, 6),
                y.getBoolean("coords", d.coords),
                y.getBoolean("showMobs", true),
                y.getBoolean("showPlayers", true),
                y.getBoolean("minimap", true),
                y.getDouble("sensitivity", d.sensitivity).coerceIn(1.0, 3.0),
                y.getInt("screenZoom", d.screenZoom).coerceIn(0, 11),
                y.getBoolean("zoomTouched", false),
                y.getInt("cursor", 0).coerceIn(0, CURSORS - 1),
                y.getBoolean("showGuild", true),
            )
        }
    }
}
