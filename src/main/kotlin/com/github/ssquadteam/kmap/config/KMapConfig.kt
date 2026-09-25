package com.github.ssquadteam.kmap.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class EntityMarkerConfig(s: ConfigurationSection?) {
    val enabled = s?.getBoolean("enabled", true) ?: true
    val players = s?.getBoolean("players", true) ?: true
    val mobs = s?.getBoolean("mobs", true) ?: true
    val radiusBlocks = (s?.getInt("radiusBlocks", 44) ?: 44).coerceIn(8, 256)
    val heightRadius = (s?.getInt("heightRadius", 8) ?: 8).coerceIn(1, 128)
    val updateTicks = (s?.getInt("updateTicks", 3) ?: 3).coerceIn(1, 40)
    val animationTicks = (s?.getInt("animationTicks", 10) ?: 10).coerceIn(0, 59)
    val sizePx = (s?.getInt("sizePx", 6) ?: 6).coerceIn(3, 32)
    val maxPerPlayer = (s?.getInt("maxPerPlayer", 24) ?: 24).coerceIn(0, 128)
    val playerImage = s?.getString("playerImage") ?: "player_red.png"
    val mobImage = s?.getString("mobImage") ?: "mob_yellow.png"
}

class BindConfig(val gesture: Gesture, val sneaking: Boolean?, val hand: HandFilter, val command: String?, val material: String?, val customModelData: Int?, val nbtKey: String?, val nbtValue: String?)

class GuiSwitches(s: ConfigurationSection?) {
    val admin = s?.getBoolean("admin", true) ?: true
    val settings = s?.getBoolean("settings", true) ?: true
    val faq = s?.getBoolean("faq", true) ?: true
    val waypoints = s?.getBoolean("waypoints", true) ?: true
    val newWaypoint = s?.getBoolean("newWaypoint", true) ?: true
    val search = s?.getBoolean("search", true) ?: true
    val zoomButtons = s?.getBoolean("zoomButtons", true) ?: true
    val resetView = s?.getBoolean("resetView", true) ?: true
    val fonts = s?.getBoolean("fonts", true) ?: true
}

class KMapConfig(val yaml: YamlConfiguration) {
    val renderMode = RenderMode.parse(yaml.getString("render.mode")) ?: RenderMode.WORLD_VIEW
    val defaultModule = MapModule.parse(yaml.getString("render.defaultModule")) ?: MapModule.MINIMAP_AND_BIG
    val defaultShape = runCatching { MinimapShape.valueOf(yaml.getString("render.defaultMinimapShape", "SQUARE")!!.uppercase()) }.getOrDefault(MinimapShape.SQUARE)
    val defaultCorner = runCatching { Corner.valueOf(yaml.getString("render.defaultMinimapPlacement", "TOP_LEFT")!!.uppercase()) }.getOrDefault(Corner.TOP_LEFT)
    val coordinatesEnabled = yaml.getBoolean("render.coordinatesEnabled", true)
    val openMapOnJoin = yaml.getBoolean("render.openMapOnJoin", true)
    val joinOpenDelayTicks = yaml.getInt("render.joinOpenDelayTicks", 20).coerceAtLeast(0)
    val screenMapGlideTicks = yaml.getInt("render.screenMapGlideTicks", 2).coerceIn(0, 10)
    val screenPanRadiusBlocks = yaml.getInt("render.screenPanRadiusBlocks", 768).coerceIn(64, 30000)
    val largeWorldThreshold = yaml.getInt("render.largeWorldThreshold", 8192)
    val largeWorldMode = RenderMode.parse(yaml.getString("render.largeWorldMode")) ?: RenderMode.DEEP_EXPLORER
    val rgbTextured = yaml.getBoolean("render.rgbTextured", false)
    val discoverRadiusChunks = yaml.getInt("render.discoverRadiusChunks", 6).coerceIn(1, 32)
    val tilesPerFlush = yaml.getInt("render.tilesPerFlush", 4).coerceIn(1, 64)
    val flushIntervalTicks = yaml.getInt("render.flushIntervalTicks", 2).coerceIn(1, 40)
    val prewarmRadiusChunks = yaml.getInt("render.prewarmRadiusChunks", 3).coerceIn(0, 16)
    val minimapSize = yaml.getInt("render.minimapSizePx", 64).coerceIn(32, 160)
    val minimapBlocks = yaml.getInt("render.minimapBlocks", 88).coerceIn(16, 512)
    val bigMapSize = yaml.getInt("render.bigMapSizePx", 300).coerceIn(96, 352)
    val bigMapBlocks = yaml.getInt("render.bigMapBlocks", 360).coerceIn(32, 2048)
    val tabMapEnabled = yaml.getBoolean("render.tabMap.enabled", true)
    val tabMapWidth = yaml.getInt("render.tabMap.widthPx", 300).coerceIn(96, 600)
    val tabMapHeight = yaml.getInt("render.tabMap.heightPx", 170).coerceIn(64, 320)
    val tabMapUpdateTicks = yaml.getInt("render.tabMap.updateTicks", 10).coerceIn(2, 100)
    val caveLayerHeight = yaml.getInt("render.cave.layerHeight", 8).coerceIn(1, 64)
    val caveComputeRadiusChunks = yaml.getInt("render.cave.computeRadiusChunks", 6).coerceIn(1, 16)
    val caveSnapshotsPerTick = yaml.getInt("render.cave.snapshotsPerTick", 24).coerceIn(1, 256)
    val streamRadiusChunks = yaml.getInt("render.streamRadiusChunks", 10).coerceIn(2, 48)
    val defaultSensitivity = yaml.getDouble("cursor.defaultSensitivity", 1.0).coerceIn(1.0, 3.0)
    val cursorDegreesScale = yaml.getDouble("cursor.pixelsPerDegree", 4.0).coerceIn(2.2, 16.0)

    val markerDistanceUpdateTicks = yaml.getInt("markers.distanceUpdateTicks", 20).coerceIn(1, 200)
    val entities = EntityMarkerConfig(yaml.getConfigurationSection("markers.entities"))

    val guis = GuiSwitches(yaml.getConfigurationSection("guis"))

    val bindsEnabled = yaml.getBoolean("binds.enabled", true)
    val binds: List<BindConfig> = (yaml.getMapList("binds.toggleMap")).mapNotNull { m ->
        val gesture = runCatching { Gesture.valueOf((m["gesture"] as? String ?: "SWAP_HANDS").uppercase()) }.getOrNull() ?: return@mapNotNull null
        val item = m["item"] as? Map<*, *>
        BindConfig(
            gesture,
            m["sneaking"] as? Boolean,
            runCatching { HandFilter.valueOf((m["hand"] as? String ?: "ANY").uppercase()) }.getOrDefault(HandFilter.ANY),
            m["command"] as? String,
            item?.get("material") as? String,
            (item?.get("customModelData") as? Number)?.toInt(),
            item?.get("nbtKey") as? String,
            item?.get("nbtValue") as? String,
        )
    }

    val saveDiscovery = yaml.getBoolean("storage.save.discovery", false)
    val saveMarkers = yaml.getBoolean("storage.save.markers", false)
    val saveWorldColors = yaml.getBoolean("storage.save.worldColors", false)

    val packSetOnJoin = yaml.getBoolean("resourcepack.setOnJoin", true)
    val packRequired = yaml.getBoolean("resourcepack.required", false)
    val packPrompt = yaml.getString("resourcepack.prompt", "kMap needs its resource pack to draw the map.")!!
    val packDescription = yaml.getString("resourcepack.description", "kMap - minimap, waypoints & markers")!!
    val packJoinDelayTicks = yaml.getInt("resourcepack.joinDelayTicks", 1).coerceAtLeast(0)
    val hostingMode = runCatching { HostingMode.valueOf(yaml.getString("resourcepack.hosting.mode", "SELF_HOST")!!.uppercase()) }.getOrDefault(HostingMode.SELF_HOST)
    val selfHostBindIp = yaml.getString("resourcepack.hosting.selfHost.bindIp", "0.0.0.0")!!
    val selfHostPort = yaml.getInt("resourcepack.hosting.selfHost.port", 8163)
    val selfHostPublicAddress = yaml.getString("resourcepack.hosting.selfHost.publicAddress", "auto")!!
    val externalUrl = yaml.getString("resourcepack.hosting.external.url", "")!!
    val mergeTarget = runCatching { MergeTarget.valueOf(yaml.getString("resourcepack.merge.target", "NONE")!!.uppercase()) }.getOrDefault(MergeTarget.NONE)
    val mergePacks: List<String> = yaml.getStringList("resourcepack.merge.packs")
    val mergeBakes = yaml.getBoolean("resourcepack.merge.bakes", false)

    val language = yaml.getString("language", "en")!!
    val debug = yaml.getBoolean("debug.logs", false)

    companion object {
        fun load(file: File, defaults: YamlConfiguration): KMapConfig {
            val yaml = YamlConfiguration.loadConfiguration(file)
            yaml.setDefaults(defaults)
            return KMapConfig(yaml)
        }
    }
}
