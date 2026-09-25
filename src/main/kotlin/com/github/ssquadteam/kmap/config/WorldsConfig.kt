package com.github.ssquadteam.kmap.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.craftbukkit.block.CraftBlockType
import net.minecraft.world.level.block.Block
import java.io.File

class AreaEntry(val name: String, val centerX: Int, val centerZ: Int, val width: Int, val height: Int)

class WorldEntry(
    val name: String,
    val mode: RenderMode,
    val bake: BakeSource,
    val persistBake: Boolean,
    val x: Int,
    val z: Int,
    val sizeX: Int,
    val sizeZ: Int,
    val rgbTextured: Boolean?,
    val biomeTint: Boolean,
    val surfaceBrightness: Int,
    val skipDecoration: Boolean,
    val skipBlocks: Set<Block>,
    val loadAtStart: Boolean,
    val autoRender: Boolean,
    val ceiling: Int?,
    val rotation: Int,
    val discoverRadiusChunks: Int?,
    val areas: List<AreaEntry>,
    val saveMarkers: Boolean?,
    val saveWorldColors: Boolean?,
)

class WorldsConfig(private val file: File) {
    private val entries = HashMap<String, WorldEntry>()
    private var yaml = YamlConfiguration()

    fun entry(name: String): WorldEntry? = entries[name]

    fun all(): Collection<WorldEntry> = entries.values

    fun load(log: (String) -> Unit) {
        entries.clear()
        if (!file.isFile) save()
        yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("worlds") ?: return
        for (name in root.getKeys(false)) {
            val s = root.getConfigurationSection(name) ?: continue
            val rawMode = s.getString("mode")
            val mode = RenderMode.parse(rawMode)
            if (rawMode != null && mode == null) {
                log("Unknown render mode '$rawMode' for world '$name' (code points ${rawMode.codePoints().toArray().joinToString(",")}), using WORLD_VIEW")
            }
            val m = mode ?: RenderMode.WORLD_VIEW
            val defaultBake = if (m.baked) (if (m.hd) BakeSource.HD_SURFACE else BakeSource.SURFACE) else BakeSource.OFF
            val bake = runCatching { BakeSource.valueOf(s.getString("bake")!!.uppercase()) }.getOrDefault(defaultBake)
            val radius = s.getInt("radius", 0)
            val sizeX = s.getInt("sizeX", if (radius > 0) radius * 2 else 1000).coerceIn(64, 16384)
            val sizeZ = s.getInt("sizeZ", if (radius > 0) radius * 2 else 1000).coerceIn(64, 16384)
            val skip = s.getStringList("skipBlocks").mapNotNull { Material.matchMaterial(it) }.filter { it.isBlock }.map { CraftBlockType.bukkitToMinecraft(it) }.toSet()
            val areas = s.getMapList("areas").mapNotNull { a ->
                val n = a["name"] as? String ?: return@mapNotNull null
                AreaEntry(n, (a["centerX"] as? Number)?.toInt() ?: 0, (a["centerZ"] as? Number)?.toInt() ?: 0, ((a["widthBlocks"] as? Number)?.toInt() ?: 256).coerceIn(16, 8192), ((a["heightBlocks"] as? Number)?.toInt() ?: 256).coerceIn(16, 8192))
            }
            entries[name] = WorldEntry(
                name, m, bake, s.getBoolean("persistBake", true), s.getInt("x", 0), s.getInt("z", 0), sizeX, sizeZ,
                if (s.contains("rgbTextured")) s.getBoolean("rgbTextured") else null,
                s.getBoolean("biomeTint", false), s.getInt("surfaceBrightness", 0), s.getBoolean("skipDecoration", true), skip,
                s.getBoolean("loadAtStart", true), s.getBoolean("autoRender", true),
                if (s.contains("ceiling")) s.getInt("ceiling") else null,
                ((s.getInt("rotation", 0) / 90) and 3) * 90,
                if (s.contains("discoverRadiusChunks")) s.getInt("discoverRadiusChunks") else null,
                areas,
                if (s.contains("save.markers")) s.getBoolean("save.markers") else null,
                if (s.contains("save.worldColors")) s.getBoolean("save.worldColors") else null,
            )
        }
    }

    fun register(name: String, sizeX: Int, sizeZ: Int, x: Int, z: Int, rotation: Int, biomeTint: Boolean?) {
        yaml.set("worlds.$name.mode", "WORLD_VIEW_HD")
        yaml.set("worlds.$name.bake", "HD_SURFACE")
        yaml.set("worlds.$name.persistBake", true)
        yaml.set("worlds.$name.x", x)
        yaml.set("worlds.$name.z", z)
        yaml.set("worlds.$name.sizeX", sizeX)
        yaml.set("worlds.$name.sizeZ", sizeZ)
        yaml.set("worlds.$name.rotation", rotation)
        if (biomeTint != null) yaml.set("worlds.$name.biomeTint", biomeTint)
        save()
    }

    fun setAreas(name: String, areas: List<AreaEntry>) {
        yaml.set("worlds.$name.areas", areas.map { mapOf("name" to it.name, "centerX" to it.centerX, "centerZ" to it.centerZ, "widthBlocks" to it.width, "heightBlocks" to it.height) })
        save()
    }

    fun set(path: String, value: Any?) {
        yaml.set(path, value)
        save()
    }

    fun save() {
        file.parentFile.mkdirs()
        if (!yaml.contains("worlds")) yaml.createSection("worlds")
        file.writeText(HEADER + yaml.saveToString())
    }

    companion object {
        val HEADER = """
            |# worlds.yml - one entry per world, keyed by the world folder name.
            |# A world with no entry is not registered and streams as render.largeWorldMode (a live map with fog of war).
            |#
            |# Register a hand-built world with:   /kmap world-register <world> [width] [height] [centerX] [centerZ] [rotation]
            |# That writes mode WORLD_VIEW_HD and bake HD_SURFACE. Restart, and the world bakes into one image, cached forever.
            |#
            |# Keys: mode (WORLD_VIEW, WORLD_VIEW_DISCOVERY, WORLD_VIEW_HD, WORLD_VIEW_HD_DISCOVERY, EXPLORER, DEEP_EXPLORER)
            |#       bake (HD_SURFACE, SURFACE, PLAN, ROOMS_SURFACE, OFF), persistBake, x, z (centre), sizeX, sizeZ (full size in blocks)
            |#       rgbTextured, biomeTint, surfaceBrightness, skipDecoration, skipBlocks, loadAtStart, autoRender, ceiling, rotation
            |#       areas: [{name, centerX, centerZ, widthBlocks, heightBlocks}], save: {markers, worldColors}
            |# Changes here need a restart. /kmap rebake <world> renders a baked world again.
            |
            |""".trimMargin()
    }
}
