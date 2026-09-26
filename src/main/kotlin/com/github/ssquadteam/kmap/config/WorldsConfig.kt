package com.github.ssquadteam.kmap.config

import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.craftbukkit.block.CraftBlockType
import net.minecraft.world.level.block.Block
import java.io.File

class WorldEntry(
    val rgbTextured: Boolean?,
    val biomeTint: Boolean,
    val surfaceBrightness: Int,
    val skipDecoration: Boolean,
    val skipBlocks: Set<Block>,
    val ceiling: Int?,
    val discoverRadiusChunks: Int?,
    val saveWorldColors: Boolean?,
)

class WorldsConfig(private val file: File) {
    private val entries = HashMap<String, WorldEntry>()

    fun entry(name: String): WorldEntry? = entries[name]

    fun load() {
        entries.clear()
        if (!file.isFile) {
            file.parentFile.mkdirs()
            file.writeText(HEADER + "worlds: {}\n")
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("worlds") ?: return
        for (name in root.getKeys(false)) {
            val s = root.getConfigurationSection(name) ?: continue
            val skip = s.getStringList("skipBlocks").mapNotNull { Material.matchMaterial(it) }.filter { it.isBlock }.map { CraftBlockType.bukkitToMinecraft(it) }.toSet()
            entries[name] = WorldEntry(
                if (s.contains("rgbTextured")) s.getBoolean("rgbTextured") else null,
                s.getBoolean("biomeTint", true),
                s.getInt("surfaceBrightness", 0),
                s.getBoolean("skipDecoration", true),
                skip,
                if (s.contains("ceiling")) s.getInt("ceiling") else null,
                if (s.contains("discoverRadiusChunks")) s.getInt("discoverRadiusChunks") else null,
                if (s.contains("save.worldColors")) s.getBoolean("save.worldColors") else null,
            )
        }
    }

    companion object {
        val HEADER = """
            |# worlds.yml - optional per-world overrides, keyed by the world folder name.
            |# Every world is mapped live as players explore it, with cave layers underground.
            |#
            |# Keys: rgbTextured, biomeTint, surfaceBrightness (-50..50), skipDecoration, skipBlocks,
            |#       ceiling (Y of a roof to map under, e.g. the nether), discoverRadiusChunks, save: {worldColors}
            |# Changes here need /kmap reload.
            |
            |""".trimMargin()
    }
}
